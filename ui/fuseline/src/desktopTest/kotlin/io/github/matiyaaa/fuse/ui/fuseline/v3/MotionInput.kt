package io.github.matiyaaa.fuse.ui.fuseline.v3

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.launch

// Every kind of input moves a value through the same few doors, so a finger, the mouse, a trackpad,
// a stick, the D-pad, the keyboard and the scroll wheel all hand a value to and from motion the same
// way: where it is and how fast it is going always carry over.

/**
 * A pointer drag (a finger, the mouse, a pen, a trackpad held down) moving [value] along
 * [orientation] in pixels. The gesture takes the value from any motion under way where it is; when
 * it lets go, [onRelease] gets the value with its release velocity known ([FuselineValue.release],
 * [FuselineValue.fling] or [FuselineValue.flingTo] carry it on); a cancelled pointer lets go where it is.
 */
fun Modifier.dragMotion(
    value: FuselineValue<Float>,
    orientation: Orientation,
    enabled: Boolean = true,
    onRelease: suspend FuselineValue<Float>.(timeNanos: Long) -> Unit = { t -> fling(Decay(), t) },
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val onUp by rememberUpdatedState(onRelease)
    if (!enabled) return@composed this
    pointerInput(value, orientation) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var time = down.uptimeMillis * NANOS_PER_MS
            value.dragBy(0f, time)
            var handedOn = false
            try {
                var pointer = down.id
                var lifted = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer } ?: break
                    time = change.uptimeMillis * NANOS_PER_MS
                    if (!change.pressed) {
                        // Another finger still down carries the drag on; the last one up ends it. A
                        // release that arrives already used (the system took the pointer away) is a cancel.
                        val other = event.changes.firstOrNull { it.pressed }
                        if (other != null) { pointer = other.id; continue }
                        lifted = !change.isConsumed
                        break
                    }
                    val d = change.positionChange()
                    if (d != androidx.compose.ui.geometry.Offset.Zero) {
                        value.dragBy(if (orientation == Orientation.Horizontal) d.x else d.y, time)
                        change.consume()
                    }
                }
                if (lifted) {
                    val at = time
                    handedOn = true
                    scope.launch { onUp(value, at) }
                }
            } finally {
                // Cancelled (the pointer was taken away, or the gesture ended without a lift): let go where it is.
                if (!handedOn && value.isDragging) value.cancelDrag()
            }
        }
    }
}

/**
 * One step of a D-pad, a key or a scroll-wheel notch: the target moves by [by] from where the value
 * is going (not where it is), so quick presses add up instead of each starting from a value still
 * on its way, and the motion under way takes the new target in place, keeping its speed. Returns
 * once the value arrives (at once when it was already moving: the move under way carries it there).
 */
suspend fun FuselineValue<Float>.nudge(by: Float, motion: Motion = Spring(1f, 600f)) {
    val base = if (isRunning) targetValue else value
    val target = base + by
    if (!retargetFloat(target, motion)) animateTo(target, motion)
}

/**
 * An analog stick (or a held key) driving a value: each frame, [push] moves it by deflection ×
 * [unitsPerSecond] for the time since the last push, as a gesture, so the stick's motion has a
 * velocity like a finger's. Letting the stick go hands the value back to motion at that velocity
 * ([letGo]); pushing again takes it back, wherever it is.
 */
class StickDrive(private val value: FuselineValue<Float>, private val unitsPerSecond: Float) {
    private var last = Long.MIN_VALUE

    /** The stick at [deflection] (−1 to 1) at [timeNanos]. A deflection of zero holds the value still. */
    fun push(deflection: Float, timeNanos: Long) {
        val d = deflection.coerceIn(-1f, 1f)
        if (last == Long.MIN_VALUE || !value.isDragging) {
            last = timeNanos
            value.dragBy(0f, timeNanos)
            return
        }
        val dt = (timeNanos - last).coerceAtLeast(0L) / NANOS_PER_SECOND
        last = timeNanos
        value.dragBy((d * unitsPerSecond * dt).toFloat(), timeNanos)
    }

    /** The stick let go at [timeNanos]: the value carries on at the stick's speed into [settle] (a fling by default). */
    suspend fun letGo(timeNanos: Long, settle: suspend FuselineValue<Float>.(Long) -> Unit = { t -> fling(Decay(), t) }) {
        last = Long.MIN_VALUE
        value.settle(timeNanos)
    }
}

/**
 * A trackpad's two-finger scroll (or any stream of small deltas): each delta moves the value as a
 * gesture, and when the stream goes quiet for [quietNanos] the value carries on at the stream's own
 * velocity as of its last event ([settle], a fling by default): lifting the fingers is only heard as
 * silence, so the speed they had when they lifted is the one carried on. A stream that slowed to a
 * stop carries on hardly at all. Call [scroll] for each event, and [settle] once [quiet].
 */
class ScrollDrive(private val value: FuselineValue<Float>, private val quietNanos: Long = 50_000_000L) {
    private var lastEvent = Long.MIN_VALUE

    fun scroll(delta: Float, timeNanos: Long) {
        lastEvent = timeNanos
        value.dragBy(delta, timeNanos)
    }

    /** True once the stream has gone quiet (and [settle] should run). */
    fun quiet(nowNanos: Long): Boolean = lastEvent != Long.MIN_VALUE && value.isDragging && nowNanos - lastEvent >= quietNanos

    suspend fun settle(settle: suspend FuselineValue<Float>.(Long) -> Unit = { t -> fling(Decay(), t) }) {
        val at = lastEvent
        lastEvent = Long.MIN_VALUE
        value.settle(at)
    }
}
