package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Small helpers for running system tools (flatpak, gio, xdg-open, wpctl, secret-tool). */
object Processes {
    /** Variables the AppImage runtime sets for Fuse itself; children must not inherit them. */
    private val APPIMAGE_VARS = listOf("APPIMAGE", "APPDIR", "ARGV0", "OWD")

    class Output(val exitCode: Int, val stdout: String)

    /**
     * Absolute path of [name] on `$PATH`, or null. On Windows the folders are split on ";" and each
     * PATHEXT extension is tried ("powershell" finds powershell.exe).
     */
    fun which(name: String): String? {
        if (File(name).isAbsolute) return name.takeIf { isExecutable(it) }
        val path = System.getenv("PATH") ?: return null
        val names = if (DesktopOs.isWindows && '.' !in name) {
            (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotEmpty() }.map { name + it.lowercase() }
        } else {
            listOf(name)
        }
        return path.split(File.pathSeparatorChar).filter { it.isNotEmpty() }
            .flatMap { dir -> names.map { File(dir, it).path } }
            .firstOrNull(::isExecutable)?.let { File(it).fusePath }
    }

    fun isExecutable(path: String): Boolean {
        val f = File(path)
        return f.isFile && f.canExecute()
    }

    /**
     * A ProcessBuilder with Fuse's AppImage variables removed from the environment. On Windows,
     * paths in the arguments are given with backslashes, as Windows programs expect.
     */
    fun builder(argv: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(if (DesktopOs.isWindows) argv.map(::windowsArgument) else argv)
        val env = pb.environment()
        APPIMAGE_VARS.forEach { env.remove(it) }
        return pb
    }

    /**
     * Runs [argv] and waits up to [timeoutMs]. [stdin] is written and closed first. Returns null when
     * the program is missing, fails to start or times out (it is then killed). Blocking: call from an
     * IO thread. stdout is capped at [maxOutput] bytes; stderr is discarded.
     */
    fun run(argv: List<String>, timeoutMs: Long = 5_000, stdin: String? = null, maxOutput: Int = 1 shl 20): Output? {
        val process = try {
            builder(argv)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { if (stdin == null) redirectInput(ProcessBuilder.Redirect.from(DesktopOs.nullDevice)) }
                .start()
        } catch (e: IOException) {
            return null
        }
        val out = CompletableFuture<String>()
        Thread({
            try {
                process.inputStream.use { input ->
                    val bytes = input.readNBytes(maxOutput)
                    // Drain the rest so the child never blocks on a full pipe.
                    input.transferTo(java.io.OutputStream.nullOutputStream())
                    out.complete(String(bytes, StandardCharsets.UTF_8))
                }
            } catch (e: IOException) {
                out.complete("")
            }
        }, "fuse-proc-out").apply { isDaemon = true }.start()
        try {
            if (stdin != null) {
                process.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            // A grandchild may keep stdout open; take what arrived instead of waiting for it.
            val text = try {
                out.get(2, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                ""
            }
            return Output(process.exitValue(), text)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            process.destroyForcibly()
            return null
        }
    }

    /**
     * Starts [argv] without waiting and without a terminal: output is discarded and stdin is empty.
     * Returns the process, or null when it could not be started.
     */
    fun spawn(argv: List<String>, workingDir: String? = null, env: Map<String, String> = emptyMap()): Process? = try {
        builder(argv)
            .apply { workingDir?.let { d -> File(d).takeIf { it.isDirectory }?.let { directory(it) } } }
            .apply { environment().putAll(env) }
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.from(DesktopOs.nullDevice))
            .start()
    } catch (e: IOException) {
        Log.warn("could not start ${argv.firstOrNull()}", e)
        null
    }
}
