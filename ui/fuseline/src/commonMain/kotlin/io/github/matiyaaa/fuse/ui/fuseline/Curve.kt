package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Immutable
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
    private fun y(t: Double) = ((ay * t + by) * t + cy) * t
    private fun dx(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx
    private fun dy(t: Double) = (3.0 * ay * t + 2.0 * by) * t + cy
    private fun ddx(t: Double) = 6.0 * ax * t + 2.0 * bx
    private fun ddy(t: Double) = 6.0 * ay * t + 2.0 * by

    // x(t) sampled at even steps of t, so a solve starts next to its answer.
    private val samples = DoubleArray(SAMPLES + 1) { x(it / SAMPLES.toDouble()) }

    /** The curve's parameter t at which x(t) = [x]. */
    internal fun solve(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        // The sample interval that holds x (x(t) only ever rises): a binary search over the samples.
        var lo = 0
        var hi = SAMPLES
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (samples[mid] < x) lo = mid else hi = mid
        }
        val x0 = samples[lo]
        val span = samples[hi] - x0
        var t = (lo + if (span > 0.0) (x - x0) / span else 0.0) / SAMPLES
        // Newton's method from there: two or three steps where the curve has slope.
        repeat(NEWTON_STEPS) {
            val err = x(t) - x
            if (abs(err) < EPSILON) return t
            val d = dx(t)
            if (abs(d) < 1e-9) return@repeat
            val next = t - err / d
            // Newton can't leave the interval that holds the answer; when it would, bisect instead.
            if (next < lo.toDouble() / SAMPLES || next > hi.toDouble() / SAMPLES) return@repeat
            t = next
        }
        if (abs(x(t) - x) < EPSILON) return t
        // Bisection within the sample interval: always lands, even on a flat stretch.
        var a = lo.toDouble() / SAMPLES
        var b = hi.toDouble() / SAMPLES
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
        const val NEWTON_STEPS = 6
        const val BISECT_STEPS = 52

        // Far below a pixel on any screen, and well within a double.
        const val EPSILON = 1e-12

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
