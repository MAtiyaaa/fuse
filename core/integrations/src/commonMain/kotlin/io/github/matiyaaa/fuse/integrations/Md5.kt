package io.github.matiyaaa.fuse.integrations

/**
 * Streaming MD5 (RFC 1321) in pure Kotlin, so hashing works the same on Android and desktop
 * without platform crypto. Not for security; RetroAchievements and scrapers identify ROMs by MD5.
 */
class Md5 {
    private val state = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
    private val block = ByteArray(64)
    private var blockLength = 0
    private var totalBytes = 0L
    private val words = IntArray(16)

    /** Adds [length] bytes of [input] starting at [offset]. */
    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): Md5 {
        require(offset >= 0 && length >= 0 && offset + length <= input.size) { "Bad range" }
        var pos = offset
        var remaining = length
        totalBytes += length
        if (blockLength > 0) {
            val take = minOf(64 - blockLength, remaining)
            input.copyInto(block, blockLength, pos, pos + take)
            blockLength += take
            pos += take
            remaining -= take
            if (blockLength == 64) {
                transform(block, 0)
                blockLength = 0
            }
        }
        while (remaining >= 64) {
            transform(input, pos)
            pos += 64
            remaining -= 64
        }
        if (remaining > 0) {
            input.copyInto(block, 0, pos, pos + remaining)
            blockLength = remaining
        }
        return this
    }

    /** Finishes the hash and returns the 16-byte digest. The instance must not be reused. */
    fun digest(): ByteArray {
        val bitLength = totalBytes * 8
        val padding = ByteArray(if (blockLength < 56) 56 - blockLength else 120 - blockLength)
        padding[0] = 0x80.toByte()
        val lengthBytes = ByteArray(8) { i -> (bitLength ushr (8 * i)).toByte() }
        val saved = totalBytes
        update(padding)
        update(lengthBytes)
        totalBytes = saved
        val out = ByteArray(16)
        for (i in 0 until 4) {
            val v = state[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }

    /** Finishes the hash as 32 lower-case hex characters. */
    fun digestHex(): String = toHex(digest())

    private fun transform(input: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val p = offset + i * 4
            words[i] = (input[p].toInt() and 0xFF) or
                ((input[p + 1].toInt() and 0xFF) shl 8) or
                ((input[p + 2].toInt() and 0xFF) shl 16) or
                ((input[p + 3].toInt() and 0xFF) shl 24)
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> { f = (b and c) or (b.inv() and d); g = i }
                1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
            }
            val temp = d
            d = c
            c = b
            b += (a + f + K[i] + words[g]).rotateLeft(S[i])
            a = temp
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    companion object {
        private val S = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )

        private val K = longArrayOf(
            0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
            0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
            0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
            0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
            0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
            0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
            0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
            0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
        ).map { it.toInt() }.toIntArray()

        private const val HEX = "0123456789abcdef"

        fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF
                append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
        }

        /** MD5 of [bytes] as lower-case hex. */
        fun hex(bytes: ByteArray): String = Md5().update(bytes).digestHex()

        /** MD5 of the UTF-8 bytes of [text] as lower-case hex. */
        fun hex(text: String): String = hex(text.encodeToByteArray())
    }
}

/**
 * Random-access bytes the app provides for hashing (a file, a SAF document, a byte array). Reads
 * may block; call the hasher from an IO dispatcher.
 */
interface ByteSource {
    /** Total length in bytes. */
    val size: Long

    /**
     * Reads up to [length] bytes at [position] into [buffer] from [offset]. Returns the number of
     * bytes read, or -1 when [position] is at or past the end.
     */
    suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** In-memory [ByteSource]. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
}

/** Reads exactly up to [length] bytes at [position], looping over short reads. Returns bytes read. */
internal suspend fun ByteSource.readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
    var total = 0
    while (total < length) {
        val n = read(position + total, buffer, offset + total, length - total)
        if (n <= 0) break
        total += n
    }
    return total
}
