package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spring solver against an independent reference: the spring's equation integrated numerically
 * (fourth-order Runge–Kutta at a hundred-thousandth of a second, far finer than any frame), across
 * fuzzed starts, targets, speeds, stiffnesses, damping ratios and thresholds. Damping ratios a hair
 * either side of critical (0.999999, 1, 1.000001), where closed forms divide by almost nothing, are
 * covered on purpose. Every check names its seed.
 */
class SpringSolverTest {
    /** x'' = −2ζω x' − ω² x from (x0, v0), stepped to each requested time. */
    private fun reference(zeta: Double, omega: Double, x0: Double, v0: Double, times: DoubleArray): Array<DoubleArray> {
        val h = 1e-5
        var x = x0
        var v = v0
        var t = 0.0
        val out = Array(times.size) { DoubleArray(2) }
        fun acc(x: Double, v: Double) = -2 * zeta * omega * v - omega * omega * x
        for ((i, target) in times.withIndex()) {
            while (t + h <= target) {
                val k1x = v; val k1v = acc(x, v)
                val k2x = v + h / 2 * k1v; val k2v = acc(x + h / 2 * k1x, v + h / 2 * k1v)
                val k3x = v + h / 2 * k2v; val k3v = acc(x + h / 2 * k2x, v + h / 2 * k2v)
                val k4x = v + h * k3v; val k4v = acc(x + h * k3x, v + h * k3v)
                x += h / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
                v += h / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
                t += h
            }
            // The last partial step, exactly to the requested time.
            val r = target - t
            if (r > 0) {
                val k1x = v; val k1v = acc(x, v)
                val k2x = v + r / 2 * k1v; val k2v = acc(x + r / 2 * k1x, v + r / 2 * k1v)
                val k3x = v + r / 2 * k2v; val k3v = acc(x + r / 2 * k2x, v + r / 2 * k2v)
                val k4x = v + r * k3v; val k4v = acc(x + r * k3x, v + r * k3v)
                x += r / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
                v += r / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
                t = target
            }
            out[i][0] = x
            out[i][1] = v
        }
        return out
    }

    private fun check(seed: Long, n: Int, zeta: Float, stiffness: Float, start: Float, target: Float, velocity: Float, threshold: Float) {
        val spring = Spring(zeta, stiffness, threshold)
        val track = SpringTrack(spring, start, target, velocity, threshold)
        val omega = sqrt(stiffness.toDouble())
        val x0 = start.toDouble() - target
        val times = DoubleArray(12) { i -> i * 0.05 + 0.003 }
        val ref = reference(zeta.toDouble(), omega, x0, velocity.toDouble(), times)
        // Float results: allow for float rounding of the value itself and of the inputs.
        val scale = max(1.0, max(abs(start.toDouble()), abs(target.toDouble())))
        for ((i, t) in times.withIndex()) {
            val play = (t * 1e9).toLong()
            if (play >= track.durationNanos) continue
            track.sample(play)
            val expectX = target + ref[i][0]
            val expectV = ref[i][1]
            val vScale = max(1.0, max(abs(velocity.toDouble()), omega * abs(x0)))
            val what = "seed $seed case $n: ζ=$zeta k=$stiffness from $start to $target at $velocity, t=$t"
            assertEquals(expectX, track.sampledValue.toDouble(), 2e-6 * scale + 2e-6 * abs(x0) + 1e-5, "$what value")
            assertEquals(expectV, track.sampledVelocity.toDouble(), 2e-5 * vScale + 1e-4, "$what velocity")
            // sample() agrees with valueAt() and velocityAt().
            assertEquals(track.valueAt(play), track.sampledValue, "$what sample value")
            assertEquals(track.velocityAt(play), track.sampledVelocity, "$what sample velocity")
        }
        // At rest means at rest: from its end on, the true spring never strays past the threshold.
        val end = track.durationNanos / 1e9
        if (end < SpringTrack.MAX_SPRING_SECONDS - 1) {
            val after = DoubleArray(40) { i -> end + i * 0.02 }
            val refAfter = reference(zeta.toDouble(), omega, x0, velocity.toDouble(), after)
            for ((i, t) in after.withIndex()) {
                assertTrue(abs(refAfter[i][0]) <= threshold * 1.001 + 1e-9, "seed $seed case $n: ζ=$zeta k=$stiffness strays ${refAfter[i][0]} past $threshold at $t (ends $end)")
            }
        }
    }

    @Test
    fun nearCriticalDampingIsExact() {
        val seed = 0x5191L
        val rnd = Random(seed)
        val zetas = floatArrayOf(0.999999f, 1f, 1.000001f, 0.9999f, 1.0001f, 0.99f, 1.01f)
        var n = 0
        for (z in zetas) repeat(40) {
            check(seed, n++, z, rnd.nextFloat() * 2000f + 10f, rnd.nextFloat() * 2000f - 1000f, rnd.nextFloat() * 2000f - 1000f, rnd.nextFloat() * 8000f - 4000f, 0.01f)
        }
    }

    @Test
    fun fuzzedSpringsMatchTheReference() {
        val seed = 0xF05EL
        val rnd = Random(seed)
        repeat(600) { n ->
            val zeta = when (n % 4) { 0 -> rnd.nextFloat() * 0.99f + 0.01f; 1 -> 1f; 2 -> 1f + rnd.nextFloat() * 4f; else -> rnd.nextFloat() * 2f }
            val stiffness = listOf(2f, 50f, 200f, 400f, 900f, 1500f, 10_000f)[n % 7] * (0.5f + rnd.nextFloat())
            val threshold = listOf(0.001f, 0.01f, 0.5f, 1f)[n % 4]
            check(seed, n, zeta, stiffness, rnd.nextFloat() * 4000f - 2000f, rnd.nextFloat() * 4000f - 2000f, rnd.nextFloat() * 20_000f - 10_000f, threshold)
        }
    }

    @Test
    fun aSpringAtRestStaysAtRestAndLandsExactly() {
        val still = SpringTrack(Spring(), 5f, 5f, 0f, 0.01f)
        assertEquals(0L, still.durationNanos)
        assertEquals(5f, still.valueAt(0))
        val t = SpringTrack(Spring(0.5f, 300f), 0f, 100f, 0f, 0.01f)
        assertEquals(100f, t.valueAt(t.durationNanos))
        assertEquals(0f, t.velocityAt(t.durationNanos))
        // Zero damping never settles by itself: it is capped, never infinite.
        val undamped = SpringTrack(Spring(0f, 100f), 0f, 1f, 0f, 0.01f)
        assertTrue(undamped.durationNanos <= (SpringTrack.MAX_SPRING_SECONDS * 1e9).toLong())
        assertTrue(undamped.valueAt(1_000_000_000).isFinite())
    }

    @Test
    fun hugeAndTinyValuesStayFinite() {
        for (start in floatArrayOf(-1e7f, -1f, 0f, 1e-6f, 1e7f)) for (v in floatArrayOf(-1e6f, 0f, 1e-6f, 1e6f)) for (z in floatArrayOf(0.05f, 1f, 6f)) {
            val t = SpringTrack(Spring(z, 1500f), start, 0f, v, 0.01f)
            for (i in 0..200) {
                t.sample(i * 5_000_000L)
                assertTrue(t.sampledValue.isFinite() && t.sampledVelocity.isFinite(), "from $start at $v, ζ=$z, step $i")
            }
        }
    }
}
