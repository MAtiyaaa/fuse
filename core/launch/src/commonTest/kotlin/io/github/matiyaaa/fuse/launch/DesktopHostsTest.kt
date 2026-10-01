package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.desktop.HostFiles
import io.github.matiyaaa.fuse.launch.desktop.MacCatalog
import io.github.matiyaaa.fuse.launch.desktop.MacDetector
import io.github.matiyaaa.fuse.launch.desktop.MacOpenAdapter
import io.github.matiyaaa.fuse.launch.desktop.WindowsCatalog
import io.github.matiyaaa.fuse.launch.desktop.WindowsDetector
import io.github.matiyaaa.fuse.launch.desktop.WindowsShortcutAdapter
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory Windows or macOS file system: folders with their entries, files, and small texts. */
private class FakeHost(
    override val homeDir: String,
    private val dirs: Map<String, List<String>>,
    private val files: Set<String> = emptySet(),
    private val texts: Map<String, String> = emptyMap(),
    private val vars: Map<String, String> = emptyMap(),
    private val path: List<String> = emptyList(),
    private val roots: List<String> = emptyList(),
) : HostFiles {
    private fun key(p: String) = if (p.length == 3 && p[1] == ':') p else p.trimEnd('/')
    private val allFiles = files + texts.keys + dirs.flatMap { (d, names) -> names.map { "${key(d).trimEnd('/')}/$it" } }.filter { key(it) !in dirs.keys.map(::key) }
    override fun env(name: String) = vars[name]
    override fun pathDirectories() = path
    override fun isFile(path: String) = path in allFiles
    override fun isDirectory(path: String) = dirs.keys.any { key(it).equals(key(path), ignoreCase = true) }
    override fun list(dir: String) = dirs.entries.firstOrNull { key(it.key).equals(key(dir), ignoreCase = true) }?.value.orEmpty()
    override fun readText(path: String) = texts[path]
    override fun drives() = roots
}

class DesktopHostsTest {
    private val windows = FakeHost(
        homeDir = "C:/Users/u",
        vars = mapOf(
            "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local", "APPDATA" to "C:\\Users\\u\\AppData\\Roaming",
            "ProgramFiles" to "C:\\Program Files", "ProgramFiles(x86)" to "C:\\Program Files (x86)", "SystemRoot" to "C:\\Windows",
        ),
        roots = listOf("C:/", "D:/"),
        dirs = mapOf(
            "C:/" to listOf("Program Files", "Program Files (x86)", "Users", "Windows", "RetroArch-Win64"),
            "C:/RetroArch-Win64" to listOf("retroarch.exe", "cores"),
            "C:/RetroArch-Win64/cores" to listOf("snes9x_libretro.dll"),
            "C:/Program Files" to listOf("PCSX2", "Dolphin"),
            "C:/Program Files/PCSX2" to listOf("pcsx2-qt.exe"),
            "C:/Program Files/Dolphin" to listOf("Dolphin.exe"),
            "C:/Program Files (x86)" to listOf("Steam"),
            "C:/Program Files (x86)/Steam" to listOf("steam.exe", "steamapps"),
            "C:/Program Files (x86)/Steam/steamapps" to listOf("libraryfolders.vdf"),
            "E:/SteamLibrary/steamapps/common" to listOf("Vita3K"),
            "E:/SteamLibrary/steamapps/common/Vita3K" to listOf("Vita3K.exe"),
            "C:/Users/u/scoop/apps" to listOf("mgba", "dosbox-x"),
            "C:/Users/u/scoop/apps/mgba" to listOf("current"),
            "C:/Users/u/scoop/apps/mgba/current" to listOf("mGBA.exe"),
            "C:/Users/u/scoop/apps/dosbox-x" to listOf("current"),
            "C:/Users/u/scoop/apps/dosbox-x/current" to listOf("dosbox-x.exe", "dosbox.exe"),
            "D:/" to listOf("Emulators"),
            "D:/Emulators" to listOf("dosbox-0.74", "dosbox-staging-0.82", "azaharplus-1.0", "xemu"),
            "D:/Emulators/dosbox-0.74" to listOf("DOSBox.exe"),
            "D:/Emulators/dosbox-staging-0.82" to listOf("dosbox.exe"),
            "D:/Emulators/azaharplus-1.0" to listOf("azahar.exe", "azaharplus.exe"),
            "D:/Emulators/xemu" to listOf("xemu.exe"),
            "C:/Windows/System32/WindowsPowerShell/v1.0" to listOf("powershell.exe"),
        ),
        files = setOf("D:/Tools/Cemu/Cemu.exe"),
        texts = mapOf(
            "C:/Program Files (x86)/Steam/steamapps/libraryfolders.vdf" to
                "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"E:\\\\SteamLibrary\"\n\t}\n}\n",
        ),
    )

