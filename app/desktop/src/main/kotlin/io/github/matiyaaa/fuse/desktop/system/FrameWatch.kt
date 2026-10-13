package io.github.matiyaaa.fuse.desktop.system

/**
 * How frames really went, for Fuse's log: every frame Skia draws while something moves, how long
 * the drawing took and how long it was since the frame before. A minute with motion in it ends in
 * one line ([report]): how many frames were late, the worst gap, and how long drawing took. Drawing
 * here is the processor's part (Fuse's drawing turned into Skia's commands); the graphics card's
 * part (pictures uploaded, layers drawn) and the way to the screen come after it. So frames drawn
 * fast but late say the time went there; late because drawing ran over says the processor's part is
 * the cost. Gaps longer than [IDLE_NANOS] are the interface resting, not late frames, and aren't
 * counted.
 *
 * Fed from Skia's render callback, on the thread that draws; [report] reads from anywhere.
 */
internal class FrameWatch(budgetNanos: Long = 16_666_667L) {
    @Volatile var budgetNanos: Long = budgetNanos

    private val draws = LongArray(CAPACITY)
    private var count = 0
    private var frames = 0
    private var late = 0
    private var worstGap = 0L
    private var lastFrame = Long.MIN_VALUE

    /** A frame drawn at [frameNanos] (Skia's frame time) whose drawing took [drawNanos]. */
    @Synchronized
    fun rendered(frameNanos: Long, drawNanos: Long) {
        val gap = if (lastFrame == Long.MIN_VALUE) Long.MAX_VALUE else frameNanos - lastFrame
        lastFrame = frameNanos
        // The first frame after a rest starts a movement: it has no gap of its own.
        if (gap !in 1..IDLE_NANOS) return
        frames++
        if (gap > budgetNanos * LATE_FACTOR) late++
        if (gap > worstGap) worstGap = gap
        draws[count % CAPACITY] = drawNanos.coerceAtLeast(0)
        count++
    }

    /** The line for the frames since the last report, or null when nothing moved; starts afresh. */
    @Synchronized
    fun report(): String? {
        if (frames < MIN_FRAMES) {
            reset()
            return null
        }
        val sorted = draws.copyOf(minOf(count, CAPACITY)).apply { sort() }
        fun at(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
        val p50 = at(0.5)
        val p95 = at(0.95)
        val worstDraw = sorted.last()
        val budget = budgetNanos
        val share = late * 100.0 / frames
        val verdict = when {
            share < 2.0 -> "smooth"
            p95 <= budget * 0.6 -> "late frames drawn in time: the time went to the graphics card's work or the way to the screen (refresh, compositor or gamescope)"
            else -> "late frames: drawing on the processor ran over the ${ms(budget)} a frame has"
        }
        val line = "Frames: $frames while moving, $late late (${"%.1f".format(java.util.Locale.ROOT, share)}%), worst gap ${ms(worstGap)}; " +
            "drawing p50 ${ms(p50)}, p95 ${ms(p95)}, worst ${ms(worstDraw)}; a frame has ${ms(budget)}; $verdict"
        reset()
        return line
    }

    private fun reset() {
        count = 0
        frames = 0
        late = 0
        worstGap = 0L
    }

    private fun ms(nanos: Long) = "%.1f ms".format(java.util.Locale.ROOT, nanos / 1e6)

    companion object {
        /** A gap this long is the interface at rest between movements. */
        const val IDLE_NANOS = 250_000_000L
        const val LATE_FACTOR = 1.5
        const val MIN_FRAMES = 30
        const val CAPACITY = 4_096
        const val PERIOD_MS = 60_000L
    }
}
