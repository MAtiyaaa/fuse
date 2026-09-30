package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.linux.DesktopExec
import io.github.matiyaaa.fuse.launch.linux.DesktopShortcutAdapter
import io.github.matiyaaa.fuse.launch.linux.LinuxCatalog
import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.ShortcutFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxAdapterTest {
    private fun adapter(id: String) = LinuxCatalog.adapters.first { it.id == EmulatorId(id) }

    private fun command(id: String, game: Game, installed: InstalledEmulator, target: LaunchTarget, options: LaunchOptions = LaunchOptions()) =
        assertIs<LaunchPlan.Command>(adapter(id).plan(LaunchRequest(game, installed, target, options)))

    private val rom = "/games/gba/Metroid Fusion.gba"

    @Test
    fun retroArchFlatpakVsNative() {
        val g = game("gba", rom)
        val flatpak = command("linux.retroarch", g, linuxEmu("linux.retroarch", "org.libretro.RetroArch", "Flatpak"), LaunchTarget.File(rom), LaunchOptions(homeDir = "/home/u"))
        assertEquals(
            listOf("flatpak", "run", "org.libretro.RetroArch", "-L", "/home/u/.var/app/org.libretro.RetroArch/config/retroarch/cores/mgba_libretro.so", rom),
            flatpak.argv,
        )
        val native = command("linux.retroarch", g, linuxEmu("linux.retroarch", "/usr/bin/retroarch"), LaunchTarget.File(rom), LaunchOptions(homeDir = "/home/u", core = "gpsp"))
        assertEquals(listOf("/usr/bin/retroarch", "-L", "/home/u/.config/retroarch/cores/gpsp_libretro.so", rom), native.argv)
        val found = command(
            "linux.retroarch", g, linuxEmu("linux.retroarch", "/usr/bin/retroarch"), LaunchTarget.File(rom),
            LaunchOptions(corePath = "/usr/lib/libretro/mgba_libretro.so"),
        )
        assertEquals("/usr/lib/libretro/mgba_libretro.so", found.argv[2])
        val steam = command("linux.retroarch-steam", g, linuxEmu("linux.retroarch-steam", "/usr/bin/steam"), LaunchTarget.File(rom))
        assertEquals(listOf("/usr/bin/steam", "-applaunch", "1118310", "-L", "mgba_libretro", rom), steam.argv)
    }

    @Test
    fun standaloneFlagsFromEsde() {
        val pcsx2 = command("linux.pcsx2", game("ps2", "/g/a.chd"), linuxEmu("linux.pcsx2", "/usr/bin/pcsx2-qt"), LaunchTarget.File("/g/a.chd"))
        assertEquals(listOf("/usr/bin/pcsx2-qt", "-batch", "/g/a.chd"), pcsx2.argv)
        val dolphin = command("linux.dolphin", game("wii", "/g/a.rvz"), linuxEmu("linux.dolphin", "org.DolphinEmu.dolphin-emu", "Flatpak"), LaunchTarget.File("/g/a.rvz"))
        assertEquals(listOf("flatpak", "run", "org.DolphinEmu.dolphin-emu", "-b", "-e", "/g/a.rvz"), dolphin.argv)
        val mednafen = command("linux.mednafen", game("saturn", "/g/a.cue"), linuxEmu("linux.mednafen", "com.github.AmatCoder.mednaffe", "Flatpak"), LaunchTarget.File("/g/a.cue"))
        assertEquals(listOf("flatpak", "run", "--command=mednafen", "com.github.AmatCoder.mednaffe", "-force_module", "ss", "/g/a.cue"), mednafen.argv)
        val eden = command("linux.eden", game("switch", "/g/a.nsp"), linuxEmu("linux.eden", "/home/u/Applications/Eden.AppImage", "AppImage"), LaunchTarget.File("/g/a.nsp"))
        assertEquals(listOf("/home/u/Applications/Eden.AppImage", "-f", "-g", "/g/a.nsp"), eden.argv)
        val mame = command("linux.mame", game("arcade", "/g/arcade/sf2.zip"), linuxEmu("linux.mame", "/usr/bin/mame"), LaunchTarget.File("/g/arcade/sf2.zip"), LaunchOptions(homeDir = "/home/u"))
        assertEquals(listOf("/usr/bin/mame", "-rompath", "/g/arcade", "sf2"), mame.argv)
        assertEquals("/home/u/.mame", mame.workingDir)
    }

    @Test
    fun rpcs3Modes() {
        val rpcs3 = linuxEmu("linux.rpcs3", "/usr/bin/rpcs3")
        val dir = command("linux.rpcs3", game("ps3", "/g/ps3/GT5"), rpcs3, LaunchTarget.Directory("/g/ps3/GT5"))
        assertEquals(listOf("/usr/bin/rpcs3", "--no-gui", "/g/ps3/GT5"), dir.argv)
        val serial = command("linux.rpcs3", game("ps3", "/g/ps3/Demon's Souls.ps3"), rpcs3, LaunchTarget.File("/g/ps3/Demon's Souls.ps3"), LaunchOptions(injectedText = "BLUS30443"))
        assertEquals(listOf("/usr/bin/rpcs3", "--no-gui", "%RPCS3_GAMEID%:BLUS30443"), serial.argv)
    }

    @Test
    fun vita3kAndSteam() {
        val vita = command("linux.vita3k", game("psvita", "/g/a.psvita"), linuxEmu("linux.vita3k", "/usr/bin/vita3k"), LaunchTarget.File("/g/a.psvita"), LaunchOptions(injectedText = "PCSB00245"))
        assertEquals(listOf("/usr/bin/vita3k", "-r", "PCSB00245"), vita.argv)
        val steam = command("linux.steam", game("steam", "/g/Celeste.steam"), linuxEmu("linux.steam", "/usr/bin/steam"), LaunchTarget.Shortcut("/g/Celeste.steam", ShortcutFormat.GAMENATIVE), LaunchOptions(injectedText = "504230\n"))
        assertEquals(listOf("/usr/bin/steam", "-applaunch", "504230"), steam.argv)
        val url = command("linux.steam-url", game("steam", "/g/Celeste.steam"), linuxEmu("linux.steam-url", "/usr/bin/xdg-open"), LaunchTarget.File("/g/Celeste.steam"), LaunchOptions(injectedText = "504230"))
        assertEquals(listOf("/usr/bin/xdg-open", "steam://rungameid/504230"), url.argv)
    }

    @Test
    fun citronOnLinuxOnlyOpensTheApp() {
        val plan = adapter("linux.citron").plan(LaunchRequest(game("switch", "/g/a.nsp"), linuxEmu("linux.citron", "/usr/bin/citron"), LaunchTarget.File("/g/a.nsp")))
        assertIs<LaunchPlan.OpenAppOnly>(plan)
    }

    @Test
    fun desktopExecFieldCodesAreStripped() {
        assertEquals("steam steam://rungameid/504230", DesktopExec.stripFieldCodes("steam steam://rungameid/504230 %U"))
        assertEquals("app --file   --x", DesktopExec.stripFieldCodes("app --file %f %F --x"))
        assertEquals("echo 100%", DesktopExec.stripFieldCodes("echo 100%%"))
        assertEquals("lutris lutris:rungameid/3", DesktopExec.stripFieldCodes("lutris lutris:rungameid/3 %i %c %k"))
        assertEquals(
            listOf("env", "WINEPREFIX=/home/u/My Prefix", "wine", "C:\\Games\\game.exe"),
            DesktopExec.toArgv("env WINEPREFIX=\"/home/u/My Prefix\" wine \"C:\\\\Games\\\\game.exe\" %f"),
        )
        assertEquals(listOf("sh", "-c", "cd /g && ./run.sh"), DesktopExec.toArgv("cd /g && ./run.sh %U"))
        assertNull(DesktopExec.toArgv("%U"))
    }

    @Test
    fun desktopShortcutAdapterUsesGioOrExec() {
        val path = "/home/u/.local/share/applications/Celeste.desktop"
        val g = game("steam", path)
        val gio = DesktopShortcutAdapter.plan(LaunchRequest(g, linuxEmu("linux.desktop", "/usr/bin/gio"), LaunchTarget.Shortcut(path, ShortcutFormat.STEAM_URL)))
        assertEquals(listOf("/usr/bin/gio", "launch", path), (gio as LaunchPlan.Command).argv)
        val text = "#!/usr/bin/env xdg-open\n[Desktop Entry]\nName=Celeste\nExec=steam steam://rungameid/504230 %U\nPath=/home/u\n[Desktop Action x]\nExec=other\n"
        val exec = DesktopShortcutAdapter.plan(LaunchRequest(g, linuxEmu("linux.desktop", "builtin", "Built in"), LaunchTarget.File(path), LaunchOptions(injectedText = text)))
        exec as LaunchPlan.Command
        assertEquals(listOf("steam", "steam://rungameid/504230"), exec.argv)
        assertEquals("/home/u", exec.workingDir)
        val invalid = DesktopShortcutAdapter.plan(LaunchRequest(g, linuxEmu("linux.desktop", "builtin", "Built in"), LaunchTarget.File(path), LaunchOptions(injectedText = "Exec=x")))
        assertIs<LaunchPlan.Unsupported>(invalid)
    }

    @Test
    fun detectorFindsPathFlatpakAndAppImages() {
        val env = FakeLinux(
            executables = setOf("/usr/bin/pcsx2-qt", "/usr/bin/gio", "/home/u/.local/bin/Cemu/Cemu"),
            flatpaks = LinuxDetector.parseFlatpakList("Application ID\norg.libretro.RetroArch\ncom.github.AmatCoder.mednaffe\n"),
            dirs = mapOf(
                "/home/u/Applications" to listOf("DuckStation-x64.AppImage", "azaharplus-2.1.AppImage", "notes.txt"),
                "/home/u/Downloads" to listOf("RPCS3-v0.0.30.AppImage", "rpcs3-v0.0.35.AppImage"),
                "/opt/emus" to listOf("pcsx2-v2.4.AppImage"),
            ),
        )
        val found = LinuxDetector.detect(env, extraDirs = listOf("/opt/emus")).associateBy { it.id.value }
        assertEquals("/usr/bin/pcsx2-qt", found.getValue("linux.pcsx2").appId)
        assertEquals("PATH", found.getValue("linux.pcsx2").detectedVia)
        assertEquals("org.libretro.RetroArch", found.getValue("linux.retroarch").appId)
        assertEquals("Flatpak", found.getValue("linux.retroarch").detectedVia)
        assertEquals("com.github.AmatCoder.mednaffe", found.getValue("linux.mednafen").appId)
        assertEquals("/home/u/Applications/DuckStation-x64.AppImage", found.getValue("linux.duckstation").appId)
        assertEquals("AppImage", found.getValue("linux.duckstation").detectedVia)
        assertEquals("/home/u/Applications/azaharplus-2.1.AppImage", found.getValue("linux.azaharplus").appId)
        assertTrue("linux.azahar" !in found, "azahar* must not pick up AzaharPlus")
        assertEquals("/home/u/Downloads/rpcs3-v0.0.35.AppImage", found.getValue("linux.rpcs3").appId)
        assertEquals("/home/u/.local/bin/Cemu/Cemu", found.getValue("linux.cemu").appId)
        assertEquals("/usr/bin/gio", found.getValue("linux.desktop").appId)
        assertTrue("linux.retroarch-steam" !in found)

        val core = LinuxDetector.findRetroArchCore(
            FakeLinux(files = setOf("/home/u/.var/app/org.libretro.RetroArch/config/retroarch/cores/mgba_libretro.so")),
            found.getValue("linux.retroarch"), "mgba",
        )
        assertEquals("/home/u/.var/app/org.libretro.RetroArch/config/retroarch/cores/mgba_libretro.so", core)
    }
}
