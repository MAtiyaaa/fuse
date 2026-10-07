package io.github.matiyaaa.fuse.ui.fuseline.v1

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext

/**
 * A value that moves: Fuseline's animated value. [animateTo] runs it to a target under a [Motion],
 * frame by frame, and [snapTo] puts it there at once. A new move takes over from one under way
 * (that one ends with a [CancellationException]), starting from the value and speed it had, so a
 * spring retargeted mid-flight carries on without a jolt.
 *
 * Reading [value] in composition or drawing subscribes to it like any Compose state. Moves are
 * made from the main thread, as Compose code is.
 */
@Stable
class FuselineValue<T>(
    initialValue: T,
    val converter: Converter<T>,
    /** How close counts as arrived for a spring without its own threshold. */
    private val threshold: Float = converter.threshold,
    val label: String = "FuselineValue",
) {
    private val dims = converter.size
    private val now = FloatArray(dims).also { converter.write(initialValue, it) }
    private val speed = FloatArray(dims)
    private var owner: Job? = null

    /** Where the value is now. */
    var value: T by mutableStateOf(initialValue)
        private set

    /** Where it is going (where it is, at rest). */
    var targetValue: T by mutableStateOf(initialValue)
        private set

    /** True while a move is under way. */
    var isRunning: Boolean by mutableStateOf(false)
        private set

    /** How fast it is moving, in its own units per second. */
    val velocity: T get() = converter.read(speed.copyOf())

    /** The value as read-only state, for handing out. */
    fun asState(): State<T> = valueState

    private val valueState = object : State<T> {
        override val value: T get() = this@FuselineValue.value
    }

    /** Puts the value at [targetValue] at once, stopping any move. */
    suspend fun snapTo(targetValue: T) {
        takeOver {
            converter.write(targetValue, now)
            speed.fill(0f)
            this.targetValue = targetValue
            value = targetValue
        }
    }

    /** Stops where it is. */
    suspend fun stop() {
        takeOver { speed.fill(0f); targetValue = value }
    }

    /**
     * Moves to [targetValue] under [animationSpec], starting at [initialVelocity] (or the speed it
     * has now). [block] runs on every frame after the value is updated. Returns once there; ends with
     * a [CancellationException] when another move takes over or the caller is cancelled, leaving the
     * value (and its speed) where it got to.
     */
    suspend fun animateTo(
        targetValue: T,
        animationSpec: Motion = Spring(threshold = threshold),
        initialVelocity: T? = null,
        block: (FuselineValue<T>.() -> Unit)? = null,
    ) {
        takeOver {
            val goal = FloatArray(dims).also { converter.write(targetValue, it) }
            val startSpeed = initialVelocity?.let { v -> FloatArray(dims).also { converter.write(v, it) } } ?: speed.copyOf()
            val tracks = Array(dims) { i -> Track.of(animationSpec, now[i], goal[i], startSpeed[i], threshold) }
            this.targetValue = targetValue
            isRunning = true
            try {
                runFrames(tracks.maxOf { it.durationNanos }) { play ->
                    for (i in 0 until dims) {
                        now[i] = tracks[i].valueAt(play)
                        speed[i] = tracks[i].velocityAt(play)
                    }
                    // Converters only read the array, so no copy is made per frame.
                    value = converter.read(now)
                    block?.invoke(this)
                }
                // Lands exactly, whatever the last frame's rounding.
                goal.copyInto(now)
                speed.fill(0f)
                value = targetValue
                block?.invoke(this)
            } finally {
                // A move that was taken over leaves the running flag to the one in charge now.
                if (owner === currentCoroutineContext().job) isRunning = false
            }
        }
    }

    /**
     * Runs [block] as the one move in charge, cancelling the one before it. Moves run on the main
     * thread and only between frames, so the old one has stopped writing the moment it is
     * cancelled: it waits on a frame it will never get. Nothing waits for it to unwind, which keeps
     * a value retargeted every frame (following a finger or a scroll) as cheap as one that isn't.
     */
    private suspend inline fun takeOver(block: () -> Unit) {
        val me = currentCoroutineContext().job
        val before = owner
        owner = me
        if (before != null && before !== me) before.cancel(TakenOver())
        try {
            block()
        } finally {
            if (owner === me) owner = null
        }
    }
}

