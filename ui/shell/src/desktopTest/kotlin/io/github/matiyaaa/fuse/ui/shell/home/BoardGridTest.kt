package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.GridSpot
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.packBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoardGridTest {
    private fun item(id: String, w: Int, h: Int, at: Pair<Int, Int>? = null) =
        BoardGrid.Item(id, BoardSize(w, h), at?.let { GridSpot(it.first, it.second) })

    private fun BoardChange.layout(): BoardLayout = (this as BoardChange.Done).layout

    private fun BoardLayout.noOverlaps() {
        val all = rects.values.toList()
        for (i in all.indices) for (j in i + 1 until all.size) assertTrue(!all[i].overlaps(all[j]), "${all[i]} overlaps ${all[j]}")
        assertTrue(rects.values.all { it.column >= 0 && it.right <= columns && it.row >= 0 })
    }

    @Test
    fun aBoardNeverArrangedLooksExactlyAsItDidBefore() {
        val sizes = listOf(2 to 2, 1 to 1, 1 to 1, 2 to 1, 4 to 1, 2 to 1, 1 to 2, 2 to 1)
        val items = sizes.mapIndexed { i, (w, h) -> item("w$i", w, h) }
        val layout = BoardGrid.layout(items, 4)
        val old = packBoard(sizes, 4)
        items.forEachIndexed { i, it -> assertEquals(old[i], layout[it.id]!!.toCell()) }
    }

    @Test
    fun savedPlacesAreKeptGapsIncluded() {
        val layout = BoardGrid.layout(listOf(item("a", 1, 1, 3 to 2), item("b", 2, 1, 0 to 0)), 4)
        assertEquals(BoardRect(3, 2, 1, 1), layout["a"])
        assertEquals(BoardRect(0, 0, 2, 1), layout["b"])
        assertEquals(3, layout.rows)
    }

    @Test
    fun aNewWidgetTakesTheFirstFreePlace() {
        val layout = BoardGrid.layout(listOf(item("a", 2, 1, 0 to 0), item("b", 1, 1, 3 to 0), item("new", 1, 1)), 4)
        assertEquals(BoardRect(2, 0, 1, 1), layout["new"])
    }

    @Test
    fun overlappingSavedPlacesAreSettledWithoutLosingAWidget() {
        val layout = BoardGrid.layout(listOf(item("a", 2, 2, 0 to 0), item("b", 2, 1, 1 to 1)), 4)
        layout.noOverlaps()
        assertEquals(BoardRect(0, 0, 2, 2), layout["a"])
        assertEquals(2, layout.rects.size)
    }

    @Test
    fun movingOntoAWidgetOfTheSameSizeSwapsThem() {
        val layout = BoardGrid.layout(listOf(item("a", 1, 1, 0 to 0), item("b", 1, 1, 1 to 0)), 4)
        val moved = BoardGrid.move(layout, "a", 1, 0) as BoardChange.Done
        assertEquals(BoardRect(1, 0, 1, 1), moved.layout["a"])
        assertEquals(BoardRect(0, 0, 1, 1), moved.layout["b"])
        assertEquals(setOf("b"), moved.moved)
    }

    @Test
    fun aWideWidgetMovedOverTwoSmallOnesTradesPlacesWithBoth() {
        val layout = BoardGrid.layout(listOf(item("wide", 2, 1, 0 to 0), item("b", 1, 1, 2 to 0), item("c", 1, 1, 3 to 0)), 4)
        val after = BoardGrid.move(layout, "wide", 2, 0).layout()
        after.noOverlaps()
        assertEquals(BoardRect(2, 0, 2, 1), after["wide"])
        assertEquals(BoardRect(0, 0, 1, 1), after["b"])
        assertEquals(BoardRect(1, 0, 1, 1), after["c"])
    }

    @Test
    fun movingIntoEmptyCellsMovesNothingElse() {
        val layout = BoardGrid.layout(listOf(item("a", 1, 1, 0 to 0), item("b", 1, 1, 1 to 0)), 4)
        val change = BoardGrid.move(layout, "a", 3, 2) as BoardChange.Done
        assertEquals(BoardRect(3, 2, 1, 1), change.layout["a"])
        assertEquals(layout["b"], change.layout["b"])
        assertTrue(change.moved.isEmpty())
    }

    @Test
    fun aWidgetPushedAsideStaysNearWhereItWas() {
        // a (2x2) moves up one row onto b; where a came from is under a again, so b can't simply
        // trade places and goes to the nearest free place, in its own row.
        val layout = BoardGrid.layout(listOf(item("b", 1, 1, 0 to 0), item("a", 2, 2, 0 to 1)), 4)
        val after = BoardGrid.move(layout, "a", 0, 0).layout()
        after.noOverlaps()
        assertEquals(BoardRect(0, 0, 2, 2), after["a"])
        assertEquals(BoardRect(2, 0, 1, 1), after["b"])
    }

    @Test
    fun aWidgetWithNoRoomNearbyGoesJustBelowAndPushesTheRestDown() {
        // A full row: dropping a one-cell widget on the strip leaves the strip nowhere to go nearby,
        // so it goes just below and the row under it moves down a row.
        val layout = BoardGrid.layout(
            listOf(
                item("a", 1, 1, 0 to 0), item("b", 1, 1, 1 to 0), item("strip", 2, 1, 2 to 0),
                item("c", 2, 1, 0 to 1), item("d", 2, 1, 2 to 1),
                item("small", 1, 1, 0 to 2), item("e", 2, 1, 2 to 2), item("f", 1, 1, 1 to 2),
            ),
            4,
        )
        val after = BoardGrid.move(layout, "small", 2, 0).layout()
        after.noOverlaps()
        assertEquals(BoardRect(2, 0, 1, 1), after["small"])
        assertEquals(BoardRect(2, 1, 2, 1), after["strip"])
        assertEquals(BoardRect(2, 2, 2, 1), after["d"])
        assertEquals(BoardRect(2, 3, 2, 1), after["e"])
        assertEquals(layout["c"], after["c"])
    }

    @Test
    fun movesPastTheEdgeAreRefused() {
        val layout = BoardGrid.layout(listOf(item("a", 2, 1, 0 to 0)), 4)
        assertIs<BoardChange.Blocked>(BoardGrid.move(layout, "a", 3, 0))
        assertIs<BoardChange.Blocked>(BoardGrid.move(layout, "a", -1, 0))
        assertIs<BoardChange.Blocked>(BoardGrid.move(layout, "a", 0, -1))
    }

    @Test
    fun growingPushesNeighboursAside() {
        val layout = BoardGrid.layout(listOf(item("a", 1, 1, 0 to 0), item("b", 1, 1, 1 to 0), item("c", 1, 1, 0 to 1)), 4)
        val change = BoardGrid.resize(layout, "a", BoardRect(0, 0, 2, 2)) as BoardChange.Done
        change.layout.noOverlaps()
        assertEquals(BoardRect(0, 0, 2, 2), change.layout["a"])
        assertEquals(setOf("b", "c"), change.moved)
        assertEquals(0, change.layout["b"]!!.row, "b stays in its row")
    }

    @Test
    fun sizesOutsideTheLimitsAreRefused() {
        val layout = BoardGrid.layout(listOf(item("a", 1, 1, 0 to 0)), 4)
        assertEquals(BoardChange.Blocked(BoardLimit.SMALLEST), BoardGrid.resize(layout, "a", BoardRect(0, 0, 0, 1)))
        assertEquals(BoardChange.Blocked(BoardLimit.LARGEST), BoardGrid.resize(layout, "a", BoardRect(0, 0, 1, 4)))
        assertEquals(BoardChange.Blocked(BoardLimit.EDGE), BoardGrid.resize(layout, "a", BoardRect(3, 0, 2, 1)))
        val narrow = BoardGrid.layout(listOf(item("a", 1, 1, 0 to 0)), 2)
        assertEquals(BoardChange.Blocked(BoardLimit.LARGEST), BoardGrid.resize(narrow, "a", BoardRect(0, 0, 3, 1)))
    }

    @Test
    fun controllerStepsGrowAndShrinkPredictably() {
        fun step(r: BoardRect, a: NavAction) = BoardGrid.resizeStep(r, a, 4)!!
        val r = BoardRect(1, 0, 2, 1)
        assertEquals(BoardRect(1, 0, 3, 1), step(r, NavAction.RIGHT).getOrThrow())
        assertEquals(BoardRect(1, 0, 1, 1), step(r, NavAction.LEFT).getOrThrow())
        assertEquals(BoardRect(1, 0, 2, 2), step(r, NavAction.DOWN).getOrThrow())
        // At the right edge, wider takes the column on the left.
        assertEquals(BoardRect(1, 0, 3, 1), step(BoardRect(2, 0, 2, 1), NavAction.RIGHT).getOrThrow())
        assertEquals(BoardLimit.SMALLEST, (step(BoardRect(0, 0, 1, 1), NavAction.UP).exceptionOrNull() as BoardGrid.LimitException).limit)
        assertEquals(BoardLimit.LARGEST, (step(BoardRect(0, 0, 4, 1), NavAction.RIGHT).exceptionOrNull() as BoardGrid.LimitException).limit)
        assertEquals(BoardLimit.LARGEST, (step(BoardRect(0, 0, 1, 3), NavAction.DOWN).exceptionOrNull() as BoardGrid.LimitException).limit)
    }

    @Test
    fun theSameRequestAlwaysGivesTheSameBoard() {
        val items = (0 until 9).map { item("w$it", 1 + it % 2, 1 + it % 3) }
        val layout = BoardGrid.layout(items, 4)
        val a = BoardGrid.move(layout, "w4", 0, 0).layout()
        val b = BoardGrid.move(layout, "w4", 0, 0).layout()
        assertEquals(a, b)
        a.noOverlaps()
    }

    @Test
    fun everyMoveOnABusyBoardKeepsEveryWidgetWithoutOverlaps() {
        val items = (0 until 10).map { item("w$it", 1 + it % 3, 1 + it % 2) }
        val layout = BoardGrid.layout(items, 4)
        for (id in layout.ids) {
            val r = layout[id]!!
            for (row in 0..layout.rows) for (col in 0..4 - r.width) {
                val after = BoardGrid.move(layout, id, col, row).layout()
                after.noOverlaps()
                assertEquals(layout.rects.keys, after.rects.keys)
                assertEquals(BoardRect(col, row, r.width, r.height), after[id])
            }
        }
    }

    @Test
    fun aPhoneBoardKeepsItsOwnPlaces() {
        val wide = item("a", 2, 1, 2 to 0)
        // On two columns the saved four-column spot doesn't apply; the board passes the two-column one.
        val narrow = BoardGrid.layout(listOf(wide.copy(spot = null)), 2)
        assertEquals(BoardRect(0, 0, 2, 1), narrow["a"])
        val fit = BoardGrid.layout(listOf(item("b", 4, 1)), 2)
        assertEquals(2, fit["b"]!!.width)
    }

    // ------------------------------------------------------------------ packed boards (the Systems page)

    /** Twelve cards of one size, four across, packed in order. */
    private fun shelf(columns: Int = 4, n: Int = 12, size: BoardSize = BoardSize(1, 1)) =
        BoardGrid.pack((0 until n).map { "s$it" }, (0 until n).associate { "s$it" to size }, columns)

    @Test
    fun aSystemPutOnItsNeighbourTradesPlacesAndNothingElseMoves() {
        val board = shelf()
        val next = BoardGrid.drop(board, "s1", 2, 0)
        assertEquals(listOf("s0", "s2", "s1", "s3"), next.layout.ids.take(4))
        assertEquals(setOf("s2"), next.moved)
        // Back again the other way.
        val back = BoardGrid.drop(next.layout, "s1", 1, 0)
        assertEquals(board.ids, back.layout.ids)
    }

    @Test
    fun carriedFarTheOthersShiftAlongInOrderInsteadOfScattering() {
        val board = shelf()
        // From the top left to the second row's third place: the ones between move back one, in order.
        val next = BoardGrid.drop(board, "s0", 2, 1).layout
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6", "s0", "s7"), next.ids.take(8))
        next.noOverlaps()
        // Everything after where it landed stays exactly where it was.
        for (id in listOf("s7", "s8", "s9", "s10", "s11")) assertEquals(board[id], next[id])
    }

    @Test
    fun theControllerCarriesASystemAlongTheReadingOrderAndAcrossRows() {
        var board = shelf()
        board = BoardGrid.carry(board, "s3", NavAction.RIGHT)!!
        // Past the row's end it goes on to the next row's start.
        assertEquals(BoardRect(0, 1, 1, 1), board["s3"])
        board = BoardGrid.carry(board, "s3", NavAction.DOWN)!!
        assertEquals(BoardRect(0, 2, 1, 1), board["s3"])
        board = BoardGrid.carry(board, "s3", NavAction.UP)!!
        board = BoardGrid.carry(board, "s3", NavAction.UP)!!
        assertEquals(BoardRect(0, 0, 1, 1), board["s3"])
        // Nowhere further up or back.
        assertEquals(null, BoardGrid.carry(board, "s3", NavAction.UP))
        assertEquals(null, BoardGrid.carry(board, "s3", NavAction.LEFT))
        board.noOverlaps()
    }

    @Test
    fun resizingAPackedSystemKeepsTheOrderAndTheRestFlowAroundIt() {
        val board = shelf(columns = 8, size = BoardSize(2, 1))
        // Half a card: the ones after it move up into the room it left.
        val small = BoardGrid.resizePacked(board, "s1", BoardSize(1, 1)).layout()
        assertEquals(board.ids, small.ids)
        assertEquals(BoardRect(2, 0, 1, 1), small["s1"])
        assertEquals(BoardRect(3, 0, 2, 1), small["s2"])
        // Taller: two rows, and the rows flow around it.
        val tall = BoardGrid.resizePacked(board, "s1", BoardSize(2, 2)).layout()
        assertEquals(BoardRect(2, 0, 2, 2), tall["s1"])
        tall.noOverlaps()
        // And back to a card: as it was.
        assertEquals(board, BoardGrid.resizePacked(tall, "s1", BoardSize(2, 1)).layout())
        // Never past the largest.
        assertIs<BoardChange.Blocked>(BoardGrid.resizePacked(board, "s1", BoardSize(2, 4)))
    }
}
