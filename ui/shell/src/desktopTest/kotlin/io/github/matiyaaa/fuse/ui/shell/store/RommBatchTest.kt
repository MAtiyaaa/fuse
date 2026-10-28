package io.github.matiyaaa.fuse.ui.shell.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A run of RomM games asked for together: how much goes to each drive, and what fits beside what is already coming. */
class RommBatchTest {
    private val gb = 1L shl 30

    private fun game(id: Long, gigs: Long, drive: String? = "sd", label: String = "SD card") = RommBatch.Game(id, gigs * gb, drive, label)

    @Test
    fun aBatchThatFitsSaysSoDriveByDrive() {
        val plan = RommBatch.plan(
            listOf(game(1, 2), game(2, 3), game(3, 1, drive = null, label = "This device")),
            free = mapOf("sd" to 20 * gb, null to 10 * gb),
            coming = emptyMap(),
        )
        assertTrue(plan.fits)
        assertEquals(listOf(1L, 2L, 3L), plan.romIds)
        assertEquals(6 * gb, plan.bytes)
        assertEquals(plan.romIds, plan.fitting)
        // The drive taking most is said first.
        assertEquals(listOf("SD card" to 5 * gb, "This device" to gb), plan.drives.map { it.label to it.bytes })
        assertEquals(2, plan.drives.first().games)
    }

    @Test
    fun whatIsAlreadyComingTakesItsRoomFirstAndWhatFitsIsTakenInOrder() {
        // 10 GB free, 4 of it already on its way: 6 left for the batch, less the spare kept free.
        val plan = RommBatch.plan(
            listOf(game(1, 3), game(2, 4), game(3, 2)),
            free = mapOf("sd" to 10 * gb),
            coming = mapOf("sd" to 4 * gb),
        )
        assertFalse(plan.fits)
        assertEquals(6 * gb, plan.drives.single().free)
        // The first fits, the second can't beside it, the third can.
        assertEquals(listOf(1L, 3L), plan.fitting)
        assertEquals(5 * gb, plan.fittingBytes)
    }

    @Test
    fun aDriveThatCantSayItsRoomNeverHoldsTheBatchBack() {
        val plan = RommBatch.plan(listOf(game(1, 500)), free = mapOf("sd" to null), coming = mapOf("sd" to 3 * gb), unplaced = 2)
        assertTrue(plan.fits)
        assertNull(plan.drives.single().free)
        assertEquals(listOf(1L), plan.fitting)
        assertEquals(2, plan.unplaced)
    }

    @Test
    fun theSpareIsKeptOnTop() {
        // Exactly the free room: nothing to spare, so it doesn't fit.
        val plan = RommBatch.plan(listOf(game(1, 4)), free = mapOf("sd" to 4 * gb), coming = emptyMap())
        assertFalse(plan.fits)
        assertTrue(plan.fitting.isEmpty())
        assertTrue(RommBatch.plan(listOf(game(1, 4)), free = mapOf("sd" to 4 * gb + RommBatch.SPARE), coming = emptyMap()).fits)
    }
}
