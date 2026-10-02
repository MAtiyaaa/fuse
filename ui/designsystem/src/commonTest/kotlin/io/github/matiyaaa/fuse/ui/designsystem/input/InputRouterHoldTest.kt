package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Holding Options (X) changes what the directions do on a layer that asks for it; a tap stays a tap. */
@OptIn(ExperimentalCoroutinesApi::class)
class InputRouterHoldTest {
    private val pad = InputSource.GAMEPAD

    private fun TestScope.router(hold: NavAction? = NavAction.CONTEXT, profile: InputProfile = InputProfile()): Pair<InputRouter, MutableList<NavEvent>> {
        val seen = mutableListOf<NavEvent>()
        val router = InputRouter(this, profile)
        router.register(LayerPriority.SCREEN, holdModifier = hold) { e -> seen += e; NavResult.MOVED }
        return router to seen
    }

    @Test
    fun directionsPressedWhileHeldCarryTheModifier() = runTest {
        val (r, seen) = router()
        r.press(PadButton.X, pad)
        assertEquals(NavAction.CONTEXT, r.heldModifier.value)
        r.press(PadButton.DPAD_RIGHT, pad)
        r.release(PadButton.DPAD_RIGHT, pad)
        r.press(PadButton.DPAD_DOWN, pad)
        r.release(PadButton.DPAD_DOWN, pad)
        r.release(PadButton.X, pad)
        runCurrent()
        assertEquals(listOf(NavAction.RIGHT, NavAction.DOWN), seen.map { it.action })
        assertEquals(listOf(NavAction.CONTEXT, NavAction.CONTEXT), seen.map { it.modifier })
        assertNull(r.heldModifier.value)
    }

    @Test
    fun aQuickPressIsStillOptions() = runTest {
        val (r, seen) = router()
        r.press(PadButton.X, pad)
        assertEquals(emptyList(), seen.map { it.action }, "nothing acts while it is held")
        r.release(PadButton.X, pad)
        runCurrent()
        assertEquals(listOf(NavAction.CONTEXT), seen.map { it.action })
        assertNull(seen.single().modifier)
    }

    @Test
    fun repeatsWhileHeldKeepTheModifier() = runTest {
        val (r, seen) = router()
        r.press(PadButton.X, pad)
        r.press(PadButton.DPAD_RIGHT, pad)
        advanceTimeBy(InputProfile().repeatDelayMs + InputProfile().repeatIntervalMs * 2L + 10)
        r.release(PadButton.DPAD_RIGHT, pad)
        r.release(PadButton.X, pad)
        runCurrent()
        val rights = seen.filter { it.action == NavAction.RIGHT }
        assert(rights.size >= 3) { "repeats: $rights" }
        assert(rights.all { it.modifier == NavAction.CONTEXT })
        assert(seen.none { it.action == NavAction.CONTEXT }) { "no Options after a resize" }
    }

    @Test
    fun aLayerWithoutAHoldModifierGetsOptionsAtOnce() = runTest {
        val (r, seen) = router(hold = null)
        r.press(PadButton.X, pad)
        runCurrent()
        assertEquals(listOf(NavAction.CONTEXT), seen.map { it.action })
        assertNull(r.heldModifier.value)
        r.release(PadButton.X, pad)
        r.press(PadButton.DPAD_LEFT, pad)
        r.release(PadButton.DPAD_LEFT, pad)
        assertNull(seen.last().modifier)
    }

    @Test
    fun directionsAfterLettingGoAreOrdinaryAgain() = runTest {
        val (r, seen) = router()
        r.press(PadButton.X, pad)
        r.press(PadButton.DPAD_UP, pad)
        r.release(PadButton.DPAD_UP, pad)
        r.release(PadButton.X, pad)
        r.press(PadButton.DPAD_UP, pad)
        r.release(PadButton.DPAD_UP, pad)
        runCurrent()
        assertEquals(listOf(NavAction.CONTEXT, null), seen.map { it.modifier })
    }

    @Test
    fun theButtonFollowsTheLayoutSwap() = runTest {
        // Pads that report buttons by position swap X and Y; Options follows to Y.
        val (r, seen) = router(profile = InputProfile(swapConfirmBack = true))
        r.press(PadButton.Y, pad)
        r.press(PadButton.DPAD_RIGHT, pad)
        r.release(PadButton.DPAD_RIGHT, pad)
        r.release(PadButton.Y, pad)
        runCurrent()
        assertEquals(NavAction.CONTEXT, seen.single().modifier)
    }
}
