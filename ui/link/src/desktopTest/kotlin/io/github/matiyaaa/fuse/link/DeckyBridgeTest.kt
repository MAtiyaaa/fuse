package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveChange
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveQueue
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveResult
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import io.github.matiyaaa.fuse.data.settings.ExtensionScope
import io.github.matiyaaa.fuse.data.settings.Extensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Decky bridge on a real loopback port, spoken to as the plugin would (and as anything else here would). */
class DeckyBridgeTest {
    private lateinit var cache: File
    private lateinit var home: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-decky-cache").toFile()
        home = Files.createTempDirectory("fuse-decky-home").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
        home.deleteRecursively()
    }

    private fun http(port: Int, method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val bytes = body?.encodeToByteArray() ?: ByteArray(0)
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (body != null) append("Content-Type: application/json\r\n")
                if (method != "GET") append("Content-Length: ${bytes.size}\r\n")
                append("\r\n")
            } + body.orEmpty()
            s.getOutputStream().apply { write(request.encodeToByteArray()); flush() }
            val raw = s.getInputStream().readBytes().decodeToString()
            val status = raw.lineSequence().first().split(' ')[1].toInt()
            var body = raw.substringAfter("\r\n\r\n")
            if (raw.substringBefore("\r\n\r\n").contains("chunked", ignoreCase = true)) body = body.lines().filterIndexed { i, _ -> i % 2 == 1 }.joinToString("")
            return status to body
        }
    }

    @Test
    fun onlyThePluginWithTheTokenIsAnsweredAndTheFileGoesWithTheBridge(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        val file = DeckyBridge.discoveryFile(home.path, xdgData = null)
        val bridge = DeckyBridge(store, "0.4.2", file)
        val port = bridge.start()
        try {
            assertTrue(port > 0)
            // The plugin finds the port and token in a file only this user can read.
            val found = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(port, found.getValue("port").jsonPrimitive.int)
            assertEquals(DeckyBridge.PROTOCOL, found.getValue("protocol").jsonPrimitive.int)
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
            val token = found.getValue("token").jsonPrimitive.content

            // Without the token, or with another, nothing is answered.
            assertEquals(401, http(port, "GET", "/v1/hello", null).first)
            assertEquals(401, http(port, "GET", "/v1/hello", "0".repeat(token.length)).first)
            assertEquals(401, http(port, "POST", "/v1/emulators/uninstall-all", null).first)

            val (ok, hello) = http(port, "GET", "/v1/hello", token)
            assertEquals(200, ok)
            assertEquals("Fuse", Json.parseToJsonElement(hello).jsonObject.getValue("app").jsonPrimitive.content)
            val (_, status) = http(port, "GET", "/v1/status", token)
            assertTrue("fuseOne" in Json.parseToJsonElement(status).jsonObject)

            // A Steam game that isn't installed here can't be added; a bad id is refused.
            val (_, add) = http(port, "POST", "/v1/steam/400/add", token)
            assertEquals(0, Json.parseToJsonElement(add).jsonObject.getValue("added").jsonPrimitive.int)
            assertEquals(400, http(port, "POST", "/v1/steam/nope/add", token).first)
            val (_, steam) = http(port, "GET", "/v1/steam/400", token)
            assertEquals("false", Json.parseToJsonElement(steam).jsonObject.getValue("inFuse").jsonPrimitive.content)

            // The library at a glance for the panel; a game Fuse doesn't know isn't opened.
            val (libOk, library) = http(port, "GET", "/v1/library", token)
            assertEquals(200, libOk)
            val lib = Json.parseToJsonElement(library).jsonObject
            assertTrue("games" in lib && "recent" in lib)
            val (_, open) = http(port, "POST", "/v1/game/987654/open", token)
            assertEquals("false", Json.parseToJsonElement(open).jsonObject.getValue("opened").jsonPrimitive.content)
            assertEquals(400, http(port, "POST", "/v1/game/nope/open", token).first)

            // While you play: no game running here, so nothing to close or mark.
            val (playOk, play) = http(port, "GET", "/v1/playing", token)
            assertEquals(200, playOk)
            assertEquals("false", Json.parseToJsonElement(play).jsonObject.getValue("playing").jsonPrimitive.content)
            val (_, quit) = http(port, "POST", "/v1/playing/quit", token)
            assertEquals("false", Json.parseToJsonElement(quit).jsonObject.getValue("quit").jsonPrimitive.content)
            val (_, mark) = http(port, "POST", "/v1/playing/journal", token, "{\"status\":\"BEATEN\"}")
            assertEquals("false", Json.parseToJsonElement(mark).jsonObject.getValue("set").jsonPrimitive.content)
        } finally {
            bridge.close()
        }
        // Gone with the bridge: a plugin never talks to a Fuse that isn't there.
        assertFalse(file.exists())
    }

    @Test
    fun fusesChangesToSteamGoThroughThePluginInSteamsRunningClient(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        val file = DeckyBridge.discoveryFile(home.path, xdgData = null)
        val live = SteamLiveQueue(System::currentTimeMillis)
        val bridge = DeckyBridge(store, "0.4.4", file, live = live)
        val port = bridge.start()
        val picture = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
        val capsule = File(home, "art/12p.png").apply { parentFile.mkdirs(); writeBytes(picture) }
        val notAPicture = File(home, "notes.txt").apply { writeText("private") }
        fun changes(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
        try {
            val token = Json.parseToJsonElement(file.readText()).jsonObject.getValue("token").jsonPrimitive.content
            assertEquals(2, Json.parseToJsonElement(http(port, "GET", "/v1/hello", token).second).jsonObject.getValue("protocol").jsonPrimitive.int)
            // Nothing waiting; asking says the plugin is there.
            assertFalse(live.connected)
            assertEquals(emptyList(), changes(http(port, "GET", "/v1/steam/changes", token).second))
            assertTrue(live.connected)

            val offered = async {
                live.offer(
                    listOf(SteamLiveChange(op = SteamLiveChange.Op.ADD, gameId = 12, name = "Minish Cap", exe = "\"/a/Fuse.AppImage\"", startDir = "\"/a\"", launchOptions = "--run 12", art = mapOf("capsule" to capsule.path, "hero" to notAPicture.path), logo = io.github.matiyaaa.fuse.ui.shell.platform.SteamLogo.FUSE)),
                    waitMs = 20_000,
                )
            }
            val (_, body) = withTimeout(10_000) {
                var got = http(port, "GET", "/v1/steam/changes", token)
                while (changes(got.second).isEmpty()) { delay(20); got = http(port, "GET", "/v1/steam/changes", token) }
                got
            }
            val change = changes(body).single()
            assertEquals("ADD", change.getValue("op").jsonPrimitive.content)
            assertEquals("--run 12", change.getValue("launchOptions").jsonPrimitive.content)
            // The logo's place as Steam's LogoPosition has it.
            assertEquals(mapOf("pinnedPosition" to "BottomLeft", "nWidthPct" to "70", "nHeightPct" to "35"), change.getValue("logo").jsonObject.mapValues { it.value.jsonPrimitive.content })
            // Pictures by kind and format only, never by path; a file that isn't a picture isn't offered.
            assertEquals(mapOf("capsule" to "png"), change.getValue("art").jsonObject.mapValues { it.value.jsonPrimitive.content })
            assertFalse(capsule.path in body)
            val id = change.getValue("id").jsonPrimitive.content
            val (artOk, art) = http(port, "GET", "/v1/steam/changes/$id/art/capsule", token)
            assertEquals(200, artOk)
            assertTrue(picture.contentEquals(java.util.Base64.getDecoder().decode(Json.parseToJsonElement(art).jsonObject.getValue("data").jsonPrimitive.content)))
            assertEquals(404, http(port, "GET", "/v1/steam/changes/$id/art/hero", token).first)
            assertEquals(404, http(port, "GET", "/v1/steam/changes/$id/art/logo", token).first)

            // The plugin made it: Steam's id for the entry comes back to Fuse.
            val (_, done) = http(port, "POST", "/v1/steam/changes/$id/done", token, """{"ok":true,"appId":3000000001}""")
            assertEquals("true", Json.parseToJsonElement(done).jsonObject.getValue("ok").jsonPrimitive.content)
            assertEquals(listOf(SteamLiveResult(true, 3_000_000_001L)), offered.await())
            assertEquals(mapOf(12L to 3_000_000_001L), live.made())
            // Reported twice: the second is no change any more.
            assertEquals("false", Json.parseToJsonElement(http(port, "POST", "/v1/steam/changes/$id/done", token, """{"ok":true}""").second).jsonObject.getValue("ok").jsonPrimitive.content)
            assertEquals(400, http(port, "POST", "/v1/steam/changes/$id/done", token, "not json").first)

            // Not allowed to change the library: refused.
            store.updatePrefs { it.copy(extensions = Extensions.set(it.extensions, Extensions.DECKY, ExtensionScope.CHANGE_LIBRARY, false)) }
            withTimeout(10_000) { while (Extensions.allows(store.prefs.value.extensions, Extensions.DECKY, ExtensionScope.CHANGE_LIBRARY)) delay(20) }
            assertEquals(403, http(port, "GET", "/v1/steam/changes", token).first)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun timeInADirectEntryIsCountedOnceAndNoticesReachSteam(): Unit = runBlocking {
        val games = File(home, "roms/gba").apply { mkdirs() }
        File(games, "Golden Sun (USA).gba").writeBytes(ByteArray(1024) { it.toByte() })
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        store.sources.add(games.parentFile.absolutePath, io.github.matiyaaa.fuse.model.LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.isNotEmpty() }.single().id }
        val file = DeckyBridge.discoveryFile(home.path, xdgData = null)
        val live = SteamLiveQueue(System::currentTimeMillis)
        val entry = 3_000_000_001L
        val bridge = DeckyBridge(store, "0.4.4", file, live = live, directGames = { mapOf(entry to game.value) })
        val port = bridge.start()
        try {
            val token = Json.parseToJsonElement(file.readText()).jsonObject.getValue("token").jsonPrimitive.content
            // The plugin is told which Direct entries to watch.
            val first = Json.parseToJsonElement(http(port, "GET", "/v1/steam/changes", token).second).jsonObject
            assertEquals(listOf(entry), first.getValue("watch").jsonArray.map { it.jsonPrimitive.content.toLong() })
            // A notice Fuse would show while a game is in front of it goes to Steam, once.
            live.notify("Saves sent")
            assertEquals(listOf("Saves sent"), Json.parseToJsonElement(http(port, "GET", "/v1/steam/changes", token).second).jsonObject.getValue("notices").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(0, Json.parseToJsonElement(http(port, "GET", "/v1/steam/changes", token).second).jsonObject.getValue("notices").jsonArray.size)

            val now = System.currentTimeMillis()
            val report = """{"sessions":[{"appId":$entry,"start":${now - 3_600_000},"end":${now - 60_000}},{"appId":12345,"start":1,"end":2}]}"""
            val answer = Json.parseToJsonElement(http(port, "POST", "/v1/steam/played", token, report).second).jsonObject
            assertEquals(1, answer.getValue("recorded").jsonPrimitive.int)
            assertEquals(1, answer.getValue("unknown").jsonPrimitive.int)
            // Sent again (the plugin didn't hear back): not counted twice.
            assertEquals(0, Json.parseToJsonElement(http(port, "POST", "/v1/steam/played", token, report).second).jsonObject.getValue("recorded").jsonPrimitive.int)
            assertEquals(400, http(port, "POST", "/v1/steam/played", token, "{}").first)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun thePluginDoesOnlyWhatThePersonLetsIt(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        val file = DeckyBridge.discoveryFile(home.path, xdgData = null)
        val bridge = DeckyBridge(store, "0.4.2", file)
        val port = bridge.start()
        suspend fun settle(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(20) }
        fun allowed(hello: String) = Json.parseToJsonElement(hello).jsonObject.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }
        try {
            val token = Json.parseToJsonElement(file.readText()).jsonObject.getValue("token").jsonPrimitive.content
            // Emulators wait until the person allows them, and the plugin is told what it may do.
            assertEquals(listOf("status", "library", "play", "change_library"), allowed(http(port, "GET", "/v1/hello", token).second))
            for ((method, path) in listOf("GET" to "/v1/emulators", "POST" to "/v1/emulators/uninstall-all", "POST" to "/v1/emulators/install-all")) {
                val (code, body) = http(port, method, path, token)
                assertEquals(403, code, path)
                assertTrue("Settings, Library, Decky" in Json.parseToJsonElement(body).jsonObject.getValue("message").jsonPrimitive.content, body)
            }
            // Allowed in Settings: answered.
            store.updatePrefs { it.copy(extensions = Extensions.set(it.extensions, Extensions.DECKY, ExtensionScope.EMULATORS, true)) }
            settle { Extensions.allows(store.prefs.value.extensions, Extensions.DECKY, ExtensionScope.EMULATORS) }
            assertEquals(200, http(port, "GET", "/v1/emulators", token).first)
            // Taken away again: refused, and who is playing isn't said either.
            store.updatePrefs { it.copy(extensions = Extensions.set(Extensions.set(it.extensions, Extensions.DECKY, ExtensionScope.EMULATORS, false), Extensions.DECKY, ExtensionScope.STATUS, false)) }
            settle { !Extensions.allows(store.prefs.value.extensions, Extensions.DECKY, ExtensionScope.STATUS) }
            assertEquals(403, http(port, "GET", "/v1/status", token).first)
            assertEquals(403, http(port, "GET", "/v1/emulators", token).first)
            val hello = Json.parseToJsonElement(http(port, "GET", "/v1/hello", token).second).jsonObject
            assertEquals("null", hello.getValue("profile").toString())
            assertFalse("status" in allowed(hello.toString()))
            // What it still may do is answered.
            assertEquals(200, http(port, "GET", "/v1/steam/400", token).first)
        } finally {
            bridge.close()
        }
    }
}
