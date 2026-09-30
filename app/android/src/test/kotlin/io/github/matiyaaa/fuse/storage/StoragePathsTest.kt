package io.github.matiyaaa.fuse.storage

import io.github.matiyaaa.fuse.model.LibrarySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathsTest {
    private val primary = Volume("/storage/emulated/0", "primary", isPrimary = true)
    private val card = Volume("/storage/ABCD-1234", "ABCD-1234", isPrimary = false)
    private val volumes = listOf(primary, card)

    @Test
    fun treeDocumentIdsBecomePaths() {
        assertEquals("/storage/emulated/0/ROMs", StoragePaths.pathForDocumentId("primary:ROMs", volumes))
        assertEquals("/storage/emulated/0", StoragePaths.pathForDocumentId("primary:", volumes))
        assertEquals("/storage/ABCD-1234/x", StoragePaths.pathForDocumentId("ABCD-1234:x", volumes))
        assertEquals("/storage/1A2B-3C4D/Games", StoragePaths.pathForDocumentId("1A2B-3C4D:Games", volumes))
        assertEquals("/storage/emulated/0/Documents/roms", StoragePaths.pathForDocumentId("home:roms", volumes))
        assertNull(StoragePaths.pathForDocumentId("raw:/storage/emulated/0/Download", volumes))
        assertNull(StoragePaths.pathForDocumentId("primary:../../data", volumes))
        assertNull(StoragePaths.pathForDocumentId("nocolon", volumes))
    }

    @Test
    fun safIdsFollowTheSystemFolderLikeEsDe() {
        val sources = listOf(SourceRoot("/storage/emulated/0/ROMs", LibrarySourceKind.ROMS_ROOT))
        val ids = StoragePaths.safIds("/storage/emulated/0/ROMs/n3ds/Animal Crossing New Leaf.cci", sources, volumes)
        assertEquals("primary:ROMs/n3ds" to "primary:ROMs/n3ds/Animal Crossing New Leaf.cci", ids)
    }

    @Test
    fun nestedGamesUseTheirPlatformFolder() {
        val sources = listOf(SourceRoot("/storage/ABCD-1234/ROMs", LibrarySourceKind.ROMS_ROOT))
        val ids = StoragePaths.safIds("/storage/ABCD-1234/ROMs/psx/Final Fantasy VII/FF7.m3u", sources, volumes)
        assertEquals("ABCD-1234:ROMs/psx" to "ABCD-1234:ROMs/psx/Final Fantasy VII/FF7.m3u", ids)
    }

    @Test
    fun platformFolderSourcesAreTheirOwnTree() {
        val sources = listOf(SourceRoot("/storage/emulated/0/Games/GBA", LibrarySourceKind.PLATFORM_FOLDER))
        val ids = StoragePaths.safIds("/storage/emulated/0/Games/GBA/sub/Metroid.gba", sources, volumes)
        assertEquals("primary:Games/GBA" to "primary:Games/GBA/sub/Metroid.gba", ids)
    }

    @Test
    fun rommLibrariesUseTheirPlatformRomsFolder() {
        val sources = listOf(SourceRoot("/storage/emulated/0/library", LibrarySourceKind.ROMM_LIBRARY))
        assertEquals(
            "/storage/emulated/0/library/roms/gba",
            StoragePaths.systemFolder("/storage/emulated/0/library/roms/gba/Game.gba", sources),
        )
        assertEquals(
            "/storage/emulated/0/library/gba/roms",
            StoragePaths.systemFolder("/storage/emulated/0/library/gba/roms/Game.gba", sources),
        )
    }

    @Test
    fun withoutASourceTheRomsChildOrParentIsUsed() {
        assertEquals("/storage/emulated/0/ROMs/snes", StoragePaths.systemFolder("/storage/emulated/0/ROMs/snes/a/b.sfc", emptyList()))
        assertEquals("/storage/emulated/0/Other", StoragePaths.systemFolder("/storage/emulated/0/Other/b.sfc", emptyList()))
    }

    @Test
    fun sdcardAliasesMapToPrimary() {
        assertEquals("primary:ROMs/gb/Tetris.gb", StoragePaths.documentId("/sdcard/ROMs/gb/Tetris.gb", volumes))
        assertEquals("primary:ROMs/gb/Tetris.gb", StoragePaths.documentId("/storage/self/primary/ROMs/gb/Tetris.gb", volumes))
    }

    @Test
    fun privateFilesAreNotOnSharedStorage() {
        assertNull(StoragePaths.safIds("/data/user/0/io.github.matiyaaa.fuse/cache/playlists/1/Game.m3u", emptyList(), volumes))
        assertNull(StoragePaths.documentId("/storage/emulated/01/x", volumes))
    }

    @Test
    fun cacheRelativePathsCannotEscape() {
        assertTrue(StoragePaths.isSafeRelative("playlists/42/Game.m3u"))
        assertFalse(StoragePaths.isSafeRelative("../databases/fuse.db"))
        assertFalse(StoragePaths.isSafeRelative("playlists/../../x"))
        assertFalse(StoragePaths.isSafeRelative("/etc/hosts"))
        assertFalse(StoragePaths.isSafeRelative(""))
    }
}
