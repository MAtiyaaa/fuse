package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ----------------------------------------------------------------------------------------------
// Running a motion: one component from [start] to [target], starting at a velocity (units per
// second). Times are in nanoseconds of play; every track gives its position and its exact speed
// at any moment, so whatever takes over from it starts exactly where and as fast as it was.

internal const val NANOS_PER_MS = 1_000_000L
internal const val NANOS_PER_SECOND = 1_000_000_000.0

/** One component of a value travelling under a motion. */
internal abstract class Track(start: Float, target: Float) {
    var start: Float = start
        protected set
    var target: Float = target
        protected set

    /** How long the motion plays, in nanoseconds ([Long.MAX_VALUE] for a loop that never ends). */
    abstract val durationNanos: Long

    abstract fun valueAt(playNanos: Long): Float

    abstract fun velocityAt(playNanos: Long): Float

    /** What [sample] found last: the value and the velocity at the same moment. */
    var sampledValue = 0f
    var sampledVelocity = 0f

    /**
     * The value and the velocity at [playNanos] at once, into [sampledValue] and [sampledVelocity]:
     * one evaluation where the two share their work (a spring's exponential and its sine), so a frame
     * costs one solve per component, not two.
     */
    open fun sample(playNanos: Long) {
        sampledValue = valueAt(playNanos)
        sampledVelocity = velocityAt(playNanos)
    }

    /** Where the motion leaves the value once it has ended (a decay's own stopping place). */
    open val endValue: Float get() = target

    /** A time no later than [durationNanos], cheaper to know (a spring's, before it nears rest). */
    open val durationLowerBound: Long get() = durationNanos

    /**
     * Fuseline 4's event horizon for one component: a play time up to which the value certainly stays
     * within [budget] of where it is at [playNanos] (sampled there by the caller), so nothing about it
     * can be seen to change before then. [playNanos] itself when there is no such promise; Long.MAX_VALUE
     * when it never strays that far again. Every bound is a proven inequality on the motion's own
     * formula, rounded early.
     */
    open fun calmUntil(playNanos: Long, budget: Double): Long = playNanos

    companion object {
        /**
         * The track for [motion] taking [start] toward [target] from [velocity]. [threshold] is how
         * close counts as arrived when the motion doesn't say; [component] picks a [Parallel]'s motion.
         */
        fun of(motion: Motion, start: Float, target: Float, velocity: Float, threshold: Float, component: Int = 0): Track = when (motion) {
            is Tween -> TweenTrack(motion, start, target, velocity)
            is Spring -> SpringTrack(motion, start, target, velocity, motion.threshold ?: threshold)
            is Decay -> DecayTrack(motion.friction, start, velocity, motion.threshold ?: threshold)
            is Snap -> SnapTrack(motion, start, target)
            is Keyframes -> KeyframesTrack(motion, start, target)
            is Repeating -> RepeatTrack(motion, start, target, threshold, component)
            is Delayed -> DelayTrack(motion, start, target, threshold, component)
            is Sequence -> SequenceTrack(motion, start, target, velocity, threshold, component)
            is Parallel -> of(motion.forComponent(component), start, target, velocity, threshold, component)
        }

        /**
         * [of], but reusing [old] in place when it is the same kind of track: a value retargeted every
         * frame (following a finger, a scroll, the selection) then makes no new objects at all.
         */
        fun reuse(old: Track?, motion: Motion, start: Float, target: Float, velocity: Float, threshold: Float, component: Int = 0): Track = when {
            motion is Spring && old is SpringTrack -> old.apply { reset(motion, start, target, velocity, motion.threshold ?: threshold) }
            motion is Tween && old is TweenTrack -> old.apply { reset(motion, start, target, velocity) }
            motion is Parallel -> reuse(old, motion.forComponent(component), start, target, velocity, threshold, component)
            else -> of(motion, start, target, velocity, threshold, component)
        }
    }
}

