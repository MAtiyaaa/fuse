package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.ui.geometry.Offset
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FuselineValue as the continuity-aware core: retargeting, seeking, gestures taking and giving back
 * the value, decay, many values at once, every refresh rate, and nothing at all once settled. All on
 * the deterministic [MotionClock].
 */
class FuselineValueTest {
    private val frame = 16_666_667L

    /**
     * Continuity at a handoff, checked exactly: straight after it the value is where it was and as fast
     * as it was; a millisecond on, it has moved by its speed (to within what any acceleration up to
     * [maxAccel] units/s² can add over a millisecond).
     */
    private fun assertContinues(what: String, c: MotionClock, v: FuselineValue<Float>, before: Float, speed: Float, maxAccel: Float = 2e6f) {
        assertEquals(before, v.value, 1e-3f * max(1f, abs(before)), "$what: position at the handoff")
        assertEquals(speed, v.velocity, 1e-3f * max(1f, abs(speed)), "$what: velocity at the handoff")
        val dt = 1_000_000L
        c.advance(dt)
        val predicted = before + speed * dt / 1e9f
        assertEquals(predicted, v.value, 0.5f * maxAccel * 1e-6f + 1e-3f * max(1f, abs(before)), "$what: a millisecond on, from $before at $speed")
    }

    // ------------------------------------------------------------------ retargeting

