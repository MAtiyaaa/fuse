package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Immutable

/**
 * How a value travels from where it is to where it is going. A motion says nothing about what is
 * moving: Fuseline runs the same motion on a float, a size, a position or a colour, one component
 * at a time, and every motion knows its exact position and speed at any moment, so whatever takes
 * over from it (another motion, a finger) starts exactly where it was and as fast as it was going.
 *
 * - [Tween]: a fixed time along a [Curve].
 * - [Spring]: a damped spring, solved exactly.
 * - [Decay]: coasting to a stop under friction, ignoring the target (a fling).
 * - [Snap]: at once.
 * - [Keyframes]: a choreography of the way between start and target.
 * - [Repeating]: a finite motion played again and again.
 * - [Delayed], [Sequence], [Parallel]: motions composed.
 */
@Immutable
sealed interface Motion

/**
 * [durationMs] long along [curve], after waiting [delayMs]. With [inheritVelocity] (the default), a
 * tween that starts while the value is already moving carries that speed on and blends into the
 * curve, so a value redirected mid-flight never jolts: on a curve that leaves gently its position and
 * speed at the start are exactly what they were, and it still arrives on time and as the curve lands.
 * A curve that leaves at a dash ([Curves.Enter]) keeps its dash, with the value's speed added. A
 * value at rest starts the curve as drawn, and a delayed tween waits still, so it starts from rest.
 */
@Immutable
data class Tween(
    val durationMs: Int,
    val delayMs: Int = 0,
    val curve: Curve = Curves.Standard,
    val inheritVelocity: Boolean = true,
) : Motion {
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
        require(dampingRatio >= 0f && dampingRatio.isFinite()) { "A spring's damping ratio can't be negative" }
        require(stiffness > 0f && stiffness.isFinite()) { "A spring needs some stiffness" }
        require(threshold == null || threshold > 0f) { "A spring's threshold must be above zero" }
    }

    // The spring's shared solution (Fuseline 4), found once per object: not part of what the spring is.
    private var kernelFound: SpringKernel? = null

    internal fun kernel(): SpringKernel = kernelFound ?: SpringKernel.of(dampingRatio, stiffness).also { kernelFound = it }

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

/**
 * Coasting under friction from the speed it has: the speed falls by [friction] per second
 * exponentially (v(t) = v₀·e^(−friction·t)), so it travels v₀ / friction in all, and it stops once
 * what is left to travel is within [threshold] (in the value's own units; null takes the value
 * type's default). The target is ignored: where it ends is where its speed takes it
 * ([projectDecay] says where in advance), which is how a fling finds its destination.
 */
@Immutable
data class Decay(val friction: Float = DEFAULT_FRICTION, val threshold: Float? = null) : Motion {
    init {
        require(friction > 0f && friction.isFinite()) { "A decay needs some friction" }
        require(threshold == null || threshold > 0f) { "A decay's threshold must be above zero" }
    }

    companion object {
        /** About as quickly as a flung list comes to rest on a phone. */
        const val DEFAULT_FRICTION = 4.2f
    }
}

/** Straight to the target after [delayMs]. */
@Immutable
data class Snap(val delayMs: Int = 0) : Motion {
    init {
        require(delayMs >= 0) { "A snap's delay can't be negative" }
    }
}

/**
 * A choreography over [durationMs]: each [Keyframe] says what share of the way from start (0) to
 * target (1) the value has reached at its time, reached along its own curve from the keyframe
 * before. Shares outside 0..1 overshoot or undershoot, for anticipation. Without a keyframe at the
 * start or the end, the start is 0 and the end is 1. Its speed is exact everywhere.
 */
@Immutable
class Keyframes(val durationMs: Int, keys: List<Keyframe>) : Motion {
    init {
        require(durationMs >= 0) { "Keyframes can't last a negative time" }
        require(keys.all { it.atMs in 0..durationMs }) { "Every keyframe falls within the keyframes' length" }
        require(keys.all { it.fraction.isFinite() }) { "A keyframe's share of the way must be finite" }
    }

    /** The keyframes in time order, with the start (0) and end (1) filled in where missing. */
    val keys: List<Keyframe> = buildList {
        // Stable: keyframes at the same moment keep the order given (a jump, from the first to the last).
        val sorted = keys.sortedBy { it.atMs }
        if (sorted.none { it.atMs == 0 }) add(Keyframe(0, 0f, Curves.Linear))
        addAll(sorted)
        if (sorted.none { it.atMs == durationMs }) add(Keyframe(durationMs, 1f, Curves.Standard))
    }

    override fun equals(other: Any?): Boolean = other is Keyframes && durationMs == other.durationMs && keys == other.keys
    override fun hashCode(): Int = durationMs * 31 + keys.hashCode()
    override fun toString(): String = "Keyframes($durationMs, $keys)"
}

/** At [atMs], [fraction] of the way from start to target, reached along [curve] from the keyframe before. */
@Immutable
data class Keyframe(val atMs: Int, val fraction: Float, val curve: Curve = Curves.Standard)

