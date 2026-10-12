package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.input.JoydevFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JoydevFilterTest {
    @Test fun readsKernelBitmapsMostSignificantWordFirst() {
        assertEquals(setOf(0, 1), JoydevFilter.bits("3"))
        assertEquals(setOf(64 + 4), JoydevFilter.bits("10 0"))
        // Word 4 (bits 256..319) holding bits 48 and 49: BTN_SOUTH (0x130) and BTN_EAST (0x131).
        assertEquals(setOf(0x130, 0x131), JoydevFilter.bits("3000000000000 0 0 0 0"))
    }

    @Test fun anXboxPadIsAController() {
        // xpad: BTN_SOUTH.. BTN_THUMBR and BTN_MODE (0x130-0x13e), as an Xbox One pad reports them.
        val key = "7cdb000000000000 0 0 0 0"
        assertNull(JoydevFilter.rejection(key, "0", "0"))
    }

    @Test fun aPointerWithAbsoluteAxesIsNot() {
        // A VM or tablet-mode mouse: BTN_LEFT, BTN_RIGHT, BTN_MIDDLE (0x110-0x112) and absolute X/Y.
        val key = "70000 0 0 0 0"
        assertNotNull(JoydevFilter.rejection(key, "0", "0"))
    }

    @Test fun aTouchpadOrTabletIsNot() {
        // BTN_TOUCH (0x14a) and BTN_TOOL_FINGER (0x145) without gamepad buttons.
        val key = ((1uL shl (0x14a - 0x140)) or (1uL shl (0x145 - 0x140))).toString(16) + " 0 0 0 0 0"
        assertNotNull(JoydevFilter.rejection(key, "0", "0"))
    }

    @Test fun aControllersMotionSensorIsNot() {
        val key = "7cdb000000000000 0 0 0 0"
        assertNotNull(JoydevFilter.rejection(key, "0", "40"))
    }

    @Test fun unreadableCapabilitiesKeepTheNode() {
        assertNull(JoydevFilter.rejection(null, null, null))
    }
}
