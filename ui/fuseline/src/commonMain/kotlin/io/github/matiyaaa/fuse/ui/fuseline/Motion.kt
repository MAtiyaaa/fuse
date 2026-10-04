package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a value travels from where it is to where it is going: over a fixed time along a curve
 * ([Tween]), as a spring ([Spring]), at once ([Snap]), or a tween played again and again
 * ([Repeating]). A motion says nothing about what is moving: Fuseline runs the same motion on a
 * float, a size, a position or a colour, one component at a time.
 */
@Immutable
sealed interface Motion

/** [durationMs] long along [curve], after waiting [delayMs]. */
@Immutable
data class Tween(val durationMs: Int, val delayMs: Int = 0, val curve: Curve = Curves.Standard) : Motion {
    init {
        require(durationMs >= 0 && delayMs >= 0) { "A tween's times can't be negative" }
    }
}

/**
 * A spring pulling the value to its target: [stiffness] is how hard it pulls, [dampingRatio] how
 * quickly it stops swinging (1 settles without overshooting, below 1 swings past and back, above
 * 1 creeps in). Mass is one. It starts with the value's current velocity, so a spring retargeted
 * mid-flight carries on smoothly. It is done once within [threshold] of the target (in the value's
 * own units; null takes the value type's default) and nearly still.
 */
@Immutable
data class Spring(
    val dampingRatio: Float = DampingRatioNoBouncy,
    val stiffness: Float = StiffnessMedium,
    val threshold: Float? = null,
) : Motion {
    init {
        require(dampingRatio >= 0f) { "A spring's damping ratio can't be negative" }
        require(stiffness > 0f) { "A spring needs some stiffness" }
    }

    companion object {
        const val DampingRatioHighBouncy = 0.2f
        const val DampingRatioMediumBouncy = 0.5f
        const val DampingRatioLowBouncy = 0.75f
        const val DampingRatioNoBouncy = 1f
        const val StiffnessHigh = 10_000f
        const val StiffnessMedium = 1_500f
        const val StiffnessMediumLow = 400f
        const val StiffnessLow = 200f
        const val StiffnessVeryLow = 50f
    }
}

/** Straight to the target after [delayMs]. */
@Immutable
data class Snap(val delayMs: Int = 0) : Motion

/** Whether a repeating tween starts over each time, or plays back the way it came. */
enum class RepeatMode { Restart, Reverse }

/**
 * [tween] played [iterations] times ([FOREVER] for a loop that never ends), each time from the
 * start ([RepeatMode.Restart]) or back and forth ([RepeatMode.Reverse]). [startOffsetMs] starts the
 * loop that far in, so neighbours sharing a loop can be out of step.
 */
@Immutable
data class Repeating(
    val tween: Tween,
    val iterations: Int = FOREVER,
    val mode: RepeatMode = RepeatMode.Restart,
    val startOffsetMs: Int = 0,
) : Motion {
    init {
        require(iterations >= 1) { "A repeat plays at least once" }
    }

    val infinite: Boolean get() = iterations == FOREVER

    companion object {
        const val FOREVER = Int.MAX_VALUE
    }
}

// ----------------------------------------------------------------------------------------------
// Builders. They read like the motion they make, and take the same names a reader of animation
// code expects, so a spec reads the same wherever it appears.

/** A [Tween]: [durationMillis] long along [easing], after [delayMillis]. */
fun tween(durationMillis: Int = DEFAULT_TWEEN_MS, delayMillis: Int = 0, easing: Curve = Curves.Standard): Tween =
    Tween(durationMillis, delayMillis, easing)

/** A [Spring]. [visibilityThreshold] is how close counts as there, in the value's own units. */
fun spring(
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMedium,
    visibilityThreshold: Float? = null,
): Spring = Spring(dampingRatio, stiffness, visibilityThreshold)

/** A [Snap] after [delayMillis]. */
fun snap(delayMillis: Int = 0): Snap = Snap(delayMillis)

/** [animation] looped forever. */
fun infiniteRepeatable(animation: Tween, repeatMode: RepeatMode = RepeatMode.Restart, initialStartOffsetMs: Int = 0): Repeating =
    Repeating(animation, Repeating.FOREVER, repeatMode, initialStartOffsetMs)

