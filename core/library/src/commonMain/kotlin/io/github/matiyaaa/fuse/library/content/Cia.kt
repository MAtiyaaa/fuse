package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem

/**
 * A Nintendo 3DS `.cia` as its title metadata (TMD) describes it: the title id, whose high word
 * says what it is (00040000 a game, 0004000E its update, 0004008C its DLC), and the title version.
 * Laid out as 3dbrew documents CIA and TMD, and as Azahar reads them (core/file_sys/cia_container.cpp):
 * a little-endian header (header, certificate chain, ticket and TMD sizes), each section aligned to
 * 64 bytes; the TMD's own fields are big-endian, after a signature whose size its type gives.
 */
data class CiaFile(
    val path: String,
    /** The full 16-digit title id, upper case (`0004000E00055D00`). */
    val titleId: String,
    val kind: PackageKind,
    /** The title version as Nintendo writes it: major.minor.micro. */
    val version: String,
    val sizeBytes: Long,
) {
    val fileName: String get() = FsPath.name(path)

    /** The game this belongs to: the title id with a game's high word. */
    val gameId: String get() = Cia.gameIdOf(titleId)
}

object Cia {
    const val GAME = "00040000"
    const val UPDATE = "0004000E"
    const val DLC = "0004008C"

    private const val HEADER = 0x2020

    /** The CIA at [path], or null when it isn't one. */
    suspend fun read(fs: FuseFileSystem, path: String): CiaFile? {
        val head = fs.readBytes(path, 0, 0x20) ?: return null
        if (head.size < 0x20 || le32(head, 0) != HEADER.toLong()) return null
        val cert = le32(head, 0x08)
        val ticket = le32(head, 0x0C)
        val tmdSize = le32(head, 0x10)
        if (tmdSize !in 0x200..0x10000) return null
        val certAt = align(HEADER.toLong())
        val ticketAt = align(certAt + cert)
        val tmdAt = align(ticketAt + ticket)
        val bytes = fs.readBytes(path, tmdAt, tmdSize.toInt()) ?: return null
        val info = tmd(bytes) ?: return null
        val kind = when (info.titleId.substring(0, 8)) {
            GAME -> PackageKind.GAME
            UPDATE -> PackageKind.UPDATE
            DLC -> PackageKind.DLC
            else -> PackageKind.UNSUPPORTED
        }
        return CiaFile(path, info.titleId, kind, info.version, fs.stat(path)?.sizeBytes ?: 0)
    }

    /** What a TMD says: the title id, its version, and the content id of each content index (for the installed `.app` files). */
    data class Tmd(val titleId: String, val version: String, val contents: Map<Int, Long>)

    /** Reads a TMD (the `.tmd` installed in a title's content folder, or the one inside a CIA). */
    fun tmd(b: ByteArray): Tmd? {
        if (b.size < 4) return null
        val header = when (be32(b, 0)) {
            0x10003L -> 0x240
            0x10004L -> 0x140
            0x10005L -> 0x80
            else -> return null
        }
        if (b.size < header + 0xC4) return null
        val titleId = hex(b, header + 0x4C, 8)
        val v = be16(b, header + 0x9C)
        val count = be16(b, header + 0x9E)
        val contents = HashMap<Int, Long>()
        val records = header + 0xC4 + 0x900
        for (i in 0 until count) {
            val at = records + i * 0x30
            if (at + 8 > b.size) break
            contents[be16(b, at + 4)] = be32(b, at)
        }
        return Tmd(titleId, versionText(v), contents)
    }

    /** `major.minor.micro` from a 16-bit title version (6 bits, 6 bits, 4 bits). */
    fun versionText(v: Int): String = "${(v shr 10) and 0x3F}.${(v shr 4) and 0x3F}.${v and 0xF}"

    /** The game's title id for an update's or DLC's. */
    fun gameIdOf(titleId: String): String = GAME + titleId.takeLast(8)

    /**
     * The title id a 3DS cartridge image (`.3ds`/`.cci`) says, from its NCSD header (magic at 0x100,
     * media id at 0x108, little-endian), so its updates and DLC find it.
     */
    suspend fun cartridgeId(fs: FuseFileSystem, path: String): String? {
        val b = fs.readBytes(path, 0x100, 0x10) ?: return null
        if (b.size < 0x10 || b.decodeToString(0, 4) != "NCSD") return null
        val sb = StringBuilder()
        for (i in 7 downTo 0) sb.append(((b[8 + i].toInt() and 0xFF) + 0x100).toString(16).substring(1))
        return sb.toString().uppercase().takeIf { it.startsWith(GAME) }
    }

    /** Where Azahar keeps a title in its SD card: `Nintendo 3DS/<id0>/<id1>/title/<high>/<low>`. */
    fun titleDir(sdmc: String, titleId: String): String =
        FsPath.join(sdmc, "Nintendo 3DS/$ZERO/$ZERO/title/${titleId.substring(0, 8).lowercase()}/${titleId.substring(8).lowercase()}")

    private const val ZERO = "00000000000000000000000000000000"

    private fun align(v: Long) = (v + 63) / 64 * 64

    private fun hex(b: ByteArray, at: Int, n: Int): String =
        (0 until n).joinToString("") { ((b[at + it].toInt() and 0xFF) + 0x100).toString(16).substring(1) }.uppercase()

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun be32(b: ByteArray, at: Int): Long = Inflate.u32be(b, at)

    private fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}
