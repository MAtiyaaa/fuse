package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.sync.JvmSyncService
import io.github.matiyaaa.fuse.sync.NoHostLifetime
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Fuse Sync through the app's own store: a computer hosting and a handheld connected to it, each
 * with its own library folder, database and settings. A person's favourites, names, collections,
 * play time and theme follow them; switching profiles changes them at once, without a restart;
 * Home can stay this device's own; and turning Fuse Sync off leaves everything as it was.
 */
class SyncStoreTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val port = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-sync-store").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private class Device(val store: FuseStore, val services: FakeServices) {
        val sync: SyncService get() = assertNotNull(store.sync.service)
    }

    /** A device with its own copy of the library: the same games, named the way that device's files are. */
    private suspend fun device(name: String, files: List<String>): Device {
        val dir = File(root, name).apply { mkdirs() }
        val roms = File(dir, "roms/gba").apply { mkdirs() }
        files.forEach { File(roms, it).writeBytes(ByteArray(512) { b -> b.toByte() }) }
        val cache = File(dir, "cache").apply { mkdirs() }
        val data = FuseData(DesktopDatabase.open(File(dir, "fuse.db").absolutePath))
        data.settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) }
        lateinit var services: FakeServices
        services = FakeServices(data, cache, sync = { port, scope ->
            JvmSyncService(File(dir, "sync"), data.settings, services.secrets, port, "LINUX", name, "test", NoHostLifetime("test"), scope)
        })
        val store = createFuseStore(services, scope)
        store.sources.add(File(dir, "roms").absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) {
            store.library.platforms.first { systems -> systems.sumOf { it.gameCount } == files.size }
            store.sources.scan.first { it.phase == ScanPhase.DONE }
        }
        return Device(store, services)
    }

    private suspend fun games(d: Device) = d.store.library.games(GameQuery(platform = PlatformId("gba"))).first()

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        withTimeout(15_000) {
            while (!check()) delay(50)
        }
        assertTrue(check(), what)
    }

    @Test
    fun aPersonsLibraryFollowsThemAndSwitchingIsLive(): Unit = runBlocking {
        val pc = device("Gaming PC", listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba"))
        val deck = device("Steam Deck", listOf("advance wars (Europe).gba", "Golden Sun (USA, Europe).gba"))

        // On the handheld, before Fuse Sync: a favourite and a theme it already had.
        val deckWars = games(deck).first { it.title.startsWith("Advance Wars", ignoreCase = true) }
        deck.store.library.setFavorite(deckWars.id, true)
        deck.store.updatePrefs { it.withTheme(ThemePresets.all.first { t -> t.id != "fuse" }) }
        val deckTheme = deck.store.prefs.value.themeId

        // The PC becomes the host and makes a profile; the handheld connects and becomes that person.
        pc.sync.hostHere("Gaming PC", installService = false).getOrThrow()
        val code = assertNotNull(pc.sync.newPairingCode())
        deck.sync.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.sync.createProfile("Mo", "cat", null).getOrThrow()
        deck.sync.switchTo(mo.id).getOrThrow()
        pc.sync.switchTo(mo.id).getOrThrow()

        // What the handheld had is Mo's now, on the PC too: the favourite (matched across file names) and the theme, live.
        val pcWars = games(pc).first { it.title.startsWith("Advance Wars") }
        eventually("favourite came over") { pc.services.data.games.summary(pcWars.id)?.favorite == true }
        eventually("theme came over") { pc.store.prefs.value.themeId == deckTheme }

        // A rename and a collection on the PC reach the handheld.
        pc.store.library.rename(pcWars.id, "Wars!")
        val col = pc.store.collections.create("Strategy")
        pc.store.collections.add(col, pcWars.id)
        pc.sync.syncNow().getOrThrow()
        deck.sync.syncNow().getOrThrow()
        eventually("rename came over") { deck.services.data.games.summary(deckWars.id)?.titles?.custom == "Wars!" }
        eventually("collection came over") {
            deck.store.collections.collections.value.any { it.name == "Strategy" } &&
                deck.store.collections.membership(deckWars.id).isNotEmpty()
        }

        // Mo arranges the quick menu on the handheld.
        deck.store.updatePrefs { it.copy(quickMenu = listOf("WIFI:2", "CLOCK:1")) }
        deck.sync.syncNow().getOrThrow()

        // A second person on the handheld: their library is their own, switched in place, and they
        // start with Fuse's own Home, quick menu and theme, never Mo's.
        val sam = pc.sync.createProfile("Sam", "rocket", null).getOrThrow()
        deck.sync.switchTo(sam.id).getOrThrow()
        eventually("Sam starts fresh") {
            deck.services.data.games.summary(deckWars.id)?.favorite == false &&
                deck.store.collections.collections.value.none { it.name == "Strategy" } &&
                deck.store.prefs.value.quickMenu.isEmpty() &&
                deck.store.prefs.value.themeId == "fuse"
        }
        // Back to Mo: all of it returns.
        deck.sync.switchTo(mo.id).getOrThrow()
        eventually("Mo's library is back") {
            deck.services.data.games.summary(deckWars.id)?.favorite == true &&
                deck.services.data.games.summary(deckWars.id)?.titles?.custom == "Wars!" &&
                deck.store.prefs.value.themeId == deckTheme &&
                deck.store.prefs.value.quickMenu == listOf("WIFI:2", "CLOCK:1")
        }

        // Home kept as this device's own: a change here doesn't reach the PC; back to the profile's brings Mo's.
        deck.store.sync.setOwnHome(true)
        deck.store.updatePrefs { it.copy(home = it.home.copy(mode = if (it.home.mode == io.github.matiyaaa.fuse.model.HomeMode.FLOW) io.github.matiyaaa.fuse.model.HomeMode.CHANNELS else io.github.matiyaaa.fuse.model.HomeMode.FLOW)) }
        val deckMode = deck.store.prefs.value.home.mode
        deck.sync.syncNow().getOrThrow()
        pc.sync.syncNow().getOrThrow()
        assertTrue(pc.store.prefs.value.home.mode != deckMode, "Home stayed on the handheld")
        deck.store.sync.setOwnHome(false)
        eventually("the profile's Home is back") { deck.store.prefs.value.home.mode == pc.store.prefs.value.home.mode }
        assertEquals("PROFILE", deck.store.sync.config.value.homeScope)

        // Off: nothing changes here, and nothing shows.
        val before = games(deck).map { it.id to it.favorite }
        deck.store.sync.setEnabled(false)
        eventually("off") { deck.sync.status.value == SyncStatus.Off }
        assertEquals(before, games(deck).map { it.id to it.favorite })
        assertTrue(!deck.store.sync.inUse)
        pc.sync.stop()
    }
}
