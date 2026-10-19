package io.github.matiyaaa.fuse.launch.ports

/**
 * XXH3's 64-bit hash with no seed and its default secret, as xxHash 0.8 (xxhash.h,
 * `XXH3_64bits`) works it out: how N64: Recompiled's runtime (N64ModernRuntime, librecomp
 * `check_hash`) knows a ROM is the one a recompilation was made from. Written from the reference
 * code's scalar path; checked against the reference library's own results.
 */
object Xxh3 {
    private const val PRIME32_1 = 0x9E3779B1L
    private const val PRIME32_2 = 0x85EBCA77L
    private const val PRIME32_3 = 0xC2B2AE3DL
    private val PRIME64_1 = 0x9E3779B185EBCA87uL.toLong()
    private val PRIME64_2 = 0xC2B2AE3D27D4EB4FuL.toLong()
    private const val PRIME64_3 = 0x165667B19E3779F9L
    private val PRIME64_4 = 0x85EBCA77C2B2AE63uL.toLong()
    private const val PRIME64_5 = 0x27D4EB2F165667C5L
    private const val PRIME_MX1 = 0x165667919E3779F9L
    private val PRIME_MX2 = 0x9FB21C651E98DF25uL.toLong()

    private const val STRIPE = 64
    private const val CONSUME = 8
    private const val MIDSIZE_START = 3
    private const val MIDSIZE_LAST = 17
    private const val SECRET_MIN = 136
    private const val LASTACC_START = 7
    private const val MERGEACCS_START = 11

    /** `XXH3_kSecret`, the default secret (192 bytes). */
    private val SECRET: ByteArray = intArrayOf(
        0xb8, 0xfe, 0x6c, 0x39, 0x23, 0xa4, 0x4b, 0xbe, 0x7c, 0x01, 0x81, 0x2c, 0xf7, 0x21, 0xad, 0x1c,
        0xde, 0xd4, 0x6d, 0xe9, 0x83, 0x90, 0x97, 0xdb, 0x72, 0x40, 0xa4, 0xa4, 0xb7, 0xb3, 0x67, 0x1f,
        0xcb, 0x79, 0xe6, 0x4e, 0xcc, 0xc0, 0xe5, 0x78, 0x82, 0x5a, 0xd0, 0x7d, 0xcc, 0xff, 0x72, 0x21,
        0xb8, 0x08, 0x46, 0x74, 0xf7, 0x43, 0x24, 0x8e, 0xe0, 0x35, 0x90, 0xe6, 0x81, 0x3a, 0x26, 0x4c,
        0x3c, 0x28, 0x52, 0xbb, 0x91, 0xc3, 0x00, 0xcb, 0x88, 0xd0, 0x65, 0x8b, 0x1b, 0x53, 0x2e, 0xa3,
        0x71, 0x64, 0x48, 0x97, 0xa2, 0x0d, 0xf9, 0x4e, 0x38, 0x19, 0xef, 0x46, 0xa9, 0xde, 0xac, 0xd8,
        0xa8, 0xfa, 0x76, 0x3f, 0xe3, 0x9c, 0x34, 0x3f, 0xf9, 0xdc, 0xbb, 0xc7, 0xc7, 0x0b, 0x4f, 0x1d,
        0x8a, 0x51, 0xe0, 0x4b, 0xcd, 0xb4, 0x59, 0x31, 0xc8, 0x9f, 0x7e, 0xc9, 0xd9, 0x78, 0x73, 0x64,
        0xea, 0xc5, 0xac, 0x83, 0x34, 0xd3, 0xeb, 0xc3, 0xc5, 0x81, 0xa0, 0xff, 0xfa, 0x13, 0x63, 0xeb,
        0x17, 0x0d, 0xdd, 0x51, 0xb7, 0xf0, 0xda, 0x49, 0xd3, 0x16, 0x55, 0x26, 0x29, 0xd4, 0x68, 0x9e,
        0x2b, 0x16, 0xbe, 0x58, 0x7d, 0x47, 0xa1, 0xfc, 0x8f, 0xf8, 0xb8, 0xd1, 0x7a, 0xd0, 0x31, 0xce,
        0x45, 0xcb, 0x3a, 0x8f, 0x95, 0x16, 0x04, 0x28, 0xaf, 0xd7, 0xfb, 0xca, 0xbb, 0x4b, 0x40, 0x7e,
    ).let { b -> ByteArray(b.size) { b[it].toByte() } }