/** Whether a repeating motion starts over each time, or plays back the way it came. */
enum class RepeatMode { Restart, Reverse }

/**
 * [motion] (any motion that ends) played [iterations] times ([FOREVER] for a loop that never ends),
 * each time from the start ([RepeatMode.Restart]) or back and forth ([RepeatMode.Reverse], where
 * the speed turns round with it). [startOffsetMs] starts the loop that far in, so neighbours
 * sharing a loop can be out of step.
 */
@Immutable
data class Repeating(
    val motion: Motion,
    val iterations: Int = FOREVER,
    val mode: RepeatMode = RepeatMode.Restart,
    val startOffsetMs: Int = 0,
) : Motion {
    init {
        require(iterations >= 1) { "A repeat plays at least once" }
        require(startOffsetMs >= 0) { "A repeat's offset can't be negative" }
        require(motion.ends) { "Only a motion that ends can repeat" }
        require(motion !is Decay) { "A decay goes where its speed takes it, so it has nothing to repeat" }
    }

    val infinite: Boolean get() = iterations == FOREVER

    companion object {
        const val FOREVER = Int.MAX_VALUE
    }
}

/** [motion] after holding still for [delayMs]: it starts from rest, wherever the value was. */
@Immutable
data class Delayed(val delayMs: Int, val motion: Motion) : Motion {
    init {
        require(delayMs >= 0) { "A delay can't be negative" }
    }
}

/**
 * [legs] one after another: each runs toward the target for its own length, and the next takes
 * over exactly where the one before was and as fast as it was going. A decay followed by a spring
 * is a fling that settles on its target; a tween followed by a spring lands softly. Only the last
 * leg may go on for ever.
 */
@Immutable
class Sequence(val legs: List<Motion>) : Motion {
    constructor(vararg legs: Motion) : this(legs.toList())

    init {
        require(legs.isNotEmpty()) { "A sequence needs at least one motion" }
        require(legs.dropLast(1).all { it.ends }) { "Only a sequence's last motion may go on for ever" }
    }

    override fun equals(other: Any?): Boolean = other is Sequence && legs == other.legs
    override fun hashCode(): Int = legs.hashCode()
    override fun toString(): String = "Sequence($legs)"
}

/**
 * A different motion for each part of a value moving together: the first component (x, width, a
 * colour's lightness) under the first motion, the second under the second, and any further ones
 * under the last. A position can glide sideways on a spring while it fades up on a tween.
 */
@Immutable
class Parallel(val motions: List<Motion>) : Motion {
    constructor(vararg motions: Motion) : this(motions.toList())

    init {
        require(motions.isNotEmpty()) { "Parallel needs at least one motion" }
    }

    fun forComponent(component: Int): Motion = motions[component.coerceIn(0, motions.lastIndex)]

    override fun equals(other: Any?): Boolean = other is Parallel && motions == other.motions
    override fun hashCode(): Int = motions.hashCode()
    override fun toString(): String = "Parallel($motions)"
}

/** Whether this motion comes to an end by itself (a loop played for ever doesn't). */
val Motion.ends: Boolean
    get() = when (this) {
        is Repeating -> !infinite
        is Delayed -> motion.ends
        is Sequence -> legs.last().ends
        is Parallel -> motions.all { it.ends }
        else -> true
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

/** A [Decay] slowing by [friction] per second. */
fun decay(friction: Float = Decay.DEFAULT_FRICTION, threshold: Float? = null): Decay = Decay(friction, threshold)

/** A [Snap] after [delayMillis]. */
fun snap(delayMillis: Int = 0): Snap = Snap(delayMillis)

/** [animation] looped forever. */
fun infiniteRepeatable(animation: Motion, repeatMode: RepeatMode = RepeatMode.Restart, initialStartOffsetMs: Int = 0): Repeating =
    Repeating(animation, Repeating.FOREVER, repeatMode, initialStartOffsetMs)

/** [animation] played [iterations] times. */
fun repeatable(iterations: Int, animation: Motion, repeatMode: RepeatMode = RepeatMode.Restart): Repeating =
    Repeating(animation, iterations, repeatMode)

/** [motion] after [delayMillis] still. */
fun delayed(delayMillis: Int, motion: Motion): Delayed = Delayed(delayMillis, motion)

/** [Keyframes] over [durationMillis], built with [KeyframesBuilder.at]. */
fun keyframes(durationMillis: Int, build: KeyframesBuilder.() -> Unit): Keyframes =
    Keyframes(durationMillis, KeyframesBuilder().apply(build).keys)

class KeyframesBuilder internal constructor() {
    internal val keys = ArrayList<Keyframe>()

    /** At [ms], [fraction] of the way from start to target, along [curve] from the keyframe before. */
    fun at(ms: Int, fraction: Float, curve: Curve = Curves.Standard) {
        keys += Keyframe(ms, fraction, curve)
    }
}

/** The length of a tween given no length. */
const val DEFAULT_TWEEN_MS = 300
