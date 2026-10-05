package io.github.matiyaaa.fuse.ui.shell.quick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickLayoutTest {
    @Test
    fun nothingStoredIsFusesOwnAndItStoresAsNothing() {
        assertEquals(QuickLayout.default, QuickLayout.decode(emptyList()))
        assertEquals(emptyList(), QuickLayout.encode(QuickLayout.default))
    }

    @Test
    fun storedArrangementsRoundTrip() {
        val slots = listOf(QuickSlot(QuickId.MUSIC, 2), QuickSlot(QuickId.WIFI, 1), QuickSlot(QuickId.VOLUME, 1))
        assertEquals(slots, QuickLayout.decode(QuickLayout.encode(slots)))
    }

    @Test
    fun unknownRepeatedAndImpossibleEntriesAreMended() {
        val decoded = QuickLayout.decode(listOf("WIFI:1", "FROM_THE_FUTURE:2", "WIFI:3", "BRIGHTNESS:2", "SOUND"))
        assertEquals(listOf(QuickSlot(QuickId.WIFI, 1), QuickSlot(QuickId.BRIGHTNESS, 3), QuickSlot(QuickId.SOUND, 1)), decoded)
    }

    @Test
    fun rowsFillInOrderAndAWideItemThatDoesNotFitStartsTheNext() {
        val placed = QuickLayout.place(listOf(1, 1, 2, 3, 1))
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 2 to 0, 3 to 0), placed.map { it.row to it.column })
    }

    @Test
    fun movingTakesTheItemOutAndPutsItAtItsNewPlace() {
        assertEquals(listOf("b", "c", "a", "d"), QuickLayout.move(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), QuickLayout.move(listOf("a", "b", "c", "d"), 3, 0))
        assertEquals(listOf("a", "b"), QuickLayout.move(listOf("a", "b"), 5, 0))
    }

    @Test
    fun upAndDownKeepToTheColumnTheyCameFrom() {
        // Row 0: three tiles; row 1: a widget across; row 2: three tiles.
        val placed = QuickLayout.place(listOf(1, 1, 1, 3, 1, 1, 1))
        assertEquals(3, QuickLayout.neighbour(placed, 2, 0, 1))
        // From the widget, the column the selection came down in decides where it lands.
        assertEquals(6, QuickLayout.neighbour(placed, 3, 0, 1, anchor = QuickLayout.centre(placed[2])))
        assertEquals(5, QuickLayout.neighbour(placed, 3, 0, 1))
        assertNull(QuickLayout.neighbour(placed, 6, 0, 1))
        assertNull(QuickLayout.neighbour(placed, 3, 1, 0))
        assertEquals(1, QuickLayout.neighbour(placed, 0, 1, 0))
    }

    @Test
    fun sizesCycleThroughWhatAnItemCanTake() {
        assertEquals(2, QuickId.WIFI.nextSpan(1))
        assertEquals(3, QuickId.WIFI.nextSpan(2))
        assertEquals(1, QuickId.WIFI.nextSpan(3))
        assertEquals(3, QuickId.VOLUME.nextSpan(1))
        assertEquals(1, QuickId.VOLUME.nextSpan(3))
    }

    @Test
    fun aPointOverTheGridFindsItsItem() {
        val placed = QuickLayout.place(listOf(1, 2, 3))
        assertEquals(1, QuickLayout.at(placed, 2.5f, 0))
        assertEquals(2, QuickLayout.at(placed, 0.2f, 1))
        assertEquals(2, QuickLayout.at(placed, 1f, 7))
    }
}
