package io.github.matiyaaa.fuse.sync.syncthing

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.FileSaveEnvironment
import io.github.matiyaaa.fuse.sync.GameKey
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fuse with a pretend Syncthing: its REST API as Syncthing answers it, with the configuration kept
 * in memory, so what Fuse adds and changes can be read back.
 */
class SyncthingTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val port = ServerSocket(0).use { it.localPort }
    private val me = "AAAAAAA-BBBBBBB-CCCCCCC-DDDDDDD-EEEEEEE-FFFFFFF-GGGGGGG-HHHHHHH"
    private val deck = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"
    private val key = "k3y"

    private val devices = mutableListOf(obj("deviceID" to me, "name" to "Gaming PC"))
    private val folders = mutableListOf<JsonObject>()
    private val pendingFolders = mutableMapOf<String, JsonObject>()
    private val scanned = mutableListOf<String>()
    private val server = embeddedServer(CIO, port = port) {
        routing {
            get("/rest/noauth/health") { call.respondText("""{"status":"OK"}""", ContentType.Application.Json) }
            get("/rest/system/status") { authed { """{"myID":"$me"}""" } }
            get("/rest/system/version") { authed { """{"version":"v1.29.2"}""" } }
            get("/rest/system/connections") { authed { """{"connections":{"$deck":{"connected":true,"address":"192.168.1.30:22000"}}}""" } }
            get("/rest/config/devices") { authed { JsonArray(devices).toString() } }
            put("/rest/config/devices/{id}") { authed { devices.removeAll { it.s("deviceID") == call.parameters["id"] }; devices += parse(call.receiveText()); "{}" } }
            get("/rest/config/folders") { authed { JsonArray(folders).toString() } }
            get("/rest/config/defaults/folder") { authed { """{"rescanIntervalS":3600,"type":"sendreceive"}""" } }
            put("/rest/config/folders/{id}") { authed { folders.removeAll { it.s("id") == call.parameters["id"] }; folders += parse(call.receiveText()); pendingFolders.remove(call.parameters["id"]); "{}" } }
            delete("/rest/config/folders/{id}") { authed { folders.removeAll { it.s("id") == call.parameters["id"] }; "{}" } }
            get("/rest/cluster/pending/devices") { authed { "{}" } }
            get("/rest/cluster/pending/folders") { authed { JsonObject(pendingFolders).toString() } }
            get("/rest/db/status") { authed { """{"state":"idle","needBytes":0,"globalBytes":2048}""" } }
            post("/rest/db/scan") { authed { scanned += call.request.queryParameters["folder"].orEmpty(); "" } }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.authed(answer: suspend () -> String) {
        if (call.request.headers["X-API-Key"] != key) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        call.respondText(answer(), ContentType.Application.Json)
    }

    private class Secrets : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private object Platform : SyncthingPlatform {
        override val host = "LINUX"
        override val install = SyncthingInstall("Syncthing", "https://syncthing.net/downloads/", "", canStart = false)
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-syncthing").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.start(wait = false)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.stop(100, 500)
        root.deleteRecursively()
    }

    /** A computer with RetroArch's saves and states in their usual folders, and one game. */
    private fun service(): Pair<JvmSyncthingService, SaveQuery> {
        val home = File(root, "home").apply { mkdirs() }
        File(home, ".config/retroarch").mkdirs()
        File(home, ".config/retroarch/retroarch.cfg").writeText("savefile_directory = \"${home.path}/.config/retroarch/saves\"\nsavestate_directory = \"${home.path}/.config/retroarch/states\"\n")
        File(home, ".config/retroarch/saves").mkdirs()
        File(home, "roms/gba").mkdirs()
        val settings = SettingsStore(DesktopDatabase.open(null))
        val env = FileSaveEnvironment("LINUX", home.path)
        val q = SaveQuery(GameKey.of("gba", null, null, "Golden Sun"), "gba", File(home, "roms/gba/Golden Sun (USA).gba").path, "linux.retroarch", core = "mgba_libretro")
        val svc = JvmSyncthingService(Platform, settings, Secrets(), scope, env, samples = { listOf(q) })
        return svc to q
    }

    @Test
    fun connectsWithItsKeyAndSaysWhenTheKeyIsWrong(): Unit = runBlocking {
        val (svc, _) = service()
        val wrong = svc.connect("127.0.0.1:$port", "nope").getOrThrow()
        assertIs<SyncthingState.NeedsKey>(wrong)
        assertTrue(wrong.refused)
        val ok = svc.connect("127.0.0.1:$port", key).getOrThrow()
        assertIs<SyncthingState.Connected>(ok)
        assertEquals(me, ok.deviceId)
        assertEquals("1.29.2", ok.version)
        assertEquals("Gaming PC", ok.deviceName)
    }

    @Test
    fun sharesTheEmulatorsSaveFoldersUnderIdsEveryDeviceUses(): Unit = runBlocking {
        val (svc, q) = service()
        svc.connect("127.0.0.1:$port", key).getOrThrow()
        svc.addDevice(deck.lowercase().replace("-", " "), "Steam Deck").getOrThrow()
        assertTrue(devices.any { it.s("deviceID") == deck }, "a device ID is taken however it was typed")
        val plan = svc.plan(listOf(q))
        val saves = plan.first { it.kind == SaveKind.SAVE }
        assertEquals("fuse-retroarch-saves", saves.id)
        assertTrue(saves.path.endsWith(".config/retroarch/saves"))
        assertNull(saves.blocked)
        assertEquals(2, svc.share(plan, keepVersions = true).getOrThrow())
        val shared = folders.first { it.s("id") == "fuse-retroarch-saves" }
        assertEquals("staggered", (shared["versioning"] as JsonObject).s("type"))
        val sharedWith = (shared["devices"] as JsonArray).map { it.jsonObject.s("deviceID") }
        assertTrue(me in sharedWith && deck in sharedWith)
        // Shared once: planned again, it says so and isn't added twice.
        assertTrue(svc.plan(listOf(q)).first { it.kind == SaveKind.SAVE }.shared)
        assertEquals(0, svc.share(svc.plan(listOf(q)), keepVersions = true).getOrThrow())
    }

    @Test
    fun savesBesideTheGamesAreNeverShared(): Unit = runBlocking {
        val (svc, q) = service()
        File(root, "home/.config/retroarch/retroarch.cfg").writeText("savefiles_in_content_dir = \"true\"\n")
        val saves = svc.plan(listOf(q)).first { it.kind == SaveKind.SAVE }
        assertNotNull(saves.blocked, "a folder of games is not a save folder")
    }

    @Test
    fun aFolderAnotherDeviceOffersIsAcceptedWhereThisDeviceKeepsIt(): Unit = runBlocking {
        val (svc, _) = service()
        devices += obj("deviceID" to deck, "name" to "Steam Deck")
        pendingFolders["fuse-retroarch-saves"] = JsonObject(mapOf("offeredBy" to JsonObject(mapOf(deck to obj("label" to "Fuse: RetroArch saves")))))
        svc.connect("127.0.0.1:$port", key).getOrThrow()
        val until = System.currentTimeMillis() + 10_000
        while (folders.none { it.s("id") == "fuse-retroarch-saves" } && System.currentTimeMillis() < until) kotlinx.coroutines.delay(100)
        val accepted = assertNotNull(folders.firstOrNull { it.s("id") == "fuse-retroarch-saves" })
        assertTrue(accepted.s("path")!!.endsWith(".config/retroarch/saves"))
    }

    @Test
    fun beforePlayingTheFoldersAreLookedOverAndTwoVersionsAreAskedAbout(): Unit = runBlocking {
        val (svc, q) = service()
        svc.connect("127.0.0.1:$port", key).getOrThrow()
        svc.share(svc.plan(listOf(q)), keepVersions = true).getOrThrow()
        svc.refresh()
        val dir = File(root, "home/.config/retroarch/saves")
        File(dir, "Golden Sun (USA).srm").writeText("mine")
        assertIs<SyncthingGate.Go>(svc.beforeLaunch(q))
        assertTrue("fuse-retroarch-saves" in scanned)
        // Both devices played: Syncthing kept the other one beside it.
        File(dir, "Golden Sun (USA).sync-conflict-20251005-101530-MFZWI3D.srm").writeText("theirs")
        devices += obj("deviceID" to deck, "name" to "Steam Deck")
        svc.refresh()
        val gate = assertIs<SyncthingGate.Conflict>(svc.beforeLaunch(q))
        val c = gate.conflicts.single()
        assertEquals("Golden Sun (USA).srm", c.name)
        assertEquals("Steam Deck", c.device)
        // Keeping the other device's: it takes the save's place, and this one is kept as an old version.
        svc.resolve(c, keepThis = false).getOrThrow()
        assertEquals("theirs", File(dir, "Golden Sun (USA).srm").readText())
        assertTrue(File(dir, ".stversions").listFiles()!!.any { it.readText() == "mine" })
        assertIs<SyncthingGate.Go>(svc.beforeLaunch(q))
    }

    @Test
    fun aComputersOwnSyncthingConfigGivesItsAddressAndKey() {
        val xml = """
            <configuration version="37">
              <gui enabled="true" tls="true" debugging="false">
                <address>0.0.0.0:8384</address>
                <apikey>abcDEF123</apikey>
              </gui>
            </configuration>
        """.trimIndent()
        val local = assertNotNull(SyncthingConfig.parse(xml))
        assertEquals("https://127.0.0.1:8384", local.address)
        assertEquals("abcDEF123", local.apiKey)
        val linux = SyncthingConfig.candidates("LINUX", "/home/mo") { null }.map { it.path }
        assertEquals("/home/mo/.local/state/syncthing/config.xml", linux.first())
    }

    @Test
    fun deviceIdsAndConflictNamesAreRead() {
        assertEquals(deck, SyncthingService.normaliseDeviceId(deck.lowercase().replace("-", "")))
        assertNull(SyncthingService.normaliseDeviceId("not a device"))
        assertEquals("save.srm" to "MFZWI3D", SyncthingService.conflictOf("save.sync-conflict-20251005-101530-MFZWI3D.srm"))
        assertEquals("Mcd001.ps2" to null, SyncthingService.conflictOf("Mcd001.sync-conflict-20251005-101530.ps2"))
        assertNull(SyncthingService.conflictOf("save.srm"))
        assertFalse(SyncthingApi.isLoopback("https://192.168.1.20:8384"))
        assertTrue(SyncthingApi.isLoopback("127.0.0.1:8384"))
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.content
}
