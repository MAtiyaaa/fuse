package io.github.matiyaaa.fuse.library.bios

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.BiosFile
import io.github.matiyaaa.fuse.model.BiosRequirement
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiosCheckerTest {
    private val fs = InMemoryFileSystem()
    private val checker = BiosChecker(fs)
    private fun platform(id: String): Platform = PlatformCatalog.byId(id)!!

    private val scph5501Md5 = "490f666e1afb15b7362b406ed1cea246"

    @Test
    fun notRequiredWithoutBios() = runTest {
        assertEquals(BiosState.NOT_REQUIRED, checker.check(platform("snes"), listOf("/BIOS")).state)
    }

    @Test
    fun readyWhenAnyOneVerifiedPsxBiosIsPresent() = runTest {
        fs.file("/BIOS/SCPH5501.BIN", size = 524_288, md5 = scph5501Md5)
        val status = checker.check(platform("psx"), listOf("/BIOS"))
        assertEquals(BiosState.READY, status.state)
        assertEquals(listOf("/BIOS/SCPH5501.BIN"), status.found)
        assertTrue(status.missing.isEmpty())
        assertEquals(listOf("/BIOS"), status.searched)
    }

    @Test
    fun wrongChecksumIsNotAccepted() = runTest {
        fs.file("/BIOS/scph5501.bin", size = 524_288, md5 = "00000000000000000000000000000000")
        val status = checker.check(platform("psx"), listOf("/BIOS"))
        assertEquals(BiosState.MISSING, status.state)
        assertTrue(status.note!!.contains("unexpected checksum"))
    }

    @Test
    fun unverifiableChecksumFallsBackToTheName() = runTest {
        fs.md5Supported = false
        fs.file("/BIOS/scph1001.bin", size = 524_288)
        val status = checker.check(platform("psx"), listOf("/BIOS"))
        assertEquals(BiosState.READY, status.state)
        assertTrue(status.note!!.contains("could not be verified"))
    }

    @Test
    fun missingOnlyWhenEveryLocationWasReadable() = runTest {
        fs.dir("/BIOS")
        fs.dir("/RetroArch/system")
        val status = checker.check(platform("psx"), listOf("/BIOS", "/RetroArch/system", "/does/not/exist"))
        assertEquals(BiosState.MISSING, status.state)
        assertEquals(listOf("/BIOS", "/RetroArch/system"), status.searched)
        assertTrue(status.missing.contains("scph5501.bin"))
    }

    @Test
    fun unknownWhenARelevantLocationIsUnreadable() = runTest {
        fs.dir("/BIOS")
        fs.file("/storage/Android/data/com.emu/files/bios/scph5501.bin")
        val known = checker.check(
            platform("psx"),
            listOf("/BIOS", "/storage/Android/data/com.emu/files/bios"),
            unreadable = setOf("/storage/Android/data"),
        )
        assertEquals(BiosState.UNKNOWN, known.state)
        assertTrue(known.note!!.contains("could not read"))
        assertTrue(known.searched.any { it.endsWith("(not readable)") })

        // The same when the file system itself refuses to list the folder.
        fs.file("/Locked/scph5501.bin")
        fs.makeUnreadable("/Locked")
        assertEquals(BiosState.UNKNOWN, checker.check(platform("psx"), listOf("/BIOS", "/Locked")).state)
    }

    @Test
    fun unknownWhenThereIsNothingToSearch() = runTest {
        val status = checker.check(platform("psx"), listOf("/nope"))
        assertEquals(BiosState.UNKNOWN, status.state)
        assertTrue(status.searched.isEmpty())
    }

    @Test
    fun partialWhenSomeRequiredFilesArePresent() = runTest {
        fs.file("/BIOS/mcpx_1.0.bin")
        val status = checker.check(platform("xbox"), listOf("/BIOS"))
        assertEquals(BiosState.PARTIAL, status.state)
        assertEquals(listOf("Complex_4627.bin"), status.missing)
        fs.file("/BIOS/xbox/Complex_4627.bin")
        assertEquals(BiosState.READY, checker.check(platform("xbox"), listOf("/BIOS")).state)
    }

    @Test
    fun installedInEmulatorIsAlwaysUnknownWithAHint() = runTest {
        fs.file("/BIOS/PS3UPDAT.PUP", size = 200_000_000)
        val ps3 = checker.check(platform("ps3"), listOf("/BIOS"))
        assertEquals(BiosState.UNKNOWN, ps3.state)
        assertTrue(ps3.note!!.contains("PS3UPDAT.PUP"))
        assertEquals(listOf("/BIOS/PS3UPDAT.PUP"), ps3.found)
        assertEquals(BiosState.UNKNOWN, checker.check(platform("switch"), listOf("/BIOS")).state)
        // Optional and installed in the emulator (3DS keys): nothing to require.
        assertEquals(BiosState.NOT_REQUIRED, checker.check(platform("3ds"), listOf("/BIOS")).state)
    }

    @Test
    fun optionalFirmwareIsNeverMissing() = runTest {
        fs.dir("/BIOS")
        val none = checker.check(platform("dc"), listOf("/BIOS"))
        assertEquals(BiosState.NOT_REQUIRED, none.state)
        assertTrue(none.note!!.startsWith("Optional"))
        fs.file("/BIOS/dc/dc_boot.bin")
        assertEquals(BiosState.PARTIAL, checker.check(platform("dc"), listOf("/BIOS")).state)
        fs.file("/BIOS/dc/dc_flash.bin")
        assertEquals(BiosState.READY, checker.check(platform("dc"), listOf("/BIOS")).state)
    }

    @Test
    fun ps2GlobAndMinimumSize() = runTest {
        fs.file("/BIOS/scph39001.bin", size = 1024)
        val tooSmall = checker.check(platform("ps2"), listOf("/BIOS"))
        assertEquals(BiosState.MISSING, tooSmall.state)
        assertTrue(tooSmall.note!!.contains("too small"))
        fs.file("/BIOS/pcsx2/bios/SCPH-70012.BIN", size = 4_194_304)
        assertEquals(BiosState.READY, checker.check(platform("ps2"), listOf("/BIOS")).state)
    }

    @Test
    fun aliasesMatch() = runTest {
        fs.file("/BIOS/us_scd1_9210.bin")
        assertEquals(BiosState.READY, checker.check(platform("segacd"), listOf("/BIOS")).state)
    }

    @Test
    fun searchDepthIsLimited() = runTest {
        fs.file("/BIOS/a/b/c/disksys.rom")
        assertEquals(BiosState.MISSING, BiosChecker(fs, searchDepth = 2).check(platform("fds"), listOf("/BIOS")).state)
        assertEquals(BiosState.READY, BiosChecker(fs, searchDepth = 3).check(platform("fds"), listOf("/BIOS")).state)
    }

    @Test
    fun customRequirementCountsDistinctFiles() = runTest {
        val custom = platform("snes").copy(
            bios = BiosRequirement(
                label = "Test",
                files = listOf(BiosFile("a.bin"), BiosFile("b.bin"), BiosFile("c.bin")),
                requiredCount = 2,
                hint = "Two of three",
            ),
        )
        fs.file("/BIOS/a.bin")
        assertEquals(BiosState.PARTIAL, checker.check(custom, listOf("/BIOS")).state)
        fs.file("/BIOS/c.bin")
        assertEquals(BiosState.READY, checker.check(custom, listOf("/BIOS")).state)
    }

    @Test
    fun searchPathsCoverRommStructuresAndConfiguredRoots() = runTest {
        val sources = listOf(
            LibrarySource(LibrarySourceId(1), "/lib", "RomM", LibrarySourceKind.ROMM_LIBRARY),
            LibrarySource(LibrarySourceId(2), "/storage/roms", "Roms", LibrarySourceKind.ROMS_ROOT),
        )
        val paths = BiosSearchPaths.forPlatform(
            platform("psx"),
            biosRoots = listOf("/storage/BIOS/"),
            librarySources = sources,
            emulatorFolders = listOf("/storage/Android/data/com.github.stenzek.duckstation/files/bios"),
        )
        assertTrue("/storage/BIOS" in paths)
        assertTrue("/lib/bios/psx" in paths)
        assertTrue("/lib/psx/bios" in paths)
        assertTrue("/storage/bios/psx" in paths)
        assertTrue("/storage/Android/data/com.github.stenzek.duckstation/files/bios" in paths)
        assertEquals(paths.size, paths.toSet().size)

        fs.file("/lib/psx/bios/scph5501.bin", md5 = scph5501Md5)
        assertEquals(BiosState.READY, checker.check(platform("psx"), paths).state)
    }
}
