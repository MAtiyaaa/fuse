package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.time.TimeSource

/**
 * Opt-in, bounded UI-thread instrumentation. Draw time is command recording/replay on the CPU,
 * never GPU execution or compositor presentation. Input latency ends at the next root draw and
 * is only a diagnostic lower bound for visible response. Nothing is persisted or identifies a game.
 */
object UiRenderTrace {
    @Volatile var enabled = false
    var renderer: String = "Unknown"
    var expectedFrameNanos: Long = 16_666_667L
    private val epoch = TimeSource.Monotonic.markNow()
    private val draws = TraceSamples()
    private val measures = TraceSamples()
    private val frames = TraceSamples()
    private val inputDraw = TraceSamples()
    private val recordings = TraceSamples()
    private val rasters = TraceSamples()
    private var lastFrame = Long.MIN_VALUE
    private var pendingInput = Long.MIN_VALUE
    private var compositions = 0L
    private var placements = 0L
    private var rasterBytes = 0L
    private var lateFrames = 0L

    fun now(): Long = epoch.elapsedNow().inWholeNanoseconds
    fun composition() { if (enabled) compositions++ }
    fun placement() { if (enabled) placements++ }
    fun input(at: Long = now()) {
        if (enabled && pendingInput == Long.MIN_VALUE) pendingInput = at
    }
    fun frame(at: Long) {
        if (!enabled) return
        if (lastFrame != Long.MIN_VALUE && at > lastFrame) {
            val interval = at - lastFrame
            frames.add(interval)
            if (interval > expectedFrameNanos * 1.5) lateFrames++
        }
        lastFrame = at
    }
    fun drawn(start: Long) {
        if (!enabled) return
        draws.add(now() - start)
        if (pendingInput != Long.MIN_VALUE) {
            inputDraw.add((now() - pendingInput).coerceAtLeast(0))
            pendingInput = Long.MIN_VALUE
        }
    }
    fun measured(start: Long) { if (enabled) measures.add(now() - start) }
    fun recording(block: () -> Unit) {
        if (!enabled) { block(); return }
        val start = now()
        block()
        recordings.add(now() - start)
    }
    fun rasterizing(bytes: Long, block: () -> Unit) {
        if (!enabled) { block(); return }
        val start = now()
        block()
        rasters.add(now() - start)
        rasterBytes += bytes
    }

    /** Read/reset on the UI thread; bounded samples never allocate in the frame/input hot path. */
    fun report(): String = buildString {
        appendLine("Fuse UI trace, renderer=$renderer")
        appendLine("Root compositions=$compositions placements=$placements")
        appendLine("Frame-clock intervals ${frames.summary()}, late=$lateFrames, expected=${expectedFrameNanos / 1e6} ms")
        appendLine("Root draw CPU ${draws.summary()}")
        appendLine("Root measure CPU ${measures.summary()}")
        appendLine("Input to next root draw ${inputDraw.summary()}")
        appendLine("Static command recordings ${recordings.summary()}")
        appendLine("Software raster generations ${rasters.summary()}, bytes repainted=$rasterBytes")
        appendLine("Last 1024 samples per series. Root compositions count this root only.")
        appendLine("GPU submission, texture uploads, compositor presentation, full allocation and physical input latency are not measured.")
        appendLine("Tracing runs a frame-clock observer; compare identical instrumented runs.")
    }

    fun reset() {
        draws.clear(); measures.clear(); frames.clear(); inputDraw.clear(); recordings.clear(); rasters.clear()
        lastFrame = Long.MIN_VALUE; pendingInput = Long.MIN_VALUE
        compositions = 0; placements = 0; rasterBytes = 0; lateFrames = 0
    }
}

/** The root's own work only; children retained independently can redraw without root composition. */
fun Modifier.traceUiRoot(): Modifier = if (!UiRenderTrace.enabled) this else this
    .layout { measurable, constraints ->
        val start = UiRenderTrace.now()
        val child = measurable.measure(constraints)
        UiRenderTrace.measured(start)
        layout(child.width, child.height) {
            UiRenderTrace.placement()
            child.place(0, 0)
        }
    }
    .drawWithContent {
        val start = UiRenderTrace.now()
        drawContent()
        UiRenderTrace.drawn(start)
    }

/** Nearest-rank quantiles over bounded recent samples, with a separate lifetime sample count. */
internal class TraceSamples(private val capacity: Int = 1024) {
    private val samples = LongArray(capacity)
    private var head = 0
    var total = 0L
        private set
    fun add(nanos: Long) {
        if (nanos < 0) return
        samples[head] = nanos
        head = (head + 1) % capacity
        total++
    }
    fun percentile(fraction: Double): Long? {
        val size = minOf(total, capacity.toLong()).toInt()
        if (size == 0) return null
        val sorted = samples.copyOf(size).apply { sort() }
        val index = (ceil(size * fraction.coerceIn(0.0, 1.0)).toInt() - 1).coerceIn(0, size - 1)
        return sorted[index]
    }
    fun summary(): String = if (total == 0L) "not sampled" else
        "n=$total, p50=${percentile(.50)!! / 1e6} ms p95=${percentile(.95)!! / 1e6} ms p99=${percentile(.99)!! / 1e6} ms"
    fun clear() { head = 0; total = 0 }
}
