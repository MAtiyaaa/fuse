package io.github.matiyaaa.fuse.ui.designsystem.input

import androidx.compose.ui.input.pointer.PointerButtons
import io.github.matiyaaa.fuse.model.NavAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MouseButtonsTest {
    @Test
    fun backButtonGoesBack() {
        assertEquals(NavAction.BACK, MouseButtons.action(PointerButtons(isBackPressed = true)))
        // Even while typing: it is never a letter.
        assertEquals(NavAction.BACK, MouseButtons.action(PointerButtons(isBackPressed = true), typing = true))
    }

    @Test
    fun rightButtonOpensOptionsUnlessTyping() {
        assertEquals(NavAction.CONTEXT, MouseButtons.action(PointerButtons(isSecondaryPressed = true)))
        assertNull(MouseButtons.action(PointerButtons(isSecondaryPressed = true), typing = true))
    }

    @Test
    fun theMainButtonIsLeftToClicks() {
        assertNull(MouseButtons.action(PointerButtons(isPrimaryPressed = true)))
        // Both at once is a chord, not a request for options.
        assertNull(MouseButtons.action(PointerButtons(isPrimaryPressed = true, isSecondaryPressed = true)))
        assertNull(MouseButtons.action(PointerButtons(isForwardPressed = true)))
        assertNull(MouseButtons.action(PointerButtons()))
    }
}
