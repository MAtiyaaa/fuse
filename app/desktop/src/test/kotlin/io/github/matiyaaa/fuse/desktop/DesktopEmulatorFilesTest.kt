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
    fun aFileHoldingASignInIsWrittenTheSameWayWithNoCopyKept() = runBlocking {
        // RPCS3's rpcn.yml: the stretched password and token are RPCS3's alone, never copied into Fuse's folder.
        val config = File(home, ".config/rpcs3").apply { mkdirs() }
        File(config, "config.yml").writeText("")
        val rpcn = File(config, "rpcn.yml").apply { writeText("NPID: Old_One\nPassword: ABCD\nToken: T0K3N\n") }
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val rpcs3 = InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH")
        files.rpcs3Config(rpcs3)
        assertTrue(files.writePrivate(rpcn.absolutePath, "NPID: Ana_99\nPassword: E4F6\nToken: NEW\n"))
        assertEquals("NPID: Ana_99\nPassword: E4F6\nToken: NEW\n", rpcn.readText())
        assertFalse(File(backups, "rpcn.yml").exists())
        assertFalse(files.writePrivate(File(home, "elsewhere.yml").absolutePath, "x"), "only inside a known emulator folder")
    }

    @Test
    fun azaharsConfigFolderIsWhereItsOwnPathsPutIt() = runBlocking {
        val azahar = InstalledEmulator(EmulatorId("linux.azahar"), "Azahar", Host.LINUX, "/usr/bin/azahar", platforms = setOf(PlatformId("3ds")), detectedVia = "PATH")
        val linux = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val config = File(home, ".config/azahar-emu").apply { mkdirs() }
        assertNull(linux.azaharConfig(azahar), "not until Azahar has written its qt-config.ini")
        File(config, "qt-config.ini").writeText("[Core]\n")
        assertEquals(p(config), linux.azaharConfig(azahar))
        val flatpak = File(home, ".var/app/org.azahar_emu.Azahar/config/azahar-emu").apply { mkdirs(); File(this, "qt-config.ini").writeText("") }
        assertEquals(p(flatpak), linux.azaharConfig(azahar.copy(appId = "org.azahar_emu.Azahar", detectedVia = "Flatpak")))
        // macOS: its Application Support folder unless the XDG one was made first.
        val support = File(home, "Library/Application Support/Azahar/config").apply { mkdirs(); File(this, "qt-config.ini").writeText("") }
        val mac = DesktopEmulatorFiles(DesktopOs.MACOS, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertEquals(p(config), mac.azaharConfig(azahar))
        File(config, "qt-config.ini").delete()
        assertEquals(p(support), mac.azaharConfig(azahar))
    }

    @Test
    fun cemusFoldersAreWhereItsOwnPathsPutThemAndItsGameProfilesCanBeWritten() = runBlocking {
        val cemu = InstalledEmulator(EmulatorId("linux.cemu"), "Cemu", Host.LINUX, "/usr/bin/cemu", platforms = setOf(PlatformId("wiiu")), detectedVia = "PATH")
        val linux = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val data = File(home, ".local/share/Cemu").apply { mkdirs() }
        val config = File(home, ".config/Cemu").apply { mkdirs() }
        assertNull(linux.cemu(cemu), "not until Cemu has written its settings.xml")
        File(config, "settings.xml").writeText("<content/>")
        assertEquals(io.github.matiyaaa.fuse.launch.settings.CemuHome(p(data), p(config)), linux.cemu(cemu))
        // A game's own profile goes in its config folder, which Fuse may write once it found it.
        val profile = io.github.matiyaaa.fuse.launch.settings.CemuProfile.gameFile(p(config), "0005000010145D00")
        assertEquals("${p(config)}/gameProfiles/0005000010145d00.ini", profile)
        assertTrue(linux.write(profile, "# Mario Kart 8\n\n[CPU]\ncpuMode = Auto\n"))
        // A game folder's title id, from the meta.xml beside its code folder.
        val game = File(home, "wiiu/Mario Kart 8").apply { File(this, "code").mkdirs(); File(this, "meta").mkdirs() }
        File(game, "meta/meta.xml").writeText("<menu><title_id type=\"hexBinary\" length=\"8\">0005000010145D00</title_id></menu>")
        assertEquals("0005000010145D00", linux.wiiUTitleId(File(game, "code/Turbo.rpx").absolutePath, null))
    }

    @Test
    fun edensConfigFolderIsWhereItsOwnPathsPutIt() = runBlocking {
        val linux = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val eden = InstalledEmulator(EmulatorId("linux.eden"), "Eden", Host.LINUX, "/usr/bin/eden", platforms = setOf(PlatformId("switch")), detectedVia = "PATH")
        val config = File(home, ".config/eden").apply { mkdirs() }
        assertNull(linux.edenConfig(eden), "not until Eden has written its qt-config.ini")
        File(config, "qt-config.ini").writeText("[Renderer]\n")
        assertEquals(p(config), linux.edenConfig(eden))
        assertTrue(linux.write(File(config, "custom/0100F2C0115B6000.ini").absolutePath, "[Renderer]\n"), "a game's own file may be written there")
        // The Flatpak's, inside its own folder.
        val flatpak = File(home, ".var/app/dev.eden_emu.eden/config/eden").apply { mkdirs(); File(this, "qt-config.ini").writeText("") }
        assertEquals(p(flatpak), linux.edenConfig(eden.copy(appId = "dev.eden_emu.eden", detectedVia = "Flatpak")))
        // Windows: a `user` folder beside the program, else its own folder in AppData.
        val program = File(home, "Eden/eden.exe").apply { parentFile.mkdirs(); writeText("") }
        val appData = File(home, "AppData/Roaming").apply { File(this, "eden/config").mkdirs(); File(this, "eden/config/qt-config.ini").writeText("") }
        val windows = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = mapOf("APPDATA" to appData.absolutePath), home = home.absolutePath, backups = backups)
        val onWindows = InstalledEmulator(EmulatorId("windows.eden"), "Eden", Host.WINDOWS, program.absolutePath, platforms = setOf(PlatformId("switch")), detectedVia = "Folder")
        assertEquals(p(File(appData, "eden/config")), windows.edenConfig(onWindows))
        File(program.parentFile, "user/config").mkdirs()
        File(program.parentFile, "user/config/qt-config.ini").writeText("")
        assertEquals(p(File(program.parentFile, "user/config")), windows.edenConfig(onWindows))
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
    fun vita3kOnWindowsKeepsUx0InAppDataUnlessPortable() = runBlocking {
        val appData = File(home, "AppData/Roaming").apply { mkdirs() }
        val program = File(home, "Emu/Vita3K/Vita3K.exe").apply { parentFile.mkdirs(); writeText("") }
        val files = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = mapOf("APPDATA" to appData.absolutePath), home = home.absolutePath, backups = backups)
        val vita = InstalledEmulator(EmulatorId("windows.vita3k"), "Vita3K", Host.WINDOWS, program.absolutePath, platforms = setOf(PlatformId("psvita")), detectedVia = "Folder")
        val storage = files.vita3kStorage(vita)
        assertTrue(p(File(appData, "Vita3K/Vita3K")) in storage)
        assertTrue(p(File(program.parentFile, "portable/fs")) in storage)
        // Its config.yml sits beside the program, and can move ux0 anywhere.
        File(program.parentFile, "config.yml").writeText("pref-path: D:/Vita\n")
        assertEquals("D:/Vita", files.vita3kStorage(vita).first())
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

    private fun emulator(name: String, appId: String, via: String = "PATH") =
        InstalledEmulator(EmulatorId("linux.$name"), name, Host.LINUX, appId, platforms = emptySet(), detectedVia = via)

    @Test
    fun duckStationKeepsItsDataWhereItsSetDataRootSays() = runBlocking {
        // Linux: ~/.local/share/duckstation, or $XDG_CONFIG_HOME/duckstation when that is set.
        val linux = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertNull(linux.duckstation(emulator("duckstation", "/usr/bin/duckstation-qt")), "not set up yet")
        val data = File(home, ".local/share/duckstation").apply { mkdirs() }
        val moved = File(home, "srv/my-cheats")
        File(data, "settings.ini").writeText("[Folders]\nCheats = ${p(moved)}\n")
        val found = linux.duckstation(emulator("duckstation", "/usr/bin/duckstation-qt"))!!
        assertEquals(p(data), found.dataRoot)
        assertEquals(p(moved), found.cheats)
        assertEquals("${p(data)}/patches", found.patches)
        assertEquals("${p(data)}/gamesettings", found.gameSettings)
        assertNull(found.cheatsZip)
        val xdg = File(home, "xdg").apply { File(this, "duckstation").mkdirs() }
        File(xdg, "duckstation/settings.ini").writeText("")
        assertEquals(p(File(xdg, "duckstation")), DesktopEmulatorFiles(DesktopOs.LINUX, env = mapOf("XDG_CONFIG_HOME" to xdg.absolutePath), home = home.absolutePath, backups = backups).duckstation(emulator("duckstation", "/usr/bin/duckstation-qt"))!!.dataRoot)

        // Its Flatpak: inside the sandbox XDG_CONFIG_HOME is always set, so its config folder.
        val flat = File(home, ".var/app/org.duckstation.DuckStation/config/duckstation").apply { mkdirs() }
        File(flat, "settings.ini").writeText("")
        assertEquals(p(flat), linux.duckstation(emulator("duckstation", "org.duckstation.DuckStation", via = "Flatpak"))!!.dataRoot)

        // Portable: settings.ini beside the program, with its bundled codes in resources.
        val app = File(home, "Emu/DuckStation").apply { File(this, "resources").mkdirs() }
        File(app, "settings.ini").writeText("")
        File(app, "resources/cheats.zip").writeText("zip")
        File(app, "resources/patches.zip").writeText("zip")
        val portable = linux.duckstation(emulator("duckstation", p(File(app, "duckstation-qt"))))!!
        assertEquals(p(app), portable.dataRoot)
        assertEquals("${p(app)}/resources/cheats.zip", portable.cheatsZip)
        assertEquals("${p(app)}/resources/patches.zip", portable.patchesZip)
    }

    @Test
    fun duckStationOnWindowsPrefersAnOlderDocumentsFolder() = runBlocking {
        val local = File(home, "AppData/Local/DuckStation").apply { mkdirs(); File(this, "settings.ini").writeText("") }
        val env = mapOf("USERPROFILE" to home.absolutePath, "LOCALAPPDATA" to File(home, "AppData/Local").absolutePath)
        val files = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = env, home = home.absolutePath, backups = backups)
        val exe = p(File(home, "Programs/DuckStation/duckstation-qt-x64-ReleaseLTCG.exe"))
        assertEquals(p(local), files.duckstation(emulator("duckstation", exe))!!.dataRoot)
        val docs = File(home, "Documents/DuckStation").apply { mkdirs(); File(this, "settings.ini").writeText("") }
        assertEquals(p(docs), files.duckstation(emulator("duckstation", exe))!!.dataRoot)
    }

    @Test
    fun ppssppsMemoryStickIsFoundOnEachSystem() = runBlocking {
        val linux = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertNull(linux.ppsspp(emulator("ppsspp", "/usr/bin/PPSSPPSDL")), "no PSP folder yet")
        val config = File(home, ".config/ppsspp").apply { File(this, "PSP").mkdirs() }
        assertEquals(p(config), linux.ppsspp(emulator("ppsspp", "/usr/bin/PPSSPPSDL"))!!.memstick)
        val flat = File(home, ".var/app/org.ppsspp.PPSSPP/config/ppsspp").apply { File(this, "PSP").mkdirs() }
        assertEquals(p(flat), linux.ppsspp(emulator("ppsspp", "org.ppsspp.PPSSPP", via = "Flatpak"))!!.memstick)

        // Windows: memstick beside the program, unless installed.txt sends it to Documents\PPSSPP.
        val app = File(home, "Emu/PPSSPP").apply { File(this, "memstick/PSP").mkdirs() }
        val docs = File(home, "Documents/PPSSPP").apply { File(this, "PSP").mkdirs() }
        val windows = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = mapOf("USERPROFILE" to home.absolutePath), home = home.absolutePath, backups = backups)
        val exe = p(File(app, "PPSSPPWindows64.exe"))
        assertEquals(p(File(app, "memstick")), windows.ppsspp(emulator("ppsspp", exe))!!.memstick)
        File(app, "installed.txt").writeText("")
        assertEquals(p(docs), windows.ppsspp(emulator("ppsspp", exe))!!.memstick)
    }

    @Test
    fun retroArchsCheatAndOverrideFoldersFollowItsConfig() = runBlocking {
        val linux = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertNull(linux.retroArch(emulator("retroarch", "/usr/bin/retroarch")), "never started")
        val config = File(home, ".config/retroarch").apply { mkdirs() }
        File(config, "retroarch.cfg").writeText("video_driver = \"vulkan\"\n")
        val defaults = linux.retroArch(emulator("retroarch", "/usr/bin/retroarch"))!!
        assertEquals("${p(config)}/cheats", defaults.cheats)
        assertEquals("${p(config)}/config", defaults.configDir)
        File(config, "retroarch.cfg").writeText("cheat_database_path = \"~/srv/cht\"\nrgui_config_directory = \"default\"\n")
        val moved = linux.retroArch(emulator("retroarch", "/usr/bin/retroarch"))!!
        assertEquals("${p(home)}/srv/cht", moved.cheats)
        assertEquals("${p(config)}/config", moved.configDir)
        // Fuse may write only inside them, and remove only a file there.
        assertTrue(linux.write("${moved.cheats}/mGBA/A.cht", "cheats = 0\n"))
        assertTrue(linux.remove("${moved.cheats}/mGBA/A.cht"))
        assertFalse(linux.remove(p(File(config, "retroarch.cfg"))))

        // Windows keeps everything beside the program, which `:` in its config stands for.
        val app = File(home, "Emu/RetroArch").apply { mkdirs() }
        File(app, "retroarch.cfg").writeText("cheat_database_path = \":\\cheats\"\n")
        val windows = DesktopEmulatorFiles(DesktopOs.WINDOWS, env = mapOf("APPDATA" to File(home, "AppData/Roaming").absolutePath), home = home.absolutePath, backups = backups)
        val found = windows.retroArch(emulator("retroarch", p(File(app, "retroarch.exe"))))!!
        assertEquals("${p(app)}/cheats", found.cheats)
        assertEquals("${p(app)}/config", found.configDir)
    }
}
