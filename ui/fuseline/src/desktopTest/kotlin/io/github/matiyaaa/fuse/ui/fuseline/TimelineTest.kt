package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Timeline 3: each primitive alone and together (delay, parallel, sequence, stagger, repeat,
 * nesting), seeking into nested sequences, reversing during a stagger, speed changes and
 * interruptions that never jump, deterministic replay, and exact track velocities.
 */
class TimelineTest {
    private val fade = Timeline(1000) { track("a") { at(0, 0f); at(1000, 1f, Curves.Linear) } }

    @Test
    fun primitivesAlone() {
        // Delay: keys from 200 ms hold the first value before.
        val delayed = Timeline(600) { track("x") { at(200, 0f); at(600, 4f, Curves.Linear) } }
        assertEquals(0f, delayed.value("x", 100f))
        assertEquals(2f, delayed.value("x", 400f), 1e-5f)
        // Parallel: both from the start.
        val par = Timeline.parallel(fade, Timeline(500) { track("b") { at(0, 10f); at(500, 20f, Curves.Linear) } })
        assertEquals(1000, par.lengthMs)
        assertEquals(0.25f, par.value("0.a", 250f), 1e-5f)
        assertEquals(15f, par.value("1.b", 250f), 1e-5f)
        // Sequence: the second after the first.
        val seq = Timeline.sequence(fade, fade)
        assertEquals(2000, seq.lengthMs)
        assertEquals(1f, seq.value("0.a", 1500f))
        assertEquals(0.5f, seq.value("1.a", 1500f), 1e-5f)
        assertEquals(0f, seq.value("1.a", 500f))
        // Stagger: each a beat after the one before.
        val st = Timeline(1000) { stagger(listOf("p", "q", "r"), everyMs = 100) { at(0, 0f); at(200, 1f, Curves.Linear) } }
        assertEquals(1f, st.value("p", 200f))
        assertEquals(0.5f, st.value("q", 200f), 1e-5f)
        assertEquals(0f, st.value("r", 200f))
        // Repeat: straight again, and back and forth.
        val loop = Timeline(1000) { track("l") { at(0, 0f); at(250, 1f, Curves.Linear); repeat(4) } }
        assertEquals(0.5f, loop.value("l", 625f), 1e-5f)
        val swing = Timeline(1000) { track("s") { at(0, 0f); at(250, 1f, Curves.Linear); repeat(4, reverse = true) } }
        assertEquals(0.5f, swing.value("s", 375f), 1e-5f)
        assertEquals(-4f, swing.velocity("s", 375f), 1e-3f, "backwards on the second run")
        assertEquals(0f, swing.value("s", 1000f), "an even number of back-and-forth ends where it began")
        // Nested speed: a timeline played twice as fast inside another.
        val fast = Timeline(500) { include(fade, atMs = 0, speed = 2f) }
        assertEquals(0.5f, fast.value("a", 250f), 1e-5f)
        assertFailsWith<IllegalArgumentException> { Timeline(10) { include(fade, speed = 0f) } }
    }

    @Test
    fun deepNestingAndSeekingIntoASequence() {
        var t = fade
        repeat(6) { t = Timeline.sequence(t, fade) }
        // Seven fades one after another, each nested six deep at most.
        assertEquals(7000, t.lengthMs)
        val deepest = t.names.first { it.count { c -> c == '.' } == 6 }
        assertEquals(0.5f, t.value(deepest, 500f), 1e-5f)
        // A player seeks straight into the middle of the fifth.
        val p = TimelinePlayer(t)
        p.seek(4500)
        assertEquals(0.5f, p["0.0.1.a"], 1e-5f)
        assertEquals(4500f, p.timeMs)
    }

    @Test
    fun aPlayerReversesChangesSpeedAndIsInterruptedWithoutJumping() {
        val st = Timeline(1000) { stagger(listOf("p", "q", "r"), everyMs = 100) { at(0, 0f); at(600, 1f, Curves.Standard) } }
        val p = TimelinePlayer(st)
        var t = 0L
        fun frame(dt: Long = 16_666_667L) { t += dt; p.tick(t, 1f) }
        p.tick(t, 1f)
        repeat(20) { frame() }
        val at = p.timeMs
        val q = p["q"]
        // Reversed mid-stagger: from the same moment, back the way it came.
        p.reverse()
        frame()
        assertTrue(p.timeMs < at && at - p.timeMs < 20f, "${p.timeMs} after $at")
        assertTrue(p["q"] <= q && q - p["q"] < 0.1f)
        assertTrue(p.velocity("q") <= 0f)
        // Twice the speed, carrying on from where it is.
        val before = p.timeMs
        p.speed = 2f
        frame()
        assertTrue(abs(before - p.timeMs - 33.3f) < 2f, "two frames' worth in one: ${before - p.timeMs}")
        // Interrupted by a seek: from there.
        p.seek(800)
        frame()
        assertTrue(p.timeMs in 760f..800f)
        // Back to the start: finished going back.
        repeat(100) { frame() }
        assertTrue(p.finished)
        assertEquals(0f, p.timeMs)
    }

    @Test
    fun replayIsDeterministic() {
        fun run(): List<Float> {
            val p = TimelinePlayer(Timeline.sequence(fade, Timeline(500) { stagger(listOf("x", "y"), 50) { at(0, 0f); at(300, 1f, Curves.Enter) } }))
            val seen = ArrayList<Float>()
            var t = 0L
            p.tick(t, 1f)
            for (i in 0 until 120) {
                t += if (i % 3 == 0) 8_333_333L else 16_666_667L
                if (i == 40) p.reverse()
                if (i == 70) p.speed = 1.5f
                if (i == 90) p.reverse()
                p.tick(t, 1f)
                seen += p["1.y"]
                seen += p.timeMs
            }
            return seen
        }
        assertEquals(run(), run())
    }

    @Test
    fun trackVelocitiesAreExact() {
        val tl = Timeline(1000) { track("v") { at(0, 0f); at(1000, 200f, Curves.Standard) } }
        for (i in 1 until 100) {
            val ms = i * 10f
            val expect = 200f * Curves.Standard.derivative(ms / 1000f)
            assertEquals(expect, tl.velocity("v", ms), 1e-2f * maxOf(1f, abs(expect)))
        }
        assertEquals(0f, tl.velocity("v", 1000f))
        assertEquals(0f, tl.velocity("missing", 10f))
    }
}
