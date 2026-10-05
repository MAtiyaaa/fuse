package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.PowerShell
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.sync.HostAdmin
import io.github.matiyaaa.fuse.sync.HostLifetime
import io.github.matiyaaa.fuse.sync.ServiceState
import java.io.File
import java.io.IOException

/**
 * Keeps a Fuse Sync host running without Fuse open, and after the computer restarts: Fuse itself,
 * started as `Fuse --sync-host` (no window), by the system.
 *
 * - Linux: a systemd user service, with lingering on so it starts at boot, before anyone signs in.
 * - macOS: a LaunchAgent, started when this person signs in and kept running.
 * - Windows: a task at sign-in, restarted if it stops.
 *
 * Only what Fuse wrote itself (marked) is ever changed or removed.
 */
class SyncHostService(private val dirs: FuseDirs, private val port: () -> Int, private val os: DesktopOs = DesktopOs.current) : HostLifetime {
    override val supported: Boolean get() = command() != null

    override fun state(): ServiceState {
        val installed = when (os) {
            DesktopOs.LINUX -> unit().let { it.isFile && it.readText().contains(MARKER) }
            DesktopOs.MACOS -> agent().let { it.isFile && it.readText().contains(LABEL) }
            DesktopOs.WINDOWS -> marker().isFile
        }
        val running = installed && !HostAdmin.portFree(port())
        val (description, caveat) = when (os) {
            DesktopOs.LINUX -> if (lingering()) {
                "Starts with this computer, before anyone signs in" to null
            } else {
                "Starts when you sign in to this computer" to "To start before anyone signs in, run: loginctl enable-linger"
            }
            DesktopOs.MACOS -> "Starts when you sign in to this Mac, and keeps running" to "Turn on automatic sign-in for the host to start by itself after a restart."
            DesktopOs.WINDOWS -> "Starts when you sign in to this PC, and keeps running" to "Turn on automatic sign-in for the host to start by itself after a restart. Windows may ask once to let Fuse through its firewall."
        }
        return ServiceState(
            installed = installed,
            running = running,
            description = if (supported) description else "Runs only while Fuse is open",
            caveat = if (supported) caveat else "Fuse needs to run from its AppImage or an installed package to start on its own.",
            supported = supported,
        )
    }

    override fun install(): Result<ServiceState> = runCatching {
        val exec = command() ?: error("Fuse needs to run from its AppImage or an installed package to start on its own.")
        when (os) {
            DesktopOs.LINUX -> installLinux(exec)
            DesktopOs.MACOS -> installMac(exec)
            DesktopOs.WINDOWS -> installWindows(exec)
        }
        state()
    }

