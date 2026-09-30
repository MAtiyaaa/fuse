package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.FuseDirs
import java.io.File
import java.io.IOException

/**
 * "Start Fuse when I log in": an XDG autostart entry at `~/.config/autostart/fuse.desktop` that runs
 * the Fuse AppImage (or the installed launcher). Only written or removed when the user toggles it,
 * and only a file Fuse wrote itself (marked `X-Fuse-Autostart=true`) is ever removed.
 */
object Autostart {
    private const val MARKER = "X-Fuse-Autostart=true"

    private fun file(dirs: FuseDirs) = File("${dirs.xdgConfigHome}/autostart/fuse.desktop")

    /** The program to start at login: `$APPIMAGE` when running from one, else the packaged launcher. */
    fun command(): String? =
        System.getenv("APPIMAGE")?.takeIf { File(it).isFile }
            ?: System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    /** True when Fuse can set up autostart (it knows a stable program path). */
    fun supported(): Boolean = command() != null

    fun isEnabled(dirs: FuseDirs): Boolean = file(dirs).let { it.isFile && it.readText().contains(MARKER) }

    /** Writes the autostart entry. Fails when the running Fuse has no stable path (a development run). */
    fun enable(dirs: FuseDirs): Result<Unit> = runCatching {
        val exec = command() ?: error("Autostart needs Fuse to run from its AppImage or an installed package.")
        val f = file(dirs)
        if (f.exists() && !f.readText().contains(MARKER)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
        f.parentFile.mkdirs()
        f.writeText(
            """
            |[Desktop Entry]
            |Type=Application
            |Name=Fuse
            |Comment=Controller-first game launcher
            |Exec=${quoteExec(exec)}
            |Icon=fuse
            |Terminal=false
            |X-GNOME-Autostart-enabled=true
            |$MARKER
            |
            """.trimMargin(),
        )
    }

    /** Removes the entry, but only when Fuse wrote it. */
    fun disable(dirs: FuseDirs): Result<Unit> = runCatching {
        val f = file(dirs)
        if (!f.exists()) return@runCatching
        if (!f.readText().contains(MARKER)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
        if (!f.delete()) throw IOException("Could not remove ${f.path}.")
    }

    /** Quotes a path for an Exec line (Desktop Entry spec: reserved characters inside double quotes). */
    internal fun quoteExec(path: String): String {
        if (path.none { it.isWhitespace() || it in "\"'\\><~|&;\$*?#()`" }) return path.replace("%", "%%")
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
        return "\"" + escaped.replace("%", "%%") + "\""
    }
}