/**
 * A tween along its curve, with its exact speed: distance × curve slope ÷ length. A tween that
 * inherits a velocity adds k·g(τ) with g(τ) = τ(1 − τ/L)², which is zero at both ends with slope one
 * at the start and zero at the end, so it still lands where and as fast as the curve does:
 *
 * - On a curve that leaves gently (start slope up to [GENTLE_SLOPE]: Standard, Exit, Fade, Sweep,
 *   Linear), k is the difference between the value's speed and the curve's, so the tween starts at
 *   exactly the speed the value had.
 * - A curve that leaves at a dash (Enter) can't match a slower speed without first going backwards,
 *   so it keeps its dash and adds the value's speed to it: k is the value's speed.
 * - A value at rest has no motion to carry on: the curve starts as drawn.
 */
internal class TweenTrack(tween: Tween, start: Float, target: Float, velocity: Float) : Track(start, target) {
    private var curve: Curve = Curves.Linear
    private var delay = 0L
    private var length = 0L
    private var seconds = 0.0
    private var distance = 0.0
    private var duration = 0L
    override val durationNanos: Long get() = duration

    /** The speed to blend away (see the class's notes). */
    private var boost = 0.0

    init {
        reset(tween, start, target, velocity)
    }

    fun reset(tween: Tween, start: Float, target: Float, velocity: Float) {
        this.start = start
        this.target = target
        curve = tween.curve
        delay = tween.delayMs * NANOS_PER_MS
        length = tween.durationMs * NANOS_PER_MS
        seconds = length / NANOS_PER_SECOND
        distance = target.toDouble() - start.toDouble()
        duration = delay + length
        boost = if (tween.inheritVelocity && delay == 0L && length > 0L && velocity != 0f && velocity.isFinite()) {
            val startSlope = curve.derivative(0f).toDouble()
            if (abs(startSlope) > GENTLE_SLOPE) velocity.toDouble() else velocity - distance * startSlope / seconds
        } else 0.0
    }

    fun fraction(playNanos: Long): Float {
        if (length == 0L) return if (playNanos >= delay) 1f else 0f
        return ((playNanos - delay).toDouble() / length).coerceIn(0.0, 1.0).toFloat()
    }

    override fun valueAt(playNanos: Long): Float {
        if (playNanos <= delay) return if (length == 0L && playNanos >= delay) target else start
        if (playNanos >= duration) return target
        val f = fraction(playNanos)
        var v = start + distance * curve.transform(f)
        if (boost != 0.0) {
            val tau = (playNanos - delay) / NANOS_PER_SECOND
            val u = tau / seconds
            v += boost * tau * (1.0 - u) * (1.0 - u)
        }
        return v.toFloat()
    }

    override fun velocityAt(playNanos: Long): Float {
        if (playNanos < delay || length == 0L) return 0f
        if (playNanos >= duration) return (distance * curve.derivative(1f) / seconds).toFloat()
        val f = fraction(playNanos)
        var v = distance * curve.derivative(f) / seconds
        if (boost != 0.0) {
            val u = (playNanos - delay) / NANOS_PER_SECOND / seconds
            v += boost * (1.0 - u) * (1.0 - 3.0 * u)
        }
        return v.toFloat()
    }

    /**
     * Position and velocity from one solve of the curve: a cubic Bézier's parameter is found once and
     * gives both its height and its slope, where asking for each would solve it twice.
     */
    override fun sample(playNanos: Long) {
        shape(playNanos)
        sampleLike(this, playNanos)
    }

    // Fuseline 4: the curve's answer at a moment (its height and slope at the share of time played),
    // worked out once per value. Every component of a value moving under one tween (a colour's four,
    // a position's two) has the same timing and curve, so the first works it out ([shape]) and the
    // rest read it ([sampleLike]): the same numbers, solved once instead of once per component.
    private var shapeY = 0f
    private var shapeD = 0f

    /** Works out the curve's height and slope at [playNanos] (only where the formula needs them). */
    fun shape(playNanos: Long) {
        if (playNanos < delay || playNanos >= duration || length == 0L) return
        val f = fraction(playNanos)
        val c = curve
        if (c is CubicCurve) {
            // The same rounding as valueAt and velocityAt, so sampling is exactly the same function of time.
            val t = c.solveShared(f.toDouble())
            shapeY = c.y(t).toFloat()
            shapeD = c.slope(t).toFloat()
        } else {
            shapeY = c.transform(f)
            shapeD = c.derivative(f)
        }
    }

