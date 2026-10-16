package io.github.matiyaaa.fuse.ui.designsystem.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Art that failed is asked for again a few times, later each time, then left as it is. */
class ArtRetryTest {
    @Test
    fun aFailedPictureIsAskedForAgainLaterEachTimeThenLeft() {
        val waits = generateSequence(0) { it + 1 }.map(ArtRetry::delayAfter).takeWhile { it != null }.filterNotNull().toList()
        assertEquals(ArtRetry.DELAYS_MS.size, waits.size)
        assertTrue(waits.zipWithNext().all { (a, b) -> b > a }, "each wait is longer than the one before")
        assertNull(ArtRetry.delayAfter(waits.size))
    }
}
