package io.github.matiyaaa.fuse.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PadFamily
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource

/**
 * Turns Android key and joystick events into [InputRouter] presses. The router runs its own key
 * repeat, so Android's auto-repeat is dropped. Only navigation keys are taken here; letter keys are
 * left to Compose so text entry keeps working, and reach the router from the root key handler.
 *
 * Back is mapped to [PadButton.KEY_ESCAPE], which is Back in every layout (the B button swaps with A
 * in the Nintendo layout, the system Back key must not).
 */
class GamepadInput(private val router: InputRouter) {
    private var hatX = 0
    private var hatY = 0

    /** Handles [event] when it is a navigation key. Returns true when it was consumed. */
    fun onKey(event: KeyEvent): Boolean {
        // While a text field is open, keyboard Backspace, Enter and Space are typing, not navigation.
        // They go on to Compose, where the router hands them to the field.
        if (router.textInput != null && !isFromController(event) && event.keyCode in typingKeys) return false
        val button = buttonFor(event.keyCode) ?: return false
        val source = sourceOf(event, button)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                // Which controller this is (a DualSense, a Pro Controller...), so labels can follow it.
                if (isFromController(event)) event.device?.let { router.padIdentified(PadFamily.of(it.name, it.vendorId)) }
                router.press(button, source)
            }
            KeyEvent.ACTION_UP -> router.release(button, source)
        }
        return true
    }

    /** Left stick, D-pad hat and analog triggers. Returns true when the event came from a controller. */
    fun onMotion(event: MotionEvent): Boolean {
        val fromPad = event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!fromPad || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device
        router.stick(centered(event, device, MotionEvent.AXIS_X), centered(event, device, MotionEvent.AXIS_Y), InputSource.GAMEPAD)
        if (device?.getMotionRange(MotionEvent.AXIS_HAT_X, event.source) != null) {
            hatX = hat(event.getAxisValue(MotionEvent.AXIS_HAT_X), hatX, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT)
        }
        if (device?.getMotionRange(MotionEvent.AXIS_HAT_Y, event.source) != null) {
            hatY = hat(event.getAxisValue(MotionEvent.AXIS_HAT_Y), hatY, PadButton.DPAD_UP, PadButton.DPAD_DOWN)
        }
        trigger(event, device, PadButton.L2, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        trigger(event, device, PadButton.R2, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        return true
    }

    /** Focus lost or Fuse paused: nothing may stay held. */
    fun releaseAll() {
        hatX = 0
        hatY = 0
        router.releaseAll()
    }

    private fun centered(event: MotionEvent, device: InputDevice?, axis: Int): Float {
        val range = device?.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        return if (kotlin.math.abs(value) > range.flat) value else 0f
    }

    /** Presses and releases D-pad directions from a hat axis, touching only what the hat itself pressed. */
    private fun hat(value: Float, previous: Int, negative: PadButton, positive: PadButton): Int {
        val now = when {
            value <= -0.5f -> -1
            value >= 0.5f -> 1
            else -> 0
        }
        if (now == previous) return previous
        when (previous) {
            -1 -> router.release(negative, InputSource.GAMEPAD)
            1 -> router.release(positive, InputSource.GAMEPAD)
        }
        when (now) {
            -1 -> router.press(negative, InputSource.GAMEPAD)
            1 -> router.press(positive, InputSource.GAMEPAD)
        }
        return now
    }

    private fun trigger(event: MotionEvent, device: InputDevice?, button: PadButton, vararg axes: Int) {
        val present = axes.filter { device?.getMotionRange(it, event.source) != null }
        if (present.isEmpty()) return
        router.trigger(button, present.maxOf { event.getAxisValue(it) }, InputSource.GAMEPAD)
    }

    private fun sourceOf(event: KeyEvent, button: PadButton): InputSource = when {
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) -> InputSource.GAMEPAD
        event.isFromSource(InputDevice.SOURCE_DPAD) -> InputSource.GAMEPAD
        button in PAD_ONLY -> InputSource.GAMEPAD
        else -> InputSource.KEYBOARD
    }

    companion object {
        private val PAD_ONLY = setOf(
            PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2,
            PadButton.L3, PadButton.R3, PadButton.START, PadButton.SELECT, PadButton.MODE,
        )

        /** Navigation keys Fuse takes before Compose sees them. Letters are not in this list. */
        fun buttonFor(keyCode: Int): PadButton? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> PadButton.A
            KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
            KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
            KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
            KeyEvent.KEYCODE_BUTTON_MODE -> PadButton.MODE
            KeyEvent.KEYCODE_DPAD_UP -> PadButton.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> PadButton.KEY_ENTER
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> PadButton.KEY_ESCAPE
            KeyEvent.KEYCODE_TAB -> PadButton.KEY_TAB
            KeyEvent.KEYCODE_PAGE_UP -> PadButton.KEY_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> PadButton.KEY_PAGE_DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> PadButton.KEY_HOME
            KeyEvent.KEYCODE_MENU -> PadButton.START
            KeyEvent.KEYCODE_SEARCH -> PadButton.KEY_SLASH
            else -> null
        }
    }

    private fun isFromController(event: KeyEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)

    private val typingKeys = setOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
}
