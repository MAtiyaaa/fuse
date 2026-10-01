package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Screenshots and recordings through Phone Link: listed, played, downloaded one by one or as a zip. */
class PhoneLinkCapturesTest {
    private lateinit var dir: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("fuse-link-captures").toFile()
        cache = Files.createTempDirectory("fuse-link-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        cache.deleteRecursively()
    }

    /** Captures as plain files, like the Android library but without MediaStore. */
    private class FileCaptures(private val dir: File) : LinkCaptures {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        override suspend fun list(): List<LinkCapture> = dir.listFiles().orEmpty().sortedBy { it.name }.mapIndexed { i, f ->
            val video = f.extension == "mp4"
            LinkCapture("file:${f.name}", f.name, video, if (video) "video/mp4" else "image/png", f.length(), 1_790_862_302_000L + i * 60_000L, 1920, 1080, if (video) 5_000 else 0)
        }

        override suspend fun thumbnail(key: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        override suspend fun open(key: String): CaptureReader? {
            val f = File(dir, key.removePrefix("file:")).takeIf { it.isFile } ?: return null
            val file = RandomAccessFile(f, "r")
            return object : CaptureReader {
                override val length = file.length()
                override fun seek(position: Long) = file.seek(position)
                override fun read(buffer: ByteArray, max: Int) = file.read(buffer, 0, max)
                override fun close() = file.close()
            }
        }
    }

    private class Response(val status: Int, val headers: Map<String, String>, val bytes: ByteArray) {
        val body: String get() = bytes.decodeToString()
    }

    /** One raw HTTP/1.1 request with any headers, read to the end (chunked bodies decoded). */
    private fun http(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        cookie: String? = null,
        origin: String? = null,
        host: String = "127.0.0.1:$port",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 20_000
            val bytes = body?.encodeToByteArray()
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
                if (cookie != null) append("Cookie: $cookie\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                else if (method != "GET") append("Content-Length: 0\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply { write(request.encodeToByteArray()); bytes?.let(::write); flush() }
            val raw = s.getInputStream().readBytes()
            val split = (0 until raw.size - 3).first { raw[it] == '\r'.code.toByte() && raw[it + 1] == '\n'.code.toByte() && raw[it + 2] == '\r'.code.toByte() && raw[it + 3] == '\n'.code.toByte() }
            val head = raw.copyOfRange(0, split).decodeToString()
            val status = head.lineSequence().first().split(' ')[1].toInt()
            val map = head.lineSequence().drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            var content = raw.copyOfRange(split + 4, raw.size)
            if (map["transfer-encoding"] == "chunked") content = dechunk(content)
            return Response(status, map, content)
        }
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var end = i
            while (!(data[end] == '\r'.code.toByte() && data[end + 1] == '\n'.code.toByte())) end++
            val size = data.copyOfRange(i, end).decodeToString().substringBefore(';').trim().toInt(16)
            if (size == 0) break
            out.write(data, end + 2, size)
            i = end + 2 + size + 2
        }
        return out.toByteArray()
    }

