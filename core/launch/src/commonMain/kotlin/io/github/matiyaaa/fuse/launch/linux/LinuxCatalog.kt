package io.github.matiyaaa.fuse.launch.linux

import io.github.matiyaaa.fuse.launch.Confidence.UNVERIFIED
import io.github.matiyaaa.fuse.launch.Confidence.VERIFIED_ESDE
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchMode
import io.github.matiyaaa.fuse.launch.Platforms
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.TargetKind
import io.github.matiyaaa.fuse.launch.TitleIdMode
import io.github.matiyaaa.fuse.launch.platforms
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.ContentSupport
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Linux emulators. Program names, Flatpak ids and AppImage names come from ES-DE's Linux
 * es_find_rules.xml, and every argument from its es_systems.xml (master [Sources.ESDE_COMMIT], MIT),
 * unless an entry's source says otherwise.
 */
object LinuxCatalog {
    private val PS_TITLE_ID = Regex("[A-Z]{4}[0-9]{5}")
    private val STEAM_ID = Regex("[1-9][0-9]*")
    private val N3DS = platforms("3ds", "new-nintendo-3ds")

    private fun esde(rule: String) = "${Sources.ESDE_LINUX}: $rule"

    private fun spec(args: Array<out String>, workingDir: String?) = LinuxCommandSpec(args.toList(), workingDir)

    private fun file(vararg args: String, ext: Set<String>? = null, workingDir: String? = null, platforms: Set<PlatformId>? = null, label: String = "File") =
        LaunchMode(label, TargetKind.FILE, spec(args, workingDir), extensions = ext, platforms = platforms)

    private fun idFile(vararg args: String, ext: Set<String>, pattern: Regex? = null, label: String = "Id file") =
        LaunchMode(label, TargetKind.FILE, spec(args, null), extensions = ext, idFile = true, idPattern = pattern)

    private fun titleId(vararg args: String, pattern: Regex? = null) =
        LaunchMode("Title id", TargetKind.TITLE_ID, spec(args, null), idPattern = pattern)

    private fun dir(vararg args: String) = LaunchMode("Directory", TargetKind.DIRECTORY, spec(args, null))

    private fun detect(
        bin: List<String> = emptyList(),
        flatpak: List<String> = emptyList(),
        appImage: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        dirBin: List<String> = emptyList(),
        flatpakCommand: String? = null,
        required: List<String> = emptyList(),
    ) = LinuxDetection(bin, flatpak, flatpakCommand, appImage, exclude, dirBin, required)

    private fun caps(
        folders: FolderSupport = FolderSupport.RESOLVES_FILE,
        installed: Boolean = false,
        playlists: Boolean = false,
    ) = AdapterCapabilities(
        folders = folders,
        dlc = if (installed) ContentSupport.INSTALL_IN_EMULATOR else ContentSupport.NOT_APPLICABLE,
        updates = if (installed) ContentSupport.INSTALL_IN_EMULATOR else ContentSupport.NOT_APPLICABLE,
        playlists = playlists,
    )

    /** Mednafen `-force_module` per platform (ES-DE Linux Mednafen commands). */
    private val mednafenModules: Map<String, String> = mapOf(
        "psx" to "psx", "saturn" to "ss", "virtualboy" to "vb", "lynx" to "lynx",
        "neo-geo-pocket" to "ngp", "neo-geo-pocket-color" to "ngp", "wonderswan" to "wswan", "wonderswan-color" to "wswan",
        "tg16" to "pce", "turbografx-cd" to "pce", "gb" to "gb", "gbc" to "gb", "gba" to "gba",
        "nes" to "nes", "famicom" to "nes", "fds" to "nes", "snes" to "snes", "sfam" to "snes",
        "genesis" to "md", "sms" to "sms", "gamegear" to "gg",
    )

    private const val STEAM_RETROARCH_APP = "1118310"

