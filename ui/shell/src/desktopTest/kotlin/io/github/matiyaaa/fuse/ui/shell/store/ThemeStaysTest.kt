package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.sync.JvmSyncService
import io.github.matiyaaa.fuse.sync.NoHostLifetime
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * A theme chosen on a device stays chosen: Fuse Sync's next rounds (one starts a moment after any
 * change) carry it to the person's other devices and never put the household's older theme back
 * over it, whatever each device's clock says.
 */
class ThemeStaysTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val port = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-theme-stays").toFile()
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

    /** A device whose clock reads [skewMs] away from the others'. */
    private suspend fun device(name: String, skewMs: Long = 0): Device {
        val dir = File(root, name).apply { mkdirs() }
        val roms = File(dir, "roms/gba").apply { mkdirs() }
        File(roms, "Golden Sun.gba").writeBytes(ByteArray(512) { it.toByte() })
        val cache = File(dir, "cache").apply { mkdirs() }
        val data = FuseData(DesktopDatabase.open(File(dir, "fuse.db").absolutePath), clock = { System.currentTimeMillis() + skewMs })
        data.settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) }
        lateinit var services: FakeServices
        services = FakeServices(data, cache, sync = { profileData, s ->
            JvmSyncService(File(dir, "sync"), data.settings, services.secrets, profileData, "LINUX", name, "test", NoHostLifetime("test"), s)
        })
        val store = createFuseStore(services, scope)
        store.sources.add(File(dir, "roms").absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(90_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        return Device(store, services)
    }

    private fun theme(id: String) = ThemePresets.all.first { it.id == id }

    /** Waits as a person does after choosing: Fuse Sync's own rounds run, nothing is pushed by hand. */
    private suspend fun Device.stillShows(id: String, what: String, ms: Long = 9_000) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            assertEquals(id, store.prefs.value.themeId, "$what: shown")
            delay(100)
        }
        assertEquals(id, services.data.settings.current().appearance.themeId, "$what: stored")
    }

    private suspend fun household(phoneSkewMs: Long) {
        val pc = device("PC")
        val phone = device("Phone", phoneSkewMs)
        pc.sync.hostHere("PC", installService = false).getOrThrow()
        phone.sync.connect("127.0.0.1:$port", assertNotNull(pc.sync.newPairingCode())).getOrThrow()
        val me = pc.sync.createProfile("Me", "cat", null).getOrThrow()
        pc.sync.switchTo(me.id).getOrThrow()
        phone.sync.switchTo(me.id).getOrThrow()

        // Fusi chosen on the PC reaches the phone.
        pc.store.updatePrefs { it.withTheme(theme("fusi")) }
        pc.sync.syncNow().getOrThrow()
        phone.sync.syncNow().getOrThrow()
        withTimeout(45_000) { while (phone.store.prefs.value.themeId != "fusi") delay(50) }
        // Then Fuse again on the PC: the household's theme is Fuse.
        pc.store.updatePrefs { it.withTheme(ThemePresets.Fuse) }
        pc.sync.syncNow().getOrThrow()
        phone.sync.syncNow().getOrThrow()
        withTimeout(45_000) { while (phone.store.prefs.value.themeId != "fuse") delay(50) }

        // On the phone, Twin is chosen, and the phone is used: Home, a favourite. Rounds follow by themselves.
        phone.store.updatePrefs { it.withTheme(theme("twin")) }
        phone.stillShows("twin", "just after choosing Twin")
        val game = phone.store.library.games(GameQuery()).first().first()
        phone.store.library.setFavorite(game.id, true)
        phone.stillShows("twin", "after using Fuse")
        repeat(3) { phone.sync.syncNow().getOrThrow(); pc.sync.syncNow().getOrThrow() }
        phone.stillShows("twin", "after more rounds", ms = 1_000)
        withTimeout(45_000) { while (pc.store.prefs.value.themeId != "twin") delay(50) }
        pc.sync.stop()
        phone.sync.stop()
    }

    /**
     * A theme one device has and another hasn't (a newer Fuse's, or an added theme that device can't
     * read) shows as Fuse there, but stays the person's choice: using that device, which saves its
     * settings, never sends Fuse back to everyone as a new choice.
     */
    @Test
    fun aThemeAnotherDeviceDoesntKnowStaysChosen(): Unit = runBlocking {
        val pc = device("PC")
        val phone = device("Phone")
        pc.sync.hostHere("PC", installService = false).getOrThrow()
        phone.sync.connect("127.0.0.1:$port", assertNotNull(pc.sync.newPairingCode())).getOrThrow()
        val me = pc.sync.createProfile("Me", "cat", null).getOrThrow()
        pc.sync.switchTo(me.id).getOrThrow()
        phone.sync.switchTo(me.id).getOrThrow()

        // Chosen on the phone: a theme this Fuse has never heard of, as a newer Fuse's would be.
        phone.store.updatePrefs { it.copy(themeId = "aurora") }
        delay(3_000)
        assertEquals("aurora", phone.services.data.settings.current().appearance.themeId, "the phone saved its choice")
        phone.sync.syncNow().getOrThrow()
        pc.sync.syncNow().getOrThrow()
        kotlinx.coroutines.withTimeoutOrNull(45_000) { while (pc.services.data.settings.current().appearance.themeId != "aurora") delay(50) }
        assertEquals("aurora", pc.services.data.settings.current().appearance.themeId, "the choice reached the PC")
        assertEquals("fuse", pc.store.prefs.value.theme.id, "the PC shows Fuse for a theme it hasn't got")

        // The PC is used: a setting changes there, and its settings are saved.
        pc.store.updatePrefs { it.copy(videoPreview = !it.videoPreview) }
        delay(3_000)
        repeat(2) { pc.sync.syncNow().getOrThrow(); phone.sync.syncNow().getOrThrow() }
        assertEquals("aurora", pc.services.data.settings.current().appearance.themeId, "the PC kept the person's choice")
        assertEquals("aurora", phone.services.data.settings.current().appearance.themeId, "the phone's choice wasn't put back to Fuse")
        pc.sync.stop()
        phone.sync.stop()
    }

    /**
     * Two Fuses open on one computer's settings (the window, and a game Steam started with Fast
     * Start): a theme chosen in one isn't put back by the other, which still shows the one before,
     * when something else is changed there. Only what the person changed in a Fuse is written by it.
     */
    @Test
    fun anotherFuseOnTheSameSettingsDoesntPutTheThemeBack(): Unit = runBlocking {
        val db = File(root, "fuse.db").absolutePath
        fun open(name: String): FakeServices {
            val cache = File(root, "cache-$name").apply { mkdirs() }
            return FakeServices(FuseData(DesktopDatabase.open(db)), cache)
        }
        val windowServices = open("window")
        val window = createFuseStore(windowServices, scope)
        val runServices = open("run")
        val run = createFuseStore(runServices, scope)
        assertEquals("fuse", run.prefs.value.themeId)

        window.updatePrefs { it.withTheme(theme("twin")) }
        withTimeout(10_000) { while (windowServices.data.settings.current().appearance.themeId != "twin") delay(20) }
        val chosen = windowServices.data.settings.profileValues().getValue("appearance.themeId").revision

        // The other Fuse still shows Fuse; a setting is changed there.
        val video = run.prefs.value.videoPreview
        run.updatePrefs { it.copy(videoPreview = !video) }
        withTimeout(10_000) { while (runServices.data.settings.current().videoPreview.enabled == video) delay(20) }
        delay(500)
        val stored = runServices.data.settings.current()
        assertEquals("twin", stored.appearance.themeId, "the other Fuse didn't put Fuse back")
        assertEquals(chosen, runServices.data.settings.profileValues().getValue("appearance.themeId").revision, "nor sent it as a new choice")
    }

    @Test
    fun aThemeChosenStaysWithEveryClockInStep(): Unit = runBlocking { household(0) }

    @Test
    fun aThemeChosenStaysOnADeviceWhoseClockIsBehind(): Unit = runBlocking { household(-10 * 60_000L) }

    @Test
    fun aThemeChosenStaysOnADeviceWhoseClockIsAhead(): Unit = runBlocking { household(10 * 60_000L) }
}
