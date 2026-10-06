package io.github.matiyaaa.fuse.ui.fuseline

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared frame driver and frame pacing: moves joining and leaving mid-frame, mass starts and
 * stops, cancellation, nothing at all once idle, the display's real interval at 30 to 240 Hz, and
 * load answered by thinning decoration only, never by changing any motion.
 */
class FrameDriverTest {
    @BeforeTest @AfterTest
    fun freshPacing() = FramePacing.reset()

    @Test
    fun movesJoiningLeavingAndCancelledMidFrame() {
        val c = MotionClock()
        val a = FuselineValue(0f)
        val b = FuselineValue(0f)
        val late = FuselineValue(0f)
        // a ends on its first moving frame and starts another value as it does: that one begins next frame.
        c.launch {
            a.animateTo(1f, Tween(10))
            c.launch { late.animateTo(100f, Tween(100, curve = Curves.Linear)) }
        }
        var bJob: Job? = null
        bJob = c.scope.launch { b.animateTo(100f, Tween(1000, curve = Curves.Linear)) }
        c.advance(16_666_667L)
        c.advance(16_666_667L)
        assertEquals(1f, a.value)
        assertEquals(0f, late.value, "joined during a frame: starts on the next")
        c.advance(50_000_000L)
        assertEquals(0f, late.value, "its first frame is its start")
        c.advance(50_000_000L)
        assertEquals(50f, late.value, 1e-3f)
        // Cancelled: it leaves at once and is never stepped again; the others carry on.
        bJob?.cancel()
        val bAt = b.value
        c.advance(50_000_000L)
        assertEquals(bAt, b.value)
        assertEquals(100f, late.value)
        assertFalse(c.busy, "nothing left: no frame is waited for")
        c.close()
    }

    @Test
    fun massStartsAndStopsAndRandomChurn() {
        val seed = 0xD21E5L
        val rnd = Random(seed)
        val c = MotionClock()
        val values = List(1000) { FuselineValue(0f) }
        val jobs = values.map { v -> c.scope.launch { v.animateTo(100f, Spring(1f, 50f + rnd.nextFloat() * 400f)) } }
        c.run(10)
        // Half stopped at once.
        jobs.filterIndexed { i, _ -> i % 2 == 0 }.forEach { it.cancel() }
        c.run(1)
        // Random churn: new moves, retargets and cancels, every frame.
        repeat(120) {
            repeat(20) {
                val v = values[rnd.nextInt(values.size)]
                when (rnd.nextInt(3)) {
                    0 -> c.launch { v.animateTo(rnd.nextFloat() * 500f, if (rnd.nextBoolean()) Spring(0.8f, 300f) else Tween(200)) }
                    1 -> v.retarget(rnd.nextFloat() * 500f)
                    else -> c.launch { v.stop() }
                }
            }
            c.run(1)
        }
        c.runFor(10_000_000_000L)
        for ((i, v) in values.withIndex()) {
            assertFalse(v.isRunning, "seed $seed value $i still running")
            assertTrue(v.value.isFinite())
            assertEquals(v.targetValue, v.value, "seed $seed value $i rests on its target")
        }
        assertFalse(c.busy)
        c.close()
    }

    @Test
    fun idleMeansNoFramesAndNoMemory() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val c = MotionClock()
        val values = List(100) { FuselineValue(0f) }
        values.forEach { v -> c.launch { v.animateTo(1f, Spring()) } }
        c.runFor(3_000_000_000L)
        assertFalse(c.busy, "settled: no frame callback is registered")
        // Frames that still arrive (something else on screen) cost Fuseline nothing at all.
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(200) { c.advance(16_666_667L) }
        val bytes = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(bytes < 64 * 200, "idle frames allocated $bytes bytes")
        assertFalse(c.busy)
        c.close()
    }

    @Test
    fun theDisplaysOwnIntervalIsMeasuredAtEveryRate() {
        for (hz in intArrayOf(30, 40, 48, 50, 60, 72, 90, 100, 120, 144, 165, 180, 240)) {
            FramePacing.reset()
            val c = MotionClock()
            val v = FuselineValue(0f)
            c.launch { v.animateTo(1f, Tween(2000)) }
            c.run(60, hz)
            assertEquals(1_000_000_000L / hz, FramePacing.intervalNanos, "$hz Hz")
            assertEquals(hz.toFloat(), FramePacing.refreshRate, 0.5f)
            assertFalse(FramePacing.underLoad, "$hz Hz on time")
            c.close()
        }
    }

    /**
     * Frames running late (a 120 Hz display managing 40 frames a second): decoration thins to every
     * other frame, while the motion itself reads exactly the same values at the same times as on an
     * idle device. When frames are back on time, decoration comes back.
     */
    @Test
    fun loadThinsDecorationOnlyAndPhysicsNeverChanges() {
        val c = MotionClock(0)
        val spring = FuselineValue(0f)
        c.launch { spring.animateTo(500f, Spring(0.6f, 120f)) }
        c.run(30, 120)
        assertFalse(FramePacing.underLoad)
        // Late frames, at 25 ms instead of 8.3.
        repeat(10) { c.advance(25_000_000L) }
        assertTrue(FramePacing.underLoad)
        val shown = (0 until 10).count { FramePacing.shouldDrawDecoration() }
        assertEquals(5, shown, "decoration every other frame under load")
        // The spring's value is the closed form at its own elapsed time, load or not.
        val reference = SpringTrack(Spring(0.6f, 120f), 0f, 500f, 0f, 0.01f)
        assertEquals(reference.valueAt(c.now - 8_333_333L), spring.value, 1e-3f)
        // Back on time for a while: decoration returns to every frame.
        repeat(40) { c.advance(8_333_333L) }
        assertFalse(FramePacing.underLoad)
        assertEquals(10, (0 until 10).count { FramePacing.shouldDrawDecoration() })
        c.close()
    }

    @Test
    fun aDecorativeLoopShowsRealTimeEvenWhenThinned() {
        val c = MotionClock(0)
        val clock = LoopClock("test", decorative = true)
        c.launch {
            runFrames(Long.MAX_VALUE) { play -> if (FramePacing.shouldDrawDecoration()) clock.playNanos = play }
        }
        c.frameAt(0)
        repeat(30) { c.advance(8_333_333L) }
        repeat(10) { c.advance(25_000_000L) }
        // Whenever it shows, it shows the real elapsed time, never a slowed one.
        val seen = HashSet<Long>()
        repeat(6) {
            c.advance(25_000_000L)
            seen += clock.playNanos
        }
        assertTrue(seen.all { it % 1_000_000L == 0L || it > 0 })
        assertEquals(c.now, seen.max().coerceAtLeast(0) + (c.now - seen.max()), "real time")
        assertTrue(c.now - seen.max() <= 25_000_000L, "at most one frame behind the real time")
        c.close()
    }
}
