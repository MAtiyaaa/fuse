package io.github.matiyaaa.fuse.transfer

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A hash a source published for a file, to check it against: "md5", "sha1" or "sha256", in hex. */
data class ExpectedHash(val algorithm: String, val hex: String)

/**
 * Downloads one file into a partial file, carrying on from where it stopped when the server allows
 * (HTTP ranges), then checks it. Nothing is ever written to the destination itself: [TransferFiles.place]
 * moves the checked file into place.
 */
object RangedDownload {
    private const val BUFFER = 256 * 1024

    /** Bytes gathered before each write to the drive: fewer, larger writes (an SD card's are slow). */
    private const val WRITE_BUFFER = 1 shl 20

    /**
     * Fetches [url] into [part]. [size] is what the source said the file is, when it did. Returns the
     * bytes in [part]. [request] adds headers (sign-in) to every request, and is asked again on each
     * try so a route that changed is followed.
     */
    suspend fun fetch(
        http: HttpClient,
        url: suspend () -> String,
        part: File,
        size: Long?,
        io: TransferIo,
        doneBefore: Long = 0,
        totalOverall: Long? = size,
        request: HttpRequestBuilder.() -> Unit = {},
    ): Long = fetchInto(http, url, part, size, io, doneBefore, totalOverall, digest = null, request)

    /**
     * As [fetch], then checks the file as [TransferFiles.verify] does, by a hash worked out as the
     * bytes arrive: only what an earlier try left in [part] is read again, never the whole file.
     */
    suspend fun fetchVerified(
        http: HttpClient,
        url: suspend () -> String,
        part: File,
        size: Long?,
        hash: ExpectedHash?,
        io: TransferIo,
        doneBefore: Long = 0,
        totalOverall: Long? = size,
        request: HttpRequestBuilder.() -> Unit = {},
    ): Long {
        val digest = hash?.takeIf { it.hex.isNotBlank() }?.let { TransferFiles.messageDigest(it.algorithm) }
        val have = fetchInto(http, url, part, size, io, doneBefore, totalOverall, digest, request)
        io.phase(TransferPhase.VERIFYING)
        TransferFiles.verify(part, size, hash, digested = digest?.let { TransferFiles.hex(it.digest()) })
        return have
    }

    private suspend fun fetchInto(
        http: HttpClient,
        url: suspend () -> String,
        part: File,
        size: Long?,
        io: TransferIo,
        doneBefore: Long,
        totalOverall: Long?,
        digest: MessageDigest?,
        request: HttpRequestBuilder.() -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        // Already whole (a restart after the last byte arrived): nothing to ask for.
        if (size != null && have == size && size > 0) {
            io.progress(doneBefore + have, totalOverall)
            digest?.let { TransferFiles.feed(it, part, have) }
            return@withContext have
        }
        if (size != null && have > size) {
            part.delete()
            have = 0
        }
        val address = url()
        // A big file takes as long as it takes: only a stalled connection (the socket timeout) ends it.
        val timeouts = http.pluginOrNull(HttpTimeout) != null
        http.prepareGet(address) {
            if (timeouts) timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            request()
            if (have > 0) header(HttpHeaders.Range, "bytes=$have-")
        }.execute { resp ->
            when {
                resp.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                    // What is here is more than the server has: start again.
                    part.delete()
                    throw TransferInterrupted("The server's copy changed; starting this file again.")
                }
                resp.status == HttpStatusCode.Unauthorized -> throw TransferFailure("The server no longer accepts Fuse's sign-in. Sign in again in Settings.", retryable = false)
                resp.status == HttpStatusCode.Forbidden -> throw TransferFailure("The server doesn't allow this download for your account.", retryable = false)
                resp.status == HttpStatusCode.NotFound -> throw TransferFailure("The server doesn't have this file any more.", retryable = false)
                resp.status.value >= 500 -> throw TransferInterrupted("The server had a problem (${resp.status.value}).")
                resp.status.value !in 200..299 -> throw TransferFailure("The server said no (${resp.status.value}).")
            }
            val resuming = resp.status == HttpStatusCode.PartialContent && have > 0 && contentRangeStart(resp) == have
            if (!resuming) have = 0
            digest?.reset()
            if (digest != null && have > 0) TransferFiles.feed(digest, part, have)
            val length = resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val expected = size ?: length?.let { it + have }
            io.phase(TransferPhase.TRANSFERRING)
            io.progress(doneBefore + have, totalOverall ?: expected?.let { doneBefore + it })
            val channel = resp.bodyAsChannel()
            val file = FileOutputStream(part, resuming)
            // Closed on every way out, a pause or a dropped line too, so what arrived is on the drive to carry on from.
            BufferedOutputStream(file, WRITE_BUFFER).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    io.throttle(n)
                    try {
                        out.write(buffer, 0, n)
                    } catch (e: IOException) {
                        throw writeFailure(part, e)
                    }
                    digest?.update(buffer, 0, n)
                    have += n
                    if (expected != null && have > expected) throw TransferFailure("The server sent more than the file's size. Try again later.")
                    io.progress(doneBefore + have, totalOverall ?: expected?.let { doneBefore + it })
                }
                try {
                    out.flush()
                } catch (e: IOException) {
                    throw writeFailure(part, e)
                }
                file.fd.sync()
            }
            if (expected != null && have < expected) throw TransferInterrupted("The connection closed before the file was complete.")
        }
        have
    }

    private fun contentRangeStart(resp: HttpResponse): Long? =
        resp.headers[HttpHeaders.ContentRange]?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

    /** What a failed write means: a full drive says so; a drive that went is waited for. */
    fun writeFailure(file: File, e: IOException): Exception {
        val m = e.message.orEmpty().lowercase()
        return when {
            "no space" in m || "enospc" in m || "not enough space" in m || "disk full" in m ->
                TransferFailure("There's no room left where this goes. Free some space, then try again.")
            !file.parentFile.exists() -> TransferInterrupted("The folder this goes to went away.", e)
            else -> TransferInterrupted(e.message ?: "Writing failed", e)
        }
    }
}

