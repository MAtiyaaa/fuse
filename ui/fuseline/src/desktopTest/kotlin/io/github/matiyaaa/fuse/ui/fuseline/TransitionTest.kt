package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * State-to-state transitions: every part on screen moves on from where it is and how fast it is
 * going at every change; directions both ways; reversal; rapid fuzzed navigation that never jumps,
 * never leaks a state and always lands on the last destination; scrubbing any way; and release.
 */
class TransitionTest {
    private val tabs = listOf("Home", "Library", "Systems", "Apps", "Addons")
    private val frame = 16_666_667L

    private fun transition(start: String = "Home", wraps: Boolean = false) =
        MotionTransition(start, order = { tabs.indexOf(it).takeIf { i -> i >= 0 } }, wraps = wraps, count = { tabs.size })

    /** Every part's presence and position, and their velocities, keyed by state. */
    private fun snapshot(t: MotionTransition<String>) = t.parts.associate { it.state to floatArrayOf(it.presenceValue, it.position, it.presenceVelocity, it.positionVelocity) }

    /** Nothing that was on screen moved or lost speed at the instant of a change. */
    private fun assertNoJump(what: String, before: Map<String, FloatArray>, after: Map<String, FloatArray>) {
        for ((state, b) in before) {
            val a = after[state] ?: continue
            assertEquals(b[0], a[0], 1e-5f, "$what: $state presence jumped")
            assertEquals(b[1], a[1], 1e-5f, "$what: $state position jumped")
            assertEquals(b[2], a[2], 1e-3f * maxOf(1f, abs(b[2])), "$what: $state presence velocity reset")
            assertEquals(b[3], a[3], 1e-3f * maxOf(1f, abs(b[3])), "$what: $state position velocity reset")
        }
    }

    @Test
    fun aToB() {
        val c = MotionClock()
        val t = transition()
        t.go("Library", c.scope)
        assertEquals(TransitionDirection.FORWARD, t.direction)
        assertEquals("Home", t.previousState)
        // Library waits off to the right, unseen; Home leaves to the left.
        assertEquals(1f, t.partOf("Library")!!.position)
        c.advance(frame)
        c.run(5)
        assertTrue(t.partOf("Library")!!.position in 0f..1f)
        assertTrue(t.partOf("Home")!!.position < 0f)
        assertTrue(t.progress in 0.01f..0.99f)
        c.runFor(3_000_000_000L)
        assertTrue(t.isSettled)
        assertEquals(listOf("Library"), t.parts.map { it.state }, "Home was let go")
        assertEquals(0f, t.partOf("Library")!!.position)
        assertFalse(c.busy)
        c.close()
    }

    @Test
    fun aToBToCCarriesOnWithoutAJump() {
        val c = MotionClock()
        val t = transition()
        t.go("Library", c.scope)
        c.advance(frame)
        c.run(6)
        val library = t.partOf("Library")!!
        val before = snapshot(t)
        val wasMovingLeft = library.positionVelocity < 0f
        t.go("Apps", c.scope)
        assertNoJump("Home -> Library -> Apps", before, snapshot(t))
        assertTrue(wasMovingLeft)
        // Library keeps going the way it was going: leftward, through its place and out.
        c.run(20)
        assertTrue(library.position < 0f, "Library carried on left: ${library.position}")
        c.runFor(3_000_000_000L)
        assertEquals(listOf("Apps"), t.parts.map { it.state })
        c.close()
    }

    @Test
    fun aToBToAReverses() {
        val c = MotionClock()
        val t = transition()
        t.go("Library", c.scope)
        c.advance(frame)
        c.run(6)
        val before = snapshot(t)
        t.go("Home", c.scope)
        assertEquals(TransitionDirection.BACK, t.direction)
        assertNoJump("Home -> Library -> Home", before, snapshot(t))
        c.run(3)
        // Each goes back the way it came: Home from the left, Library to the right.
        assertTrue(t.partOf("Library")!!.positionVelocity > 0f)
        assertTrue(t.partOf("Home")!!.positionVelocity > 0f)
        c.runFor(3_000_000_000L)
        assertEquals(listOf("Home"), t.parts.map { it.state })
        assertEquals(0f, t.parts.single().position)
        c.close()
    }

    @Test
    fun aToBToCToBToA() {
        val c = MotionClock()
        val t = transition()
        val path = listOf("Library", "Systems", "Library", "Home")
        c.advance(frame)
        for (s in path) {
            val before = snapshot(t)
            t.go(s, c.scope)
            assertNoJump("-> $s", before, snapshot(t))
            c.run(4)
        }
        c.runFor(3_000_000_000L)
        assertEquals(listOf("Home"), t.parts.map { it.state })
        c.close()
    }

    @Test
    fun rapidFuzzedNavigationNeverJumpsLeaksOrMisses() {
        val seed = 0x7AB5L
        val rnd = Random(seed)
        repeat(60) { run ->
            val c = MotionClock()
            val t = transition()
            c.advance(frame)
            var last = "Home"
            repeat(80) { step ->
                // Controller, keyboard and touch speeds: a change every frame up to every half second.
                val next = tabs[rnd.nextInt(tabs.size)]
                val before = snapshot(t)
                t.go(next, c.scope, if (rnd.nextBoolean()) Spring(1f, 500f) else Tween(180))
                assertNoJump("seed $seed run $run step $step -> $next", before, snapshot(t))
                last = next
                repeat(rnd.nextInt(0, 30)) { c.advance(frame) }
                for (p in t.parts) {
                    assertTrue(p.presenceValue.isFinite() && p.position.isFinite(), "seed $seed run $run: $p")
                    assertTrue(abs(p.position) <= 1.5f, "seed $seed run $run: $p strayed")
                }
                assertTrue(t.parts.size <= tabs.size, "seed $seed run $run: ${t.parts}")
            }
            c.runFor(4_000_000_000L)
            assertEquals(last, t.targetState)
            assertEquals(listOf(last), t.parts.map { it.state }, "seed $seed run $run leaked ${t.parts}")
            assertTrue(t.isSettled)
            assertFalse(c.busy, "seed $seed run $run: nothing left running")
            c.close()
        }
    }

