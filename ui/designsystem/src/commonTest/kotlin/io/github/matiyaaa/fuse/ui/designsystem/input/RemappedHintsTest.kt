package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.icons.remappedHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hint line names the button that does an action now, remaps included. */
class RemappedHintsTest {
    @Test
    fun noRemapsLeaveEveryHintAlone() {
        assertTrue(remappedHints(InputProfile()).isEmpty())
        assertTrue(remappedHints(InputProfile(swapConfirmBack = true, swapShoulders = true)).isEmpty())
    }

    @Test
    fun confirmMovedToYIsShownOnY() {
        // A now opens options and Y confirms: the confirm hint shows Y, the options hint A.
        val p = InputProfile(remap = mapOf(PadButton.A to NavAction.CONTEXT, PadButton.Y to NavAction.SELECT))
        val hints = remappedHints(p)
        assertEquals(PadButton.Y, hints[HintButton.CONFIRM])
        assertEquals(PadButton.Y, hints[HintButton.HOLD_CONFIRM])
        // X still opens options, and X is the button the options hint shows.
        assertEquals(null, hints[HintButton.OPTIONS])
        // Search lost Y and nothing else does it from the pad, so its hint stays as it was.
        assertEquals(null, hints[HintButton.SEARCH])
    }

    @Test
    fun aRemapThatKeepsTheShownButtonChangesNothing() {
        // B also confirms now, but A still does, and A is what the hint shows.
        val p = InputProfile(remap = mapOf(PadButton.B to NavAction.SELECT, PadButton.START to NavAction.BACK))
        val hints = remappedHints(p)
        assertEquals(null, hints[HintButton.CONFIRM])
        // Back left B for Start.
        assertEquals(PadButton.START, hints[HintButton.BACK])
    }

    @Test
    fun swapsDecideWhichButtonTheHintShows() {
        // Confirm and back swapped: B confirms, so remapping A changes no confirm hint.
        val p = InputProfile(swapConfirmBack = true, remap = mapOf(PadButton.A to NavAction.HOME))
        val hints = remappedHints(p)
        assertEquals(null, hints[HintButton.CONFIRM])
        assertEquals(null, hints[HintButton.BACK])
    }

    @Test
    fun theMenuHintFollowsStart() {
        val p = InputProfile(remap = mapOf(PadButton.START to NavAction.HOME))
        // The right stick still opens the quick menu, so the hint shows it.
        assertEquals(PadButton.R3, remappedHints(p)[HintButton.MENU])
    }

    @Test
    fun profileMappingMatchesTheRouter() {
        val p = InputProfile(swapConfirmBack = true, remap = mapOf(PadButton.X to NavAction.HOME))
        assertEquals(NavAction.HOME, p.actionFor(PadButton.X))
        assertEquals(NavAction.SEARCH, p.defaultActionFor(PadButton.X))
        assertEquals(NavAction.SELECT, p.actionFor(PadButton.B))
    }
}
