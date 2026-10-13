package io.github.matiyaaa.fuse.desktop.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minute's frames in one line: smooth, late while drawing fast, or late because drawing ran over. */
class FrameWatchTest {
    private val frame = 16_666_667L

    private fun FrameWatch.move(frames: Int, every: Long = frame, draw: Long = 3_000_000L, start: Long = 1_000_000_000L): Long {
        var t = start
        repeat(frames) {
            rendered(t, draw)
            t += every
        }
        return t
    }

    @Test
    fun nothingMovingSaysNothing() {
        val watch = FrameWatch(frame)
        watch.move(10)
        assertNull(watch.report())
    }

    @Test
    fun evenFramesAreSmooth() {
        val watch = FrameWatch(frame)
        watch.move(120)
        val line = assertNotNull(watch.report())
        assertTrue("0 late" in line && line.endsWith("smooth"), line)
        assertNull(watch.report(), "a report starts afresh")
    }

    @Test
    fun restsBetweenMovementsArentLateFrames() {
        val watch = FrameWatch(frame)
        val end = watch.move(60)
        watch.move(60, start = end + 2_000_000_000L)
        val line = assertNotNull(watch.report())
        assertTrue(line.startsWith("Frames: 118 while moving, 0 late"), line)
    }

    @Test
    fun lateFramesDrawnQuicklyPointPastTheProcessor() {
        val watch = FrameWatch(frame)
        watch.move(100, every = 33_000_000L, draw = 2_000_000L)
        val line = assertNotNull(watch.report())
        assertTrue("graphics card's work" in line, line)
    }

    @Test
    fun lateFramesThatDrewTooLongBlameTheDrawing() {
        val watch = FrameWatch(frame)
        watch.move(100, every = 33_000_000L, draw = 25_000_000L)
        val line = assertNotNull(watch.report())
        assertTrue("drawing on the processor ran over" in line, line)
        assertEquals(true, "worst gap 33.0 ms" in line, line)
    }
}
