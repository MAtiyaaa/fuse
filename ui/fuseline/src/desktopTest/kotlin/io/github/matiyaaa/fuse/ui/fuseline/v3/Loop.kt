package io.github.matiyaaa.fuse.ui.fuseline.v3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * One clock for values that loop for as long as they are shown: a spinner, a pulse, a light that
 * travels and rests. Every value made from it ([animateFloat], [animateColor]) shares its time, so
 * loops started together stay in step. The clock only ticks while it is composed.
 */
@Stable
class LoopClock internal constructor(val label: String, val decorative: Boolean = true) {
    internal var playNanos by mutableLongStateOf(0L)

    /** A float going from [initialValue] to [targetValue] under [animationSpec] (usually looping). */
    @Composable
    fun animateFloat(initialValue: Float, targetValue: Float, animationSpec: Repeating, label: String = "Loop"): State<Float> {
        val from by rememberUpdatedState(initialValue)
        val to by rememberUpdatedState(targetValue)
        val spec by rememberUpdatedState(animationSpec)
        // The track is made again only when the loop itself changes, not on every frame.
        val track = remember { derivedStateOf { Track.of(spec, from, to, 0f, FloatConverter.threshold) } }
        return remember { derivedStateOf { track.value.valueAt(playNanos) } }
    }

    /** A colour going from [initialValue] to [targetValue] under [animationSpec], through Oklab. */
    @Composable
    fun animateColor(initialValue: Color, targetValue: Color, animationSpec: Repeating, label: String = "Loop"): State<Color> {
        val from by rememberUpdatedState(initialValue)
        val to by rememberUpdatedState(targetValue)
        val spec by rememberUpdatedState(animationSpec)
        val tracks = remember {
            derivedStateOf {
                val a = FloatArray(4).also { ColorConverter.write(from, it) }
                val b = FloatArray(4).also { ColorConverter.write(to, it) }
                Array(4) { i -> Track.of(spec, a[i], b[i], 0f, ColorConverter.threshold) }
            }
        }
        return remember { derivedStateOf { ColorConverter.read(FloatArray(4) { i -> tracks.value[i].valueAt(playNanos) }) } }
    }
}

/**
 * A [LoopClock] that ticks while this is composed. A [decorative] loop (a shimmer, an ambient glow)
 * thins out to every other frame while frames run late ([FramePacing]), always showing the real time;
 * one that carries meaning (a progress spinner) passes false and keeps every frame.
 */
@Composable
fun rememberLoopClock(label: String = "LoopClock", decorative: Boolean = true): LoopClock {
    val clock = remember { LoopClock(label, decorative) }
    // A page kept in the background doesn't animate; it carries on where it was when shown again.
    val active = LocalPageActive.current
    LaunchedEffect(clock, active) {
        if (!active) return@LaunchedEffect
        val from = clock.playNanos
        runFrames(Long.MAX_VALUE) { play ->
            if (!clock.decorative || FramePacing.shouldDrawDecoration()) clock.playNanos = from + play
        }
    }
    return clock
}
