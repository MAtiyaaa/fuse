package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.UploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class UploadFilesTest {
    @Test
    fun aFolderGameBringsEverythingWithItsFoldersAndTheLaunchFileFirst() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/switch/Zelda/Zelda.nsp", size = 900)
            .file("/roms/switch/Zelda/.DS_Store", size = 4)
            .file("/roms/switch/Zelda/dlc/Master Trials.nsp", size = 50)
            .file("/roms/switch/Zelda/dlc/._Master Trials.nsp", size = 4)
            .file("/roms/switch/Zelda/update/1.6.0.nsp", size = 70)
            .file("/roms/switch/Zelda/.hidden/x.bin", size = 1)
        val loc = GameLocation(
            LibrarySourceId(1), "/roms/switch/Zelda", LocationKind.FOLDER, "/roms/switch/Zelda/Zelda.nsp",
            interpretation = FolderInterpretation.MULTI_FILE_GAME,
        )
        val files = UploadFiles.collect(fs, loc, emptyList())
        assertEquals(
            listOf(
                UploadFile("/roms/switch/Zelda/Zelda.nsp", "Zelda.nsp", "", 900),
                UploadFile("/roms/switch/Zelda/dlc/Master Trials.nsp", "Master Trials.nsp", "dlc", 50),
                UploadFile("/roms/switch/Zelda/update/1.6.0.nsp", "1.6.0.nsp", "update", 70),
            ),
            files,
        )
    }

    @Test
    fun aDiscSetStartsWithItsPlaylistAndKeepsEveryDisc() = runTest {
        val fs = InMemoryFileSystem()
            .file("/roms/psx/Pepsiman.m3u", content = "Pepsiman (Disc 1).chd\nPepsiman (Disc 2).chd\n")
            .file("/roms/psx/Pepsiman (Disc 1).chd", size = 500)
            .file("/roms/psx/Pepsiman (Disc 2).chd", size = 510)
            .file("/roms/psx/Pepsiman.srm", size = 8)
        val loc = GameLocation(LibrarySourceId(1), "/roms/psx/Pepsiman.m3u", LocationKind.FILE, "/roms/psx/Pepsiman.m3u", interpretation = FolderInterpretation.MULTI_DISC)
        val files = UploadFiles.collect(
            fs, loc,
            listOf(Disc(1, "Disc 1", "/roms/psx/Pepsiman (Disc 1).chd"), Disc(2, "Disc 2", "/roms/psx/Pepsiman (Disc 2).chd")),
        )
        assertEquals(listOf("Pepsiman.m3u", "Pepsiman (Disc 1).chd", "Pepsiman (Disc 2).chd"), files.map { it.name })
        assertTrue(files.all { it.folder.isEmpty() })
    }

    @Test
    fun aMissingGameHasNothingToUpload() = runTest {
        val loc = GameLocation(LibrarySourceId(1), "/roms/gba/Gone.gba", LocationKind.FILE, "/roms/gba/Gone.gba")
        assertTrue(UploadFiles.collect(InMemoryFileSystem(), loc, emptyList()).isEmpty())
    }
}
