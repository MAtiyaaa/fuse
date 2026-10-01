package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.PowerShell
import java.io.File
import java.io.IOException

/**
 * "Start Fuse when I log in". Linux: an XDG autostart entry at `~/.config/autostart/fuse.desktop`
 * that runs the Fuse AppImage (or the installed launcher). Windows: a "Fuse" value under the user's
 * Run key. macOS: a LaunchAgent that opens Fuse.app. Only written or removed when the user toggles
 * it, and only what Fuse wrote itself (marked) is ever removed.
 */
object Autostart {
    private const val MARKER = "X-Fuse-Autostart=true"
    private const val RUN_KEY = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val AGENT_LABEL = "io.github.matiyaaa.fuse"

    private fun file(dirs: FuseDirs) = File("${dirs.xdgConfigHome}/autostart/fuse.desktop")

    /** Windows: Fuse notes here that it set the Run value, so Settings needn't ask the registry. */
    private fun windowsMarker(dirs: FuseDirs) = File(dirs.config, "autostart.on")

    private fun launchAgent(dirs: FuseDirs) = File("${dirs.home}/Library/LaunchAgents/$AGENT_LABEL.plist")

    /** The program to start at login: `$APPIMAGE` when running from one, else the packaged launcher. */
    fun command(): String? =
        System.getenv("APPIMAGE")?.takeIf { File(it).isFile }
            ?: System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    /** True when Fuse can set up autostart (it knows a stable program path). */
    fun supported(): Boolean = command() != null

    fun isEnabled(dirs: FuseDirs): Boolean = when (DesktopOs.current) {
        DesktopOs.LINUX -> file(dirs).let { it.isFile && it.readText().contains(MARKER) }
        DesktopOs.WINDOWS -> windowsMarker(dirs).isFile
        DesktopOs.MACOS -> launchAgent(dirs).let { it.isFile && it.readText().contains(AGENT_LABEL) }
    }

    /** Sets up the login entry. Fails when the running Fuse has no stable path (a development run). */
    fun enable(dirs: FuseDirs): Result<Unit> = runCatching {
        val exec = command() ?: error("Autostart needs Fuse to run from its AppImage or an installed package.")
        when (DesktopOs.current) {
            DesktopOs.LINUX -> enableLinux(dirs, exec)
            DesktopOs.WINDOWS -> {
                val value = "'\"' + ${PowerShell.literal(File(exec).absolutePath)} + '\"'"
                val out = PowerShell.run("Set-ItemProperty -Path '$RUN_KEY' -Name 'Fuse' -Value ($value)")
                if (out?.exitCode != 0) throw IOException("Windows did not accept the startup entry.")
                windowsMarker(dirs).apply { parentFile.mkdirs() }.writeText("Fuse starts at sign-in through the Run key.\n")
            }
            DesktopOs.MACOS -> {
                val app = macApp(exec) ?: error("Autostart needs Fuse to run from Fuse.app.")
                val f = launchAgent(dirs)
                f.parentFile.mkdirs()
                f.writeText(launchAgentPlist(app))
            }
        }
    }

    /** Removes the entry, but only when Fuse wrote it. */
    fun disable(dirs: FuseDirs): Result<Unit> = runCatching {
        when (DesktopOs.current) {
            DesktopOs.LINUX -> {
                val f = file(dirs)
                if (!f.exists()) return@runCatching
                if (!f.readText().contains(MARKER)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
                if (!f.delete()) throw IOException("Could not remove ${f.path}.")
            }
            DesktopOs.WINDOWS -> {
                val out = PowerShell.run("Remove-ItemProperty -Path '$RUN_KEY' -Name 'Fuse' -ErrorAction SilentlyContinue")
                if (out == null) throw IOException("Windows did not answer.")
                windowsMarker(dirs).delete()
            }
            DesktopOs.MACOS -> {
                val f = launchAgent(dirs)
                if (!f.exists()) return@runCatching
                if (!f.readText().contains(AGENT_LABEL)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
                if (!f.delete()) throw IOException("Could not remove ${f.path}.")
            }
        }
    }

    private fun enableLinux(dirs: FuseDirs, exec: String) {
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

    /**
     * The Fuse.app bundle around the launcher at `Fuse.app/Contents/MacOS/Fuse`. Read from the path
     * itself, so it gives a macOS path on any system (the tests run on Windows too).
     */
    internal fun macApp(launcher: String): String? {
        val parts = launcher.trimEnd('/').split('/')
        if (parts.size < 4 || parts[parts.size - 3] != "Contents" || parts[parts.size - 2] != "MacOS") return null
        return parts.dropLast(3).joinToString("/").takeIf { it.endsWith(".app") }
    }

    /** A LaunchAgent that opens [app] at login (`open -a`, so macOS starts it like a click would). */
    internal fun launchAgentPlist(app: String): String {
        val escaped = app.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>$AGENT_LABEL</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>/usr/bin/open</string>
            |        <string>-a</string>
            |        <string>$escaped</string>
            |    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |</dict>
            |</plist>
            |
        """.trimMargin()
    }

    /** Quotes a path for an Exec line (Desktop Entry spec: reserved characters inside double quotes). */
    internal fun quoteExec(path: String): String {
        if (path.none { it.isWhitespace() || it in "\"'\\><~|&;\$*?#()`" }) return path.replace("%", "%%")
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
        return "\"" + escaped.replace("%", "%%") + "\""
    }
}
