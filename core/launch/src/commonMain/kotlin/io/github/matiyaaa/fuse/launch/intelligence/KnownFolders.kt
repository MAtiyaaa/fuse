package io.github.matiyaaa.fuse.launch.intelligence

import io.github.matiyaaa.fuse.model.Host

/**
 * What Fuse already knows about where emulators keep their settings, before any looking: each
 * emulator's own documented folder for its native install. Intelligence consults this first and
 * only looks further for an emulator it doesn't name (a fork, a new emulator) or when none of
 * these folders is there.
 *
 * Paths are written against a base: `config/` is `$XDG_CONFIG_HOME` (or `~/.config`), `data/` is
 * `$XDG_DATA_HOME` (or `~/.local/share`), and `~/` is the home folder. A Flatpak keeps its own
 * under `~/.var/app/<its id>` whatever is listed here.
 */
object KnownFolders {
    private val linux: Map<String, List<String>> = mapOf(
        "linux.retroarch" to listOf("config/retroarch"),
        "linux.dolphin" to listOf("config/dolphin-emu", "data/dolphin-emu"),
        "linux.pcsx2" to listOf("config/PCSX2"),
        "linux.duckstation" to listOf("data/duckstation"),
        "linux.ppsspp" to listOf("config/ppsspp"),
        "linux.rpcs3" to listOf("config/rpcs3"),
        "linux.cemu" to listOf("config/Cemu", "data/Cemu"),
        "linux.ryujinx" to listOf("config/Ryujinx"),
        "linux.melonds" to listOf("config/melonDS"),
        "linux.mgba" to listOf("config/mgba"),
        "linux.flycast" to listOf("config/flycast", "data/flycast"),
        "linux.citra" to listOf("config/citra-emu"),
        "linux.lime3ds" to listOf("config/lime3ds-emu", "config/lime3ds"),
        "linux.azahar" to listOf("config/azahar-emu"),
        "linux.vita3k" to listOf("data/Vita3K"),
        "linux.shadps4" to listOf("data/shadPS4"),
        "linux.xemu" to listOf("data/xemu"),
        "linux.mame" to listOf("~/.mame"),
        "linux.mednafen" to listOf("~/.mednafen"),
        "linux.mupen64plus" to listOf("config/mupen64plus"),
        "linux.scummvm" to listOf("config/scummvm"),
        "linux.dosbox-staging" to listOf("config/dosbox"),
    )

    /** The folders Fuse knows for [emulator] on [host], resolved against [look]; empty when it knows none. */
    fun of(emulator: String, host: Host, look: ReadOnlyLook): List<String> = when (host) {
        Host.LINUX -> linux[emulator].orEmpty().map { resolve(it, look) }
        else -> emptyList()
    }

    /** Whether Fuse names any folder for [emulator] on [host]. */
    fun knows(emulator: String, host: Host): Boolean = host == Host.LINUX && emulator in linux

    internal fun resolve(path: String, look: ReadOnlyLook): String {
        val home = look.home.trimEnd('/')
        return when {
            path.startsWith("config/") -> (look.env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config").trimEnd('/') + "/" + path.removePrefix("config/")
            path.startsWith("data/") -> (look.env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share").trimEnd('/') + "/" + path.removePrefix("data/")
            path.startsWith("~/") -> "$home/" + path.removePrefix("~/")
            else -> path
        }
    }
}
