package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReorderMathTest {
    // Three 100 px cards in a row with 20 px gaps.
    private val slots = mapOf<Any, Rect>(
        "a" to Rect(0f, 0f, 100f, 100f),
        "b" to Rect(120f, 0f, 220f, 100f),
        "c" to Rect(240f, 0f, 340f, 100f),
    )

    @Test
    fun aHitIsTheCardUnderTheFinger() {
        assertEquals("b", ReorderMath.hit(Offset(130f, 50f), slots))
        assertNull(ReorderMath.hit(Offset(110f, 50f), slots))
    }

    @Test
    fun theTargetCountsOnlyTheMiddleOfACard() {
        assertEquals("c", ReorderMath.target(Offset(290f, 50f), slots, held = "a"))
        // The outer 12% of a card, and the gaps, keep the current place, so nothing flickers.
        assertNull(ReorderMath.target(Offset(245f, 50f), slots, held = "a"))
        assertNull(ReorderMath.target(Offset(230f, 50f), slots, held = "a"))
        // Over itself, nothing changes either.
        assertNull(ReorderMath.target(Offset(50f, 50f), slots, held = "a"))
    }

    @Test
    fun autoScrollGrowsTowardsTheEnds() {
        fun speed(pos: Float) = ReorderMath.autoScrollSpeed(pos, start = 0f, end = 1000f, zone = 100f, max = 2000f)
        assertEquals(0f, speed(500f))
        assertEquals(0f, speed(100f))
        assertTrue(speed(50f) < 0f)
        assertEquals(-2000f, speed(0f))
        assertEquals(2000f, speed(1000f))
        // Half way into the zone is a quarter of the speed.
        assertEquals(500f, speed(950f))
        assertEquals(0f, ReorderMath.autoScrollSpeed(10f, 0f, 0f, 100f, 2000f))
    }
}
