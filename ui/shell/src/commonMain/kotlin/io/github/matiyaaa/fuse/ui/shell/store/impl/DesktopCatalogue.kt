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
) {
    fun repoFor(host: Host): String? = repos[host] ?: repo
}

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
            "ppsspp", "PPSSPP", "Henrik Rydgård", "PlayStation Portable. Its builds are on its own site.",
            EMULATORS, listOf("psp"), emulator = "ppsspp", page = "https://www.ppsspp.org/download/",
        ),
        DesktopEntry(
            "dolphin", "Dolphin", "Dolphin team", "GameCube and Wii. Its builds are on its own site.",
            EMULATORS, listOf("gc", "wii"), emulator = "dolphin", page = "https://dolphin-emu.org/download/",
        ),
        DesktopEntry(
            "retroarch", "RetroArch", "Libretro", "Many systems through its cores. Its builds are on its own site.",
            EMULATORS, emptyList(), emulator = "retroarch", page = "https://www.retroarch.com/?page=platforms",
        ),
        DesktopEntry(
            "moonlight", "Moonlight", "Moonlight team", "Streams games from your PC, from Sunshine or GeForce Experience.",
            STREAMING, emptyList(), repo = "https://github.com/moonlight-stream/moonlight-qt", inFuse = InFuse.STREAMING,
        ),
        DesktopEntry(
            "srm", "Steam ROM Manager", "SteamGridDB", "Adds your emulated games to Steam, with their art.",
            TOOLS, emptyList(), repo = "https://github.com/SteamGridDB/steam-rom-manager", inFuse = InFuse.TOOL,
        ),
    )
}
