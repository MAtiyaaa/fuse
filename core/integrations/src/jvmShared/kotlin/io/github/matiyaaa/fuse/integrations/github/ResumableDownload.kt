package io.github.matiyaaa.fuse.integrations.github

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release file so an interrupted download carries on where it stopped: what arrived is
 * kept in the file, with a note of which download it belongs to beside it (`<file>.from`), and the
 * next attempt asks only for the rest (an HTTP range). A server that sends the whole file again, a
 * different download behind the same name, or a part that doesn't fit starts it over. The SHA-256
 * it returns covers the whole file, so the checksum GitHub publishes still decides whether it is
 * kept: on a mismatch the caller [forget]s it.
 */
object ResumableDownload {
    /**
     * Fetches [url] into [file] (resuming what is there for the same [identity]) and returns the
     * file's SHA-256 as lower-case hex. [onProgress] gets 0..1 when the size is known.
     */
    suspend fun fetch(
        http: HttpClient,
        url: String,
        file: File,
        sizeBytes: Long,
        identity: String,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val note = noteOf(file)
        file.parentFile?.mkdirs()
        var have = if (file.isFile && runCatching { note.readText() }.getOrNull() == identity) file.length() else 0L
        if (sizeBytes > 0 && have > sizeBytes) have = 0L
        if (have == 0L) file.delete()
        note.writeText(identity)
        for (attempt in 0 until 2) {
            val sha = attemptFrom(http, url, file, have, sizeBytes, onProgress)
            if (sha != null) return@withContext sha
            // The server wouldn't carry on from there: once more from the start.
            file.delete()
            have = 0L
        }
        throw IOException("The download couldn't carry on. Try again.")
    }

    /** The download is done with (installed, or refused): its note goes, and the file when [file] is still there. */
    fun forget(file: File) {
        file.delete()
        noteOf(file).delete()
    }

    /** The note beside a finished download whose file was moved to where it belongs. */
    fun finished(file: File) {
        noteOf(file).delete()
    }

    fun noteOf(file: File) = File(file.path + ".from")

    /** One request from byte [from]; the file's SHA-256, or null when it has to start over. */
    private suspend fun attemptFrom(http: HttpClient, url: String, file: File, from: Long, sizeBytes: Long, onProgress: (Float) -> Unit): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        if (from > 0) hashInto(digest, file)
        return http.prepareGet(url) {
            // Large files: no overall deadline, but a stalled connection still times out.
            timeout {
                requestTimeoutMillis = 60 * 60 * 1000L
                socketTimeoutMillis = 60_000L
            }
            if (from > 0) header(HttpHeaders.Range, "bytes=$from-")
        }.execute { response ->
            if (from > 0 && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                // Nothing after what is here: it is all here when its size says so.
                return@execute if (sizeBytes > 0 && from == sizeBytes) hex(digest) else null
            }
            if (!response.status.isSuccess()) throw IOException("The download failed (HTTP ${response.status.value}).")
            val resumed = from > 0 && response.status == HttpStatusCode.PartialContent &&
                response.headers[HttpHeaders.ContentRange]?.startsWith("bytes $from-") == true
            if (from > 0 && !resumed) {
                if (response.status == HttpStatusCode.PartialContent) return@execute null
                // The whole file came instead: start it over from this answer.
                digest.reset()
            }
            val start = if (resumed) from else 0L
            val length = response.contentLength()
            val total = length?.let { it + start } ?: sizeBytes.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(128 * 1024)
            var done = start
            // In tenths of a percent: a large download on a slow line moved by whole percents seemed stuck.
            var lastReported = -1
            FileOutputStream(file, resumed).use { out ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total != null) {
                        val permille = (done * 1000 / total).toInt().coerceIn(0, 999)
                        if (permille != lastReported) {
                            lastReported = permille
                            onProgress(permille / 1000f)
                        }
                    }
                }
                out.fd.sync()
            }
            if (length != null && done != start + length) {
                throw IOException("The download stopped before the end. Download it again to carry on from there.")
            }
            hex(digest)
        }
    }

    private fun hashInto(digest: MessageDigest, file: File) {
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
    }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }
}
