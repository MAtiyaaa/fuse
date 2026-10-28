package io.github.matiyaaa.fuse.sync

/**
 * A GameCube save on its own, as a `.gci` file: the card's directory entry for it (0x40 bytes:
 * the game's code and maker, its file name, and how many 8 KB blocks it takes, at 0x38), then its
 * blocks. Dolphin's GCI folder keeps each such file as `<maker>-<game code>-<file name>.gci`
 * (Core/HW/GCMemcard/GCMemcardDirectory.cpp), the name read here so an import lands where Dolphin
 * looks for it, whatever the file was called.
 */
object Gci {
    private const val HEADER = 0x40
    private const val BLOCK = 0x2000

    /** The game id it is a save of (`GALE01`: code, then maker), or null when it isn't a GCI. */
    fun gameId(bytes: ByteArray): String? {
        if (bytes.size < HEADER + BLOCK) return null
        val blocks = ((bytes[0x38].toInt() and 0xFF) shl 8) or (bytes[0x39].toInt() and 0xFF)
        if (blocks < 1 || bytes.size != HEADER + blocks * BLOCK) return null
        val id = bytes.decodeToString(0, 6)
        return id.takeIf { it.all { c -> c.isLetterOrDigit() } }
    }

    /** The name Dolphin's GCI folder gives it; null when it isn't a GCI. */
    fun dolphinName(bytes: ByteArray): String? {
        val id = gameId(bytes) ?: return null
        val end = (8 until 8 + 32).firstOrNull { bytes[it] == 0.toByte() } ?: (8 + 32)
        val name = bytes.decodeToString(8, end).map { c -> if (c in "\\/:*?\"<>|" || c.code < 32) '_' else c }.joinToString("").trim()
        if (name.isEmpty()) return null
        return "${id.substring(4, 6)}-${id.substring(0, 4)}-$name.gci"
    }
}