    /** Samples this component at [playNanos] from [lead]'s curve answer for the same moment (see [shape]). */
    fun sampleLike(lead: TweenTrack, playNanos: Long) {
        val value: Double = when {
            playNanos <= delay -> (if (length == 0L && playNanos >= delay) target else start).toDouble()
            playNanos >= duration -> target.toDouble()
            else -> {
                var v = start + distance * lead.shapeY
                if (boost != 0.0) {
                    val tau = (playNanos - delay) / NANOS_PER_SECOND
                    val u = tau / seconds
                    v += boost * tau * (1.0 - u) * (1.0 - u)
                }
                v
            }
        }
        val velocity: Double = when {
            playNanos < delay || length == 0L -> 0.0
            playNanos >= duration -> distance * curve.derivative(1f) / seconds
            else -> {
                var v = distance * lead.shapeD / seconds
                if (boost != 0.0) {
                    val u = (playNanos - delay) / NANOS_PER_SECOND / seconds
                    v += boost * (1.0 - u) * (1.0 - 3.0 * u)
                }
                v
            }
        }
        sampledValue = value.toFloat()
        sampledVelocity = velocity.toFloat()
    }

    /**
     * Still while it waits; after that, no faster than the distance times the curve's steepest slope
     * from here on, over the tween's length, plus the inherited speed being blended away (whose rate,
     * (1 − u)(1 − 3u), never exceeds one).
     */
    override fun calmUntil(playNanos: Long, budget: Double): Long {
        if (budget < 0.0) return playNanos
        if (playNanos < delay) return delay
        if (playNanos >= duration || length == 0L) return Long.MAX_VALUE
        val steepest = when (val c = curve) {
            is CubicCurve -> c.steepestFrom(fraction(playNanos).toDouble())
            is LinearCurve -> 1.0
            else -> return playNanos
        }
        val fastest = abs(distance) * steepest / seconds + abs(boost)
        if (fastest == 0.0) return Long.MAX_VALUE
        // The curve's own solve lands within a billionth of the way: allow for it.
        val room = budget - abs(distance) * steepest * 2e-9
        if (!(room > 0.0) || fastest.isInfinite()) return playNanos
        return minOf(later(playNanos, room / fastest), duration)
    }

    internal companion object {
        /** The steepest start a curve may have and still be matched to the value's speed exactly. */
        const val GENTLE_SLOPE = 3.0
    }
}

internal class SnapTrack(snap: Snap, start: Float, target: Float) : Track(start, target) {
    override val durationNanos: Long = snap.delayMs * NANOS_PER_MS
    override fun valueAt(playNanos: Long): Float = if (playNanos >= durationNanos) target else start
    override fun velocityAt(playNanos: Long): Float = 0f
    override fun calmUntil(playNanos: Long, budget: Double): Long =
        if (budget < 0.0) playNanos else if (playNanos < durationNanos) durationNanos else Long.MAX_VALUE
}

/**
 * A damped spring solved exactly. The displacement from the target x(t) obeys
 * x'' + 2ζω x' + ω² x = 0, with ω = √stiffness and ζ the damping ratio, from x(0) = start − target
 * and x'(0) = velocity. With a = ζω, q = a² − ω² and B = v₀ + a·x₀, every damping has one form:
 *
 *     x(t) = e^(−at) (x₀ C(t) + B S(t))
 *     v(t) = e^(−at) (v₀ C(t) + (q x₀ − a B) S(t))
 *
 * where C and S are cos(ω_d t) and sin(ω_d t)/ω_d below critical damping (ω_d = √−q), 1 and t at
 * critical, and cosh(γt) and sinh(γt)/γ above (γ = √q). S is evaluated without dividing by a tiny
 * frequency (its series where the angle is small), so springs a hair either side of critical
 * (ζ = 0.999999, 1.000001) are as exact as the critical one, and above critical the exponentials
 * are combined so nothing overflows.
 *
 * Fuseline 4: e^(−at)C(t) and e^(−at)S(t) belong to the spring, not the value, so they come from the
 * spring's [SpringKernel]: solved once a moment for everyone on that spring, or stepped on from this
 * track's own solution a frame ago. And the moment the spring comes to rest (its Newton solve) is
 * only worked out once the spring could be near it: until then a bound that costs one logarithm
 * says it isn't.
 */
