package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.math.abs

/** A cell in a packed grid: column/row start and span, in grid units. */
data class GridCell(val column: Int, val row: Int, val columnSpan: Int, val rowSpan: Int = 1) {
    val centerX: Float get() = column + columnSpan / 2f
    val centerY: Float get() = row + rowSpan / 2f
}

/**
 * Packs items with column spans into rows of [columns], left to right, like a board of tiles.
 * Returns one cell per item in the same order.
 */
fun packCells(spans: List<Int>, columns: Int): List<GridCell> {
    var col = 0
    var row = 0
    return spans.map { raw ->
        val span = raw.coerceIn(1, columns)
        if (col + span > columns) {
            col = 0
            row++
        }
        GridCell(col, row, span).also { col += span }
    }
}

/**
 * Packs items of [sizes] (cells across and down) onto a board [columns] wide, the way a phone's
 * home screen places widgets: each in turn takes the first place, reading from the top left, where
 * it fits without overlapping those before it. A small item can fill a gap a larger one left
 * behind, so the board stays tight; items wider than the board are made as wide as it. Returns one
 * cell per item, in the same order.
 */
fun packBoard(sizes: List<Pair<Int, Int>>, columns: Int): List<GridCell> {
    val cols = columns.coerceAtLeast(1)
    // Taken cells, row by row; rows are added as items need them.
    val taken = ArrayList<BooleanArray>()
    fun free(col: Int, row: Int, w: Int, h: Int): Boolean {
        for (r in row until row + h) {
            val line = taken.getOrNull(r) ?: continue
            for (c in col until col + w) if (line[c]) return false
        }
        return true
    }
    return sizes.map { (rawW, rawH) ->
        val w = rawW.coerceIn(1, cols)
        val h = rawH.coerceAtLeast(1)
        var row = 0
        var placed: GridCell? = null
        while (placed == null) {
            for (col in 0..cols - w) {
                if (free(col, row, w, h)) {
                    placed = GridCell(col, row, w, h)
                    break
                }
            }
            if (placed == null) row++
        }
        val cell = placed
        while (taken.size < cell.row + cell.rowSpan) taken += BooleanArray(cols)
        for (r in cell.row until cell.row + cell.rowSpan) for (c in cell.column until cell.column + cell.columnSpan) taken[r][c] = true
        cell
    }
}

/**
 * Deterministic spatial navigation over arbitrary cells (tiles of different sizes). Up and down go to
 * the very next row, never past it: inside that row they pick the cell that overlaps the current one
 * on the other axis, else the closest one. Left and right stay in the row when they can, else go to
 * the nearest cell by distance. The same press always lands on the same tile.
 */
@Stable
class SpatialSelection(initial: Int = 0) {
    var index by mutableIntStateOf(initial)

    /**
     * Keeps the index inside [count] items. Screens call this while composing; it reads the index
     * without subscribing the screen to it, so moving the selection doesn't recompose the screen
     * that only clamps it.
     */
    fun clamp(count: Int) = Snapshot.withoutReadObservation {
        if (count <= 0) index = 0 else if (index >= count) index = count - 1
    }

    fun move(action: NavAction, cells: List<GridCell>): NavResult {
        val from = cells.getOrNull(index) ?: return NavResult.IGNORED
        val candidates = cells.withIndex().filter { (i, c) ->
            i != index && when (action) {
                NavAction.LEFT -> c.column + c.columnSpan <= from.column
                NavAction.RIGHT -> c.column >= from.column + from.columnSpan
                NavAction.UP -> c.row + c.rowSpan <= from.row
                NavAction.DOWN -> c.row >= from.row + from.rowSpan
                else -> false
            }
        }
        if (candidates.isEmpty()) return NavResult.IGNORED
        val horizontal = action == NavAction.LEFT || action == NavAction.RIGHT
        // Up and down only look at the nearest row, so a short row is never skipped over.
        val near = if (horizontal) {
            candidates
        } else {
            fun gap(c: GridCell) = if (action == NavAction.UP) from.row - (c.row + c.rowSpan) else c.row - (from.row + from.rowSpan)
            val nearest = candidates.minOf { gap(it.value) }
            candidates.filter { gap(it.value) == nearest }
        }
        fun overlaps(c: GridCell) = if (horizontal) {
            c.row < from.row + from.rowSpan && from.row < c.row + c.rowSpan
        } else {
            c.column < from.column + from.columnSpan && from.column < c.column + c.columnSpan
        }
        val pool = near.filter { overlaps(it.value) }.ifEmpty { near }
        val best = pool.minBy { (_, c) ->
            val primary = if (horizontal) abs(c.centerX - from.centerX) else abs(c.centerY - from.centerY)
            val secondary = if (horizontal) abs(c.centerY - from.centerY) else abs(c.centerX - from.centerX)
            primary * 10 + secondary
        }
        index = best.index
        return NavResult.MOVED
    }
}
