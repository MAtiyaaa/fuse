package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.ui.shell.store.JobActivity
import io.github.matiyaaa.fuse.ui.shell.store.LibraryProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Performance Observatory's sums: frames, missed refreshes, and the report it gives. */
class ObservatoryTest {
    @Test
    fun framesAreSummedAsTheyCame() {
        // 98 frames on time at 60 Hz, one a refresh late, one two refreshes late.
        val intervals = FloatArray(98) { 16.6f } + floatArrayOf(33.3f, 50f)
        val f = FrameSample.of(intervals, displayMs = 16.6f)
        assertEquals(100, f.count)
        assertEquals(2, f.late)
        assertEquals(16.6f, f.percentile(50))
        assertEquals(50f, f.worst)
        // 100 frames over 1.71 seconds.
        assertTrue(f.fps in 58f..59f, "${f.fps}")
    }

    @Test
    fun anUnknownDisplayRateFallsBackToTheMedianFrame() {
        val f = FrameSample.of(FloatArray(10) { 8.3f } + floatArrayOf(20f), displayMs = 0f)
        assertEquals(8.3f, f.displayMs)
        assertEquals(1, f.late)
    }

    @Test
    fun theReportHoldsEveryNumberAndNoNames() {
        val m = Measured(
            FrameSample.of(FloatArray(60) { 16.6f }, 16.6f),
            LibraryProbe(games = 10_000, countsMs = 8.0, indexMs = 75.0, searchMs = 6.5),
            listOf(JobActivity("Finding series", runs = 1, running = 0, held = 2, lastMs = 195, longestMs = 195, totalMs = 195)),
            PictureCaches(10L shl 20, 512L shl 20, 200L shl 20, 768L shl 20),
            listOf(Triple("cpu-temp", "CPU temperature", "48 °C")),
            120L to 1536L,
        )
        val text = observatoryReport(m, "0.4.5")
        for (part in listOf("Fuse 0.4.5", "60 in 1.0 s", "missed refreshes 0", "10000 games", "a search 6.5 ms", "memory 10 MB of 512 MB", "Finding series: 1 runs", "held 2", "CPU temperature 48 °C", "120 of 1536 MB")) {
            assertTrue(part in text, "\"$part\" in:\n$text")
        }
    }
}
