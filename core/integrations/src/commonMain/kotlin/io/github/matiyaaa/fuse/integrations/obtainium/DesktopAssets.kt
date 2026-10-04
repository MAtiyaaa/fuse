package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.model.Host

/** A file of a release: its name, address, size and the digest its source published. */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long? = null, val digest: String? = null)

/** What a desktop download is, so the installer knows what to do with it. */
enum class DesktopAssetKind {
    /** A Linux AppImage: made runnable and used as it is. */
    APPIMAGE,

    /** A zip: unpacked (on Linux, only for the AppImage inside it). */
    ZIP,

    /** A macOS disk image: its app is copied out. */
    DMG,
}

/**
 * Picks the file of a release meant for this computer, as an emulator's own developers publish it:
 * on Linux only AppImages (or a zip holding one), on Windows a portable zip, on macOS a disk image
 * or a zipped app. Installers, debug symbols, source archives and other processors' builds are
 * never picked, and nothing is ever repackaged: Fuse only fetches what upstream publishes.
 */
object DesktopAssets {
    /** The file for [host] on the processor [arch] ("x86_64" or "arm64"), or null when the release has none. */
    fun pick(assets: List<ReleaseAsset>, host: Host, arch: String, pattern: Regex? = null): ReleaseAsset? {
        val usable = assets.filter { a ->
            val n = a.name.lowercase()
            kindOf(a.name, host) != null && NEVER.none { it in n } && (pattern == null || pattern.containsMatchIn(a.name))
        }
        val forHost = usable.filter { a -> fitsHost(a.name.lowercase(), host) }
        val candidates = forHost.ifEmpty { if (host == Host.LINUX) usable.filter { kindOf(it.name, host) == DesktopAssetKind.APPIMAGE } else emptyList() }
        val arm = arch.lowercase().let { it.contains("arm") || it.contains("aarch64") }
        val fitting = candidates.filter { a -> archFits(a.name.lowercase(), arm) }
        // The plainest name wins among equals: "app-x64.AppImage" before "app-x64-debug-something".
        return fitting.sortedWith(compareBy<ReleaseAsset>({ rank(it.name, host) }, { it.name.length })).firstOrNull()
    }

    /** What [name] is on [host], or null when Fuse doesn't install that kind of file there. */
    fun kindOf(name: String, host: Host): DesktopAssetKind? {
        val n = name.lowercase()
        return when (host) {
            Host.LINUX -> when {
                n.endsWith(".appimage") -> DesktopAssetKind.APPIMAGE
                // A zip is only taken on Linux when it says it holds an AppImage.
                n.endsWith(".zip") && "appimage" in n -> DesktopAssetKind.ZIP
                else -> null
            }
            Host.WINDOWS -> if (n.endsWith(".zip")) DesktopAssetKind.ZIP else null
            Host.MACOS -> when {
                n.endsWith(".dmg") -> DesktopAssetKind.DMG
                n.endsWith(".zip") -> DesktopAssetKind.ZIP
                else -> null
            }
            Host.ANDROID -> null
        }
    }

    private fun fitsHost(n: String, host: Host): Boolean = when (host) {
        Host.LINUX -> n.endsWith(".appimage") || LINUX.any { it in n }
        Host.WINDOWS -> WINDOWS.any { it in n } && MACOS.none { it in n } && LINUX.none { it in n }
        Host.MACOS -> MACOS.any { it in n } || n.endsWith(".dmg")
        Host.ANDROID -> false
    }

    private fun archFits(n: String, arm: Boolean): Boolean {
        val saysArm = ARM.any { it in n }
        val saysIntel = INTEL.any { it in n }
        val universal = "universal" in n
        return when {
            universal -> true
            arm -> saysArm || !saysIntel
            // An Intel computer never takes an ARM-only build.
            else -> !saysArm || saysIntel
        }
    }

    /** Lower is better: the kind each system prefers. */
    private fun rank(name: String, host: Host): Int = when (kindOf(name, host)) {
        DesktopAssetKind.APPIMAGE -> 0
        DesktopAssetKind.DMG -> 0
        DesktopAssetKind.ZIP -> 1
        null -> 9
    }

    private val NEVER = listOf("debug", "symbols", "pdb", "source", "src.", "-src", "setup", "installer", ".sha256", ".sig", "dbgsym")
    private val LINUX = listOf("linux", "appimage")
    private val WINDOWS = listOf("win", "windows", "msvc", "mingw")
    private val MACOS = listOf("mac", "macos", "osx", "darwin", "apple")
    private val ARM = listOf("arm64", "aarch64", "armv8", "-arm")
    private val INTEL = listOf("x86_64", "x64", "amd64", "x86-64", "intel")
}