    /** The hash of [data] (its first [length] bytes). */
    fun hash64(data: ByteArray, length: Int = data.size): Long {
        val len = length
        return when {
            len <= 16 -> upTo16(data, len)
            len <= 128 -> upTo128(data, len)
            len <= 240 -> upTo240(data, len)
            else -> long(data, len)
        }
    }

    /** [hash64] as 16 lower-case hex digits, as the recompilations write theirs. */
    fun hex(data: ByteArray, length: Int = data.size): String = hash64(data, length).toULong().toString(16).padStart(16, '0')

    private fun upTo16(d: ByteArray, len: Int): Long = when {
        len > 8 -> {
            val flip1 = r64(SECRET, 24) xor r64(SECRET, 32)
            val flip2 = r64(SECRET, 40) xor r64(SECRET, 48)
            val lo = r64(d, 0) xor flip1
            val hi = r64(d, len - 8) xor flip2
            avalanche(len.toLong() + swap64(lo) + hi + fold(lo, hi))
        }
        len >= 4 -> {
            val in1 = r32(d, 0)
            val in2 = r32(d, len - 4)
            val flip = r64(SECRET, 8) xor r64(SECRET, 16)
            rrmxmx((in2 + (in1 shl 32)) xor flip, len.toLong())
        }
        len > 0 -> {
            val c1 = d[0].toLong() and 0xff
            val c2 = d[len shr 1].toLong() and 0xff
            val c3 = d[len - 1].toLong() and 0xff
            val combined = ((c1 shl 16) or (c2 shl 24) or c3 or (len.toLong() shl 8)) and 0xFFFFFFFFL
            val flip = (r32(SECRET, 0) xor r32(SECRET, 4))
            xxh64Avalanche(combined xor flip)
        }
        else -> xxh64Avalanche(r64(SECRET, 56) xor r64(SECRET, 64))
    }

    private fun upTo128(d: ByteArray, len: Int): Long {
        var acc = len.toLong() * PRIME64_1
        if (len > 32) {
            if (len > 64) {
                if (len > 96) {
                    acc += mix16(d, 48, 96)
                    acc += mix16(d, len - 64, 112)
                }
                acc += mix16(d, 32, 64)
                acc += mix16(d, len - 48, 80)
            }
            acc += mix16(d, 16, 32)
            acc += mix16(d, len - 32, 48)
        }
        acc += mix16(d, 0, 0)
        acc += mix16(d, len - 16, 16)
        return avalanche(acc)
    }

    private fun upTo240(d: ByteArray, len: Int): Long {
        var acc = len.toLong() * PRIME64_1
        val rounds = len / 16
        for (i in 0 until 8) acc += mix16(d, 16 * i, 16 * i)
        var end = mix16(d, len - 16, SECRET_MIN - MIDSIZE_LAST)
        acc = avalanche(acc)
        for (i in 8 until rounds) end += mix16(d, 16 * i, 16 * (i - 8) + MIDSIZE_START)
        return avalanche(acc + end)
    }