/** A [FuselineValue] of a float. */
fun FuselineValue(initialValue: Float, threshold: Float = FloatConverter.threshold): FuselineValue<Float> =
    FuselineValue(initialValue, FloatConverter, threshold)

/** A [FuselineValue] of a colour. */
fun FuselineValue(initialValue: Color): FuselineValue<Color> = FuselineValue(initialValue, ColorConverter)

/** A [FuselineValue] of a length. */
fun FuselineValue(initialValue: Dp): FuselineValue<Dp> = FuselineValue(initialValue, DpConverter)

/** A [FuselineValue] of a position. */
fun FuselineValue(initialValue: Offset): FuselineValue<Offset> = FuselineValue(initialValue, OffsetConverter)

/** A [FuselineValue] of a whole-pixel position. */
fun FuselineValue(initialValue: IntOffset): FuselineValue<IntOffset> = FuselineValue(initialValue, IntOffsetConverter)

/** A [FuselineValue] of a size. */
fun FuselineValue(initialValue: Size): FuselineValue<Size> = FuselineValue(initialValue, SizeConverter)

/** A [FuselineValue] of a whole-pixel size. */
fun FuselineValue(initialValue: IntSize): FuselineValue<IntSize> = FuselineValue(initialValue, IntSizeConverter)

/** A [FuselineValue] of a rectangle. */
fun FuselineValue(initialValue: Rect): FuselineValue<Rect> = FuselineValue(initialValue, RectConverter)

/**
 * Runs a float from [initialValue] to [targetValue] under [animationSpec], calling [block] with the
 * value and its speed on every frame, without keeping any state.
 */
suspend fun animate(
    initialValue: Float,
    targetValue: Float,
    initialVelocity: Float = 0f,
    animationSpec: Motion = Spring(),
    block: (value: Float, velocity: Float) -> Unit,
) {
    val track = Track.of(animationSpec, initialValue, targetValue, initialVelocity, FloatConverter.threshold)
    runFrames(track.durationNanos) { play -> block(track.valueAt(play), track.velocityAt(play)) }
    block(targetValue, 0f)
}

/**
 * Calls [onFrame] with the time played (nanoseconds, scaled by the system's animation speed) on
 * every frame until [durationNanos] has played. Under an animation speed of zero (animations
 * turned off) nothing plays and the move ends at once. A move that never ends tells the platform
 * so, the way tests and screenshot tools expect of loops ([InfiniteAnimationPolicy]).
 */
internal suspend fun runFrames(durationNanos: Long, onFrame: (playNanos: Long) -> Unit) {
    val context = currentCoroutineContext()
    val scale = context[MotionDurationScale]?.scaleFactor ?: 1f
    if (durationNanos == 0L || scale == 0f) return
    val forever = durationNanos == Long.MAX_VALUE
    // A move that ends shares its frames with every other on the same clock ([FrameDriver]).
    val clock = context[androidx.compose.runtime.MonotonicFrameClock]
    if (!forever && clock != null) {
        FrameDriver.of(clock).run(context, durationNanos, scale, onFrame)
        return
    }
    var start = Long.MIN_VALUE
    while (true) {
        val done = frame(forever) { frameNanos ->
            if (start == Long.MIN_VALUE) start = frameNanos
            val play = ((frameNanos - start) / scale).toLong()
            onFrame(play.coerceAtMost(durationNanos))
            play >= durationNanos
        }
        if (done) return
    }
}

/** One frame; for a move that never ends, through the platform's policy for endless animation. */
internal suspend fun <R> frame(infinite: Boolean, onFrame: (Long) -> R): R {
    val policy = if (infinite) coroutineContext[InfiniteAnimationPolicy] else null
    return if (policy != null) policy.onInfiniteOperation { withFrameNanos(onFrame) } else withFrameNanos(onFrame)
}

/** The frame time in milliseconds, through the policy for endless animation: for loops that keep their own time. */
suspend fun <R> withInfiniteFrameMillis(onFrame: (Long) -> R): R = frame(true) { onFrame(it / NANOS_PER_MS) }

/**
 * How a move ends when another takes over. It carries no stack trace and is never copied, so
 * ending a move costs next to nothing even with coroutine debugging on (as tests run), which
 * keeps a value retargeted every frame cheap.
 */
internal class TakenOver : CancellationException("Another move took over"), kotlinx.coroutines.CopyableThrowable<TakenOver> {
    override fun createCopy(): TakenOver? = null
    override fun fillInStackTrace(): Throwable = this
}
