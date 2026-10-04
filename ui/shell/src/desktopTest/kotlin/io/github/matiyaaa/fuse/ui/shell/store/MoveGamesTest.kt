package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.ScanPhase
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Moving games to an SD card: their files land in the card's system folders (one already there is
 * joined, whatever its name's case), leave the device, and each game keeps its id, favourite and
 * play history. A card without a games folder gets one made and added to the library.
 */
class MoveGamesTest {
    private lateinit var base: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("fuse-move").toFile()
        cache = Files.createTempDirectory("fuse-move-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        base.deleteRecursively()
        cache.deleteRecursively()
    }

    private fun drive(id: String, label: String, mount: File, kind: VolumeKind) =
        StorageVolume(id, label, listOf(mount.absolutePath), kind, removable = kind == VolumeKind.SD_CARD, totalBytes = 1L shl 32, freeBytes = 1L shl 31)

    @Test
    fun gamesMoveToTheCardAndStayTheSameGames(): Unit = runBlocking {
        val inside = File(base, "internal").apply { mkdirs() }
        val card = File(base, "card").apply { mkdirs() }
        File(inside, "ROMs/gba").mkdirs()
        File(inside, "ROMs/gba/Advance Wars (USA).gba").writeBytes(ByteArray(2048) { 7 })
        File(inside, "ROMs/psx/Crash").mkdirs()
        File(inside, "ROMs/psx/Crash/Crash Bandicoot (USA).cue").writeText("FILE \"Crash Bandicoot (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n")
        File(inside, "ROMs/psx/Crash/Crash Bandicoot (USA).bin").writeBytes(ByteArray(4096) { 3 })
        File(card, "ROMs/GBA").mkdirs()
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        services.drives = listOf(drive("uuid:INT", "Internal storage", inside, VolumeKind.INTERNAL), drive("uuid:CARD", "SD card", card, VolumeKind.SD_CARD))
        val store = createFuseStore(services, scope)
        store.sources.add(File(inside, "ROMs").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val before = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        val wars = before.single { it.title.startsWith("Advance") }
        val crash = before.single { it.title.startsWith("Crash") }
        store.library.setFavorite(wars.id, true)

        // The card has no games folder in the library yet: moving says so, and one can be made.
        val target = store.storage.moveTargets().single { it.volumeId == "uuid:CARD" }
        assertNull(target.gamesFolder)
        assertEquals(0, store.storage.move(listOf(wars.id), "uuid:CARD").moved)
        val folder = assertNotNull(store.storage.makeGamesFolder("uuid:CARD"))
        assertEquals(File(card, "ROMs").absolutePath, folder)
        withTimeout(20_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        withTimeout(5_000) { store.sources.status.first { s -> s.size == 2 && s.all { it.volume != null } } }

        val report = store.storage.move(listOf(wars.id, crash.id), "uuid:CARD")
        assertEquals(2, report.moved, "${report.failed} ${report.reason}")
        assertEquals(2048L + 4096L + File(card, "ROMs/psx/Crash/Crash Bandicoot (USA).cue").length(), report.bytes)
        // Into the card's own GBA folder, and a psx folder made for Crash with its subfolder.
        assertTrue(File(card, "ROMs/GBA/Advance Wars (USA).gba").isFile)
        assertTrue(File(card, "ROMs/psx/Crash/Crash Bandicoot (USA).bin").isFile)
        assertFalse(File(inside, "ROMs/gba/Advance Wars (USA).gba").exists())
        assertFalse(File(inside, "ROMs/psx/Crash/Crash Bandicoot (USA).bin").exists())

        withTimeout(20_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        val after = withTimeout(10_000) { store.library.games(GameQuery()).first { list -> list.size == 2 && list.none { it.missing } } }
        assertEquals(setOf(wars.id, crash.id), after.map { it.id }.toSet(), "the same games, not new ones")
        assertTrue(after.single { it.id == wars.id }.favorite)
        assertNull(store.storage.moving.value)
    }

    @Test
    fun aNewCardIsSetUpWithAFolderForEachSystem(): Unit = runBlocking {
        val inside = File(base, "internal").apply { mkdirs() }
        val card = File(base, "card").apply { mkdirs() }
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse2.db").absolutePath)), cache)
        services.drives = listOf(drive("uuid:INT", "Internal storage", inside, VolumeKind.INTERNAL), drive("uuid:CARD", "SD card", card, VolumeKind.SD_CARD))
        val store = createFuseStore(services, scope)
        val setup = assertNotNull(store.storage.setUpDrive("uuid:CARD"))
        assertEquals(File(card, "Emulation/ROMs").absolutePath, setup.romsFolder)
        assertTrue(File(card, "Emulation/ROMs/gba").isDirectory)
        assertTrue(File(card, "Emulation/ROMs/psx").isDirectory)
        assertTrue(File(card, "Emulation/bios/psx").isDirectory, "firmware goes beside the ROMs, where Fuse looks for it")
        assertFalse(File(card, "Emulation/ROMs/android").exists())
        assertTrue(setup.systems >= 15)
        assertEquals(setup.romsFolder, store.sources.sources.value.single().path)
    }
}
