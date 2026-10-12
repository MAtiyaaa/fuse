package io.github.matiyaaa.fuse.desktop.input

/**
 * Whether a controller press is Fuse's. On a desktop that is when Fuse's window has focus: the
 * controller belongs to whatever is in front. In Steam's Game Mode the screen is Fuse's, and Steam's
 * own keyboard and menus (typing a Wi-Fi password, keyboard mode) take the window's focus without
 * always giving it back under gamescope, which left the controller dead until the screen was
 * touched. There a press is Fuse's whenever Fuse is showing and no game is running (one Fuse
 * started, or any game Steam runs), focus or not, and taking it brings the focus back.
 */
internal class ControllerGate(private val gameMode: Boolean) {
    @Volatile var focused: Boolean = false

    /** Fuse's window is on screen (not stepped aside for a game). */
    @Volatile var showing: Boolean = true

    /** A game Fuse started is running. */
    @Volatile var playing: Boolean = false

    /** Steam says a game of its own is running (checked while Fuse is without focus in Game Mode). */
    @Volatile var steamGame: Boolean = false

    /** A press now is Fuse's. */
    fun admits(): Boolean = focused || (gameMode && showing && !playing && !steamGame)

    /** A press was taken without focus: the window should ask for it back. */
    fun reclaims(): Boolean = !focused && admits()

    /** Whether to keep asking Steam about its games: only while the answer can matter. */
    val watchesSteam: Boolean get() = gameMode && !focused && showing && !playing
}
