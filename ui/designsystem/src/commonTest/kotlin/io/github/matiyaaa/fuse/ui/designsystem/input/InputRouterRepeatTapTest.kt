package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A button that repeats while held (X and A on the on-screen keyboard) acts once per tap. */
@OptIn(ExperimentalCoroutinesApi::class)
class InputRouterRepeatTapTest {
    private val pad = InputSource.GAMEPAD

    @Test
    fun aTapOfARepeatingButtonActsOnce() = runTest {
        val seen = mutableListOf<NavAction>()
        val router = InputRouter(this, InputProfile())
        router.register(LayerPriority.DIALOG) { e -> seen += e.action; NavResult.ACTIVATED }
            .update(true, true, false, { e -> seen += e.action; NavResult.ACTIVATED }, repeats = setOf(NavAction.CONTEXT, NavAction.SELECT))
        for (button in listOf(PadButton.X, PadButton.A)) {
            router.press(button, pad)
            runCurrent()
            router.release(button, pad)
            runCurrent()
        }
        assertEquals(listOf(NavAction.CONTEXT, NavAction.SELECT), seen, "one tap, one delete (or one key typed)")
    }

    @Test
    fun aTapOfXOnAnOrdinaryScreenIsStillOneOptions() = runTest {
        val seen = mutableListOf<NavAction>()
        val router = InputRouter(this, InputProfile())
        router.register(LayerPriority.SCREEN, longPress = true) { e -> seen += e.action; NavResult.ACTIVATED }
        router.press(PadButton.A, pad)
        runCurrent()
        router.release(PadButton.A, pad)
        runCurrent()
        assertEquals(listOf(NavAction.SELECT), seen, "a select waiting on a long press still acts when let go")
    }
}
