package io.github.matiyaaa.fuse.launch.desktop

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchMode
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.TargetKind
import io.github.matiyaaa.fuse.launch.linux.LinuxCatalog
import io.github.matiyaaa.fuse.launch.linux.LinuxCommandAdapter
import io.github.matiyaaa.fuse.launch.linux.LinuxCommandSpec
import io.github.matiyaaa.fuse.launch.linux.LinuxDetection
import io.github.matiyaaa.fuse.launch.linux.LinuxEmulatorDef
import io.github.matiyaaa.fuse.launch.platforms
import io.github.matiyaaa.fuse.model.Host

/**
 * Windows emulators. Program names come from ES-DE's windows es_find_rules.xml and arguments from its
 * es_systems.xml ([Sources.ESDE_WINDOWS], MIT). The arguments match Linux for nearly every emulator,
 * so each entry reuses the Linux definition with its own id and only the differences spelled out
 * (Cemu's `-f`, the programs ES-DE starts from their own folder).
 */
object WindowsCatalog {
    private const val PREFIX = "windows"

    private fun rule(name: String) = "${Sources.ESDE_WINDOWS}: $name"

    /** The Linux entry [linuxId] for Windows, found by [programs]. */
    private fun port(
        linuxId: String,
        vararg programs: String,
        rule: String = linuxId.uppercase(),
        folders: List<String> = emptyList(),
        excludes: List<String> = emptyList(),
        fromItsFolder: Boolean = false,
        change: (LinuxEmulatorDef) -> LinuxEmulatorDef = { it },
    ): DesktopEmulatorDef {
        val base = requireNotNull(LinuxCatalog.def("linux.$linuxId")) { "No Linux entry $linuxId" }
        var def = base.copy(id = "$PREFIX.$linuxId", detection = LinuxDetection(), source = rule(rule))
        // ES-DE's %STARTDIR%=%EMUDIR%: the program reads its settings from its own folder.
        if (fromItsFolder) def = def.copy(modes = def.modes.map { it.copy(spec = it.spec.copy(workingDir = "{EMUDIR}")) })
        return DesktopEmulatorDef(change(def), DesktopFind(programs.toList(), folders = folders, excludes = excludes))
    }

    private fun file(vararg args: String) = LaunchMode("File", TargetKind.FILE, LinuxCommandSpec(args.toList()))

    val defs: List<DesktopEmulatorDef> = listOf(
        port("retroarch", "retroarch.exe") {
            it.copy(limitations = listOf("The core must be installed (RetroArch's Online Updater)."))
        },
        port("pcsx2", "pcsx2-qt.exe", "pcsx2-qtx64.exe", "pcsx2-qtx64-avx2.exe"),
        port("play", "Play.exe", rule = "PLAY!", folders = listOf("play*"), fromItsFolder = true),
        port("duckstation", "duckstation-qt-x64-ReleaseLTCG.exe", "duckstation-qt-x64-ReleaseLTCG-SSE2.exe", "duckstation-nogui-x64-ReleaseLTCG.exe"),
        port("ppsspp", "PPSSPPWindows64.exe"),
        port("dolphin", "Dolphin.exe"),
        port("cemu", "Cemu.exe") { it.copy(modes = listOf(file("-f", "-g", "{ROM}"))) },
        port("rpcs3", "rpcs3.exe"),
        port("eden", "eden.exe"),
        port("ryujinx", "Ryujinx.exe", "Ryujinx.Ava.exe"),
        port("citron", "citron.exe") { it.copy(source = "Not in ES-DE; command line not verified") },
        port("azahar", "azahar.exe", excludes = listOf("azaharplus*")),
        port("azaharplus", "azaharplus.exe"),
        port("lime3ds", "lime3ds.exe", "lime3ds-gui.exe", "lime-qt.exe"),
        port("citra", "citra-qt.exe"),
        port("mandarine", "mandarine-qt.exe"),
        port("panda3ds", "Alber.exe"),
        port("melonds", "melonDS.exe"),
        port("mgba", "mGBA.exe"),
        DesktopEmulatorDef(
            LinuxEmulatorDef(
                id = "$PREFIX.mupen64plus", name = "Mupen64Plus", platforms = platforms("n64"),
                detection = LinuxDetection(),
                modes = listOf(file("--fullscreen", "{ROM}")),
                source = rule("MUPEN64PLUS"), confidence = Confidence.VERIFIED_ESDE, homepage = "https://mupen64plus.org/",
            ),
            DesktopFind(listOf("mupen64plus-ui-console.exe")),
        ),
        port("flycast", "flycast.exe"),
        port("redream", "redream.exe"),
        port("vita3k", "Vita3K.exe", fromItsFolder = true),
        port("xemu", "xemu.exe", fromItsFolder = true),
        port("xenia", "xenia.exe", "xenia_canary.exe"),
        port("shadps4", "shadPS4.exe", fromItsFolder = true),
        port("sharpemu", "SharpEmu.exe", fromItsFolder = true) { it.copy(source = LinuxCatalog.SHARPEMU_SOURCE) },
        DesktopEmulatorDef(
            LinuxEmulatorDef(
                id = "$PREFIX.kytyps5", name = "KytyPS5", platforms = platforms("ps5"),
                detection = LinuxDetection(),
                modes = listOf(
                    LaunchMode("Game", TargetKind.FILE, LinuxCommandSpec(listOf("--game", "{ROM}"), workingDir = "{EMUDIR}")),
                ),
                source = "KytyPS5's README: kyty_emulator.exe --game <game folder or eboot.bin>", confidence = Confidence.COMMUNITY,
                homepage = "https://kytyps5.github.io/",
                limitations = listOf("PlayStation 5 emulation is young: a few games play, many stop at a menu or loading screen."),
            ),
            DesktopFind(listOf("kyty_emulator.exe", "kyty_launcher.exe"), folders = listOf("kyty*")),
        ),
        port("mednafen", "mednafen.exe"),
        port("mame", "mame.exe", fromItsFolder = true),
        port("scummvm", "scummvm.exe"),
        port("pico8", "pico8.exe", rule = "PICO-8"),
        port("ruffle", "ruffle.exe"),
        port("dosbox-staging", "dosbox.exe", folders = listOf("*staging*")),
        port("dosbox-x", "dosbox-x.exe"),
        port("steam", "steam.exe") {
            it.copy(source = "Steam's command line (-applaunch <appid>); ${rule("STEAM")} for where Steam is")
        },
    )

    /** Adapters for every entry plus the built-in programs and shortcuts adapter. */
    val adapters: List<EmulatorAdapter> = defs.map { LinuxCommandAdapter(it.def, Host.WINDOWS) } + WindowsShortcutAdapter

    fun def(id: String): DesktopEmulatorDef? = defs.firstOrNull { it.def.id == id }
}
