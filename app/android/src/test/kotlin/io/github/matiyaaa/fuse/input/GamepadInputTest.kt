package io.github.matiyaaa.fuse.input

import android.view.KeyEvent
import io.github.matiyaaa.fuse.model.PadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadInputTest {
    @Test
    fun faceButtonsKeepTheirPosition() {
        assertEquals(PadButton.A, GamepadInput.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PadButton.B, GamepadInput.buttonFor(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(PadButton.MODE, GamepadInput.buttonFor(KeyEvent.KEYCODE_BUTTON_MODE))
        assertEquals(PadButton.L2, GamepadInput.buttonFor(KeyEvent.KEYCODE_BUTTON_L2))
    }

    @Test
    fun systemBackIsAlwaysBackNotTheBButton() {
        // B swaps with A in the Nintendo layout; the Back key must not.
        assertEquals(PadButton.KEY_ESCAPE, GamepadInput.buttonFor(KeyEvent.KEYCODE_BACK))
        assertEquals(PadButton.KEY_ESCAPE, GamepadInput.buttonFor(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun remotesAndKeyboardsNavigate() {
        assertEquals(PadButton.KEY_ENTER, GamepadInput.buttonFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PadButton.KEY_ENTER, GamepadInput.buttonFor(KeyEvent.KEYCODE_ENTER))
        assertEquals(PadButton.DPAD_LEFT, GamepadInput.buttonFor(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun lettersAndVolumeAreLeftAlone() {
        assertNull(GamepadInput.buttonFor(KeyEvent.KEYCODE_F))
        assertNull(GamepadInput.buttonFor(KeyEvent.KEYCODE_SPACE))
        assertNull(GamepadInput.buttonFor(KeyEvent.KEYCODE_DEL))
        assertNull(GamepadInput.buttonFor(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
