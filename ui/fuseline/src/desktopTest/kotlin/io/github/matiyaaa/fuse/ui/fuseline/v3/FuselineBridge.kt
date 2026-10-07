package io.github.matiyaaa.fuse.ui.fuseline.v3

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.ui.unit.IntOffset

/**
 * The one place Fuseline meets Compose's own animation types. Lazy lists move their items into new
 * places themselves and only take a Compose spec for it, so Fuseline hands them one whose numbers
 * all come from Fuseline's own tracks (a spring, a tween): the shape of the movement is Fuse's.
 * Nothing else in Fuse may import `androidx.compose.animation` (a test checks).
 */
object FuselineBridge {
    /** How lazy list items glide to a new place, moved by [motion]. */
    fun placement(motion: Motion): FiniteAnimationSpec<IntOffset> = BridgeSpec(motion, IntOffsetConverter.threshold)
}

private class BridgeSpec<T>(private val motion: Motion, private val threshold: Float) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        BridgeVectorized(motion, threshold)
}

/** Positions are two numbers (x and y), which is all lazy item placement moves. */
@Suppress("UNCHECKED_CAST")
private class BridgeVectorized<V : AnimationVector>(private val motion: Motion, private val threshold: Float) : VectorizedFiniteAnimationSpec<V> {
    // Asked every frame for the same move: the last move's tracks are kept.
    private var lastKey: FloatArray? = null
    private var last: Pair<Track, Track>? = null

    private fun tracks(initial: V, target: V, velocity: V): Pair<Track, Track> {
        val a = initial as AnimationVector2D
        val b = target as AnimationVector2D
        val v = velocity as AnimationVector2D
        val key = floatArrayOf(a.v1, a.v2, b.v1, b.v2, v.v1, v.v2)
        val cached = last
        if (cached != null && lastKey.contentEquals(key)) return cached
        return (Track.of(motion, a.v1, b.v1, v.v1, threshold) to Track.of(motion, a.v2, b.v2, v.v2, threshold)).also {
            last = it
            lastKey = key
        }
    }

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
        val (x, y) = tracks(initialValue, targetValue, initialVelocity)
        return AnimationVector2D(x.valueAt(playTimeNanos), y.valueAt(playTimeNanos)) as V
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
        val (x, y) = tracks(initialValue, targetValue, initialVelocity)
        return AnimationVector2D(x.velocityAt(playTimeNanos), y.velocityAt(playTimeNanos)) as V
    }

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long {
        val (x, y) = tracks(initialValue, targetValue, initialVelocity)
        return maxOf(x.durationNanos, y.durationNanos)
    }
}
