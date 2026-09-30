package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.launch.linux.DesktopExec
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
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
            val path = if (appId.startsWith("/")) appId.takeIf { File(it).isFile } else desktopFiles.pathFor(appId)
            if (path == null) RunResult.NotInstalled else launchDesktopFile(path)
        }
        appId.startsWith("/") -> when {
            !File(appId).exists() -> RunResult.NotInstalled
            !Processes.isExecutable(appId) -> RunResult.Failed("${baseName(appId)} is not marked as executable.")
            else -> start(listOf(appId), null, emptyMap(), baseName(appId))
        }
        isFlatpakId(appId) -> {
            val flatpak = Processes.which("flatpak")
            if (flatpak == null) RunResult.NotInstalled else start(listOf(flatpak, "run", appId), null, emptyMap(), appId)
        }
        else -> Processes.which(appId)?.let { start(listOf(it), null, emptyMap(), appId) } ?: RunResult.NotInstalled
    }

    /** Opens a `.desktop` file with `gio launch`, or runs its Exec line when gio is missing. */
    suspend fun launchDesktopFile(path: String): RunResult {
        val gio = Processes.which("gio")
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
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .start()
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                return@withContext when {
                    "error=2," in msg || "No such file" in msg -> RunResult.NotInstalled
                    "error=13," in msg -> RunResult.Failed("$name is not marked as executable.")
                    else -> RunResult.Failed("$name could not start: ${msg.substringAfterLast(": ").ifEmpty { "unknown error" }}")
                }
            }
            val stderr = StderrTail(process)
            // A command that fails at once (missing core, uninstalled Flatpak) is reported, not hidden.
            if (process.waitFor(QUICK_FAIL_MS, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
                val detail = stderr.firstLine(500)
                Log.info("$name exited right away with code ${process.exitValue()}")
                return@withContext if (detail != null && "not installed" in detail) {
                    RunResult.NotInstalled
                } else {
                    RunResult.Failed("$name stopped right away (exit code ${process.exitValue()})" + (detail?.let { ": ${it.take(160)}" } ?: "."))
                }
            }
            // Hand-off commands return at once while the game runs elsewhere (Steam, gio, xdg-open);
            // the play session then ends when Fuse comes back to the front, like on Android.
            if (isHandoff(argv)) RunResult.Started(awaitExit = null)
            else RunResult.Started(awaitExit = {
                process.onExit().await()
                hooks.onGameExited?.invoke()
            })
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

        fun firstLine(waitMs: Long): String? {
            done.await(waitMs, TimeUnit.MILLISECONDS)
            val text = synchronized(buffer) { String(buffer.toByteArray(), StandardCharsets.UTF_8) }
            return text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        }
    }

    companion object {
        private const val QUICK_FAIL_MS = 600L
        private val HANDOFF = setOf("gio", "xdg-open", "steam", "kde-open", "kde-open5", "gnome-open", "exo-open")

        fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/')

        /** Flatpak application ids look like reverse DNS: at least two dots, no slash. */
        fun isFlatpakId(id: String): Boolean =
            id.count { it == '.' } >= 2 && '/' !in id && ' ' !in id && !id.endsWith(".desktop")

        fun isHandoff(argv: List<String>): Boolean {
            val first = baseName(argv.firstOrNull() ?: return false)
            if (first in HANDOFF) return true
            if (first == "flatpak") return argv.any { it == "com.valvesoftware.Steam" }
            return false
        }
    }
}
