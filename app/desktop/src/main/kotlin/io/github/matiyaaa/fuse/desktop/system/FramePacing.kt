package io.github.matiyaaa.fuse.desktop.system

/**
 * What the window is really drawn with, and the frame budget that follows from where it is shown.
 *
 * Skiko names only the graphics API it chose. OpenGL can still be drawn on the processor (Mesa's
 * llvmpipe or softpipe, SwiftShader, lavapipe for Vulkan), which looks like "the graphics card" by
 * its name alone. [Renderer.classify] reads Skiko's render info (the GL vendor and renderer) too.
 */
internal object Renderer {
    private val software = listOf("llvmpipe", "softpipe", "swiftshader", "lavapipe", "software rasterizer", "swrast", "microsoft basic render")

    data class Verdict(val name: String, val gpu: Boolean, val why: String?)

    fun classify(api: String, info: String?): Verdict {
        val text = info.orEmpty().lowercase()
        val marker = software.firstOrNull { it in text }
        val cpuApi = api.startsWith("SOFTWARE", ignoreCase = true)
        val label = when {
            cpuApi -> "The processor (no graphics card)"
            marker != null -> "The processor, through ${api.uppercase()} ($marker)"
            api.equals("OPENGL", ignoreCase = true) -> "The graphics card (OpenGL)"
            api.equals("METAL", ignoreCase = true) -> "The graphics card (Metal)"
            api.equals("DIRECT3D", ignoreCase = true) -> "The graphics card (Direct3D)"
            api.equals("VULKAN", ignoreCase = true) -> "The graphics card (Vulkan)"
            else -> api
        }
        val why = when {
            cpuApi -> "Skia found no usable graphics driver for this window and fell back to drawing on the processor."
            marker != null -> "The graphics driver in use is a software one ($marker): usually a missing or mismatched Mesa or GPU driver in the environment Fuse was started from."
            else -> null
        }
        return Verdict(label, gpu = !cpuApi && marker == null, why = why)
    }
}

/** Where Fuse's window is being shown, which decides how it should pace and how much decoration it can afford. */
internal enum class DisplaySession { X11, WAYLAND, GAMESCOPE_NESTED, STEAM_GAME_MODE, UNKNOWN }

internal data class PacingProfile(
    val session: DisplaySession,
    /** The display's refresh, or Steam's frame limit under Game Mode when frames show it. */
    val refreshHz: Double,
    /** A large output (a TV): text and focus must read from a distance, art is shown big. */
    val largeOutput: Boolean,
    /** The window is drawn smaller than the output shows it (gamescope scaling it up): it will look soft. */
    val upscaled: Boolean,
    val gpu: Boolean,
) {
    val budgetNanos: Long get() = (1e9 / refreshHz).toLong()

    /** Decorative effects (large blur, glass, grain) start off only on the processor; otherwise [EffectsGovernor] decides. */
    val decorativeAtStart: Boolean get() = gpu
}

internal object FramePacing {
    /** The session, from the environment Fuse was started in. Steam's Game Mode is gamescope as the whole session. */
    fun session(env: (String) -> String?): DisplaySession {
        val desktop = env("XDG_CURRENT_DESKTOP").orEmpty().lowercase()
        val gamescope = env("GAMESCOPE_WAYLAND_DISPLAY") != null || "gamescope" in desktop
        return when {
            gamescope && (env("SteamGamepadUI") == "1" || env("SteamDeck") == "1" || "gamescope" in desktop) -> DisplaySession.STEAM_GAME_MODE
            gamescope -> DisplaySession.GAMESCOPE_NESTED
            env("WAYLAND_DISPLAY") != null || env("XDG_SESSION_TYPE") == "wayland" -> DisplaySession.WAYLAND
            env("DISPLAY") != null -> DisplaySession.X11
            else -> DisplaySession.UNKNOWN
        }
    }

    /**
     * The profile for a window [windowW] by [windowH] pixels on an output whose current mode is
     * [outputW] by [outputH] at [refreshHz], with Steam's limit inferred from [intervalsNanos]
     * (recent frame intervals) when they settle well below the display's own rate.
     */
    fun profile(
        session: DisplaySession, windowW: Int, windowH: Int, outputW: Int?, outputH: Int?, refreshHz: Double,
        gpu: Boolean, intervalsNanos: List<Long> = emptyList(),
    ): PacingProfile {
        val hz = refreshHz.takeIf { it in 20.0..500.0 } ?: 60.0
        val limited = if (session == DisplaySession.STEAM_GAME_MODE) steamLimit(hz, intervalsNanos) else null
        val large = (outputW ?: windowW) >= 3200 || (outputH ?: windowH) >= 1800
        // Under gamescope the window is the app resolution Steam set (-w/-h); the output is the screen's mode (-W/-H).
        val upscaled = outputW != null && outputH != null && windowW > 0 && windowH > 0 &&
            (outputW.toDouble() / windowW >= 1.4 || outputH.toDouble() / windowH >= 1.4)
        return PacingProfile(session, limited ?: hz, large, upscaled, gpu)
    }

    /** Steam's frame limit (40, 30, 20 Hz...) when the median interval sits steadily at a lower rate than [hz]. */
    internal fun steamLimit(hz: Double, intervals: List<Long>): Double? {
        if (intervals.size < 30) return null
        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val p10 = sorted[sorted.size / 10].toDouble()
        val rate = 1e9 / median
        // Steady (the fast tenth is close to the median) and clearly below the display: a limit, not slowness.
        return rate.takeIf { it < hz * 0.85 && p10 > median * 0.9 }?.let { Math.round(it).toDouble() }
    }

    /** p50, p95 and p99 of [intervals], and how many took longer than 1.5 budgets (a frame shown late). */
    fun stats(intervals: List<Long>, budgetNanos: Long): FrameStats {
        if (intervals.isEmpty()) return FrameStats(0, 0, 0, 0, 0)
        val s = intervals.sorted()
        fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
        return FrameStats(pct(0.5), pct(0.95), pct(0.99), s.count { it > budgetNanos * 3 / 2 }, s.size)
    }
}

internal data class FrameStats(val p50: Long, val p95: Long, val p99: Long, val late: Int, val frames: Int)

/**
 * Turns optional decoration down under measured pressure and back up when it passes, never on one
 * bad frame: [slowWindows] windows in a row over budget at p95 to drop, [goodWindows] in a row
 * comfortably within it to restore. Interaction animations are never governed here.
 */
internal class EffectsGovernor(private val slowWindows: Int = 3, private val goodWindows: Int = 6) {
    var reduced = false
        private set
    private var slow = 0
    private var good = 0

    fun window(stats: FrameStats, budgetNanos: Long): Boolean {
        if (stats.frames < 10) return reduced
        val over = stats.p95 > budgetNanos * 5 / 4
        val easy = stats.p95 < budgetNanos * 9 / 10
        slow = if (over) slow + 1 else 0
        good = if (easy) good + 1 else 0
        if (!reduced && slow >= slowWindows) { reduced = true; good = 0 }
        if (reduced && good >= goodWindows) { reduced = false; slow = 0 }
        return reduced
    }
}

/**
 * The mode of the screen Fuse is shown on, from the kernel: the one connected, enabled output's
 * mode. The kernel lists a connector's modes with the preferred one first, which is the one
 * gamescope and desktops use unless changed; with more than one output enabled, null.
 */
internal object ActiveOutput {
    fun currentMode(drm: java.io.File = java.io.File("/sys/class/drm")): Pair<Int, Int>? =
        RenderScale.outputs(drm).singleOrNull()?.let { it.width to it.height }
}
