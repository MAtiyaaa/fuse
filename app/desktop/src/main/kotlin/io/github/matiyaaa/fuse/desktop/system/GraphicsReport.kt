package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log
import java.util.concurrent.TimeUnit

/**
 * How Fuse's window is really drawn, and when not on the graphics card, why: what Skia said as it
 * gave up on the graphics driver, what in the environment Fuse was started from forces a software
 * driver, and what the system's own OpenGL reports, to tell a problem with the computer from one
 * with how Fuse was started (Steam's Game Mode starts a program with its own environment).
 */
internal object GraphicsReport {
    private val failures = ArrayDeque<String>()

    /** What Skia said when it couldn't use a graphics API, latest last (at most four). */
    val skiaFailures: List<String> get() = synchronized(failures) { failures.toList() }

    /**
     * Routes Skia's own messages into Fuse's log and keeps its warnings and errors, so a fallback
     * to the processor has its reason. Installed before the first window: Skia settles on its logger
     * the first time it logs, so this says whether it took (false once Skia has logged already).
     */
    fun install(): Boolean = runCatching {
        // Skiko's Logger is internal to Kotlin but public on the JVM: its factory is set by name.
        val logger = Class.forName("org.jetbrains.skiko.Logger")
        val instance = logger.getField("INSTANCE").get(null)
        val factory: () -> org.jetbrains.skiko.SkikoLoggerInterface = { SkiaLog }
        logger.getMethod("setLoggerFactory", Function0::class.java).invoke(instance, factory)
        val took = logger.getMethod("getLoggerImpl").invoke(instance) === SkiaLog
        if (!took) Log.warn("Skia had already started its log, so its reasons for not using the graphics card aren't kept")
        took
    }.getOrElse {
        Log.warn("Skia's messages can't be followed", it)
        false
    }

    internal fun remember(message: String, error: Throwable?) {
        val text = listOfNotNull(message.trim().takeIf { it.isNotEmpty() }, error?.message?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(": ").replace('\n', ' ').take(240)
        if (text.isEmpty()) return
        synchronized(failures) {
            if (failures.lastOrNull() == text) return
            failures.addLast(text)
            while (failures.size > 4) failures.removeFirst()
        }
    }

    private object SkiaLog : org.jetbrains.skiko.SkikoLoggerInterface {
        override val isTraceEnabled = false
        override val isDebugEnabled = false
        override val isInfoEnabled = true
        override val isWarnEnabled = true
        override val isErrorEnabled = true
        override fun trace(message: String) = Unit
        override fun debug(message: String) = Unit
        override fun info(message: String) = Log.info("Skia: $message")
        override fun warn(message: String) { Log.warn("Skia: $message"); remember(message, null) }
        override fun error(message: String) { Log.warn("Skia: $message"); remember(message, null) }
        override fun trace(t: Throwable, message: String) = Unit
        override fun debug(t: Throwable, message: String) = Unit
        override fun info(t: Throwable, message: String) = Log.info("Skia: $message (${t.message})")
        override fun warn(t: Throwable, message: String) { Log.warn("Skia: $message", t); remember(message, t) }
        override fun error(t: Throwable, message: String) { Log.warn("Skia: $message", t); remember(message, t) }
    }

    /**
     * What in the environment makes OpenGL draw in software, each said plainly with what to do. A
     * Steam runtime's libraries ahead of the system's can load an older C++ library than the
     * system's Mesa driver needs, and the driver then falls back to software.
     */
    fun environmentCauses(env: (String) -> String?): List<String> = buildList {
        val always = env("LIBGL_ALWAYS_SOFTWARE")
        if (always != null && always != "0" && !always.equals("false", ignoreCase = true)) {
            add("LIBGL_ALWAYS_SOFTWARE is set, which tells OpenGL to draw on the processor. Remove it from Fuse's launch options")
        }
        val gallium = env("GALLIUM_DRIVER")?.lowercase()
        if (gallium != null && gallium in setOf("llvmpipe", "softpipe", "swr")) {
            add("GALLIUM_DRIVER is set to $gallium, a software driver. Remove it from Fuse's launch options")
        }
        val override = env("MESA_LOADER_DRIVER_OVERRIDE")?.lowercase()
        if (override != null && override in setOf("swrast", "kms_swrast", "llvmpipe")) {
            add("MESA_LOADER_DRIVER_OVERRIDE is set to $override, a software driver. Remove it from Fuse's launch options")
        }
        if (steamRuntimeLibraries(env("LD_LIBRARY_PATH")).isNotEmpty()) {
            add("Steam's runtime libraries are ahead of the system's, which can stop the graphics driver from loading. Start Fuse from its AppImage, which sets them aside")
        } else if (steamRuntimeLibraries(env("FUSE_STEAM_LD_LIBRARY_PATH")).isNotEmpty()) {
            add("Steam's runtime libraries were set aside as Fuse started, so they weren't the cause")
        }
    }

    /** The parts of [ldLibraryPath] that are a Steam runtime's own libraries. */
    fun steamRuntimeLibraries(ldLibraryPath: String?): List<String> =
        ldLibraryPath.orEmpty().split(':').filter { p ->
            val l = p.lowercase()
            l.isNotBlank() && ("steam-runtime" in l || "/ubuntu12_32" in l || "/ubuntu12_64" in l || "steamrt" in l || "pressure-vessel" in l)
        }

    /**
     * The system's own OpenGL renderer, from glxinfo where it is installed (Bazzite and SteamOS
     * have it), within two seconds; null where it can't be asked.
     */
    fun systemRenderer(): String? = runCatching {
        val process = ProcessBuilder("glxinfo", "-B").redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        parseGlxRenderer(process.inputStream.bufferedReader().readText())
    }.getOrNull()

    fun parseGlxRenderer(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("OpenGL renderer string:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** Vendor and model from Skia's render info, else its first line. */
    fun driver(info: String?): String? {
        if (info.isNullOrBlank()) return null
        val lines = info.lines().map { it.trim() }
        fun field(name: String) = lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val vendor = field("Vendor")
        val model = field("Model") ?: field("Renderer")
        return when {
            vendor != null && model != null -> if (model.contains(vendor, ignoreCase = true)) model else "$vendor $model"
            model != null -> model
            vendor != null -> vendor
            else -> lines.firstOrNull { it.isNotEmpty() }
        }
    }

    /** The session in plain words. */
    fun sessionName(session: DisplaySession): String = when (session) {
        DisplaySession.STEAM_GAME_MODE -> "Steam's Game Mode (gamescope)"
        DisplaySession.GAMESCOPE_NESTED -> "gamescope, in a window"
        DisplaySession.WAYLAND -> "Wayland"
        DisplaySession.X11 -> "X11"
        DisplaySession.UNKNOWN -> "Unknown"
    }
}
