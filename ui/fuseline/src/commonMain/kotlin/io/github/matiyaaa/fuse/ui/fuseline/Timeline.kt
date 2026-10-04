package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * A choreography: named tracks, each a line of keyframes over [lengthMs]. Read a track at a moment
 * with [value]; between two keyframes it travels along the later keyframe's curve, before the first
 * it holds the first and after the last it holds the last. A [TimelinePlayer] plays one.
 *
 * ```
 * val intro = Timeline(3_200) {
 *     track("trace") { at(0, 0f); at(1_100, 1f, Curves.Standard) }
 *     track("spark") { at(1_100, 0f); at(1_520, 1f, Curves.Enter) }
 * }
 * ```
 */
@Immutable
class Timeline(val lengthMs: Int, build: Builder.() -> Unit) {
    private val tracks: Map<String, List<Key>> = Builder().apply(build).tracks.mapValues { (_, keys) -> keys.sortedBy { it.atMs } }

    /** The track [name]'s value at [timeMs] (0 for a track that doesn't exist). */
    fun value(name: String, timeMs: Float): Float {
        val keys = tracks[name] ?: return 0f
        if (keys.isEmpty()) return 0f
        if (timeMs <= keys.first().atMs) return keys.first().value
        if (timeMs >= keys.last().atMs) return keys.last().value
        val next = keys.indexOfFirst { it.atMs > timeMs }
        val a = keys[next - 1]
        val b = keys[next]
        val span = (b.atMs - a.atMs).toFloat()
        val f = if (span <= 0f) 1f else b.curve.transform((timeMs - a.atMs) / span)
        return a.value + (b.value - a.value) * f
    }

    /** The names of every track. */
    val names: Set<String> get() = tracks.keys

    @Immutable
    class Key internal constructor(val atMs: Int, val value: Float, val curve: Curve)

    class Builder internal constructor() {
        internal val tracks = LinkedHashMap<String, MutableList<Key>>()

        fun track(name: String, keys: TrackBuilder.() -> Unit) {
            tracks[name] = TrackBuilder().apply(keys).keys
        }
    }

    class TrackBuilder internal constructor() {
        internal val keys = mutableListOf<Key>()

        /** At [ms], the track is [value], reached along [curve] from the keyframe before. */
        fun at(ms: Int, value: Float, curve: Curve = Curves.Standard) {
            keys += Key(ms, value, curve)
        }
    }
}

/**
 * Plays a [Timeline] from its start while [playing]: [timeMs] runs with the frame clock (scaled by
 * the system's animation speed). [seek] jumps anywhere, and wins over the frame in flight; [skip]
 * jumps to the end. [finished] once the end is reached. Under reduced motion the whole timeline
 * plays in a short time, evenly, so a choreography becomes a quick fade.
 */
@Stable
class TimelinePlayer internal constructor(val timeline: Timeline) {
    private var playedMs by mutableLongStateOf(0L)

    /** How many timeline milliseconds pass per real millisecond (more than 1 under reduced motion). */
    internal var speed = 1f

    // Where the play was anchored: a timeline time, and the frame it was at (plain, read per frame).
    private var anchorMs = 0L
    private var anchorNanos = Long.MIN_VALUE

    /** Bumped when a finished player is sent back, so it plays on from there. */
    internal var restarts by mutableStateOf(0)

    /** Where the play is, in the timeline's own milliseconds. */
    val timeMs: Float get() = playedMs.toFloat()

    val finished: Boolean get() = playedMs >= timeline.lengthMs

    /** The track [name] now. */
    operator fun get(name: String): Float = timeline.value(name, timeMs)

    fun seek(ms: Int) {
        val wasDone = finished
        playedMs = ms.toLong().coerceIn(0, timeline.lengthMs.toLong())
        anchorMs = playedMs
        anchorNanos = Long.MIN_VALUE
        if (wasDone && !finished) restarts++
    }

    fun skip() = seek(timeline.lengthMs)

    /** One frame: moves the play on and says whether it has reached the end. */
    internal fun tick(frameNanos: Long, scale: Float): Boolean {
        if (anchorNanos == Long.MIN_VALUE) anchorNanos = frameNanos
        val real = (frameNanos - anchorNanos) / NANOS_PER_MS.toFloat() / scale
        playedMs = (anchorMs + (real * speed).toLong()).coerceAtMost(timeline.lengthMs.toLong())
        return finished
    }
}

/**
 * A [TimelinePlayer] for [timeline], playing while [playing]. Under [reduced] motion it plays in
 * [reducedMs]. [onFinished] runs once it reaches the end.
 */
@Composable
fun rememberTimelinePlayer(
    timeline: Timeline,
    playing: Boolean = true,
    reduced: Boolean = false,
    reducedMs: Int = 600,
    onFinished: () -> Unit = {},
): TimelinePlayer {
    val player = remember(timeline) { TimelinePlayer(timeline) }
    player.speed = if (reduced) timeline.lengthMs / reducedMs.toFloat().coerceAtLeast(1f) else 1f
    val done by rememberUpdatedState(onFinished)
    LaunchedEffect(player, playing, player.restarts) {
        if (!playing) return@LaunchedEffect
        val scale = kotlinx.coroutines.currentCoroutineContext()[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
        if (scale == 0f) player.skip()
        while (!player.finished) {
            frame(false) { player.tick(it, scale) }
        }
        done()
    }
    return player
}
