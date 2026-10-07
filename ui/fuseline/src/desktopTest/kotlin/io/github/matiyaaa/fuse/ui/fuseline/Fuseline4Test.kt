package io.github.matiyaaa.fuse.ui.fuseline

import io.github.matiyaaa.fuse.ui.fuseline.bench.DrawModel
import androidx.compose.runtime.snapshots.Snapshot
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Fuseline 4 claims, proved one claim at a time: motion is a function of time, so frames the
 * engine skips change nothing; a value nobody reads rests and comes back exactly where it would be;
 * touching it, retargeting it or reading it again works from its exact state; shared solutions equal
 * solving each value alone; the event horizon never sleeps through a change readers would see; and
 * the steady paths make no garbage.
 */
class Fuseline4Test {
    @BeforeTest @AfterTest
    fun fresh() {
        FramePacing.reset()
        MotionInspector.enabled = false
        Inspection.reset()
    }

    /** Positions and velocities may differ by one float step where a solution was stepped on rather than solved afresh. */
    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(expected == actual || abs(expected.toRawBits() - actual.toRawBits()) <= 1, "$what: $expected vs $actual")
    }

    @Test
    fun framesTheEngineSkipsDoNotAlterMotion() {
        // The same springs, one read every frame, the other only every seventh: at the frames both
        // are read, they are in exactly the same place, at exactly the same speed.
        val c = MotionClock()
        val everyFrame = List(20) { FuselineValue(0f) }
        val sometimes = List(20) { FuselineValue(0f) }
        val rnd = Random(4)
        val springs = List(20) { Spring(0.2f + rnd.nextFloat(), 50f + rnd.nextFloat() * 900f) }
        for (i in 0 until 20) {
            c.launch { everyFrame[i].animateTo(500f + i, springs[i]) }
            c.launch { sometimes[i].animateTo(500f + i, springs[i]) }
        }
        val hz = listOf(60, 120, 144, 90, 240, 30)
        for (f in 0 until 400) {
            c.advance(1_000_000_000L / hz[(f / 37) % hz.size])
            for (v in everyFrame) v.floatValue
            if (f % 7 == 0) for (i in 0 until 20) {
                assertClose(everyFrame[i].floatValue, sometimes[i].floatValue, "frame $f value $i position")
                assertClose(everyFrame[i].velocityComponent(0), sometimes[i].velocityComponent(0), "frame $f value $i velocity")
                assertEquals(everyFrame[i].isRunning, sometimes[i].isRunning, "frame $f value $i arrival")
            }
        }
        c.close()
    }

    @Test
    fun aValueNobodyReadsRestsAndComesBackExactly() {
        val c = MotionClock(0)
        MotionInspector.enabled = true
        val v = FuselineValue(0f)
        // Soft: it takes far longer than the five seconds it spends unseen to settle.
        val spring = Spring(0.4f, 10f)
        val draw = DrawModel(1) { v.floatValue }
        c.launch { v.animateTo(1000f, spring) }
        Snapshot.notifyObjectsInitialized()
        draw.draw(0)
        c.advance(16_666_667L)
        draw.frame()
        // Hidden: its reader stops drawing it.
        draw.hide(0)
        repeat(3) { c.advance(16_666_667L); draw.frame() }
        Inspection.reset()
        // Five seconds unseen.
        repeat(300) { c.advance(16_666_667L) }
        assertTrue(v.resting, "nobody reads it: it rests")
        assertEquals(0L, Inspection.visited, "resting: no frame stepped it")
        assertTrue(v.isRunning, "still in motion all the same")
        // Seen again: exactly where continuous motion puts it, the spring's closed form at its own time.
        val reference = SpringTrack(spring, 0f, 1000f, 0f, FloatConverter.threshold)
        val played = v.playNanos
        reference.sample(played)
        assertClose(reference.sampledValue, v.floatValue, "position after 5 s unseen")
        assertClose(reference.sampledVelocity, v.velocityComponent(0), "velocity after 5 s unseen")
        // Read again, it is stepped again from the next frame.
        c.advance(16_666_667L)
        assertTrue(Inspection.visited >= 1, "read again: back on the frames")
        assertFalse(v.resting)
        c.close()
        draw.dispose()
    }

    @Test
    fun inputReachesARestingValueOnTheInteractionItself() {
        val c = MotionClock(0)
        val v = FuselineValue(0f)
        c.launch { v.animateTo(1000f, Spring(1f, 20f)) }
        // Read once, then left unread for long enough to rest.
        c.advance(16_666_667L)
        v.floatValue
        repeat(60) { c.advance(16_666_667L) }
        assertTrue(v.resting)
        val before = v.peekComponent(0)
        // A finger lands: the value is the finger's at once, from exactly where its motion had reached.
        v.dragBy(25f, c.now + 1_000_000L)
        assertEquals(MotionOwner.GESTURE, v.owner)
        assertEquals(before + 25f, v.floatValue)
        assertFalse(v.isRunning)
        c.close()
    }

    @Test
    fun retargetingAfterRestingCarriesTheExactVelocity() {
        // Twins: one read every frame, one left to rest; both retargeted at the same frame. From then
        // on they move identically: the resting one reconstructed its exact state, velocity included.
        val c = MotionClock(0)
        val watched = FuselineValue(0f)
        val resting = FuselineValue(0f)
        val spring = Spring(0.5f, 120f)
        c.launch { watched.animateTo(800f, spring) }
        c.launch { resting.animateTo(800f, spring) }
        c.advance(16_666_667L)
        resting.floatValue
        repeat(40) { c.advance(16_666_667L); watched.floatValue }
        assertTrue(resting.resting)
        assertTrue(watched.retargetFloat(-300f))
        assertTrue(resting.retargetFloat(-300f))
        repeat(120) {
            c.advance(8_333_333L)
            assertClose(watched.floatValue, resting.floatValue, "position after retarget")
            assertClose(watched.velocityComponent(0), resting.velocityComponent(0), "velocity after retarget")
        }
        c.close()
    }

    @Test
    fun sharedSolutionsEqualSolvingEachValueAlone() {
        // A hundred values on the same spring (separate Spring objects with the same numbers), started
        // on the same frame: each frame solves the spring once and every value reads it. Each must
        // equal its own track solved from scratch.
        val c = MotionClock(0)
        val values = List(100) { FuselineValue(it.toFloat()) }
        for ((i, v) in values.withIndex()) c.launch { v.animateTo(1000f - i, Spring(0.82f, 900f)) }
        Kernels.reset()
        Kernels.counting = true
        try {
            c.advance(16_666_667L)
            for (f in 0 until 40) {
                c.advance(16_666_667L)
                for ((i, v) in values.withIndex()) {
                    if (!v.isRunning) continue
                    // The spring's closed form, written out here on its own (nothing shared with the engine).
                    val omega = kotlin.math.sqrt(900.0)
                    val a = 0.82 * omega
                    val wd = omega * kotlin.math.sqrt(1.0 - 0.82 * 0.82)
                    val x0 = i.toDouble() - (1000.0 - i)
                    val t = v.playNanos / 1e9
                    val exact = ((1000.0 - i) + kotlin.math.exp(-a * t) * (x0 * kotlin.math.cos(wd * t) + a * x0 / wd * kotlin.math.sin(wd * t))).toFloat()
                    assertTrue(abs(exact - v.floatValue) <= Math.ulp(exact) * 2, "frame $f value $i: $exact vs ${v.floatValue}")
                }
            }
            assertTrue(Kernels.reused > Kernels.solved * 50, "shared: solved ${Kernels.solved}, reused ${Kernels.reused}")
        } finally {
            Kernels.counting = false
        }
        c.close()
    }

    @Test
    fun theEventHorizonNeverSleepsThroughAVisibleChange() {
        // Every bound, checked against the motion itself every 10 µs: from the horizon's start to its
        // end, no sample strays further than the room it was given.
        val rnd = Random(8)
        var checked = 0
        repeat(3000) { n ->
            val start = rnd.nextFloat() * 2000f - 1000f
            val target = rnd.nextFloat() * 2000f - 1000f
            val velocity = if (rnd.nextBoolean()) 0f else rnd.nextFloat() * 6000f - 3000f
            val track: Track = when (n % 4) {
                0 -> SpringTrack(Spring(listOf(0.1f, 0.5f, 0.82f, 1f, 1.5f, 0.999999f).random(rnd), listOf(2f, 50f, 400f, 900f, 4000f).random(rnd)), start, target, velocity, 0.01f)
                1 -> TweenTrack(Tween(50 + rnd.nextInt(800), if (rnd.nextInt(4) == 0) rnd.nextInt(200) else 0, listOf(Curves.Standard, Curves.Enter, Curves.Exit, Curves.Fade, Curves.Linear, Curves.Sweep).random(rnd)), start, target, velocity)
                2 -> DecayTrack(listOf(1f, 4.2f, 10f).random(rnd), start, velocity + 1f, 0.01f)
                else -> SpringTrack(Spring(1f, 300f), start, target, velocity, 0.5f)
            }
            val end = track.durationNanos
            if (end <= 0L) return@repeat
            val t0 = (rnd.nextDouble() * end).toLong()
            track.sample(t0)
            val x0 = track.sampledValue.toDouble()
            val room = listOf(0.00125, 0.0625, 0.01, 1.0).random(rnd)
            val calm = track.calmUntil(t0, room)
            if (calm <= t0) return@repeat
            val until = minOf(calm, end + 1_000_000L, t0 + 20_000_000_000L)
            var t = t0
            while (t < until) {
                track.sample(t)
                val drift = abs(track.sampledValue.toDouble() - x0)
                assertTrue(drift <= room + abs(x0).ulpOf() * 2, "$track at $t (from $t0, calm until $calm): moved $drift, room $room")
                t += 10_000L
                checked++
            }
        }
        assertTrue(checked > 1_000_000, "only $checked samples checked")
    }

    private fun Double.ulpOf(): Double = Math.ulp(this.toFloat()).toDouble()

    @Test
    fun steadyMotionMakesNoGarbage() {
        // The frame clock's own waiting allocates a little every frame whatever moves (every engine
        // pays it): measured with two values, then with two hundred. The difference is what the values
        // themselves cost: nothing, read every frame or resting unread, retargeted or not.
        fun bytesPerFrame(count: Int): Long {
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val thread = Thread.currentThread().id
            val c = MotionClock(0)
            val read = List(count / 2) { FuselineValue(0f) }
            val unread = List(count / 2) { FuselineValue(0f) }
            val s = Spring(0.3f, 4f)
            for (v in read) c.launch { v.animateTo(1000f, s) }
            for (v in unread) c.launch { v.animateTo(1000f, s) }
            // Warm: the driver's lists and heap reach their size.
            repeat(120) { c.advance(16_666_667L); for (v in read) v.floatValue; for (v in read) v.retargetFloat(1000f + it) }
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(300) { f ->
                c.advance(16_666_667L)
                for (v in read) v.floatValue
                if (f % 3 == 0) for (v in read) v.retargetFloat(900f + f)
            }
            val bytes = bean.getThreadAllocatedBytes(thread) - before
            c.close()
            return bytes / 300
        }
        bytesPerFrame(200)
        val few = bytesPerFrame(2)
        val many = bytesPerFrame(200)
        assertTrue(many - few <= 8, "200 moving values allocated ${many - few} bytes a frame more than 2 ($many vs $few)")
    }
}
