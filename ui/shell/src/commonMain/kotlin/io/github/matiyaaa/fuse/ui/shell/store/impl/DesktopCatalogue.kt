package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.ui.shell.store.InFuse

/**
 * A program the computer's Store lists: where its releases are (one GitHub repository, or one per
 * system in [repos]), what it plays, and which of Fuse's emulators it is ([emulator], the id without
 * the system's prefix, as in "duckstation"). [page] is set for programs whose builds aren't on
 * GitHub: the Store shows them and sends you to their download page.
 */
internal data class DesktopEntry(
    val key: String,
    val name: String,
    val author: String,
    val about: String,
    val category: String,
    val systems: List<String>,
    val repo: String? = null,
    val repos: Map<Host, String> = emptyMap(),
    val emulator: String? = null,
    val page: String? = null,
    /** Rolling projects that only publish pre-releases ("continuous", "latest"). */
    val prereleases: Boolean = false,
    val inFuse: InFuse = InFuse.LAUNCHES_GAMES,
    /** The systems it is offered on, where its builds exist for only some; null: every computer. */
    val hosts: Set<Host>? = null,
    /** Its repository on a Forgejo or Gitea server of its own (Eden's), where GitHub isn't used. */
    val forge: String? = null,
    /** Builds on a plain download listing, per system, where there are no releases to read. */
    val listings: Map<Host, Listing> = emptyMap(),
    /** On Linux, its Flathub id, for a program that publishes no build of its own to fetch. */
    val flathub: String? = null,
    /** Which of a release's files is for this computer, where the plain rules can't tell. */
    val pick: ((Host, arm: Boolean, handheld: String?) -> Regex?)? = null,
    /**
     * The emulator it is a build of, where several carry one on ("yuzu" for Eden and Citron, "Citra"
     * for Azahar and its forks), so each one's page offers the others.
     */
    val family: String? = null,
) {
    fun repoFor(host: Host): String? = repos[host] ?: repo

    /** How [host] gets it: its own build (GitHub, its forge or a listing), Flathub, or not at all (its page). */
    fun sourceFor(host: Host): Source? = when {
        repoFor(host) != null -> Source.GITHUB
        forge != null -> Source.FORGE
        host in listings -> Source.LISTING
        flathub != null && host == Host.LINUX -> Source.FLATHUB
        else -> null
    }

    enum class Source { GITHUB, FORGE, LISTING, FLATHUB }
}

/**
 * A download listing: [page], the links on it whose last part [pattern] matches (its first group
 * the version), the newest version taken, and [then], the file below a version's folder.
 */
internal data class Listing(val page: String, val pattern: Regex, val then: String? = null)

/**
 * The computer's Store: emulators and companions that publish builds for Linux (AppImages),
 * Windows (portable zips) and macOS (disk images or zipped apps) on their own GitHub releases.
 * Fuse fetches those files as they are; the programs and their builds are their developers'.
 */
internal object DesktopCatalogue {
    const val EMULATORS = "Emulators"
    const val STREAMING = "Streaming"
    const val TOOLS = "Tools"

    /** Category colours (ARGB), matching the Android Store's feel. */
    val colors: Map<String, Long> = mapOf(
        EMULATORS to 0xFF5B8DEF,
        STREAMING to 0xFF8E6CEF,
        TOOLS to 0xFF4FB39A,
        io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps.COMPANIONS to 0xFFE0702E,
        io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps.OTHER to 0xFF8A93A6,
    )

