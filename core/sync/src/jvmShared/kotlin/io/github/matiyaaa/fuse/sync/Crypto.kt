package io.github.matiyaaa.fuse.sync

import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Fuse Sync's cryptography, from the platform's own providers only. */
object SyncCrypto {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** SHA-256 of a stream, read in chunks (a save can be large). */
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun hmac(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray()))
    }

    /** A random secret, URL-safe text. */
    fun token(bytes: Int = 32): String = b64.encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun decode(text: String): ByteArray = unb64.decode(text)
    fun encode(bytes: ByteArray): String = b64.encodeToString(bytes)

    /**
     * A pairing code to read off the host's screen: eight characters from an alphabet without the
     * ones people mistake for each other (0/O, 1/I/L), about 40 bits.
     */
    fun pairingCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** A PIN or password, stretched with a salt: what the host keeps instead of the PIN itself. */
    fun hashSecret(secret: String, salt: String = token(16), iterations: Int = PIN_ITERATIONS): String {
        val key = pbkdf2(secret, decode(salt), iterations, 32)
        return "pbkdf2-sha256\$$iterations\$$salt\$${encode(key)}"
    }

    /** Checks [secret] against what [hashSecret] made, in constant time. */
    fun verifySecret(secret: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = decode(parts[3])
        val actual = pbkdf2(secret, decode(parts[2]), iterations, expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(secret: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, bytes * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /**
     * Seals [plain] with a key stretched from [code]: AES-GCM, so a wrong code or a changed message
     * fails to open rather than giving garbage. Returns the sealed text; [salt] goes with it.
     */
    fun seal(plain: ByteArray, code: String, salt: String): String {
        val key = SecretKeySpec(pbkdf2(code.uppercase(), decode(salt), SEAL_ITERATIONS, 32), "AES")
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return encode(iv + cipher.doFinal(plain))
    }

    /** Opens what [seal] sealed, or null when the code is wrong or the text was changed. */
    fun open(sealed: String, code: String, salt: String): ByteArray? = runCatching {
        val all = decode(sealed)
        val key = SecretKeySpec(pbkdf2(code.uppercase(), decode(salt), SEAL_ITERATIONS, 32), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all.copyOfRange(0, 12)))
        cipher.doFinal(all.copyOfRange(12, all.size))
    }.getOrNull()

    fun constantEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = chars[v ushr 4]
            out[i * 2 + 1] = chars[v and 0x0F]
        }
        return String(out)
    }

    /** Stretching for profile PINs: slow enough to make guessing a stolen hash costly. */
    const val PIN_ITERATIONS = 120_000

    /** Stretching for the pairing seal: a captured seal can't be opened by trying codes quickly. */
    const val SEAL_ITERATIONS = 200_000
}

/**
 * Every call from a device carries a signature instead of its secret: HMAC-SHA256 over the method,
 * the path, the time, a one-off nonce and the body's hash. The secret itself never crosses the
 * network after pairing, a captured call can't be replayed (the host keeps the nonces it has seen
 * for the window the time allows), and a changed body or path breaks the signature.
 */
object RequestSigning {
    const val DEVICE = "X-Fuse-Device"
    const val TIME = "X-Fuse-Time"
    const val NONCE = "X-Fuse-Nonce"
    const val SIGNATURE = "X-Fuse-Signature"
    const val TICKET = "X-Fuse-Ticket"

    /** How far a call's time may be from the host's, either way. */
    const val WINDOW_MS = 5 * 60_000L

    fun message(method: String, path: String, time: Long, nonce: String, bodyHash: String): String =
        "${method.uppercase()}\n$path\n$time\n$nonce\n$bodyHash"

    fun sign(secret: String, method: String, path: String, time: Long, nonce: String, body: ByteArray): String =
        SyncCrypto.hmac(SyncCrypto.decode(secret), message(method, path, time, nonce, SyncCrypto.sha256(body)))
}
