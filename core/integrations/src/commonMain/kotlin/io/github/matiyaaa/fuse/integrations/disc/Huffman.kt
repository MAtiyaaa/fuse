package io.github.matiyaaa.fuse.integrations.disc

/** Reads bits most significant first, as MAME's bitstream does (CHD maps and its Huffman codec). */
internal class BitReader(private val data: ByteArray, private val start: Int, private val length: Int) {
    private var buffer = 0L
    private var bits = 0
    private var offset = 0

    fun peek(count: Int): Int {
        if (count == 0) return 0
        if (count > bits) {
            while (bits <= 24) {
                val shift = 24 - bits
                if (offset < length && shift < 32) buffer = buffer or ((data[start + offset].toLong() and 0xFF) shl shift)
                offset++
                bits += 8
            }
        }
        return ((buffer and 0xFFFFFFFFL) ushr (32 - count)).toInt()
    }

    fun remove(count: Int) {
        buffer = if (count >= 32) 0 else (buffer shl count) and 0xFFFFFFFFL
        bits -= count
    }

    fun read(count: Int): Int = peek(count).also { remove(count) }

    /** True when more was read than there is. */
    val overflowed: Boolean get() = offset - bits / 8 > length
}

/** MAME's canonical Huffman decoder ([numCodes] symbols, codes up to [maxBits] long), as CHD uses it. */
internal class Huffman(private val numCodes: Int, private val maxBits: Int) {
    private val lengths = IntArray(numCodes)
    private val codes = IntArray(numCodes)
    private val lookup = IntArray(1 shl maxBits)

    fun decode(reader: BitReader): Int {
        val entry = lookup[reader.peek(maxBits)]
        reader.remove(entry and 0x1F)
        return entry ushr 5
    }

    /** The code lengths as an RLE-coded list (a CHD v5 map's). */
    fun importRle(reader: BitReader): Boolean {
        val numBits = when {
            maxBits >= 16 -> 5
            maxBits >= 8 -> 4
            else -> 3
        }
        var node = 0
        while (node < numCodes) {
            var nodeBits = reader.read(numBits)
            if (nodeBits != 1) {
                lengths[node++] = nodeBits
            } else {
                nodeBits = reader.read(numBits)
                if (nodeBits == 1) {
                    lengths[node++] = nodeBits
                } else {
                    var repeat = reader.read(numBits) + 3
                    if (repeat + node > numCodes) return false
                    while (repeat-- > 0) lengths[node++] = nodeBits
                }
            }
        }
        return node == numCodes && build() && !reader.overflowed
    }

    /** The code lengths as a small Huffman-coded list (CHD's Huffman codec). */
    fun importHuffman(reader: BitReader): Boolean {
        val small = Huffman(24, 6)
        small.lengths[0] = reader.read(3)
        val start = reader.read(3) + 1
        var count = 0
        for (index in 1 until 24) {
            if (index < start || count == 7) {
                small.lengths[index] = 0
            } else {
                count = reader.read(3)
                small.lengths[index] = if (count == 7) 0 else count
            }
        }
        if (!small.build()) return false
        var temp = numCodes - 9
        var rleFullBits = 0
        while (temp != 0) {
            temp = temp ushr 1
            rleFullBits++
        }
        var code = 0
        var last = 0
        while (code < numCodes) {
            val value = small.decode(reader)
            if (value != 0) {
                last = value - 1
                lengths[code++] = last
            } else {
                var repeat = reader.read(3) + 2
                if (repeat == 7 + 2) repeat += reader.read(rleFullBits)
                while (repeat != 0 && code < numCodes) {
                    lengths[code++] = last
                    repeat--
                }
            }
        }
        return code == numCodes && build() && !reader.overflowed
    }

    /** Canonical codes from the lengths, then the lookup table. */
    private fun build(): Boolean {
        val histogram = LongArray(33)
        for (n in 0 until numCodes) {
            if (lengths[n] > maxBits) return false
            histogram[lengths[n]]++
        }
        var start = 0L
        for (length in 32 downTo 1) {
            val next = (start + histogram[length]) ushr 1
            if (length != 1 && next * 2 != start + histogram[length]) return false
            histogram[length] = start
            start = next
        }
        for (n in 0 until numCodes) if (lengths[n] > 0) codes[n] = (histogram[lengths[n]]++).toInt()
        lookup.fill(0)
        for (n in 0 until numCodes) {
            val length = lengths[n]
            if (length <= 0) continue
            val value = (n shl 5) or (length and 0x1F)
            val shift = maxBits - length
            val from = codes[n] shl shift
            val to = ((codes[n] + 1) shl shift) - 1
            if (from < 0 || to >= lookup.size || to < from) return false
            for (i in from..to) lookup[i] = value
        }
        return true
    }
}
