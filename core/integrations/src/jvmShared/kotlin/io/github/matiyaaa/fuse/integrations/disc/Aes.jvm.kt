package io.github.matiyaaa.fuse.integrations.disc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal actual class Aes128 actual constructor(key: ByteArray) {
    private val spec = SecretKeySpec(key.copyOf(16), "AES")
    private val encrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
    private val decrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec) }

    actual fun encryptBlock(data: ByteArray, offset: Int) {
        encrypt.doFinal(data, offset, 16, data, offset)
    }

    actual fun decryptBlock(data: ByteArray, offset: Int) {
        decrypt.doFinal(data, offset, 16, data, offset)
    }
}
