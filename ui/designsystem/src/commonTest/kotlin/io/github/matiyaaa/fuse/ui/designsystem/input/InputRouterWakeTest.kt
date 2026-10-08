package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class InputRouterWakeTest {
    @Test fun heldWakePressCannotReachUnderlyingControlAfterOverlayCloses() = runTest {
        val router = InputRouter(this)
        var navigations = 0
        router.register(LayerPriority.SCREEN) { navigations++; NavResult.CONSUMED }
        val standby = router.register(LayerPriority.SYSTEM, modal = true) { event ->
            router.consumeUntilRelease(event.action)
            NavResult.CONSUMED
        }
        router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
        standby.remove()
        router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD, repeat = 1)
        assertEquals(0, navigations)
        router.release(PadButton.DPAD_RIGHT, InputSource.GAMEPAD)
        router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
        assertEquals(1, navigations)
    }
}
