package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FsPath
import kotlin.io.encoding.Base64

/** The kinds of licence file a PlayStation game can come with. */
enum class LicenceKind {
    /** PS3 `.rap`: 16 bytes, named `<contentId>.rap`; RPCS3 looks for exactly that name in exdata. */
    RAP,

    /** PS3 `.edat` (NPD header): RPCS3 copies these into exdata beside the `.rap` files. */
    EDAT,

    /** Vita `.rif` or `work.bin` (SceNpDrmLicense, content id at 0x10). */
    RIF,

    /** A Vita zRIF: a `.rif` deflated with a fixed dictionary and written in base64 (`KO5i...`). */
    ZRIF,
}

/**
 * One licence Fuse found, and the content it is for. [contentId] is null when nothing in the file
 * says it (a `.rap` named anything else, which RPCS3 won't use under that name).
 */
data class Licence(val kind: LicenceKind, val path: String, val contentId: String?, val zrif: String? = null) {
    val titleId: String? get() = contentId?.takeIf { it.length >= 16 }?.substring(7, 16)?.takeIf { PsPackages.TITLE_ID.matches(it) }
}

object Licences {
    /** zRIF keys in any text (a `.zrif`, `.txt` or NoPayStation's `.tsv`). */
    val ZRIF_IN_TEXT = Regex("KO5i[0-9A-Za-z+/]{40,}={0,2}")

    const val RAP_SIZE = 16L
    const val RIF_SIZE = 512
    private const val MAX_RIF = 1024

    /** Files worth reading for a licence, by extension (or name, for `work.bin`). */
    fun isLicenceFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "work.bin" || FsPath.extension(lower) in LICENCE_EXTENSIONS
    }

    /** Text files that may hold zRIF keys. */
    fun isKeyText(name: String): Boolean = FsPath.extension(name.lowercase()) in KEY_TEXT

    private val LICENCE_EXTENSIONS = setOf("rap", "edat", "rif")
    private val KEY_TEXT = setOf("zrif", "txt", "tsv", "rif64")

    /** A `.rap` licence: its name is the content id, and it must be exactly 16 bytes. */
    fun rap(path: String, sizeBytes: Long): Licence? {
        if (sizeBytes != RAP_SIZE) return null
        val id = FsPath.stem(FsPath.name(path)).uppercase()
        return Licence(LicenceKind.RAP, path, id.takeIf { PsPackages.CONTENT_ID.matches(it) })
    }

    /** The content id a licence's file name says (`UP0001-BLUS30443_00-DEMONSSOULS00000.rap`), when it says one. */
    fun contentIdInName(name: String): String? = FsPath.stem(name).uppercase().takeIf { PsPackages.CONTENT_ID.matches(it) }

    /** The NPD block of an `.edat` or a PSN `EBOOT.BIN` (first 8 KB): its content id, and whether it needs a `.rap`. */
    fun npd(bytes: ByteArray): Npd? {
        val at = indexOf(bytes, NPD_MAGIC)
        if (at < 0 || at + 0x40 > bytes.size) return null
        val licence = Inflate.u32be(bytes, at + 8).toInt()
        val contentId = cString(bytes, at + 16, 0x30)
        if (!PsPackages.CONTENT_ID.matches(contentId)) return null
        return Npd(contentId, licence)
    }

    /** An NPD header: [licenceType] 1 network, 2 local (both need `<contentId>.rap`), 3 free. */
    data class Npd(val contentId: String, val licenceType: Int) {
        val needsRap: Boolean get() = licenceType == 1 || licenceType == 2
    }

    /** The content id inside a Vita licence (`.rif`, `work.bin`), or null when [bytes] aren't one. */
    fun rifContentId(bytes: ByteArray): String? {
        if (bytes.size < 0x40 || bytes.size > MAX_RIF) return null
        return cString(bytes, 0x10, 0x30).takeIf { PsPackages.CONTENT_ID.matches(it) }
    }

    /** The licence a zRIF holds, decoded the way Vita3K and pkg2zip do; null when it isn't a valid zRIF. */
    fun decodeZrif(zrif: String): ByteArray? {
        val raw = try {
            Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(zrif.trim())
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Inflate.zlib(raw, maxOut = MAX_RIF, dictionary = ZRIF_DICTIONARY)?.takeIf { it.size == RIF_SIZE || it.size == MAX_RIF }
    }

    /** The content id a zRIF is for. */
    fun zrifContentId(zrif: String): String? = decodeZrif(zrif)?.let(::rifContentId)

    /**
     * A zRIF for a Vita licence file, which is what Vita3K's `--zrif` takes. Vita3K's own installer
     * makes one from an installed `.rif` the same way (rif2zrif); this one isn't compressed, which
     * every zlib reader accepts.
     */
    fun zrifOf(rif: ByteArray): String? {
        if (rifContentId(rif) == null) return null
        return Base64.Default.encode(Inflate.zlibStored(rif, ZRIF_DICTIONARY))
    }

    /** Every zRIF in [text] with the content id it is for. Keys that don't decode are left out. */
    fun zrifsIn(text: String): List<Pair<String, String>> =
        ZRIF_IN_TEXT.findAll(text).mapNotNull { m -> zrifContentId(m.value)?.let { m.value to it } }.distinct().toList()

    private val NPD_MAGIC = byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 0)

    private fun indexOf(b: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun cString(b: ByteArray, at: Int, max: Int): String {
        var end = at
        while (end < at + max && end < b.size && b[end] != 0.toByte()) end++
        return b.decodeToString(at, end).trim()
    }

    /**
     * The preset dictionary zRIFs are deflated with (pkg2zip's zrif_dict, Adler-32 0x627D1D5D): 880
     * zero bytes, then content id fragments and the usual licence header bytes.
     */
    val ZRIF_DICTIONARY: ByteArray = ByteArray(880) + hex(
        "3030303039000000000000000000000030303030363030303037303030303800303030303330303030343030303035305f30302d414444434f4e" +
            "5430303030322d5043534730303030303030303030312d504353453030302d504353463030302d504353433030302d504353443030302d5043" +
            "53413030302d504353423030300001000100010002efcdab8967452301",
    )

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
