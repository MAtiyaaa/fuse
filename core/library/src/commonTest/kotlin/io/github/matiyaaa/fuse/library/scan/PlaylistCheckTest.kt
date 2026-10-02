package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.model.Disc
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistCheckTest {
    @Test
    fun aDiscThePlaylistNamesButIsGoneIsReported() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/ps2/GT4.m3u", content = "GT4 (Disc 1).chd\nGT4 (Disc 2).chd\n")
            .file("/roms/ps2/GT4 (Disc 1).chd")
        val discs = listOf(Disc(1, "Disc 1", "/roms/ps2/GT4 (Disc 1).chd"), Disc(2, "Disc 2", "/roms/ps2/GT4 (Disc 2).chd"))
        val missing = PlaylistCheck(fs).check("/roms/ps2/GT4.m3u", discs)
        assertEquals(1, missing.size)
        assertEquals("/roms/ps2/GT4 (Disc 2).chd", missing.single().path)
        assertEquals("/roms/ps2/GT4.m3u", missing.single().owner)
        assertEquals(2, missing.single().disc?.number)
    }

    @Test
    fun aCueWhoseTrackIsGoneIsReported() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/psx/Game.cue", content = "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"Game (Track 2).bin\" BINARY\n")
            .file("/roms/psx/Game (Track 1).bin")
        val missing = PlaylistCheck(fs).check("/roms/psx/Game.cue", emptyList())
        assertEquals(listOf("/roms/psx/Game (Track 2).bin"), missing.map { it.path })
    }

    @Test
    fun aCompleteGameAndAnUnreadableSheetReportNothing() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/dc/Game.gdi", content = "2\n1 0 4 2352 track01.bin 0\n2 600 0 2352 track02.raw 0\n")
            .file("/roms/dc/track01.bin").file("/roms/dc/track02.raw")
            .file("/roms/psx/Other.cue", content = null)
        assertTrue(PlaylistCheck(fs).check("/roms/dc/Game.gdi", emptyList()).isEmpty())
        assertTrue(PlaylistCheck(fs).check("/roms/psx/Other.cue", emptyList()).isEmpty())
        assertTrue(PlaylistCheck(fs).check("/roms/gba/x.gba", emptyList()).isEmpty())
    }
}
