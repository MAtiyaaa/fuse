package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InputRouterMirrorTest {
    @Test fun aMirroredLayoutMovesTheWayTheScreenReads() = runTest {
        val router = InputRouter(this)
        val seen = ArrayList<NavAction>()
        router.register(LayerPriority.SCREEN) { seen += it.action; NavResult.CONSUMED }
        val sent = listOf(NavAction.LEFT, NavAction.RIGHT, NavAction.PREVIOUS_SECTION, NavAction.NEXT_SECTION, NavAction.PAGE_PREVIOUS, NavAction.PAGE_NEXT, NavAction.UP, NavAction.SELECT)
        sent.forEach { router.dispatch(it, InputSource.GAMEPAD) }
        assertEquals(sent, seen, "as they come, left to right")
        seen.clear()
        router.mirrored = true
        sent.forEach { router.dispatch(it, InputSource.GAMEPAD) }
        assertEquals(
            listOf(NavAction.RIGHT, NavAction.LEFT, NavAction.NEXT_SECTION, NavAction.PREVIOUS_SECTION, NavAction.PAGE_NEXT, NavAction.PAGE_PREVIOUS, NavAction.UP, NavAction.SELECT),
            seen,
            "right to left: across is swapped, up and the rest stay",
        )
        // A pressed D-pad and keyboard arrow go the same way.
        seen.clear()
        router.press(PadButton.DPAD_RIGHT, InputSource.GAMEPAD)
        router.release(PadButton.DPAD_RIGHT, InputSource.GAMEPAD)
        router.press(PadButton.KEY_LEFT, InputSource.KEYBOARD)
        router.release(PadButton.KEY_LEFT, InputSource.KEYBOARD)
        assertEquals(listOf(NavAction.LEFT, NavAction.RIGHT), seen)
    }
}
