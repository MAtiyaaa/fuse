package io.github.matiyaaa.fuse.ui.fuseline.v31

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
        val c = curve
        if (c !is CubicCurve || playNanos <= delay || playNanos >= duration || length == 0L) {
            super.sample(playNanos)
            return
        }
        // The same rounding as valueAt and velocityAt, so sampling is exactly the same function of time.
        val f = fraction(playNanos)
        val t = c.solve(f.toDouble())
        var value = start + distance * c.y(t).toFloat()
        var velocity = distance * c.slope(t).toFloat() / seconds
        if (boost != 0.0) {
            val tau = (playNanos - delay) / NANOS_PER_SECOND
            val u = tau / seconds
            value += boost * tau * (1.0 - u) * (1.0 - u)
            velocity += boost * (1.0 - u) * (1.0 - 3.0 * u)
        }
        sampledValue = value.toFloat()
        sampledVelocity = velocity.toFloat()
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
 */
internal class SpringTrack(
    spring: Spring,
    start: Float,
    target: Float,
    velocity: Float,
    threshold: Float,
) : Track(start, target) {
    private var threshold = 0f
    private var a = 0.0
    private var q = 0.0
    private var x0 = 0.0
    private var v0 = 0.0
    private var b = 0.0
    private var k = 0.0

    /** Below critical: the ringing frequency; above: γ. Zero at critical. */
    private var freq = 0.0
    private var under = false
    private var over = false

    // Filled by [solve]: e^(−at)·C(t) and e^(−at)·S(t).
    private var ec = 0.0
    private var es = 0.0

    init {
        reset(spring, start, target, velocity, threshold)
    }

    fun reset(spring: Spring, start: Float, target: Float, velocity: Float, threshold: Float) {
        this.start = start
        this.target = target
        this.threshold = threshold
        val omega = sqrt(spring.stiffness.toDouble())
        val zeta = spring.dampingRatio.toDouble()
        a = zeta * omega
        q = omega * omega * (zeta - 1.0) * (zeta + 1.0)
        x0 = start.toDouble() - target.toDouble()
        v0 = velocity.toDouble()
        b = v0 + a * x0
        k = q * x0 - a * b
        freq = sqrt(abs(q))
        under = q < 0.0
        over = q > 0.0
        duration = UNKNOWN
    }

    private fun solve(t: Double) {
        when {
            under -> {
                val e = exp(-a * t)
                val u = freq * t
                ec = e * cos(u)
                es = e * if (abs(u) < SERIES) t * (1.0 - u * u / 6.0) else sin(u) / freq
            }
            over -> {
                val u = freq * t
                if (u < SERIES) {
                    val e = exp(-a * t)
                    ec = e * (1.0 + u * u / 2.0)
                    es = e * t * (1.0 + u * u / 6.0)
                } else {
                    // γ < a always, so e^((γ−a)t) only shrinks: no overflow however long it runs.
                    val e1 = exp((freq - a) * t)
                    val e2 = exp(-(freq + a) * t)
                    ec = (e1 + e2) / 2.0
                    es = (e1 - e2) / (2.0 * freq)
                }
            }
            else -> {
                val e = exp(-a * t)
                ec = e
                es = e * t
            }
        }
    }

    /**
     * When the spring is at rest: from then on it never strays more than [threshold] from the
     * target. Each damping has a decaying bound on |x(t)|: e^(−rt)(|x₀| + |B|·min(t, cap)), with
     * r = a and cap = 1/ω_d below critical (where |S| ≤ min(t, 1/ω_d)), and r = a − γ, cap = 1/(2γ)
     * above. Below critical the ringing amplitude √(x₀² + (B/ω_d)²)·e^(−at) bounds it too; the
     * earlier of the two counts. The bound is found by Newton's method on its logarithm (concave, so
     * it converges from the right), a handful of steps, so a spring retargeted every frame stays cheap.
     */
    private var duration = UNKNOWN

    override val durationNanos: Long
        get() {
            if (duration == UNKNOWN) duration = settle()
            return duration
        }

    private fun settle(): Long {
        if (x0 == 0.0 && v0 == 0.0) return 0L
        val th = threshold.toDouble()
        val ax = abs(x0)
        val ab = abs(b)
        val rate: Double
        val cap: Double
        when {
            under -> { rate = a; cap = 1.0 / freq }
            over -> { rate = a - freq; cap = 1.0 / (2.0 * freq) }
            else -> { rate = a; cap = Double.POSITIVE_INFINITY }
        }
        var seconds = boundSettle(ax, ab, rate, cap, th)
        if (under && a > 0.0) {
            val amplitude = sqrt(x0 * x0 + (b / freq) * (b / freq))
            val ringing = if (amplitude <= th) 0.0 else ln(amplitude / th) / a
            seconds = min(seconds, ringing)
        }
        if (!(seconds >= 0.0)) seconds = MAX_SPRING_SECONDS
        return (min(seconds, MAX_SPRING_SECONDS) * NANOS_PER_SECOND).toLong()
    }

    override fun valueAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return target
        solve(playNanos / NANOS_PER_SECOND)
        return (target + x0 * ec + b * es).toFloat()
    }

    override fun velocityAt(playNanos: Long): Float {
        if (playNanos >= durationNanos) return 0f
        solve(playNanos / NANOS_PER_SECOND)
        return (v0 * ec + k * es).toFloat()
    }

    override fun sample(playNanos: Long) {
        if (playNanos >= durationNanos) {
            sampledValue = target
            sampledVelocity = 0f
            return
        }
        solve(playNanos / NANOS_PER_SECOND)
        sampledValue = (target + x0 * ec + b * es).toFloat()
        sampledVelocity = (v0 * ec + k * es).toFloat()
    }

    internal companion object {
        private const val UNKNOWN = -1L

        /** No spring runs longer than this, however soft. */
        const val MAX_SPRING_SECONDS = 60.0

        /** Below this angle, S(t) and cosh come from their series: exact, and nothing divides by nearly zero. */
        const val SERIES = 1e-3

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