/** [animation] played [iterations] times. */
fun repeatable(iterations: Int, animation: Tween, repeatMode: RepeatMode = RepeatMode.Restart): Repeating =
    Repeating(animation, iterations, repeatMode)

/** The length of a tween given no length. */
const val DEFAULT_TWEEN_MS = 300

// ----------------------------------------------------------------------------------------------
// Running a motion: one component from [start] to [target], starting at [velocity] (units per
// second). Times are in nanoseconds of play.

internal const val NANOS_PER_MS = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** One component of a value travelling under a motion. */
internal sealed class Track(val start: Float, val target: Float) {
    /** How long the motion plays, in nanoseconds ([Long.MAX_VALUE] for a loop that never ends). */
    abstract val durationNanos: Long

    abstract fun valueAt(playNanos: Long): Float

    abstract fun velocityAt(playNanos: Long): Float

    companion object {
        fun of(motion: Motion, start: Float, target: Float, velocity: Float, threshold: Float): Track = when (motion) {
            is Tween -> TweenTrack(motion, start, target)
            is Spring -> SpringTrack(motion, start, target, velocity, motion.threshold ?: threshold)
            is Snap -> SnapTrack(motion, start, target)
            is Repeating -> RepeatTrack(motion, start, target)
        }
    }
}

internal class TweenTrack(private val tween: Tween, start: Float, target: Float) : Track(start, target) {
    private val delay = tween.delayMs * NANOS_PER_MS
    private val length = tween.durationMs * NANOS_PER_MS
    override val durationNanos: Long = delay + length

    fun fraction(playNanos: Long): Float {
        if (length == 0L) return if (playNanos >= delay) 1f else 0f
        return ((playNanos - delay).toFloat() / length).coerceIn(0f, 1f)
    }

    override fun valueAt(playNanos: Long): Float {
        val f = tween.curve.transform(fraction(playNanos))
        return start + (target - start) * f
    }

    /** A tween's speed, measured across a millisecond, for a spring that takes over from it. */
    override fun velocityAt(playNanos: Long): Float {
        if (playNanos <= delay || playNanos >= durationNanos) return 0f
        val before = valueAt((playNanos - NANOS_PER_MS).coerceAtLeast(0))
        val now = valueAt(playNanos)
        return (now - before) * 1_000f
    }
}

internal class SnapTrack(private val snap: Snap, start: Float, target: Float) : Track(start, target) {
    override val durationNanos: Long = snap.delayMs * NANOS_PER_MS
    override fun valueAt(playNanos: Long): Float = if (playNanos >= durationNanos) target else start
    override fun velocityAt(playNanos: Long): Float = 0f
}

internal class RepeatTrack(private val repeat: Repeating, start: Float, target: Float) : Track(start, target) {
    private val inner = TweenTrack(repeat.tween, start, target)
    private val once = inner.durationNanos.coerceAtLeast(1)
    private val offset = repeat.startOffsetMs * NANOS_PER_MS
    override val durationNanos: Long = if (repeat.infinite) Long.MAX_VALUE else once * repeat.iterations

    /** Where in its own run the current iteration is, played backwards on reversed iterations. */
    private fun local(playNanos: Long): Long {
        val t = playNanos + offset
        if (!repeat.infinite && t >= durationNanos) {
            // Finished: where the last iteration ends.
            val lastReversed = repeat.mode == RepeatMode.Reverse && repeat.iterations % 2 == 0
            return if (lastReversed) 0 else once
        }
        val iteration = t / once
        val within = t % once
        return if (repeat.mode == RepeatMode.Reverse && iteration % 2 == 1L) once - within else within
    }

    override fun valueAt(playNanos: Long): Float = inner.valueAt(local(playNanos))
    override fun velocityAt(playNanos: Long): Float = inner.velocityAt(local(playNanos))
}

/**
 * A damped spring solved exactly: the displacement from the target x(t) obeys
 * x'' + 2 ζ ω x' + ω² x = 0, with ω = √stiffness and ζ the damping ratio, from x(0) = start - target
 * and x'(0) = velocity. Under-, critically and over-damped springs each have their closed form.
 */
