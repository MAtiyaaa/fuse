package io.github.matiyaaa.fuse.launch.desktop

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.launch.platforms
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * PlayStation 5 games ported with AnyPS5 (github.com/boykopovar/AnyPS5, GPL-2.0). It is not an
 * emulator: its relinker turns a game's eboot.bin into a program of the computer's own (a Windows
 * `.exe`, a Linux ELF, an x86-64 Mac program), which runs with AnyPS5's system libraries in `libs/`
 * and the game's files in `app0/` beside it (docs/user/USAGE.md, "Runtime layout"). Fuse finds such
 * a folder as one game and starts its program from that folder: the game's paths, and its saves in
 * `_sd`, are relative to the folder it starts in (libc's General.cpp roots guest paths at the working
 * directory; libSceSaveData keeps saves in `/_sd`).
 *
 * Nothing is relinked or changed by Fuse: the person runs AnyPS5's relinker on a game they dumped,
 * and Fuse plays what it made.
 */
class AnyPs5Adapter(override val host: Host) : EmulatorAdapter {
    override val id = EmulatorId("${prefix(host)}.anyps5")
    override val name = "AnyPS5"
    override val platforms: Set<PlatformId> = platforms("ps5")
    override val capabilities = AdapterCapabilities(folders = FolderSupport.NONE)
    override val limitations = buildList {
        add("Plays games AnyPS5's relinker has made programs of; it doesn't start eboot.bin. Only a few games are known to play.")
        if (host == Host.LINUX) add("The program must be allowed to run (chmod +x), as AnyPS5's usage says.")
        if (host == Host.MACOS) add("Ports relinked with --macos (and --to-rosetta on Apple silicon).")
    }
    override val source = "AnyPS5's docs/user/USAGE.md: relinker [options] <input.elf> <output>, then the output runs from its folder (libs/, app0/)"
    override val confidence = Confidence.VERIFIED_SOURCE
    override val homepage = "https://github.com/boykopovar/AnyPS5"
    override val builtIn = true

    /** Whether [path] is a program of this system: `.exe` on Windows, an ELF (`.elf` or none) on Linux, none on a Mac. */
    fun isProgram(path: String): Boolean {
        val ext = Paths.extension(path).lowercase()
        return when (host) {
            Host.WINDOWS -> ext == "exe"
            Host.LINUX -> ext == "elf" || ext.isEmpty()
            Host.MACOS -> ext.isEmpty()
            Host.ANDROID -> false
        }
    }

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        platform in platforms && target.path?.let(::isProgram) == true

    override fun plan(request: LaunchRequest): LaunchPlan {
        val path = request.target.path
        if (path == null || !isProgram(path)) {
            val made = when (host) {
                Host.WINDOWS -> "a Windows program (relinker --windows)"
                Host.MACOS -> "a Mac program (relinker --macos)"
                else -> "a Linux program"
            }
            return LaunchPlan.Unsupported(id, "AnyPS5 plays a game once its relinker has made $made of its eboot.bin, with libs/ and app0/ beside it. Add that folder to the library.")
        }
        return LaunchPlan.Command(id, listOf(path), workingDir = Paths.parent(path), target = request.target)
    }

    companion object {
        private fun prefix(host: Host) = when (host) {
            Host.WINDOWS -> "windows"
            Host.MACOS -> "macos"
            else -> "linux"
        }
    }
}
