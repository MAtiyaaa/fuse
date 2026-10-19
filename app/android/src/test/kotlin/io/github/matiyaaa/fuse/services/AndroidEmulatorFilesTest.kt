package io.github.matiyaaa.fuse.services

import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.sync.FileSaveEnvironment
import io.github.matiyaaa.fuse.sync.SaveEnvironment
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emulators' files Emulator Settings reaches on Android: those in shared storage, and nothing outside them. */
class AndroidEmulatorFilesTest {
    private val storage = Files.createTempDirectory("fuse-storage").toFile()
    private val backups = Files.createTempDirectory("fuse-backups").toFile()

    /** The device's storage, here a folder of the test's (new each time: a search that found nothing is remembered a while). */
    private fun env(): SaveEnvironment = object : SaveEnvironment by FileSaveEnvironment("ANDROID") {
        override fun storageRoots() = listOf(storage.absolutePath)
    }

    private fun files() = AndroidEmulatorFiles(env(), backups)

    private fun installed(id: String) = InstalledEmulator(EmulatorId(id), id, Host.ANDROID, "org.example.$id", platforms = emptySet(), detectedVia = "Package")

    @After
    fun tearDown() {
        storage.deleteRecursively()
        backups.deleteRecursively()
    }

    @Test
    fun ppssppsMemoryStickIsFoundAndOnlyItIsWritten() = runBlocking {
        assertNull("not until PPSSPP has written its settings", files().ppsspp(installed("ppsspp")))
        val system = File(storage, "PSP/SYSTEM").apply { mkdirs() }
        File(system, "ppsspp.ini").writeText("[Graphics]\nInternalResolution = 1\n")
        val files = files()
        val home = files.ppsspp(installed("ppsspp"))!!
        assertEquals(storage.absolutePath, home.memstick)
        assertTrue(files.write(home.gameSettingsFile("ULUS10041"), "[Graphics]\nInternalResolution = 2\n"))
        assertEquals("[Graphics]\nInternalResolution = 2\n", File(system, "ULUS10041_ppsspp.ini").readText())
        // Changed in place, the file as it was kept first.
        assertTrue(files.write(home.settingsFile, "[Graphics]\nInternalResolution = 3\n"))
        assertEquals("[Graphics]\nInternalResolution = 1\n", File(backups, io.github.matiyaaa.fuse.ui.shell.store.EmulatorFileCopies.below(File(system, "ppsspp.ini").absolutePath)).readText())
        assertFalse("nothing outside its folders", files.write(File(storage, "Download/x.ini").absolutePath, "x"))
    }

    @Test
    fun armsx2sDataFolderIsReadAsPcsx2sWithItsOwnFolders() = runBlocking {
        val data = File(storage, "Emulation/ARMSX2").apply { File(this, "memcards").mkdirs() }
        File(data, "memcards/Mcd001.ps2").writeBytes(ByteArray(16))
        assertNull("not until it has its settings", files().pcsx2(installed("armsx2")))
        File(data, "inis").mkdirs()
        File(data, "inis/PCSX2.ini").writeText("[Folders]\nCheats = /sdcard/ps2/cheats\n")
        val files = files()
        val home = files.pcsx2(installed("armsx2"))!!
        assertEquals(data.absolutePath, home.dataRoot)
        assertEquals(File(data, "gamesettings").absolutePath, home.gameSettings)
        assertEquals("/sdcard/ps2/cheats", home.cheats)
        assertTrue(files.write("${home.gameSettings}/SLUS-20946_ABCD1234.ini", "[EmuCore/GS]\nupscale_multiplier = 2\n"))
    }

    @Test
    fun foldersArmsx2sOwnSettingsMovedAreWrittenToo() = runBlocking {
        val data = File(storage, "Emulation/ARMSX2").apply { File(this, "inis").mkdirs(); File(this, "memcards").mkdirs() }
        File(data, "memcards/Mcd001.ps2").writeBytes(ByteArray(16))
        val card = File(storage, "sdcard-ps2/cheats").apply { mkdirs() }
        File(data, "inis/PCSX2.ini").writeText("[Folders]\nCheats = ${card.absolutePath}\n")
        val files = files()
        val home = files.pcsx2(installed("armsx2"))!!
        assertEquals(card.absolutePath, home.cheats)
        assertTrue("its cheats folder, where its settings put it", files.write("${home.cheats}/SLUS-20946_ABCD1234.pnach", "patch=1,EE,0,word,0\n"))
        assertFalse("but nothing beside it", files.write(File(storage, "sdcard-ps2/other.ini").absolutePath, "x"))
    }

    @Test
    fun retroArchsOwnFolderIsUsedWhenItIsInSharedStorage() = runBlocking {
        assertNull("its private folder can't be reached", files().retroArch(installed("retroarch")))
        val ra = File(storage, "RetroArch").apply { mkdirs() }
        File(ra, "retroarch.cfg").writeText("video_vsync = \"true\"\n")
        val files = files()
        val home = files.retroArch(installed("retroarch"))!!
        assertEquals(File(ra, "retroarch.cfg").absolutePath, home.config)
        assertEquals(File(ra, "config").absolutePath, home.configDir)
        assertTrue(files.write("${home.configDir}/mGBA/Golden Sun.cfg", "video_scale_integer = \"true\"\n"))
        assertTrue(files.remove("${home.configDir}/mGBA/Golden Sun.cfg"))

        // Its global settings: retroarch.cfg itself, beside its folders, kept as it was first.
        assertTrue("RetroArch's own settings", files.write(home.config!!, "video_vsync = \"false\"\n"))
        assertEquals("video_vsync = \"false\"\n", File(ra, "retroarch.cfg").readText())
        assertEquals("video_vsync = \"true\"\n", File(backups, io.github.matiyaaa.fuse.ui.shell.store.EmulatorFileCopies.below(File(ra, "retroarch.cfg").absolutePath)).readText())
        assertFalse("never removed", files.remove(home.config!!))
        assertTrue(File(ra, "retroarch.cfg").isFile)
        assertFalse("nothing else beside it", files.write(File(ra, "retroarch-core-options.cfg").absolutePath, "x"))
    }

    @Test
    fun twoFilesOfOneNameEachKeepTheirOwnCopy() = runBlocking {
        val ra = File(storage, "RetroArch").apply { mkdirs() }
        File(ra, "retroarch.cfg").writeText("video_vsync = \"true\"\n")
        val files = files()
        val home = files.retroArch(installed("retroarch"))!!
        val mgba = File(home.configDir, "mGBA/Golden Sun.cfg").apply { parentFile.mkdirs(); writeText("mgba's\n") }
        val vba = File(home.configDir, "VBA-M/Golden Sun.cfg").apply { parentFile.mkdirs(); writeText("vba-m's\n") }
        assertTrue(files.write(mgba.absolutePath, "changed\n"))
        assertTrue(files.write(vba.absolutePath, "changed\n"))
        fun copy(f: File) = File(backups, io.github.matiyaaa.fuse.ui.shell.store.EmulatorFileCopies.below(f.absolutePath))
        assertEquals("mgba's\n", copy(mgba).readText())
        assertEquals("vba-m's\n", copy(vba).readText())
        // The copy is of the file as it was before Fuse first changed it: a later change keeps it.
        assertTrue(files.write(mgba.absolutePath, "again\n"))
        assertEquals("mgba's\n", copy(mgba).readText())
    }
}
