package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FuseFileSystem

/**
 * Reads single small files out of a ZIP archive (a Vita `.vpk` is one) through
 * [FuseFileSystem.readBytes]: the end-of-central-directory record, the central directory, then the
 * one entry asked for, stored or deflated. ZIP64 archives are found through their locator. Nothing
 * else of the archive is read, so a 4 GB dump costs a few kilobytes.
 */
class ZipReader(private val fs: FuseFileSystem) {
    /** One file listed in the archive. */
    data class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localHeader: Long)

    /** Every entry of the archive at [path], or null when it isn't a readable ZIP. */
    suspend fun entries(path: String): List<Entry>? {
        val size = fs.stat(path)?.sizeBytes ?: return null
        if (size < 22) return null
        val tailLen = minOf(size, (22 + 0xFFFF + 20).toLong()).toInt()
        val tail = fs.readBytes(path, size - tailLen, tailLen) ?: return null
        var eocd = -1
        for (i in tail.size - 22 downTo 0) {
            if (le32(tail, i) == EOCD_SIG) {
                eocd = i
                break
            }
        }
        if (eocd < 0) return null
        var count = le16(tail, eocd + 10).toLong()
        var cdSize = le32(tail, eocd + 12)
        var cdOffset = le32(tail, eocd + 16)
        // ZIP64: the locator sits right before the end record and points at the ZIP64 end record.
        if ((cdOffset == 0xFFFFFFFFL || count == 0xFFFFL) && eocd >= 20 && le32(tail, eocd - 20) == LOCATOR_SIG) {
            val at = le64(tail, eocd - 20 + 8)
            val rec = fs.readBytes(path, at, 56) ?: return null
            if (rec.size < 56 || le32(rec, 0) != ZIP64_EOCD_SIG) return null
            count = le64(rec, 32)
            cdSize = le64(rec, 40)
            cdOffset = le64(rec, 48)
        }
        if (cdSize <= 0 || cdSize > MAX_DIRECTORY || cdOffset + cdSize > size || count > MAX_ENTRIES) return null
        val cd = fs.readBytes(path, cdOffset, cdSize.toInt()) ?: return null
        val out = ArrayList<Entry>()
        var at = 0
        while (at + 46 <= cd.size && le32(cd, at) == CENTRAL_SIG) {
            val method = le16(cd, at + 10)
            var compressed = le32(cd, at + 20)
            var uncompressed = le32(cd, at + 24)
            val nameLen = le16(cd, at + 28)
            val extraLen = le16(cd, at + 30)
            val commentLen = le16(cd, at + 32)
            var local = le32(cd, at + 42)
            if (at + 46 + nameLen + extraLen > cd.size) break
            val name = cd.decodeToString(at + 46, at + 46 + nameLen)
            // ZIP64 extra field: the 64-bit values, in order, for each 32-bit one that is all ones.
            var x = at + 46 + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd) {
                val id = le16(cd, x)
                val len = le16(cd, x + 2)
                if (id == 1) {
                    var p = x + 4
                    if (uncompressed == 0xFFFFFFFFL && p + 8 <= x + 4 + len) { uncompressed = le64(cd, p); p += 8 }
                    if (compressed == 0xFFFFFFFFL && p + 8 <= x + 4 + len) { compressed = le64(cd, p); p += 8 }
                    if (local == 0xFFFFFFFFL && p + 8 <= x + 4 + len) local = le64(cd, p)
                }
                x += 4 + len
            }
            out += Entry(name, method, compressed, uncompressed, local)
            at += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    /** The bytes of [entry] in the archive at [path], or null when it is larger than [maxBytes] or can't be read. */
    suspend fun read(path: String, entry: Entry, maxBytes: Int): ByteArray? {
        if (entry.size > maxBytes || entry.compressedSize > maxBytes.toLong() * 2 + 1024) return null
        val header = fs.readBytes(path, entry.localHeader, 30) ?: return null
        if (header.size < 30 || le32(header, 0) != LOCAL_SIG) return null
        val dataAt = entry.localHeader + 30 + le16(header, 26) + le16(header, 28)
        val data = fs.readBytes(path, dataAt, entry.compressedSize.toInt()) ?: return null
        if (data.size.toLong() != entry.compressedSize) return null
        return when (entry.method) {
            0 -> data.takeIf { it.size.toLong() == entry.size }
            8 -> Inflate.raw(data, maxOut = maxBytes)?.takeIf { it.size.toLong() == entry.size }
            else -> null
        }
    }

    /** The first entry whose name (case ignored, "/" separators) matches [name] exactly or ends with "/[name]". */
    suspend fun find(path: String, name: String, maxBytes: Int): ByteArray? {
        val all = entries(path) ?: return null
        val want = name.lowercase()
        val entry = all.filter { e ->
            val n = e.name.replace('\\', '/').lowercase()
            n == want || n.endsWith("/$want")
        }.minByOrNull { it.name.count { c -> c == '/' } } ?: return null
        return read(path, entry, maxBytes)
    }

    private companion object {
        const val EOCD_SIG = 0x06054b50L
        const val LOCATOR_SIG = 0x07064b50L
        const val ZIP64_EOCD_SIG = 0x06064b50L
        const val CENTRAL_SIG = 0x02014b50L
        const val LOCAL_SIG = 0x04034b50L
        const val MAX_DIRECTORY = 16L * 1024 * 1024
        const val MAX_ENTRIES = 500_000L

        fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, at: Int): Long = le16(b, at).toLong() or (le16(b, at + 2).toLong() shl 16)
        fun le64(b: ByteArray, at: Int): Long = le32(b, at) or (le32(b, at + 4) shl 32)
    }
}
