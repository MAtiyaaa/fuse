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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Socket
import java.nio.file.Files
import io.github.matiyaaa.fuse.data.settings.ExtensionScope
import io.github.matiyaaa.fuse.data.settings.Extensions
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

        // Fuse's own downloads are in "now"; the phone pauses and resumes them, marks a favourite,
        // looks for new games and shows a game's page on the device (starting it stays a press there).
        assertTrue("\"transfers\":{" in nowJson.body && "\"paused\":" in nowJson.body, nowJson.body)
        assertEquals(200, http(port, "POST", "/api/transfers/pause", cookie = cookie).status)
        assertEquals(200, http(port, "POST", "/api/transfers/resume", cookie = cookie).status)
        val fav = http(port, "PUT", "/api/games/$id/favorite", """{"favorite":true}""", cookie = cookie)
        assertEquals(200, fav.status, fav.body)
        withTimeout(10_000) { while (store.library.games(GameQuery()).first().none { it.id.value == id.toLong() && it.favorite }) kotlinx.coroutines.delay(20) }
        assertEquals(400, http(port, "PUT", "/api/games/$id/favorite", """{"favorite":"maybe"}""", cookie = cookie).status)
        assertEquals(404, http(port, "PUT", "/api/games/999999/favorite", """{"favorite":true}""", cookie = cookie).status)
        assertEquals(200, http(port, "POST", "/api/rescan", cookie = cookie).status)

        // The Journal, written on the phone: a status, a rating and a note, each kept on the device.
        assertTrue("\"journal\":{" in http(port, "GET", "/api/games/$id", cookie = cookie).body)
        val marked = http(port, "PUT", "/api/games/$id/journal", """{"status":"BEATEN","rating":9,"note":"  The last boss took a week.  "}""", cookie = cookie)
        assertEquals(200, marked.status, marked.body)
        val journal = store.library.game(io.github.matiyaaa.fuse.model.GameId(id.toLong())).first()!!.game.journal
        assertEquals(io.github.matiyaaa.fuse.model.PlayStatus.BEATEN, journal.status)
        assertEquals(9, journal.rating)
        assertEquals("The last boss took a week.", journal.note)
        // What isn't sent stays; a status of null takes it out; nonsense is refused.
        assertEquals(200, http(port, "PUT", "/api/games/$id/journal", """{"status":null}""", cookie = cookie).status)
        val after = store.library.game(io.github.matiyaaa.fuse.model.GameId(id.toLong())).first()!!.game.journal
        assertEquals(null, after.status)
        assertEquals(9, after.rating)
        assertEquals(400, http(port, "PUT", "/api/games/$id/journal", """{"status":"WON"}""", cookie = cookie).status)
        assertEquals(400, http(port, "PUT", "/api/games/$id/journal", """{"rating":11}""", cookie = cookie).status)
        // A game's achievements: none known here (no account), said so; a game not here isn't found.
        assertTrue("\"available\":false" in http(port, "GET", "/api/games/$id/achievements", cookie = cookie).body)
        assertEquals(404, http(port, "GET", "/api/games/999999/achievements", cookie = cookie).status)
        // No game running here, and this device can't close one: said so, nothing else happens.
        assertTrue("\"canQuit\":false" in http(port, "GET", "/api/now", cookie = cookie).body)
        assertTrue("error" in http(port, "POST", "/api/playing/quit", cookie = cookie).body)
        val shown = async { io.github.matiyaaa.fuse.ui.shell.app.ExternalRequests.requests.first() }
        kotlinx.coroutines.yield()
        assertEquals(200, http(port, "POST", "/api/games/$id/open", cookie = cookie).status)
        assertEquals(io.github.matiyaaa.fuse.ui.shell.app.ExternalRequest.OpenGame(io.github.matiyaaa.fuse.model.GameId(id.toLong())), withTimeout(5_000) { shown.await() })

        // Nothing deletes, and made-up art or matches are refused.
        assertFalse(http(port, "DELETE", "/api/games/$id", cookie = cookie).status in 200..299)
        val art = http(port, "POST", "/api/games/$id/art/cover", """{"url":"http://192.168.1.1/x.png"}""", cookie = cookie)
        assertEquals(400, art.status)
        val match = http(port, "POST", "/api/games/$id/identify", """{"providerId":"IGDB","providerGameId":"1"}""", cookie = cookie)
        assertEquals(400, match.status)

        // What the person takes from Phone Link (Settings, Accounts, Phone Link) is refused, with where to allow it.
        store.updatePrefs { it.copy(extensions = Extensions.set(Extensions.set(it.extensions, Extensions.PHONE_LINK, ExtensionScope.CHANGE_LIBRARY, false), Extensions.PHONE_LINK, ExtensionScope.CAPTURES, false)) }
        withTimeout(10_000) { while (Extensions.allows(store.prefs.value.extensions, Extensions.PHONE_LINK, ExtensionScope.CAPTURES)) kotlinx.coroutines.delay(20) }
        val fill = http(port, "POST", "/api/games/$id/fill", cookie = cookie)
        assertEquals(403, fill.status)
        assertTrue("Settings, Accounts, Phone Link" in fill.body, fill.body)
        assertEquals(403, http(port, "GET", "/api/captures", cookie = cookie).status)
        assertEquals(200, http(port, "GET", "/api/games/$id", cookie = cookie).status, "browsing stays allowed")

        // Signing everyone out ends the session.
        link.signOutAll()
        assertEquals(401, http(port, "GET", "/api/now", cookie = cookie).status)

        store.updatePrefs { it.copy(phoneLinkEnabled = false) }
        withTimeout(10_000) { link.state.first { !it.running } }
    }

    @Test
    fun aGamesAchievementsReachThePhoneTheEarnedOnesNewestFirst(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val base = createFuseStore(services, scope)
        base.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { base.library.games(GameQuery()).first { it.size == 2 } }.first()
        val media = "https://media.retroachievements.org/Badge"
        fun a(n: Long, title: String, points: Int, earned: Long?, hardcore: Long?) =
            io.github.matiyaaa.fuse.model.Achievement(n, 1, title, "$title, the description", points, "$media/$n.png", "$media/${n}_lock.png", earned, hardcore, displayOrder = n.toInt())
        val set = io.github.matiyaaa.fuse.model.AchievementState(
            1, game.title, "SNES", null, total = 3, earned = 2, earnedHardcore = 1, points = 30, pointsEarned = 15, highestAward = "beaten-hardcore",
            achievements = listOf(a(1, "First steps", 5, 100, null), a(2, "The boss", 10, 200, 200), a(3, "Everything", 15, null, null)),
            fetchedAt = 0,
        )
        val store = object : io.github.matiyaaa.fuse.ui.shell.store.FuseStore by base {
            override val achievements = object : io.github.matiyaaa.fuse.ui.shell.store.AchievementOps by base.achievements {
                override suspend fun forGame(game: io.github.matiyaaa.fuse.model.GameId) = set
            }
        }
        val reply = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.0.4").api.achievements(game.id.value)
        assertEquals(200, reply.status.value)
        val body = reply.body as kotlinx.serialization.json.JsonObject
        assertEquals("true", body["available"].toString())
        assertEquals("15", body["pointsEarned"].toString())
        val items = body["items"] as kotlinx.serialization.json.JsonArray
        // Earned, the newest first, then the rest in the set's order, each with the badge it shows.
        assertEquals(listOf("\"The boss\"", "\"First steps\"", "\"Everything\""), items.map { (it as kotlinx.serialization.json.JsonObject)["title"].toString() })
        assertEquals("\"$media/3_lock.png\"", (items[2] as kotlinx.serialization.json.JsonObject)["badge"].toString())
        assertEquals("true", (items[0] as kotlinx.serialization.json.JsonObject)["hardcore"].toString())
    }

    @Test
    fun theRommServerIsLookedThroughAndAGameQueuedFromThePhone(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val base = createFuseStore(services, scope)
        // Without RomM set up, the phone is told so rather than shown an empty server.
        val off = PhoneLinkServer(base, services.secrets, scope, "Test Device", "0.4.6").api.romm("", "", 0, 30)
        assertEquals("false", off["available"].toString())
        assertTrue(off["message"].toString().isNotBlank())

        fun game(id: Long, title: String, presence: io.github.matiyaaa.fuse.ui.shell.store.RommPresence) = io.github.matiyaaa.fuse.ui.shell.store.RommGame(
            id,
            io.github.matiyaaa.fuse.ui.shell.store.GameCard(io.github.matiyaaa.fuse.model.GameId(-id), io.github.matiyaaa.fuse.model.PlatformId("snes"), title, "SNES", 0xFF336699, io.github.matiyaaa.fuse.ui.shell.store.Art(), year = 1994),
            presence,
            game = io.github.matiyaaa.fuse.model.GameId(-id).takeIf { presence == io.github.matiyaaa.fuse.ui.shell.store.RommPresence.INSTALLED },
            sizeBytes = 4_000_000,
        )
        val games = listOf(
            game(1, "Lantern Quest", io.github.matiyaaa.fuse.ui.shell.store.RommPresence.ON_SERVER),
            game(2, "Ember Racer", io.github.matiyaaa.fuse.ui.shell.store.RommPresence.INSTALLED),
            game(3, "Moth Knight", io.github.matiyaaa.fuse.ui.shell.store.RommPresence.QUEUED),
        )
        val queued = ArrayList<Pair<Long, Boolean>>()
        val store = object : io.github.matiyaaa.fuse.ui.shell.store.FuseStore by base {
            override val romm = object : io.github.matiyaaa.fuse.ui.shell.store.RommOps by base.romm {
                override val supported = true
                override val state = kotlinx.coroutines.flow.MutableStateFlow(io.github.matiyaaa.fuse.ui.shell.store.RommState(link = io.github.matiyaaa.fuse.ui.shell.store.RommLink.ONLINE, games = 3))
                override val systems = kotlinx.coroutines.flow.MutableStateFlow(listOf(io.github.matiyaaa.fuse.ui.shell.store.RommSystem(null, "snes", "Super Nintendo", 3, 1, 0)))
                override suspend fun browse(slug: String?, query: String, offset: Int, limit: Int): io.github.matiyaaa.fuse.ui.shell.store.RommPage {
                    val found = games.filter { query.isBlank() || it.card.title.contains(query, ignoreCase = true) }
                    return io.github.matiyaaa.fuse.ui.shell.store.RommPage(found.drop(offset).take(limit), found.size)
                }
                override suspend fun download(romId: Long, what: io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat): String? = null.also { queued += romId to false }
                override suspend fun downloadLater(romId: Long): String? = if (romId == 2L) "Ember Racer is already here." else null.also { queued += romId to true }
            }
        }
        val api = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.4.6").api
        fun items(o: kotlinx.serialization.json.JsonObject) = (o["items"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }

        // A page of two of the three, each saying where it is, with the server's systems to narrow by.
        val page = api.romm("", "", 0, 2)
        assertEquals("true", page["available"].toString())
        assertEquals("3", page["total"].toString())
        assertEquals(listOf("\"server\"", "\"here\""), items(page).map { it["state"].toString() })
        assertEquals("\"Super Nintendo\"", ((page["systems"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject)["name"].toString())
        // A game in the library says which one, for opening it there.
        assertEquals("-2", items(page)[1]["gameId"].toString())

        val moth = api.romm("snes", "moth", 0, 30)
        assertEquals(listOf("\"Moth Knight\""), items(moth).map { it["title"].toString() })
        assertEquals("\"coming\"", items(moth)[0]["state"].toString())

        // Now, or for later; and a game that can't be queued says why.
        assertEquals(200, api.rommDownload(1, null).status.value)
        assertEquals(200, api.rommDownload(3, kotlinx.serialization.json.buildJsonObject { put("later", kotlinx.serialization.json.JsonPrimitive(true)) }).status.value)
        assertEquals(listOf(1L to false, 3L to true), queued)
        val refused = api.rommDownload(2, kotlinx.serialization.json.buildJsonObject { put("later", kotlinx.serialization.json.JsonPrimitive(true)) })
        assertEquals(409, refused.status.value)
        assertTrue(refused.body.toString().contains("already here"))
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

    @Test
    fun aPortAnotherProgramHoldsIsPassedOver(): Unit = runBlocking {
        // Another program listening where Phone Link would start: it carries on at the next port.
        java.net.ServerSocket(0).use { other ->
            val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
            val store = createFuseStore(services, scope)
            val link = PhoneLinkServer(store, services.secrets, scope, "Test Device", "0.4.2", basePort = other.localPort)
            link.start()
            store.updatePrefs { it.copy(phoneLinkEnabled = true) }
            val state = withTimeout(20_000) { link.state.first { it.running || it.error != null } }
            assertTrue(state.running, "${state.error}")
            val port = link.boundPort()!!
            assertTrue(port in other.localPort + 1 until other.localPort + 10, "started at $port")
            assertEquals(401, http(port, "GET", "/api/input").status, "the server Phone Link says it runs answers")
            store.updatePrefs { it.copy(phoneLinkEnabled = false) }
            withTimeout(10_000) { link.state.first { !it.running } }
        }
    }
}