    val entries: List<DesktopEntry> = listOf(
        DesktopEntry(
            "duckstation", "DuckStation", "Stenzek", "PlayStation, fast and accurate, with upscaling and texture replacement.",
            EMULATORS, listOf("psx"), repo = "https://github.com/stenzek/duckstation", emulator = "duckstation", prereleases = true,
        ),
        DesktopEntry(
            "pcsx2", "PCSX2", "PCSX2 team", "PlayStation 2, with widescreen patches and upscaling.",
            EMULATORS, listOf("ps2"), repo = "https://github.com/PCSX2/pcsx2", emulator = "pcsx2",
        ),
        DesktopEntry(
            "rpcs3", "RPCS3", "RPCS3 team", "PlayStation 3. Needs the PS3 firmware from Sony's site, installed in RPCS3.",
            EMULATORS, listOf("ps3"),
            repos = mapOf(
                Host.LINUX to "https://github.com/RPCS3/rpcs3-binaries-linux",
                Host.WINDOWS to "https://github.com/RPCS3/rpcs3-binaries-win",
                Host.MACOS to "https://github.com/RPCS3/rpcs3-binaries-mac",
            ),
            emulator = "rpcs3",
        ),
        DesktopEntry(
            "shadps4", "shadPS4", "shadPS4 team", "PlayStation 4, early but growing fast.",
            EMULATORS, listOf("ps4"), repo = "https://github.com/shadps4-emu/shadPS4", emulator = "shadps4",
        ),
        DesktopEntry(
            "vita3k", "Vita3K", "Vita3K team", "PlayStation Vita. Games are installed into it with their licences.",
            EMULATORS, listOf("psvita"), repo = "https://github.com/Vita3K/Vita3K", emulator = "vita3k", prereleases = true,
        ),
        DesktopEntry(
            "cemu", "Cemu", "Cemu team", "Wii U, with graphic packs for higher resolutions and frame rates.",
            EMULATORS, listOf("wiiu"), repo = "https://github.com/cemu-project/Cemu", emulator = "cemu",
        ),
        DesktopEntry(
            "azahar", "Azahar", "Azahar team", "Nintendo 3DS, carrying on Citra and Lime3DS.",
            EMULATORS, listOf("3ds"), repo = "https://github.com/azahar-emu/azahar", emulator = "azahar",
            family = "Citra",
        ),
        DesktopEntry(
            "melonds", "melonDS", "Arisotura", "Nintendo DS and DSi, with local wireless play.",
            EMULATORS, listOf("nds"), repo = "https://github.com/melonDS-emu/melonDS", emulator = "melonds",
        ),
        DesktopEntry(
            "mgba", "mGBA", "endrift", "Game Boy Advance, Game Boy and Game Boy Color.",
            EMULATORS, listOf("gba", "gb", "gbc"), repo = "https://github.com/mgba-emu/mgba", emulator = "mgba",
        ),
        DesktopEntry(
            "flycast", "Flycast", "flyinghead", "Dreamcast, NAOMI and Atomiswave.",
            EMULATORS, listOf("dreamcast"), repo = "https://github.com/flyinghead/flycast", emulator = "flycast",
        ),
        DesktopEntry(
            "xemu", "xemu", "xemu team", "The original Xbox. Needs its BIOS and hard disk image.",
            EMULATORS, listOf("xbox"), repo = "https://github.com/xemu-project/xemu", emulator = "xemu",
        ),
        DesktopEntry(
            // Windows takes xenia_canary_windows.zip, Linux xenia_canary_linux.tar.xz; there is no macOS build.
            "xenia", "Xenia Canary", "Xenia Canary team", "Xbox 360, the Canary branch that runs most games.",
            EMULATORS, listOf("xbox360"), repo = "https://github.com/xenia-canary/xenia-canary-releases", emulator = "xenia",
            prereleases = true, hosts = setOf(Host.WINDOWS, Host.LINUX),
        ),
        DesktopEntry(
            // Linux: the AppImage its releases carry (PPSSPP-v…-anylinux-x86_64.AppImage).
            "ppsspp", "PPSSPP", "Henrik Rydgård", "PlayStation Portable, with upscaling and its own save states.",
            EMULATORS, listOf("psp"), repos = mapOf(Host.LINUX to "https://github.com/hrydgard/ppsspp"), emulator = "ppsspp",
            page = "https://www.ppsspp.org/download/",
        ),
        DesktopEntry(
            // Linux: the official build as an AppImage, made weekly by pkgforge-dev (Dolphin_Emulator-…-anylinux-x86_64.AppImage).
            "dolphin", "Dolphin", "Dolphin team", "GameCube and Wii, in high resolution, with its own netplay.",
            EMULATORS, listOf("gc", "wii"), repos = mapOf(Host.LINUX to "https://github.com/pkgforge-dev/Dolphin-emu-AppImage"), emulator = "dolphin",
            page = "https://dolphin-emu.org/download/",
        ),
        DesktopEntry(
            // Linux: the newest stable build on Libretro's build server, a 7-Zip archive holding the AppImage.
            "retroarch", "RetroArch", "Libretro", "Many systems through its cores.",
            EMULATORS, emptyList(), emulator = "retroarch", page = "https://www.retroarch.com/?page=platforms",
            listings = mapOf(Host.LINUX to Listing("https://buildbot.libretro.com/stable/", Regex("^(\\d+(?:\\.\\d+)+)$"), then = "linux/x86_64/RetroArch.7z")),
        ),
        DesktopEntry(
            // Eden keeps its own Forgejo (its GitHub was taken down), with builds of its own for the Steam
            // Deck and the ROG Ally.
            "eden", "Eden", "Eden team", "Nintendo Switch, carrying on yuzu, with its own builds for the Steam Deck and the ROG Ally.",
            EMULATORS, listOf("switch"), emulator = "eden", forge = "https://git.eden-emu.dev/eden-emu/eden",
            pick = { host, arm, handheld ->
                // Its files carry the version: Eden-Linux-v0.2.1-amd64-clang-pgo.AppImage, Eden-Windows-v0.2.1-amd64-clang-pgo.zip.
                val build = when {
                    handheld == "steamdeck" && host == Host.LINUX -> "steamdeck"
                    handheld == "rog-ally" -> "rog-ally"
                    arm -> if (host == Host.WINDOWS) "arm64" else "aarch64"
                    else -> "amd64"
                }
                when (host) {
                    Host.LINUX -> Regex("^Eden-Linux-(v[^-]+-)?$build-clang-pgo\\.AppImage$")
                    Host.WINDOWS -> Regex("^Eden-Windows-(v[^-]+-)?$build-clang-pgo\\.zip$")
                    else -> null
                }
            },
            family = "yuzu",
        ),
        DesktopEntry(
            "ryujinx", "Ryujinx", "Ryubing", "Nintendo Switch, the Ryubing continuation of Ryujinx.",
            EMULATORS, listOf("switch"), emulator = "ryujinx", flathub = "io.github.ryubing.Ryujinx", hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "citron", "Citron", "Citron Neo team", "Nintendo Switch, a yuzu fork. Nightly builds.",
            EMULATORS, listOf("switch"), repo = "https://github.com/citron-neo/CI", emulator = "citron", prereleases = true, hosts = setOf(Host.LINUX),
            family = "yuzu",
        ),
        DesktopEntry(
            "azaharplus", "AzaharPlus", "AzaharPlus team", "Nintendo 3DS, Azahar with extra features.",
            EMULATORS, listOf("3ds"), repo = "https://github.com/AzaharPlus/AzaharPlus", emulator = "azaharplus", hosts = setOf(Host.LINUX),
            family = "Citra",
        ),
        DesktopEntry(
            // Its Linux build is a tarball holding the AppImage (mandarine-linux-appimage-….tar.gz).
            "mandarine", "Mandarine", "Mandarine team", "Nintendo 3DS, a Citra fork.",
            EMULATORS, listOf("3ds"), repo = "https://github.com/mandarine3ds/mandarine", emulator = "mandarine", hosts = setOf(Host.LINUX),
            family = "Citra",
        ),
        DesktopEntry(
            // Linux-Qt.zip holds the Qt AppImage.
            "panda3ds", "Panda3DS", "wheremyfoodat", "Nintendo 3DS, a young emulator built from scratch.",
            EMULATORS, listOf("3ds"), repo = "https://github.com/wheremyfoodat/Panda3DS", emulator = "panda3ds", hosts = setOf(Host.LINUX),
            pick = { host, _, _ -> if (host == Host.LINUX) Regex("Linux-Qt") else null },
        ),
        DesktopEntry(
            "play", "Play!", "Jean-Philip Desjardins", "PlayStation 2, a lighter emulator than PCSX2.",
            EMULATORS, listOf("ps2"), emulator = "play", flathub = "org.purei.Play", hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            // A tarball of the program's folder (redream.x86_64-linux-v….tar.gz) on its own download page.
            "redream", "Redream", "Redream", "Dreamcast, simple and fast.",
            EMULATORS, listOf("dreamcast"), emulator = "redream", page = "https://redream.io/download", hosts = setOf(Host.LINUX),
            listings = mapOf(Host.LINUX to Listing("https://redream.io/download", Regex("^redream\\.x86_64-linux-v(\\d+(?:\\.\\d+)+)\\.tar\\.gz$"))),
        ),
        DesktopEntry(
            "sharpemu", "SharpEmu", "SharpEmu team", "PlayStation 5, experimental: a handful of games start.",
            EMULATORS, listOf("ps5"), repo = "https://github.com/sharpemu/sharpemu", emulator = "sharpemu", prereleases = true, hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            // Each build is a release of its own (KytyPS5-<date>-<commit>), with one file per system
            // (.github/workflows/build.yml, "Release KytyPS5"); the Mac's is x86-64, Rosetta 2 on Apple silicon.
            "kytyps5", "KytyPS5", "KytyPS5 team", "PlayStation 5, experimental: a few games play, Windows first.",
            EMULATORS, listOf("ps5"), repo = "https://github.com/KytyPS5/KytyPS5", emulator = "kytyps5",
            pick = { host, _, _ ->
                when (host) {
                    Host.LINUX -> Regex("-Linux-x86_64\\.tar\\.gz$")
                    Host.WINDOWS -> Regex("-Windows-x64\\.zip$")
                    Host.MACOS -> Regex("-macOS-x86_64\\.zip$")
                    else -> null
                }
            },
        ),
        DesktopEntry(
            "mame", "MAME", "MAMEdev", "Arcade machines, thousands of them.",
            EMULATORS, listOf("arcade"), emulator = "mame", flathub = "org.mamedev.MAME", hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "supermodel", "Supermodel", "The Supermodel Team", "Sega Model 3 arcade games: Daytona USA 2, Virtua Fighter 3, Scud Race.",
            EMULATORS, listOf("model3"), emulator = "supermodel", flathub = "com.supermodel3.Supermodel", hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "mednafen", "Mednafen", "Mednafen team", "Many older systems, accurately: PlayStation, Saturn, PC Engine and more.",
            EMULATORS, listOf("psx", "saturn", "tg16"), emulator = "mednafen", flathub = "com.github.AmatCoder.mednaffe", hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "scummvm", "ScummVM", "ScummVM team", "Classic point-and-click adventures.",
            EMULATORS, listOf("scummvm"), emulator = "scummvm", flathub = "org.scummvm.ScummVM", hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "dosbox-staging", "DOSBox Staging", "DOSBox Staging team", "DOS games, with modern sound and video.",
            EMULATORS, listOf("dos"), repo = "https://github.com/dosbox-staging/dosbox-staging", emulator = "dosbox-staging", hosts = setOf(Host.LINUX),
            family = "DOSBox",
        ),
        DesktopEntry(
            "dosbox-x", "DOSBox-X", "joncampbell123", "DOS and Windows 9x games, the most complete DOSBox.",
            EMULATORS, listOf("dos"), emulator = "dosbox-x", flathub = "com.dosbox_x.DOSBox-X", hosts = setOf(Host.LINUX),
            family = "DOSBox",
        ),
        DesktopEntry(
            "ruffle", "Ruffle", "Ruffle team", "Flash games, safely.",
            EMULATORS, listOf("flash"), repo = "https://github.com/ruffle-rs/ruffle", emulator = "ruffle", prereleases = true, hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "moonlight", "Moonlight", "Moonlight team", "Streams games from your PC, from Sunshine or GeForce Experience.",
            STREAMING, emptyList(), repo = "https://github.com/moonlight-stream/moonlight-qt", inFuse = InFuse.STREAMING,
        ),
        DesktopEntry(
            // Cartridge runs on Linux (and Android): its releases carry Cartridge-x86_64.AppImage.
            "cartridge", "Cartridge", "abdu2304", "Your RomM library on this computer: the games you pick land in the right system folders, firmware included.",
            io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps.COMPANIONS, emptyList(),
            repo = "https://github.com/${io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol.RELEASE_REPO}",
            inFuse = InFuse.TOOL, hosts = setOf(Host.LINUX),
        ),
        DesktopEntry(
            "srm", "Steam ROM Manager", "SteamGridDB", "Adds your emulated games to Steam, with their art.",
            TOOLS, emptyList(), repo = "https://github.com/SteamGridDB/steam-rom-manager", inFuse = InFuse.TOOL,
        ),
    )
}
