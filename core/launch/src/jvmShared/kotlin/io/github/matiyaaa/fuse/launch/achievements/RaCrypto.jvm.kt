package io.github.matiyaaa.fuse.launch.achievements

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object RaCrypto {
    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun aes128CbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = cbc(Cipher.ENCRYPT_MODE, key, iv, data)

    actual fun aes128CbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = cbc(Cipher.DECRYPT_MODE, key, iv, data)

    private fun cbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
}
