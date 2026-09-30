package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscGrouperTest {
    private val fs = InMemoryFileSystem()
    private val grouper = DiscGrouper(fs)

    private suspend fun group(dir: String, exts: Set<String>): DiscGrouping =
        grouper.group(dir, fs.list(dir)) { e: FsEntry -> e.extension in exts }

    private val psx = setOf("bin", "cue", "chd", "iso", "img", "m3u", "pbp", "ccd", "mds", "mdf")

    @Test
    fun groupsDiscSiblingsInOrder() = runTest {
        fs.file("/psx/Final Fantasy VII (Europe) (Disc 3).chd", size = 300)
        fs.file("/psx/Final Fantasy VII (Europe) (Disc 1).chd", size = 100)
        fs.file("/psx/Final Fantasy VII (Europe) (Disc 2).chd", size = 200)
        fs.file("/psx/Crash Bandicoot (USA).chd")

        val groups = group("/psx", psx).groups
        assertEquals(2, groups.size)
        val ff7 = groups.single { it.kind == GroupKind.MULTI_DISC }
        assertEquals("Final Fantasy VII (Europe)", ff7.title)
        assertEquals(listOf(1, 2, 3), ff7.discs.map { it.number })
        assertTrue(ff7.discs[0].path.endsWith("(Disc 1).chd"))
        assertEquals(ff7.discs[0].path, ff7.primary.path)
        assertEquals(600, ff7.sizeBytes)
        assertEquals(GroupKind.SINGLE, groups.single { it.title.startsWith("Crash") }.kind)
    }

    @Test
    fun differentReleasesAreNotMerged() = runTest {
        fs.file("/psx/Game (USA) (Disc 1).chd")
        fs.file("/psx/Game (USA) (Disc 2).chd")
        fs.file("/psx/Game (Europe) (Disc 1).chd")
        fs.file("/psx/Game (Europe) (Disc 2).chd")
        val groups = group("/psx", psx).groups
        assertEquals(listOf("Game (Europe)", "Game (USA)"), groups.map { it.title })
        assertTrue(groups.all { it.discs.size == 2 })
    }

    @Test
    fun cdAndDashAndLetterMarkers() = runTest {
        fs.file("/x/Game (CD1).cue", content = "")
        fs.file("/x/Game (CD2).cue", content = "")
        fs.file("/x/Other - Disc 1.iso")
        fs.file("/x/Other - Disc 2.iso")
        fs.file("/amiga/Monkey Island (Disk B).adf")
        fs.file("/amiga/Monkey Island (Disk A).adf")
        val x = group("/x", psx).groups
        assertEquals(listOf("Game", "Other"), x.map { it.title })
        assertTrue(x.all { it.kind == GroupKind.MULTI_DISC && it.discs.size == 2 })
        val amiga = group("/amiga", setOf("adf")).groups.single()
        assertEquals("Monkey Island", amiga.title)
        assertTrue(amiga.discs.first().path.endsWith("(Disk A).adf"))
    }

    @Test
    fun m3uOwnsItsDiscsAndHidesThem() = runTest {
        fs.file("/psx/Final Fantasy VII.m3u", content = "#EXTM3U\nFinal Fantasy VII (Disc 1).cue\nFinal Fantasy VII (Disc 2).cue\n")
        fs.file("/psx/Final Fantasy VII (Disc 1).cue", content = "FILE \"Final Fantasy VII (Disc 1).bin\" BINARY\n")
        fs.file("/psx/Final Fantasy VII (Disc 1).bin", size = 700)
        fs.file("/psx/Final Fantasy VII (Disc 2).cue", content = "FILE \"Final Fantasy VII (Disc 2).bin\" BINARY\n")
        fs.file("/psx/Final Fantasy VII (Disc 2).bin", size = 700)

        val grouping = group("/psx", psx)
        val game = grouping.groups.single()
        assertEquals(GroupKind.PLAYLIST, game.kind)
        assertEquals("Final Fantasy VII", game.title)
        assertTrue(game.primary.path.endsWith(".m3u"))
        assertEquals(listOf("Disc 1", "Disc 2"), game.discs.map { it.label })
        assertTrue(grouping.hidden.contains("/psx/Final Fantasy VII (Disc 1).bin"))
        assertTrue(grouping.hidden.contains("/psx/Final Fantasy VII (Disc 2).cue"))
        assertTrue(game.sizeBytes >= 1400)
    }

    @Test
    fun cueHidesItsBinTracks() = runTest {
        fs.file(
            "/psx/Tomb Raider (USA).cue",
            content = "FILE \"Tomb Raider (USA) (Track 1).bin\" BINARY\nFILE \"Tomb Raider (USA) (Track 2).bin\" BINARY\n",
        )
        fs.file("/psx/Tomb Raider (USA) (Track 1).bin", size = 500)
        fs.file("/psx/Tomb Raider (USA) (Track 2).bin", size = 50)
        fs.file("/psx/Loose Game (USA).bin", size = 10)

        val groups = group("/psx", psx).groups
        assertEquals(listOf("Loose Game (USA)", "Tomb Raider (USA)"), groups.map { it.title })
        assertEquals(550 + fs.stat("/psx/Tomb Raider (USA).cue")!!.sizeBytes, groups.last().sizeBytes)
    }

    @Test
    fun gdiCcdAndMdsCompanionsAreHidden() = runTest {
        fs.file("/dc/Shenmue.gdi", content = "2\n1 0 4 2352 track01.bin 0\n2 600 0 2352 track02.raw 0\n")
        fs.file("/dc/track01.bin")
        fs.file("/dc/track02.raw")
        val dc = group("/dc", setOf("gdi", "bin", "raw", "cue", "chd"))
        assertEquals(listOf("Shenmue"), dc.groups.map { it.title })

        fs.file("/psx/Game.ccd", content = "[CloneCD]")
        fs.file("/psx/Game.img")
        fs.file("/psx/Game.sub")
        fs.file("/psx/Other.mds")
        fs.file("/psx/Other.mdf")
        val p = group("/psx", psx + "sub")
        assertEquals(listOf("Game", "Other"), p.groups.map { it.title })
        assertEquals(setOf("ccd", "mds"), p.groups.map { it.primary.extension }.toSet())
    }

    @Test
    fun singleDiscMarkerStaysASingleGame() = runTest {
        fs.file("/psx/Lonely (Disc 1).chd")
        val g = group("/psx", psx).groups.single()
        assertEquals(GroupKind.SINGLE, g.kind)
        assertEquals("Lonely (Disc 1)", g.title)
    }

    @Test
    fun ignoresNonGameAndIgnoredFiles() = runTest {
        fs.file("/gba/Game.gba")
        fs.file("/gba/Game.sav")
        fs.file("/gba/cover.png")
        fs.file("/gba/Game.gba.part")
        assertEquals(listOf("Game"), group("/gba", setOf("gba")).groups.map { it.title })
    }
}
