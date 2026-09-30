package io.github.matiyaaa.fuse.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FsPathTest {
    @Test
    fun joinsWithOneSeparator() {
        assertEquals("/roms/psx", FsPath.join("/roms/", "/psx"))
        assertEquals("/psx", FsPath.join("/", "psx"))
        assertEquals("/a/b/c", FsPath.join("/a", "b", "c"))
    }

    @Test
    fun splitsNamesAndExtensions() {
        assertEquals("Game.cue", FsPath.name("/roms/psx/Game.cue"))
        assertEquals("/roms/psx", FsPath.parent("/roms/psx/Game.cue"))
        assertNull(FsPath.parent("/"))
        assertEquals("cue", FsPath.extension("Game.CUE"))
        assertEquals("", FsPath.extension(".hidden"))
        assertEquals("Super Mario Bros. 3", FsPath.stem("Super Mario Bros. 3.nes"))
    }

    @Test
    fun normalisesAndResolvesReferences() {
        assertEquals("/a/c", FsPath.normalize("/a//b/../c/"))
        assertEquals("/roms/psx/Disc 1/g.cue", FsPath.resolve("/roms/psx", "Disc 1\\g.cue"))
        assertEquals("/roms/g.cue", FsPath.resolve("/roms/psx", "../g.cue"))
        assertEquals("/abs/g.cue", FsPath.resolve("/roms/psx", "/abs/g.cue"))
    }

    @Test
    fun relativisesAndChecksAncestry() {
        assertEquals("../roms/psx/d1.chd", FsPath.relativize("/cache/m3u", "/cache/roms/psx/d1.chd"))
        assertEquals("d1.chd", FsPath.relativize("/roms/psx", "/roms/psx/d1.chd"))
        assertTrue(FsPath.isWithin("/storage/Android/data/x", "/storage/Android/data"))
        assertFalse(FsPath.isWithin("/storage/Android/database", "/storage/Android/data"))
    }
}
