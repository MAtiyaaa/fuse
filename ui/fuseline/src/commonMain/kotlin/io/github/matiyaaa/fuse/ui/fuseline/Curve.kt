package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Immutable
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * How a movement spends its time: [transform] takes the share of the time gone (0 to 1) and gives
 * the share of the way travelled, and [derivative] how fast that share changes at a moment (the
 * slope of the curve). Fuseline's tweens and timelines run their time through a curve, and take
 * their speed from the slope, so a value handed from a curve to a spring keeps its speed exactly.
 *
 * A curve written as a lambda gets its slope measured across a small step; Fuse's own curves give
 * theirs exactly.
 */
fun interface Curve {
    fun transform(fraction: Float): Float

    /** The slope of [transform] at [fraction] (share of the way per share of the time). */
    fun derivative(fraction: Float): Float = measuredSlope(this, fraction)
}

/** A curve's slope measured across a step either side (one side at the ends), for curves that don't give theirs. */
internal fun measuredSlope(curve: Curve, fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    val lo = (f - SLOPE_STEP).coerceAtLeast(0f)
    val hi = (f + SLOPE_STEP).coerceAtMost(1f)
    if (hi <= lo) return 0f
    return (curve.transform(hi) - curve.transform(lo)) / (hi - lo)
}

private const val SLOPE_STEP = 1e-3f

/** Constant speed: the share of the way is the share of the time, and its slope is always one. */
@Immutable
object LinearCurve : Curve {
    override fun transform(fraction: Float): Float = fraction
    override fun derivative(fraction: Float): Float = 1f
    override fun toString(): String = "LinearCurve"
}

/**
 * A cubic Bézier curve from (0, 0) to (1, 1) with the control points ([x1], [y1]) and ([x2], [y2]),
 * the shape CSS and design tools describe easing with. The curve is given as x(t) and y(t); for a
 * fraction of time (an x) it finds t by Newton's method, falling back to bisection where the slope
 * flattens, then returns y(t). Its slope is exact: dy/dx = y'(t) / x'(t) at that t, and where x'(t)
 * vanishes (a control point on the time axis's end) the ratio of second derivatives, its limit.
 */
