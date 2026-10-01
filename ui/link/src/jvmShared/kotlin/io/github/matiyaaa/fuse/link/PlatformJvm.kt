package io.github.matiyaaa.fuse.link

import io.nayuki.qrcodegen.QrCode
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val random = SecureRandom()

internal actual fun pbkdf2(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, bytes * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal actual fun secureRandom(bytes: Int): ByteArray = ByteArray(bytes).also(random::nextBytes)

internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal actual fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

internal actual fun base64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

internal actual fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)

internal actual fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        // Wi-Fi and Ethernet before anything else (mobile data, VPNs).
        .sortedBy { i -> if (i.name.startsWith("wlan") || i.name.startsWith("eth") || i.name.startsWith("en")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
}.getOrDefault(emptyList())

actual fun qrModules(text: String): List<BooleanArray>? = runCatching {
    val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    List(qr.size) { y -> BooleanArray(qr.size) { x -> qr.getModule(x, y) } }
}.getOrNull()

internal actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

internal actual fun readFile(path: String, maxBytes: Int): ByteArray? = runCatching {
    val f = File(path)
    if (!f.isFile || f.length() > maxBytes) null else f.readBytes()
}.getOrNull()