internal class SpringTrack(
    spring: Spring,
    start: Float,
    target: Float,
    velocity: Float,
    threshold: Float,
) : Track(start, target) {
    private var threshold = 0f
    private var kernel: SpringKernel = spring.kernel()
    private var x0 = 0.0
    private var v0 = 0.0
    private var b = 0.0
    private var k = 0.0

    // This track's last solution: when, e^(−at)·C(t) and e^(−at)·S(t), and how many steps since one was solved afresh.
    private var lastPlay = -1L
    private var ec = 0.0
    private var es = 0.0
    private var steps = 0

    /** A time the spring is certainly still moving at (no later than [durationNanos]): see [reset]. */
    private var restBound = 0L

    init {
        reset(spring, start, target, velocity, threshold)
    }

    fun reset(spring: Spring, start: Float, target: Float, velocity: Float, threshold: Float) {
        this.start = start
        this.target = target
        this.threshold = threshold
        val kn = spring.kernel()
        kernel = kn
        x0 = start.toDouble() - target.toDouble()
        v0 = velocity.toDouble()
        b = v0 + kn.a * x0
        k = kn.q * x0 - kn.a * b
        duration = UNKNOWN
        lastPlay = -1L
        // Every bound [settle] takes the earlier of decays no faster than e^(−at) and starts at |x₀| or
        // more, so the spring can't come to rest before ln(|x₀| / threshold) / a: one logarithm, where
        // the Newton solve itself waits until the spring could be near rest.
        val ax = abs(x0)
        val th = threshold.toDouble()
        restBound = if (kn.a > 0.0 && ax > th * (1.0 + 1e-6)) {
            val seconds = min(ln(ax / th) / kn.a, MAX_SPRING_SECONDS)
            ((seconds * (1.0 - 1e-6)) * NANOS_PER_SECOND).toLong() - 1_000L
        } else 0L
    }

    /** Fills [ec] and [es] for [playNanos]: shared with the spring's other users, stepped on, or solved. */
    private fun solveAt(playNanos: Long) {
        if (playNanos == lastPlay) return
        val t = playNanos / NANOS_PER_SECOND
        val kn = kernel
        if (kn.hasMemo(t)) {
            if (Kernels.counting) Kernels.reused++
            ec = kn.memoEc
            es = kn.memoEs
            steps = 0
        } else if (lastPlay >= 0L && playNanos > lastPlay && steps < RESOLVE && freqAngle(kn, t) >= SERIES) {
            kn.step(ec, es, t, playNanos - lastPlay)
            if (Kernels.counting) Kernels.stepped++
            ec = kn.stepEc
            es = kn.stepEs
            steps++
            kn.remember(t, ec, es)
        } else {
            kn.solve(t)
            ec = kn.memoEc
            es = kn.memoEs
            steps = 0
        }
        lastPlay = playNanos
    }

    private fun freqAngle(kn: SpringKernel, t: Double) = if (kn.under || kn.over) kn.freq * t else Double.MAX_VALUE

    private var duration = UNKNOWN

    override val durationNanos: Long
        get() {
            if (duration == UNKNOWN) duration = settle()
            return duration
        }

    override val durationLowerBound: Long get() = if (duration == UNKNOWN) restBound else duration

    /** True once the spring is at rest at [playNanos] (the Newton solve only when it could be). */
    private fun atRest(playNanos: Long) = playNanos >= restBound && playNanos >= durationNanos

    /**
     * When the spring is at rest: from then on it never strays more than [threshold] from the
     * target. Each damping has a decaying bound on |x(t)|: e^(−rt)(|x₀| + |B|·min(t, cap)), with
     * r = a and cap = 1/ω_d below critical (where |S| ≤ min(t, 1/ω_d)), and r = a − γ, cap = 1/(2γ)
     * above. Below critical the ringing amplitude √(x₀² + (B/ω_d)²)·e^(−at) bounds it too; the
     * earlier of the two counts. The bound is found by Newton's method on its logarithm (concave, so
     * it converges from the right), a handful of steps.
     */
    private fun settle(): Long {
        if (x0 == 0.0 && v0 == 0.0) return 0L
        val kn = kernel
        val th = threshold.toDouble()
        val ax = abs(x0)
        val ab = abs(b)
        val rate: Double
        val cap: Double
        when {
            kn.under -> { rate = kn.a; cap = 1.0 / kn.freq }
            kn.over -> { rate = kn.a - kn.freq; cap = 1.0 / (2.0 * kn.freq) }
            else -> { rate = kn.a; cap = Double.POSITIVE_INFINITY }
        }
        var seconds = boundSettle(ax, ab, rate, cap, th)
        if (kn.under && kn.a > 0.0) {
            val amplitude = sqrt(x0 * x0 + (b / kn.freq) * (b / kn.freq))
            val ringing = if (amplitude <= th) 0.0 else ln(amplitude / th) / kn.a
            seconds = min(seconds, ringing)
        }
        if (!(seconds >= 0.0)) seconds = MAX_SPRING_SECONDS
        return (min(seconds, MAX_SPRING_SECONDS) * NANOS_PER_SECOND).toLong()
    }

    override fun valueAt(playNanos: Long): Float {
        if (atRest(playNanos)) return target
        solveAt(playNanos)
        return (target + x0 * ec + b * es).toFloat()
    }

    override fun velocityAt(playNanos: Long): Float {
        if (atRest(playNanos)) return 0f
        solveAt(playNanos)
        return (v0 * ec + k * es).toFloat()
    }

    override fun sample(playNanos: Long) {
        if (atRest(playNanos)) {
            sampledValue = target
            sampledVelocity = 0f
            return
        }
        solveAt(playNanos)
        sampledValue = (target + x0 * ec + b * es).toFloat()
        sampledVelocity = (v0 * ec + k * es).toFloat()
    }

    /**
     * How long from [playNanos] the value certainly stays within [budget] of where it is: from then on
     * it is either within budget of the target for good (the displacement's decaying envelope says so),
     * or moving no faster than its speed's decaying envelope allows, which takes at least budget / that
     * speed to cover the budget.
     */
    override fun calmUntil(playNanos: Long, budget: Double): Long {
        if (budget <= 0.0) return playNanos
        if (atRest(playNanos)) return Long.MAX_VALUE
        solveAt(playNanos)
        val kn = kernel
        val rate: Double
        val cap: Double
        when {
            kn.under -> { rate = kn.a; cap = 1.0 / kn.freq }
            kn.over -> { rate = kn.a - kn.freq; cap = 1.0 / (2.0 * kn.freq) }
            else -> { rate = kn.a; cap = Double.POSITIVE_INFINITY }
        }
        if (!(rate > 0.0)) return playNanos
        val t = playNanos / NANOS_PER_SECOND
        val here = abs(x0 * ec + b * es)
        if (here + envelope(abs(x0), abs(b), rate, cap, t) <= budget) return Long.MAX_VALUE
        val fastest = envelope(abs(v0), abs(k), rate, cap, t)
        val calm = if (fastest <= 0.0) Long.MAX_VALUE else later(playNanos, budget / fastest)
        // At rest it jumps the last of the way to the target (up to the threshold), which no speed
        // bound covers: the horizon never reaches past that moment.
        if (calm >= restBound) return minOf(calm, durationNanos)
        return calm
    }

    internal companion object {
        private const val UNKNOWN = -1L

        /** No spring runs longer than this, however soft. */
        const val MAX_SPRING_SECONDS = 60.0

        /** Below this angle, S(t) and cosh come from their series: exact, and nothing divides by nearly zero. */
        const val SERIES = 1e-3

        /** Steps from one frame's solution to the next before solving afresh (see [SpringKernel.step]). */
        const val RESOLVE = 64

        /**
         * The highest e^(−rt)(lead + growth·min(t, cap)) reaches from [t0] on: it rises while
         * growth / (lead + growth·t) > r, so its peak is at 1/r − lead/growth (capped), and it only falls after.
         */
        fun envelope(lead: Double, growth: Double, rate: Double, cap: Double, t0: Double): Double {
            if (growth == 0.0 || t0 >= cap) return exp(-rate * t0) * (lead + growth * min(t0, cap))
            val peak = (1.0 / rate - lead / growth).coerceIn(t0, cap)
            return exp(-rate * peak) * (lead + growth * peak)
        }

        /**
         * The first time after which e^(−rate·t)(ax + ab·min(t, cap)) stays within [th]: zero when it
         * starts within, else Newton's method on f(t) = ln(ax + ab·min(t, cap)) − rate·t − ln th.
         */
        fun boundSettle(ax: Double, ab: Double, rate: Double, cap: Double, th: Double): Double {
            if (rate <= 0.0) return MAX_SPRING_SECONDS
            fun lin(t: Double) = ax + ab * min(t, cap)
            fun f(t: Double) = ln(lin(t)) - rate * t - ln(th)
            fun df(t: Double) = (if (t < cap) ab / lin(t) else 0.0) - rate
            // f is concave: it rises while ab/lin > rate, peaks, then falls for good.
            val peak = if (ab > 0.0) max(0.0, min(cap, 1.0 / rate - ax / ab)) else 0.0
            // Within the threshold even at its highest: at rest from the start.
            if (f(peak) <= 0.0) return 0.0
            // A point past the root, then Newton from the right: on a concave falling curve each step
            // stays right of the root and closes in on it, so the answer is never early.
            var t = peak + 1.0 / rate
            while (f(t) > 0.0) {
                if (t >= MAX_SPRING_SECONDS) return MAX_SPRING_SECONDS
                t = 2.0 * t + 1.0 / rate
            }
            repeat(NEWTON) {
                val next = t - f(t) / df(t)
                if (t - next < 1e-9) return max(next, 0.0)
                t = next
            }
            return t
        }

        const val NEWTON = 40
    }
}

