package io.github.matiyaaa.fuse.desktop.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whose a controller press is, through what took the controller away on the Deck. */
class ControllerGateTest {
    @Test
    fun onADesktopTheControllerIsTheFrontWindows() {
        val gate = ControllerGate(gameMode = false)
        gate.focused = true
        assertTrue(gate.admits())
        // Another program in front: its controller, not Fuse's, and nothing is reclaimed.
        gate.focused = false
        assertFalse(gate.admits())
        assertFalse(gate.reclaims())
        assertFalse(gate.watchesSteam)
    }

    @Test
    fun afterSteamsKeyboardOrWifiMenuTheControllerComesBack() {
        val gate = ControllerGate(gameMode = true)
        gate.focused = true
        // Steam's keyboard (or the Wi-Fi menu) takes the focus and doesn't give it back.
        gate.focused = false
        // While Steam hasn't answered whether a game runs, nothing is taken.
        gate.steamGame = true
        assertFalse(gate.admits())
        gate.steamGame = false
        // Back on Fuse's screen: the press is Fuse's, and the focus is asked back.
        assertTrue(gate.admits())
        assertTrue(gate.reclaims())
        gate.focused = true
        assertFalse(gate.reclaims())
    }

    @Test
    fun aGameInFrontKeepsItsController() {
        val gate = ControllerGate(gameMode = true)
        // A game Fuse started: Fuse stepped aside and its presses are the game's.
        gate.playing = true
        gate.showing = false
        assertFalse(gate.admits())
        assertFalse(gate.watchesSteam)
        // A game Steam started from its own library, with Fuse still on screen behind it.
        gate.playing = false
        gate.showing = true
        gate.steamGame = true
        assertFalse(gate.admits())
        assertTrue(gate.watchesSteam)
        // The game ended: Fuse's again.
        gate.steamGame = false
        assertTrue(gate.admits())
    }
}
