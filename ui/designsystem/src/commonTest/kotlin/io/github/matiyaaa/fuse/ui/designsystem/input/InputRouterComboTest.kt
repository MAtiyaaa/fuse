package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class InputRouterComboTest {
    private val pad = InputSource.GAMEPAD

    private fun TestScope.router(profile: InputProfile = InputProfile()): Pair<InputRouter, MutableList<Any>> {
        val seen = mutableListOf<Any>()
        val router = InputRouter(this, profile)
        router.register(LayerPriority.SCREEN) { e -> seen += e.action; NavResult.CONSUMED }
        router.onCaptureCombo = { seen += it }
        return router to seen
    }

    @Test
    fun bothSticksTappedTakeOneTap() = runTest {
        val (r, seen) = router()
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        advanceTimeBy(200)
        r.release(PadButton.R3, pad)
        r.release(PadButton.L3, pad)
        runCurrent()
        assertEquals(listOf<Any>(ComboGesture.TAP), seen)
    }

    @Test
    fun holdingBothSticksIsOneHoldAndNoTap() = runTest {
        val (r, seen) = router()
        r.press(PadButton.R3, pad)
        r.press(PadButton.L3, pad)
        advanceTimeBy(InputRouter.COMBO_HOLD_MS + 50)
        runCurrent()
        advanceTimeBy(2_000)
        r.release(PadButton.L3, pad)
        r.release(PadButton.R3, pad)
        runCurrent()
        assertEquals(listOf<Any>(ComboGesture.HOLD), seen)
    }

    @Test
    fun oneStickAloneDoesNothing() = runTest {
        val (r, seen) = router()
        r.press(PadButton.L3, pad)
        advanceTimeBy(2_000)
        r.release(PadButton.L3, pad)
        runCurrent()
        assertEquals(emptyList<Any>(), seen)
    }

    @Test
    fun aRemappedStickStillActsAloneButNotInTheCombo() = runTest {
        val (r, seen) = router(InputProfile(remap = mapOf(PadButton.R3 to NavAction.SEARCH)))
        r.press(PadButton.R3, pad)
        r.release(PadButton.R3, pad)
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        r.release(PadButton.R3, pad)
        r.release(PadButton.L3, pad)
        runCurrent()
        assertEquals(listOf<Any>(NavAction.SEARCH, ComboGesture.TAP), seen)
    }

    @Test
    fun noComboWhileMappingButtonsOrTestingTheController() = runTest {
        val (r, seen) = router()
        val captured = mutableListOf<PadButton>()
        r.capture = { captured += it }
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        r.release(PadButton.R3, pad)
        r.release(PadButton.L3, pad)
        r.capture = null
        r.exclusive = { _, _ -> }
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emptyList<Any>(), seen)
        assertEquals(listOf(PadButton.L3, PadButton.R3), captured)
    }

    @Test
    fun releaseAllForgetsAHalfDoneCombo() = runTest {
        val (r, seen) = router()
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        r.releaseAll()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emptyList<Any>(), seen)
        // A fresh press afterwards works again.
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        r.release(PadButton.L3, pad)
        r.release(PadButton.R3, pad)
        runCurrent()
        assertEquals(listOf<Any>(ComboGesture.TAP), seen)
    }

    @Test
    fun withNoListenerTheSticksAreOrdinaryButtons() = runTest {
        val (r, seen) = router(InputProfile(remap = mapOf(PadButton.L3 to NavAction.HOME)))
        r.onCaptureCombo = null
        r.press(PadButton.L3, pad)
        r.press(PadButton.R3, pad)
        r.release(PadButton.R3, pad)
        r.release(PadButton.L3, pad)
        runCurrent()
        assertEquals(listOf<Any>(NavAction.HOME), seen)
    }
}