    val defs: List<LinuxEmulatorDef> = listOf(
        LinuxEmulatorDef(
            id = "linux.retroarch", name = "RetroArch",
            platforms = RetroArchCores.linux.keys,
            detection = detect(bin = listOf("retroarch"), flatpak = listOf("org.libretro.RetroArch"), appImage = listOf("*retroarch*.appimage")),
            modes = listOf(file("-L", "{CORE_PATH}", "{ROM}")),
            source = esde("RETROARCH rule and core paths; %EMULATOR_RETROARCH% -L %CORE_RETROARCH%/<core>_libretro.so %ROM%"),
            confidence = VERIFIED_ESDE, homepage = "https://www.retroarch.com/",
            capabilities = caps(playlists = true),
            limitations = listOf("The core must be installed (Online Updater, or your distribution's libretro packages)."),
            usesRetroArchCores = true,
        ),
        LinuxEmulatorDef(
            id = "linux.retroarch-steam", name = "RetroArch (Steam)",
            platforms = RetroArchCores.linux.keys,
            detection = detect(
                bin = listOf("steam"), flatpak = listOf("com.valvesoftware.Steam"),
                required = listOf(
                    ".local/share/Steam/steamapps/appmanifest_$STEAM_RETROARCH_APP.acf",
                    ".steam/steam/steamapps/appmanifest_$STEAM_RETROARCH_APP.acf",
                    ".var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/appmanifest_$STEAM_RETROARCH_APP.acf",
                ),
            ),
            modes = listOf(file("-applaunch", STEAM_RETROARCH_APP, "-L", "{CORE}_libretro", "{ROM}")),
            source = "ES-DE USERGUIDE, RetroArch on Steam: %EMULATOR_STEAM% -applaunch 1118310 -L <core>_libretro %ROM%; " +
                "detection by Steam's app manifest for 1118310 is Fuse's own",
            confidence = VERIFIED_ESDE, homepage = "https://store.steampowered.com/app/1118310/",
            capabilities = caps(playlists = true),
            limitations = listOf(
                "ES-DE does not officially support this: Steam stays in charge, its overlay can steal focus, and a missing core fails silently.",
            ),
            usesRetroArchCores = true,
        ),
        LinuxEmulatorDef(
            id = "linux.pcsx2", name = "PCSX2", platforms = platforms("ps2"),
            detection = detect(bin = listOf("pcsx2-qt", "pcsx2"), flatpak = listOf("net.pcsx2.PCSX2"), appImage = listOf("*pcsx2*.appimage")),
            modes = listOf(file("-batch", "{ROM}")),
            source = esde("PCSX2"), confidence = VERIFIED_ESDE, homepage = "https://pcsx2.net/",
        ),
        LinuxEmulatorDef(
            id = "linux.play", name = "Play!", platforms = platforms("ps2"),
            detection = detect(flatpak = listOf("org.purei.Play"), appImage = listOf("play!*.appimage")),
            modes = listOf(file("--fullscreen", "--disc", "{ROM}")),
            source = esde("PLAY!"), confidence = VERIFIED_ESDE, homepage = "https://purei.org/",
        ),
        LinuxEmulatorDef(
            id = "linux.duckstation", name = "DuckStation", platforms = platforms("psx"),
            detection = detect(
                bin = listOf("duckstation-nogui", "duckstation-qt", "duckstation"),
                flatpak = listOf("org.duckstation.DuckStation"), appImage = listOf("*duckstation*.appimage"),
            ),
            modes = listOf(file("-batch", "{ROM}")),
            source = esde("DUCKSTATION"), confidence = VERIFIED_ESDE, homepage = "https://www.duckstation.org/",
            capabilities = caps(playlists = true),
        ),
        LinuxEmulatorDef(
            id = "linux.ppsspp", name = "PPSSPP", platforms = platforms("psp"),
            detection = detect(bin = listOf("ppsspp", "PPSSPPSDL", "PPSSPPQt"), flatpak = listOf("org.ppsspp.PPSSPP"), appImage = listOf("*ppsspp*.appimage")),
            modes = listOf(file("{ROM}")),
            source = esde("PPSSPP"), confidence = VERIFIED_ESDE, homepage = "https://www.ppsspp.org/",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.dolphin", name = "Dolphin", platforms = platforms("ngc", "wii"),
            detection = detect(
                bin = listOf("dolphin-emu"), flatpak = listOf("org.DolphinEmu.dolphin-emu"),
                appImage = listOf("dolphin_emulator*.appimage", "dolphin-emu*.appimage"),
            ),
            modes = listOf(file("-b", "-e", "{ROM}")),
            source = esde("DOLPHIN"), confidence = VERIFIED_ESDE, homepage = "https://dolphin-emu.org/",
        ),
        LinuxEmulatorDef(
            id = "linux.cemu", name = "Cemu", platforms = platforms("wiiu"),
            detection = detect(
                bin = listOf("cemu", "Cemu"), flatpak = listOf("info.cemu.Cemu"),
                appImage = listOf("cemu*.appimage"), dirBin = listOf("Cemu/Cemu"),
            ),
            modes = listOf(file("-g", "{ROM}")),
            source = esde("CEMU"), confidence = VERIFIED_ESDE, homepage = "https://cemu.info/",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.rpcs3", name = "RPCS3", platforms = platforms("ps3"),
            detection = detect(bin = listOf("rpcs3"), flatpak = listOf("net.rpcs3.RPCS3"), appImage = listOf("rpcs3*.appimage")),
            modes = listOf(
                idFile("--no-gui", "%RPCS3_GAMEID%:{SERIAL}", ext = setOf("ps3"), pattern = PS_TITLE_ID, label = "Game Serial"),
                titleId("--no-gui", "%RPCS3_GAMEID%:{SERIAL}", pattern = PS_TITLE_ID),
                file("--no-gui", "{ROM}", ext = setOf("iso"), label = "ISO"),
                dir("--no-gui", "{ROM}"),
            ),
            source = esde("RPCS3, commands RPCS3 Directory / ISO / Game Serial") +
                "; RPCS3 rpcs3.cpp (--no-gui) and Emu/System.h (\"%RPCS3_GAMEID%:\" boot prefix)",
            confidence = VERIFIED_ESDE, homepage = "https://rpcs3.net/",
            capabilities = caps(folders = FolderSupport.DIRECTORY, installed = true),
            titleIdMode = TitleIdMode.OPTIONAL,
            installHint = "File > Install Packages/Raps/Edats",
            limitations = listOf("Folder games are passed as the game folder (disc structure); a .ps3 file with a serial starts an installed game."),
        ),
        LinuxEmulatorDef(
            id = "linux.eden", name = "Eden", platforms = platforms("switch"),
            detection = detect(bin = listOf("eden"), flatpak = listOf("dev.eden_emu.eden"), appImage = listOf("eden*.appimage")),
            modes = listOf(file("-f", "-g", "{ROM}", ext = setOf("nca", "nro", "nso", "nsp", "xci"))),
            source = esde("EDEN"), confidence = VERIFIED_ESDE, homepage = "https://eden-emu.dev/",
            capabilities = caps(installed = true), installHint = "Install to NAND",
        ),
        LinuxEmulatorDef(
            id = "linux.ryujinx", name = "Ryujinx", platforms = platforms("switch"),
            detection = detect(
                bin = listOf("Ryujinx", "Ryujinx.Ava", "ryujinx"),
                flatpak = listOf("io.github.ryubing.Ryujinx", "org.ryujinx.Ryujinx"),
                appImage = listOf("*yujinx*.appimage"), dirBin = listOf("publish/Ryujinx", "publish/Ryujinx.Ava"),
            ),
            modes = listOf(file("{ROM}", ext = setOf("nca", "nro", "nso", "nsp", "xci"))),
            source = esde("RYUJINX"), confidence = VERIFIED_ESDE, homepage = "https://ryujinx.app/",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.citron", name = "Citron", platforms = platforms("switch"),
            detection = detect(bin = listOf("citron"), appImage = listOf("citron*.appimage")),
            modes = emptyList(),
            source = "Not in ES-DE; command line not verified", confidence = UNVERIFIED,
            capabilities = caps(installed = true),
            openAppOnlyReason = "Citron's command line is not verified, so Fuse only opens it.",
        ),
        LinuxEmulatorDef(
            id = "linux.azahar", name = "Azahar", platforms = N3DS,
            detection = detect(
                bin = listOf("azahar"), flatpak = listOf("org.azahar_emu.Azahar"),
                appImage = listOf("azahar*.appimage"), exclude = listOf("azaharplus*"),
            ),
            modes = listOf(file("{ROM}")),
            source = esde("AZAHAR"), confidence = VERIFIED_ESDE, homepage = "https://azahar-emu.org/",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.azaharplus", name = "AzaharPlus", platforms = N3DS,
            detection = detect(appImage = listOf("azaharplus*.appimage")),
            modes = listOf(file("{ROM}")),
            source = esde("AZAHARPLUS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/AzaharPlus/AzaharPlus",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.lime3ds", name = "Lime3DS", platforms = N3DS,
            detection = detect(
                bin = listOf("lime3ds", "lime3ds-gui", "lime-qt"), flatpak = listOf("io.github.lime3ds.Lime3DS"),
                appImage = listOf("lime3ds*.appimage", "lime-qt*.appimage"),
            ),
            modes = listOf(file("{ROM}")),
            source = esde("LIME3DS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/Lime3DS/lime3ds-archive",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.citra", name = "Citra", platforms = N3DS,
            detection = detect(bin = listOf("citra"), flatpak = listOf("org.citra_emu.citra"), appImage = listOf("citra-qt*.appimage")),
            modes = listOf(file("{ROM}")),
            source = esde("CITRA"), confidence = VERIFIED_ESDE, homepage = "https://github.com/PabloMK7/citra",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.mandarine", name = "Mandarine", platforms = N3DS,
            detection = detect(bin = listOf("mandarine-qt"), appImage = listOf("mandarine-qt*.appimage")),
            modes = listOf(file("{ROM}")),
            source = esde("MANDARINE"), confidence = VERIFIED_ESDE, homepage = "https://github.com/mandarine3ds/mandarine",
            capabilities = caps(installed = true),
        ),
        LinuxEmulatorDef(
            id = "linux.panda3ds", name = "Panda3DS", platforms = N3DS,
            detection = detect(bin = listOf("panda3ds"), appImage = listOf("alber-*.appimage")),
            modes = listOf(file("{ROM}")),
            source = esde("PANDA3DS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/wheremyfoodat/Panda3DS",
        ),
        LinuxEmulatorDef(
            id = "linux.melonds", name = "melonDS", platforms = platforms("nds", "nintendo-dsi"),
            detection = detect(bin = listOf("melonDS", "melonds"), flatpak = listOf("net.kuribo64.melonDS"), appImage = listOf("melonds*.appimage")),
            modes = listOf(file("-f", "{ROM}")),
            source = esde("MELONDS"), confidence = VERIFIED_ESDE, homepage = "https://melonds.kuribo64.net/",
        ),
        LinuxEmulatorDef(
            id = "linux.mgba", name = "mGBA", platforms = platforms("gb", "gbc", "gba"),
            detection = detect(bin = listOf("mgba", "mgba-qt"), flatpak = listOf("io.mgba.mGBA"), appImage = listOf("mgba*.appimage")),
            modes = listOf(file("-f", "{ROM}")),
            source = esde("MGBA"), confidence = VERIFIED_ESDE, homepage = "https://mgba.io/",
        ),
        LinuxEmulatorDef(
            id = "linux.mupen64plus", name = "Mupen64Plus (m64p)", platforms = platforms("n64"),
            detection = detect(bin = listOf("m64p"), flatpak = listOf("io.github.m64p.m64p")),
            modes = listOf(file("--nogui", "{ROM}")),
            source = esde("MUPEN64PLUS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/loganmc10/m64p",
        ),
        LinuxEmulatorDef(
            id = "linux.flycast", name = "Flycast", platforms = platforms("dc", "arcade"),
            detection = detect(bin = listOf("flycast"), flatpak = listOf("org.flycast.Flycast"), appImage = listOf("flycast*.appimage")),
            modes = listOf(file("{ROM}")),
            source = esde("FLYCAST"), confidence = VERIFIED_ESDE, homepage = "https://github.com/flyinghead/flycast",
            limitations = listOf("Arcade support covers NAOMI and Atomiswave sets only."),
        ),
        LinuxEmulatorDef(
            id = "linux.redream", name = "Redream", platforms = platforms("dc"),
            detection = detect(bin = listOf("redream"), dirBin = listOf("redream/redream")),
            modes = listOf(file("{ROM}")),
            source = esde("REDREAM"), confidence = VERIFIED_ESDE, homepage = "https://redream.io/",
        ),
        LinuxEmulatorDef(
            id = "linux.vita3k", name = "Vita3K", platforms = platforms("psvita"),
            detection = detect(bin = listOf("vita3k", "Vita3K"), appImage = listOf("vita3k*.appimage"), dirBin = listOf("Vita3K/Vita3K")),
            modes = listOf(idFile("-r", "{SERIAL}", ext = setOf("psvita"), pattern = PS_TITLE_ID), titleId("-r", "{SERIAL}", pattern = PS_TITLE_ID)),
            source = esde("VITA3K"), confidence = VERIFIED_ESDE, homepage = "https://vita3k.org/",
            capabilities = caps(folders = FolderSupport.NONE, installed = true),
            titleIdMode = TitleIdMode.REQUIRED,
            limitations = listOf("Games must be installed in Vita3K first; Fuse starts them by title id."),
        ),
        LinuxEmulatorDef(
            id = "linux.xemu", name = "xemu", platforms = platforms("xbox"),
            detection = detect(bin = listOf("xemu"), flatpak = listOf("app.xemu.xemu"), appImage = listOf("xemu*.appimage")),
            modes = listOf(file("-dvd_path", "{ROM}")),
            source = esde("XEMU"), confidence = VERIFIED_ESDE, homepage = "https://xemu.app/",
        ),
        LinuxEmulatorDef(
            id = "linux.xenia", name = "Xenia", platforms = platforms("xbox360"),
            detection = detect(
                bin = listOf("xenia", "xenia_canary"),
                appImage = listOf("xenia_canary*.appimage", "xenia-canary*.appimage"),
                dirBin = listOf("xenia/xenia", "xenia/xenia_canary"),
            ),
            modes = listOf(file("{ROM}", workingDir = "{EMUDIR}")),
            source = esde("XENIA"), confidence = VERIFIED_ESDE, homepage = "https://xenia.jp/",
        ),
        LinuxEmulatorDef(
            id = "linux.shadps4", name = "shadPS4", platforms = platforms("ps4"),
            detection = detect(
                bin = listOf("shadps4"), appImage = listOf("shadps4-qt*.appimage", "shadps4-sdl*.appimage"),
                dirBin = listOf("shadps4/shadps4"),
            ),
            modes = listOf(
                idFile("-g", "{SERIAL}", ext = setOf("ps4"), pattern = PS_TITLE_ID, label = "Game Serial"),
                titleId("-g", "{SERIAL}", pattern = PS_TITLE_ID),
                file("{ROM}", ext = setOf("bin"), label = "eboot.bin"),
            ),
            source = esde("SHADPS4, commands shadPS4 Game Serial / eboot.bin"), confidence = VERIFIED_ESDE,
            homepage = "https://shadps4.net/",
            capabilities = caps(installed = true), titleIdMode = TitleIdMode.OPTIONAL,
        ),
        LinuxEmulatorDef(
            id = "linux.mednafen", name = "Mednafen",
            platforms = mednafenModules.keys.map(::PlatformId).toSet(),
            detection = detect(bin = listOf("mednafen"), flatpak = listOf("com.github.AmatCoder.mednaffe"), flatpakCommand = "mednafen"),
            modes = mednafenModules.map { (p, module) -> file("-force_module", module, "{ROM}", platforms = platforms(p)) },
            source = esde("MEDNAFEN (-force_module per system; Flatpak via Mednaffe --command=mednafen)"),
            confidence = VERIFIED_ESDE, homepage = "https://mednafen.github.io/",
            capabilities = caps(playlists = true),
        ),
        LinuxEmulatorDef(
            id = "linux.mame", name = "MAME", platforms = platforms("arcade", "neogeoaes"),
            detection = detect(bin = listOf("mame"), flatpak = listOf("org.mamedev.MAME"), appImage = listOf("mame*.appimage")),
            modes = listOf(file("-rompath", "{ROMDIR}", "{BASENAME}", workingDir = "~/.mame")),
            source = esde("MAME, arcade/neogeo commands (-rompath uses the game's own folder; ES-DE also appends <ROMs>/<system>)"),
            confidence = VERIFIED_ESDE, homepage = "https://www.mamedev.org/",
        ),
        LinuxEmulatorDef(
            id = "linux.scummvm", name = "ScummVM", platforms = platforms("scummvm"),
            detection = detect(bin = listOf("scummvm"), flatpak = listOf("org.scummvm.ScummVM")),
            modes = listOf(file("{BASENAME}", ext = setOf("scummvm", "svm"), workingDir = "{ROMDIR}")),
            source = esde("SCUMMVM"), confidence = VERIFIED_ESDE, homepage = "https://www.scummvm.org/",
            capabilities = caps(folders = FolderSupport.RESOLVES_FILE),
            limitations = listOf("The .scummvm file must be named after the game's ScummVM short name (ES-DE)."),
        ),
        LinuxEmulatorDef(
            id = "linux.dosbox-staging", name = "DOSBox Staging", platforms = platforms("dos"),
            detection = detect(bin = listOf("dosbox-staging"), flatpak = listOf("io.github.dosbox-staging")),
            modes = listOf(file("{ROM}", workingDir = "{ROMDIR}")),
            source = esde("DOSBOX-STAGING"), confidence = VERIFIED_ESDE, homepage = "https://www.dosbox-staging.org/",
        ),
        LinuxEmulatorDef(
            id = "linux.dosbox-x", name = "DOSBox-X", platforms = platforms("dos"),
            detection = detect(bin = listOf("dosbox-x"), flatpak = listOf("com.dosbox_x.DOSBox-X")),
            modes = listOf(file("{ROM}", workingDir = "{ROMDIR}")),
            source = esde("DOSBOX-X"), confidence = VERIFIED_ESDE, homepage = "https://dosbox-x.com/",
        ),
        LinuxEmulatorDef(
            id = "linux.steam", name = "Steam", platforms = platforms("steam", "win"),
            detection = detect(bin = listOf("steam"), flatpak = listOf("com.valvesoftware.Steam")),
            modes = listOf(idFile("-applaunch", "{INJECT}", ext = setOf("steam"), pattern = STEAM_ID, label = "Steam id")),
            source = "ES-DE USERGUIDE (%EMULATOR_STEAM% -applaunch <appid>) and ${esde("STEAM rule")}; Flatpak id from Flathub",
            confidence = VERIFIED_ESDE, homepage = "https://store.steampowered.com/about/",
            capabilities = caps(folders = FolderSupport.NONE),
        ),
        LinuxEmulatorDef(
            id = "linux.steam-url", name = "Steam (link)", platforms = platforms("steam", "win"),
            detection = detect(bin = listOf("xdg-open")),
            modes = listOf(idFile("steam://rungameid/{INJECT}", ext = setOf("steam"), pattern = STEAM_ID, label = "Steam id")),
            source = "Steam's own shortcuts open steam://rungameid/<id> (ES-DE INSTALL.md steam import rule execFilter); xdg-open(1)",
            confidence = VERIFIED_ESDE, homepage = "https://store.steampowered.com/about/",
            capabilities = caps(folders = FolderSupport.NONE),
            limitations = listOf("Opens the Steam link with the desktop's default handler."),
        ),
        LinuxEmulatorDef(
            id = "linux.script", name = "Shell script", platforms = Platforms.all.toSet(),
            detection = detect(bin = listOf("bash", "sh")),
            modes = listOf(file("{ROM}", ext = setOf("sh"), workingDir = "{ROMDIR}")),
            source = esde("OS-SHELL rule; 'Shortcut or script' command %STARTDIR%=%GAMEDIR% %EMULATOR_OS-SHELL% %ROM%"),
            confidence = VERIFIED_ESDE,
            capabilities = caps(folders = FolderSupport.NONE),
            shortcutsOnly = true,
        ),
    )

    /** Adapters for every entry plus the built-in `.desktop` shortcut adapter. */
    val adapters: List<EmulatorAdapter> = defs.map(::LinuxCommandAdapter) + DesktopShortcutAdapter

    fun def(id: String): LinuxEmulatorDef? = defs.firstOrNull { it.id == id }
}
