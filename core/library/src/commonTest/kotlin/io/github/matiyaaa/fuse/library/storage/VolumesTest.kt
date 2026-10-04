package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.model.VolumeRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VolumesTest {
    private val root = StorageVolume("uuid:root", "System", listOf("/"), VolumeKind.INTERNAL)
    private val sd = StorageVolume("uuid:SD-1", "SD card", listOf("/run/media/me/GAMES"), VolumeKind.SD_CARD, removable = true)
    private val ssd = StorageVolume("uuid:SSD-2", "External SSD", listOf("/run/media/me/SSD"), VolumeKind.USB, removable = true)

    private fun source(path: String, ref: VolumeRef? = null, id: Long = 1) =
        LibrarySource(LibrarySourceId(id), path, "ROMs", LibrarySourceKind.ROMS_ROOT, volume = ref)

    private fun refOn(v: StorageVolume, relative: String) = VolumeRef(v.id, v.label, v.kind, v.removable, relative)

    private suspend fun eval(s: LibrarySource, vols: List<StorageVolume>, folders: Set<String>, unreadable: Set<String> = emptySet()) =
        Volumes.evaluate(s, vols, exists = { it in folders }, readable = { it !in unreadable })

    @Test
    fun locatesTheLongestMountAndTheRelativePath() {
        val (v, rel) = Volumes.locate("/run/media/me/GAMES/ROMs/psx", listOf(root, sd))!!
        assertEquals(sd.id, v.id)
        assertEquals("ROMs/psx", rel)
        assertEquals(root.id, Volumes.locate("/home/me/roms", listOf(root, sd))!!.first.id)
        assertNull(Volumes.locate("/x", listOf(sd)))
    }

    @Test
    fun aMountPathIsNotAPrefixOfASiblingName() {
        assertNull(Volumes.relativeTo("/run/media/me/GAMES2/x", "/run/media/me/GAMES"))
        assertEquals("x", Volumes.relativeTo("/run/media/me/GAMES/x", "/run/media/me/GAMES"))
    }

    @Test
    fun windowsDrivesCompareWithoutCase() {
        assertEquals("Games/ps2", Volumes.relativeTo("e:/Games/ps2", "E:/"))
        assertEquals("Games", Volumes.relativeTo("E:/Games", "E:"))
    }

    @Test
    fun aFolderWithoutADriveYetIsCheckedAtItsPath() = runTest {
        val s = source("/run/media/me/GAMES/ROMs")
        assertEquals(SourceState.ONLINE, eval(s, listOf(root, sd), setOf(s.path)).state)
        assertEquals(SourceState.FOLDER_MISSING, eval(s, listOf(root, sd), emptySet()).state)
        assertEquals(SourceState.NO_ACCESS, eval(s, listOf(root, sd), setOf(s.path), unreadable = setOf(s.path)).state)
    }

    @Test
    fun anUnpluggedCardIsOfflineEvenWhenItsEmptyMountPointRemains() = runTest {
        val s = source("/run/media/me/GAMES/ROMs", refOn(sd, "ROMs"))
        // The card is out; the mount point folder (and even a folder of the same name) is on the root drive.
        val status = eval(s, listOf(root), setOf("/run/media/me/GAMES/ROMs"))
        assertEquals(SourceState.OFFLINE, status.state)
        assertEquals(false, status.scannable)
        assertEquals("SD card", status.driveLabel)
    }

    @Test
    fun theCardComingBackPutsTheFolderOnline() = runTest {
        val s = source("/run/media/me/GAMES/ROMs", refOn(sd, "ROMs"))
        assertEquals(SourceState.ONLINE, eval(s, listOf(root, sd), setOf(s.path)).state)
    }

    @Test
    fun aDriveMountedSomewhereElseIsFollowed() = runTest {
        val s = source("/run/media/me/GAMES/ROMs", refOn(sd, "ROMs"))
        val elsewhere = sd.copy(mountPaths = listOf("/media/me/GAMES"))
        val status = eval(s, listOf(root, elsewhere), setOf("/media/me/GAMES/ROMs"))
        assertEquals(SourceState.MOVED, status.state)
        assertEquals("/media/me/GAMES/ROMs", status.relinkTo)
    }

    @Test
    fun aWindowsDriveThatChangedLetterIsFollowed() = runTest {
        val e = StorageVolume("vsn:1A2B3C4D", "Games", listOf("E:/"), VolumeKind.USB, removable = true)
        val s = source("E:/Games", refOn(e, "Games"))
        val nowF = e.copy(mountPaths = listOf("F:/"))
        val status = eval(s, listOf(nowF), setOf("F:/Games"))
        assertEquals(SourceState.MOVED, status.state)
        assertEquals("F:/Games", status.relinkTo)
    }

    @Test
    fun itsDriveWithoutTheFolderIsAMissingFolderNotAMove() = runTest {
        val s = source("/run/media/me/GAMES/ROMs", refOn(sd, "ROMs"))
        val elsewhere = sd.copy(mountPaths = listOf("/media/me/GAMES"))
        assertEquals(SourceState.FOLDER_MISSING, eval(s, listOf(root, elsewhere), emptySet()).state)
    }

    @Test
    fun anotherCardInTheSameSlotIsNotTakenForThisOne() = runTest {
        val s = source("/run/media/me/GAMES/ROMs", refOn(sd, "ROMs"))
        val other = StorageVolume("uuid:OTHER", "GAMES", listOf("/run/media/me/GAMES"), VolumeKind.SD_CARD, removable = true)
        assertEquals(SourceState.OTHER_DRIVE, eval(s, listOf(root, other), setOf(s.path)).state)
    }

    @Test
    fun aWeakIdNeverMakesAReadableFolderOffline() = runTest {
        val weak = StorageVolume(StorageVolume.weakId("/mnt/games"), "games", listOf("/mnt/games"))
        val s = source("/mnt/games/ROMs", refOn(weak, "ROMs"))
        // The system now reports a real UUID for the same drive.
        val strong = weak.copy(id = "uuid:NEW")
        assertEquals(SourceState.ONLINE, eval(s, listOf(root, strong), setOf(s.path)).state)
        // And without the folder, it's offline rather than missing.
        assertEquals(SourceState.OFFLINE, eval(s, listOf(root), emptySet()).state)
    }

    @Test
    fun theSecondSsdStaysOnlineWhileTheCardIsOut() = runTest {
        val onCard = source("/run/media/me/GAMES/ROMs", refOn(sd, "ROMs"), id = 1)
        val onSsd = source("/run/media/me/SSD/ROMs", refOn(ssd, "ROMs"), id = 2)
        val folders = setOf(onSsd.path)
        assertEquals(SourceState.OFFLINE, eval(onCard, listOf(root, ssd), folders).state)
        assertEquals(SourceState.ONLINE, eval(onSsd, listOf(root, ssd), folders).state)
    }

    @Test
    fun rebaseMovesEveryPathInsideTheOldRootOnly() {
        assertEquals("/media/me/GAMES/ROMs/psx/a.chd", Volumes.rebase("/run/media/me/GAMES/ROMs/psx/a.chd", "/run/media/me/GAMES/ROMs", "/media/me/GAMES/ROMs"))
        assertEquals("/media/me/GAMES/ROMs", Volumes.rebase("/run/media/me/GAMES/ROMs", "/run/media/me/GAMES/ROMs", "/media/me/GAMES/ROMs"))
        assertNull(Volumes.rebase("/run/media/me/GAMES/ROMs2/a", "/run/media/me/GAMES/ROMs", "/x"))
    }

    @Test
    fun aliasesOfOneFolderAreFound() {
        val a = source("/run/media/me/GAMES/ROMs", id = 1)
        val b = source("/home/me/roms", id = 2)
        val c = source("/home/me/other", id = 3)
        val groups = Volumes.duplicateRoots(mapOf(a to "/run/media/me/GAMES/ROMs", b to "/run/media/me/GAMES/ROMs", c to "/home/me/other"))
        assertEquals(listOf(listOf(a, b)), groups)
    }

    @Test
    fun aMacWhoseSystemDiskChangedIdKeepsItsFoldersOnline() = runTest {
        // Fuse's Steam shortcuts live on the Mac's own disk; after a macOS update that disk reports
        // a new id. The folder is still there, so its games stay where they are, never "missing".
        val before = StorageVolume("uuid:SEALED-1", "Macintosh HD", listOf("/"), VolumeKind.INTERNAL)
        val steam = source("/Users/me/Library/Application Support/Fuse/steam", refOn(before, "Users/me/Library/Application Support/Fuse/steam"))
        val after = before.copy(id = "uuid:SEALED-2")
        assertEquals(SourceState.ONLINE, eval(steam, listOf(after), setOf(steam.path)).state)
        // Without the folder it is missing, as anywhere else.
        assertEquals(SourceState.OFFLINE, eval(steam, listOf(after, sd), emptySet()).state)
    }
}
