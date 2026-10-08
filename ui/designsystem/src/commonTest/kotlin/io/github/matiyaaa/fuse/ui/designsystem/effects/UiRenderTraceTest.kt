package io.github.matiyaaa.fuse.ui.designsystem.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiRenderTraceTest {
    @Test fun emptyMeansUnknownNotZero() {
        assertNull(TraceSamples().percentile(.95))
    }
    @Test fun quantilesIncludeOutliersAndEvictOldSamples() {
        val samples = TraceSamples(100)
        repeat(99) { samples.add(10) }
        samples.add(1000)
        assertEquals(10L, samples.percentile(.95))
        assertEquals(1000L, samples.percentile(1.0))
        repeat(100) { samples.add(20) }
        assertEquals(200L, samples.total)
        assertEquals(20L, samples.percentile(.99))
        samples.clear()
        assertNull(samples.percentile(.50))
    }
}
