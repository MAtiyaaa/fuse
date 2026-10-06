package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * A value that moves: Fuseline's animated value, and the one place its motion lives. It owns its
 * position, its velocity, its target, the motion under way, how far that motion has played and who
 * is moving it ([owner]: nobody, a motion, or a gesture). Every way of moving it carries on from the
 * exact position and velocity it has, so motion never breaks continuity:
 *
 * - [animateTo] runs it to a target under any [Motion]; a new one takes over from the one under way.
 * - [retarget] gives the motion under way a new target (or a new motion) in place, without a new
 *   move, so a value following a finger, a scroll or the selection every frame stays cheap.
 * - [seek] moves the motion under way to any moment of its play, forward or back.
 * - [dragBy] and [dragTo] hand it to a gesture; [release], [fling] and [flingTo] hand it back to a
 *   motion with the gesture's own velocity; [cancelDrag] lets go where it is.
 * - [animateDecay] coasts it from a velocity, [snapTo] puts it somewhere at once, [stop] stills it.
 *
 * Reading [value] (or [floatValue]) in composition or drawing subscribes to it like any Compose
 * state. A frame writes one number that Compose watches and nothing else: the value itself is kept
 * as plain floats and put together only when read, so a value in motion makes no objects per frame.
 * Moves are made from the main thread, as Compose code is.
 */
