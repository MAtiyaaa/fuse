package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * A choreography: named tracks, each a line of keyframes over [lengthMs]. Read a track at a moment
 * with [value] (and its exact speed with [velocity]); between two keyframes it travels along the
 * later keyframe's curve, before the first it holds the first and after the last it holds the last.
 * A [TimelinePlayer] plays one, on one clock, forward or back, at any speed, from any moment.
 *
 * Timelines compose: [Builder.include] nests one inside another at a moment (and speed), so a
 * [sequence] or [parallel] of timelines is itself a timeline; [Builder.stagger] gives a run of tracks
 * the same keyframes, each a beat after the one before; a track can [TrackBuilder.repeat] its own
 * keyframes, straight again or back and forth.
 *
 * ```
 * val intro = Timeline(3_200) {
 *     track("trace") { at(0, 0f); at(1_100, 1f, Curves.Standard) }
 *     track("spark") { at(1_100, 0f); at(1_520, 1f, Curves.Enter) }
 *     stagger(listOf("a", "b", "c"), everyMs = 40) { at(1_600, 0f); at(1_900, 1f, Curves.Enter) }
 * }
 * ```
 */
@Immutable
class Timeline(val lengthMs: Int, build: Builder.() -> Unit) {
    init {
        require(lengthMs >= 0) { "A timeline can't last a negative time" }
    }

    internal val tracks: Map<String, TrackData> = Builder().apply(build).tracks.mapValues { (_, b) -> b.build() }

    /** The track [name]'s value at [timeMs] (0 for a track that doesn't exist). */
    fun value(name: String, timeMs: Float): Float = tracks[name]?.value(timeMs) ?: 0f

    /** How fast the track [name] changes at [timeMs], per second, exactly. */
    fun velocity(name: String, timeMs: Float): Float = tracks[name]?.velocity(timeMs) ?: 0f

    /** The names of every track. */
    val names: Set<String> get() = tracks.keys

    @Immutable
    class Key internal constructor(val atMs: Float, val value: Float, val curve: Curve)

    /** One track: its keys in time order, and how its run of keys repeats. */
    internal class TrackData(val keys: List<Key>, val times: Int, val reverse: Boolean) {
        private val at = FloatArray(keys.size) { keys[it].atMs }
        private val first = if (keys.isEmpty()) 0f else at.first()
        private val span = if (keys.isEmpty()) 0f else at.last() - first

        /** The time within the keys a moment maps to, and whether it plays backwards there. */
        private fun local(timeMs: Float): Float {
            if (times <= 1 || span <= 0f || timeMs <= first) return timeMs
            val since = timeMs - first
            val loops = since / span
            if (loops >= times) return if (reverse && times % 2 == 0) first else first + span
            val iteration = loops.toInt()
            val within = since - iteration * span
            return first + if (reverse && iteration % 2 == 1) span - within else within
        }

        private fun backwards(timeMs: Float): Boolean {
            if (times <= 1 || span <= 0f || timeMs <= first || !reverse) return false
            val loops = (timeMs - first) / span
            return loops < times && loops.toInt() % 2 == 1
        }

        private fun segment(t: Float): Int {
            var lo = 0
            var hi = at.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (at[mid] <= t) lo = mid else hi = mid
            }
            return lo
        }

        fun value(timeMs: Float): Float {
            if (keys.isEmpty()) return 0f
            val t = local(timeMs)
            if (t <= at.first()) return keys.first().value
            if (t >= at.last()) return keys.last().value
            val i = segment(t)
            val a = keys[i]
            val b = keys[i + 1]
            val gap = at[i + 1] - at[i]
            val f = if (gap <= 0f) 1f else b.curve.transform((t - at[i]) / gap)
            return a.value + (b.value - a.value) * f
        }

        fun velocity(timeMs: Float): Float {
            if (keys.size < 2) return 0f
            val t = local(timeMs)
            if (t <= at.first() || t >= at.last()) return 0f
            val i = segment(t)
            val a = keys[i]
            val b = keys[i + 1]
            val gap = at[i + 1] - at[i]
            if (gap <= 0f) return 0f
            val v = (b.value - a.value) * b.curve.derivative((t - at[i]) / gap) / (gap / 1000f)
            return if (backwards(timeMs)) -v else v
        }
    }

    class Builder internal constructor() {
        internal val tracks = LinkedHashMap<String, TrackBuilder>()

        fun track(name: String, keys: TrackBuilder.() -> Unit) {
            tracks[name] = TrackBuilder().apply(keys)
        }

        /**
         * [timeline]'s tracks inside this one, starting at [atMs] and played at [speed] (2 plays it in
         * half the time), each named [prefix] + its own name.
         */
        fun include(timeline: Timeline, atMs: Int = 0, speed: Float = 1f, prefix: String = "") {
            require(speed > 0f) { "A nested timeline plays forward" }
            for ((name, data) in timeline.tracks) {
                val shifted = TrackBuilder()
                for (k in data.keys) shifted.keys += Key(atMs + k.atMs / speed, k.value, k.curve)
                shifted.times = data.times
                shifted.reverse = data.reverse
                tracks[prefix + name] = shifted
            }
        }

        /** The tracks [names] all given [keys], each [everyMs] after the one before. */
        fun stagger(names: List<String>, everyMs: Int, keys: TrackBuilder.() -> Unit) {
            for ((i, name) in names.withIndex()) {
                val shifted = TrackBuilder().apply(keys)
                val offset = i * everyMs.toFloat()
                tracks[name] = TrackBuilder().also { t ->
                    t.keys += shifted.keys.map { Key(it.atMs + offset, it.value, it.curve) }
                    t.times = shifted.times
                    t.reverse = shifted.reverse
                }
            }
        }
    }

    class TrackBuilder internal constructor() {
        internal val keys = mutableListOf<Key>()
        internal var times = 1
        internal var reverse = false

        /** At [ms], the track is [value], reached along [curve] from the keyframe before. */
        fun at(ms: Int, value: Float, curve: Curve = Curves.Standard) {
            keys += Key(ms.toFloat(), value, curve)
        }

        /** The track's run of keyframes played [times] times, back and forth when [reverse]. */
        fun repeat(times: Int, reverse: Boolean = false) {
            require(times >= 1) { "A track plays at least once" }
            this.times = times
            this.reverse = reverse
        }

        internal fun build(): TrackData = TrackData(keys.sortedBy { it.atMs }, times, reverse)
    }

    companion object {
        /** [timelines] one after another: a timeline as long as all of them, each nested with [prefix]es "0.", "1."... */
        fun sequence(vararg timelines: Timeline): Timeline {
            val total = timelines.sumOf { it.lengthMs }
            return Timeline(total) {
                var at = 0
                for ((i, t) in timelines.withIndex()) {
                    include(t, at, prefix = "$i.")
                    at += t.lengthMs
                }
            }
        }

        /** [timelines] together from the start: as long as the longest, each nested with [prefix]es "0.", "1."... */
        fun parallel(vararg timelines: Timeline): Timeline =
            Timeline(timelines.maxOfOrNull { it.lengthMs } ?: 0) {
                for ((i, t) in timelines.withIndex()) include(t, prefix = "$i.")
            }
    }
}

