package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GameFilesTest {
    private fun file(path: String, interpretation: FolderInterpretation = FolderInterpretation.SINGLE_FILE) =
        GameLocation(LibrarySourceId(1), path, LocationKind.FILE, path, interpretation = interpretation)

    @Test
    fun aCueOwnsItsTracksButNotTheSaveNextToIt() = runTest {
        val cue = "FILE \"Game (Track 1).bin\" BINARY\nFILE \"Game (Track 2).bin\" BINARY\n"
        val fs = InMemoryFileSystem()
            .file("/roms/psx/Game.cue", content = cue)
            .file("/roms/psx/Game (Track 1).bin", size = 600)
            .file("/roms/psx/Game (Track 2).bin", size = 40)
            .file("/roms/psx/Game.srm", size = 8)
        val set = GameFiles.resolve(fs, file("/roms/psx/Game.cue"), emptyList())
        assertEquals(listOf("/roms/psx/Game.cue", "/roms/psx/Game (Track 1).bin", "/roms/psx/Game (Track 2).bin"), set.paths)
        assertEquals(3, set.fileCount)
        assertEquals(cue.length + 640L, set.sizeBytes)
    }

    @Test
    fun aDiscSetOwnsEveryDiscAndItsPlaylist() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/psx/FF7.m3u", content = "FF7 (Disc 1).chd\nFF7 (Disc 2).chd\n")
            .file("/roms/psx/FF7 (Disc 1).chd", size = 500)
            .file("/roms/psx/FF7 (Disc 2).chd", size = 510)
        val set = GameFiles.resolve(
            fs,
            file("/roms/psx/FF7.m3u", FolderInterpretation.MULTI_DISC),
            listOf(Disc(1, "Disc 1", "/roms/psx/FF7 (Disc 1).chd"), Disc(2, "Disc 2", "/roms/psx/FF7 (Disc 2).chd")),
        )
        assertEquals(3, set.paths.size)
        assertEquals(true, set.sizeBytes >= 1010)
    }

    @Test
    fun aFolderGameIsItsFolderWithEverythingInside() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/ps3/Game/PS3_GAME/USRDIR/EBOOT.BIN", size = 100)
            .file("/roms/ps3/Game/PS3_DISC.SFB", size = 1)
        val loc = GameLocation(LibrarySourceId(1), "/roms/ps3/Game", LocationKind.FOLDER, "/roms/ps3/Game", interpretation = FolderInterpretation.FOLDER_IS_GAME)
        val set = GameFiles.resolve(fs, loc, emptyList())
        assertEquals(listOf("/roms/ps3/Game"), set.paths)
        assertEquals(2, set.fileCount)
        assertEquals(101L, set.sizeBytes)
    }

    @Test
    fun missingFilesAreLeftOut() = runTest {
        val fs = InMemoryFileSystem().file("/roms/sat/Game.cue", content = "FILE \"Gone.bin\" BINARY\n")
        val set = GameFiles.resolve(fs, file("/roms/sat/Game.cue"), emptyList())
        assertEquals(listOf("/roms/sat/Game.cue"), set.paths)
        assertEquals(true, GameFiles.resolve(fs, file("/roms/sat/Nothing.iso"), emptyList()).isEmpty)
    }
}