internal class SpringTrack(
    spring: Spring,
    start: Float,
    target: Float,
    private val velocity: Float,
    private val threshold: Float,
) : Track(start, target) {
    private val omega = sqrt(spring.stiffness.toDouble())
    private val zeta = spring.dampingRatio.toDouble()
    private val x0 = (start - target).toDouble()
    private val v0 = velocity.toDouble()

    // Under-damped: the frequency it rings at.
    private val omegaD = if (zeta < 1.0) omega * sqrt(1.0 - zeta * zeta) else 0.0

    // Over-damped: the two decay rates and their weights.
    private val r1: Double
    private val r2: Double
    private val c1: Double
    private val c2: Double

    init {
        if (zeta > 1.0) {
            val root = sqrt(zeta * zeta - 1.0)
            r1 = -omega * (zeta - root)
            r2 = -omega * (zeta + root)
            c2 = (r1 * x0 - v0) / (r1 - r2)
            c1 = x0 - c2
        } else {
            r1 = 0.0; r2 = 0.0; c1 = 0.0; c2 = 0.0
        }
    }

    // Under-damped: the weight of the sine term, fixed for the whole move.
    private val bUnder = if (zeta < 1.0) (v0 + zeta * omega * x0) / omegaD else 0.0

    // Critically damped: the weight of the linear term.
    private val bCritical = v0 + omega * x0

    /** Displacement at [t] seconds, without allocating (it runs every frame). */
    private fun displacement(t: Double): Double = when {
        zeta < 1.0 -> exp(-zeta * omega * t) * (x0 * cos(omegaD * t) + bUnder * sin(omegaD * t))
        zeta == 1.0 -> (x0 + bCritical * t) * exp(-omega * t)
        else -> c1 * exp(r1 * t) + c2 * exp(r2 * t)
    }

    /** Velocity at [t] seconds. */
    private fun speed(t: Double): Double = when {
        zeta < 1.0 -> {
            val decay = exp(-zeta * omega * t)
            val cos = cos(omegaD * t)
            val sin = sin(omegaD * t)
            decay * ((bUnder * omegaD - zeta * omega * x0) * cos - (x0 * omegaD + zeta * omega * bUnder) * sin)
        }
        zeta == 1.0 -> (bCritical - omega * (x0 + bCritical * t)) * exp(-omega * t)
        else -> c1 * r1 * exp(r1 * t) + c2 * r2 * exp(r2 * t)
    }

    private fun resting(ms: Long): Boolean {
        val t = ms / 1_000.0
        val x = displacement(t)
        return abs(x) <= threshold && x * speed(t) <= 0.0
    }

    /**
     * When the spring is at rest: it never again strays more than [threshold] from the target. A
     * spring that swings past is bounded by its decaying envelope, so the moment that envelope is
     * within the threshold it is done (never in the middle of an overshoot). One that doesn't
     * swing is done once within the threshold and heading home: found in strides, then to the
     * millisecond, so a spring retargeted every frame stays cheap.
     */
    override val durationNanos: Long by lazy(LazyThreadSafetyMode.NONE) {
        if (x0 == 0.0 && v0 == 0.0) return@lazy 0L
        if (zeta < 1.0) {
            val amplitude = sqrt(x0 * x0 + bUnder * bUnder)
            if (amplitude <= threshold) return@lazy 0L
            val seconds = kotlin.math.ln(amplitude / threshold) / (zeta * omega)
            return@lazy (seconds * NANOS_PER_SECOND).toLong().coerceAtMost(MAX_SPRING_MS * NANOS_PER_MS)
        }
        if (resting(0)) return@lazy 0L
        var ms = 0L
        while (ms < MAX_SPRING_MS && !resting(ms + STRIDE_MS)) ms += STRIDE_MS
        // The first resting millisecond within the last stride.
        while (ms < MAX_SPRING_MS && !resting(ms)) ms++
        ms.coerceAtMost(MAX_SPRING_MS) * NANOS_PER_MS
    }

    override fun valueAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return target
        return (target + displacement(playNanos / NANOS_PER_SECOND)).toFloat()
    }

    override fun velocityAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return 0f
        return speed(playNanos / NANOS_PER_SECOND).toFloat()
    }

    private companion object {
        /** No spring runs longer than this, however soft. */
        const val MAX_SPRING_MS = 60_000L

        /** How far the search for a calm spring's end steps before it narrows to the millisecond. */
        const val STRIDE_MS = 8L

    }
}
