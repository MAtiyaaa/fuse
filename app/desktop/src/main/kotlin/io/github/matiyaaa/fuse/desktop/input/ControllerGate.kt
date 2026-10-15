package io.github.matiyaaa.fuse.desktop.input

/**
 * Whether a controller press is Fuse's. On a desktop that is when Fuse's window has focus: the
 * controller belongs to whatever is in front. In Steam's Game Mode the screen is Fuse's, and Steam's
 * own keyboard and menus (typing a Wi-Fi password, keyboard mode) take the window's focus without
 * always giving it back under gamescope, which left the controller dead until the screen was
 * touched. There a press is Fuse's whenever Fuse is showing and no game is running (one Fuse
 * started, or any game Steam runs), focus or not, and taking it brings the focus back.
 *
 * Right after a game Fuse started ends, a desktop's window manager often refuses to hand Fuse the
 * focus back (focus-stealing prevention, XWayland), which left the controller dead and Fuse looking
 * frozen until it was clicked. For [RETURN_MS] after the game, a press is Fuse's on a desktop too,
 * and taking it brings the focus back.
 */
internal class ControllerGate(private val gameMode: Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var focused: Boolean = false
        set(value) {
            field = value
            if (value) returnedUntil = 0L
        }

    /** Until when a game Fuse started has just ended and Fuse is coming back to the front. */
    @Volatile private var returnedUntil: Long = 0L

    /** A game Fuse started has ended: Fuse takes presses while it gets the focus back. */
    fun gameEnded() {
        playing = false
        returnedUntil = clock() + RETURN_MS
    }

    /** Fuse's window is on screen (not stepped aside for a game). */
    @Volatile var showing: Boolean = true

    /** A game Fuse started is running. */
    @Volatile var playing: Boolean = false

    /** Steam says a game of its own is running (checked while Fuse is without focus in Game Mode). */
    @Volatile var steamGame: Boolean = false

    /** A press now is Fuse's. */
    fun admits(): Boolean = focused || (gameMode && showing && !playing && !steamGame) || (showing && !playing && clock() < returnedUntil)

    /** A press was taken without focus: the window should ask for it back. */
    fun reclaims(): Boolean = !focused && admits()

    /** Whether to keep asking Steam about its games: only while the answer can matter. */
    val watchesSteam: Boolean get() = gameMode && !focused && showing && !playing

    companion object {
        const val RETURN_MS = 30_000L
    }
}
