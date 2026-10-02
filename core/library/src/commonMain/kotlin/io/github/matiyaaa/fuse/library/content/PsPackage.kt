package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.disc.ParamSfo

/** The console a Sony package is for. */
enum class PsSystem { PS3, PSP, VITA }

/** What a package holds for its game. */
enum class PackageKind {
    /** The game itself (a PSN game, a PS1/PSP classic on PS3, a Vita app). */
    GAME,

    /** An update (patch) for a game. */
    UPDATE,

    /** Additional content for a game. */
    DLC,

    /** A licence package. */
    LICENCE,

    /** A theme, avatar or other system extra: not part of a game. */
    EXTRA,

    /** A kind no emulator Fuse knows installs (PSM, PS4). */
    UNSUPPORTED,
}

/**
 * A PlayStation 3, PSP or Vita `.pkg` as its unencrypted header describes it. Only the header, its
 * metadata packets and (for Vita packages) the plain copy of PARAM.SFO are read, the same parts
 * RPCS3 (rpcs3/Crypto/unpkg.cpp), Vita3K (vita3k/packages/src/pkg.cpp) and pkg2zip read first.
 *
 * @property contentId For example `UP9000-BCUS98137_00-0000000000000001`.
 * @property titleId Characters 7 to 15 of [contentId] (`BCUS98137`), the folder RPCS3 and Vita3K install into.
 * @property drmType Metadata packet 1: 1 network and 2 local licence (PS3 needs `<contentId>.rap`), 3 free.
 * @property version The application version this package brings (`01.02`), when it says.
 */
data class PsPackage(
    val path: String,
    val system: PsSystem,
    val kind: PackageKind,
    val contentId: String,
    val titleId: String?,
    val contentType: Int,
    val flags: Int,
    val drmType: Int?,
    val version: String?,
    val title: String? = null,
    val category: String? = null,
    val sizeBytes: Long = 0,
) {
    val fileName: String get() = FsPath.name(path)

    /**
     * Whether a licence has to be there for it to install and start: a `.rap` named after
     * [contentId] for PS3 (network or local DRM), a zRIF or `.rif` for Vita games and DLC (an
     * update uses the game's own).
     */
    val needsLicence: Boolean
        get() = when (system) {
            PsSystem.PS3 -> drmType == 1 || drmType == 2
            PsSystem.VITA -> (kind == PackageKind.GAME || kind == PackageKind.DLC || kind == PackageKind.UPDATE) && titleId?.startsWith("PCS") == true
            PsSystem.PSP -> false
        }
}

object PsPackages {
    const val MAGIC = 0x7F504B47L
    const val PLATFORM_PS3 = 1
    const val PLATFORM_PSP_VITA = 2

    const val FLAG_PATCH = 0x10

    private const val HEADER_SIZE = 0xC0
    private const val MAX_META = 64 * 1024
    private const val MAX_SFO = 64 * 1024

    /** PlayStation title ids as RPCS3 and Vita3K name their folders after them (`BLUS30443`, `PCSE00120`). */
    val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")

    /** `UP9000-BCUS98137_00-0000000000000001`: service id, title id, a two-digit number, a label. */
    val CONTENT_ID = Regex("^[A-Z]{2}\\d{4}-[A-Z]{4}\\d{5}_\\d{2}-[A-Z0-9_]{16}$")

    /** The package at [path], or null when it isn't a PlayStation package (or can't be read). */
    suspend fun read(fs: FuseFileSystem, path: String): PsPackage? {
        val header = fs.readBytes(path, 0, HEADER_SIZE) ?: return null
        val head = header(header) ?: return null
        val meta = if (head.metaCount in 1..63 && head.metaOffset >= 0x20) {
            fs.readBytes(path, head.metaOffset, head.metaSize.coerceIn(0, MAX_META).takeIf { it > 0 } ?: 4096)
        } else {
            null
        }
        val packets = meta?.let { packets(it, head.metaCount) }.orEmpty()
        val sfoInfo = packets[14]
        val sfo = if (sfoInfo != null && sfoInfo.size >= 8) {
            val offset = Inflate.u32be(sfoInfo, 0)
            val size = Inflate.u32be(sfoInfo, 4)
            if (size in 20..MAX_SFO.toLong()) fs.readBytes(path, offset, size.toInt())?.let(ParamSfo::strings) else null
        } else {
            null
        }
        val sizeBytes = fs.stat(path)?.sizeBytes ?: 0
        return describe(path, head, packets, sfo.orEmpty(), sizeBytes)
    }

    /** The fixed header fields, or null when [bytes] don't start a package. */
    internal fun header(bytes: ByteArray): Header? {
        if (bytes.size < 0x60 || Inflate.u32be(bytes, 0) != MAGIC) return null
        val platform = u16be(bytes, 6)
        if (platform != PLATFORM_PS3 && platform != PLATFORM_PSP_VITA) return null
        val contentId = text(bytes, 0x30, 0x30)
        return Header(
            platform = platform,
            metaOffset = Inflate.u32be(bytes, 8),
            metaCount = Inflate.u32be(bytes, 12).toInt(),
            metaSize = Inflate.u32be(bytes, 16).toInt(),
            contentId = contentId,
        )
    }

    internal data class Header(val platform: Int, val metaOffset: Long, val metaCount: Int, val metaSize: Int, val contentId: String)

