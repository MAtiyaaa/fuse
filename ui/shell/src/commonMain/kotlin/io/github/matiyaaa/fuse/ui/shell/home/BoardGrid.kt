package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.GridSpot
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridCell
import kotlin.math.abs

/** A widget's place and size on the board, in cells. */
data class BoardRect(val column: Int, val row: Int, val width: Int, val height: Int) {
    val right: Int get() = column + width
    val bottom: Int get() = row + height
    val spot: GridSpot get() = GridSpot(column, row)
    val size: BoardSize get() = BoardSize(width, height)

    fun overlaps(o: BoardRect): Boolean = column < o.right && o.column < right && row < o.bottom && o.row < bottom

    fun contains(c: Int, r: Int): Boolean = c in column until right && r in row until bottom

    fun toCell(): GridCell = GridCell(column, row, width, height)
}

/** The board as placed: every shown widget's rectangle on a board [columns] wide. */
data class BoardLayout(val columns: Int, val rects: Map<String, BoardRect>) {
    /** Ids in reading order: top to bottom, then left to right. */
    val ids: List<String> by lazy { rects.entries.sortedWith(compareBy({ it.value.row }, { it.value.column })).map { it.key } }

    /** Rows in use. */
    val rows: Int get() = rects.values.maxOfOrNull { it.bottom } ?: 0

    operator fun get(id: String): BoardRect? = rects[id]

    /** The widget covering the cell, or null when it is empty. */
    fun occupant(column: Int, row: Int): String? = rects.entries.firstOrNull { it.value.contains(column, row) }?.key

    /** True when [rect] lies on the board and covers no widget but those in [ignoring]. */
    fun isFree(rect: BoardRect, ignoring: Set<String> = emptySet()): Boolean =
        rect.column >= 0 && rect.row >= 0 && rect.right <= columns &&
            rects.none { (id, r) -> id !in ignoring && r.overlaps(rect) }
}

/** Why a move or resize can't happen. */
enum class BoardLimit {
    /** The board's edge is in the way. */
    EDGE,

    /** The widget is as large as a widget can be. */
    LARGEST,

    /** The widget is one cell already. */
    SMALLEST,
}

/** What asking the board for a change gave. */
sealed interface BoardChange {
    /** The board after the change; [moved] are the other widgets that made room. */
    data class Done(val layout: BoardLayout, val moved: Set<String>) : BoardChange

    data class Blocked(val limit: BoardLimit) : BoardChange
}

/**
 * Home's board as a real grid: every widget has its own place, so moving or resizing one never
 * reflows the rest. A widget put where others are takes those cells, and each widget it covers moves
 * to the nearest free place, preferring the place the moved widget just left (so two widgets swap).
 * Everything here is pure and deterministic: the same request on the same board always gives the
 * same result, which is what the live preview shows and what is then kept.
 */
object BoardGrid {
    /** A widget to place: its size and, when it has one for this board width, its saved spot. */
    data class Item(val id: String, val size: BoardSize, val spot: GridSpot?)

    /**
     * Places [items] (in their order) on a board [columns] wide. Saved spots are kept when they are
     * on the board and free; widgets without one (all of them on a board never arranged, or a widget
     * just added) take the first place they fit, reading from the top left, like a phone's home
     * screen. A board never arranged therefore looks exactly as it did when widgets had no places.
     */
    fun layout(items: List<Item>, columns: Int): BoardLayout {
        val cols = columns.coerceAtLeast(1)
        val placed = LinkedHashMap<String, BoardRect>()
        val rest = ArrayList<Item>()
        for (item in items) {
            val size = item.size.fit(cols)
            val spot = item.spot
            if (spot == null) {
                rest += item
                continue
            }
            val r = BoardRect(spot.column.coerceIn(0, cols - size.width), spot.row.coerceAtLeast(0), size.width, size.height)
            if (BoardLayout(cols, placed).isFree(r)) placed[item.id] = r else rest += item
        }
        for (item in rest) {
            val size = item.size.fit(cols)
            val current = BoardLayout(cols, placed)
            val want = item.spot
            placed[item.id] = if (want != null) {
                nearestFree(BoardRect(want.column.coerceIn(0, cols - size.width), want.row.coerceAtLeast(0), size.width, size.height), current)
            } else {
                firstFree(size, current)
            }
        }
        return BoardLayout(cols, placed)
    }

    /** [id] moved so its top left corner is at [column], [row]. */
    fun move(layout: BoardLayout, id: String, column: Int, row: Int): BoardChange {
        val from = layout[id] ?: return BoardChange.Blocked(BoardLimit.EDGE)
        val target = BoardRect(column, row, from.width, from.height)
        if (column < 0 || row < 0 || target.right > layout.columns) return BoardChange.Blocked(BoardLimit.EDGE)
        if (target == from) return BoardChange.Done(layout, emptySet())
        return place(layout, id, target, vacated = from)
    }

    /** [id] given the place and size [target]; widgets in the way make room. */
    fun resize(layout: BoardLayout, id: String, target: BoardRect): BoardChange {
        val from = layout[id] ?: return BoardChange.Blocked(BoardLimit.EDGE)
        sizeLimit(target, layout.columns)?.let { return BoardChange.Blocked(it) }
        if (target == from) return BoardChange.Done(layout, emptySet())
        return place(layout, id, target, vacated = null)
    }

    /** Why [rect] can't be a widget's place on a board [columns] wide, or null when it can. */
    fun sizeLimit(rect: BoardRect, columns: Int): BoardLimit? = when {
        rect.width < 1 || rect.height < 1 -> BoardLimit.SMALLEST
        rect.width > maxWidth(columns) || rect.height > BoardSize.MAX_HEIGHT -> BoardLimit.LARGEST
        rect.column < 0 || rect.row < 0 || rect.right > columns -> BoardLimit.EDGE
        else -> null
    }

