package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** System health end to end: what it finds, what it leaves alone, and the report it writes. */
class HealthStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-health").toFile()
        cache = Files.createTempDirectory("fuse-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(64))
        File(root, "psx").mkdirs()
        // A playlist that names a second disc that isn't there.
        File(root, "psx/Metal Gear Solid.m3u").writeText("Metal Gear Solid (Disc 1).chd\nMetal Gear Solid (Disc 2).chd\n")
        File(root, "psx/Metal Gear Solid (Disc 1).chd").writeText("disc1")
        // A system nothing installed can run.
        File(root, "n64").mkdirs()
        File(root, "n64/Wave Racer.z64").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private suspend fun report(store: FuseStore, condition: (HealthReport) -> Boolean): HealthReport = try {
        withTimeout(20_000) { store.health.report.first(condition) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Health never matched; it has: " + store.health.report.value.issues.map { it.id + ": " + it.problem.title } + " platforms=" + store.library.platforms.value.map { it.platform.id.value + " installed=" + it.emulatorInstalled })
    }

    @Test
    fun findsWhatNeedsDoingAndExplainsIt(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.size == 3 } }
        store.health.check()

        val found = report(store) { r -> r.issues.any { it.id.startsWith("files.") } && r.issues.any { it.id == "emulator.n64" } }
        val disc = found.issues.first { it.id.startsWith("files.") }
        assertEquals("Metal Gear Solid: Disc 2 missing", disc.problem.title)
        assertNotNull(disc.game)
        assertTrue(disc.problem.actions.any { it is ProblemAction.OpenGame })
        val emulator = found.issues.first { it.id == "emulator.n64" }
        assertTrue(emulator.problem.title.startsWith("No emulator for"))
        // Systems with an emulator are left alone.
        assertFalse(found.issues.any { it.id == "emulator.gba" })
        assertEquals(Severity.ATTENTION, found.worst)

        // A game whose file goes is a note, not an alarm, and never deleted.
        File(root, "gba/Advance Wars (USA).gba").delete()
        store.sources.rescan(ScanScope.FULL)
        val missing = report(store) { r -> r.issues.any { it.id == "games.missing" } }.issues.first { it.id == "games.missing" }
        assertEquals(Severity.INFO, missing.problem.severity)
        assertTrue(missing.problem.actions.any { it is ProblemAction.ShowMissing })
    }

    @Test
    fun aDriveThatIsOutIsANoteAndItsGamesAreNotMissing(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse2.db").absolutePath)), cache)
        val card = StorageVolume("uuid:CARD", "SD card", listOf(root.absolutePath), VolumeKind.SD_CARD, removable = true)
        services.drives = listOf(StorageVolume("uuid:SYS", "System drive", listOf("/"), VolumeKind.INTERNAL), card)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.size == 3 } }
        withTimeout(20_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        services.drives = listOf(StorageVolume("uuid:SYS", "System drive", listOf("/"), VolumeKind.INTERNAL))
        store.sources.refreshDrives()
        val r = report(store) { r -> r.issues.any { it.id.startsWith("folder.") } }
        val folder = r.issues.first { it.id.startsWith("folder.") }
        assertEquals("SD card isn't connected", folder.problem.title)
        assertEquals(Severity.INFO, folder.problem.severity)
        assertFalse(r.issues.any { it.id == "games.missing" })
    }

    @Test
    fun theReportLeavesOutTheHomeFolderAndSecrets(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse3.db").absolutePath)), cache)
        services.secrets.put("sgdb.apikey", "SUPERSECRETKEY123")
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.size == 3 } }
        val text = store.health.diagnostics(listOf("Device: test"), crash = "boom at https://api.example.com/x?apikey=abc123")
        assertTrue("## Systems" in text, text)
        assertTrue("Game Boy Advance" in text, text)
        assertFalse("SUPERSECRETKEY123" in text)
        assertFalse("abc123" in text, text)
        assertTrue("Device: test" in text)
    }
}
