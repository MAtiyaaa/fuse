package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * One frame callback for every move under way on a frame clock. Each move used to wait for its own
 * frame: a coroutine resumed, an awaiter and a callback made, per value, every frame. Here a move
 * hands its frame work to the clock's driver and waits once, until it ends; the driver waits for
 * each frame once and steps every move in that one callback. A hundred values moving cost one
 * frame wait, not a hundred.
 *
 * Moves join from the main thread, as all of Fuseline's do. A move that joins while the driver is
 * stepping (one move starting another) begins on the next frame, as it would have waiting on its
 * own. A move taken over or cancelled leaves at once and is never stepped again.
 */
internal class FrameDriver private constructor(private val clock: MonotonicFrameClock) {

    private inner class Move(
        var durationNanos: Long,
        val scale: Float,
        val onFrame: FrameStep,
        val waiting: CancellableContinuation<Unit>,
    ) : Retimer {
        var start = Long.MIN_VALUE
        var gone = false

        /** A seek asked for before the first frame: where the play starts. */
        var pending = -1L

        /**
         * Plays again for [durationNanos] from the frame just shown (where its value is now), keeping
         * its place among the moves: the next frame is one frame into the new motion, never a pause.
         */
        override fun retime(durationNanos: Long) {
            this.durationNanos = durationNanos
            start = if (start == Long.MIN_VALUE) Long.MIN_VALUE else lastFrame
        }

        /** Puts the play at [playNanos] as of the frame just shown, so the next frame carries on from there. */
        override fun seek(playNanos: Long) {
            if (start == Long.MIN_VALUE) pending = playNanos else start = lastFrame - (playNanos * scale).toLong()
        }
    }

    private val moves = ArrayList<Move>()
    private var running = false

    /** The time of the frame last stepped. */
    private var lastFrame = Long.MIN_VALUE

    /** Steps [onFrame] on every frame until [durationNanos] has played (scaled by [scale]), then returns. */
    suspend fun run(context: CoroutineContext, durationNanos: Long, scale: Float, onStart: ((Retimer) -> Unit)?, onFrame: FrameStep) {
        suspendCancellableCoroutine { waiting ->
            val move = Move(durationNanos, scale, onFrame, waiting)
            onStart?.invoke(move)
            moves += move
            waiting.invokeOnCancellation { move.gone = true }
            if (!running) start(context)
        }
    }

    private fun start(context: CoroutineContext) {
        running = true
        // Its own job: a move that is cancelled never takes the others' frames with it.
        CoroutineScope(context.minusKey(Job) + Job()).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    clock.withFrameNanos(stepper)
                    if (moves.isEmpty()) break
                }
            } finally {
                // Normally every move has ended; if the clock itself stopped (its window closed),
                // the moves still waiting end with it rather than wait for a frame that never comes.
                running = false
                drivers.remove(clock)
                val left = moves.toList()
                moves.clear()
                for (m in left) if (!m.gone) m.waiting.cancel()
            }
        }
    }

    /** The frame callback, made once rather than on every frame. */
    private val stepper: (Long) -> Unit = ::step

    /** One frame: every move steps; the ones that arrived carry on with the frames after. */
    private fun step(frameNanos: Long) {
        FramePacing.frameAt(frameNanos)
        lastFrame = frameNanos
        val count = moves.size
        var kept = 0
        for (i in 0 until count) {
            val m = moves[i]
            if (!m.gone) {
                if (m.start == Long.MIN_VALUE) m.start = frameNanos - if (m.pending >= 0) (m.pending * m.scale).toLong() else 0L
                val play = ((frameNanos - m.start) / m.scale).toLong()
                m.onFrame.step(play.coerceAtMost(m.durationNanos))
                if (play >= m.durationNanos) {
                    m.gone = true
                    m.waiting.resume(Unit)
                }
            }
            if (!m.gone) moves[kept++] = m
        }
        // Moves that joined during this frame keep their places after the ones still running.
        for (i in count until moves.size) moves[kept++] = moves[i]
        while (moves.size > kept) moves.removeAt(moves.lastIndex)
    }

    companion object {
        private val drivers = HashMap<MonotonicFrameClock, FrameDriver>()

        /** The driver for [clock], made when the first move on it starts. */
        fun of(clock: MonotonicFrameClock): FrameDriver = drivers.getOrPut(clock) { FrameDriver(clock) }
    }
}