    @Test
    fun windowsDetectorSearchesFoldersSteamAndScoop() {
        val found = WindowsDetector.detect(windows, located = mapOf("windows.cemu" to "D:\\Tools\\Cemu\\Cemu.exe")).associateBy { it.id.value }
        assertEquals("C:/RetroArch-Win64/retroarch.exe", found.getValue("windows.retroarch").appId)
        assertEquals("C:/Program Files/PCSX2/pcsx2-qt.exe", found.getValue("windows.pcsx2").appId)
        assertEquals("C:/Program Files/Dolphin/Dolphin.exe", found.getValue("windows.dolphin").appId)
        assertEquals("C:/Program Files (x86)/Steam/steam.exe", found.getValue("windows.steam").appId)
        assertEquals("E:/SteamLibrary/steamapps/common/Vita3K/Vita3K.exe", found.getValue("windows.vita3k").appId)
        assertEquals("Steam", found.getValue("windows.vita3k").detectedVia)
        assertEquals("C:/Users/u/scoop/apps/mgba/current/mGBA.exe", found.getValue("windows.mgba").appId)
        // DOSBox Staging's dosbox.exe, not the original DOSBox's.
        assertEquals("D:/Emulators/dosbox-staging-0.82/dosbox.exe", found.getValue("windows.dosbox-staging").appId)
        assertEquals("C:/Users/u/scoop/apps/dosbox-x/current/dosbox-x.exe", found.getValue("windows.dosbox-x").appId)
        // AzaharPlus's folder holds an azahar.exe too; it is not Azahar.
        assertEquals("D:/Emulators/azaharplus-1.0/azaharplus.exe", found.getValue("windows.azaharplus").appId)
        assertFalse("windows.azahar" in found)
        assertEquals("D:/Tools/Cemu/Cemu.exe", found.getValue("windows.cemu").appId)
        assertEquals("Located", found.getValue("windows.cemu").detectedVia)
        assertEquals("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe", found.getValue("windows.shortcut").appId)
        assertFalse("windows.ppsspp" in found)
        assertEquals(
            "C:/RetroArch-Win64/cores/snes9x_libretro.dll",
            WindowsDetector.findRetroArchCore(windows, found.getValue("windows.retroarch"), "snes9x"),
        )
        assertNull(WindowsDetector.findRetroArchCore(windows, found.getValue("windows.retroarch"), "mgba"))
    }

    @Test
    fun steamLibraryListParses() {
        val vdf = "\"libraryfolders\" { \"0\" { \"path\" \"C:\\\\Program Files (x86)\\\\Steam\" } \"1\" { \"path\" \"D:\\\\Steam Games\" } }"
        assertEquals(listOf("C:/Program Files (x86)/Steam", "D:/Steam Games"), WindowsDetector.parseLibraryFolders(vdf))
    }

    private fun windowsEmu(id: String, path: String) = InstalledEmulator(EmulatorId(id), id, Host.WINDOWS, path, platforms = emptySet(), detectedVia = "Folder")

    private fun plan(adapterId: String, installed: InstalledEmulator, platform: String, rom: String, options: LaunchOptions = LaunchOptions()): LaunchPlan {
        val adapter = AdapterRegistry.Default[EmulatorId(adapterId)]!!
        return adapter.plan(LaunchRequest(game(platform, rom), installed, LaunchTarget.File(rom), options))
    }

