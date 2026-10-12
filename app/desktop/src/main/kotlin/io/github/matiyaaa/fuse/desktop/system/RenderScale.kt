package io.github.matiyaaa.fuse.desktop.system

import java.io.File

/**
 * How many screen pixels each of Fuse's pixels becomes. Under SteamOS's Game Mode (gamescope) Fuse
 * is drawn at the size Steam gives the game, which is the handheld's own screen unless the game's
 * resolution is set otherwise, and gamescope then enlarges that picture to fill the TV. Fuse can't
 * change that size itself, but it can tell: the screen it draws on (what Java sees) against the
 * screens the graphics driver reports as connected, with the modes they run at.
 *
 * Read-only: it only lists `/sys/class/drm`. Never guessed: no connected screen read, no answer.
 */
object RenderScale {
    /** A connected screen's native size, as the driver lists its first (preferred) mode. */
    data class Output(val connector: String, val width: Int, val height: Int)

    /** What Fuse draws at against the largest connected screen; [factor] above 1 means enlarged. */
    data class Reading(val drawnWidth: Int, val drawnHeight: Int, val output: Output) {
        val factor: Float get() = maxOf(output.width.toFloat() / drawnWidth, output.height.toFloat() / drawnHeight)

        /** Enough enlargement to see: soft text, blocky gradients and art less sharp than it is. */
        val enlarged: Boolean get() = factor >= ENLARGED_AT
    }

    /** The connected screens, from the kernel's DRM connectors (`card0-HDMI-A-1` and the like). */
    fun outputs(drm: File = File("/sys/class/drm")): List<Output> =
        drm.listFiles().orEmpty()
            .filter { it.name.matches(CONNECTOR) }
            .mapNotNull { dir ->
                val status = runCatching { File(dir, "status").readText().trim() }.getOrNull()
                if (status != "connected") return@mapNotNull null
                val mode = runCatching { File(dir, "modes").useLines { it.firstOrNull() } }.getOrNull()
                parseMode(mode)?.let { (w, h) -> Output(dir.name.substringAfter('-'), w, h) }
            }
            .sortedByDescending { it.width.toLong() * it.height }

    /** "3840x2160" (some drivers add "i" for interlaced) into width and height; null otherwise. */
    fun parseMode(mode: String?): Pair<Int, Int>? {
        val m = MODE.matchEntire(mode?.trim().orEmpty()) ?: return null
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        return if (w in 320..16_384 && h in 200..16_384) w to h else null
    }

    /**
     * Fuse's drawing against the largest connected screen. Only in Game Mode, where one app owns
     * the screen and is enlarged to it; on a desktop, a window smaller than the screen is a choice.
     */
    fun read(drawnWidth: Int, drawnHeight: Int, gameMode: Boolean = GameMode.active, drm: File = File("/sys/class/drm")): Reading? {
        if (!gameMode || drawnWidth <= 0 || drawnHeight <= 0) return null
        val output = outputs(drm).firstOrNull() ?: return null
        return Reading(drawnWidth, drawnHeight, output)
    }

    /** From here, enlargement shows: a 1280 by 800 picture on a 1920 by 1080 screen is 1.5. */
    const val ENLARGED_AT = 1.2f

    private val CONNECTOR = Regex("card\\d+-.+")
    private val MODE = Regex("(\\d+)x(\\d+)i?")
}
