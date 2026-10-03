package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.desktop.MacCatalog
import io.github.matiyaaa.fuse.launch.desktop.WindowsCatalog
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Default emulator order per platform, used when neither the game nor the platform names one. The
 * first installed adapter that can launch the game wins; adapters that only open their app come last
 * in practice (see [LaunchResolver]). Standalone emulators lead from the PlayStation on (ES-DE's
 * defaults for PS2, PS3, PSP, Vita, 3DS, Switch, Wii U, Xbox and PC; DuckStation for PSX), and
 * RetroArch leads for the older systems, where ES-DE also defaults to a libretro core
 * (ES-DE ANDROID.md system table).
 */
object EmulatorPriority {
    private fun ids(vararg ids: String) = ids.map(::EmulatorId)

    private fun expand(vararg entries: Pair<List<String>, List<EmulatorId>>): Map<PlatformId, List<EmulatorId>> =
        entries.flatMap { (keys, list) -> keys.map { PlatformId(it) to list } }.toMap()

    private val RA = "retroarch"

    val android: Map<PlatformId, List<EmulatorId>> = expand(
        listOf("psx") to ids("duckstation", RA, "epsxe", "fpse-ng", "fpse", "armsx1", "lemuroid"),
        listOf("ps2") to ids("nethersx2", "nethersx2-turnip", "nethersx2-turnip-classic", "armsx2", "emucorex", "play", RA),
        listOf("ps3") to ids("aps3e", "armsx3", "emucorec", "rpcsx", "rpcs3-android"),
        listOf("ps4") to ids("bachatas4", "shadps4"),
        listOf("psp") to ids("ppsspp", RA, "lemuroid"),
        listOf("psvita") to ids("vita3k", "emucorev"),
        listOf("n64") to ids(RA, "m64plus-fz", "mupen64plus-ae", "lemuroid", "gopher64"),
        listOf("nds") to ids("melonds", "melonds-nightly", "watermelonds", "drastic", "seedlessds", "noods", "skyemu", RA, "lemuroid"),
        listOf("nintendo-dsi") to ids("melonds", "melonds-nightly", "watermelonds", "seedlessds", RA),
        listOf("3ds", "new-nintendo-3ds") to ids("azahar", "azaharplus", "citra", "citra-canary", "mandarine", "lime3ds", "citra-mmj", RA, "panda3ds"),
        listOf("gb", "gbc") to ids(RA, "pizza-boy-gbc", "my-oldboy", "linkboy", "skyemu", "gbc-emu", "lemuroid"),
        listOf("gba") to ids(RA, "pizza-boy-gba", "my-boy", "linkboy", "skyemu", "noods", "gba-emu", "lemuroid"),
        listOf("nes") to ids(RA, "nes-emu", "ines", "nesoid", "lemuroid"),
        listOf("famicom", "fds") to ids(RA, "nes-emu", "ines", "nesoid"),
        listOf("snes") to ids(RA, "snes9x-explus", "lemuroid"),
        listOf("sfam") to ids(RA, "snes9x-explus"),
        listOf("ngc", "wii") to ids("dolphin", "dolphin-mmjr2", "dolphin-mmjr", RA),
        listOf("wiiu") to ids("cemu", RA),
        listOf("switch") to ids("eden", "eden-nightly", "kenji-nx", "citron", "sudachi", "yuzu", "skyline", "strato"),
        listOf("virtualboy") to ids(RA, "virtual-virtual-boy"),
        listOf("pokemon-mini") to ids(RA),
        listOf("dc") to ids("flycast", "redream", RA),
        listOf("saturn") to ids(RA, "yabasanshiro-2", "saturn-emu"),
        listOf("genesis") to ids(RA, "md-emu", "pizza-boy-sc", "lemuroid"),
        listOf("segacd") to ids(RA, "md-emu", "pizza-boy-sc", "lemuroid"),
        listOf("sms") to ids(RA, "md-emu", "pizza-boy-sc", "mastergear", "lemuroid"),
        listOf("gamegear") to ids(RA, "pizza-boy-sc", "mastergear", "lemuroid"),
        listOf("sg1000") to ids(RA, "mastergear"),
        listOf("sega32") to ids(RA),
        listOf("arcade") to ids(RA, "mame4droid-current", "mame4droid", "flycast", "neo-emu", "lemuroid"),
        listOf("neogeoaes") to ids(RA, "neo-emu", "mame4droid-current", "mame4droid"),
        listOf("neo-geo-cd") to ids(RA),
        listOf("neo-geo-pocket", "neo-geo-pocket-color") to ids(RA, "ngp-emu", "lemuroid"),
        listOf("tg16") to ids(RA, "pce-emu", "lemuroid"),
        listOf("turbografx-cd") to ids(RA, "pce-emu"),
        listOf("atari2600") to ids(RA, "2600-emu", "lemuroid"),
        listOf("atari5200") to ids(RA),
        listOf("atari7800") to ids(RA, "mame4droid-current", "lemuroid"),
        listOf("lynx") to ids(RA, "lynx-emu", "lemuroid"),
        listOf("jaguar") to ids(RA, "iratajaguar", "mame4droid-current"),
        listOf("wonderswan", "wonderswan-color") to ids(RA, "swan-emu", "lemuroid"),
        listOf("xbox") to ids("x1-box", "hakux", "xenra"),
        listOf("xbox360") to ids("ax360e", "xendroid", "xenra", "x360-mobile"),
        listOf("win") to ids(
            "winlator-cmod", "winnative", "winlator-glibc", "winlator-proot", "bannerlator", "gamenative", "gamehub-lite",
            "gamehub-lite-local", "winlator", "winlator-frost", "gamehub",
        ),
        listOf("steam") to ids("gamenative", "gamehub-lite", "gamehub-lite-local", "winnative", "gamehub"),
        listOf("android") to ids("android-app"),
        listOf("dos") to ids(RA),
        listOf("scummvm") to ids("scummvm", RA),
        listOf("msx") to ids(RA, "fmsx", "msx-emu"),
        listOf("c64") to ids(RA, "c64-emu"),
        listOf("amiga") to ids(RA),
        listOf("3do") to ids(RA, "real3doplayer"),
        listOf("colecovision") to ids(RA, "colem", "msx-emu"),
        listOf("intellivision") to ids(RA, "mame4droid-current"),
        listOf("vectrex") to ids(RA, "mame4droid-current"),
        listOf("pico8") to ids("pico8-android"),
        listOf("flash") to ids("swiff"),
    )

