package io.github.matiyaaa.fuse.library.achievements

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object TrophyAes {
    actual fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray? = try {
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    } catch (e: java.security.GeneralSecurityException) {
        null
    }
}
