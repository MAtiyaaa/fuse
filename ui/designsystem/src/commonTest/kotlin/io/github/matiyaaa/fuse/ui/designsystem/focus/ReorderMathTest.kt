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

    @Test
    fun theItemJustPassedWaitsUntilTheFingerLeavesIt() {
        // A wide card that slid under the finger after a swap doesn't swap straight back.
        assertNull(ReorderMath.target(Offset(290f, 50f), slots, held = "a", skip = "c"))
        assertEquals("b", ReorderMath.target(Offset(170f, 50f), slots, held = "a", skip = "c"))
    }

    @Test
    fun liftTimeIsQuickerThanALongPressWithinBounds() {
        assertEquals(350L, ReorderDefaults.liftMs(500))
        assertEquals(300L, ReorderDefaults.liftMs(200))
        assertEquals(450L, ReorderDefaults.liftMs(2_000))
    }

    @Test
    fun theNearestPlaceIsFoundEvenOutsideEveryCard() {
        // Past the end of the row, in the padding where the list scrolls by itself.
        assertEquals("c", ReorderMath.nearest(Offset(400f, 50f), slots, held = "a"))
        assertEquals("b", ReorderMath.nearest(Offset(-30f, 50f), slots, held = "a"))
    }

    @Test
    fun inAColumnOnlyTheDistanceAlongItCounts() {
        val rows = mapOf<Any, Rect>("a" to Rect(0f, 0f, 1000f, 100f), "b" to Rect(0f, 120f, 1000f, 220f))
        // Held by the far left (a shelf's title), over the next row.
        assertEquals("b", ReorderMath.target(Offset(20f, 170f), rows, held = "a", lane = ReorderMath.Lane.COLUMN))
        assertNull(ReorderMath.target(Offset(20f, 170f), rows, held = "a"))
    }
}
