package io.github.matiyaaa.fuse.library.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistsTest {
    @Test
    fun m3uSkipsCommentsAndResolvesRelativePaths() {
        val text = "﻿#EXTM3U\r\n# comment\r\nFF7 (Disc 1).chd\r\n\r\n./FF7 (Disc 2).chd\n.hidden\\FF7 (Disc 3).chd\n../other/x.chd\n"
        assertEquals(
            listOf("/roms/psx/FF7 (Disc 1).chd", "/roms/psx/FF7 (Disc 2).chd", "/roms/psx/.hidden/FF7 (Disc 3).chd", "/roms/other/x.chd"),
            Playlists.parseM3u(text, "/roms/psx"),
        )
    }

    @Test
    fun cueFileLinesQuotedAndUnquoted() {
        val cue = """
            FILE "Game (Track 01).bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE track02.bin BINARY
              TRACK 02 AUDIO
            file "Sub Dir/track03.wav" WAVE
        """.trimIndent()
        assertEquals(
            listOf("/r/Game (Track 01).bin", "/r/track02.bin", "/r/Sub Dir/track03.wav"),
            Playlists.parseCue(cue, "/r"),
        )
    }

    @Test
    fun gdiTracks() {
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0\n"
        assertEquals(listOf("/dc/track01.bin", "/dc/track 02.raw", "/dc/track03.bin"), Playlists.parseGdi(gdi, "/dc"))
    }

    @Test
    fun m3uWriterRendersRelativeOrAbsolutePaths() {
        val discs = listOf("/roms/psx/FF7 (Disc 1).chd", "/roms/psx/FF7 (Disc 2).chd")
        assertEquals("/roms/psx/FF7 (Disc 1).chd\n/roms/psx/FF7 (Disc 2).chd\n", M3uWriter.render(discs))
        assertEquals(
            "../../roms/psx/FF7 (Disc 1).chd\n../../roms/psx/FF7 (Disc 2).chd\n",
            M3uWriter.render(discs, relativeTo = "/cache/playlists"),
        )
        assertEquals("FF7 (Disc 1).chd\nFF7 (Disc 2).chd\n", M3uWriter.render(discs, relativeTo = "/roms/psx"))
    }

    @Test
    fun m3uWriterRoundTripsThroughTheParser() {
        val discs = listOf("/roms/psx/A (Disc 1).cue", "/roms/psx/A (Disc 2).cue")
        val text = M3uWriter.render(discs, relativeTo = "/cache")
        assertEquals(discs, Playlists.parseM3u(text, "/cache"))
    }

    @Test
    fun aSheetNamesAPatchedCopyInTheFolderOfWhatItNamed() {
        // A cue's first FILE line, quoted or not, keeping its folder, indent, type and line endings.
        val cue = "REM made by hand\r\n  FILE tracks\\game.bin BINARY\r\n    TRACK 01 MODE2/2352\r\nFILE \"game (Track 2).bin\" BINARY\r\n"
        assertEquals(
            "REM made by hand\r\n  FILE \"tracks\\game - Fix.bin\" BINARY\r\n    TRACK 01 MODE2/2352\r\nFILE \"game (Track 2).bin\" BINARY\r\n",
            Playlists.repointCue(cue, "game - Fix.bin"),
        )
        assertEquals(null, Playlists.repointCue("REM nothing\n", "x.bin"))
        // An .m3u's entry by its place among the discs, comments not counted.
        val m3u = "﻿#EXTM3U\nA (Disc 1).cue\n# second\ndiscs/A (Disc 2).cue\n"
        assertEquals("﻿#EXTM3U\nA (Disc 1).cue\n# second\ndiscs/A (Disc 2) - Fix.cue\n", Playlists.repointM3u(m3u, 1, "A (Disc 2) - Fix.cue"))
        assertEquals(null, Playlists.repointM3u(m3u, 2, "x.cue"))
    }

    @Test
    fun m3uWriterRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { M3uWriter.render(emptyList()) }
        assertFailsWith<IllegalArgumentException> { M3uWriter.render(listOf("/a\n/b")) }
    }
}
