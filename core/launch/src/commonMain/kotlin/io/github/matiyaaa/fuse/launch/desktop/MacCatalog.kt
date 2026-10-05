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
 * macOS emulators: the ones with a Mac build. App bundles and Homebrew programs come from ES-DE's
 * macos es_find_rules.xml and arguments from its es_systems.xml ([Sources.ESDE_MACOS], MIT). Like
 * Windows, each entry reuses the Linux definition with its own id; the program inside a bundle is
 * what runs (`Contents/MacOS/<CFBundleExecutable>`), as ES-DE runs it.
 */
object MacCatalog {
    private const val PREFIX = "macos"

    private fun rule(name: String) = "${Sources.ESDE_MACOS}: $name"

    private fun port(
        linuxId: String,
        bundles: List<String>,
        programs: List<String> = emptyList(),
        rule: String = linuxId.uppercase(),
        excludes: List<String> = emptyList(),
        opener: String? = null,
        change: (LinuxEmulatorDef) -> LinuxEmulatorDef = { it },
    ): DesktopEmulatorDef {
        val base = requireNotNull(LinuxCatalog.def("linux.$linuxId")) { "No Linux entry $linuxId" }
        val def = base.copy(id = "$PREFIX.$linuxId", detection = LinuxDetection(), source = rule(rule))
        return DesktopEmulatorDef(change(def), DesktopFind(programs, bundles, excludes = excludes, opener = opener))
    }

    private fun file(vararg args: String) = LaunchMode("File", TargetKind.FILE, LinuxCommandSpec(args.toList()))

    val defs: List<DesktopEmulatorDef> = listOf(
        port("retroarch", listOf("RetroArch*.app")) {
            it.copy(limitations = listOf("The core must be installed (RetroArch's Online Updater)."))
        },
        port("pcsx2", listOf("PCSX2*.app")),
        port("play", listOf("Play.app"), rule = "PLAY!"),
        port("duckstation", listOf("DuckStation*.app")),
        port("ppsspp", listOf("PPSSPP*.app"), programs = listOf("ppsspp")),
        port("dolphin", listOf("Dolphin*.app")),
        port("cemu", listOf("Cemu.app")),
        port("rpcs3", listOf("RPCS3.app")),
        port("eden", listOf("eden.app")),
        port("ryujinx", listOf("Ryujinx*.app")),
        port("azahar", listOf("azahar*.app"), excludes = listOf("azaharplus*")),
        port("lime3ds", listOf("lime3ds*.app", "lime-qt.app")),
        port("citra", listOf("citra-qt.app")),
        port("mandarine", listOf("mandarine-qt.app")),
        port("panda3ds", listOf("Alber.app")),
        port("melonds", listOf("melonDS.app")),
        port("mgba", listOf("mGBA.app"), programs = listOf("mGBA", "mgba")),
        DesktopEmulatorDef(
            LinuxEmulatorDef(
                id = "$PREFIX.mupen64plus", name = "Mupen64Plus", platforms = platforms("n64"),
                detection = LinuxDetection(),
                modes = listOf(file("{ROM}")),
                source = rule("MUPEN64PLUS"), confidence = Confidence.VERIFIED_ESDE, homepage = "https://mupen64plus.org/",
            ),
            DesktopFind(programs = listOf("mupen64plus"), bundles = listOf("mupen64plus.app")),
        ),
        port("flycast", listOf("Flycast.app")),
        port("redream", listOf("redream.app")),
        port("vita3k", listOf("Vita3K.app")),
        port("xemu", listOf("xemu.app")),
        port("shadps4", listOf("shadps4*.app")),
        port("sharpemu", listOf("SharpEmu*.app"), programs = listOf("SharpEmu")) { it.copy(source = LinuxCatalog.SHARPEMU_SOURCE) },
        port("mednafen", emptyList(), programs = listOf("mednafen")),
        port("mame", emptyList(), programs = listOf("mame")),
        port("scummvm", listOf("ScummVM.app"), programs = listOf("scummvm")),
        port("pico8", listOf("PICO-8.app"), rule = "PICO-8"),
        port("ruffle", listOf("Ruffle.app")),
        port("dosbox-staging", listOf("dosbox-staging.app", "DOSBox Staging.app"), programs = listOf("dosbox-staging")),
        port("dosbox-x", listOf("dosbox-x.app"), programs = listOf("dosbox-x")),
        port("steam-url", listOf("Steam.app"), rule = "STEAM", opener = "/usr/bin/open") {
            it.copy(
                source = "Steam's own shortcuts open steam://rungameid/<id>; ${rule("STEAM")} for where Steam is; open(1)",
                limitations = listOf("Opens the Steam link in Steam."),
            )
        },
    )

    /** Adapters for every entry plus the built-in apps and scripts adapter. */
    val adapters: List<EmulatorAdapter> = defs.map { LinuxCommandAdapter(it.def, Host.MACOS) } + MacOpenAdapter

    fun def(id: String): DesktopEmulatorDef? = defs.firstOrNull { it.def.id == id }
}
