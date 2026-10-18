package io.github.matiyaaa.fuse.desktop.system

/**
 * How Skia draws Fuse's window, set before the first window opens.
 *
 * Skia keeps the pictures it draws with (art, blurred rooms, glass, each layer drawn offscreen) in a
 * cache on the graphics card, 96 MB unless told otherwise. One full-screen layer on a 4K screen is
 * about 33 MB, so three of them filled it, and Skia threw pictures out and uploaded them again on
 * every frame of a transition. The cache is sized for the screen instead: room for [LAYERS]
 * full-screen layers, at least [MIN_CACHE_MB] and at most [MAX_CACHE_MB]. It is a budget, not memory
 * taken up front.
 *
 * Under gamescope (Steam's Game Mode, or gamescope in a desktop) Skia doesn't wait for the screen's
 * refresh itself. On Linux it draws on the interface's own thread and, waiting, swaps buffers there:
 * every animation and button press waited with it, and under gamescope the wait is a second one,
 * since gamescope composes and paces the frames it shows (an X11 app that waits hands its picture
 * over too late for the refresh it waited for). Skia then paces itself on a timer at the screen's
 * rate instead, and the thread stays free. Pictures still never tear: gamescope shows whole ones.
 *
 * Settings for trying how frames reach the screen, without a new build, read from the environment
 * (in Steam, a game's Launch Options: `FUSE_VSYNC=off %command%`):
 * - `FUSE_GPU_CACHE_MB`: the cache's size in megabytes.
 * - `FUSE_VSYNC=off`: Skia doesn't wait for the screen's refresh (gamescope and Wayland wait already).
 * - `FUSE_BUFFERING=double` or `triple`: how many frames are kept in flight.
 * - `FUSE_WAIT_VSYNC=off`: a redraw asked for at once doesn't wait for the next refresh first.
 * - `FUSE_CURSOR=keep`: the pointer is never swapped for a blank one while a controller is used.
 * - `FUSE_STEAM_OVERLAY=off`: Steam's overlay library stays out of Fuse (the AppImage's AppRun sets it
 *   aside; the games Fuse starts still get it).
 * - `FUSE_DITHER=gradients` or `window`: each gradient dithered as it is drawn, or the whole window
 *   drawn in half floats and rounded once (the default on a graphics card; see DesktopDithering).
 * A `-Dskiko...` property given to Java itself always wins.
 */
internal object RenderTuning {
    data class Choice(val properties: Map<String, String>, val keepCursor: Boolean, val notes: List<String>)

    /** What to set for a screen of [screenPixels], given the environment ([env]) and properties already set ([given]). */
    fun choose(screenPixels: Long, env: (String) -> String?, given: (String) -> String?, session: DisplaySession = DisplaySession.UNKNOWN): Choice {
        val properties = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        fun put(key: String, value: String, why: String) {
            if (given(key) != null) {
                notes += "$key=${given(key)} (given to Java)"
                return
            }
            properties[key] = value
            notes += "$key=$value ($why)"
        }
        val asked = env("FUSE_GPU_CACHE_MB")?.trim()?.toIntOrNull()?.takeIf { it in 16..8_192 }
        put(CACHE, "${asked ?: cacheMegabytes(screenPixels)}M", if (asked != null) "FUSE_GPU_CACHE_MB" else "sized for the screen")
        when (env("FUSE_VSYNC")?.trim()?.lowercase()) {
            "off", "0", "false", "no" -> put(VSYNC, "false", "FUSE_VSYNC")
            "on", "1", "true", "yes" -> put(VSYNC, "true", "FUSE_VSYNC")
            else -> if (session == DisplaySession.STEAM_GAME_MODE || session == DisplaySession.GAMESCOPE_NESTED) {
                put(VSYNC, "false", "gamescope paces the frames")
            }
        }
        when (env("FUSE_BUFFERING")?.trim()?.lowercase()) {
            "double", "2" -> put(BUFFERING, "DOUBLE", "FUSE_BUFFERING")
            "triple", "3" -> put(BUFFERING, "TRIPLE", "FUSE_BUFFERING")
        }
        when (env("FUSE_WAIT_VSYNC")?.trim()?.lowercase()) {
            "off", "0", "false", "no" -> put(WAIT_VSYNC, "false", "FUSE_WAIT_VSYNC")
            "on", "1", "true", "yes" -> put(WAIT_VSYNC, "true", "FUSE_WAIT_VSYNC")
        }
        val keepCursor = env("FUSE_CURSOR")?.trim()?.lowercase() == "keep"
        if (keepCursor) notes += "the pointer stays as it is (FUSE_CURSOR)"
        if (env("FUSE_STEAM_LD_PRELOAD") != null) notes += "Steam's overlay left out of Fuse (FUSE_STEAM_OVERLAY)"
        return Choice(properties, keepCursor, notes)
    }

    /** Room for [LAYERS] full-screen layers of four bytes a pixel, in whole megabytes within the bounds. */
    fun cacheMegabytes(screenPixels: Long): Long {
        val bytes = screenPixels.coerceAtLeast(0) * BYTES_PER_PIXEL * LAYERS
        return ((bytes + MEGABYTE - 1) / MEGABYTE).coerceIn(MIN_CACHE_MB, MAX_CACHE_MB)
    }

    /** Whether the pointer stays itself while a controller is used ([choose]'s `FUSE_CURSOR`). */
    @Volatile var keepCursor = false
        private set

    /** Sets Skia's properties for this screen; call before any window opens. Returns what was chosen, for the log. */
    fun install(screenPixels: Long): Choice {
        val choice = choose(screenPixels, System::getenv, System::getProperty, FramePacing.session(System::getenv))
        for ((key, value) in choice.properties) System.setProperty(key, value)
        keepCursor = choice.keepCursor
        return choice
    }

    /** What Skia draws with now, for Settings: the picture cache's size and every setting asked for. */
    fun described(): List<Pair<String, String>> = buildList {
        System.getProperty(CACHE)?.let { add("Picture cache" to it.removeSuffix("M") + " MB on the graphics card") }
        System.getProperty(VSYNC)?.let { add("Wait for refresh" to if (it == "false") "Off" else "On") }
        System.getProperty(BUFFERING)?.let { add("Frames in flight" to it.lowercase().replaceFirstChar(Char::uppercase)) }
        System.getProperty(WAIT_VSYNC)?.let { add("Immediate redraws wait for refresh" to if (it == "false") "No" else "Yes") }
        if (keepCursor) add("Pointer" to "Always shown")
        if (System.getenv("FUSE_STEAM_LD_PRELOAD") != null) add("Steam's overlay" to "Left out of Fuse")
    }

    /** The largest screen Fuse could open on: the desktop's mode, or the output gamescope shows it on. */
    fun screenPixels(): Long {
        val desktop = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.maxOf { it.displayMode.width.toLong() * it.displayMode.height }
        }.getOrDefault(0L)
        val output = runCatching { ActiveOutput.currentMode()?.let { (w, h) -> w.toLong() * h } }.getOrNull() ?: 0L
        return maxOf(desktop, output)
    }

    private const val CACHE = "skiko.gpu.resourceCacheLimit"
    private const val VSYNC = "skiko.vsync.enabled"
    private const val BUFFERING = "skiko.buffering"
    private const val WAIT_VSYNC = "skiko.rendering.linux.waitForFrameVsyncOnRedrawImmediately"
    private const val BYTES_PER_PIXEL = 4L
    private const val LAYERS = 24L
    private const val MEGABYTE = 1L shl 20
    private const val MIN_CACHE_MB = 256L
    private const val MAX_CACHE_MB = 1_024L
}
