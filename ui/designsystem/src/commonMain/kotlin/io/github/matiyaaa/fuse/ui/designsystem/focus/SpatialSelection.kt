package io.github.matiyaaa.fuse.ui.designsystem.focus

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
 * Deterministic spatial navigation over arbitrary cells (tiles of different sizes). Moving picks the
 * nearest cell in that direction that overlaps the current one on the other axis; if none overlaps,
 * the nearest by distance. The same press always lands on the same tile.
 */
@Stable
class SpatialSelection(initial: Int = 0) {
    var index by mutableIntStateOf(initial)

    fun clamp(count: Int) {
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
        fun overlaps(c: GridCell) = if (horizontal) {
            c.row < from.row + from.rowSpan && from.row < c.row + c.rowSpan
        } else {
            c.column < from.column + from.columnSpan && from.column < c.column + c.columnSpan
        }
        val pool = candidates.filter { overlaps(it.value) }.ifEmpty { candidates }
        val best = pool.minBy { (_, c) ->
            val primary = if (horizontal) abs(c.centerX - from.centerX) else abs(c.centerY - from.centerY)
            val secondary = if (horizontal) abs(c.centerY - from.centerY) else abs(c.centerX - from.centerX)
            primary * 10 + secondary
        }
        index = best.index
        return NavResult.MOVED
    }
}