    @Test
    fun capturesAreListedPlayedAndDownloaded(): Unit = runBlocking {
        val png = Random(1).nextBytes(300_000)
        val mp4 = Random(2).nextBytes(1_200_000)
        File(dir, "Fuse 2026-10-01 13-45-02.png").writeBytes(png)
        File(dir, "Fuse 2026-10-01 13-46-10.mp4").writeBytes(mp4)
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        var now = 1_790_862_400_000L
        val link = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.1.4", captures = FileCaptures(dir), clock = { now })
        link.start()
        store.updatePrefs { it.copy(phoneLinkEnabled = true) }
        assertTrue(withTimeout(20_000) { link.state.first { it.running || it.error != null } }.running)
        val port = link.boundPort()!!

        assertTrue("\"captures\":true" in http(port, "GET", "/api/session").body)
        assertEquals(401, http(port, "GET", "/api/captures").status)
        link.setAccount("player", "secret123").getOrThrow()
        val cookie = http(port, "POST", "/api/login", """{"username":"player","password":"secret123"}""").headers["set-cookie"]!!.substringBefore(';')

        // Newest first, with ids the server made up.
        val list = http(port, "GET", "/api/captures", cookie = cookie)
        assertEquals(200, list.status)
        val ids = Regex("\"id\":\"([^\"]+)\"").findAll(list.body).map { it.groupValues[1] }.toList()
        assertEquals(2, ids.size, list.body)
        assertTrue(list.body.indexOf("13-46-10.mp4") < list.body.indexOf("13-45-02.png"), "newest first")
        assertFalse("file:" in list.body, "the device's own keys stay on the device")
        val (videoId, pictureId) = ids

        // The whole picture, shown in the page.
        val picture = http(port, "GET", "/api/captures/$pictureId", cookie = cookie)
        assertEquals(200, picture.status)
        assertContentEquals(png, picture.bytes)
        assertEquals("image/png", picture.headers["content-type"])
        assertEquals("bytes", picture.headers["accept-ranges"])
        assertTrue(picture.headers["content-disposition"]!!.startsWith("inline"))

        // Byte ranges, so a video seeks.
        val part = http(port, "GET", "/api/captures/$videoId", cookie = cookie, headers = mapOf("Range" to "bytes=100-199"))
        assertEquals(206, part.status)
        assertEquals("bytes 100-199/${mp4.size}", part.headers["content-range"])
        assertContentEquals(mp4.copyOfRange(100, 200), part.bytes)
        val tail = http(port, "GET", "/api/captures/$videoId", cookie = cookie, headers = mapOf("Range" to "bytes=-50"))
        assertContentEquals(mp4.copyOfRange(mp4.size - 50, mp4.size), tail.bytes)
        val past = http(port, "GET", "/api/captures/$videoId", cookie = cookie, headers = mapOf("Range" to "bytes=${mp4.size}-"))
        assertEquals(416, past.status)
        assertEquals("bytes */${mp4.size}", past.headers["content-range"])

        // Only ids this server handed out work: never a key, a path or a guess.
        assertEquals(404, http(port, "GET", "/api/captures/file:Fuse%202026-10-01%2013-45-02.png", cookie = cookie).status)
        assertEquals(404, http(port, "GET", "/api/captures/AAAAAAAAAAAAAAAA", cookie = cookie).status)

        val thumb = http(port, "GET", "/api/captures/$pictureId/thumb", cookie = cookie)
        assertEquals("image/jpeg", thumb.headers["content-type"])
        assertTrue(thumb.headers["cache-control"]!!.startsWith("private"))

        // Download links: asked for from the app's own page, then they work without the cookie.
        assertEquals(403, http(port, "POST", "/api/captures/download", """{"ids":["$pictureId"]}""", cookie = cookie, origin = "http://evil.example").status)
        assertEquals(401, http(port, "POST", "/api/captures/download", """{"ids":["$pictureId"]}""").status)
        assertEquals(404, http(port, "POST", "/api/captures/download", """{"ids":["$pictureId","AAAAAAAAAAAAAAAA"]}""", cookie = cookie).status)
        val one = http(port, "POST", "/api/captures/download", """{"ids":["$pictureId"]}""", cookie = cookie, origin = "http://127.0.0.1:$port")
        assertEquals(200, one.status, one.body)
        val oneUrl = Regex("\"url\":\"([^\"]+)\"").find(one.body)!!.groupValues[1]
        val file = http(port, "GET", oneUrl)
        assertEquals(200, file.status)
        assertContentEquals(png, file.bytes)
        assertEquals("attachment; filename=\"Fuse 2026-10-01 13-45-02.png\"", file.headers["content-disposition"])
        // A download manager resuming.
        val resumed = http(port, "GET", oneUrl, headers = mapOf("Range" to "bytes=299000-"))
        assertEquals(206, resumed.status)
        assertContentEquals(png.copyOfRange(299_000, png.size), resumed.bytes)
        assertEquals(403, http(port, "GET", oneUrl, host = "evil.example:$port").status)

        // Several at once: one zip, read back file by file.
        val both = http(port, "POST", "/api/captures/download", """{"ids":["$pictureId","$videoId"]}""", cookie = cookie)
        assertTrue("\"count\":2" in both.body && ".zip\"" in both.body, both.body)
        val zip = http(port, "GET", Regex("\"url\":\"([^\"]+)\"").find(both.body)!!.groupValues[1])
        assertEquals(200, zip.status)
        assertEquals("application/zip", zip.headers["content-type"])
        assertTrue(zip.headers["content-disposition"]!!.startsWith("attachment; filename=\"Fuse captures "))
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip.bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        assertEquals(setOf("Fuse 2026-10-01 13-45-02.png", "Fuse 2026-10-01 13-46-10.mp4"), entries.keys)
        assertContentEquals(png, entries["Fuse 2026-10-01 13-45-02.png"])
        assertContentEquals(mp4, entries["Fuse 2026-10-01 13-46-10.mp4"])

        // Links run out after ten minutes.
        now += CaptureShare.DOWNLOAD_MS + 1
        assertEquals(404, http(port, "GET", oneUrl).status)

        // Nothing deletes a capture.
        assertFalse(http(port, "DELETE", "/api/captures/$pictureId", cookie = cookie).status in 200..299)
        assertTrue(File(dir, "Fuse 2026-10-01 13-45-02.png").exists())

        store.updatePrefs { it.copy(phoneLinkEnabled = false) }
        withTimeout(10_000) { link.state.first { !it.running } }
    }

    @Test
    fun withoutCapturesThePhoneHidesTheTab(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        val link = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.1.4")
        link.start()
        store.updatePrefs { it.copy(phoneLinkEnabled = true) }
        assertTrue(withTimeout(20_000) { link.state.first { it.running || it.error != null } }.running)
        val port = link.boundPort()!!
        assertTrue("\"captures\":false" in http(port, "GET", "/api/session").body)
        link.setAccount("player", "secret123").getOrThrow()
        val cookie = http(port, "POST", "/api/login", """{"username":"player","password":"secret123"}""").headers["set-cookie"]!!.substringBefore(';')
        val list = http(port, "GET", "/api/captures", cookie = cookie)
        assertTrue("\"available\":false" in list.body, list.body)
        assertNotNull(list.headers["cache-control"])
        store.updatePrefs { it.copy(phoneLinkEnabled = false) }
        withTimeout(10_000) { link.state.first { !it.running } }
    }
}
