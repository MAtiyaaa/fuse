package io.github.matiyaaa.fuse.ui.fuseline.v1

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/**
 * How a movement spends its time: [transform] takes the share of the time gone (0 to 1) and gives
 * the share of the way travelled. Fuseline's tweens and timelines run their time through a curve.
 */
fun interface Curve {
    fun transform(fraction: Float): Float
}

/**
 * A cubic Bézier curve from (0, 0) to (1, 1) with the control points ([x1], [y1]) and ([x2], [y2]),
 * the shape CSS and design tools describe easing with. The curve is given as x(t) and y(t); for a
 * fraction of time (an x) it finds t by Newton's method, falling back to bisection where the slope
 * flattens, then returns y(t). Exact to well under a thousandth everywhere.
 */
@Immutable
class CubicCurve(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Curve {
    init {
        require(x1 in 0f..1f && x2 in 0f..1f) { "A curve's control points must lie between 0 and 1 in time" }
    }

    // Polynomial coefficients of x(t) and y(t): a t^3 + b t^2 + c t.
    private val cx = 3f * x1
    private val bx = 3f * (x2 - x1) - cx
    private val ax = 1f - cx - bx
    private val cy = 3f * y1
    private val by = 3f * (y2 - y1) - cy
    private val ay = 1f - cy - by

    private fun x(t: Float) = ((ax * t + bx) * t + cx) * t
    private fun y(t: Float) = ((ay * t + by) * t + cy) * t
    private fun dx(t: Float) = (3f * ax * t + 2f * bx) * t + cx

    // x(t) sampled at even steps of t, so a solve starts next to its answer.
    private val samples = FloatArray(SAMPLES + 1) { x(it / SAMPLES.toFloat()) }

    /** The curve's parameter t at which x(t) = [x]. */
    internal fun solve(x: Float): Float {
        // The sample interval that holds x (x(t) only ever rises), then a guess inside it.
        var i = 1
        while (i < SAMPLES && samples[i] < x) i++
        val x0 = samples[i - 1]
        val span = samples[i] - x0
        var t = (i - 1 + if (span > 0f) (x - x0) / span else 0f) / SAMPLES
        // Newton's method from there: two or three steps where the curve has slope.
        repeat(NEWTON_STEPS) {
            val err = x(t) - x
            if (abs(err) < EPSILON) return t
            val d = dx(t)
            if (abs(d) < 1e-6f) return@repeat
            t -= err / d
        }
        // Bisection within the sample interval: always lands, even on a flat stretch.
        var lo = (i - 1) / SAMPLES.toFloat()
        var hi = i / SAMPLES.toFloat()
        t = (lo + hi) / 2f
        repeat(BISECT_STEPS) {
            val v = x(t)
            if (abs(v - x) < EPSILON) return t
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return t
    }

    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        return y(solve(fraction))
    }

    override fun equals(other: Any?): Boolean =
        other is CubicCurve && x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2

    override fun hashCode(): Int = ((x1.hashCode() * 31 + y1.hashCode()) * 31 + x2.hashCode()) * 31 + y2.hashCode()

    override fun toString(): String = "CubicCurve($x1, $y1, $x2, $y2)"

    private companion object {
        const val SAMPLES = 32
        const val NEWTON_STEPS = 4
        const val BISECT_STEPS = 24

        // A float holds about seven digits; a hundred-thousandth of the way is far below a pixel.
        const val EPSILON = 1e-5f
    }
}

/** Fuse's curves. Every movement in Fuse uses one of these. */
object Curves {
    /** Constant speed: loops, spinners, a clock's hand. */
    val Linear: Curve = Curve { it }

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
