package io.github.matiyaaa.fuse.integrations.disc

/** AES-128 on single 16-byte blocks, from the platform (for 3DS dumps that are still encrypted). */
internal expect class Aes128(key: ByteArray) {
    fun encryptBlock(data: ByteArray, offset: Int)
    fun decryptBlock(data: ByteArray, offset: Int)
}

/**
 * tiny-AES's CTR and CBC as rcheevos uses them, so a 3DS hash comes out the same: CTR starts a new
 * keystream block at every call (the counter carries on from the last call), CBC keeps its IV.
 */
internal class TinyAes(key: ByteArray, iv: ByteArray = ByteArray(16)) {
    private var aes = Aes128(key)
    val iv: ByteArray = iv.copyOf()

    fun setKey(key: ByteArray) {
        aes = Aes128(key)
    }

    fun setIv(value: ByteArray) {
        value.copyInto(iv, 0, 0, 16)
    }

    fun ctr(buffer: ByteArray, offset: Int, length: Long) {
        val stream = ByteArray(16)
        var bi = 16
        for (i in 0 until length.toInt()) {
            if (bi == 16) {
                iv.copyInto(stream)
                aes.encryptBlock(stream, 0)
                var b = 15
                while (b >= 0) {
                    if (iv[b] == 0xFF.toByte()) { iv[b] = 0; b-- } else { iv[b]++; break }
                }
                bi = 0
            }
            buffer[offset + i] = (buffer[offset + i].toInt() xor stream[bi].toInt()).toByte()
            bi++
        }
    }

    fun cbcDecrypt(buffer: ByteArray, offset: Int, length: Int) {
        val next = ByteArray(16)
        var at = offset
        while (at + 16 <= offset + length) {
            buffer.copyInto(next, 0, at, at + 16)
            aes.decryptBlock(buffer, at)
            for (i in 0 until 16) buffer[at + i] = (buffer[at + i].toInt() xor iv[i].toInt()).toByte()
            next.copyInto(iv)
            at += 16
        }
    }
}
