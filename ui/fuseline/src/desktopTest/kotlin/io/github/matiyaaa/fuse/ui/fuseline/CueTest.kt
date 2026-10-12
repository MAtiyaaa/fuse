package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuseline 4.1's cue: one clock that many parts play from, each with its own delay, length and
 * curve, exactly where its own move would have been, and no frames asked for once everything
 * joined to it has played.
 */
class CueTest {
    private val clock = MotionClock()

    @AfterTest fun close() = clock.close()

    @Test
    fun manyPartsPlayFromOneClockWithTheirOwnDelays() {
        val cue = Cue(clock.scope)
        // A hundred and twenty tiles, staggered 25 ms apart up to eight deep, 360 ms each.
        val parts = (0 until 120).map { i -> 25 * minOf(i, 8) to 360 }
        parts.forEach { (delay, length) -> cue.join((delay + length).toLong()) }
        assertTrue(clock.busy, "one frame callback for all of them")
        // Before the first frame every part is at its start.
        assertTrue(parts.all { (d, l) -> cue.progress(d, l) == 0f })
        clock.advance(16_000_000) // the cue's origin
        clock.advance(100_000_000) // 100 ms in
        assertEquals(100, cue.nowMillis)
        // Each part is where its own curve puts it for its own delay: exactly.
        val first = cue.progress(0, 360)
        assertEquals(Curves.Enter.transform(100f / 360f), first)
        assertEquals(Curves.Enter.transform(75f / 360f), cue.progress(25, 360))
        assertEquals(0f, cue.progress(200, 360))
        // Past the last part's end every part is in place, and the cue lets go of the frame clock.
        clock.runFor(700_000_000)
        assertTrue(parts.all { (d, l) -> cue.progress(d, l) == 1f })
        assertFalse(cue.playing)
        assertFalse(clock.busy, "nothing asks for frames once the entrance has played")
    }

    @Test
    fun aPartJoiningLaterExtendsTheCueAndAStoppedCueLeavesNothingHalfWay() {
        val cue = Cue(clock.scope)
        cue.join(200)
        clock.advance(16_000_000)
        clock.advance(150_000_000)
        cue.join(400)
        clock.advance(100_000_000)
        assertTrue(cue.playing, "runs for the part that joined late")
        assertTrue(cue.progress(0, 400) in 0.01f..0.99f)
        // The page left mid-entrance: whatever is still drawn is drawn in place, never stuck half in.
        clock.close()
        assertEquals(1f, cue.progress(0, 400))
    }

    @Test
    fun aCueStoppedBeforeItsFirstFrameNeverLeavesAPartHidden() {
        // A page composed and dropped before a frame reached it (a fast page turn on Home): its
        // widgets must be drawn in place, not held at their first, invisible frame.
        val cue = Cue(clock.scope)
        cue.join(400)
        assertEquals(0f, cue.progress(0, 400), "the one hidden frame before the entrance starts")
        clock.close()
        assertEquals(1f, cue.progress(0, 400))
        assertEquals(1f, cue.progress(200, 400))
    }
}
