package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fuseline 2's in-place retarget: a spring given a new target carries on, smoothly, without a new move. */
class RetargetTest {
    private val frame = 16_666_667L

    @Test
    fun `a spring retargeted in place carries on smoothly and lands on the new target`() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        val v = FuselineValue(0f)
        var arrived = false
        scope.launch { v.animateTo(100f, spring(1f, 200f)); arrived = true }
        var t = 1_000_000_000L
        clock.sendFrame(t)
        val seen = ArrayList<Float>()
        repeat(10) { t += frame; clock.sendFrame(t); seen += v.value }
        assertTrue(v.retarget(-50f, spring(1f, 200f)))
        assertEquals(-50f, v.targetValue)
        // The next frame moves on from where it was (no stall, no jump): it keeps its speed a moment.
        t += frame; clock.sendFrame(t)
        val step = v.value - seen.last()
        assertTrue(step > 0f, "still heading up for a moment, under its own speed: $step")
        assertTrue(abs(step) < 30f, "no jump: $step")
        repeat(300) { t += frame; clock.sendFrame(t) }
        assertEquals(-50f, v.value)
        assertTrue(arrived, "the move's caller returns once it lands on the new target")
        assertFalse(v.isRunning)
        // At rest, there is nothing to retarget: a new move is needed.
        assertFalse(v.retarget(10f))
        scope.cancel()
    }

    @Test
    fun `a tween under way isn't retargeted (it has its own time), only a spring is`() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        val v = FuselineValue(0f)
        scope.launch { v.animateTo(1f, tween(500)) }
        clock.sendFrame(1_000_000_000L)
        assertFalse(v.retarget(2f))
        scope.cancel()
    }

    @Test
    fun `retargeted every frame, it follows and settles exactly where the last target was`() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        val v = FuselineValue(0f)
        scope.launch { v.animateTo(0.5f, spring(0.8f, 300f)) }
        var t = 1_000_000_000L
        clock.sendFrame(t)
        for (f in 1..60) {
            assertTrue(v.retarget(f * 10f, spring(0.8f, 300f)))
            t += frame
            clock.sendFrame(t)
        }
        // It kept up: well on its way to 600 already.
        assertTrue(v.value > 400f, "followed: ${v.value}")
        repeat(400) { t += frame; clock.sendFrame(t) }
        assertEquals(600f, v.value)
        scope.cancel()
    }
}
