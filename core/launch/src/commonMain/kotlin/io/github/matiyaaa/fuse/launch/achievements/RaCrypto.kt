package io.github.matiyaaa.fuse.launch.achievements

/** SHA-256 and AES-128 in CBC mode without padding, from the platform's own implementations. */
internal expect object RaCrypto {
    fun sha256(data: ByteArray): ByteArray

    /** [data] (a whole number of 16-byte blocks) encrypted with [key] (16 bytes) from [iv] (16 bytes). */
    fun aes128CbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

    fun aes128CbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray
}