@Stable
class FuselineValue<T>(
    initialValue: T,
    val converter: Converter<T>,
    /** How close counts as arrived for a motion without its own threshold. */
    private val threshold: Float = converter.threshold,
    val label: String = "FuselineValue",
) {
    private val dims = converter.size
    private val now = FloatArray(dims).also { converter.write(initialValue, it) }
    private val speed = FloatArray(dims)
    private val goal = FloatArray(dims).also { converter.write(initialValue, it) }
    private val scratch = FloatArray(dims)
    private val tracks = arrayOfNulls<Track>(dims)

    /** The coroutine of the move in charge, if any. */
    private var job: Job? = null

    /** The move under way: its motion, where it lands, how far it has played. */
    private class Ride(var motion: Motion) {
        var retimer: Retimer? = null
        var durationNanos = 0L
        var playNanos = 0L
    }

    private var ride: Ride? = null

    // What Compose watches: a number bumped whenever the value moves. The value is read from the
    // floats and kept until the next bump, so reading it twice in a frame builds it once.
    private val version = mutableIntStateOf(0)
    private var readAt = -1
    private var read: T = initialValue

    // The target is kept as floats too ([goal]), and put together only when read.
    private val targetVersion = mutableIntStateOf(0)
    private var targetReadAt = -1
    private var targetRead: T = initialValue

    /** While a gesture holds it, the target is wherever the value is (read from it, never built per event). */
    private var targetFollowsValue = false

    /** Where the value is now. */
    val value: T
        get() {
            val v = version.intValue
            if (v != readAt) {
                read = converter.read(now)
                readAt = v
            }
            return read
        }

    /** A one-number value as a float, without boxing it. */
    val floatValue: Float
        get() {
            version.intValue
            return now[0]
        }

    /** Where it is going (where it is, at rest; where a decay will stop). */
    val targetValue: T
        get() {
            val v = targetVersion.intValue
            if (targetFollowsValue) return value
            if (v != targetReadAt) {
                targetRead = converter.read(goal)
                targetReadAt = v
            }
            return targetRead
        }

    /** Who moves it now. */
    var owner: MotionOwner by mutableStateOf(MotionOwner.IDLE)
        private set

    /** True while a motion moves it. */
    val isRunning: Boolean get() = owner == MotionOwner.ANIMATION

    /** True while a gesture moves it. */
    val isDragging: Boolean get() = owner == MotionOwner.GESTURE

    /** How fast it is moving, in its own units per second. */
    val velocity: T get() = converter.read(speed)

    /** The motion under way, or null. */
    val motion: Motion? get() = ride?.motion

    /** How far the motion under way has played, 0 to 1 (1 at rest; 0 for a loop that never ends). */
    val progress: Float
        get() {
            version.intValue
            val r = ride ?: return 1f
            if (r.durationNanos == Long.MAX_VALUE) return 0f
            if (r.durationNanos <= 0L) return 1f
            return (r.playNanos.toDouble() / r.durationNanos).coerceIn(0.0, 1.0).toFloat()
        }

    /** How long the motion under way has played, in nanoseconds. */
    val playNanos: Long get() = ride?.playNanos ?: 0L

    /** The value as read-only state, for handing out. */
    fun asState(): State<T> = valueState

    private val valueState = object : State<T> {
        override val value: T get() = this@FuselineValue.value
    }

    /** A one-number value as [FloatState], read without boxing. */
    fun asFloatState(): FloatState = floatState

    private val floatState = object : FloatState {
        override val floatValue: Float get() = this@FuselineValue.floatValue
    }

    private fun moved() {
        version.intValue++
    }

    /**
     * The target is now [goal] (as the motion has it): for motions that go their own way (a decay,
     * keyframes, a sequence), where they end.
     */
    private fun aimed(motion: Motion) {
        if (!(motion is Spring || motion is Tween || motion is Snap) && motion.ends) {
            for (i in 0 until dims) goal[i] = tracks[i]!!.endValue
        }
        targetFollowsValue = false
        targetVersion.intValue++
    }

    /** Puts the value at [targetValue] at once, stopping any move or gesture. */
    suspend fun snapTo(targetValue: T) {
        takeOver {
            converter.write(targetValue, now)
            converter.write(targetValue, goal)
            speed.fill(0f)
            targetFollowsValue = false
            targetVersion.intValue++
            moved()
        }
    }

    /** Stops where it is, still. */
    suspend fun stop() {
        takeOver {
            speed.fill(0f)
            now.copyInto(goal)
            targetFollowsValue = false
            targetVersion.intValue++
            moved()
        }
    }

    /**
     * Moves to [targetValue] under [animationSpec], starting at [initialVelocity] (or the velocity it
     * has now, whoever was moving it). [block] runs on every frame after the value is updated. Returns
     * once there; ends with a [CancellationException] when another move or a gesture takes over or the
     * caller is cancelled, leaving the value (and its velocity) where it got to.
     */
    suspend fun animateTo(
        targetValue: T,
        animationSpec: Motion = Spring(threshold = threshold),
        initialVelocity: T? = null,
        block: (FuselineValue<T>.() -> Unit)? = null,
    ) {
        takeOver {
            converter.write(targetValue, goal)
            if (initialVelocity != null) converter.write(initialVelocity, speed)
            val r = Ride(animationSpec)
            build(r, animationSpec)
            aimed(animationSpec)
            ride = r
            owner = MotionOwner.ANIMATION
            try {
                runFrames(r.durationNanos, onStart = { r.retimer = it }) { play ->
                    step(r, play)
                    block?.invoke(this)
                }
                // Lands exactly where the motion ends, whatever the last frame's rounding.
                for (i in 0 until dims) now[i] = tracks[i]!!.endValue
                speed.fill(0f)
                r.playNanos = r.durationNanos
                moved()
                block?.invoke(this)
            } finally {
                if (ride === r) ride = null
            }
        }
    }

    /** Coasts from [initialVelocity] under [decay] to wherever that takes it ([targetValue] says where, from the start). */
    suspend fun animateDecay(initialVelocity: T, decay: Decay = Decay(), block: (FuselineValue<T>.() -> Unit)? = null) {
        animateTo(value, decay, initialVelocity, block)
    }

    /** The tracks for [motion] from where and how fast the value is now, reusing the ones it has. */
    private fun build(r: Ride, motion: Motion) {
        var longest = 0L
        for (i in 0 until dims) {
            val t = Track.reuse(tracks[i], motion, now[i], goal[i], speed[i], threshold, i)
            tracks[i] = t
            val d = t.durationNanos
            if (d > longest) longest = d
        }
        r.motion = motion
        r.durationNanos = longest
        r.playNanos = 0L
    }

    /** One frame of the move: every component's position and velocity, solved together. */
    private fun step(r: Ride, play: Long) {
        r.playNanos = play
        for (i in 0 until dims) {
            val t = tracks[i]!!
            t.sample(play)
            now[i] = t.sampledValue
            speed[i] = t.sampledVelocity
        }
        moved()
    }

    /**
     * Gives the move under way a new [targetValue] in place (and a new motion, with [animationSpec]):
     * it carries on from where it is, at the velocity it has, without a new move or any new objects.
     * This is what a value following a finger, a scroll or a selection wants every frame. The move's
     * caller still returns when it arrives (at the new target). False when nothing is moving it:
     * then start a move with [animateTo].
     */
    fun retarget(targetValue: T, animationSpec: Motion? = null): Boolean {
        val r = ride ?: return false
        val retimer = r.retimer ?: return false
        if (owner != MotionOwner.ANIMATION) return false
        converter.write(targetValue, goal)
        return rebuild(r, retimer, animationSpec)
    }

    /** [retarget] for a one-number value, without boxing the target: the path for following a finger every frame. */
    fun retargetFloat(target: Float, animationSpec: Motion? = null): Boolean {
        val r = ride ?: return false
        val retimer = r.retimer ?: return false
        if (owner != MotionOwner.ANIMATION) return false
        goal[0] = target
        return rebuild(r, retimer, animationSpec)
    }

    /** [retarget] for a two-number value (a position, a size), without boxing the target. */
    fun retargetXY(x: Float, y: Float, animationSpec: Motion? = null): Boolean {
        val r = ride ?: return false
        val retimer = r.retimer ?: return false
        if (owner != MotionOwner.ANIMATION) return false
        goal[0] = x
        goal[1] = y
        return rebuild(r, retimer, animationSpec)
    }

    private fun rebuild(r: Ride, retimer: Retimer, animationSpec: Motion?): Boolean {
        val motion = animationSpec ?: r.motion
        build(r, motion)
        aimed(motion)
        retimer.retime(r.durationNanos)
        return true
    }

    /**
     * Moves the motion under way to [playNanos] into its play (any moment, forward or back, past its
     * end ends it there) and shows that moment now. False when nothing is moving it.
     */
    fun seek(playNanos: Long): Boolean {
        val r = ride ?: return false
        val retimer = r.retimer ?: return false
        val play = playNanos.coerceIn(0L, r.durationNanos)
        retimer.seek(play)
        step(r, play)
        return true
    }

    /** [seek] to a share of the motion under way, 0 to 1. False for a loop that never ends, or nothing moving. */
    fun seekProgress(fraction: Float): Boolean {
        val d = ride?.durationNanos ?: return false
        if (d == Long.MAX_VALUE) return false
        return seek((d * fraction.coerceIn(0f, 1f).toDouble()).toLong())
    }

    // ----------------------------------------------------------------------------------------
    // Gestures

    private var tracker: DragVelocity? = null

    private fun grab(timeNanos: Long) {
        // The move under way stops where it is; the gesture holds it from there.
        job?.cancel(TakenOver())
        job = null
        ride = null
        owner = MotionOwner.GESTURE
        val t = tracker ?: DragVelocity(dims).also { tracker = it }
        t.reset()
        t.add(timeNanos, now)
        speed.fill(0f)
    }

    private fun dragged(timeNanos: Long) {
        val t = tracker!!
        t.add(timeNanos, now)
        t.velocity(timeNanos, speed)
        now.copyInto(goal)
        if (!targetFollowsValue) {
            targetFollowsValue = true
            targetVersion.intValue++
        }
        moved()
    }

    /** Moves the value by [delta] under a gesture (taking it from any move under way), at [timeNanos]. */
    fun dragBy(delta: T, timeNanos: Long = monotonicNanos()) {
        if (owner != MotionOwner.GESTURE) grab(timeNanos)
        converter.write(delta, scratch)
        for (i in 0 until dims) now[i] += scratch[i]
        dragged(timeNanos)
    }

    /** Puts the value at [position] under a gesture (taking it from any move under way), at [timeNanos]. */
    fun dragTo(position: T, timeNanos: Long = monotonicNanos()) {
        if (owner != MotionOwner.GESTURE) grab(timeNanos)
        converter.write(position, now)
        dragged(timeNanos)
    }

    /** How fast the gesture was moving the value at [timeNanos] (still, if it had paused). */
    fun releaseVelocity(timeNanos: Long = monotonicNanos()): T {
        val t = tracker ?: return converter.read(speed.also { it.fill(0f) })
        t.velocity(timeNanos, speed)
        return converter.read(speed)
    }

    /** Lets go where it is: the gesture ends without a motion, and the value rests. */
    fun cancelDrag() {
        if (owner != MotionOwner.GESTURE) return
        owner = MotionOwner.IDLE
        speed.fill(0f)
        moved()
    }

    /** Lets go and moves to [targetValue] under [animationSpec], starting at the gesture's own velocity. */
    suspend fun release(targetValue: T, animationSpec: Motion = Spring(threshold = threshold), timeNanos: Long = monotonicNanos()) {
        animateTo(targetValue, animationSpec, releaseVelocity(timeNanos))
    }

    /** Lets go and coasts at the gesture's own velocity under [decay]. */
    suspend fun fling(decay: Decay = Decay(), timeNanos: Long = monotonicNanos()) {
        animateDecay(releaseVelocity(timeNanos), decay)
    }

    /**
     * Lets go and lands on a resting place: where a coast under [decay] would stop is worked out first,
     * [choose] picks the place from that (the nearest page, item or edge), and [settle] takes the value
     * there starting at the gesture's own velocity.
     */
    suspend fun flingTo(
        choose: (projected: T) -> T,
        decay: Decay = Decay(),
        settle: Motion = Spring(threshold = threshold),
        timeNanos: Long = monotonicNanos(),
    ) {
        val v = releaseVelocity(timeNanos)
        converter.write(v, scratch)
        val projected = FloatArray(dims) { i -> projectDecay(now[i], scratch[i], decay, decay.threshold ?: threshold) }
        animateTo(choose(converter.read(projected)), settle, v)
    }

    /**
     * Runs [block] as the one move in charge, cancelling the one before it (or ending a gesture).
     * Moves run on the main thread and only between frames, so the old one has stopped writing the
     * moment it is cancelled: it waits on a frame it will never get. Nothing waits for it to unwind,
     * which keeps a value retargeted every frame as cheap as one that isn't.
     */
    private suspend inline fun takeOver(block: () -> Unit) {
        val me = currentCoroutineContext().job
        val before = job
        job = me
        if (before != null && before !== me) before.cancel(TakenOver())
        try {
            block()
        } finally {
            if (job === me) {
                job = null
                if (owner == MotionOwner.ANIMATION) owner = MotionOwner.IDLE
            }
        }
    }
}