/**
 * [playNanos] plus [seconds], a little early (never late) and never past the end of time: how a
 * bound in seconds becomes the play time before which nothing can happen.
 */
internal fun later(playNanos: Long, seconds: Double): Long {
    if (!(seconds > 0.0)) return playNanos
    val nanos = seconds * NANOS_PER_SECOND * (1.0 - 1e-9) - 1.0
    if (nanos >= (Long.MAX_VALUE - playNanos).toDouble()) return Long.MAX_VALUE
    return playNanos + max(0L, nanos.toLong())
}

/**
 * Coasting under friction k from v₀: v(t) = v₀ e^(−kt) and x(t) = x₀ + (v₀/k)(1 − e^(−kt)). It ends
 * when what is left to travel, |v|/k, is within the threshold: at T = ln(|v₀| / (k·threshold)) / k,
 * having travelled (v₀/k)(1 − e^(−kT)). The target it was given is not used.
 */
internal class DecayTrack(friction: Float, start: Float, velocity: Float, threshold: Float) : Track(start, decayEnd(start, velocity, friction, threshold)) {
    private val k = friction.toDouble()
    private val v0 = velocity.toDouble()
    private val reach = v0 / k

    override val durationNanos: Long = (decaySeconds(velocity, friction, threshold) * NANOS_PER_SECOND).toLong()

