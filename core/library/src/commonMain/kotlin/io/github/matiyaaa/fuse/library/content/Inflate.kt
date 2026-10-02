package io.github.matiyaaa.fuse.library.content

/**
 * DEFLATE (RFC 1951) and zlib (RFC 1950) decoding in plain Kotlin, after Mark Adler's puff.c: enough
 * to read a file inside a Vita `.vpk`/`.zip` and to open a zRIF, on every platform Fuse runs on.
 * Every length is checked against the input and [maxOut]; damaged data gives null, never a crash.
 */
object Inflate {
    /** The raw DEFLATE stream in [input] from [start] to [end], or null when it is damaged or larger than [maxOut]. */
    fun raw(input: ByteArray, start: Int = 0, end: Int = input.size, maxOut: Int, dictionary: ByteArray? = null): ByteArray? =
        try {
            val s = State(input, start, end, maxOut, dictionary)
            s.run()
            s.output()
        } catch (e: Damaged) {
            null
        }

    /**
     * A zlib stream: header, the DEFLATE data, and the Adler-32 checksum, which must match. A stream
     * made with a preset dictionary only opens with that [dictionary].
     */
    fun zlib(input: ByteArray, maxOut: Int, dictionary: ByteArray? = null): ByteArray? {
        if (input.size < 6) return null
        val cmf = input[0].toInt() and 0xFF
        val flg = input[1].toInt() and 0xFF
        if ((cmf * 256 + flg) % 31 != 0 || cmf and 0x0F != 8) return null
        var at = 2
        val dict = if (flg and 0x20 != 0) {
            if (dictionary == null || input.size < 10 || u32be(input, 2) != adler32(dictionary)) return null
            at = 6
            dictionary
        } else {
            null
        }
        return try {
            val s = State(input, at, input.size, maxOut, dict)
            s.run()
            val out = s.output()
            if (s.pos + 4 > input.size || u32be(input, s.pos) != adler32(out)) return null
            out
        } catch (e: Damaged) {
            null
        }
    }

