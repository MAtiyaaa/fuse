package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A library on a removable drive, end to end: unplugged it reads as offline (never as deleted, even
 * with an empty mount point left behind), launching says which drive to connect, deleting refuses,
 * and plugged back in under another path every game comes back as it was.
 */
class DriveStoreTest {
    private lateinit var base: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("fuse-drives").toFile()
        cache = Files.createTempDirectory("fuse-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        base.deleteRecursively()
        cache.deleteRecursively()
    }

    private fun card(mount: File) = StorageVolume("uuid:CARD", "SD card", listOf(mount.absolutePath), VolumeKind.SD_CARD, removable = true, totalBytes = 1 shl 30, freeBytes = 1 shl 29)
    private val system = StorageVolume("uuid:SYSTEM", "System drive", listOf("/"), VolumeKind.INTERNAL)

    @Test
    fun anUnpluggedCardIsOfflineAndComesBackUnderANewPath(): Unit = runBlocking {
        val mountA = File(base, "run/media/me/GAMES").apply { mkdirs() }
        File(mountA, "ROMs/gba").mkdirs()
        File(mountA, "ROMs/gba/Advance Wars (USA).gba").writeBytes(ByteArray(1024))
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        services.drives = listOf(system, card(mountA))
        val store = createFuseStore(services, scope)
        store.sources.add(File(mountA, "ROMs").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        store.library.setFavorite(game.id, true)
        withTimeout(5_000) { store.sources.status.first { s -> s.singleOrNull()?.source?.volume?.id == "uuid:CARD" } }

        // Out: the card's files go, and the empty mount point (with an empty ROMs/gba) stays behind.
        services.drives = listOf(system)
        val mountB = File(base, "media/me/GAMES")
        mountB.parentFile.mkdirs()
        assertTrue(mountA.renameTo(mountB))
        File(mountA, "ROMs/gba").mkdirs()
        store.sources.rescan(ScanScope.FULL)
        withTimeout(10_000) { store.sources.status.first { it.single().state == SourceState.OFFLINE } }
        withTimeout(10_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }

        val away = withTimeout(5_000) { store.library.games(GameQuery()).first { it.single().unavailable != null } }.single()
        assertFalse(away.missing, "an unplugged card's games are never missing")
        assertEquals("SD card unavailable", away.unavailable?.label)
        val outcome = assertIs<LaunchOutcome.Problem>(store.library.launch(away.id))
        assertEquals(ProblemKind.DRIVE, outcome.problem.kind)
        assertTrue(outcome.problem.message.contains("SD card"))
        assertTrue(services.launched.isEmpty())
        assertEquals(0, store.storage.delete(listOf(away.id)).deleted, "nothing is deleted while the drive is unknown")
        assertTrue(store.library.games(GameQuery(set = GameSet.MISSING)).first().isEmpty())

        // Back, mounted under another path: the folder follows it and every game is as it was.
        services.drives = listOf(system, card(mountB))
        store.sources.refreshDrives()
        val status = withTimeout(10_000) { store.sources.status.first { it.single().state == SourceState.ONLINE } }.single()
        assertEquals(File(mountB, "ROMs").absolutePath, status.source.path)
        val back = withTimeout(10_000) { store.library.games(GameQuery()).first { it.single().unavailable == null } }.single()
        assertEquals(game.id, back.id)
        assertTrue(back.favorite)
        assertFalse(back.missing)
        assertIs<LaunchOutcome.Started>(store.library.launch(back.id))
        val launched = assertNotNull(services.launched.lastOrNull())
        assertTrue(launched.toString().contains(mountB.absolutePath), "$launched")
    }

    @Test
    fun aFileGoneFromAConnectedDriveIsExplained(): Unit = runBlocking {
        val lib = File(base, "roms").apply { mkdirs() }
        File(lib, "gba").mkdirs()
        val rom = File(lib, "gba/Advance Wars (USA).gba").apply { writeBytes(ByteArray(10)) }
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse2.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(lib.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        rom.delete()
        val outcome = assertIs<LaunchOutcome.Problem>(store.library.launch(game.id))
        assertEquals(ProblemKind.FILE, outcome.problem.kind)
        assertTrue(outcome.problem.actions.any { it is ProblemAction.Rescan })
        assertTrue(services.launched.isEmpty())
    }

    @Test
    fun aCardPulledOutDuringAScanLosesNothing(): Unit = runBlocking {
        val mount = File(base, "run/media/me/CARD").apply { mkdirs() }
        File(mount, "ROMs/gba").mkdirs()
        File(mount, "ROMs/gba/Advance Wars (USA).gba").writeBytes(ByteArray(1024))
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse3.db").absolutePath)), cache)
        services.drives = listOf(system, card(mount))
        val store = createFuseStore(services, scope)
        store.sources.add(File(mount, "ROMs").absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }
        withTimeout(10_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }

        // The next scan reads the card as connected, then it is pulled just before its games are listed.
        val gba = File(mount, "ROMs/gba").absolutePath
        var pulled = false
        services.javaFs.beforeList = { path ->
            if (path == gba && !pulled) {
                pulled = true
                services.drives = listOf(system)
                File(mount, "ROMs/gba/Advance Wars (USA).gba").delete()
            }
        }
        store.sources.rescan(ScanScope.FULL)
        withTimeout(10_000) { while (!pulled) kotlinx.coroutines.delay(10) }
        withTimeout(10_000) { store.sources.status.first { it.single().state == SourceState.OFFLINE } }
        withTimeout(10_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        kotlinx.coroutines.delay(200)
        assertTrue(store.library.games(GameQuery(set = GameSet.MISSING)).first().isEmpty(), "nothing marked missing")
        assertEquals(1, store.library.games(GameQuery()).first().size)
    }
}