    override fun valueAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return target
        return (start + reach * (1.0 - exp(-k * playNanos / NANOS_PER_SECOND))).toFloat()
    }

    override fun velocityAt(playNanos: Long): Float {
        if (playNanos > durationNanos) return 0f
        return (v0 * exp(-k * playNanos / NANOS_PER_SECOND)).toFloat()
    }

    override fun sample(playNanos: Long) {
        if (playNanos >= durationNanos) {
            sampledValue = target
            sampledVelocity = if (playNanos == durationNanos) velocityAt(playNanos) else 0f
            return
        }
        val e = exp(-k * playNanos / NANOS_PER_SECOND)
        sampledValue = (start + reach * (1.0 - e)).toFloat()
        sampledVelocity = (v0 * e).toFloat()
    }

    /** Exactly: from t₀ the value can still travel |v(t₀)|/k·(1 − e^(−kΔ)), which reaches [budget] at Δ = −ln(1 − budget·k/|v(t₀)|)/k. */
    override fun calmUntil(playNanos: Long, budget: Double): Long {
        if (budget < 0.0) return playNanos
        if (playNanos >= durationNanos) return Long.MAX_VALUE
        val speed = abs(v0) * exp(-k * playNanos / NANOS_PER_SECOND)
        if (speed == 0.0) return Long.MAX_VALUE
        val share = budget * k / speed
        if (share >= 1.0) return Long.MAX_VALUE
        return later(playNanos, -ln(1.0 - share) / k)
    }
}

