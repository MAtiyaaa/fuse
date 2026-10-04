package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult

/**
 * Selection is explicit state, not platform focus. Every list or grid owns one of these, so the
 * selected item survives recomposition, layout changes and navigation, and can never "disappear".
 *
 * Edges return [NavResult.IGNORED] so the parent layer can decide (for example Up on the first row
 * moves to the section tabs).
 */
@Stable
class LinearSelection(initial: Int = 0) {
    var index by mutableIntStateOf(initial)

    /** Clamps into range after the list changed size. */
    /**
     * Keeps the index inside [count] items. Screens call this while composing; it reads the index
     * without subscribing the screen to it, so moving the selection doesn't recompose the screen
     * that only clamps it.
     */
    /**
     * Keeps the selection on the same row when rows before it come or go ([ids] are the rows' ids,
     * in order): a warning row that clears above the chosen one doesn't move the choice to its
     * neighbour. Called while composing, like [clamp], without subscribing to the index.
     */
    fun keepOn(ids: List<String>) = Snapshot.withoutReadObservation {
        val old = lastIds
        if (old != null && old != ids) {
            val at = old.getOrNull(index)?.let(ids::indexOf) ?: -1
            if (at >= 0) index = at
        }
        lastIds = ids
    }

    private var lastIds: List<String>? = null

    fun clamp(count: Int) = Snapshot.withoutReadObservation {
        if (count <= 0) index = 0 else if (index >= count) index = count - 1
    }

    /** Horizontal (or vertical when [vertical]) movement over [count] items. */
    fun move(action: NavAction, count: Int, vertical: Boolean = false, page: Int = 5, wrap: Boolean = false): NavResult {
        if (count <= 0) return NavResult.IGNORED
        val back = if (vertical) NavAction.UP else NavAction.LEFT
        val fwd = if (vertical) NavAction.DOWN else NavAction.RIGHT
        val target = when (action) {
            back -> index - 1
            fwd -> index + 1
            NavAction.PAGE_UP -> (index - page).coerceAtLeast(0)
            NavAction.PAGE_DOWN -> (index + page).coerceAtMost(count - 1)
            else -> return NavResult.IGNORED
        }
        return when {
            target in 0 until count && target != index -> { index = target; NavResult.MOVED }
            wrap && (action == back || action == fwd) -> { index = (target + count) % count; NavResult.MOVED }
            action == NavAction.PAGE_UP || action == NavAction.PAGE_DOWN -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }

    /** Jumps to [target] (touch, search results, restoring a remembered position). */
    fun select(target: Int, count: Int): Boolean {
        if (count <= 0 || target !in 0 until count) return false
        val moved = target != index
        index = target
        return moved
    }
}

/** Two-dimensional grid with a fixed number of [columns]. */
@Stable
class GridSelection(initial: Int = 0) {
    var index by mutableIntStateOf(initial)

    /**
     * Keeps the index inside [count] items. Screens call this while composing; it reads the index
     * without subscribing the screen to it, so moving the selection doesn't recompose the screen
     * that only clamps it.
     */
    fun clamp(count: Int) = Snapshot.withoutReadObservation {
        if (count <= 0) index = 0 else if (index >= count) index = count - 1
    }

    fun row(columns: Int): Int = index / columns.coerceAtLeast(1)
    fun column(columns: Int): Int = index % columns.coerceAtLeast(1)

    fun move(action: NavAction, count: Int, columns: Int, pageRows: Int = 3): NavResult {
        if (count <= 0) return NavResult.IGNORED
        val cols = columns.coerceAtLeast(1)
        val col = index % cols
        val rows = (count + cols - 1) / cols
        val row = index / cols
        val target = when (action) {
            NavAction.LEFT -> if (col == 0) return NavResult.IGNORED else index - 1
            NavAction.RIGHT -> if (col == cols - 1 || index == count - 1) return NavResult.IGNORED else index + 1
            NavAction.UP -> if (row == 0) return NavResult.IGNORED else index - cols
            // Moving down into a shorter last row lands on its last item instead of refusing.
            NavAction.DOWN -> if (row == rows - 1) return NavResult.IGNORED else (index + cols).coerceAtMost(count - 1)
            NavAction.PAGE_UP -> (index - cols * pageRows).let { if (it < 0) col.coerceAtMost(count - 1) else it }
            NavAction.PAGE_DOWN -> (index + cols * pageRows).coerceAtMost(count - 1)
            else -> return NavResult.IGNORED
        }
        if (target == index) return NavResult.BLOCKED
        index = target
        return NavResult.MOVED
    }

    fun select(target: Int, count: Int): Boolean {
        if (count <= 0 || target !in 0 until count) return false
        val moved = target != index
        index = target
        return moved
    }
}

/**
 * Rows of items where each row remembers its own column (Home shelves). Moving up or down returns to
 * the column you last had in that row, like a console dashboard.
 */
@Stable
class ShelfSelection(initialRow: Int = 0) {
    var row by mutableIntStateOf(initialRow)
    private val columns = mutableStateMapOf<String, Int>()

    fun column(rowKey: String): Int = columns[rowKey] ?: 0

    fun setColumn(rowKey: String, column: Int) {
        columns[rowKey] = column
    }

    fun clamp(rowKeys: List<String>, sizeOf: (String) -> Int) = Snapshot.withoutReadObservation {
        if (rowKeys.isEmpty()) {
            row = 0
            return@withoutReadObservation
        }
        if (row >= rowKeys.size) row = rowKeys.size - 1
        for (key in rowKeys) {
            val size = sizeOf(key)
            val c = columns[key] ?: continue
            if (size > 0 && c >= size) columns[key] = size - 1
        }
    }

    /**
     * Moves within [rowKeys]. [sizeOf] gives the item count of a row (rows with 0 items are skipped
     * vertically).
     */
    fun move(action: NavAction, rowKeys: List<String>, sizeOf: (String) -> Int): NavResult {
        if (rowKeys.isEmpty()) return NavResult.IGNORED
        val key = rowKeys[row.coerceIn(0, rowKeys.lastIndex)]
        val size = sizeOf(key)
        val col = column(key)
        return when (action) {
            NavAction.LEFT -> if (col > 0) { setColumn(key, col - 1); NavResult.MOVED } else NavResult.IGNORED
            NavAction.RIGHT -> if (col < size - 1) { setColumn(key, col + 1); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP -> {
                var r = row - 1
                while (r >= 0 && sizeOf(rowKeys[r]) == 0) r--
                if (r < 0) NavResult.IGNORED else { row = r; NavResult.MOVED }
            }
            NavAction.DOWN -> {
                var r = row + 1
                while (r <= rowKeys.lastIndex && sizeOf(rowKeys[r]) == 0) r++
                if (r > rowKeys.lastIndex) NavResult.BLOCKED else { row = r; NavResult.MOVED }
            }
            else -> NavResult.IGNORED
        }
    }
}
