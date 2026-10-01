package io.github.matiyaaa.fuse.ui.designsystem.focus

import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SpatialSelectionTest {

    // The default Channels board: Continue playing (2), Systems, New in your library, then Favourites,
    // Recent achievements (2), then This week (2), Cartridge (2), then Apps. Rows of 3, 2, 2 and 1.
    private val board = packCells(listOf(2, 1, 1, 1, 2, 2, 2, 1), columns = 4)

    private fun SpatialSelection.press(action: NavAction): Int {
        move(action, board)
        return index
    }

    @Test
    fun theBoardPacksIntoRows() {
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2, 3), board.map { it.row })
        assertEquals(listOf(0, 2, 3, 0, 1, 0, 2, 0), board.map { it.column })
    }

    @Test
    fun downFromTheLastTileOfARowGoesToTheNextRowEvenWhenItIsShorter() {
        // New in your library sits over nothing in the row of two, so it lands on that row's closest tile.
        assertEquals(4, SpatialSelection(2).press(NavAction.DOWN))
        assertEquals(4, SpatialSelection(1).press(NavAction.DOWN))
        assertEquals(3, SpatialSelection(0).press(NavAction.DOWN))
    }

    @Test
    fun upNeverSkipsARowEither() {
        assertEquals(4, SpatialSelection(6).press(NavAction.UP))
        assertEquals(3, SpatialSelection(5).press(NavAction.UP))
        assertEquals(5, SpatialSelection(7).press(NavAction.UP))
    }

    @Test
    fun leftAndRightStayInTheRow() {
        assertEquals(1, SpatialSelection(0).press(NavAction.RIGHT))
        assertEquals(2, SpatialSelection(1).press(NavAction.RIGHT))
        assertEquals(3, SpatialSelection(4).press(NavAction.LEFT))
    }

    @Test
    fun theEdgesAreIgnored() {
        val s = SpatialSelection(0)
        assertEquals(NavResult.IGNORED, s.move(NavAction.UP, board))
        assertEquals(NavResult.IGNORED, s.move(NavAction.LEFT, board))
        assertEquals(0, s.index)
        assertEquals(NavResult.IGNORED, SpatialSelection(7).move(NavAction.DOWN, board))
    }
}
