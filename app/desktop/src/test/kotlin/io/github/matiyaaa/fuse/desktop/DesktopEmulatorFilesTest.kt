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

    /** Paths as Fuse reports them on every system: forward slashes. */
    private fun p(f: File) = f.absolutePath.replace('\\', '/')

    private fun pcsx2(appId: String, via: String = "PATH") =
        InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, appId, platforms = setOf(PlatformId("ps2")), detectedVia = via)

    @Test
    fun linuxFindsItsConfigFolderAndFollowsMovedFolders() = runBlocking {
        val data = File(home, ".config/PCSX2").apply { File(this, "inis").mkdirs() }
        // An absolute path on this system, wherever the tests run.
        val moved = File(home, "srv/my-patches")
        File(data, "inis/PCSX2.ini").writeText("[Folders]\nPatches = ${p(moved)}\n")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val found = files.pcsx2(pcsx2("/usr/bin/pcsx2-qt"))!!
        assertEquals(p(data), found.dataRoot)
        assertEquals(p(moved), found.patches)
        assertEquals("${p(data)}/gamesettings", found.gameSettings)
    }

    @Test
    fun theFlatpakKeepsItsOwnAndASetUpIsNeeded() = runBlocking {
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertNull(files.pcsx2(pcsx2("net.pcsx2.PCSX2", via = "Flatpak")), "not set up yet")
        val data = File(home, ".var/app/net.pcsx2.PCSX2/config/PCSX2").apply { File(this, "inis").mkdirs() }
        File(data, "inis/PCSX2.ini").writeText("")
        assertEquals(p(data), files.pcsx2(pcsx2("net.pcsx2.PCSX2", via = "Flatpak"))!!.dataRoot)
    }

    @Test
    fun portableModeUsesTheProgramsFolderAndItsBundledPatches() = runBlocking {
        val app = File(home, "Emu/PCSX2").apply { File(this, "inis").mkdirs(); File(this, "resources").mkdirs() }
        File(app, "portable.ini").writeText("")
        File(app, "inis/PCSX2.ini").writeText("")
        File(app, "resources/patches.zip").writeBytes(ByteArray(4))
        val files = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = mapOf("USERPROFILE" to home.absolutePath), home = home.absolutePath, backups = backups)
        val found = files.pcsx2(pcsx2(File(app, "pcsx2-qt.exe").absolutePath))!!
        assertEquals(p(app), found.dataRoot)
        assertEquals(p(File(app, "resources/patches.zip")), found.patchesZip)
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

    @Test
    fun rpcs3StorageFollowsVfsYml() = runBlocking {
        val config = File(home, ".config/rpcs3").apply { File(this, "config").mkdirs() }
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val rpcs3 = InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH")
        assertEquals(listOf("${p(config)}/dev_hdd0"), files.rpcs3Storage(rpcs3), "next to its config by default")
        File(config, "config/vfs.yml").writeText("\$(EmulatorDir): \"\"\n/dev_hdd0/: /run/media/me/Games/ps3hdd/\n/dev_hdd1/: \$(EmulatorDir)dev_hdd1/\n")
        assertEquals(listOf("/run/media/me/Games/ps3hdd"), files.rpcs3Storage(rpcs3), "moved to another drive")
        File(config, "config/vfs.yml").writeText("/dev_hdd0/: \$(EmulatorDir)hdd0/\n")
        assertEquals(listOf("${p(config)}/hdd0"), files.rpcs3Storage(rpcs3))
    }

    @Test
    fun vita3kStorageFollowsItsPrefPath() = runBlocking {
        val data = File(home, ".local/share/Vita3K/Vita3K").apply { mkdirs() }
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val vita = InstalledEmulator(EmulatorId("linux.vita3k"), "Vita3K", Host.LINUX, "/usr/bin/Vita3K", platforms = setOf(PlatformId("psvita")), detectedVia = "PATH")
        assertTrue(p(data) in files.vita3kStorage(vita))
        File(data, "config.yml").writeText("backend-renderer: Vulkan\npref-path: /media/sd/vita3k\n")
        assertEquals("/media/sd/vita3k", files.vita3kStorage(vita).first(), "its own setting comes first")
    }

    @Test
    fun anInstallerIsWaitedForAndStoppedWhenItSaysItIsDone() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val lines = mutableListOf<String>()
        val done = files.runInstaller(io.github.matiyaaa.fuse.ui.shell.store.InstallerRun(listOf("sh", "-c", "echo one; echo two; exit 3"))) { lines += it }
        assertEquals(3, done.exitCode)
        assertEquals(listOf("one", "two"), lines)
        val started = System.currentTimeMillis()
        val stopped = files.runInstaller(
            io.github.matiyaaa.fuse.ui.shell.store.InstallerRun(listOf("sh", "-c", "echo Content installed, will auto-boot: PCSA00001; sleep 30"), stopWhen = Regex("will auto-boot")),
        )
        assertNull(stopped.exitCode, "Fuse stopped it")
        assertTrue(System.currentTimeMillis() - started < 10_000)
        val missing = files.runInstaller(io.github.matiyaaa.fuse.ui.shell.store.InstallerRun(listOf("/nowhere/rpcs3")))
        assertFalse(missing.started)
    }

    @Test
    fun aLicenceIsStagedUnderItsNameAndClearedAfter() = runBlocking {
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val source = File(home, "key.rap").apply { writeBytes(ByteArray(16) { 1 }) }
        val staged = files.stage(source.absolutePath, "UP0700-BLUS30443_00-DEMONSSOULS00000.rap")!!
        assertTrue(staged.endsWith("/UP0700-BLUS30443_00-DEMONSSOULS00000.rap"))
        assertTrue(File(staged).isFile)
        files.clearStaged()
        assertFalse(File(staged).exists())
        assertTrue(source.isFile, "the original stays")
    }
}
