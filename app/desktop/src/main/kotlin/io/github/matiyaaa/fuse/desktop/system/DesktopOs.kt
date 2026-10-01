package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.model.Host
import java.io.File
import java.util.Locale

/**
 * The desktop system Fuse runs on. One build serves Linux, Windows and macOS; the few places that
 * differ (folders, credentials, controllers, emulator search, packaging) switch on [current].
 */
enum class DesktopOs(val host: Host) {
    LINUX(Host.LINUX),
    WINDOWS(Host.WINDOWS),
    MACOS(Host.MACOS),
    ;

    companion object {
        val current: DesktopOs = of(System.getProperty("os.name").orEmpty())

        fun of(osName: String): DesktopOs {
            val name = osName.lowercase(Locale.ROOT)
            return when {
                name.startsWith("windows") -> WINDOWS
                name.startsWith("mac") || name.startsWith("darwin") -> MACOS
                else -> LINUX
            }
        }

        val isWindows: Boolean get() = current == WINDOWS
        val isMac: Boolean get() = current == MACOS

        /** Where a child's stdin reads nothing: `NUL` on Windows. */
        val nullDevice: File get() = File(if (isWindows) "NUL" else "/dev/null")
    }
}

/**
 * [this] path with forward slashes, the way Fuse's shared code keeps paths ("C:/Games/x.iso").
 * Windows paths only change at the edges: here, and back to backslashes in what a program is given.
 */
val File.fusePath: String get() = invariantSeparatorsPath

/** [path] with backslashes, for a Windows program's arguments ("C:/x.iso" becomes "C:\x.iso"). */
fun windowsArgument(path: String): String =
    if (path.length >= 3 && path[1] == ':' && path[0].isLetter() && path[2] == '/') path.replace('/', '\\') else path
