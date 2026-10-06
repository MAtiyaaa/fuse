package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Curves and their exact slopes. A reference solves the Bézier in double precision by bisection
 * alone (slow, but certain) and takes dy/dx from the parametric derivatives; every curve here must
 * agree with it, across a fuzzed range of control points that includes overshooting, flat and
 * vertical stretches. Seeds are fixed and printed with any failure, so a failure replays exactly.
 */
class CurveMathTest {
    /** The reference: t for x by 200 bisection steps, then y(t) and y'(t)/x'(t). */
    private class Reference(x1: Double, y1: Double, x2: Double, y2: Double) {
        private val cx = 3 * x1
        private val bx = 3 * (x2 - x1) - cx
        private val ax = 1 - cx - bx
        private val cy = 3 * y1
        private val by = 3 * (y2 - y1) - cy
        private val ay = 1 - cy - by
        fun x(t: Double) = ((ax * t + bx) * t + cx) * t
        fun y(t: Double) = ((ay * t + by) * t + cy) * t
        fun dx(t: Double) = (3 * ax * t + 2 * bx) * t + cx
        fun dy(t: Double) = (3 * ay * t + 2 * by) * t + cy
        fun t(x: Double): Double {
            var lo = 0.0
            var hi = 1.0
            repeat(200) { val m = (lo + hi) / 2; if (x(m) < x) lo = m else hi = m }
            return (lo + hi) / 2
        }
        fun value(f: Double) = y(t(f))
        fun slope(f: Double): Double { val t = t(f); return dy(t) / dx(t) }
    }

    @Test
    fun fuseCurvesMatchTheReferenceInValueAndSlope() {
        val curves = listOf(Curves.Standard, Curves.Enter, Curves.Exit, Curves.Fade, Curves.Sweep).map { it as CubicCurve }
        for (c in curves) {
            val ref = Reference(c.x1.toDouble(), c.y1.toDouble(), c.x2.toDouble(), c.y2.toDouble())
            for (i in 0..2000) {
                val f = i / 2000.0
                assertEquals(ref.value(f), c.transform(f.toFloat()).toDouble(), 2e-6, "$c value at $f")
                val s = ref.slope(f)
                if (abs(s) < 1e3) assertEquals(s, c.derivative(f.toFloat()).toDouble(), 2e-3 * maxOf(1.0, abs(s)), "$c slope at $f")
            }
        }
    }

    @Test
    fun fuzzedCurvesAgreeWithTheReferenceAndStayFinite() {
        val seed = 0x3F05E3L
        val rnd = Random(seed)
        repeat(4000) { n ->
            val x1 = rnd.nextDouble(0.0, 1.0)
            val x2 = rnd.nextDouble(0.0, 1.0)
            val y1 = rnd.nextDouble(-1.0, 2.0)
            val y2 = rnd.nextDouble(-1.0, 2.0)
            val c = CubicCurve(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
            val ref = Reference(c.x1.toDouble(), c.y1.toDouble(), c.x2.toDouble(), c.y2.toDouble())
            repeat(25) {
                val f = rnd.nextDouble()
                val v = c.transform(f.toFloat())
                val d = c.derivative(f.toFloat())
                assertTrue(v.isFinite() && d.isFinite(), "seed $seed case $n: $c at $f gave $v, $d")
                assertEquals(ref.value(f.toFloat().toDouble()), v.toDouble(), 5e-6, "seed $seed case $n: $c value at $f")
                val s = ref.slope(f.toFloat().toDouble())
                // Where the slope is moderate the derivative is exact; near a vertical tangent it is capped.
                if (abs(s) < 200 && abs(ref.dx(ref.t(f))) > 1e-3) {
                    assertEquals(s, d.toDouble(), 1e-3 * maxOf(1.0, abs(s)), "seed $seed case $n: $c slope at $f")
                }
            }
        }
    }

    @Test
    fun pathologicalCurvesStayFiniteAndCorrectAtTheirEnds() {
        // Vertical start (x1 = 0), vertical middle (x1 = 1, x2 = 0), flat ends, overshoot and the diagonal.
        val cases = listOf(
            CubicCurve(0f, 1f, 1f, 0f), CubicCurve(1f, 0f, 0f, 1f), CubicCurve(0f, 0f, 1f, 1f),
            CubicCurve(0.5f, -1f, 0.5f, 2f), CubicCurve(0f, 0f, 0f, 0f), CubicCurve(1f, 1f, 1f, 1f),
            CubicCurve(0.999999f, 0f, 0.000001f, 1f),
        )
        for (c in cases) {
            assertEquals(0f, c.transform(0f))
            assertEquals(1f, c.transform(1f))
            for (i in 0..1000) {
                val f = i / 1000f
                assertTrue(c.transform(f).isFinite() && c.derivative(f).isFinite(), "$c at $f")
                assertTrue(abs(c.derivative(f)) <= CubicCurve.MAX_SLOPE.toFloat() + 1f, "$c slope capped at $f")
            }
        }
        // The diagonal Bézier is the straight line: slope one throughout.
        val line = CubicCurve(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)
        for (i in 0..100) assertEquals(1f, line.derivative(i / 100f), 1e-4f)
    }

    @Test
    fun endsHaveExactSlopes() {
        // Standard (0.2, 0, 0, 1): leaves flat-out at y'(0)/x'(0) = 0 and lands at 3(1 − y2)/3(1 − x2) = 0.
        assertEquals(0f, Curves.Standard.derivative(0f), 1e-6f)
        assertEquals(0f, Curves.Standard.derivative(1f), 1e-6f)
        // Enter (0.05, 0.7, ...): leaves at 0.7 / 0.05 = 14.
        assertEquals(14f, Curves.Enter.derivative(0f), 1e-3f)
        assertEquals(1f, Curves.Linear.derivative(0.37f))
    }

    @Test
    fun aLambdaCurveGetsAMeasuredSlope() {
        val square = Curve { it * it }
        for (i in 1..99) {
            val f = i / 100f
            assertEquals(2f * f, square.derivative(f), 2e-3f)
        }
        assertEquals(0f, square.derivative(0f), 2e-3f)
        assertEquals(2f, square.derivative(1f), 2e-3f)
    }
}
