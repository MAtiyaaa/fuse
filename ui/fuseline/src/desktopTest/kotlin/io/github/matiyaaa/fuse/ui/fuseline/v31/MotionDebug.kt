package io.github.matiyaaa.fuse.ui.fuseline.v31

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A record of what motion did, in order: every start, retarget, seek, gesture taking a value,
 * release, decay, chosen destination, handoff, chased target, transition, reversal and settling,
 * with the value and velocity at that moment. Off by default; while off, each place that could
 * record costs one check of [enabled] and nothing else.
 */
object MotionTrace {
    enum class Kind {
        START, RETARGET, SEEK, SNAP, STOP, GESTURE_TAKEOVER, RELEASE, DECAY, DESTINATION,
        HANDOFF, MOVING_TARGET, TRANSITION, REVERSAL, SETTLE,
    }

    class Event internal constructor(
        val kind: Kind,
        val label: String,
        val timeNanos: Long,
        val value: Float,
        val velocity: Float,
        val detail: String?,
    ) {
        override fun toString(): String =
            "$kind $label at ${fmt(value, 3)} moving ${fmt(velocity, 1)}" + (detail?.let { " ($it)" } ?: "")
    }

    /** Whether motion is recorded. */
    var enabled = false

    private const val CAPACITY = 1024
    private val ring = arrayOfNulls<Event>(CAPACITY)
    private var head = 0
    private var count = 0

    internal fun record(kind: Kind, label: String, value: Float, velocity: Float, detail: String? = null) {
        ring[head] = Event(kind, label, monotonicNanos(), value, velocity, detail)
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /** What was recorded, oldest first (the newest [CAPACITY] events). */
    fun events(): List<Event> = List(count) { i -> ring[(head - count + i + CAPACITY) % CAPACITY]!! }

    fun clear() {
        ring.fill(null)
        head = 0
        count = 0
    }
}

/**
 * Every value in motion, on demand: its motion and tuning, value, target, velocity, progress, time
 * played and left, who moves it, and a short history of its value to graph; and what a frame of the
 * shared driver costs. Off by default; while off, starting a move costs one check of [enabled].
 */
object MotionInspector {
    /** Whether values in motion are watched. */
    var enabled = false

    internal const val HISTORY = 120

    internal class Watched(val value: FuselineValue<*>) {
        val history = FloatArray(HISTORY)
        var samples = 0
    }

    private val watched = ArrayList<Watched>()

    /** The last frame's cost, in nanoseconds, of stepping every move on the shared driver. */
    var frameCostNanos: Long = 0L
        internal set

    /** Bumped every sampled frame, for a panel to redraw by. */
    internal val frames = mutableIntStateOf(0)

    internal fun watch(v: FuselineValue<*>) {
        if (watched.none { it.value === v }) watched += Watched(v)
    }

    internal fun sample() {
        watched.removeAll { !it.value.isRunning && !it.value.isDragging }
        for (w in watched) {
            w.history[w.samples % HISTORY] = w.value.component(0)
            w.samples++
        }
        frames.intValue++
    }

    /** How many values are being watched now. */
    val count: Int get() = watched.size

    /** One value's state, as the inspector shows it. */
    class Info(
        val label: String,
        val owner: MotionOwner,
        val motion: String,
        val value: String,
        val target: String,
        val velocity: String,
        val progress: Float,
        val playedMs: Float,
        val leftMs: Float,
        val running: Boolean,
        val history: FloatArray,
    )

    /** Every watched value, now. */
    fun snapshot(): List<Info> = watched.map { w -> info(w) }

    private fun info(w: Watched): Info {
        val v = w.value
        val n = minOf(w.samples, HISTORY)
        val history = FloatArray(n) { i -> w.history[(w.samples - n + i) % HISTORY] }
        return Info(
            label = v.label,
            owner = v.owner,
            motion = v.motion?.let(::describe) ?: if (v.isDragging) "Gesture" else "At rest",
            value = components(v) { v.component(it) },
            target = components(v) { v.targetComponent(it) },
            velocity = components(v) { i -> v.velocityComponent(i) },
            progress = v.progress,
            playedMs = v.playNanos / 1e6f,
            leftMs = v.remainingNanos / 1e6f,
            running = v.isRunning,
            history = history,
        )
    }

    private fun components(v: FuselineValue<*>, read: (Int) -> Float): String {
        val n = v.converter.size
        if (n == 1) return fmt(read(0), 2)
        return (0 until n).joinToString(", ", "(", ")") { fmt(read(it), 2) }
    }

