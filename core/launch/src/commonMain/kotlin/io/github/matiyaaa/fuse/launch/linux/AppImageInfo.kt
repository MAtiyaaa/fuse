package io.github.matiyaaa.fuse.launch.linux

/**
 * What an AppImage says about itself without running or unpacking it: AppImages are ELF programs
 * marked "AI" with a type byte at offset 8, and most carry an `.upd_info` section naming where they
 * update from, ending in their release file's name (`gh-releases-zsync|RPCS3|rpcs3-binaries-linux|
 * latest|rpcs3-*_linux64.AppImage.zsync`). That name is matched against the same globs as a file
 * name, so an emulator's AppImage is found however it was renamed (as Cartridge finds them).
 */
object AppImageInfo {
    private const val ELF_MAGIC = 0x7f454c46
    private const val HEADER = 64
    private const val MAX_SECTIONS = 200
    private const val MAX_TEXT = 4096

    /** True when [head] (at least 11 bytes from the start of a file) is an AppImage of type 1 or 2. */
    fun isAppImage(head: ByteArray): Boolean {
        if (head.size < 11 || be32(head, 0) != ELF_MAGIC) return false
        return head[8] == 0x41.toByte() && head[9] == 0x49.toByte() && (head[10] == 1.toByte() || head[10] == 2.toByte())
    }

    /**
     * The release file the AppImage at [path] updates from ("rpcs3-*_linux64.AppImage"), or null when
     * it isn't an AppImage, carries no update information, or can't be read.
     */
    fun releaseName(env: LinuxEnvironment, path: String): String? {
        val head = env.readBytes(path, 0, HEADER) ?: return null
        if (!isAppImage(head)) return null
        val text = section(env, path, head, ".upd_info") ?: return null
        return releaseNameOf(text)
    }

    /** The last field of an update string, without its `.zsync`. */
    fun releaseNameOf(updateInfo: String): String? =
        updateInfo.trim().trimEnd('\u0000').substringAfterLast('|').removeSuffix(".zsync").trim().takeIf { it.isNotEmpty() }

    private fun section(env: LinuxEnvironment, path: String, h: ByteArray, wanted: String): String? {
        val is64 = h[4] == 2.toByte()
        val shoff = if (is64) le64(h, 0x28) else le32(h, 0x20).toLong()
        val shentsize = le16(h, if (is64) 0x3a else 0x2e)
        val shnum = le16(h, if (is64) 0x3c else 0x30)
        val shstrndx = le16(h, if (is64) 0x3e else 0x32)
        if (shnum !in 1..MAX_SECTIONS || shentsize < (if (is64) 64 else 40) || shstrndx >= shnum || shoff <= 0) return null
        val table = env.readBytes(path, shoff, shentsize * shnum) ?: return null
        fun entry(i: Int): Triple<Int, Long, Long> {
            val o = i * shentsize
            return if (is64) Triple(le32(table, o), le64(table, o + 0x18), le64(table, o + 0x20))
            else Triple(le32(table, o), le32(table, o + 0x10).toLong(), le32(table, o + 0x14).toLong())
        }
        val (_, strOff, strSize) = entry(shstrndx)
        if (strSize <= 0 || strSize > 65_536) return null
        val names = env.readBytes(path, strOff, strSize.toInt()) ?: return null
        for (i in 0 until shnum) {
            val (nameAt, off, size) = entry(i)
            if (nameAt < 0 || nameAt >= names.size) continue
            var end = nameAt
            while (end < names.size && names[end] != 0.toByte()) end++
            if (names.decodeToString(nameAt, end) != wanted) continue
            if (size <= 0) return null
            return env.readBytes(path, off, minOf(size, MAX_TEXT.toLong()).toInt())?.decodeToString()?.replace("\u0000", "")?.trim()
        }
        return null
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, o: Int): Int = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun le64(b: ByteArray, o: Int): Long = (le32(b, o).toLong() and 0xffffffffL) or (le32(b, o + 4).toLong() shl 32)
}