    private val LRA = "linux.retroarch"
    private val LRAS = "linux.retroarch-steam"

    val linux: Map<PlatformId, List<EmulatorId>> = expand(
        listOf("psx") to ids("linux.duckstation", LRA, "linux.mednafen", LRAS),
        listOf("ps2") to ids("linux.pcsx2", LRA, "linux.play", LRAS),
        listOf("ps3") to ids("linux.rpcs3"),
        listOf("ps4") to ids("linux.shadps4"),
        listOf("psp") to ids("linux.ppsspp", LRA, LRAS),
        listOf("psvita") to ids("linux.vita3k"),
        listOf("n64") to ids(LRA, "linux.mupen64plus", LRAS),
        listOf("nds", "nintendo-dsi") to ids("linux.melonds", LRA, LRAS),
        listOf("3ds", "new-nintendo-3ds") to ids(
            "linux.azahar", "linux.azaharplus", "linux.lime3ds", "linux.citra", "linux.mandarine", "linux.panda3ds", LRA, LRAS,
        ),
        listOf("gb", "gbc", "gba") to ids(LRA, "linux.mgba", "linux.mednafen", LRAS),
        listOf("ngc", "wii") to ids("linux.dolphin", LRA, LRAS),
        listOf("wiiu") to ids("linux.cemu", LRA, LRAS),
        listOf("switch") to ids("linux.eden", "linux.ryujinx", "linux.citron"),
        listOf("dc") to ids("linux.flycast", "linux.redream", LRA, LRAS),
        listOf("arcade") to ids(LRA, "linux.mame", "linux.flycast", LRAS),
        listOf("neogeoaes") to ids(LRA, "linux.mame", LRAS),
        listOf("xbox") to ids("linux.xemu"),
        listOf("xbox360") to ids("linux.xenia"),
        listOf("steam") to ids("linux.steam", "linux.steam-url", "linux.desktop"),
        listOf("win") to ids("linux.desktop", "linux.steam", "linux.steam-url", "linux.script"),
        listOf("dos") to ids(LRA, "linux.dosbox-staging", "linux.dosbox-x", LRAS),
        listOf("scummvm") to ids("linux.scummvm", LRA, LRAS),
        listOf(
            "nes", "famicom", "fds", "snes", "sfam", "virtualboy", "saturn", "genesis", "sms", "gamegear", "lynx",
            "neo-geo-pocket", "neo-geo-pocket-color", "wonderswan", "wonderswan-color", "tg16", "turbografx-cd",
        ) to ids(LRA, "linux.mednafen", LRAS),
        listOf(
            "pokemon-mini", "segacd", "sega32", "sg1000", "neo-geo-cd", "atari2600", "atari5200", "atari7800", "jaguar",
            "msx", "c64", "amiga", "3do", "colecovision", "intellivision", "vectrex",
        ) to ids(LRA, LRAS),
    )

    /**
     * Linux's order with each host's ids, keeping the emulators that host has; [own] replaces the
     * lists for platforms the host runs differently (PC games, Steam).
     */
    private fun port(prefix: String, has: Set<String>, own: Map<PlatformId, List<EmulatorId>>): Map<PlatformId, List<EmulatorId>> =
        linux.mapValues { (_, list) ->
            list.map { EmulatorId(prefix + "." + it.value.removePrefix("linux.")) }.filter { it.value in has }
        }.filterValues { it.isNotEmpty() } + own

    val windows: Map<PlatformId, List<EmulatorId>> by lazy {
        port(
            "windows",
            WindowsCatalog.defs.map { it.def.id }.toSet(),
            expand(
                listOf("win") to ids("windows.shortcut", "windows.steam"),
                listOf("steam") to ids("windows.steam", "windows.shortcut"),
            ),
        )
    }

    val macos: Map<PlatformId, List<EmulatorId>> by lazy {
        port(
            "macos",
            MacCatalog.defs.map { it.def.id }.toSet(),
            expand(
                listOf("steam") to ids("macos.steam-url", "macos.open"),
                listOf("win") to ids("macos.open", "macos.steam-url"),
            ),
        )
    }

    /** The order for [host]: Android's own, Linux's, or Linux's carried over to Windows and macOS. */
    fun map(host: Host): Map<PlatformId, List<EmulatorId>> = when (host) {
        Host.ANDROID -> android
        Host.LINUX -> linux
        Host.WINDOWS -> windows
        Host.MACOS -> macos
    }

    fun forPlatform(host: Host, platform: PlatformId): List<EmulatorId> = map(host)[platform].orEmpty()
}
