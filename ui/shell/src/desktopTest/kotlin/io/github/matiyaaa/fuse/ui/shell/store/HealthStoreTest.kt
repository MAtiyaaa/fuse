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
    fun libraryHealthFindsPicturesWhoseFilesAreGoneAndPutsThemRight(): Unit = runBlocking {
        val data = FuseData(DesktopDatabase.open(File(cache, "fuse3.db").absolutePath))
        val store = createFuseStore(FakeServices(data, cache), scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.size == 3 } }
        val kept = File(cache, "kept.png").apply { writeBytes(ByteArray(8)) }
        val (a, b, c) = games.sortedBy { it.title }.map { io.github.matiyaaa.fuse.model.MediaOwner.OfGame(it.id) }
        // A downloaded cover whose file went, one that is still there, and the person's own, gone too.
        data.media.putScraped(a, listOf(io.github.matiyaaa.fuse.model.MediaItem(io.github.matiyaaa.fuse.model.MediaKind.BOXART, io.github.matiyaaa.fuse.model.MediaSource.STEAMGRIDDB, localPath = File(cache, "gone.png").absolutePath, remoteUrl = "https://example.org/a.png")), io.github.matiyaaa.fuse.model.MediaFillMode.FILL_MISSING)
        data.media.putScraped(b, listOf(io.github.matiyaaa.fuse.model.MediaItem(io.github.matiyaaa.fuse.model.MediaKind.BOXART, io.github.matiyaaa.fuse.model.MediaSource.STEAMGRIDDB, localPath = kept.absolutePath)), io.github.matiyaaa.fuse.model.MediaFillMode.FILL_MISSING)
        data.media.setCustom(c, io.github.matiyaaa.fuse.model.MediaKind.BOXART, File(cache, "mine.png").absolutePath)
        store.health.check()

        // A check the scan started may have looked before all three were written: wait for one that saw them.
        val gone = report(store) { r -> r.issues.any { it.id == "library.pictures.gone" && it.problem.title.startsWith("2 ") } }.issues.first { it.id == "library.pictures.gone" }
        assertEquals("2 pictures' files are gone", gone.problem.title)
        assertTrue("1 will be downloaded again" in gone.problem.reassurance.orEmpty(), gone.problem.reassurance)
        assertTrue("1 you chose yourself stay" in gone.problem.reassurance.orEmpty(), gone.problem.reassurance)
        assertTrue(gone.problem.actions.any { it is ProblemAction.RepairPictures })

        assertEquals(1, store.health.repairPictures())
        assertEquals(null, data.media.get(a).boxart?.localPath)
        assertEquals(kept.absolutePath, data.media.get(b).boxart?.localPath)
        assertEquals(File(cache, "mine.png").absolutePath, data.media.get(c).boxart?.localPath)
        // Only the person's own is left, and it stays reported, never changed.
        val after = report(store) { r -> r.issues.firstOrNull { it.id == "library.pictures.gone" }?.problem?.title == "A picture's file is gone" }
        assertTrue(after.issues.any { it.id == "library.pictures.gone" })
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
    fun whatTheInterfaceFindsIsShownWithTheRestAndGoesWhenPutRight(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse4.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        val steam = io.github.matiyaaa.fuse.ui.shell.store.HealthIssue(
            "steam.entries",
            io.github.matiyaaa.fuse.ui.shell.store.Problem("A Steam entry needs putting right", "Metroid Fusion.", actions = listOf(ProblemAction.PutSteamRight())),
        )
        store.health.setFound("steam", listOf(steam))
        assertTrue(report(store) { r -> r.issues.any { it.id == "steam.entries" } }.issues.single { it.id == "steam.entries" }.problem.actions.single() is ProblemAction.PutSteamRight)
        store.health.setFound("steam", emptyList())
        report(store) { r -> r.issues.none { it.id == "steam.entries" } }
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