    /** The metadata packets by id: `{id u32, size u32, data}` one after another. */
    internal fun packets(meta: ByteArray, count: Int): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        var at = 0
        repeat(count) {
            if (at + 8 > meta.size) return out
            val id = Inflate.u32be(meta, at).toInt()
            val size = Inflate.u32be(meta, at + 4)
            if (size < 0 || at + 8 + size > meta.size) return out
            out.putIfAbsent(id, meta.copyOfRange(at + 8, at + 8 + size.toInt()))
            at += 8 + size.toInt()
        }
        return out
    }

    internal fun describe(path: String, head: Header, packets: Map<Int, ByteArray>, sfo: Map<String, String>, sizeBytes: Long): PsPackage {
        fun u32(id: Int): Int? = packets[id]?.takeIf { it.size >= 4 }?.let { Inflate.u32be(it, 0).toInt() }
        val drm = u32(1)
        val contentType = u32(2) ?: 0
        val flags = u32(3) ?: 0
        val contentId = head.contentId
        val titleId = contentId.takeIf { it.length >= 16 }?.substring(7, 16)?.takeIf { TITLE_ID.matches(it) }
            ?: sfo["TITLE_ID"]?.trim()?.takeIf { TITLE_ID.matches(it) }
        val category = sfo["CATEGORY"]?.trim()?.lowercase()
        val system = when {
            head.platform == PLATFORM_PS3 -> PsSystem.PS3
            contentType in VITA_TYPES -> PsSystem.VITA
            else -> PsSystem.PSP
        }
        val kind = when (system) {
            PsSystem.PS3 -> when {
                flags and FLAG_PATCH != 0 -> PackageKind.UPDATE
                contentType in PS3_GAMES -> PackageKind.GAME
                contentType == 0x04 -> PackageKind.DLC
                contentType == 0x0B -> PackageKind.LICENCE
                contentType in PS3_EXTRAS -> PackageKind.EXTRA
                else -> PackageKind.UNSUPPORTED
            }
            PsSystem.VITA -> when (contentType) {
                // Vita3K and pkg2zip tell a patch from the game by PARAM.SFO's CATEGORY ("gp").
                0x15 -> when {
                    category == "gp" -> PackageKind.UPDATE
                    category == "ac" -> PackageKind.DLC
                    else -> PackageKind.GAME
                }
                0x16 -> PackageKind.DLC
                0x1F -> PackageKind.EXTRA
                else -> PackageKind.UNSUPPORTED
            }
            PsSystem.PSP -> PackageKind.GAME
        }
        // PS3: the software revision packet (8) holds the app version as two BCD bytes at +6.
        val ps3Version = packets[8]?.takeIf { it.size >= 8 }?.let { bcd(it, 6) } ?: packets[8]?.takeIf { it.size >= 6 }?.let { bcd(it, 4) }
        val version = sfo["APP_VER"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: ps3Version?.takeIf { it != "00.00" }
            ?: versionInName(FsPath.name(path))
        return PsPackage(
            path = path,
            system = system,
            kind = kind,
            contentId = contentId,
            titleId = titleId,
            contentType = contentType,
            flags = flags,
            drmType = drm,
            version = version,
            title = sfo["TITLE"]?.trim()?.replace('\n', ' ')?.takeIf { it.isNotEmpty() },
            category = category,
            sizeBytes = sizeBytes,
        )
    }

    /**
     * A version written in a package's file name: Sony's `-A0102-V0100` (the A part is the app
     * version), or `v1.02` / `ver-0102`.
     */
    fun versionInName(name: String): String? {
        Regex("-A(\\d{2})(\\d{2})-V\\d{4}", RegexOption.IGNORE_CASE).find(name)?.let { return "${it.groupValues[1]}.${it.groupValues[2]}" }
        Regex("(?:^|[^a-z])v(?:er)?[ ._-]?(\\d{1,2})[._]?(\\d{2})(?!\\d)", RegexOption.IGNORE_CASE).find(name)?.let {
            return "${it.groupValues[1].padStart(2, '0')}.${it.groupValues[2]}"
        }
        return null
    }

    /** Compares two `01.02`-style versions numerically; null sorts first. */
    fun compareVersions(a: String?, b: String?): Int {
        if (a == b) return 0
        if (a == null) return -1
        if (b == null) return 1
        val x = a.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun bcd(b: ByteArray, at: Int): String {
        fun two(v: Int) = "${(v shr 4) and 0xF}${v and 0xF}"
        return "${two(b[at].toInt() and 0xFF)}.${two(b[at + 1].toInt() and 0xFF)}"
    }

    private fun u16be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun text(b: ByteArray, at: Int, max: Int): String {
        var end = at
        while (end < at + max && end < b.size && b[end] != 0.toByte()) end++
        return b.decodeToString(at, end).trim()
    }

    /** Content types RPCS3 installs as games: GameExec, PS1 classics, PSP and minis, NEOGEO, PS2 classics, PSP remasters. */
    private val PS3_GAMES = setOf(0x05, 0x06, 0x07, 0x0E, 0x0F, 0x10, 0x12, 0x14)
    private val PS3_EXTRAS = setOf(0x09, 0x0A, 0x0C, 0x0D, 0x11)
    private val VITA_TYPES = setOf(0x15, 0x16, 0x17, 0x18, 0x1D, 0x1F)
}