private val origin = TimeSource.Monotonic.markNow()

/** Nanoseconds on a clock that only moves forward, for gestures that don't give their own times. */
fun monotonicNanos(): Long = origin.elapsedNow().inWholeNanoseconds

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
    runFrames(track.durationNanos) { play ->
        track.sample(play)
        block(track.sampledValue, track.sampledVelocity)
    }
    block(track.endValue, 0f)
}

/**
 * Calls [onFrame] with the time played (nanoseconds, scaled by the system's animation speed) on
 * every frame until [durationNanos] has played. Under an animation speed of zero (animations
 * turned off) nothing plays and the move ends at once. A move that never ends tells the platform
 * so, the way tests and screenshot tools expect of loops ([InfiniteAnimationPolicy]).
 */
internal suspend fun runFrames(durationNanos: Long, onStart: ((Retimer) -> Unit)? = null, onFrame: FrameStep) {
    val context = currentCoroutineContext()
    val scale = context[MotionDurationScale]?.scaleFactor ?: 1f
    if (durationNanos == 0L || scale == 0f) return
    val forever = durationNanos == Long.MAX_VALUE
    // A move that ends shares its frames with every other on the same clock ([FrameDriver]).
    val clock = context[androidx.compose.runtime.MonotonicFrameClock]
    if (!forever && clock != null) {
        FrameDriver.of(clock).run(context, durationNanos, scale, onStart, onFrame)
        return
    }
    var start = Long.MIN_VALUE
    var last = Long.MIN_VALUE
    var length = durationNanos
    var pending = -1L
    // Again from the frame just shown, as on the shared driver.
    onStart?.invoke(object : Retimer {
        override fun retime(durationNanos: Long) {
            length = durationNanos
            if (start != Long.MIN_VALUE) start = last
        }

        override fun seek(playNanos: Long) {
            if (start == Long.MIN_VALUE) pending = playNanos else start = last - (playNanos * scale).toLong()
        }
    })
    while (true) {
        val done = frame(length == Long.MAX_VALUE) { frameNanos ->
            last = frameNanos
            if (start == Long.MIN_VALUE) start = frameNanos - if (pending >= 0) (pending * scale).toLong() else 0L
            val play = ((frameNanos - start) / scale).toLong()
            onFrame.step(play.coerceAtMost(length))
            play >= length
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
