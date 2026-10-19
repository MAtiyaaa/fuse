package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.sync.TrophyUnlock
import io.github.matiyaaa.fuse.ui.shell.store.impl.TrophyHistory
import io.github.matiyaaa.fuse.ui.shell.store.impl.TrophyNote
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trophies across the person's devices with RomM and no Fuse One host, through a stand-in of RomM
 * 5.4's notes: the person's own note read into a game's history (someone else's public note of the
 * same name never), each trophy's earliest kept, and what was earned here added only once they
 * chose to send their changes, the note made the first time there is something to put in it.
 */
class RommTrophyNoteStoreTest {
    private val root: File = Files.createTempDirectory("fuse-trophy-note").toFile()
    private val cache: File = Files.createTempDirectory("fuse-trophy-note-cache").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private data class Note(val id: Long, val title: String, val content: String, val public: Boolean, val user: String)
    private data class Call(val method: HttpMethod, val path: String, val body: String)

    private val notes = CopyOnWriteArrayList<Note>()
    private val writes = CopyOnWriteArrayList<Call>()

    private fun noteJson(n: Note) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(n.id), "title" to JsonPrimitive(n.title), "content" to JsonPrimitive(n.content),
            "is_public" to JsonPrimitive(n.public), "tags" to JsonArray(listOf(JsonPrimitive("fuse"))), "user_id" to JsonPrimitive(1),
            "username" to JsonPrimitive(n.user), "created_at" to JsonPrimitive("2026-10-01T00:00:00Z"), "updated_at" to JsonPrimitive("2026-10-01T00:00:00Z"),
        ),
    ).toString()

    /** A Vita game here with Vita3K's trophies, trophy 1 earned at [earned] seconds (and trophy 0 too when [platinum]). */
    private fun vitaGame(earned: Long, platinum: Long? = null): File {
        File(root, "games/psvita").mkdirs()
        File(root, "games/psvita/Gravity Rush.psvita").writeText("PCSA00011\n")
        val pref = File(root, "vita3k")
        File(pref, "ux0/app/PCSA00011/sce_sys/trophy/NPWR01234_00").mkdirs()
        File(pref, "ux0/app/PCSA00011/sce_sys/trophy/NPWR01234_00/TROPHY.TRP").writeBytes(ByteArray(16))
        val conf = File(pref, "ux0/user/00/trophy/conf/NPWR01234_00").apply { mkdirs() }
        File(conf, "TROP.SFM").writeText(
            """<trophyconf version="1.1"><title-name>Gravity Rush</title-name>
<trophy id="000" hidden="no" ttype="P" pid="-1"><name>Queen of Gravity</name><detail>All trophies.</detail></trophy>
<trophy id="001" hidden="no" ttype="B" pid="000"><name>First Fall</name><detail>Fall and land.</detail></trophy>
<trophy id="002" hidden="yes" ttype="S" pid="000"><name>Secret</name><detail>Hidden.</detail></trophy></trophyconf>""",
        )
        unlocks(pref, earned, platinum)
        return pref
    }

    /** Vita3K's TROPUSR.DAT with trophy 1 (and 0) unlocked at those seconds. */
    private fun unlocks(pref: File, earned: Long, platinum: Long?) {
        val usr = ByteArray(1648)
        fun put(at: Int, v: Long, bytes: Int) { for (i in 0 until bytes) usr[at + i] = (v ushr (8 * i)).toByte() }
        put(0, 0x12D5819AL, 4)
        usr[4] = if (platinum != null) 0b11 else 0b10
        put(112 + 8, earned, 8)
        if (platinum != null) put(112, platinum, 8)
        File(pref, "ux0/user/00/trophy/data/NPWR01234_00").mkdirs()
        File(pref, "ux0/user/00/trophy/data/NPWR01234_00/TROPUSR.DAT").writeBytes(usr)
    }

    private fun services(): FakeServices {
        val vita3k = InstalledEmulator(EmulatorId("linux.vita3k"), "Vita3K", Host.LINUX, "/usr/bin/Vita3K", platforms = setOf(PlatformId("psvita")), detectedVia = "PATH")
        return FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, installedEmulators = listOf(vita3k)).apply {
            vitaPref = File(root, "vita3k").absolutePath
            web = { req ->
                if (req.url.host != "romm.test") {
                    null
                } else {
                    val path = req.url.encodedPath
                    val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                    fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    if (req.method != HttpMethod.Get) writes += Call(req.method, path, body)
                    when {
                        path == "/api/heartbeat" -> json("""{"SYSTEM":{"VERSION":"5.4.0"}}""")
                        path == "/openapi.json" -> json(
                            """{"paths":{"/api/roms/{id}/props":{},"/api/collections/{id}/roms":{},"/api/play-sessions":{},"/api/roms/identifiers":{},"/api/roms/{id}/notes":{},"/api/roms/{id}/notes/{note_id}":{}}}""",
                        )
                        path == "/api/users/me" -> json("""{"username":"pat"}""")
                        path == "/api/roms/7/notes" && req.method == HttpMethod.Get -> json(notes.joinToString(",", "[", "]") { noteJson(it) })
                        path == "/api/roms/7/notes" && req.method == HttpMethod.Post -> {
                            val o = Json.parseToJsonElement(body).jsonObject
                            val n = Note(99, o.getValue("title").jsonPrimitive.content, o.getValue("content").jsonPrimitive.content, o.getValue("is_public").jsonPrimitive.boolean, "pat")
                            notes += n
                            json(noteJson(n))
                        }
                        path.startsWith("/api/roms/7/notes/") && req.method == HttpMethod.Put -> {
                            val id = path.substringAfterLast('/').toLong()
                            val i = notes.indexOfFirst { it.id == id }
                            notes[i] = notes[i].copy(content = Json.parseToJsonElement(body).jsonObject.getValue("content").jsonPrimitive.content)
                            json(noteJson(notes[i]))
                        }
                        path == "/api/collections" -> json("""[]""")
                        path == "/api/roms" -> json("""{"items":[],"total":0}""")
                        else -> json("[]")
                    }
                }
            }
        }
    }

    private suspend fun signIn(store: FuseStore, services: FakeServices, gameId: io.github.matiyaaa.fuse.model.GameId) {
        services.data.games.updateLinks(gameId) { it.copy(rommRomId = 7) }
        store.romm.setEnabled(true)
        store.romm.setAddresses("http://romm.test", "", RouteMode.LOCAL)
        store.romm.useToken("tok").getOrThrow()
        withTimeout(20_000) { store.romm.state.first { it.link == RommLink.ONLINE && it.canSendChanges } }
    }

    @Test
    fun theNoteOfAnotherDeviceJoinsTheGameAndWhatWasEarnedHereEarlierIsAdded(): Unit = runBlocking {
        vitaGame(earned = 1_700_000_000L)
        // The person's note, from their PC: trophy 1 later than here, and the secret trophy 2.
        val pc = listOf(
            TrophyUnlock("psvita", "1", "First Fall", 1_800_000_000_000, "pc-id", "Gaming PC", "vita3k"),
            TrophyUnlock("psvita", "2", "Secret", 1_650_000_000_000, "pc-id", "Gaming PC", "vita3k"),
        )
        notes += Note(5, TrophyNote.TITLE, TrophyNote.write(pc), false, "pat")
        // Someone else's public note of the same name: never the person's.
        notes += Note(6, TrophyNote.TITLE, TrophyNote.write(listOf(TrophyUnlock("psvita", "0", "Queen of Gravity", 1_600_000_000_000, "sam-id", "Sam's Deck"))), true, "sam")

        val services = services()
        val store = createFuseStore(services, scope)
        withTimeout(20_000) { store.emulators.installed.first { it.isNotEmpty() } }
        store.sources.add(File(root, "games").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.single()
        signIn(store, services, game.id)
        store.romm.setSendChanges(true).getOrThrow()

        // The page says where the secret one was earned, and only the person's own note counts.
        val trophies = withTimeout(20_000) { store.library.game(game.id).first { it?.achievements != null } }!!.achievements!!
        assertTrue(trophies.achievements.single { it.title == "Hidden trophy" }.description.endsWith("Earned on Gaming PC."), trophies.achievements.toString())
        assertFalse(trophies.achievements.single { it.title == "Queen of Gravity" }.description.contains("Earned on"))
        val history = TrophyHistory.read(services.data, game.id.value).associateBy { it.id }
        assertEquals(setOf("1", "2"), history.keys)
        assertEquals(1_700_000_000_000L, history.getValue("1").unlockedAt, "the earliest is kept")

        // The note gains what was earned here earlier; the other device's stays as it was.
        withTimeout(10_000) { while (writes.none { it.method == HttpMethod.Put && it.path == "/api/roms/7/notes/5" }) delay(50) }
        val note = TrophyNote.read(notes.single { it.id == 5L }.content).associateBy { it.id }
        assertEquals(1_700_000_000_000L, note.getValue("1").unlockedAt)
        assertTrue(note.getValue("1").device.startsWith("fuse-"), "this device's own id, with no Fuse One")
        assertEquals(pc[1], note.getValue("2"))
        assertEquals(TrophyNote.write(listOf(note.getValue("1"), pc[1])), notes.single { it.id == 5L }.content)
        assertTrue(writes.all { it.method == HttpMethod.Put && it.path == "/api/roms/7/notes/5" }, "only the person's own note is written: $writes")
    }

    @Test
    fun nothingIsWrittenUntilChangesGoToRommAndThenTheNoteIsMadeOnce(): Unit = runBlocking {
        val pref = vitaGame(earned = 1_700_000_000L)
        val services = services()
        val store = createFuseStore(services, scope)
        withTimeout(20_000) { store.emulators.installed.first { it.isNotEmpty() } }
        store.sources.add(File(root, "games").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.single()
        signIn(store, services, game.id)

        // Off: the page reads RomM's notes (there are none) and writes nothing.
        withTimeout(20_000) { store.library.game(game.id).first { it?.achievements != null } }
        delay(1_000)
        assertTrue(writes.none { it.path.startsWith("/api/roms/7/notes") }, "$writes")

        // On, and the platinum earned here: the note is made, kept to the person, with both.
        store.romm.setSendChanges(true).getOrThrow()
        unlocks(pref, 1_700_000_000L, platinum = 1_700_000_500L)
        store.achievements.refresh(force = true)
        withTimeout(15_000) { while (writes.none { it.method == HttpMethod.Post && it.path == "/api/roms/7/notes" }) delay(50) }
        val made = Json.parseToJsonElement(writes.single { it.method == HttpMethod.Post }.body).jsonObject
        assertEquals(TrophyNote.TITLE, made.getValue("title").jsonPrimitive.content)
        assertFalse(made.getValue("is_public").jsonPrimitive.boolean)
        assertEquals("[\"fuse\"]", made.getValue("tags").toString())
        val note = TrophyNote.read(notes.single().content)
        assertEquals(listOf("1" to 1_700_000_000_000L, "0" to 1_700_000_500_000L), note.map { it.id to it.unlockedAt })
        assertTrue(notes.single().content.startsWith("Trophies you earned in emulators"), "RomM shows a line saying what it is")
        assertTrue("- Queen of Gravity, 2023-11-14" in notes.single().content, notes.single().content)
    }
}