    @Test
    fun retargetingNeverRestartsFromRest() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        c.launch { v.animateTo(1000f, Spring(0.8f, 300f)) }
        c.advance(frame)
        c.run(8)
        val before = v.value
        val speed = v.velocity
        // Reversed, the same, zero distance, and three times before the next frame (the last wins).
        assertTrue(v.retarget(-500f))
        assertTrue(v.retarget(1000f))
        assertTrue(v.retarget(v.value))
        assertTrue(v.retarget(-200f))
        assertContinues("retargeted four times before a frame", c, v, before, speed)
        c.runFor(3_000_000_000L)
        assertEquals(-200f, v.value)
        assertFalse(v.isRunning)
        assertFalse(c.busy, "settled: no frame is waited for")
        c.close()
    }

    @Test
    fun aHundredRetargetsAndOneEveryFrameStayContinuous() {
        val seed = 0xBEEFL
        val rnd = Random(seed)
        for (motion in listOf<Motion>(Spring(0.7f, 400f), Spring(1f, 900f), Tween(250), Tween(400, curve = Curves.Linear))) {
            val c = MotionClock()
            val v = FuselineValue(0f)
            c.launch { v.animateTo(100f, motion) }
            c.advance(frame)
            var last = v.value
            var lastSpeed = v.velocity
            repeat(100) { n ->
                assertTrue(v.retarget(rnd.nextFloat() * 2000f - 1000f), "seed $seed $motion retarget $n")
                c.advance(frame)
                // One frame on: never a jump bigger than the speed it had, and never a stall to rest.
                val moved = v.value - last
                val bound = max(abs(lastSpeed), abs(v.velocity)) * frame / 1e9f * 1.6f + 30f
                assertTrue(abs(moved) <= bound, "seed $seed $motion retarget $n: jumped $moved (bound $bound)")
                assertTrue(v.value.isFinite() && v.velocity.isFinite())
                last = v.value
                lastSpeed = v.velocity
            }
            val final = v.targetValue
            c.runFor(5_000_000_000L)
            assertEquals(final, v.value, "$motion lands on the last target")
            assertFalse(c.busy)
            c.close()
        }
    }

    @Test
    fun retargetNearSettlementAndAtRest() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        c.launch { v.animateTo(10f, Spring(1f, 1500f)) }
        c.advance(frame)
        // Run until within a hair of settling, then move the target a little.
        while (abs(v.value - 10f) > 0.05f) c.advance(frame)
        assertTrue(v.retarget(10.5f))
        c.runFor(2_000_000_000L)
        assertEquals(10.5f, v.value)
        // At rest there is no motion to retarget.
        assertFalse(v.retarget(20f))
        c.close()
    }

    @Test
    fun stopThenResumeAndSnapThenAnimate() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        c.launch { v.animateTo(100f, Tween(400, curve = Curves.Linear)) }
        c.advance(frame)
        c.run(6)
        c.launch { v.stop() }
        val stoppedAt = v.value
        assertEquals(0f, v.velocity)
        assertFalse(v.isRunning)
        c.run(5)
        assertEquals(stoppedAt, v.value, "stopped means still")
        // Resumed: from rest, where it stopped (a new move's first frame is its start).
        c.launch { v.animateTo(0f, Spring(1f, 500f)) }
        c.advance(frame)
        assertEquals(stoppedAt, v.value)
        c.advance(frame)
        assertTrue(v.value < stoppedAt)
        c.launch { v.snapTo(42f) }
        assertEquals(42f, v.value)
        assertEquals(42f, v.targetValue)
        c.launch { v.animateTo(50f, Tween(100)) }
        c.runFor(200_000_000L)
        assertEquals(50f, v.value)
        c.close()
    }

    // ------------------------------------------------------------------ seeking

    @Test
    fun seekingGoesAnywhereInThePlayAndCarriesOnFromThere() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        val tween = Tween(1000, curve = Curves.Linear)
        c.launch { v.animateTo(100f, tween) }
        c.advance(frame)
        // Forward, back, past the middle, to the start, to the end, repeatedly; exact every time.
        for (ms in longArrayOf(500, 100, 800, 0, 1000, 300, 300, 650)) {
            assertTrue(v.seek(ms * 1_000_000L))
            assertEquals(ms / 10f, v.value, 1e-3f, "seek to $ms ms")
            assertEquals(ms / 1000f, v.progress, 1e-4f)
        }
        // From 650 ms the play carries on with the frames.
        c.advance(100_000_000L)
        assertEquals(75f, v.value, 1e-3f)
        // Seeking by share, and after an interruption: a retarget makes a new play from where it is
        // and how fast it was going (100 a second here), which seeking then moves through exactly.
        assertTrue(v.seekProgress(0.5f))
        assertEquals(50f, v.value, 1e-3f)
        assertTrue(v.retarget(0f, Tween(1000, curve = Curves.Linear)))
        assertTrue(v.seekProgress(0.5f))
        val fresh = TweenTrack(Tween(1000, curve = Curves.Linear), 50f, 0f, 100f)
        assertEquals(fresh.valueAt(500_000_000L), v.value, 1e-3f)
        assertTrue(v.seekProgress(0f))
        assertEquals(50f, v.value, 1e-3f)
        c.close()
        // Nothing moving: nothing to seek.
        assertFalse(FuselineValue(0f).seek(5))
    }

    // ------------------------------------------------------------------ gestures

    @Test
    fun aGestureTakesTheValueAndGivesItBackWithItsOwnVelocity() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        c.launch { v.animateTo(500f, Spring(0.6f, 200f)) }
        c.advance(frame)
        c.run(5)
        val grabbed = v.value
        // The finger lands: the spring stops where it is; nothing jumps.
        var t = c.now
        v.dragBy(0f, t)
        assertEquals(MotionOwner.GESTURE, v.owner)
        assertEquals(grabbed, v.value)
        c.run(3)
        assertEquals(grabbed, v.value, "frames no longer move a held value")
        // Dragged at 1200 units a second for 100 ms.
        repeat(12) { t += 8_333_333L; v.dragBy(10f, t) }
        assertEquals(grabbed + 120f, v.value, 1e-3f)
        assertEquals(1200f, v.releaseVelocity(t), 1f)
        // Released toward a target: the spring starts at exactly that velocity, from exactly there.
        c.launch { v.release(1000f, Spring(1f, 300f), t) }
        assertEquals(MotionOwner.ANIMATION, v.owner)
        c.advance(frame) // the move's first frame: its start
        assertContinues("released", c, v, grabbed + 120f, v.releaseVelocity(t).let { 1200f })
        c.runFor(4_000_000_000L)
        assertEquals(1000f, v.value)
        c.close()
    }

    @Test
    fun pausedReversedRegrabbedAndCancelledDrags() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        var t = 0L
        // Forward then back: the release velocity is the latest direction.
        repeat(10) { t += 10_000_000L; v.dragBy(5f, t) }
        repeat(10) { t += 10_000_000L; v.dragBy(-8f, t) }
        assertTrue(v.releaseVelocity(t) < -700f)
        // A finger that stopped before letting go has no fling.
        assertEquals(0f, v.releaseVelocity(t + 60_000_000L))
        // Cancelled: the value rests where it is.
        val at = v.value
        v.cancelDrag()
        assertEquals(MotionOwner.IDLE, v.owner)
        assertEquals(at, v.value)
        // Rapid re-grab during a fling: the fling stops dead, and the new drag starts fresh.
        repeat(5) { t += 8_000_000L; v.dragBy(30f, t) }
        c.launch { v.fling(Decay(), t) }
        c.advance(frame)
        c.run(2)
        val mid = v.value
        v.dragBy(0f, c.now)
        assertEquals(mid, v.value)
        assertEquals(MotionOwner.GESTURE, v.owner)
        assertEquals(0f, v.releaseVelocity(c.now), "a fresh grab has no speed yet")
        c.close()
    }

    @Test
    fun aFlingCoastsToItsProjectionAndFlingToSettlesOnAChoice() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        var t = 0L
        repeat(10) { t += 10_000_000L; v.dragBy(20f, t) }
        val speed = v.releaseVelocity(t)
        val projected = projectDecay(v.value, speed)
        c.launch { v.fling(Decay(), t) }
        assertEquals(projected, v.targetValue, "the target is known from the start")
        c.runFor(3_000_000_000L)
        assertEquals(projected, v.value)
        // flingTo: projected, then the nearest multiple of 500 chosen, and a spring from the release speed.
        val w = FuselineValue(0f)
        t = 0L
        repeat(10) { t += 10_000_000L; w.dragBy(30f, t) }
        val wSpeed = w.releaseVelocity(t)
        c.launch { w.flingTo({ p -> (Math.round(p / 500f) * 500f) }, Decay(), Spring(1f, 200f), t) }
        assertEquals(wSpeed, w.velocity, 1f)
        val choice = Math.round(projectDecay(300f, wSpeed) / 500f) * 500f
        assertEquals(choice, w.targetValue)
        c.runFor(5_000_000_000L)
        assertEquals(choice, w.value)
        // Decay interrupted by a spring retarget: continuous, then lands on the spring's target.
        val d = FuselineValue(0f)
        c.launch { d.animateDecay(3000f) }
        c.advance(frame)
        c.run(4)
        val dv = d.velocity
        val dx = d.value
        assertTrue(d.retarget(100f, Spring(1f, 300f)))
        assertContinues("decay -> spring", c, d, dx, dv)
        c.runFor(4_000_000_000L)
        assertEquals(100f, d.value)
        c.close()
    }

    @Test
    fun gestureVelocityInTwoDimensions() {
        val v = FuselineValue(Offset.Zero)
        var t = 0L
        repeat(8) { t += 10_000_000L; v.dragBy(Offset(10f, -4f), t) }
        val r = v.releaseVelocity(t)
        assertEquals(1000f, r.x, 1f)
        assertEquals(-400f, r.y, 1f)
    }

    // ------------------------------------------------------------------ refresh rates

    /**
     * The same moves at 30 to 240 Hz: at the moments the rates share (here, every 50 ms, sent to all
     * of them as extra frames), the value is exactly the same, through a spring, a tween, a decay,
     * a gesture handoff and retargets.
     */
    @Test
    fun everyRefreshRateSeesTheSameMotion() {
        val rates = intArrayOf(30, 40, 48, 50, 60, 72, 90, 100, 120, 144, 165, 180, 240)
        val results = rates.map { hz -> hz to scenario(hz) }
        val (_, reference) = results.first()
        for ((hz, seen) in results) {
            assertEquals(reference.size, seen.size)
            for (i in reference.indices) assertEquals(reference[i], seen[i], "at $hz Hz, checkpoint $i")
        }
    }

    private fun scenario(hz: Int): List<Float> {
        val c = MotionClock(0)
        val v = FuselineValue(0f)
        val seen = ArrayList<Float>()
        val step = 1_000_000_000L / hz
        fun until(end: Long) {
            while (c.now + step < end) c.advance(step)
            c.frameAt(end)
            seen += v.value
        }
        c.launch { v.animateTo(400f, Spring(0.5f, 250f)) }
        c.frameAt(0)
        var t = 0L
        repeat(6) { t += 50_000_000L; until(t) }
        v.retarget(-100f, Tween(300))
        repeat(4) { t += 50_000_000L; until(t) }
        v.retarget(250f, Decay())
        repeat(4) { t += 50_000_000L; until(t) }
        v.retarget(0f, Spring(1f, 600f))
        repeat(10) { t += 50_000_000L; until(t) }
        c.close()
        return seen
    }

    // ------------------------------------------------------------------ scale, idle, allocation

    @Test
    fun oneTenAHundredAndAThousandValuesAllLandAndThenRest() {
        for (n in intArrayOf(1, 10, 100, 1000)) {
            val c = MotionClock()
            val values = List(n) { FuselineValue(0f) }
            values.forEachIndexed { i, v -> c.launch { v.animateTo(i.toFloat(), if (i % 2 == 0) Spring(0.7f, 400f) else Tween(300)) } }
            c.runFor(3_000_000_000L)
            values.forEachIndexed { i, v -> assertEquals(i.toFloat(), v.value, "$n values: #$i") }
            assertFalse(c.busy, "$n values settled: nothing waits for a frame")
            c.close()
        }
    }

    /**
     * A frame costs the same memory with one value moving as with a thousand: the only objects made
     * per frame are the one shared wait for the next frame (the frame clock's own continuation),
     * nothing per value, whether the values just move or are retargeted every frame.
     */
    @Test
    fun movingValuesMakeNoObjectsPerFrame() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        fun perFrame(n: Int, retarget: Boolean): Double {
            val c = MotionClock()
            val values = List(n) { FuselineValue(0f) }
            values.forEach { v -> c.launch { v.animateTo(1000f, Spring(0.2f, 5f)) } }
            c.advance(frame)
            repeat(300) { f -> if (retarget) values.forEach { it.retargetFloat(f.toFloat()) }; c.advance(frame) }
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(300) { f -> if (retarget) values.forEach { it.retargetFloat(f + 300f) }; c.advance(frame) }
            val bytes = (bean.getThreadAllocatedBytes(thread) - before) / 300.0
            c.close()
            return bytes
        }
        for (retarget in listOf(false, true)) {
            val one = perFrame(1, retarget)
            val thousand = perFrame(1000, retarget)
            val perValue = (thousand - one) / 999
            assertTrue(perValue < 1.0, "retarget=$retarget: ${"%.2f".format(perValue)} bytes per value per frame ($one with one, $thousand with a thousand)")
            assertTrue(one < 1024.0, "retarget=$retarget: the shared frame wait costs $one bytes")
        }
    }
}
