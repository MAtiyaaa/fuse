package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.ui.shell.platform.StayAwake

/**
 * Keeps a computer awake while Fuse works ([PlatformUi.stayAwake]) with the system's own means, held
 * by a small helper process for as long as it runs: `systemd-inhibit` on Linux (Steam Deck included),
 * `caffeinate` on macOS and the system's execution state on Windows. The helper watches Fuse's process,
 * so the lock goes with Fuse even if Fuse ends without letting go.
 */
internal class DesktopStayAwake(
    private val os: DesktopOs = DesktopOs.current,
    private val which: (String) -> String? = Processes::which,
    private val start: (List<String>) -> Process? = { argv -> runCatching { Processes.builder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() },
) {
    private var holding: StayAwake = StayAwake.NONE
    private var helper: Process? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ helper?.destroy() }, "fuse-stay-awake"))
    }

    @Synchronized
    fun apply(awake: StayAwake) {
        if (awake == holding && (helper?.isAlive == true || !awake.cpu)) return
        helper?.destroy()
        helper = null
        holding = awake
        if (!awake.cpu) return
        val argv = command(os, awake, ProcessHandle.current().pid(), which) ?: return
        helper = start(argv)
        if (helper == null) Log.info("Couldn't ask the system to stay awake (${argv.first()})")
    }

    companion object {
        private const val WHY = "Fuse is finding art, downloading or uploading"

        /** The helper that holds the lock for [awake] while process [pid] (Fuse) runs, or null where there is none. */
        fun command(os: DesktopOs, awake: StayAwake, pid: Long, which: (String) -> String?): List<String>? {
            if (!awake.cpu) return null
            return when (os) {
                DesktopOs.LINUX -> {
                    val inhibit = which("systemd-inhibit") ?: return null
                    val sh = which("sh") ?: "/bin/sh"
                    listOf(
                        inhibit, "--what=" + if (awake.screen) "sleep:idle" else "sleep", "--who=Fuse", "--why=$WHY", "--mode=block",
                        sh, "-c", "while kill -0 $pid 2>/dev/null; do sleep 15; done",
                    )
                }
                DesktopOs.MACOS -> listOf(which("caffeinate") ?: "/usr/bin/caffeinate", if (awake.screen) "-di" else "-i", "-w", pid.toString())
                DesktopOs.WINDOWS -> {
                    // ES_CONTINUOUS | ES_SYSTEM_REQUIRED, and ES_DISPLAY_REQUIRED for the screen.
                    val flags = if (awake.screen) "0x80000003" else "0x80000001"
                    listOf(
                        which("powershell") ?: "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                        "Add-Type -Name Power -Namespace Fuse -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);'; " +
                            "[Fuse.Power]::SetThreadExecutionState($flags) | Out-Null; " +
                            "while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 15 }",
                    )
                }
            }
        }
    }
}
