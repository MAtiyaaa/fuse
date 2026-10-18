package io.github.matiyaaa.fuse.launch.online

/**
 * SHA3-256 (FIPS 202: Keccak-f[1600], a rate of 136 bytes, the domain bits `01` then the
 * `pad10*1` padding), HMAC over it (RFC 2104, with its block of 136 bytes) and PBKDF2 over that
 * (RFC 8018), for RPCS3's RPCN password (see [Rpcn]). Pure, so it is the same on every system:
 * Android's own providers have no SHA3 HMAC.
 */
internal object Sha3 {
    const val RATE = 136
    const val SIZE = 32

    fun sha256(data: ByteArray): ByteArray = Sponge().apply { absorb(data) }.finish()

    /** HMAC-SHA3-256 of [message] with [key]. */
    fun hmac(key: ByteArray, message: ByteArray): ByteArray = Hmac(key).mac(message)

    /** PBKDF2 with HMAC-SHA3-256: [length] bytes from [password] and [salt] after [iterations] rounds. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations > 0 && length > 0)
        val prf = Hmac(password)
        val out = ByteArray(length)
        var block = 1
        var at = 0
        while (at < length) {
            var u = prf.mac(salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = prf.mac(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(SIZE, length - at)
            t.copyInto(out, at, 0, n)
            at += n
            block++
        }
        return out
    }

    /** HMAC with the key's two padded blocks absorbed once, so each round costs two permutations. */
    private class Hmac(key: ByteArray) {
        private val inner: Sponge
        private val outer: Sponge

        init {
            val k = (if (key.size > RATE) sha256(key) else key).copyOf(RATE)
            inner = Sponge().apply { absorb(ByteArray(RATE) { (k[it].toInt() xor 0x36).toByte() }) }
            outer = Sponge().apply { absorb(ByteArray(RATE) { (k[it].toInt() xor 0x5C).toByte() }) }
        }

        fun mac(message: ByteArray): ByteArray {
            val h = inner.copy().apply { absorb(message) }.finish()
            return outer.copy().apply { absorb(h) }.finish()
        }
    }

    /** The sponge: whole blocks absorbed as they come, the rest kept until [finish]. */
    private class Sponge(private val state: LongArray = LongArray(25), private val pending: ByteArray = ByteArray(RATE), private var used: Int = 0) {
        fun copy() = Sponge(state.copyOf(), pending.copyOf(), used)

        fun absorb(data: ByteArray) {
            for (b in data) {
                pending[used++] = b
                if (used == RATE) {
                    block()
                    used = 0
                }
            }
        }

        fun finish(): ByteArray {
            for (i in used until RATE) pending[i] = 0
            pending[used] = (pending[used].toInt() xor 0x06).toByte()
            pending[RATE - 1] = (pending[RATE - 1].toInt() xor 0x80).toByte()
            block()
            return ByteArray(SIZE) { i -> (state[i / 8] ushr (8 * (i % 8))).toByte() }
        }

        private fun block() {
            for (i in 0 until RATE / 8) {
                var v = 0L
                for (j in 0 until 8) v = v or ((pending[i * 8 + j].toLong() and 0xFF) shl (8 * j))
                state[i] = state[i] xor v
            }
            keccak(state)
        }
    }

    private val ROUND = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7FFFFFFFFFFF7F76L, -0x7FFFFFFF7FFF8000L,
        0x000000000000808BL, 0x0000000080000001L, -0x7FFFFFFF7FFF7F7FL, -0x7FFFFFFFFFFF7FF7L,
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, -0x7FFFFFFFFFFFFF75L, -0x7FFFFFFFFFFF7F77L, -0x7FFFFFFFFFFF7FFDL,
        -0x7FFFFFFFFFFF7FFEL, -0x7FFFFFFFFFFFFF80L, 0x000000000000800AL, -0x7FFFFFFF7FFFFFF6L,
        -0x7FFFFFFF7FFF7F7FL, -0x7FFFFFFFFFFF7F80L, 0x0000000080000001L, -0x7FFFFFFF7FFF7FF8L,
    )

    /** Each lane's rotation (x + 5y), FIPS 202 section 3.2.2. */
    private val ROTATION = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    private fun keccak(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = a[x + 5 * y].rotateLeft(ROTATION[x + 5 * y])
            }
            for (y in 0 until 5) for (x in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            a[0] = a[0] xor ROUND[round]
        }
    }
}