/**
 * Plays a [Timeline] on one clock: [timeMs] runs with the frame clock (scaled by the system's
 * animation speed) at [speed], forward or, after [reverse], back. [seek] jumps anywhere and wins
 * over the frame in flight; [skip] jumps to the end. A change of speed or direction carries on from
 * the moment it is at, never jumping. [finished] once the end it is heading for is reached. Under
 * reduced motion the whole timeline plays in a short time, evenly, so a choreography becomes a quick fade.
 */
@Stable
class TimelinePlayer internal constructor(val timeline: Timeline) {
    private var playedMs by mutableLongStateOf(0L)

    /** How many timeline milliseconds pass per real millisecond (more than 1 under reduced motion). */
    internal var baseSpeed = 1f

    /** A speed of its own on top (2 is twice as fast); changing it mid-play carries on from the same moment. */
    var speed: Float = 1f
        set(value) {
            require(value > 0f) { "Speed is above zero; use reverse() to play backwards" }
            reanchor()
            field = value
        }

    /** Playing forward (true) or back. */
    var forward by mutableStateOf(true)
        private set

    // Where the play was anchored: a timeline time, and the frame it was at (plain, read per frame).
    private var anchorMs = 0.0
    private var anchorNanos = Long.MIN_VALUE
    private var lastFrame = Long.MIN_VALUE
    private var exactMs = 0.0

    /** Bumped when a finished player is sent off again, so it plays on from there. */
    internal var restarts by mutableIntStateOf(0)

    /** Where the play is, in the timeline's own milliseconds. */
    val timeMs: Float get() = playedMs.toFloat()

    val finished: Boolean get() = if (forward) playedMs >= timeline.lengthMs else playedMs <= 0L

    /** The track [name] now. */
    operator fun get(name: String): Float = timeline.value(name, timeMs)

    /** How fast the track [name] changes now, per second of real time (direction and speed included). */
    fun velocity(name: String): Float = timeline.velocity(name, timeMs) * baseSpeed * speed * if (forward) 1f else -1f

    private fun reanchor() {
        anchorMs = exactMs
        anchorNanos = lastFrame
    }

    fun seek(ms: Int) {
        val wasDone = finished
        exactMs = ms.toDouble().coerceIn(0.0, timeline.lengthMs.toDouble())
        playedMs = exactMs.toLong()
        anchorMs = exactMs
        anchorNanos = Long.MIN_VALUE
        if (wasDone && !finished) restarts++
    }

    fun skip() = seek(if (forward) timeline.lengthMs else 0)

    /** Turns round: plays back toward the start from where it is (or forward again). */
    fun reverse() {
        val wasDone = finished
        reanchor()
        forward = !forward
        if (wasDone && !finished) restarts++
    }

    /** One frame: moves the play on and says whether it has reached the end it is heading for. */
    internal fun tick(frameNanos: Long, scale: Float): Boolean {
        lastFrame = frameNanos
        if (anchorNanos == Long.MIN_VALUE) anchorNanos = frameNanos
        val real = (frameNanos - anchorNanos) / NANOS_PER_MS.toDouble() / scale
        val step = real * baseSpeed * speed
        exactMs = (if (forward) anchorMs + step else anchorMs - step).coerceIn(0.0, timeline.lengthMs.toDouble())
        playedMs = exactMs.toLong()
        return finished
    }
}

/**
 * A [TimelinePlayer] for [timeline], playing while [playing]. Under [reduced] motion it plays in
 * [reducedMs]. [onFinished] runs each time it reaches the end it is heading for.
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
    player.baseSpeed = if (reduced) timeline.lengthMs / reducedMs.toFloat().coerceAtLeast(1f) else 1f
    val done by rememberUpdatedState(onFinished)
    LaunchedEffect(player, playing, player.restarts, player.forward) {
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
