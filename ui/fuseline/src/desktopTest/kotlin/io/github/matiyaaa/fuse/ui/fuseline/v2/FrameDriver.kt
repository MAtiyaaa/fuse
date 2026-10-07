package io.github.matiyaaa.fuse.ui.fuseline.v2

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
        val onFrame: (playNanos: Long) -> Unit,
        val waiting: CancellableContinuation<Unit>,
    ) : Retimer {
        var start = Long.MIN_VALUE
        var gone = false

        /**
         * Plays again for [durationNanos] from the frame just shown (where its value is now), keeping
         * its place among the moves: the next frame is one frame into the new motion, never a pause.
         */
        override fun retime(durationNanos: Long) {
            this.durationNanos = durationNanos
            start = if (start == Long.MIN_VALUE) Long.MIN_VALUE else lastFrame
        }
    }

    private val moves = ArrayList<Move>()
    private var running = false

    /** The time of the frame last stepped. */
    private var lastFrame = Long.MIN_VALUE

    /** Steps [onFrame] on every frame until [durationNanos] has played (scaled by [scale]), then returns. */
    suspend fun run(context: CoroutineContext, durationNanos: Long, scale: Float, onStart: ((Retimer) -> Unit)?, onFrame: (Long) -> Unit) {
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
                    clock.withFrameNanos(::step)
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

    /** One frame: every move steps; the ones that arrived carry on with the frames after. */
    private fun step(frameNanos: Long) {
        lastFrame = frameNanos
        val count = moves.size
        var kept = 0
        for (i in 0 until count) {
            val m = moves[i]
            if (!m.gone) {
                if (m.start == Long.MIN_VALUE) m.start = frameNanos
                val play = ((frameNanos - m.start) / m.scale).toLong()
                m.onFrame(play.coerceAtMost(m.durationNanos))
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
internal fun interface Retimer {
    fun retime(durationNanos: Long)
}
