package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.Host

/**
 * Where RetroArch's own Core Downloader gets a core: libretro's buildbot, nightly builds for each
 * system, one zip per core holding its library. Fuse fetches the same file into the folder RetroArch
 * reads its cores from, so a game whose core is missing can play without opening RetroArch first.
 */
object RetroArchBuildbot {
    private const val BASE = "https://buildbot.libretro.com/nightly"
    private val NAME = Regex("[a-z0-9_]+")

    /** The folder of builds for [host] on [arch] (`os.arch`), or null where the buildbot has none Fuse uses. */
    fun folder(host: Host, arch: String): String? {
        val a = arch.lowercase()
        val x64 = a == "amd64" || a == "x86_64" || a == "x64"
        val arm64 = a == "aarch64" || a == "arm64"
        return when (host) {
            Host.LINUX -> if (x64) "$BASE/linux/x86_64/latest" else null
            Host.WINDOWS -> if (x64) "$BASE/windows/x86_64/latest" else null
            Host.MACOS -> when {
                arm64 -> "$BASE/apple/osx/arm64/latest"
                x64 -> "$BASE/apple/osx/x86_64/latest"
                else -> null
            }
            Host.ANDROID -> null
        }
    }

    /** The address of [core]'s zip for [host] on [arch]; null for a name that isn't a core's, or a system without builds. */
    fun url(host: Host, arch: String, core: String): String? {
        if (!NAME.matches(core)) return null
        val folder = folder(host, arch) ?: return null
        return "$folder/${RetroArchCores.coreFile(host, core)}.zip"
    }

    /** Whether [head] (a file's first bytes) starts as a library [host] loads: ELF, a Windows DLL, or Mach-O. */
    fun looksLikeLibrary(host: Host, head: ByteArray): Boolean {
        fun at(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
        return when (host) {
            Host.LINUX -> at(0x7F, 0x45, 0x4C, 0x46)
            Host.WINDOWS -> at(0x4D, 0x5A)
            Host.MACOS -> at(0xCF, 0xFA, 0xED, 0xFE) || at(0xCA, 0xFE, 0xBA, 0xBE)
            Host.ANDROID -> false
        }
    }

    /**
     * Of RetroArch's core folders [dirs] (in the order RetroArch reads them), the one to install
     * into: the first that is the person's own (under [home]) and either there already or the first
     * of theirs. Never a system folder, which only the system's packages may change.
     */
    fun installFolder(dirs: List<String>, home: String, exists: (String) -> Boolean): String? {
        val h = home.trimEnd('/', '\\') + "/"
        val own = dirs.filter { it.replace('\\', '/').startsWith(h.replace('\\', '/')) }
        return own.firstOrNull(exists) ?: own.firstOrNull()
    }
}
