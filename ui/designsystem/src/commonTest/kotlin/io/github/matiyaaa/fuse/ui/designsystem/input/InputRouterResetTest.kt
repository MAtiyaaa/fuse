package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class InputRouterResetTest {
    private val pad = InputSource.GAMEPAD

    @Test
    fun startAndSelectHeldResetEvenWhileAScreenHasTheController() = runTest {
        val router = InputRouter(this, InputProfile())
        var resets = 0
        router.onResetCombo = { resets++ }
        router.exclusive = { _, _ -> }
        router.press(PadButton.START, pad)
        router.press(PadButton.SELECT, pad)
        advanceTimeBy(InputRouter.RESET_HOLD_MS + 50)
        runCurrent()
        assertEquals(1, resets)
        assertNull(router.exclusive)
    }

    @Test
    fun lettingGoEarlyDoesNothing() = runTest {
        val router = InputRouter(this, InputProfile())
        var resets = 0
        router.onResetCombo = { resets++ }
        router.press(PadButton.START, pad)
        router.press(PadButton.SELECT, pad)
        advanceTimeBy(1_000)
        router.release(PadButton.SELECT, pad)
        advanceTimeBy(InputRouter.RESET_HOLD_MS)
        runCurrent()
        assertEquals(0, resets)
    }

    @Test
    fun confirmLeftOnlyOnGuideIsSetAsideWhereGuideNeverArrives() {
        // What an older guided setup could save on Linux: A became Home, Confirm moved to Guide.
        val broken = InputProfile(remap = mapOf(PadButton.A to NavAction.HOME, PadButton.MODE to NavAction.SELECT))
        assertEquals(emptyMap(), broken.usable(padButtons(guide = false)).remap)
        // Where Guide reaches the app, the same mapping still works and is kept.
        assertEquals(broken, broken.usable(padButtons(guide = true)))
    }

    @Test
    fun guideIsNeverMappedOntoWhenItIsLeftOut() {
        val next = InputProfile().withMapping(PadButton.A, NavAction.HOME, padButtons(guide = false))
        // Confirm has no other pad button to go to, so the mapping is refused rather than lost.
        assertNull(next)
    }
}