    private fun long(d: ByteArray, len: Int): Long {
        val acc = longArrayOf(PRIME32_3, PRIME64_1, PRIME64_2, PRIME64_3, PRIME64_4, PRIME32_2, PRIME64_5, PRIME32_1)
        val stripesPerBlock = (SECRET.size - STRIPE) / CONSUME
        val block = STRIPE * stripesPerBlock
        val blocks = (len - 1) / block
        for (n in 0 until blocks) {
            accumulate(acc, d, n * block, stripesPerBlock)
            scramble(acc, SECRET.size - STRIPE)
        }
        val stripes = ((len - 1) - block * blocks) / STRIPE
        accumulate(acc, d, blocks * block, stripes)
        accumulate512(acc, d, len - STRIPE, SECRET.size - STRIPE - LASTACC_START)
        var result = len.toLong() * PRIME64_1
        for (i in 0 until 4) result += fold(acc[2 * i] xor r64(SECRET, MERGEACCS_START + 16 * i), acc[2 * i + 1] xor r64(SECRET, MERGEACCS_START + 16 * i + 8))
        return avalanche(result)
    }

    private fun accumulate(acc: LongArray, d: ByteArray, at: Int, stripes: Int) {
        for (n in 0 until stripes) accumulate512(acc, d, at + n * STRIPE, n * CONSUME)
    }

    private fun accumulate512(acc: LongArray, d: ByteArray, at: Int, secret: Int) {
        for (i in 0 until 8) {
            val value = r64(d, at + 8 * i)
            val key = value xor r64(SECRET, secret + 8 * i)
            acc[i xor 1] += value
            acc[i] += (key and 0xFFFFFFFFL) * (key ushr 32)
        }
    }

    private fun scramble(acc: LongArray, secret: Int) {
        for (i in 0 until 8) {
            var a = acc[i]
            a = a xor (a ushr 47)
            a = a xor r64(SECRET, secret + 8 * i)
            acc[i] = a * PRIME32_1
        }
    }

    private fun mix16(d: ByteArray, at: Int, secret: Int): Long =
        fold(r64(d, at) xor r64(SECRET, secret), r64(d, at + 8) xor r64(SECRET, secret + 8))

    /** The 128-bit product of [a] and [b], its two halves xored. */
    private fun fold(a: Long, b: Long): Long = (a * b) xor multiplyHigh(a, b)

    /** The upper 64 bits of the unsigned product of [a] and [b] (`XXH_mult64to128`, scalar). */
    private fun multiplyHigh(a: Long, b: Long): Long {
        val aLo = a and 0xFFFFFFFFL
        val aHi = a ushr 32
        val bLo = b and 0xFFFFFFFFL
        val bHi = b ushr 32
        val loLo = aLo * bLo
        val hiLo = aHi * bLo
        val loHi = aLo * bHi
        val hiHi = aHi * bHi
        val cross = (loLo ushr 32) + (hiLo and 0xFFFFFFFFL) + loHi
        return (hiLo ushr 32) + (cross ushr 32) + hiHi
    }

    private fun avalanche(h0: Long): Long {
        var h = h0 xor (h0 ushr 37)
        h *= PRIME_MX1
        return h xor (h ushr 32)
    }

    private fun rrmxmx(h0: Long, len: Long): Long {
        var h = h0 xor (h0.rotateLeft(49) xor h0.rotateLeft(24))
        h *= PRIME_MX2
        h = h xor ((h ushr 35) + len)
        h *= PRIME_MX2
        return h xor (h ushr 28)
    }

    private fun xxh64Avalanche(h0: Long): Long {
        var h = h0 xor (h0 ushr 33)
        h *= PRIME64_2
        h = h xor (h ushr 29)
        h *= PRIME64_3
        return h xor (h ushr 32)
    }

    private fun swap64(v: Long): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or ((v ushr (8 * i)) and 0xff)
        return r
    }

    private fun r64(b: ByteArray, at: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[at + i].toLong() and 0xff)
        return r
    }

    private fun r32(b: ByteArray, at: Int): Long {
        var r = 0L
        for (i in 3 downTo 0) r = (r shl 8) or (b[at + i].toLong() and 0xff)
        return r
    }
}
