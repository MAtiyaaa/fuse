package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.obtainium.HtmlLinks
import io.github.matiyaaa.fuse.ui.shell.store.DownloadSink
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Why a download stopped, said for the user. */
internal class DownloadException(message: String) : Exception(message)

/**
 * Downloads an APK for the Store. Every address on the way is HTTPS: redirects are followed one by
 * one here (never by the client), and a hop to anything else stops the download. The size is
 * capped and checked against the free space first, an incomplete download is refused, and when the
 * source published a `sha256:` digest (GitHub does) the file must match it. A download that fails
 * for any reason is deleted.
 */
internal class ApkDownloader(http: HttpClient) {
    private val client = http.config { followRedirects = false }

    suspend fun download(
        url: String,
        sink: DownloadSink,
        expectedBytes: Long?,
        digest: String?,
        freeBytes: Long?,
        headers: Map<String, String> = emptyMap(),
        onProgress: (written: Long, total: Long?) -> Unit,
    ) {
        try {
            var current = url
            var hops = 0
            while (true) {
                if (!HtmlLinks.isHttps(current)) throw DownloadException("The download isn't offered over a secure (HTTPS) address, so Fuse stopped.")
                val next = fetch(current, sink, expectedBytes, freeBytes, headers, onProgress)
                if (next == null) break
                if (++hops > MAX_REDIRECTS) throw DownloadException("The download kept redirecting, so Fuse stopped.")
                current = next
            }
            val actual = sink.finish()
            val wanted = digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.trim()?.lowercase()
            if (wanted != null && wanted != actual) throw DownloadException("The download didn't match the checksum its source published, so it was deleted.")
        } catch (e: CancellationException) {
            sink.discard()
            throw e
        } catch (e: DownloadException) {
            sink.discard()
            throw e
        } catch (e: Exception) {
            sink.discard()
            throw DownloadException("The download failed. Check the connection and try again.")
        }
    }

    /** Downloads [url] into [sink], or returns where it redirects to (resolved, not yet followed). */
    private suspend fun fetch(
        url: String,
        sink: DownloadSink,
        expectedBytes: Long?,
        freeBytes: Long?,
        headers: Map<String, String>,
        onProgress: (Long, Long?) -> Unit,
    ): String? = client.prepareGet(url) {
        headers.forEach { (k, v) -> header(k, v) }
        // A large APK takes longer than an ordinary request may.
        if (client.pluginOrNull(HttpTimeout) != null) {
            timeout {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
        }
    }.execute { response ->
        val status = response.status.value
        if (status in 300..399) {
            val location = response.headers[HttpHeaders.Location] ?: throw DownloadException("The download moved without saying where.")
            return@execute HtmlLinks.resolve(url, location) ?: throw DownloadException("The download moved to an address Fuse won't follow.")
        }
        if (status !in 200..299) throw DownloadException("The download's server answered HTTP $status.")
        val total = response.contentLength()?.takeIf { it > 0 } ?: expectedBytes?.takeIf { it > 0 }
        if (total != null && total > MAX_BYTES) throw DownloadException("The download is larger than Fuse installs (${total / (1024 * 1024)} MB).")
        if (total != null && freeBytes != null && total + SPACE_MARGIN > freeBytes) throw DownloadException("There isn't enough free space for the download.")
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER)
        var written = 0L
        onProgress(0, total)
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (n == 0) {
                if (channel.isClosedForRead) break
                continue
            }
            sink.write(buffer, n)
            written += n
            if (written > MAX_BYTES) throw DownloadException("The download is larger than Fuse installs.")
            onProgress(written, total)
            currentCoroutineContext().ensureActive()
        }
        if (total != null && written != total) throw DownloadException("The download was incomplete. Try again.")
        if (written == 0L) throw DownloadException("The download was empty.")
        null
    }

    companion object {
        const val MAX_REDIRECTS = 8
        const val MAX_BYTES = 1L shl 30
        const val SPACE_MARGIN = 32L * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val DOWNLOAD_TIMEOUT_MS = 60L * 60 * 1000
    }
}