/** How long a decay from [velocity] runs, in seconds. */
internal fun decaySeconds(velocity: Float, friction: Float, threshold: Float): Double {
    val left = abs(velocity.toDouble()) / friction
    if (!(left > threshold)) return 0.0
    return min(ln(left / threshold) / friction, SpringTrack.MAX_SPRING_SECONDS)
}

private fun decayEnd(start: Float, velocity: Float, friction: Float, threshold: Float): Float {
    val t = decaySeconds(velocity, friction, threshold)
    return (start + velocity.toDouble() / friction * (1.0 - exp(-friction * t))).toFloat()
}

/**
 * Where a decay from [start] at [velocity] comes to rest: the destination of a fling, known before it
 * moves, so a list can choose the item it lands on and a spring can settle there.
 */
fun projectDecay(start: Float, velocity: Float, decay: Decay = Decay(), threshold: Float = FloatConverter.threshold): Float =
    decayEnd(start, velocity, decay.friction, decay.threshold ?: threshold)

/** Keyframes: shares of the way, each segment along the arriving keyframe's curve, with exact slopes. */
internal class KeyframesTrack(keyframes: Keyframes, start: Float, target: Float) : Track(start, target) {
    private val keys = keyframes.keys
    private val times = LongArray(keys.size) { keys[it].atMs * NANOS_PER_MS }
    private val distance = target.toDouble() - start.toDouble()
    override val durationNanos: Long = keyframes.durationMs * NANOS_PER_MS

    /** The segment that holds [play]: the last key at or before it. */
    private fun segment(play: Long): Int {
        var lo = 0
        var hi = times.size - 1
        if (play >= times[hi]) return hi
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= play) lo = mid else hi = mid
        }
        return lo
    }

    override fun valueAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return (start + distance * keys.last().fraction).toFloat()
        val i = segment(playNanos.coerceAtLeast(0))
        if (i >= keys.lastIndex) return (start + distance * keys.last().fraction).toFloat()
        val a = keys[i]
        val b = keys[i + 1]
        val span = times[i + 1] - times[i]
        val share = if (span <= 0L) b.fraction.toDouble() else a.fraction + (b.fraction - a.fraction) * b.curve.transform(((playNanos - times[i]).toDouble() / span).toFloat()).toDouble()
        return (start + distance * share).toFloat()
    }

    override fun velocityAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return 0f
        val i = segment(playNanos.coerceAtLeast(0))
        if (i >= keys.lastIndex) return 0f
        val a = keys[i]
        val b = keys[i + 1]
        val span = times[i + 1] - times[i]
        if (span <= 0L) return 0f
        val f = ((playNanos - times[i]).toDouble() / span).toFloat()
        return (distance * (b.fraction - a.fraction) * b.curve.derivative(f) / (span / NANOS_PER_SECOND)).toFloat()
    }

    override val endValue: Float get() = (start + distance * keys.last().fraction).toFloat()
}

/**
 * A finite motion played again and again. A reversed iteration plays the motion backwards, so its
 * speed turns round too.
 */
internal class RepeatTrack(private val repeat: Repeating, start: Float, target: Float, threshold: Float, component: Int) : Track(start, target) {
    private val inner = of(repeat.motion, start, target, 0f, threshold, component)
    private val once = inner.durationNanos.coerceAtLeast(1)
    private val offset = repeat.startOffsetMs * NANOS_PER_MS
    override val durationNanos: Long = if (repeat.infinite) Long.MAX_VALUE else saturatingTimes(once, repeat.iterations)

    private val lastReversed = repeat.mode == RepeatMode.Reverse && repeat.iterations % 2 == 0

    /** Where in its own run the current iteration is, and whether it plays backwards. */
    private var reversed = false

