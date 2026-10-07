package io.github.matiyaaa.fuse.ui.fuseline

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
        // Time held still while the person is doing something ([FramePacing.decorationHeld]): the
        // loop carries on from where it paused, never jumping ahead.
        var held = 0L
        var last = 0L
        runFrames(Long.MAX_VALUE) { play ->
            val step = play - last
            last = play
            if (clock.decorative && FramePacing.decorationHeld()) held += step
            else if (!clock.decorative || FramePacing.shouldDrawDecoration()) clock.playNanos = from + play - held
        }
    }
    return clock
}

/**
 * Fuseline 3.1: the time of a decorative loop that needs [fps] updates a second (a room's light, a
 * slow drift), in nanoseconds since it started, given to [onFrame] only when it is due. Between updates
 * it waits instead of taking every frame of the display, so a 30 a second loop on a 120 Hz screen
 * wakes 30 times a second, not 120. It holds still while the person is doing something
 * ([FramePacing.decorationHeld]) and carries on from there, never jumping, and thins out with the
 * other decoration while frames run late. [infinite] frames go through the platform's policy for
 * endless animation ([withInfiniteFrameMillis]). Returns only when cancelled.
 */
suspend fun decorationFrames(fps: Int, infinite: Boolean = true, onFrame: (playedNanos: Long) -> Unit) {
    val every = 1_000_000_000L / fps.coerceAtLeast(1)
    var last = frame(infinite) { it }
    var played = 0L
    var shownAt = 0L
    while (true) {
        val now = frame(infinite) { it }
        FramePacing.decorationWakeups++
        val step = (now - last).coerceAtLeast(0L)
        last = now
        if (!FramePacing.decorationHeld()) {
            played += step
            if (played - shownAt >= every && FramePacing.shouldDrawDecoration()) {
                shownAt = played
                onFrame(played)
            }
        }
        // Wait out the rest of the interval, less a frame, rather than taking frames in between.
        val wait = every - (played - shownAt) - FramePacing.intervalNanos
        if (wait > MIN_WAIT_NANOS) kotlinx.coroutines.delay(wait / NANOS_PER_MS)
    }
}

/** Waits shorter than this aren't worth a timer: the next frame comes sooner. */
private const val MIN_WAIT_NANOS = 4_000_000L