/** Checking downloaded files and putting them in place. */
object TransferFiles {
    /** The hex digest of [file] with [algorithm] ("md5", "sha1", "sha256"). */
    fun digest(file: File, algorithm: String): String {
        val md = messageDigest(algorithm)
        feed(md, file, Long.MAX_VALUE)
        return hex(md.digest())
    }

    /** The digest for [algorithm] ("md5", "sha1", anything else SHA-256). */
    fun messageDigest(algorithm: String): MessageDigest = MessageDigest.getInstance(
        when (algorithm.lowercase()) {
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            else -> "SHA-256"
        },
    )

    /** Adds the first [upTo] bytes of [file] to [md]. */
    fun feed(md: MessageDigest, file: File, upTo: Long) {
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            var left = upTo
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Checks [part] against what the source said ([size], [hash]). A file of the wrong size or hash is
     * removed (it can't be trusted to carry on from) and the transfer fails, to start over when tried again.
     * [digested] is [part]'s hash already worked out as it was written, when it was.
     */
    fun verify(part: File, size: Long?, hash: ExpectedHash?, digested: String? = null) {
        if (!part.isFile) throw TransferFailure("The downloaded file went missing before it could be checked.")
        if (size != null && part.length() != size) {
            part.delete()
            throw TransferFailure("The server sent a file of the wrong size. Try again.")
        }
        if (hash != null && hash.hex.isNotBlank()) {
            val got = digested ?: digest(part, hash.algorithm)
            if (!got.equals(hash.hex.trim(), ignoreCase = true)) {
                part.delete()
                throw TransferFailure("The file didn't match its checksum, so it wasn't kept. Try again.")
            }
        }
    }

    /**
     * Moves [from] to [to] in one step where the drive allows, never over a file that is already there
     * unless [replace]. Parent folders are made. Returns the file now at [to].
     */
    fun place(from: File, to: File, replace: Boolean = false): File {
        to.parentFile?.mkdirs()
        if (to.exists() && !replace) throw TransferFailure("Something named ${to.name} is already there, so it was left alone.", retryable = false)
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, *(if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()))
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), *(if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()))
        }
        return to
    }

    /** A partial file's name beside [destination]: hidden, and never mistaken for a game by a scan. */
    fun partFor(destination: File): File = File(destination.parentFile, ".${destination.name}.fuse-part")

    /** Free bytes on the drive holding [dir] (its nearest existing parent). */
    fun freeSpace(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return d?.usableSpace ?: 0L
    }

    /** Reads [length] bytes of [file] from [offset] (an upload's chunk). */
    fun readChunk(file: File, offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        val out = ByteArray(length)
        raf.readFully(out)
        out
    }
}
