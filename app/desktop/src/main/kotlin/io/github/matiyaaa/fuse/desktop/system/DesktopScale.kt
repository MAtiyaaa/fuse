package io.github.matiyaaa.fuse.desktop.system

/**
 * The scale the desktop asks apps to draw at, where Java doesn't follow it.
 *
 * On Linux, Java's toolkit scales windows only by whole numbers (GDK_SCALE), so a desktop scaled to
 * 125% or 150% (KDE Plasma, GNOME's text scaling) gets a Fuse drawn at 1x: either too small, or,
 * where the compositor enlarges it to fit, soft everywhere. The desktop states its real scale as the
 * X resource Xft.dpi (96 at 100%, 120 at 125%, 144 at 150%). Fuse draws at that density itself, at
 * the screen's own resolution, so text and gradients stay sharp at the size the desktop asked for.
 */
internal object DesktopScale {
    /**
     * How much larger than Java's own scale ([javaScale]) to draw, from [dpi] (Xft.dpi): 1 when the
     * desktop isn't scaled further than Java already does, or [override] (FUSE_SCALE) when given.
     */
    fun extra(dpi: Double?, javaScale: Double, override: String? = null): Float {
        override?.toFloatOrNull()?.takeIf { it in 0.5f..4f }?.let { return (it / javaScale.toFloat()).coerceAtLeast(0.5f) }
        val wanted = (dpi ?: return 1f) / 96.0
        if (wanted !in 0.5..4.0) return 1f
        val extra = wanted / javaScale.coerceAtLeast(1.0)
        // Within a few percent is rounding, not a request: drawing at exactly 1 keeps pixels whole.
        return if (kotlin.math.abs(extra - 1.0) < 0.04) 1f else extra.toFloat()
    }

    /** Xft.dpi from the X server's resources ("Xft.dpi:\t120"), or null when it isn't set. */
    fun parseXrdb(text: String?): Double? = text?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("Xft.dpi:") }
        ?.substringAfter(':')?.trim()?.toDoubleOrNull()

    /** The desktop's Xft.dpi, read once at start on Linux outside gamescope (which sets its own size). */
    fun readDpi(): Double? {
        if (DesktopOs.current != DesktopOs.LINUX) return null
        if (System.getenv("GAMESCOPE_WAYLAND_DISPLAY") != null || System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("gamescope", ignoreCase = true)) return null
        val xrdb = Processes.which("xrdb") ?: return null
        return parseXrdb(Processes.run(listOf(xrdb, "-query"), timeoutMs = 1_500)?.stdout)
    }
}