    override fun remove(): Result<ServiceState> = runCatching {
        when (os) {
            DesktopOs.LINUX -> {
                val f = unit()
                if (f.exists()) {
                    if (!f.readText().contains(MARKER)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
                    Processes.run(listOf("systemctl", "--user", "disable", "--now", UNIT), timeoutMs = 15_000)
                    f.delete()
                    Processes.run(listOf("systemctl", "--user", "daemon-reload"), timeoutMs = 15_000)
                }
            }
            DesktopOs.MACOS -> {
                val f = agent()
                if (f.exists()) {
                    if (!f.readText().contains(LABEL)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
                    Processes.run(listOf("launchctl", "bootout", "gui/${uid()}/$LABEL"), timeoutMs = 15_000)
                    f.delete()
                }
            }
            DesktopOs.WINDOWS -> {
                PowerShell.run("Stop-ScheduledTask -TaskName '$TASK' -ErrorAction SilentlyContinue; Unregister-ScheduledTask -TaskName '$TASK' -Confirm:\$false -ErrorAction SilentlyContinue")
                marker().delete()
            }
        }
        state()
    }

    private fun installLinux(exec: String) {
        val f = unit()
        if (f.exists() && !f.readText().contains(MARKER)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
        f.parentFile.mkdirs()
        f.writeText(systemdUnit(exec))
        Processes.run(listOf("systemctl", "--user", "daemon-reload"), timeoutMs = 15_000)
        val enabled = Processes.run(listOf("systemctl", "--user", "enable", "--now", UNIT), timeoutMs = 20_000)
        if (enabled?.exitCode != 0) throw IOException("systemd didn't start the service. Is this a systemd system?")
        // Lingering starts this person's services at boot. Most systems allow it for yourself; when not, it starts at sign-in.
        if (!lingering()) Processes.run(listOf("loginctl", "enable-linger", System.getProperty("user.name")), timeoutMs = 15_000)
    }

    private fun installMac(exec: String) {
        val f = agent()
        if (f.exists() && !f.readText().contains(LABEL)) throw IOException("${f.path} was not written by Fuse; leaving it alone.")
        f.parentFile.mkdirs()
        f.writeText(launchAgent(exec, File(dirs.data, "sync/host.log").path))
        Processes.run(listOf("launchctl", "bootout", "gui/${uid()}/$LABEL"), timeoutMs = 15_000)
        val loaded = Processes.run(listOf("launchctl", "bootstrap", "gui/${uid()}", f.path), timeoutMs = 15_000)
        if (loaded?.exitCode != 0) throw IOException("macOS didn't start the host.")
    }

    private fun installWindows(exec: String) {
        val script = """
            ${'$'}action = New-ScheduledTaskAction -Execute ${PowerShell.literal(File(exec).absolutePath)} -Argument '$ARGUMENT'
            ${'$'}trigger = New-ScheduledTaskTrigger -AtLogOn -User ${'$'}env:USERNAME
            ${'$'}settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable
            Register-ScheduledTask -TaskName '$TASK' -Description 'Fuse Sync by Fuse: the host for your devices' -Action ${'$'}action -Trigger ${'$'}trigger -Settings ${'$'}settings -Force | Out-Null
            Start-ScheduledTask -TaskName '$TASK'
        """.trimIndent()
        val out = PowerShell.run(script)
        if (out?.exitCode != 0) throw IOException("Windows didn't accept the task.")
        marker().apply { parentFile.mkdirs() }.writeText("Fuse Sync's host starts at sign-in through the task '$TASK'.\n")
    }

    private fun unit() = File("${dirs.xdgConfigHome}/systemd/user/$UNIT")
    private fun agent() = File("${dirs.home}/Library/LaunchAgents/$LABEL.plist")
    private fun marker() = File(dirs.config, "sync-host-task.on")

    private fun lingering(): Boolean =
        File("/var/lib/systemd/linger/${System.getProperty("user.name")}").exists()

    private fun uid(): String = Processes.run(listOf("id", "-u"))?.stdout?.trim().orEmpty().ifEmpty { "501" }

    /** The program that runs the host: `$APPIMAGE` when running from one, else the installed launcher. */
    private fun command(): String? = Autostart.command()

    companion object {
        const val ARGUMENT = "--sync-host"
        const val UNIT = "fuse-sync-host.service"
        const val LABEL = "io.github.matiyaaa.fuse.synchost"
        const val TASK = "Fuse Sync Host"
        private const val MARKER = "X-Fuse-Sync-Host=true"

        internal fun systemdUnit(exec: String): String = """
            |# $MARKER
            |[Unit]
            |Description=Fuse Sync by Fuse (host)
            |Wants=network-online.target
            |After=network-online.target
            |
            |[Service]
            |ExecStart=${Autostart.quoteExec(exec)} $ARGUMENT
            |Restart=always
            |RestartSec=5
            |
            |[Install]
            |WantedBy=default.target
            |
        """.trimMargin()

        internal fun launchAgent(exec: String, log: String): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0">
                |<dict>
                |    <key>Label</key>
                |    <string>$LABEL</string>
                |    <key>ProgramArguments</key>
                |    <array>
                |        <string>${esc(exec)}</string>
                |        <string>$ARGUMENT</string>
                |    </array>
                |    <key>RunAtLoad</key>
                |    <true/>
                |    <key>KeepAlive</key>
                |    <true/>
                |    <key>ProcessType</key>
                |    <string>Background</string>
                |    <key>StandardErrorPath</key>
                |    <string>${esc(log)}</string>
                |</dict>
                |</plist>
                |
            """.trimMargin()
        }
    }
}
