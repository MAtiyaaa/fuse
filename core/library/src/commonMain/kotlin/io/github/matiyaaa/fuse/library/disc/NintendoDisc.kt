package io.github.matiyaaa.fuse.library.disc

/**
 * The six-letter id a GameCube or Wii disc names itself by (GALE01), as Dolphin files a game's own
 * settings under it. Read from the disc header (Dolphin's DiscIO/VolumeDisc.cpp): the id at 0 and
 * the GameCube magic at 0x1C or the Wii magic at 0x18; in a WIA or RVZ image the header is kept
 * uncompressed at 0x58 (DiscIO/WIABlob.h, WIAHeader2.disc_header); in a CISO image the disc starts
 * at 0x8000. Compressed GCZ and WBFS images aren't read.
 */
object NintendoDisc {
    /** How many bytes from the start of an image [gameId] needs. */
    const val HEAD = 0x8000 + 0x20

    /** The id in [head] (the first bytes of an image), or null when it isn't a disc Fuse can read. */
    fun gameId(head: ByteArray): String? {
        val at = when {
            magic(head, 0) -> 0
            head.size < 4 -> return null
            head.decodeToString(0, 3) == "RVZ" || head.decodeToString(0, 3) == "WIA" -> WIA_DISC
            head.decodeToString(0, 4) == "CISO" -> CISO_DATA
            else -> return null
        }
        if (at != 0 && !magic(head, at)) return null
        return head.decodeToString(at, at + 6).takeIf { ID.matches(it) }
    }

    /** A GameCube (0xC2339F3D at 0x1C) or Wii (0x5D1C9EA3 at 0x18) disc header at [at]. */
    private fun magic(b: ByteArray, at: Int): Boolean {
        if (b.size < at + 0x20) return false
        return u32be(b, at + 0x1C) == 0xC2339F3DL || u32be(b, at + 0x18) == 0x5D1C9EA3L
    }

    private fun u32be(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private const val WIA_DISC = 0x58
    private const val CISO_DATA = 0x8000
    private val ID = Regex("[A-Z0-9]{6}")

    /** A disc id given in a game's name or tags ([GALE01]), as Dolphin writes it. */
    fun fromTag(serial: String?): String? = serial?.trim()?.uppercase()?.takeIf { ID.matches(it) }
}
