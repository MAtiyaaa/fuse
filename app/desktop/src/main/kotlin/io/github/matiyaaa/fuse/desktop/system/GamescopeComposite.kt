package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log

/**
 * In Steam's Game Mode, asks gamescope to compose Fuse's picture itself while Fuse is on screen,
 * instead of handing it to the display as it is (direct scanout).
 *
 * Handed over directly, Fuse's 8-bit picture goes through the display hardware's colour tables
 * (gamescope maps sRGB to the TV's own colours there) with nothing between its steps, and soft
 * gradients show as lines on many TVs, at 60 Hz too, though not in Desktop Mode or on Windows
 * (gamescope issue 2250). Composed, gamescope works in a 10-bit buffer in a shader and the graphics
 * card dithers what reaches the TV: Steam's own developer switch, Force Composite, does this for
 * everything, and those who tried it saw the bands mostly go. Fuse turns it on for itself only,
 * through the root window property gamescope reads (`GAMESCOPE_COMPOSITE_FORCE`), and off again
 * when it steps aside for a game, so games keep direct scanout. Composing one picture a frame costs
 * the graphics card well under a millisecond.
 *
 * `FUSE_COMPOSITE=off` (a launch option) leaves gamescope as it is.
 */
internal object GamescopeComposite {
    private const val ATOM = "GAMESCOPE_COMPOSITE_FORCE"

    @Volatile private var enabled = false
    @Volatile private var on = false

    /** Whether to ask at all: Game Mode only, unless turned off. */
    fun wanted(session: DisplaySession, env: (String) -> String?): Boolean =
        session == DisplaySession.STEAM_GAME_MODE && env("FUSE_COMPOSITE")?.trim()?.lowercase() !in setOf("off", "0", "false", "no")

    /** Starts asking (Fuse's window is up), and stops asking when Fuse quits. */
    fun enable(session: DisplaySession) {
        if (enabled || !wanted(session, System::getenv)) return
        enabled = true
        Runtime.getRuntime().addShutdownHook(Thread({ set(false) }, "fuse-composite-off"))
        set(true)
        Log.info("gamescope composes Fuse while it is shown (FUSE_COMPOSITE=off to leave it)")
    }

    /** Fuse is on screen. */
    fun shown() {
        if (enabled) set(true)
    }

    /** Fuse stepped aside (a game is in front) or is closing. */
    fun hidden() {
        if (enabled) set(false)
    }

    @Synchronized
    private fun set(value: Boolean) {
        if (value == on) return
        val done = runCatching {
            val xatom = Class.forName("sun.awt.X11.XAtom")
            val atom = xatom.getMethod("get", String::class.java).invoke(null, ATOM)
            val root = Class.forName("sun.awt.X11.XToolkit").getMethod("getDefaultRootWindow").invoke(null) as Long
            if (value) {
                xatom.getMethod("setCard32Property", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(atom, root, 1L)
            } else {
                xatom.getMethod("DeleteProperty", Long::class.javaPrimitiveType).invoke(atom, root)
            }
        }
        if (done.isSuccess) {
            on = value
        } else {
            // Not X11, or the toolkit isn't open to Fuse: nothing more to try.
            enabled = false
            Log.warn("couldn't ask gamescope to compose Fuse", done.exceptionOrNull())
        }
    }
}
