package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.model.ScanPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The store end to end on a real (temporary) folder and an in-memory database: scanning, platform
 * cards, launching through a fake launcher, honest play sessions, generated playlists in the cache
 * and persisted preferences. Nothing touches the network.
 */
class StoreIntegrationTest {
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
        File(root, "psx").mkdirs()
        File(root, "psx/Final Fantasy VII (USA) (Disc 1).chd").writeText("disc1")
        File(root, "psx/Final Fantasy VII (USA) (Disc 2).chd").writeText("disc2")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun scansLaunchesAndTracksPlaytime() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)

        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)

        val systems = withTimeout(10_000) { store.library.platforms.first { it.size == 2 } }
        assertEquals(setOf("gba", "psx"), systems.map { it.platform.id.value }.toSet())
        val gba = systems.first { it.platform.id.value == "gba" }
        assertEquals(1, gba.gameCount)
        assertTrue(gba.emulatorInstalled, "mGBA is installed and should be chosen automatically")

        val games = store.library.games(GameQuery(platform = PlatformId("gba"))).first()
        val game = games.single()
        assertTrue(game.title.startsWith("Advance Wars"), game.title)

        // Launch: the fake launcher gets mGBA's command and the session stays open until it exits.
        val exit = CompletableDeferred<Unit>()
        services.exit = exit
        val outcome = store.library.launch(game.id)
        assertIs<LaunchOutcome.Started>(outcome)
        val plan = assertIs<LaunchPlan.Command>(services.launched.single().plan)
        assertEquals(EmulatorId("linux.mgba"), plan.emulatorId)
        assertTrue(plan.argv.any { it.endsWith("Advance Wars (USA).gba") }, plan.argv.toString())
        val open = services.data.playSessions.openSession().first()
        assertEquals(game.id, open?.gameId)

        exit.complete(Unit)
        withTimeout(10_000) { services.data.playSessions.openSession().first { it == null } }
        val played = assertNotNull(services.data.games.summary(game.id))
        assertNotNull(played.lastPlayedAt)
        assertEquals(1, played.sessions)
    }

    @Test
    fun multiDiscGamesGetAPlaylistInTheCacheOnly() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val ff7 = store.library.games(GameQuery(platform = PlatformId("psx"))).first().single()
        assertEquals(2, ff7.discs)

        assertIs<LaunchOutcome.Started>(store.library.launch(ff7.id))
        val target = (services.launched.single().plan as LaunchPlan.Command).target
        val playlist = assertIs<LaunchTarget.Playlist>(target)
        assertTrue(playlist.generated)
        assertTrue(playlist.path.startsWith(cache.absolutePath), "Playlists belong in Fuse's cache: ${playlist.path}")
        assertTrue(File(root, "psx").listFiles()!!.none { it.name.endsWith(".m3u") }, "Nothing may be written into the ROM folder")
    }

    @Test
    fun preferencesPersistAcrossRestarts() = runBlocking {
        val file = File(cache, "prefs.db").absolutePath
        val first = FakeServices(FuseData(DesktopDatabase.open(file)), cache)
        val store = createFuseStore(first, scope)
        assertTrue(!store.prefs.value.onboardingDone)
        store.updatePrefs { it.copy(themeId = "crt", onboardingDone = true, clock24h = true, heroDim = 0.5f) }
        // Writes are asynchronous; wait for the database to have them.
        withTimeout(10_000) { first.data.settings.settings.first { it.appearance.themeId == "crt" && it.onboarding.completed } }

        val again = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(file)), cache), scope)
        val prefs = again.prefs.value
        assertEquals("crt", prefs.themeId)
        assertTrue(prefs.onboardingDone)
        assertTrue(prefs.clock24h)
        assertEquals(0.5f, prefs.heroDim)
    }

    @Test
    fun removingAGameNeverTouchesFiles() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(freshDb())), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        awaitScan(store)
        val game = store.library.games(GameQuery(platform = PlatformId("gba"))).first().single()
        store.library.removeFromFuse(game.id)
        store.library.rename(game.id, "My Wars")
        assertTrue(File(root, "gba/Advance Wars (USA).gba").exists())
        assertTrue(store.library.games(GameQuery(platform = PlatformId("gba"))).first().isEmpty())
    }

    /**
     * A new database file per store, like the desktop app uses. (The in-memory database shares one
     * connection between threads, which the app never does.)
     */
    private fun freshDb(): String = File(cache, "fuse-${System.nanoTime()}.db").absolutePath

    /** Waits until the scan's results are in the library (a follow-up quick scan may already run). */
    private suspend fun awaitScan(store: FuseStore) {
        withTimeout(20_000) {
            store.library.platforms.first { systems -> systems.sumOf { it.gameCount } == 2 }
            store.sources.scan.first { it.phase == ScanPhase.DONE }
        }
    }
}
