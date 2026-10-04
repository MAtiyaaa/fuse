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

    private class Move(
        val durationNanos: Long,
        val scale: Float,
        val onFrame: (playNanos: Long) -> Unit,
        val waiting: CancellableContinuation<Unit>,
    ) {
        var start = Long.MIN_VALUE
        var gone = false
    }

    private val moves = ArrayList<Move>()
    private var running = false

    /** Steps [onFrame] on every frame until [durationNanos] has played (scaled by [scale]), then returns. */
    suspend fun run(context: CoroutineContext, durationNanos: Long, scale: Float, onFrame: (Long) -> Unit) {
        suspendCancellableCoroutine { waiting ->
            val move = Move(durationNanos, scale, onFrame, waiting)
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
