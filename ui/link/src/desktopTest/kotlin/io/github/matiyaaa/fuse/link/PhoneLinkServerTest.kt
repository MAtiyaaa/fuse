package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.ui.shell.app.RemoteCommand
import io.github.matiyaaa.fuse.ui.shell.app.RemoteInput
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real server on a real port, spoken to over plain HTTP like a phone would (and like an attacker would). */
class PhoneLinkServerTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-link-lib").toFile()
        cache = Files.createTempDirectory("fuse-link-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(64))
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    /** One raw HTTP/1.1 request, so every header (Host, Origin) is exactly what we say. */
    private fun http(port: Int, method: String, path: String, body: String? = null, host: String = "127.0.0.1:$port", cookie: String? = null, origin: String? = null): Response {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val bytes = body?.encodeToByteArray()
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nAccept: application/json\r\n")
                if (cookie != null) append("Cookie: $cookie\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                else if (method != "GET") append("Content-Length: 0\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply { write(request.encodeToByteArray()); bytes?.let(::write); flush() }
            val raw = s.getInputStream().readBytes().decodeToString()
            val head = raw.substringBefore("\r\n\r\n")
            val status = head.lineSequence().first().split(' ')[1].toInt()
            val headers = head.lineSequence().drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            var text = raw.substringAfter("\r\n\r\n")
            // Chunked bodies: good enough for small JSON answers.
            if (headers["transfer-encoding"] == "chunked") text = text.lines().filterIndexed { i, _ -> i % 2 == 1 }.joinToString("")
            return Response(status, headers, text)
        }
    }

    @Test
    fun signInIsRequiredAndOnlyTheLocalNetworkByAddressGetsIn(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        var now = 1_000_000L
        val link = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.0.4", clock = { now })
        link.start()
        store.updatePrefs { it.copy(phoneLinkEnabled = true) }
        val state = withTimeout(20_000) { link.state.first { it.running || it.error != null } }
        assertTrue(state.running, state.error)
        val port = link.boundPort()!!
        assertTrue(port >= PhoneLinkServer.PORT)
        assertTrue(state.addresses.all { it.startsWith("http://") && it.endsWith(":$port/") })

        // The app and the session check are open; everything else needs a sign-in.
        val page = http(port, "GET", "/")
        assertEquals(200, page.status)
        assertTrue("<!doctype html" in page.body.lowercase() || "<html" in page.body.lowercase())
        assertTrue(http(port, "GET", "/app.js").headers["content-type"]!!.startsWith("text/javascript"))
        assertEquals("nosniff", page.headers["x-content-type-options"])
        assertTrue("\"signedIn\":false" in http(port, "GET", "/api/session").body)
        assertEquals(401, http(port, "GET", "/api/now").status)
        assertEquals(401, http(port, "GET", "/api/games?query=&system=&sort=title&offset=0&limit=60").status)

        // A domain name in Host (DNS rebinding) or another page's Origin is refused.
        assertEquals(403, http(port, "GET", "/api/session", host = "evil.example:$port").status)
        link.setAccount("player", "secret123").getOrThrow()
        assertEquals(403, http(port, "POST", "/api/login", """{"username":"player","password":"secret123"}""", origin = "http://evil.example").status)

        // Wrong passwords lock sign-in for a minute.
        repeat(4) { assertEquals(401, http(port, "POST", "/api/login", """{"username":"player","password":"wrong"}""").status) }
        val locked = http(port, "POST", "/api/login", """{"username":"player","password":"wrong"}""")
        assertEquals(429, locked.status)
        assertTrue("retryAfterSeconds" in locked.body)
        now += 61_000

        val login = http(port, "POST", "/api/login", """{"username":"player","password":"secret123"}""", origin = "http://127.0.0.1:$port")
        assertEquals(200, login.status)
        val setCookie = login.headers["set-cookie"]!!
        assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie, setCookie)
        val cookie = setCookie.substringBefore(';')

        val nowJson = http(port, "GET", "/api/now", cookie = cookie)
        assertEquals(200, nowJson.status)
        assertTrue("\"fill\":" in nowJson.body && "\"cartridge\":" in nowJson.body, nowJson.body)
        val games = http(port, "GET", "/api/games?query=&system=&sort=title&offset=0&limit=60", cookie = cookie)
        assertTrue("\"total\":2" in games.body, games.body)
        val id = Regex("\"id\":(\\d+)").find(games.body)!!.groupValues[1]
        assertTrue("\"title\":" in http(port, "GET", "/api/games/$id", cookie = cookie).body)

        // Nothing deletes, and made-up art or matches are refused.
        assertFalse(http(port, "DELETE", "/api/games/$id", cookie = cookie).status in 200..299)
        val art = http(port, "POST", "/api/games/$id/art/cover", """{"url":"http://192.168.1.1/x.png"}""", cookie = cookie)
        assertEquals(400, art.status)
        val match = http(port, "POST", "/api/games/$id/identify", """{"providerId":"IGDB","providerGameId":"1"}""", cookie = cookie)
        assertEquals(400, match.status)

        // Signing everyone out ends the session.
        link.signOutAll()
        assertEquals(401, http(port, "GET", "/api/now", cookie = cookie).status)

        store.updatePrefs { it.copy(phoneLinkEnabled = false) }
        withTimeout(10_000) { link.state.first { !it.running } }
    }

    @Test
    fun aPairingCodeSignsInOnceAndThePhoneTypesAndPressesButtons(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        var now = 1_000_000L
        val link = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.2.8", clock = { now })
        link.start()
        store.updatePrefs { it.copy(phoneLinkEnabled = true) }
        assertTrue(withTimeout(20_000) { link.state.first { it.running || it.error != null } }.running)
        val port = link.boundPort()!!

        // The input routes need a sign-in like everything else.
        assertEquals(401, http(port, "GET", "/api/input").status)
        assertEquals(401, http(port, "POST", "/api/pad", """{"button":"A","down":true}""").status)

        // A code from the device's keyboard signs the phone in once; a second use and a made-up one don't.
        val code = link.pairingLink("http://127.0.0.1:$port/")!!.substringAfterLast("/pair/")
        val paired = http(port, "GET", "/pair/$code")
        assertEquals(303, paired.status)
        assertEquals("/#remote", paired.headers["location"])
        val cookie = paired.headers["set-cookie"]!!.substringBefore(';')
        assertTrue("\"signedIn\":true" in http(port, "GET", "/api/session", cookie = cookie).body)
        val again = http(port, "GET", "/pair/$code")
        assertEquals("/?pair=expired", again.headers["location"])
        assertTrue(again.headers["set-cookie"] == null)
        assertEquals("/?pair=expired", http(port, "GET", "/pair/not-a-real-code").headers["location"])

        // A code left for more than two minutes no longer works.
        val late = link.pairingLink("http://127.0.0.1:$port/")!!.substringAfterLast("/pair/")
        now += LinkAuth.PAIR_MS + 1
        assertEquals("/?pair=expired", http(port, "GET", "/pair/$late").headers["location"])

        // No field open: nothing to type into.
        assertEquals("null", http(port, "GET", "/api/input", cookie = cookie).body.trim())
        assertEquals(409, http(port, "PUT", "/api/input/text", """{"id":1,"text":"x"}""", cookie = cookie).status)

        // A field opens on the device: the phone sees it and its text reaches the device.
        val typed = mutableListOf<RemoteCommand>()
        val collecting = scope.launch { RemoteInput.commands.collect { typed += it } }
        val id = RemoteInput.opened("Search", "zel", secret = false, placeholder = "", doneLabel = "Results", cancellable = false)
        val field = http(port, "GET", "/api/input", cookie = cookie).body
        assertTrue("\"title\":\"Search\"" in field && "\"text\":\"zel\"" in field, field)
        assertEquals(200, http(port, "PUT", "/api/input/text", """{"id":$id,"text":"zelda"}""", cookie = cookie).status)
        assertEquals(409, http(port, "PUT", "/api/input/text", """{"id":${id + 1},"text":"other"}""", cookie = cookie).status)
        assertEquals(409, http(port, "POST", "/api/input/cancel", """{"id":$id}""", cookie = cookie).status)
        assertEquals(200, http(port, "POST", "/api/input/done", """{"id":$id}""", cookie = cookie).status)
        RemoteInput.closed(id)

        // A password field's text never goes to the phone.
        val secret = RemoteInput.opened("Password", "hunter22", secret = true, placeholder = "", doneLabel = "Save", cancellable = true)
        val hidden = http(port, "GET", "/api/input", cookie = cookie).body
        assertFalse("hunter22" in hidden, hidden)
        assertTrue("\"length\":8" in hidden, hidden)
        RemoteInput.closed(secret)

        // The phone as a controller: known buttons only, and only while the device allows it.
        assertEquals(200, http(port, "POST", "/api/pad", """{"button":"DPAD_DOWN","down":true}""", cookie = cookie).status)
        assertEquals(400, http(port, "POST", "/api/pad", """{"button":"POWER","down":true}""", cookie = cookie).status)
        store.updatePrefs { it.copy(phoneLinkController = false) }
        assertEquals(403, http(port, "POST", "/api/pad", """{"button":"A","down":true}""", cookie = cookie).status)
        withTimeout(5_000) { while (typed.size < 3) kotlinx.coroutines.delay(20) }
        collecting.cancel()
        assertEquals(RemoteCommand.SetText(id, "zelda"), typed[0])
        assertEquals(RemoteCommand.Submit(id), typed[1])
        assertEquals(RemoteCommand.Pad(io.github.matiyaaa.fuse.model.PadButton.DPAD_DOWN, true), typed[2])

        store.updatePrefs { it.copy(phoneLinkEnabled = false) }
        withTimeout(10_000) { link.state.first { !it.running } }
    }
}
