package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.launch.LaunchDiagnostic
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.launch.linux.DesktopExec
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Starts games and apps as child processes. Emulator configuration is never touched: Fuse only runs
 * the command the launch plan describes. Output goes nowhere except the first few kilobytes of
 * stderr, kept in memory to explain a start that fails right away.
 */
/** Lets the window react to games ending (Fuse asks to come back to the front). */
class LauncherHooks {
    @Volatile
    var onGameExited: (() -> Unit)? = null

    /** A game Fuse waits on has started (in Game Mode Fuse steps aside so it shows). */
    @Volatile
    var onGameStarted: (() -> Unit)? = null
}

internal class DesktopLauncher(
    private val desktopFiles: DesktopFileIndex,
    private val hooks: LauncherHooks,
) : GameLauncher {

    override suspend fun run(launch: ResolvedLaunch, displayId: Int?): RunResult = when (val plan = launch.plan) {
        is LaunchPlan.Command -> start(plan.argv, plan.workingDir, plan.env, launch.installed?.name ?: plan.argv.firstOrNull()?.let(::baseName))
        is LaunchPlan.OpenAppOnly -> openApp(plan.appId)
        is LaunchPlan.Unsupported -> RunResult.Failed(plan.reason)
        is LaunchPlan.AndroidIntent -> RunResult.Failed("This launch needs Android.")
    }

    override suspend fun openApp(appId: String): RunResult = when {
        appId.isBlank() || appId == "builtin" || baseName(appId) == "gio" ->
            RunResult.Failed("Desktop shortcuts have no app of their own to open.")
        appId.endsWith(".desktop") -> {
            val path = if (FsPath.isAbsolute(appId)) appId.takeIf { File(it).isFile } else desktopFiles.pathFor(appId)
            if (path == null) RunResult.NotInstalled else launchDesktopFile(path)
        }
        // A macOS emulator is the program inside its app; opening the app itself shows it properly.
        DesktopOs.isMac && macApp(appId) != null -> start(listOf("/usr/bin/open", macApp(appId)!!), null, emptyMap(), baseName(appId))
        FsPath.isAbsolute(appId) -> when {
            !File(appId).exists() -> RunResult.NotInstalled
            !Processes.isExecutable(appId) -> RunResult.Failed("${baseName(appId)} is not marked as executable.")
            // The emulator's own folder, where portable Windows emulators keep their settings.
            else -> start(listOf(appId), FsPath.parent(appId), emptyMap(), baseName(appId))
        }
        isFlatpakId(appId) -> {
            val flatpak = Processes.which("flatpak")
            if (flatpak == null) RunResult.NotInstalled else start(listOf(flatpak, "run", appId), null, emptyMap(), appId)
        }
        else -> Processes.which(appId)?.let { start(listOf(it), null, emptyMap(), appId) } ?: RunResult.NotInstalled
    }

    /**
     * Opens a `.desktop` file with `gio launch`, or runs its Exec line when gio is missing. In Game
     * Mode its Exec line always runs as Fuse's own child: gamescope only shows windows that belong to
     * the app on screen, and one gio started for the session would stay hidden.
     */
    suspend fun launchDesktopFile(path: String): RunResult {
        val gio = Processes.which("gio")?.takeUnless { io.github.matiyaaa.fuse.desktop.system.GameMode.active }
        if (gio != null) return start(listOf(gio, "launch", path), null, emptyMap(), baseName(path))
        val text = SystemLinuxEnvironment.readSmallText(path) ?: return RunResult.NotInstalled
        val entry = ShortcutParser.parseDesktop(text) ?: return RunResult.Failed("${baseName(path)} is not a valid .desktop file.")
        val argv = entry.exec?.let(DesktopExec::toArgv) ?: return RunResult.Failed("${baseName(path)} has no command to run.")
        return start(argv, entry.path, emptyMap(), entry.name ?: baseName(path))
    }

    private suspend fun start(argv: List<String>, workingDir: String?, env: Map<String, String>, label: String?): RunResult =
        withContext(Dispatchers.IO) {
            if (argv.isEmpty()) return@withContext RunResult.Failed("Nothing to run.")
            val name = label ?: baseName(argv[0])
            val process = try {
                Processes.builder(argv)
                    .apply { workingDir?.let { d -> File(d).takeIf { it.isDirectory }?.let { directory(it) } } }
                    .apply { environment().putAll(env) }
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(DesktopOs.nullDevice))
                    .start()
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                return@withContext when {
                    "error=2," in msg || "error=3," in msg || "No such file" in msg -> RunResult.NotInstalled
                    "error=13," in msg -> RunResult.Failed("$name is not marked as executable.")
                    "error=5," in msg -> RunResult.Failed("Windows did not allow $name to start.")
                    "error=740," in msg -> RunResult.Failed("$name asks to run as administrator. Fuse starts programs as you.")
                    else -> RunResult.Failed("$name could not start: ${msg.substringAfterLast(": ").ifEmpty { "unknown error" }}")
                }
            }
            val stderr = StderrTail(process)
            val family = ProcessFamily(process)
            // A command that fails at once (missing core, uninstalled Flatpak) is reported, not hidden.
            if (process.waitFor(QUICK_FAIL_MS, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
                val detail = stderr.firstLine(500)
                Log.info("$name exited right away with code ${process.exitValue()}")
                return@withContext if (detail != null && "not installed" in detail) {
                    RunResult.NotInstalled
                } else {
                    RunResult.Failed(
                        "$name stopped right away (exit code ${process.exitValue()})" + (detail?.let { ": ${it.take(160)}" } ?: "."),
                        LaunchDiagnostic(argv, process.exitValue(), stderr.text(0)),
                    )
                }
            }
            // Hand-off commands return at once while the game runs elsewhere (Steam, gio, xdg-open);
            // the play session then ends when Fuse comes back to the front, like on Android.
            if (isHandoff(argv)) {
                family.stop()
                return@withContext RunResult.Started(awaitExit = null)
            }
            hooks.onGameStarted?.invoke()
            RunResult.Started(awaitExit = {
                process.onExit().await()
                // A launcher that hands the game to a process of its own (a wrapper script, an
                // AppImage's runtime, an emulator that starts itself again) has left: the game is
                // over only once what it started is too.
                family.awaitRest()
                hooks.onGameExited?.invoke()
            })
        }

    /**
     * Every process [process] starts, noted while it runs (often at first, when launchers hand
     * over), so they can be waited for once it is gone: after a process exits, the system no longer
     * says which processes were its children. Helpers that outlive any game by design (a sandbox's
     * D-Bus proxy, Wine's server, desktop daemons) are left out.
     */
    internal class ProcessFamily(private val process: Process, private val clock: () -> Long = System::currentTimeMillis) {
        private val seen = java.util.concurrent.ConcurrentHashMap<Long, ProcessHandle>()
        @Volatile private var stopped = false
        private val started = clock()

        init {
            Thread({
                while (!stopped && process.isAlive) {
                    runCatching { process.descendants().forEach { seen.putIfAbsent(it.pid(), it) } }
                    // Often at first, while launchers hand over; then now and then.
                    Thread.sleep(if (clock() - started < 10_000) 50 else 1_000)
                }
            }, "fuse-process-family").apply { isDaemon = true }.start()
        }

        fun stop() { stopped = true }

        /** What it started that still runs, helpers left out. */
        fun rest(): List<ProcessHandle> = seen.values.filter { it.isAlive && !helper(it) }

        /** Waits until everything it started (but helpers) has ended too. */
        suspend fun awaitRest() {
            stopped = true
            while (true) {
                val alive = rest()
                if (alive.isEmpty()) return
                for (h in alive) h.onExit().await()
            }
        }

        private fun helper(h: ProcessHandle): Boolean {
            val name = h.info().command().orElse("").let(::baseName).lowercase().removeSuffix(".exe")
            return name in HELPERS
        }
    }

    /** Keeps the first few KB of a child's stderr and drains the rest so it never blocks. */
    private class StderrTail(process: Process) {
        private val buffer = java.io.ByteArrayOutputStream()
        private val done = java.util.concurrent.CountDownLatch(1)

        init {
            Thread({
                try {
                    process.errorStream.use { input ->
                        val chunk = ByteArray(4096)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            synchronized(buffer) { if (buffer.size() < 4096) buffer.write(chunk, 0, minOf(n, 4096 - buffer.size())) }
                        }
                    }
                } catch (e: IOException) {
                    // The process ended.
                } finally {
                    done.countDown()
                }
            }, "fuse-child-stderr").apply { isDaemon = true }.start()
        }

        fun firstLine(waitMs: Long): String? = text(waitMs).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

        /** What it wrote, up to the first few KB. */
        fun text(waitMs: Long): String {
            done.await(waitMs, TimeUnit.MILLISECONDS)
            return synchronized(buffer) { String(buffer.toByteArray(), StandardCharsets.UTF_8) }
        }
    }

    companion object {
        private const val QUICK_FAIL_MS = 600L

        /** Processes a launcher may leave behind that never are the game. */
        private val HELPERS = setOf(
            "xdg-dbus-proxy", "dbus-daemon", "dbus-launch", "wineserver", "winedevice", "services", "plugplay", "explorer", "rpcss",
            "conhost", "gvfsd", "at-spi-bus-launcher", "at-spi2-registryd", "pulseaudio", "pipewire", "ssh-agent", "gpg-agent",
        )
        private val HANDOFF = setOf("gio", "xdg-open", "steam", "kde-open", "kde-open5", "gnome-open", "exo-open", "explorer")

        /** The last part of a path, with Windows' backslashes too (a Windows program's own path keeps them). */
        fun baseName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

        /** The `X.app` around a program at `X.app/Contents/MacOS/x`, or null. */
        fun macApp(path: String): String? {
            val marker = ".app/Contents/MacOS/"
            val at = path.indexOf(marker)
            return if (at < 0) null else path.substring(0, at + 4)
        }

        /** Flatpak application ids look like reverse DNS: at least two dots, no slash. */
        fun isFlatpakId(id: String): Boolean =
            id.count { it == '.' } >= 2 && '/' !in id && ' ' !in id && !id.endsWith(".desktop")

        fun isHandoff(argv: List<String>): Boolean {
            val first = baseName(argv.firstOrNull() ?: return false).lowercase().removeSuffix(".exe")
            if (first in HANDOFF) return true
            // macOS `open` returns at once unless told to wait (-W).
            if (first == "open") return "-W" !in argv
            if (first == "flatpak") return argv.any { it == "com.valvesoftware.Steam" }
            return false
        }
    }
}
