package io.github.matiyaaa.fuse.ui.designsystem.focus

import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackBoardTest {
    @Test
    fun widgetsTakeTheFirstPlaceThatFitsInReadingOrder() {
        val cells = packBoard(listOf(2 to 2, 1 to 1, 1 to 1, 2 to 1, 2 to 1), columns = 4)
        assertEquals(
            listOf(GridCell(0, 0, 2, 2), GridCell(2, 0, 1), GridCell(3, 0, 1), GridCell(2, 1, 2), GridCell(0, 2, 2)),
            cells,
        )
    }

    @Test
    fun aSmallWidgetFillsTheGapALargeOneLeft() {
        // Three small ones, then one too wide for the last place: it starts a row, and the next
        // small one goes back to the gap it left.
        val cells = packBoard(listOf(1 to 1, 1 to 1, 1 to 1, 2 to 1, 1 to 1), columns = 4)
        assertEquals(GridCell(0, 1, 2), cells[3])
        assertEquals(GridCell(3, 0, 1), cells[4])
    }

    @Test
    fun widgetsWiderThanTheBoardAreMadeAsWideAsIt() {
        val cells = packBoard(listOf(4 to 2, 3 to 1), columns = 2)
        assertEquals(GridCell(0, 0, 2, 2), cells[0])
        assertEquals(GridCell(0, 2, 2, 1), cells[1])
    }

    @Test
    fun noTwoWidgetsEverOverlap() {
        val random = Random(7)
        repeat(200) {
            val columns = if (random.nextBoolean()) 4 else 2
            val sizes = List(random.nextInt(1, 14)) { random.nextInt(1, 5) to random.nextInt(1, 4) }
            val cells = packBoard(sizes, columns)
            val seen = HashSet<Pair<Int, Int>>()
            for (c in cells) {
                assertTrue(c.column >= 0 && c.column + c.columnSpan <= columns, "inside the board: $c")
                for (r in c.row until c.row + c.rowSpan) for (x in c.column until c.column + c.columnSpan) {
                    assertTrue(seen.add(x to r), "cell $x,$r taken twice in $sizes")
                }
            }
        }
    }

    @Test
    fun theDpadMovesBetweenWidgetsOfEverySize() {
        // A large widget on the left, two small ones and a wide one on its right.
        val cells = packBoard(listOf(2 to 2, 1 to 1, 1 to 1, 2 to 1), columns = 4)
        val sel = SpatialSelection(0)
        assertEquals(NavResult.MOVED, sel.move(NavAction.RIGHT, cells))
        assertEquals(1, sel.index)
        assertEquals(NavResult.MOVED, sel.move(NavAction.DOWN, cells))
        assertEquals(3, sel.index)
        assertEquals(NavResult.MOVED, sel.move(NavAction.LEFT, cells))
        assertEquals(0, sel.index)
    }
}