    /** Adler-32 of [data] (zlib's checksum and dictionary id). */
    fun adler32(data: ByteArray, from: Int = 0, to: Int = data.size): Long {
        var a = 1L
        var b = 0L
        for (i in from until to) {
            a = (a + (data[i].toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    /**
     * A zlib stream holding [data] uncompressed (stored blocks), made with [dictionary] when given.
     * What a zRIF needs when Fuse turns an installed licence back into one; any zlib reader opens it.
     */
    fun zlibStored(data: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val out = ArrayList<Byte>(data.size + 32)
        // A 1 KB window and "best compression" level, as zRIFs are written (they start "KO5i").
        val cmf = 0x28
        var flg = 0xC0 or (if (dictionary != null) 0x20 else 0)
        val rem = (cmf * 256 + flg) % 31
        if (rem != 0) flg += 31 - rem
        out += cmf.toByte()
        out += flg.toByte()
        if (dictionary != null) putBe(out, adler32(dictionary))
        var at = 0
        do {
            val len = minOf(0xFFFF, data.size - at)
            val last = at + len >= data.size
            out += (if (last) 1 else 0).toByte()
            out += (len and 0xFF).toByte()
            out += (len shr 8).toByte()
            out += (len.inv() and 0xFF).toByte()
            out += ((len.inv() shr 8) and 0xFF).toByte()
            for (i in at until at + len) out += data[i]
            at += len
        } while (!last)
        putBe(out, adler32(data))
        return out.toByteArray()
    }

    private fun putBe(out: MutableList<Byte>, v: Long) {
        out += (v shr 24).toByte()
        out += (v shr 16).toByte()
        out += (v shr 8).toByte()
        out += v.toByte()
    }

    internal fun u32be(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private class Damaged : Exception()

    private class Huffman(val count: IntArray, val symbol: IntArray)

    private class State(val src: ByteArray, var pos: Int, val end: Int, val maxOut: Int, dictionary: ByteArray?) {
        private var bitBuf = 0L
        private var bitCount = 0
        private val dictLen = dictionary?.size ?: 0
        private var out = ByteArray(maxOf(256, minOf(maxOut, 64 * 1024)) + dictLen)
        private var outLen = 0

        init {
            if (dictionary != null) {
                dictionary.copyInto(out)
                outLen = dictionary.size
            }
        }

        fun output(): ByteArray = out.copyOfRange(dictLen, outLen)

        private fun bits(need: Int): Int {
            var v = bitBuf
            while (bitCount < need) {
                if (pos >= end) throw Damaged()
                v = v or ((src[pos++].toLong() and 0xFF) shl bitCount)
                bitCount += 8
            }
            bitBuf = v ushr need
            bitCount -= need
            return (v and ((1L shl need) - 1)).toInt()
        }

        private fun put(b: Byte) {
            if (outLen - dictLen >= maxOut) throw Damaged()
            if (outLen == out.size) out = out.copyOf(minOf(out.size * 2, maxOut + dictLen).coerceAtLeast(out.size + 1))
            out[outLen++] = b
        }

        fun run() {
            do {
                val last = bits(1)
                when (bits(2)) {
                    0 -> stored()
                    1 -> codes(FIXED_LEN, FIXED_DIST)
                    2 -> dynamic()
                    else -> throw Damaged()
                }
            } while (last == 0)
            // Unused bits of the last byte belong to no one; the checksum starts at the next whole byte.
            bitBuf = 0
            bitCount = 0
        }

        private fun stored() {
            bitBuf = 0
            bitCount = 0
            if (pos + 4 > end) throw Damaged()
            val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
            val nlen = (src[pos + 2].toInt() and 0xFF) or ((src[pos + 3].toInt() and 0xFF) shl 8)
            if (len != (nlen.inv() and 0xFFFF)) throw Damaged()
            pos += 4
            if (pos + len > end) throw Damaged()
            for (i in 0 until len) put(src[pos + i])
            pos += len
        }

        private fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..MAX_BITS) {
                code = code or bits(1)
                val count = h.count[len]
                if (code - count < first) return h.symbol[index + (code - first)]
                index += count
                first += count
                first = first shl 1
                code = code shl 1
            }
            throw Damaged()
        }

        private fun codes(lencode: Huffman, distcode: Huffman) {
            while (true) {
                var symbol = decode(lencode)
                if (symbol < 256) {
                    put(symbol.toByte())
                } else if (symbol == 256) {
                    return
                } else {
                    symbol -= 257
                    if (symbol >= 29) throw Damaged()
                    val len = LBASE[symbol] + bits(LEXT[symbol])
                    val d = decode(distcode)
                    if (d >= 30) throw Damaged()
                    val dist = DBASE[d] + bits(DEXT[d])
                    if (dist > outLen) throw Damaged()
                    for (i in 0 until len) put(out[outLen - dist])
                }
            }
        }

        private fun dynamic() {
            val nlen = bits(5) + 257
            val ndist = bits(5) + 1
            val ncode = bits(4) + 4
            if (nlen > 286 || ndist > 30) throw Damaged()
            val lengths = IntArray(320)
            for (i in 0 until ncode) lengths[ORDER[i]] = bits(3)
            val lencode = build(lengths, 19) ?: throw Damaged()
            var index = 0
            while (index < nlen + ndist) {
                var symbol = decode(lencode)
                if (symbol < 16) {
                    lengths[index++] = symbol
                } else {
                    var len = 0
                    when (symbol) {
                        16 -> {
                            if (index == 0) throw Damaged()
                            len = lengths[index - 1]
                            symbol = 3 + bits(2)
                        }
                        17 -> symbol = 3 + bits(3)
                        else -> symbol = 11 + bits(7)
                    }
                    if (index + symbol > nlen + ndist) throw Damaged()
                    repeat(symbol) { lengths[index++] = len }
                }
            }
            if (lengths[256] == 0) throw Damaged()
            val lens = build(lengths.copyOfRange(0, nlen), nlen) ?: throw Damaged()
            val dists = build(lengths.copyOfRange(nlen, nlen + ndist), ndist) ?: throw Damaged()
            codes(lens, dists)
        }
    }

    /** A canonical Huffman table from code [lengths]; null when the lengths over-subscribe. */
    private fun build(lengths: IntArray, n: Int): Huffman? {
        val count = IntArray(MAX_BITS + 1)
        for (i in 0 until n) count[lengths[i]]++
        if (count[0] == n) return Huffman(count, IntArray(n))
        var left = 1
        for (len in 1..MAX_BITS) {
            left = left shl 1
            left -= count[len]
            if (left < 0) return null
        }
        val offs = IntArray(MAX_BITS + 1)
        for (len in 1 until MAX_BITS) offs[len + 1] = offs[len] + count[len]
        val symbol = IntArray(n)
        for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i
        return Huffman(count, symbol)
    }

    private const val MAX_BITS = 15
    private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    private val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DBASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    private val FIXED_LEN: Huffman = IntArray(288).let { l ->
        for (i in 0 until 144) l[i] = 8
        for (i in 144 until 256) l[i] = 9
        for (i in 256 until 280) l[i] = 7
        for (i in 280 until 288) l[i] = 8
        build(l, 288)!!
    }
    private val FIXED_DIST: Huffman = build(IntArray(30) { 5 }, 30)!!
}
