package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerDisplayPacingTest {
    @BeforeTest @AfterTest fun reset() = FramePacing.reset()

    @Test fun unlikeDisplaysCannotClassifyOneAnotherAsLate() {
        val fast = Any()
        val slow = Any()
        repeat(64) { i ->
            FramePacing.frameAt(i * 8_333_333L, fast)
            FramePacing.frameAt(i * 16_666_667L, slow)
        }
        assertEquals(8_333_333L, FramePacing.intervalFor(fast))
        assertEquals(16_666_667L, FramePacing.intervalFor(slow))
        assertFalse(FramePacing.underLoadFor(fast))
        assertFalse(FramePacing.underLoadFor(slow))
        var t = 63 * 8_333_333L
        repeat(8) {
            t += 25_000_000L
            FramePacing.frameAt(t, fast)
            FramePacing.frameAt((64 + it) * 16_666_667L, slow)
        }
        assertTrue(FramePacing.underLoadFor(fast))
        assertFalse(FramePacing.underLoadFor(slow))
        var fastShown = 0
        repeat(10) {
            t += 25_000_000L
            FramePacing.frameAt(t, fast)
            val first = FramePacing.shouldDrawDecoration(fast)
            val second = FramePacing.shouldDrawDecoration(fast)
            assertEquals(first, second, "loops sharing one frame must not starve each other")
            if (first) fastShown++
        }
        assertEquals(5, fastShown)
        assertTrue(FramePacing.shouldDrawDecoration(slow))
    }

    @Test fun healthyInputDoesNotFreezeAmbienceButPressureCan() {
        val clock = Any()
        val input = 1_000_000_000L
        FramePacing.input(input)
        assertFalse(FramePacing.decorationHeld(input + 1, clock))
        FramePacing.devicePressure = DevicePressure.HOT
        assertTrue(FramePacing.decorationHeld(input + 1, clock))
        FramePacing.moving(clock, true)
        assertTrue(FramePacing.decorationHeld(input + 500_000_000, clock))
        assertFalse(FramePacing.decorationHeld(input + 1_600_000_000, clock))
    }
}
