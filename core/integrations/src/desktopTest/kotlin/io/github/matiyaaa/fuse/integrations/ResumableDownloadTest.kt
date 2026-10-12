package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.github.ResumableDownload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ResumableDownloadTest {
    private val root: File = Files.createTempDirectory("fuse-resume").toFile()
    private val whole = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(whole).joinToString("") { "%02x".format(it) }
    private val ranges = ArrayList<String?>()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A release server that honours ranges, or ignores them when [ranges] is false. */
    private fun server(honoursRanges: Boolean = true) = HttpClient(MockEngine { request ->
        val range = request.headers[HttpHeaders.Range]
        ranges += range
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        when {
            from != null && honoursRanges && from >= whole.size -> respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable)
            from != null && honoursRanges -> {
                val rest = whole.copyOfRange(from.toInt(), whole.size)
                respond(rest, HttpStatusCode.PartialContent, headersOf(
                    HttpHeaders.ContentRange to listOf("bytes $from-${whole.size - 1}/${whole.size}"),
                    HttpHeaders.ContentLength to listOf(rest.size.toString()),
                ))
            }
            else -> respond(whole, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, whole.size.toString()))
        }
    })

    private fun partOf(bytes: Int, identity: String = "fuse-0.4.3"): File {
        val file = File(root, "Fuse.AppImage.part")
        file.writeBytes(whole.copyOfRange(0, bytes))
        ResumableDownload.noteOf(file).writeText(identity)
        return file
    }

    @Test
    fun anInterruptedDownloadCarriesOnFromWhereItStopped() = runBlocking {
        val file = partOf(120_000)
        val result = ResumableDownload.fetch(server(), "https://example.invalid/fuse", file, whole.size.toLong(), "fuse-0.4.3")
        assertEquals(listOf<String?>("bytes=120000-"), ranges)
        assertContentEquals(whole, file.readBytes())
        assertEquals(sha, result, "the checksum covers the whole file, the kept part included")
    }

    @Test
    fun aServerThatSendsItWholeOrAnotherDownloadStartsOver() = runBlocking {
        val file = partOf(120_000)
        assertEquals(sha, ResumableDownload.fetch(server(honoursRanges = false), "https://example.invalid/fuse", file, whole.size.toLong(), "fuse-0.4.3"))
        assertContentEquals(whole, file.readBytes())
        // A part from another download (another version) isn't carried on.
        ranges.clear()
        val other = partOf(120_000, identity = "fuse-0.4.2")
        assertEquals(sha, ResumableDownload.fetch(server(), "https://example.invalid/fuse", other, whole.size.toLong(), "fuse-0.4.3"))
        assertEquals(listOf<String?>(null), ranges)
        assertContentEquals(whole, other.readBytes())
    }

    @Test
    fun aDownloadThatIsAllThereIsDoneAndForgettingClearsIt() = runBlocking {
        val file = partOf(whole.size)
        assertEquals(sha, ResumableDownload.fetch(server(), "https://example.invalid/fuse", file, whole.size.toLong(), "fuse-0.4.3"))
        assertContentEquals(whole, file.readBytes())
        ResumableDownload.forget(file)
        assertFalse(file.exists() || ResumableDownload.noteOf(file).exists())
        // A fresh download sends no range.
        ranges.clear()
        ResumableDownload.fetch(server(), "https://example.invalid/fuse", file, whole.size.toLong(), "fuse-0.4.3")
        assertNull(ranges.single())
    }
}
