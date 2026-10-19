package io.github.matiyaaa.fuse.integrations.ports

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** The DER header of an Ed25519 SubjectPublicKeyInfo (RFC 8410 section 4), before the raw 32-byte key. */
private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

/** The JDK's own Ed25519 (Java 15 and later). Where the platform has none (Android before 13), nothing verifies. */
internal actual fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = try {
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + publicKey))
    Signature.getInstance("Ed25519").run {
        initVerify(key)
        update(message)
        verify(signature)
    }
} catch (e: GeneralSecurityException) {
    false
} catch (e: IllegalArgumentException) {
    false
}