    /** The widest a widget can be on a board [columns] wide. */
    fun maxWidth(columns: Int): Int = minOf(BoardSize.MAX_WIDTH, columns.coerceAtLeast(1))

    /**
     * One controller step of resizing [rect] (the D-pad with Options held): right and down make it
     * bigger, left and up smaller. Against the board's right edge, growing wider takes the column on
     * its left instead, so a widget at the edge can still grow. Null for other actions.
     */
    fun resizeStep(rect: BoardRect, action: NavAction, columns: Int): Result<BoardRect>? = when (action) {
        NavAction.RIGHT -> when {
            rect.width >= maxWidth(columns) -> Result.failure(LimitException(BoardLimit.LARGEST))
            rect.right < columns -> Result.success(rect.copy(width = rect.width + 1))
            rect.column > 0 -> Result.success(rect.copy(column = rect.column - 1, width = rect.width + 1))
            else -> Result.failure(LimitException(BoardLimit.EDGE))
        }
        NavAction.LEFT -> if (rect.width > 1) Result.success(rect.copy(width = rect.width - 1)) else Result.failure(LimitException(BoardLimit.SMALLEST))
        NavAction.DOWN -> if (rect.height < BoardSize.MAX_HEIGHT) Result.success(rect.copy(height = rect.height + 1)) else Result.failure(LimitException(BoardLimit.LARGEST))
        NavAction.UP -> if (rect.height > 1) Result.success(rect.copy(height = rect.height - 1)) else Result.failure(LimitException(BoardLimit.SMALLEST))
        else -> null
    }

    /** Carries the [BoardLimit] a step ran into. */
    class LimitException(val limit: BoardLimit) : Exception(limit.name)

    /**
     * Puts [id] at [target]. Widgets it covers move, in reading order: first to the same place
     * relative to where [id] came from ([vacated], when it moved), else to the nearest free place
     * within a row of where they were. When there is none that close, the widget goes just below
     * [target] and the widgets under it move down to make room, so nothing jumps far away.
     */
    private fun place(layout: BoardLayout, id: String, target: BoardRect, vacated: BoardRect?): BoardChange.Done {
        val displaced = layout.ids.filter { it != id && layout.rects.getValue(it).overlaps(target) }
        val result = LinkedHashMap<String, BoardRect>()
        for ((key, r) in layout.rects) if (key != id && key !in displaced) result[key] = r
        result[id] = target
        for (d in displaced) {
            val r = layout.rects.getValue(d)
            val current = BoardLayout(layout.columns, result)
            val swap = vacated?.let { v ->
                BoardRect(v.column + (r.column - target.column), v.row + (r.row - target.row), r.width, r.height)
            }?.takeIf { current.isFree(it) }
            val near = swap ?: nearestFree(r, current, maxRowDistance = 1)
            if (near != null) {
                result[d] = near
            } else {
                pushDown(result, d, BoardRect(r.column.coerceIn(0, layout.columns - r.width), target.bottom, r.width, r.height))
            }
        }
        return BoardChange.Done(BoardLayout(layout.columns, result), displaced.toSet())
    }

    /**
     * Puts [id] at [rect] and moves every widget it then covers down to just below it, and so on
     * down the board. Only ever moves widgets down, so it always ends.
     */
    private fun pushDown(result: MutableMap<String, BoardRect>, id: String, rect: BoardRect) {
        result[id] = rect
        val under = result.entries
            .filter { (key, r) -> key != id && r.overlaps(rect) }
            .sortedWith(compareBy({ it.value.row }, { it.value.column }))
            .map { it.key }
        for (key in under) {
            val r = result.getValue(key)
            if (r.overlaps(rect)) pushDown(result, key, r.copy(row = rect.bottom))
        }
    }

    /** The first place [size] fits, reading from the top left. */
    private fun firstFree(size: BoardSize, layout: BoardLayout): BoardRect {
        var row = 0
        while (true) {
            for (col in 0..layout.columns - size.width) {
                val r = BoardRect(col, row, size.width, size.height)
                if (layout.isFree(r)) return r
            }
            row++
        }
    }

    /**
     * The free place closest to [want] for a widget its size. Moving sideways counts for less than
     * moving up or down, so a widget pushed along a row stays in it when it can; on a tie it moves
     * down rather than up, then the place nearer the top left wins.
     */
    private fun nearestFree(want: BoardRect, layout: BoardLayout): BoardRect =
        nearestFree(want, layout, maxRowDistance = Int.MAX_VALUE) ?: firstFree(want.size, layout)

    /** As above, looking no more than [maxRowDistance] rows up or down; null when nothing is free there. */
    private fun nearestFree(want: BoardRect, layout: BoardLayout, maxRowDistance: Int): BoardRect? {
        if (layout.isFree(want)) return want
        val lastRow = maxOf(layout.rows, want.row) + 1
        var best: BoardRect? = null
        var bestScore = Int.MAX_VALUE
        for (row in 0..lastRow) {
            for (col in 0..layout.columns - want.width) {
                val r = BoardRect(col, row, want.width, want.height)
                if (!layout.isFree(r)) continue
                val dr = row - want.row
                if (abs(dr) > maxRowDistance) continue
                val score = (abs(dr) * 2 + abs(col - want.column)) * 4 + (if (dr < 0) 1 else 0)
                if (score < bestScore) {
                    best = r
                    bestScore = score
                }
            }
        }
        return best
    }
}