@Immutable
class CubicCurve(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Curve {
    init {
        require(x1 in 0f..1f && x2 in 0f..1f) { "A curve's control points must lie between 0 and 1 in time" }
        require(y1.isFinite() && y2.isFinite()) { "A curve's control points must be finite" }
    }

    // Polynomial coefficients of x(t) and y(t): a t^3 + b t^2 + c t, in double for the slope's sake.
    private val cx = 3.0 * x1
    private val bx = 3.0 * (x2 - x1) - cx
    private val ax = 1.0 - cx - bx
    private val cy = 3.0 * y1
    private val by = 3.0 * (y2 - y1) - cy
    private val ay = 1.0 - cy - by

    private fun x(t: Double) = ((ax * t + bx) * t + cx) * t
    internal fun y(t: Double) = ((ay * t + by) * t + cy) * t
    private fun dx(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx
    private fun dy(t: Double) = (3.0 * ay * t + 2.0 * by) * t + cy
    private fun ddx(t: Double) = 6.0 * ax * t + 2.0 * bx
    private fun ddy(t: Double) = 6.0 * ay * t + 2.0 * by

    // x(t) sampled at even steps of t, for the careful fallback.
    private val samples = DoubleArray(SAMPLES + 1) { x(it / SAMPLES.toDouble()) }

    // The inverse, t at even steps of x, found once (on the first solve) by bisection, with its slope
    // dt/dx there. Between two steps the inverse is a cubic (Hermite) through both ends and their
    // slopes; each interval where that cubic was checked to land within a hundredth of the precision
    // asked is answered by the cubic alone. Elsewhere (where the curve's time flattens, near an end
    // whose control point sits on the time axis), Newton's method finishes from the table's guess.
    //
    // Five numbers an interval: the cubic's four coefficients, and 1 where the cubic alone answers.
    // Built whole before it is published, so a solve on another thread sees all of it or none.
    @Volatile
    private var table: DoubleArray? = null

    private fun build(): DoubleArray {
        val nodes = DoubleArray(INVERSE + 1) { j ->
            val target = j / INVERSE.toDouble()
            var a = 0.0
            var b = 1.0
            repeat(60) { val m = (a + b) / 2.0; if (x(m) < target) a = m else b = m }
            (a + b) / 2.0
        }
        val h = 1.0 / INVERSE
        val coefficients = DoubleArray(INVERSE * STRIDE)
        for (j in 0 until INVERSE) {
            val t0 = nodes[j]
            val t1 = nodes[j + 1]
            val d0 = dx(t0)
            val d1 = dx(t1)
            val o = j * STRIDE
            if (d0 > FLAT_SLOPE && d1 > FLAT_SLOPE) {
                // Hermite in s (0 to 1 across the interval): t(s) = c0 + s(c1 + s(c2 + s c3)).
                val m0 = h / d0
                val m1 = h / d1
                coefficients[o] = t0
                coefficients[o + 1] = m0
                coefficients[o + 2] = 3.0 * (t1 - t0) - 2.0 * m0 - m1
                coefficients[o + 3] = 2.0 * (t0 - t1) + m0 + m1
                var good = true
                for (k in 1 until CHECKS) {
                    val sv = k / CHECKS.toDouble()
                    val g = ((coefficients[o + 3] * sv + coefficients[o + 2]) * sv + coefficients[o + 1]) * sv + t0
                    if (g < t0 || g > t1 || abs(x(g) - (j + sv) * h) > EPSILON / 100.0) { good = false; break }
                }
                if (good) coefficients[o + 4] = 1.0
            } else {
                coefficients[o] = t0
                coefficients[o + 1] = t1 - t0
            }
        }
        table = coefficients
        return coefficients
    }

    /** The curve's parameter t at which x(t) = [x]. */
    internal fun solve(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val c = table ?: build()
        val pos = x * INVERSE
        val j = pos.toInt().coerceAtMost(INVERSE - 1)
        val s = pos - j
        val o = j * STRIDE
        var g = ((c[o + 3] * s + c[o + 2]) * s + c[o + 1]) * s + c[o]
        if (c[o + 4] != 0.0) return g
        // From the table's guess, Newton: one or two steps land within a billionth almost
        // everywhere; the careful path below covers flat stretches.
        repeat(2) {
            val err = x(g) - x
            if (abs(err) < EPSILON) return g
            val d = dx(g)
            if (abs(d) < 1e-6) return@repeat
            g -= err / d
        }
        if (g in 0.0..1.0 && abs(x(g) - x) < EPSILON) return g
        // The sample interval that holds x: x(t) is close to t for real easing curves, so x itself
        // names the interval, and a step or two either way finds it (no unpredictable search).
        var i = (x * SAMPLES).toInt()
        if (i >= SAMPLES) i = SAMPLES - 1
        while (i > 0 && samples[i] > x) i--
        while (i < SAMPLES - 1 && samples[i + 1] < x) i++
        val x0 = samples[i]
        val span = samples[i + 1] - x0
        var t = (i + if (span > 0.0) (x - x0) / span else 0.0) / SAMPLES
        val lo = i.toDouble() / SAMPLES
        val hi = (i + 1).toDouble() / SAMPLES
        // Newton's method from there: two or three steps where the curve has slope.
        for (n in 0 until NEWTON_STEPS) {
            val err = x(t) - x
            if (abs(err) < EPSILON) return t
            val d = dx(t)
            if (abs(d) < 1e-9) break
            val next = t - err / d
            // Newton can't leave the interval that holds the answer; when it would, bisect instead.
            if (next < lo || next > hi) break
            t = next
        }
        if (abs(x(t) - x) < EPSILON) return t
        // Bisection within the sample interval: always lands, even on a flat stretch.
        var a = lo
        var b = hi
        t = (a + b) / 2.0
        repeat(BISECT_STEPS) {
            val v = x(t)
            if (abs(v - x) < EPSILON) return t
            if (v < x) a = t else b = t
            t = (a + b) / 2.0
        }
        return t
    }

    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        return y(solve(fraction.toDouble())).toFloat()
    }

    // Fuseline 4: the last time solved, shared by every tween on this curve that asks for the same
    // moment in the same frame (tiles revealed together, a page's values moving as one). Used only by
    // the frame driver's own tracks, on the main thread.
    private var memoX = Double.NaN
    private var memoT = 0.0

    /** [solve], answered from the curve's last solve when it was for the same [x]. */
    internal fun solveShared(x: Double): Double {
        if (x == memoX) {
            if (Kernels.counting) Kernels.curveReused++
            return memoT
        }
        val t = solve(x)
        memoX = x
        memoT = t
        return t
    }

    /**
     * For each of the inverse's intervals, a proven bound on |dy/dx| from that interval to the end of
     * the curve (positive infinity where the time axis goes flat and the curve can be vertical):
     * |y'(t)| at its largest over the interval's t, over x'(t) at its smallest, each a quadratic whose
     * extremes are at the ends or the vertex. A tween's event horizon reads it: past here the value can
     * move no faster than this.
     */
    @Volatile
    private var steepest: DoubleArray? = null

    internal fun steepestFrom(x: Double): Double {
        val s = steepest ?: buildSteepest()
        val j = (x * INVERSE).toInt().coerceIn(0, INVERSE - 1)
        return s[j]
    }

    private fun buildSteepest(): DoubleArray {
        val nodes = DoubleArray(INVERSE + 1) { j ->
            val target = j / INVERSE.toDouble()
            var lo = 0.0
            var hi = 1.0
            repeat(60) { val m = (lo + hi) / 2.0; if (x(m) < target) lo = m else hi = m }
            (lo + hi) / 2.0
        }
        // Each node is within 2^-60 of the true one: widen the interval by a hair to cover it.
        val out = DoubleArray(INVERSE)
        var after = 0.0
        for (j in INVERSE - 1 downTo 0) {
            val t0 = (nodes[j] - 1e-12).coerceAtLeast(0.0)
            val t1 = (nodes[j + 1] + 1e-12).coerceAtMost(1.0)
            val dyMax = quadraticAbsMax(3.0 * ay, 2.0 * by, cy, t0, t1)
            val dxMin = quadraticMin(3.0 * ax, 2.0 * bx, cx, t0, t1)
            val bound = if (dxMin <= 1e-9) Double.POSITIVE_INFINITY else dyMax / dxMin * (1.0 + 1e-9)
            after = maxOf(after, bound)
            out[j] = after
        }
        steepest = out
        return out
    }

    private fun quadraticAbsMax(a: Double, b: Double, c: Double, t0: Double, t1: Double): Double {
        fun f(t: Double) = (a * t + b) * t + c
        var m = maxOf(kotlin.math.abs(f(t0)), kotlin.math.abs(f(t1)))
        if (a != 0.0) {
            val v = -b / (2.0 * a)
            if (v > t0 && v < t1) m = maxOf(m, kotlin.math.abs(f(v)))
        }
        return m
    }

    private fun quadraticMin(a: Double, b: Double, c: Double, t0: Double, t1: Double): Double {
        fun f(t: Double) = (a * t + b) * t + c
        var m = minOf(f(t0), f(t1))
        if (a != 0.0) {
            val v = -b / (2.0 * a)
            if (v > t0 && v < t1) m = minOf(m, f(v))
        }
        return m
    }

    override fun derivative(fraction: Float): Float = slope(solve(fraction.toDouble().coerceIn(0.0, 1.0))).toFloat()

    /** dy/dx at parameter [t]: finite everywhere, using the limit where x'(t) vanishes. */
    internal fun slope(t: Double): Double {
        val d = dx(t)
        if (abs(d) > FLAT) return (dy(t) / d).coerceIn(-MAX_SLOPE, MAX_SLOPE)
        // x'(t) is (nearly) zero: only at an end, when a control point sits on that end of the time axis.
        val dd = ddx(t)
        val num = dy(t)
        return when {
            abs(num) <= FLAT && abs(dd) > FLAT -> (ddy(t) / dd).coerceIn(-MAX_SLOPE, MAX_SLOPE)
            num == 0.0 -> 0.0
            else -> if (num > 0) MAX_SLOPE else -MAX_SLOPE
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CubicCurve && x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2

    override fun hashCode(): Int = ((x1.hashCode() * 31 + y1.hashCode()) * 31 + x2.hashCode()) * 31 + y2.hashCode()

    override fun toString(): String = "CubicCurve($x1, $y1, $x2, $y2)"

    internal companion object {
        const val SAMPLES = 32
        const val INVERSE = 256

        /** Points checked inside each interval of the inverse before the cubic alone answers there. */
        const val CHECKS = 8

        const val STRIDE = 5

        /** Where x'(t) is too flat for the inverse's cubic. */
        const val FLAT_SLOPE = 1e-3
        const val NEWTON_STEPS = 5
        const val BISECT_STEPS = 52

        // A billionth of the way: far below a pixel on any screen, and past what a float result shows.
        const val EPSILON = 1e-9

        /** Where x'(t) counts as zero. */
        const val FLAT = 1e-9

        /** The steepest slope a curve reports: a vertical tangent becomes this, so speeds stay finite. */
        const val MAX_SLOPE = 1e4
    }
}

/** Fuse's curves. Every movement in Fuse uses one of these. */
object Curves {
    /** Constant speed: loops, spinners, a clock's hand. */
    val Linear: Curve = LinearCurve

    /** Most movement: quick start, long gentle landing. */
    val Standard: Curve = CubicCurve(0.2f, 0f, 0f, 1f)

    /** Things arriving on screen. */
    val Enter: Curve = CubicCurve(0.05f, 0.7f, 0.1f, 1f)

    /** Things leaving: accelerate away, never linger. */
    val Exit: Curve = CubicCurve(0.3f, 0f, 0.8f, 0.15f)

    /** Crossfades between artworks. */
    val Fade: Curve = CubicCurve(0.4f, 0f, 0.2f, 1f)

    /**
     * Light travelling across glass: eases in and out more strongly than [Fade], so a sweep seems to
     * pick up speed through the middle of a tile the way a reflection does when you tilt it.
     */
    val Sweep: Curve = CubicCurve(0.45f, 0f, 0.25f, 1f)
}
