package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuseline 3.1's decoration: a loop paced to the updates it needs, not to the display, and holding
 * still while the person is doing something, carrying on from there without a jump. Frames and
 * waits both run on virtual time, so the counts are exact.
 */
class DecorationTest {
    @BeforeTest @AfterTest
    fun freshPacing() = FramePacing.reset()

    /**
     * A 125 Hz display (8 ms frames, so frames and waits share one millisecond clock) for [seconds],
     * with [onFrame] before each frame; returns the updates given.
     */
    private fun run(seconds: Double, fps: Int, onFrame: (Long) -> Unit = {}): List<Long> {
        val scheduler = TestCoroutineScheduler()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler) + clock + Job())
        val updates = ArrayList<Long>()
        scope.launch { decorationFrames(fps, infinite = false) { updates += it } }
        scheduler.runCurrent()
        val step = 8_000_000L
        var t = 1_000_000_000L
        repeat((seconds * 125).toInt()) {
            scheduler.advanceTimeBy(step / 1_000_000L)
            scheduler.runCurrent()
            t += step
            onFrame(t)
            clock.sendFrame(t)
            scheduler.runCurrent()
        }
        scope.cancel()
        return updates
    }

    @Test
    fun aThirtyASecondLoopWakesForItsUpdatesNotForEveryFrame() {
        val before = FramePacing.decorationWakeups
        val updates = run(2.0, fps = 30)
        val wakes = FramePacing.decorationWakeups - before
        // Every update it needs, about 30 a second.
        println("Decoration: ${updates.size} updates and $wakes wakes in 250 frames")
        assertTrue(updates.size in 56..64, "updates: ${updates.size}")
        // About one wake per update, not one per frame of the display (250 in two seconds).
        assertTrue(wakes <= 80, "woke $wakes times for 250 frames")
    }

    @Test
    fun inputHoldsDecorationAndItCarriesOnWithoutAJump() {
        // A button every 100 ms for the first second, then nothing.
        var pressedUntil = 0L
        val updates = run(2.0, fps = 30) { t ->
            if (t < 2_000_000_000L && (t / 100_000_000L) != (pressedUntil / 100_000_000L)) {
                pressedUntil = t
                FramePacing.input(monotonicNanos())
            }
        }
        // Played time only moves forward and never leaps: no update is more than two intervals on.
        for (i in 1 until updates.size) {
            val gap = updates[i] - updates[i - 1]
            assertTrue(gap in 1..80_000_000L, "gap $gap at $i")
        }
        assertEquals(updates.sorted(), updates)
    }

    @Test
    fun theAppCanThinDecorationOrPauseItAndItCarriesOnWhereItWas() {
        FramePacing.decorationRate = DecorationRate.REDUCED
        val reduced = run(2.0, fps = 30)
        assertTrue(reduced.size in 18..22, "a third of 60 updates, got ${reduced.size}")

        FramePacing.reset()
        // Paused for the middle second: nothing is drawn then, and played time doesn't count it.
        val updates = run(3.0, fps = 30) { t ->
            FramePacing.decorationRate = if (t in 2_000_000_000L until 3_000_000_000L) DecorationRate.PAUSED else DecorationRate.FULL
        }
        assertTrue(updates.size in 54..66, "two seconds of updates, got ${updates.size}")
        for (i in 1 until updates.size) assertTrue(updates[i] - updates[i - 1] in 1..80_000_000L, "no jump at $i")
    }
}
