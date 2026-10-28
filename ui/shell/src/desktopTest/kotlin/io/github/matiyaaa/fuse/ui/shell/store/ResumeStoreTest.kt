package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
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

/** Continue: a game left mid-play carries on from its resume state, Start Fresh never does. */
class ResumeStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-resume").toFile()
        cache = Files.createTempDirectory("fuse-resume-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun continueCarriesOnFromDuckStationsResumeState() = runBlocking {
        File(root, "roms/psx").mkdirs()
        File(root, "roms/psx/Spyro the Dragon (USA) (SCUS-94228).chd").writeBytes(ByteArray(64))
        // A portable DuckStation: its data (savestates) beside the program.
        val duck = File(root, "duck").apply { mkdirs() }
        File(duck, "settings.ini").writeText("[Main]\nSaveStateOnExit = true\n")
        lateinit var services: FakeServices
        services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, File(duck, "duckstation-qt").absolutePath, platforms = setOf(PlatformId("psx")), detectedVia = "PATH")),
            sync = { port, sc ->
                io.github.matiyaaa.fuse.sync.JvmSyncService(
                    File(cache, "sync"), services.data.settings, services.secrets, port, "LINUX", "Desk", "test",
                    io.github.matiyaaa.fuse.sync.NoHostLifetime("test"), sc,
                )
            },
        )
        val store = createFuseStore(services, scope)
        store.sources.add(File(root, "roms").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        // Never left mid-game: nothing to continue from, and Play is plain.
        assertNull(store.library.resumePoint(game.id))

        // DuckStation kept where it was left as it closed.
        File(duck, "savestates").mkdirs()
        File(duck, "savestates/SCUS-94228_resume.sav").writeBytes(ByteArray(128) { 7 })
        val point = assertNotNull(store.library.resumePoint(game.id))
        assertEquals("DuckStation", point.emulator)
        assertNotNull(point.at)

        assertIs<LaunchOutcome.Started>(store.library.launch(game.id, resume = true))
        val continued = assertIs<LaunchPlan.Command>(services.launched.last().plan).argv
        assertTrue("-resume" in continued, continued.toString())
        assertTrue(continued.indexOf("-resume") < continued.indexOfFirst { it.endsWith(".chd") }, "before the game's file: $continued")

        // Start Fresh: exactly the launch it always was.
        services.launched.clear()
        assertIs<LaunchOutcome.Started>(store.library.launch(game.id))
        val fresh = assertIs<LaunchPlan.Command>(services.launched.last().plan).argv
        assertFalse("-resume" in fresh, fresh.toString())

        // Continue turned off: no resume point is offered, and asking for one starts it fresh.
        store.updatePrefs { it.copy(keepPlace = false) }
        withTimeout(10_000) { while (services.data.settings.current().library.keepPlace) kotlinx.coroutines.delay(20) }
        assertNull(store.library.resumePoint(game.id))
        services.launched.clear()
        assertIs<LaunchOutcome.Started>(store.library.launch(game.id, resume = true))
        assertFalse("-resume" in assertIs<LaunchPlan.Command>(services.launched.last().plan).argv)
    }
}