    @Test
    fun directionsBothWaysWrappingAndUnordered() {
        val t = transition()
        assertEquals(TransitionDirection.FORWARD, t.directionOf("Home", "Apps"))
        assertEquals(TransitionDirection.BACK, t.directionOf("Apps", "Home"))
        assertEquals(TransitionDirection.NONE, t.directionOf("Home", "Home"))
        assertEquals(TransitionDirection.NONE, t.directionOf("Home", "Somewhere unlisted"))
        // Wrapping: the last tab's next is the first, the shorter way round.
        val w = transition(wraps = true)
        assertEquals(TransitionDirection.FORWARD, w.directionOf("Addons", "Home"))
        assertEquals(TransitionDirection.BACK, w.directionOf("Home", "Addons"))
        assertEquals(TransitionDirection.FORWARD, w.directionOf("Home", "Library"))
        // Every direction reverses to its opposite, and the opposite of the opposite is itself.
        for (d in TransitionDirection.entries) {
            assertEquals(d, d.reversed.reversed)
            assertEquals(-d.sign, d.reversed.sign)
        }
        // Unordered states crossfade in place: nothing slides.
        val c = MotionClock()
        val u = MotionTransition("a")
        u.go("b", c.scope)
        assertEquals(0f, u.partOf("b")!!.position)
        c.runFor(1_000_000_000L)
        assertEquals(listOf("b"), u.parts.map { it.state })
        c.close()
    }

    @Test
    fun reorderedTabsKeepWhatIsOnScreenWhereItIs() {
        val order = tabs.toMutableList()
        val c = MotionClock()
        val t = MotionTransition("Home", order = { order.indexOf(it) })
        t.go("Apps", c.scope)
        c.advance(frame)
        c.run(5)
        val before = snapshot(t)
        // The tabs are rearranged mid-flight: Apps is now first, so going to Library is forward from it.
        order.remove("Apps"); order.add(0, "Apps")
        t.go("Library", c.scope)
        assertEquals(TransitionDirection.FORWARD, t.direction)
        assertNoJump("reordered", before, snapshot(t))
        c.runFor(3_000_000_000L)
        assertEquals(listOf("Library"), t.parts.map { it.state })
        c.close()
    }

    @Test
    fun scrubbingAnyWayIsExactAndReleaseDecides() {
        val c = MotionClock()
        val t = transition()
        var time = 0L
        for (f in floatArrayOf(0f, 0.1f, 0.4f, 0.8f, 0.5f, 0.2f, 0.7f, 1f, 0.3f)) {
            time += 20_000_000L
            t.scrub("Library", f, time)
            assertEquals(f, t.progress, 1e-6f)
            assertEquals(1f - f, t.partOf("Home")!!.presenceValue, 1e-6f)
            assertEquals(1f - f, t.partOf("Library")!!.position, 1e-6f)
            assertEquals(-f, t.partOf("Home")!!.position, 1e-6f)
        }
        // Released slowly short of halfway: it goes back.
        time += 60_000_000L
        val back = t.release(c.scope, timeNanos = time)
        assertEquals("Home", back)
        assertEquals("Home", t.targetState)
        c.runFor(2_000_000_000L)
        assertEquals(listOf("Home"), t.parts.map { it.state })
        // Flicked short of halfway but fast: it gets there.
        time = 0L
        for (f in floatArrayOf(0.05f, 0.12f, 0.2f, 0.3f)) { time += 8_000_000L; t.scrub("Library", f, time) }
        assertEquals("Library", t.release(c.scope, timeNanos = time))
        c.runFor(2_000_000_000L)
        assertEquals(listOf("Library"), t.parts.map { it.state })
        c.close()
    }

    @Test
    fun aGestureTakesARunningTransitionAndMotionTakesItBack() {
        val c = MotionClock()
        val t = transition()
        t.go("Library", c.scope)
        c.advance(frame)
        c.run(5)
        val at = t.progress
        // A finger catches it mid-way and drags it on.
        t.scrub("Library", at, c.now)
        assertEquals(at, t.progress, 1e-6f)
        t.scrub("Library", at + 0.2f, c.now + 50_000_000L)
        // Then a new destination by motion: from the finger's place, at the finger's speed.
        val before = snapshot(t)
        t.go("Systems", c.scope)
        val after = snapshot(t)
        for ((state, b) in before) {
            val a = after[state] ?: continue
            assertEquals(b[0], a[0], 1e-5f, "$state presence")
            assertEquals(b[1], a[1], 1e-5f, "$state position")
        }
        c.runFor(3_000_000_000L)
        assertEquals(listOf("Systems"), t.parts.map { it.state })
        c.close()
    }

    @Test
    fun sameTargetAndFirstPart() {
        val c = MotionClock()
        val t = transition()
        t.go("Home", c.scope)
        assertNull(t.previousState, "going where it already is changes nothing")
        assertEquals(1, t.parts.size)
        assertNotNull(t.partOf("Home"))
        assertTrue(t.isSettled)
        c.close()
    }
}