    @Test
    fun windowsCommands() {
        val rom = "D:/ROMs/snes/Chrono Trigger (USA).sfc"
        val ra = assertIs<LaunchPlan.Command>(plan("windows.retroarch", windowsEmu("windows.retroarch", "C:/RetroArch-Win64/retroarch.exe"), "snes", rom))
        assertEquals(listOf("C:/RetroArch-Win64/retroarch.exe", "-L", "C:/RetroArch-Win64/cores/snes9x_libretro.dll", rom), ra.argv)
        val cemu = assertIs<LaunchPlan.Command>(plan("windows.cemu", windowsEmu("windows.cemu", "D:/Cemu/Cemu.exe"), "wiiu", "D:/ROMs/wiiu/Zelda.wua"))
        assertEquals(listOf("D:/Cemu/Cemu.exe", "-f", "-g", "D:/ROMs/wiiu/Zelda.wua"), cemu.argv)
        // ES-DE starts xemu from its own folder.
        val xemu = assertIs<LaunchPlan.Command>(plan("windows.xemu", windowsEmu("windows.xemu", "D:/Emulators/xemu/xemu.exe"), "xbox", "D:/ROMs/xbox/Halo.iso"))
        assertEquals("D:/Emulators/xemu", xemu.workingDir)
        val atRoot = assertIs<LaunchPlan.Command>(plan("windows.xemu", windowsEmu("windows.xemu", "D:/xemu.exe"), "xbox", "D:/ROMs/xbox/Halo.iso"))
        assertEquals("D:/", atRoot.workingDir)
    }

    @Test
    fun windowsShortcutsRunThroughPowerShellLiterals() {
        val shell = windowsEmu("windows.shortcut", "C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe")
        val exe = assertIs<LaunchPlan.Command>(plan("windows.shortcut", shell, "win", "D:/Games/Hades/Hades.exe"))
        assertEquals(listOf("D:/Games/Hades/Hades.exe"), exe.argv)
        assertEquals("D:/Games/Hades", exe.workingDir)
        val lnk = assertIs<LaunchPlan.Command>(plan("windows.shortcut", shell, "win", "D:/Games/Tom's Game (USA) & More.lnk"))
        assertEquals(shell.appId, lnk.argv.first())
        val script = lnk.argv.last()
        assertTrue("ProcessStartInfo 'D:\\Games\\Tom''s Game (USA) & More.lnk'" in script, script)
        assertTrue("WorkingDirectory = 'D:\\Games'" in script, script)
        assertEquals("'it\u2019\u2019s'", WindowsShortcutAdapter.literal("it\u2019s"))
        assertIs<LaunchPlan.Unsupported>(plan("windows.shortcut", shell, "win", "D:/Games/readme.txt"))
        assertFalse(WindowsShortcutAdapter.accepts(LaunchTarget.File("D:/a.iso"), PlatformId("ps2")))
    }

    private val mac = FakeHost(
        homeDir = "/Users/u",
        dirs = mapOf(
            "/Applications" to listOf("PPSSPPSDL.app", "RetroArch.app", "Emulators", "Steam.app", "azaharplus.app", "Safari.app"),
            "/Applications/PPSSPPSDL.app/Contents/MacOS" to listOf("PPSSPPSDL"),
            "/Applications/RetroArch.app/Contents/MacOS" to listOf("RetroArch", "helper"),
            "/Applications/Steam.app/Contents/MacOS" to listOf("steam_osx"),
            "/Applications/azaharplus.app/Contents/MacOS" to listOf("azaharplus"),
            "/Applications/Emulators" to listOf("DuckStation.app"),
            "/Applications/Emulators/DuckStation.app/Contents/MacOS" to listOf("DuckStation"),
            "/Users/u/Library/Application Support/RetroArch/cores" to listOf("mgba_libretro.dylib"),
        ),
        files = setOf("/opt/homebrew/bin/mednafen"),
        texts = mapOf(
            "/Applications/PPSSPPSDL.app/Contents/Info.plist" to
                "<?xml version=\"1.0\"?><plist><dict><key>CFBundleExecutable</key>\n<string>PPSSPPSDL</string></dict></plist>",
            "/Applications/RetroArch.app/Contents/Info.plist" to "bplist00\u0001\u0002",
        ),
    )

