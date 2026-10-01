package io.github.matiyaaa.fuse.link

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toOutputStream
import io.nayuki.qrcodegen.QrCode
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual suspend fun writeZip(channel: ByteWriteChannel, items: List<ZipItem>) = withContext(Dispatchers.IO) {
    // Deflate at level 0 (no compression: pictures and videos are compressed already). Sizes and
    // checksums follow each file, so one pass over the files is enough and every unzip tool reads
    // it. Java switches to Zip64 by itself past 4 GB.
    ZipOutputStream(channel.toOutputStream()).use { zip ->
        zip.setLevel(Deflater.NO_COMPRESSION)
        val buffer = ByteArray(256 * 1024)
        for (item in items) {
            val reader = item.open() ?: continue
            reader.use {
                zip.putNextEntry(ZipEntry(item.name).apply { time = item.time })
                while (true) {
                    val n = it.read(buffer, buffer.size)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                }
                zip.closeEntry()
            }
        }
    }
}
