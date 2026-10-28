package io.github.matiyaaa.fuse.integrations.ports

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Ed25519 an older Android checks the Ports index with: RFC 8032's own test vectors, section 7.1. */
class Ed25519FallbackTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun rfc8032sVectorsVerifyAndNothingElseDoes() {
        // TEST 1: the empty message.
        val key1 = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig1 = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519Fallback.verify(ByteArray(0), sig1, key1))
        // TEST 2: one byte, 0x72.
        val key2 = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val sig2 = hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
        assertTrue(Ed25519Fallback.verify(byteArrayOf(0x72), sig2, key2))
        // Another message, another key, a changed signature: none verify.
        assertFalse(Ed25519Fallback.verify(byteArrayOf(0x73), sig2, key2))
        assertFalse(Ed25519Fallback.verify(byteArrayOf(0x72), sig2, key1))
        assertFalse(Ed25519Fallback.verify(byteArrayOf(0x72), sig2.copyOf().also { it[10] = (it[10] + 1).toByte() }, key2))
        assertFalse(Ed25519Fallback.verify(byteArrayOf(0x72), sig2.copyOf(63), key2))
    }
}