    @Test
    fun macDetectorReadsBundlesAndHomebrew() {
        val found = MacDetector.detect(mac).associateBy { it.id.value }
        assertEquals("/Applications/PPSSPPSDL.app/Contents/MacOS/PPSSPPSDL", found.getValue("macos.ppsspp").appId)
        assertEquals("App", found.getValue("macos.ppsspp").detectedVia)
        // A binary Info.plist: the program named like the bundle.
        assertEquals("/Applications/RetroArch.app/Contents/MacOS/RetroArch", found.getValue("macos.retroarch").appId)
        assertEquals("/Applications/Emulators/DuckStation.app/Contents/MacOS/DuckStation", found.getValue("macos.duckstation").appId)
        assertEquals("/opt/homebrew/bin/mednafen", found.getValue("macos.mednafen").appId)
        assertEquals("/usr/bin/open", found.getValue("macos.steam-url").appId)
        assertFalse("macos.azahar" in found, "azahar*.app must not pick up AzaharPlus")
        assertEquals("/usr/bin/open", found.getValue("macos.open").appId)
        assertEquals(
            "/Users/u/Library/Application Support/RetroArch/cores/mgba_libretro.dylib",
            MacDetector.findRetroArchCore(mac, found.getValue("macos.retroarch"), "mgba"),
        )
        assertEquals("/Applications/PPSSPPSDL.app/Contents/MacOS/PPSSPPSDL", MacDetector.resolvePicked(mac, "/Applications/PPSSPPSDL.app/"))
        assertNull(MacDetector.resolvePicked(mac, "/Applications/Missing.app"))
    }

    @Test
    fun macCommands() {
        val installed = InstalledEmulator(EmulatorId("macos.retroarch"), "RetroArch", Host.MACOS, "/Applications/RetroArch.app/Contents/MacOS/RetroArch", platforms = emptySet(), detectedVia = "App")
        val ra = assertIs<LaunchPlan.Command>(plan("macos.retroarch", installed, "gba", "/ROMs/gba/Metroid.gba", LaunchOptions(homeDir = "/Users/u")))
        assertEquals(
            listOf(installed.appId, "-L", "/Users/u/Library/Application Support/RetroArch/cores/mgba_libretro.dylib", "/ROMs/gba/Metroid.gba"),
            ra.argv,
        )
        val open = InstalledEmulator(EmulatorId("macos.open"), "open", Host.MACOS, "/usr/bin/open", platforms = emptySet(), detectedVia = "Built in")
        val app = assertIs<LaunchPlan.Command>(MacOpenAdapter.plan(LaunchRequest(game("win", "/Games/Celeste.app"), open, LaunchTarget.Directory("/Games/Celeste.app"), LaunchOptions())))
        assertEquals(listOf("/usr/bin/open", "-W", "/Games/Celeste.app"), app.argv)
    }

    @Test
    fun everyDesktopHostHasItsOwnOrder() {
        assertEquals(EmulatorId("windows.duckstation"), EmulatorPriority.forPlatform(Host.WINDOWS, PlatformId("psx")).first())
        assertEquals(EmulatorId("macos.retroarch"), EmulatorPriority.forPlatform(Host.MACOS, PlatformId("snes")).first())
        assertEquals(EmulatorId("windows.shortcut"), EmulatorPriority.forPlatform(Host.WINDOWS, PlatformId("win")).first())
        // Xenia has no Mac build.
        assertTrue(EmulatorPriority.forPlatform(Host.MACOS, PlatformId("xbox360")).isEmpty())
        assertTrue(WindowsCatalog.defs.all { it.find.programs.isNotEmpty() })
        assertTrue(MacCatalog.defs.all { it.find.programs.isNotEmpty() || it.find.bundles.isNotEmpty() })
        assertTrue(Paths.isAbsolute("C:/x.exe") && Paths.isAbsolute("/usr/bin/x") && !Paths.isAbsolute("x.exe") && !Paths.isAbsolute("C:x"))
    }
}
