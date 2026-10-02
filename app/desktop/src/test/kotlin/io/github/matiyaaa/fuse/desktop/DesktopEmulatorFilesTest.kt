package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopEmulatorFiles
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopEmulatorFilesTest {
    private val home = Files.createTempDirectory("fuse-home").toFile()
    private val backups = File(home, "fuse-backups")

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun pcsx2(appId: String, via: String = "PATH") =
        InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, appId, platforms = setOf(PlatformId("ps2")), detectedVia = via)

    @Test
    fun linuxFindsItsConfigFolderAndFollowsMovedFolders() = runBlocking {
        val data = File(home, ".config/PCSX2").apply { File(this, "inis").mkdirs() }
        File(data, "inis/PCSX2.ini").writeText("[Folders]\nPatches = /srv/my-patches\n")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val found = files.pcsx2(pcsx2("/usr/bin/pcsx2-qt"))!!
        assertEquals(data.absolutePath, found.dataRoot)
        assertEquals("/srv/my-patches", found.patches)
        assertEquals("${data.absolutePath}/gamesettings", found.gameSettings)
    }

    @Test
    fun theFlatpakKeepsItsOwnAndASetUpIsNeeded() = runBlocking {
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertNull(files.pcsx2(pcsx2("net.pcsx2.PCSX2", via = "Flatpak")), "not set up yet")
        val data = File(home, ".var/app/net.pcsx2.PCSX2/config/PCSX2").apply { File(this, "inis").mkdirs() }
        File(data, "inis/PCSX2.ini").writeText("")
        assertEquals(data.absolutePath, files.pcsx2(pcsx2("net.pcsx2.PCSX2", via = "Flatpak"))!!.dataRoot)
    }

    @Test
    fun portableModeUsesTheProgramsFolderAndItsBundledPatches() = runBlocking {
        val app = File(home, "Emu/PCSX2").apply { File(this, "inis").mkdirs(); File(this, "resources").mkdirs() }
        File(app, "portable.ini").writeText("")
        File(app, "inis/PCSX2.ini").writeText("")
        File(app, "resources/patches.zip").writeBytes(ByteArray(4))
        val files = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = mapOf("USERPROFILE" to home.absolutePath), home = home.absolutePath, backups = backups)
        val found = files.pcsx2(pcsx2(File(app, "pcsx2-qt.exe").absolutePath))!!
        assertEquals(app.absolutePath, found.dataRoot)
        assertEquals(File(app, "resources/patches.zip").absolutePath, found.patchesZip)
    }

    @Test
    fun writesStayInsidePcsx2sFolderAndTheFirstChangeKeepsACopy() = runBlocking {
        val data = File(home, ".config/PCSX2").apply { File(this, "inis").mkdirs(); File(this, "gamesettings").mkdirs() }
        File(data, "inis/PCSX2.ini").writeText("")
        val settings = File(data, "gamesettings/SLUS-20946_0C040404.ini").apply { writeText("[Patches]\nEnable = Mine\n") }
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        files.pcsx2(pcsx2("/usr/bin/pcsx2-qt"))
        assertFalse(files.write(File(home, "elsewhere.ini").absolutePath, "x"))
        assertTrue(files.write(settings.absolutePath, "[Patches]\nEnable = Mine\nEnable = 60 FPS\n"))
        assertTrue(files.write(settings.absolutePath, "[Patches]\nEnable = Mine\n"))
        assertEquals("[Patches]\nEnable = Mine\n", File(backups, settings.name).readText())
    }
}