/**
 * A move under way that can be given a new length and started again from its next frame, in place:
 * how a spring takes a new target without a new move (see [FuselineValue.retarget]).
 */
/**
 * One frame of a move, given the time played. An interface of its own rather than a function type,
 * so the time passes as a plain number: a `(Long) -> Unit` would box it on every frame of every value.
 */
internal fun interface FrameStep {
    fun step(playNanos: Long)
}

internal interface Retimer {
    fun retime(durationNanos: Long)

    /** Moves the play to [playNanos], as of the frame just shown. */
    fun seek(playNanos: Long)
}

/**
 * What the frames themselves say about the display and the load: the interval between frames as it
 * really is (30 Hz is about 33.3 ms, 120 Hz 8.3 ms, 240 Hz 4.2 ms; never assumed to be 60 Hz), and
 * whether frames are running late. Late frames are answered by thinning out decoration only (ambient
 * loops update every other frame), never by changing any motion's time: every value is always worked
 * out from the real frame time, so physics stays exactly the same however busy the device is, and
 * interaction, focus, navigation, gestures and transitions keep every frame.
 */
object FramePacing {
    private const val HISTORY = 32

    /** Frames at least this much longer than the display's interval count as late. */
    private const val LATE_RATIO = 1.5f

    /** Late frames in a row (of the history) before decoration is thinned, and on-time ones before it comes back. */
    private const val LATE_TO_THIN = 6
    private const val ON_TIME_TO_RESTORE = 30

    private val intervals = LongArray(HISTORY)
    private var count = 0
    private var head = 0
    private var late = 0
    private var onTime = 0

    /** The display's own frame interval as measured (the shortest interval seen lately), in nanoseconds. */
    var intervalNanos: Long = 16_666_667L
        private set

    /** Frames per second, as measured. */
    val refreshRate: Float get() = (1e9 / intervalNanos).toFloat()

    /** True while frames run late: decoration is thinned (see [shouldDrawDecoration]). */
    var underLoad: Boolean = false
        private set

    private var decorationTick = 0L

    private var lastSeen = Long.MIN_VALUE

    /**
     * A frame at [frameNanos], from whoever sees it first (the shared driver, or a loop on its own):
     * each frame time counts once however many moves and loops share it.
     */
    fun frameAt(frameNanos: Long) {
        if (frameNanos == lastSeen) return
        val before = lastSeen
        lastSeen = frameNanos
        if (before != Long.MIN_VALUE && frameNanos > before) frame(frameNanos - before)
    }

    /** Records one frame [nanos] after the one before. */
    fun frame(nanos: Long) {
        if (nanos <= 0L || nanos > 1_000_000_000L) return
        intervals[head] = nanos
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++
        // The display's interval is the quickest steady frame lately: late frames only ever add time.
        var best = Long.MAX_VALUE
        for (i in 0 until count) if (intervals[i] < best) best = intervals[i]
        intervalNanos = best
        if (nanos > intervalNanos * LATE_RATIO) {
            late++
            onTime = 0
            if (late >= LATE_TO_THIN) underLoad = true
        } else {
            onTime++
            if (onTime >= ON_TIME_TO_RESTORE) {
                underLoad = false
                late = 0
            }
        }
    }

    /**
     * Whether a decorative loop should show this frame: always, unless frames are running late, when
     * it shows every other frame. Its value is still worked out from the real time when it shows.
     */
    fun shouldDrawDecoration(): Boolean {
        decorationTick++
        return !underLoad || decorationTick % 2L == 0L
    }

    /** Forgets what it has measured (tests, and a display that changed). */
    fun reset() {
        count = 0
        head = 0
        late = 0
        onTime = 0
        underLoad = false
        intervalNanos = 16_666_667L
        decorationTick = 0L
        lastSeen = Long.MIN_VALUE
    }
}