    private fun local(playNanos: Long): Long {
        val t = playNanos + offset
        if (!repeat.infinite && t >= durationNanos) {
            reversed = false
            return if (lastReversed) 0 else once
        }
        val iteration = t / once
        val within = t % once
        reversed = repeat.mode == RepeatMode.Reverse && iteration % 2 == 1L
        return if (reversed) once - within else within
    }

    override fun valueAt(playNanos: Long): Float = inner.valueAt(local(playNanos))

    override fun velocityAt(playNanos: Long): Float {
        if (!repeat.infinite && playNanos + offset >= durationNanos) return 0f
        val l = local(playNanos)
        val v = inner.velocityAt(l)
        return if (reversed) -v else v
    }

    override val endValue: Float get() = if (lastReversed) inner.valueAt(0) else inner.endValue
}

private fun saturatingTimes(a: Long, b: Int): Long = if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

/** Holding still for the delay, then the motion, from rest. */
internal class DelayTrack(delayed: Delayed, start: Float, target: Float, threshold: Float, component: Int) : Track(start, target) {
    private val delay = delayed.delayMs * NANOS_PER_MS
    private val inner = of(delayed.motion, start, target, 0f, threshold, component)
    override val durationNanos: Long = if (inner.durationNanos == Long.MAX_VALUE) Long.MAX_VALUE else delay + inner.durationNanos

    override fun valueAt(playNanos: Long): Float = if (playNanos < delay) start else inner.valueAt(playNanos - delay)
    override fun velocityAt(playNanos: Long): Float = if (playNanos < delay) 0f else inner.velocityAt(playNanos - delay)
    override fun sample(playNanos: Long) {
        if (playNanos < delay) {
            sampledValue = start
            sampledVelocity = 0f
            return
        }
        inner.sample(playNanos - delay)
        sampledValue = inner.sampledValue
        sampledVelocity = inner.sampledVelocity
    }

    override fun calmUntil(playNanos: Long, budget: Double): Long {
        if (budget < 0.0) return playNanos
        if (playNanos < delay) return delay
        // The inner track is asked at its own time, after sampling there (as the caller did for this one).
        inner.sample(playNanos - delay)
        val p = inner.calmUntil(playNanos - delay, budget)
        return if (p == Long.MAX_VALUE || p > Long.MAX_VALUE - delay) Long.MAX_VALUE else p + delay
    }

    override val endValue: Float get() = inner.endValue
}

/** Legs one after another, each starting where and as fast as the one before ended. */
internal class SequenceTrack(sequence: Sequence, start: Float, target: Float, velocity: Float, threshold: Float, component: Int) : Track(start, target) {
    private val legs: Array<Track>
    private val starts: LongArray

    init {
        val built = ArrayList<Track>(sequence.legs.size)
        val at = LongArray(sequence.legs.size)
        var from = start
        var speed = velocity
        var time = 0L
        for ((i, m) in sequence.legs.withIndex()) {
            val leg = of(m, from, target, speed, threshold, component)
            built += leg
            at[i] = time
            if (i < sequence.legs.lastIndex) {
                val d = leg.durationNanos
                from = leg.valueAt(d)
                speed = leg.velocityAt(d)
                time = if (time > Long.MAX_VALUE - d) Long.MAX_VALUE else time + d
            }
        }
        legs = built.toTypedArray()
        starts = at
    }

    override val durationNanos: Long = legs.last().durationNanos.let { d -> if (d == Long.MAX_VALUE || starts.last() > Long.MAX_VALUE - d) Long.MAX_VALUE else starts.last() + d }

    private fun leg(play: Long): Int {
        var i = legs.lastIndex
        while (i > 0 && play < starts[i]) i--
        return i
    }

    override fun valueAt(playNanos: Long): Float {
        val i = leg(playNanos)
        return legs[i].valueAt(playNanos - starts[i])
    }

    override fun velocityAt(playNanos: Long): Float {
        val i = leg(playNanos)
        return legs[i].velocityAt(playNanos - starts[i])
    }

    override fun sample(playNanos: Long) {
        val i = leg(playNanos)
        val l = legs[i]
        l.sample(playNanos - starts[i])
        sampledValue = l.sampledValue
        sampledVelocity = l.sampledVelocity
    }

    override val endValue: Float get() = legs.last().endValue
}
