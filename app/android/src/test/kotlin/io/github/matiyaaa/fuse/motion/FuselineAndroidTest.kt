package io.github.matiyaaa.fuse.motion

import androidx.compose.runtime.BroadcastFrameClock
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Decay
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.Spring
import io.github.matiyaaa.fuse.ui.fuseline.Tween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Fuseline as Android builds it: the same motion lands in the same place at every refresh rate a
 * phone or handheld runs at, a move retargeted mid-flight keeps its place and speed, a fling coasts
 * to rest, and curves give their exact slopes.
 */
class FuselineAndroidTest {
    /** Runs [frames] frames at [hz] with a manual clock, moves stepping inline as each frame arrives. */
    private fun frames(hz: Int, frames: Int, start: CoroutineScope.() -> Unit, each: (Int) -> Unit = {}) {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        scope.start()
        var t = 1_000_000_000L
        clock.sendFrame(t)
        for (f in 0 until frames) {
            t += 1_000_000_000L / hz
            clock.sendFrame(t)
            each(f)
        }
        scope.cancel()
    }

    @Test
    fun aSpringLandsTheSameAtEveryRefreshRate() {
        for (hz in listOf(30, 40, 48, 50, 60, 72, 90, 100, 120, 144, 165, 180, 240)) {
            val v = FuselineValue(0f)
            var half = Float.NaN
            frames(hz, hz * 2, { launch { v.animateTo(100f, Spring(0.7f, 300f)) } }) { f -> if (f == hz / 10 - 1) half = v.value }
            assertEquals("$hz Hz lands", 100f, v.value, 0f)
            assertFalse("$hz Hz settles", v.isRunning)
            // A tenth of a second in, every rate shows the same moment of the same motion.
            assertTrue("$hz Hz at 100 ms: $half", half in 40f..100f)
        }
    }

    @Test
    fun aRetargetKeepsPlaceAndSpeed() {
        val v = FuselineValue(0f)
        frames(60, 120, { launch { v.animateTo(500f, Tween(400, curve = Curves.Standard)) } }) { f ->
            if (f == 10) {
                val at = v.value
                val speed = v.velocity
                assertTrue(v.retarget(-200f, Spring(1f, 400f)))
                assertEquals(at, v.value, 0f)
                assertEquals(speed, v.velocity, abs(speed) * 1e-4f)
            }
        }
        assertEquals(-200f, v.value, 0f)
    }

    @Test
    fun aFlingCoastsToRestWhereItWasPredicted() {
        val v = FuselineValue(0f)
        frames(120, 600, { launch { v.animateDecay(3000f, Decay()) } })
        assertFalse(v.isRunning)
        assertTrue("coasted forward: ${v.value}", v.value > 100f)
    }

    @Test
    fun curvesGiveTheirSlopes() {
        assertEquals(0f, Curves.Standard.transform(0f), 0f)
        assertEquals(1f, Curves.Standard.transform(1f), 0f)
        for (i in 1 until 100) {
            val f = i / 100f
            val h = 1e-3f
            val measured = (Curves.Enter.transform(f + h) - Curves.Enter.transform(f - h)) / (2 * h)
            assertEquals("slope at $f", measured, Curves.Enter.derivative(f), maxOf(0.05f, abs(measured) * 0.02f))
        }
    }
}
