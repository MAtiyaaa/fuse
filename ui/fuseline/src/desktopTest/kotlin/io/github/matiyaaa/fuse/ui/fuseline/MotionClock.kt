package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The deterministic clock Fuseline's tests run on: frames are sent by hand at exact times, and every
 * move runs inline as its frame arrives. Nothing sleeps, nothing depends on the machine: the same
 * test gives the same numbers every time, on every platform.
 */
internal class MotionClock(startNanos: Long = 1_000_000_000L) {
    val frames = BroadcastFrameClock()
    val scope = CoroutineScope(Dispatchers.Unconfined + frames + Job())

    /** The time of the last frame sent. */
    var now: Long = startNanos
        private set

    /** One frame at [atNanos] (it must not go back). */
    fun frameAt(atNanos: Long) {
        require(atNanos >= now) { "Frames only go forward" }
        now = atNanos
        frames.sendFrame(atNanos)
    }

    /** One frame [nanos] after the last. */
    fun advance(nanos: Long) = frameAt(now + nanos)

    /** [count] frames at [hz]. */
    fun run(count: Int, hz: Int = 60) = repeat(count) { advance(1_000_000_000L / hz) }

    /** Frames at [hz] until [nanos] have passed, landing exactly on the end. */
    fun runFor(nanos: Long, hz: Int = 60) {
        val end = now + nanos
        val step = 1_000_000_000L / hz
        while (now + step < end) advance(step)
        frameAt(end)
    }

    /** True while any move waits for a frame (false once everything has settled: no work at all). */
    val busy: Boolean get() = frames.hasAwaiters

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    fun close() = scope.cancel()
}