    /** A motion as the inspector writes it: kind and tuning. */
    fun describe(motion: Motion): String = when (motion) {
        is Spring -> "Spring ζ=${fmt(motion.dampingRatio, 2)} k=${motion.stiffness.roundToInt()}"
        is Tween -> "Tween ${motion.durationMs}ms ${curveName(motion.curve)}" + (if (motion.delayMs > 0) " +${motion.delayMs}ms" else "")
        is Decay -> "Decay friction ${fmt(motion.friction, 1)}"
        is Snap -> "Snap" + (if (motion.delayMs > 0) " +${motion.delayMs}ms" else "")
        is Keyframes -> "Keyframes ${motion.durationMs}ms × ${motion.keys.size}"
        is Repeating -> "Repeat ${if (motion.infinite) "∞" else motion.iterations} ${motion.mode} of ${describe(motion.motion)}"
        is Delayed -> "${motion.delayMs}ms then ${describe(motion.motion)}"
        is Sequence -> motion.legs.joinToString(" → ") { describe(it) }
        is Parallel -> motion.motions.joinToString(" ∥ ") { describe(it) }
    }

    private fun curveName(c: Curve): String = when (c) {
        Curves.Standard -> "Standard"
        Curves.Enter -> "Enter"
        Curves.Exit -> "Exit"
        Curves.Fade -> "Fade"
        Curves.Sweep -> "Sweep"
        Curves.Linear -> "Linear"
        is CubicCurve -> "Cubic(${c.x1}, ${c.y1}, ${c.x2}, ${c.y2})"
        else -> "Custom"
    }

    fun clear() {
        watched.clear()
        frameCostNanos = 0L
    }
}

/** [v] with [decimals] decimals, rounded, without platform formatting (common code). */
internal fun fmt(v: Float, decimals: Int): String {
    var scale = 1
    repeat(decimals) { scale *= 10 }
    val r = (abs(v.toDouble()) * scale).roundToLong()
    val whole = r / scale
    val frac = (r % scale).toString().padStart(decimals, '0')
    return (if (v < 0f && r != 0L) "-" else "") + whole + (if (decimals > 0) ".$frac" else "")
}

/**
 * The inspector on screen: every value in motion with its motion, value, target, velocity, progress
 * and a graph of its recent values, and what a frame costs. Shows nothing while [MotionInspector] is off.
 */
@Composable
fun MotionInspectorPanel(modifier: Modifier = Modifier) {
    if (!MotionInspector.enabled) return
    // Redrawn as frames are sampled.
    val frame by MotionInspector.frames
    val infos = MotionInspector.snapshot()
    val ink = Color(0xFFE8ECF4)
    val dim = Color(0xFF9AA3B5)
    val small = TextStyle(color = dim, fontSize = 11.sp)
    Column(
        modifier
            .background(Color(0xE60C1018), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        BasicText(
            "Fuseline · ${infos.size} moving · frame ${(MotionInspector.frameCostNanos / 1000f).roundToInt()} µs · ${FramePacing.refreshRate.roundToInt()} Hz" +
                if (FramePacing.underLoad) " · under load" else "",
            style = TextStyle(color = ink, fontSize = 12.sp),
        )
        for (i in infos) {
            Spacer(Modifier.height(8.dp))
            BasicText("${i.label} · ${i.owner.name.lowercase()} · ${i.motion}", style = TextStyle(color = ink, fontSize = 12.sp))
            BasicText("value ${i.value} → ${i.target} · velocity ${i.velocity}", style = small)
            BasicText("progress ${(i.progress * 100).roundToInt()}% · ${i.playedMs.roundToInt()} ms played · ${i.leftMs.roundToInt()} ms left", style = small)
            val history = i.history
            Row {
                Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                    if (history.size < 2) return@Canvas
                    val lo = history.min()
                    val hi = history.max()
                    val span = (hi - lo).takeIf { it > 0f } ?: 1f
                    val step = size.width / (MotionInspector.HISTORY - 1)
                    for (k in 1 until history.size) {
                        val a = Offset((k - 1) * step, size.height * (1f - (history[k - 1] - lo) / span))
                        val b = Offset(k * step, size.height * (1f - (history[k] - lo) / span))
                        drawLine(Color(0xFF5B8CFF), a, b, strokeWidth = 2f)
                    }
                }
                Spacer(Modifier.width(0.dp))
            }
        }
        // Read so the panel follows the frames.
        if (frame < 0) BasicText("")
    }
}
