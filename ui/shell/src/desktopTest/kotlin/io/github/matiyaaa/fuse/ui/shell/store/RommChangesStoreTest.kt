package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The person's changes to a game from RomM going back to it, through a stand-in of RomM 5.4's API:
 * a favourite into RomM's favourites collection, a game hidden, a play session; waiting while RomM
 * is away and going when it answers; nothing at all until the person turns it on.
 */
class RommChangesStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-lib").toFile()
        cache = Files.createTempDirectory("fuse-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(1024) { it.toByte() })
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private data class Call(val method: HttpMethod, val path: String, val query: String, val body: String, val host: String = "romm.test")

    private val writes = CopyOnWriteArrayList<Call>()
    private val away = AtomicBoolean(false)

    /** The servers that don't answer (503), by host. */
    private val down: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** RomM's list of its parts can't be read (as when it answered slowly once): Fuse knows only its version. */
    private val partsUnread = AtomicBoolean(false)

    /** Play sessions are taken slowly, as by a busy server. */
    private val slowSessions = AtomicBoolean(false)

    private fun services(): FakeServices = FakeServices(FuseData(DesktopDatabase.open(null)), cache).apply {
        web = { req ->
            val host = req.url.host
            if (host != "romm.test" && host != "other.test") {
                null
            } else {
                val path = req.url.encodedPath
                val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
                val reads = req.method == HttpMethod.Get
                val answers = !away.get() && host !in down
                // What RomM took: a request it didn't answer took nothing.
                if (!reads && answers) writes += Call(req.method, path, req.url.encodedQuery, body, host)
                when {
                    !answers -> respond("", HttpStatusCode.ServiceUnavailable)
                    path == "/api/heartbeat" -> json("""{"SYSTEM":{"VERSION":"5.4.0"}}""")
                    path == "/openapi.json" && partsUnread.get() -> respond("", HttpStatusCode.InternalServerError)
                    path == "/openapi.json" -> json(
                        """{"paths":{"/api/roms/{id}/props":{},"/api/collections/{id}/roms":{},"/api/play-sessions":{},"/api/tasks/{task_id}":{},"/api/roms/identifiers":{}}}""",
                    )
                    path == "/api/play-sessions" && slowSessions.get() -> {
                        delay(1_500)
                        json("""{"results":[],"created_count":1,"skipped_count":0}""", HttpStatusCode.Created)
                    }
                    path == "/api/users/me" -> json("""{"username":"pat"}""")
                    path == "/api/collections" && reads -> json("""[]""")
                    path == "/api/collections" -> json("""{"id":41,"name":"Favourites","rom_ids":[],"is_favorite":true}""")
                    path == "/api/collections/41/roms" -> json("""{"id":41,"name":"Favourites","rom_ids":[7],"is_favorite":true}""")
                    path == "/api/roms/7/props" -> json("""{"id":1,"rom_id":7,"hidden":true}""")
                    path == "/api/play-sessions" -> json("""{"results":[],"created_count":1,"skipped_count":0}""", HttpStatusCode.Created)
                    path == "/api/roms" -> json("""{"items":[],"total":0}""")
                    else -> json("[]")
                }
            }
        }
    }

    @Test
    fun aFavouriteAHideAndASessionGoToRommOnlyOnceTurnedOnAndWaitWhileItIsAway(): Unit = runBlocking {
        val services = services()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) {
            store.library.platforms.first { systems -> systems.sumOf { it.gameCount } == 1 }
            store.sources.scan.first { it.phase == ScanPhase.DONE }
        }
        val game = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        // RomM's game 7 is this one.
        services.data.games.updateLinks(game.id) { it.copy(rommRomId = 7) }
        val romm = store.romm
        romm.setEnabled(true)
        romm.setAddresses("http://romm.test", "", RouteMode.LOCAL)
        romm.useToken("tok").getOrThrow()
        withTimeout(20_000) { romm.state.first { it.link == RommLink.ONLINE && it.canSendChanges } }

        // Off until turned on: a favourite stays here.
        store.library.setFavorite(game.id, true)
        delay(4_000)
        assertTrue(writes.none { it.path.startsWith("/api/collections") || it.path.endsWith("/props") }, "$writes")

        // On: the favourite goes into RomM's favourites collection (made, as the person has none).
        romm.setSendChanges(true).getOrThrow()
        store.library.setFavorite(game.id, false)
        store.library.setFavorite(game.id, true)
        withTimeout(15_000) { while (writes.none { it.path == "/api/collections/41/roms" }) delay(100) }
        val made = writes.single { it.method == HttpMethod.Post && it.path == "/api/collections" }
        assertTrue("is_favorite=true" in made.query, made.query)
        // Only where the person left it went: added, once.
        val favourite = writes.filter { it.path == "/api/collections/41/roms" }
        assertEquals(listOf(HttpMethod.Post), favourite.map { it.method })
        assertEquals("""{"rom_ids":[7]}""", favourite.single().body)

        // RomM away: the hide waits, counted, and goes once it answers.
        away.set(true)
        store.library.setHidden(game.id, true)
        withTimeout(10_000) { romm.state.first { it.changesWaiting == 1 } }
        delay(4_000)
        assertEquals(1, romm.state.value.changesWaiting)
        away.set(false)
        assertEquals(1, romm.sendChangesNow())
        assertEquals("""{"hidden":true}""", writes.last { it.path == "/api/roms/7/props" && it.method == HttpMethod.Put }.body)
        assertEquals(0, romm.state.value.changesWaiting)

        // A session played here (as Steam's plugin reports one) goes as a play session.
        assertTrue(store.library.recordSteamSession(game.id, 1_760_000_000_000, 1_760_000_600_000))
        withTimeout(15_000) { while (writes.none { it.path == "/api/play-sessions" }) delay(100) }
        val session = writes.single { it.path == "/api/play-sessions" }.body
        assertTrue(""""rom_id":7""" in session && """"duration_ms":600000""" in session, session)

        // Off again: nothing more goes.
        romm.setSendChanges(false).getOrThrow()
        val before = writes.size
        store.library.setFavorite(game.id, false)
        delay(4_000)
        assertEquals(before, writes.size)
    }

    @Test
    fun aChangeGoesOnceToTheServerItWasMadeForAndOnlyWhenItWent(): Unit = runBlocking {
        val services = services()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) {
            store.library.platforms.first { systems -> systems.sumOf { it.gameCount } == 1 }
            store.sources.scan.first { it.phase == ScanPhase.DONE }
        }
        val game = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        services.data.games.updateLinks(game.id) { it.copy(rommRomId = 7) }
        val romm = store.romm
        romm.setEnabled(true)
        romm.setAddresses("http://romm.test", "", RouteMode.LOCAL)
        romm.useToken("tok").getOrThrow()
        withTimeout(20_000) { romm.state.first { it.link == RommLink.ONLINE && it.canSendChanges } }
        romm.setSendChanges(true).getOrThrow()

        // Two sends at once (the minute's, and Send Them Now): the session goes once.
        down += "romm.test"
        assertTrue(store.library.recordSteamSession(game.id, 1_760_000_000_000, 1_760_000_600_000))
        withTimeout(10_000) { romm.state.first { it.changesWaiting == 1 } }
        delay(4_000)
        down -= "romm.test"
        slowSessions.set(true)
        val both = listOf(scope.async { romm.sendChangesNow() }, scope.async { romm.sendChangesNow() }).awaitAll()
        assertEquals(listOf(0, 1), both.sorted(), "one sent it; the other found it gone")
        assertEquals(1, writes.count { it.path == "/api/play-sessions" && it.method == HttpMethod.Post })
        slowSessions.set(false)

        // RomM's parts couldn't be read this time: what it takes isn't known, so nothing is sent, and nothing let go.
        down += "romm.test"
        store.library.setHidden(game.id, true)
        withTimeout(10_000) { romm.state.first { it.changesWaiting == 1 } }
        delay(4_000)
        down -= "romm.test"
        partsUnread.set(true)
        romm.setAddresses("http://romm.test", "", RouteMode.AUTO)
        withTimeout(20_000) { romm.state.first { it.link == RommLink.ONLINE && !it.canSendChanges } }
        assertEquals(0, romm.sendChangesNow(), "never counted as sent")
        assertEquals(1, romm.state.value.changesWaiting, "still waiting")
        assertTrue(writes.none { it.path == "/api/roms/7/props" }, "$writes")

        // Another server set up: the change waits for the one it was made for, never sent to this one.
        partsUnread.set(false)
        romm.setAddresses("http://other.test", "", RouteMode.LOCAL)
        withTimeout(20_000) { romm.state.first { it.link == RommLink.ONLINE && it.canSendChanges } }
        assertEquals(0, romm.state.value.changesWaiting)
        assertEquals(0, romm.sendChangesNow())
        assertTrue(writes.none { it.host == "other.test" && it.path.endsWith("/props") }, "$writes")
        // The library's link was to the first server's game 7, not this one's.
        withTimeout(10_000) { while (services.data.games.get(game.id)?.links?.rommRomId != null) delay(50) }

        // Back to the first: it goes there.
        romm.setAddresses("http://romm.test", "", RouteMode.LOCAL)
        withTimeout(20_000) { while (writes.none { it.host == "romm.test" && it.path == "/api/roms/7/props" }) delay(100) }
        assertEquals("""{"hidden":true}""", writes.single { it.path == "/api/roms/7/props" }.body)
        withTimeout(10_000) { romm.state.first { it.changesWaiting == 0 } }
    }
}
