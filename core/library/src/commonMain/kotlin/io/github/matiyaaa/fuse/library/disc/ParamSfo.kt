package io.github.matiyaaa.fuse.library.disc

/**
 * PARAM.SFO, the small table PS3, PSP and Vita games describe themselves in (TITLE_ID, TITLE).
 * The layout follows RPCS3's rpcs3/Loader/PSF.cpp: a header (magic "\0PSF", version 0x101, key
 * table and data table offsets, entry count), then 16-byte index entries pointing into both tables.
 */
object ParamSfo {
    /** The text values of [bytes] by key; integers are left out. Empty when it isn't a PARAM.SFO. */
    fun strings(bytes: ByteArray): Map<String, String> {
        if (bytes.size < 20 || bytes[0] != 0.toByte() || bytes.decodeToString(1, 4) != "PSF") return emptyMap()
        val keys = u32(bytes, 8)
        val data = u32(bytes, 12)
        val count = u32(bytes, 16)
        if (keys < 20 || keys > data || data > bytes.size || count > 512) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until count) {
            val at = 20 + i * 16
            if (at + 16 > bytes.size) break
            val keyOff = u16(bytes, at)
            val format = u16(bytes, at + 2)
            val length = u32(bytes, at + 4)
            val dataOff = u32(bytes, at + 12)
            if (format == INTEGER) continue
            val k = keys + keyOff
            if (k >= data) continue
            var end = k
            while (end < data && bytes[end] != 0.toByte()) end++
            val key = bytes.decodeToString(k, end)
            val start = data + dataOff
            val stop = (start + length).coerceAtMost(bytes.size)
            if (start >= stop) continue
            out[key] = bytes.decodeToString(start, stop).trimEnd('\u0000')
        }
        return out
    }

    private const val INTEGER = 0x0404

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
