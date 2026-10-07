package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.GridSpot
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderMath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which side of a widget a resize handle moves. */
internal enum class ResizeEdge { LEFT, TOP, RIGHT, BOTTOM, CORNER }

/** A change to the board in progress; [base] is the board it started from, so previews never drift. */
internal sealed interface BoardOp {
    val id: String
    val base: BoardLayout

    /** Where the widget would be (and how large) if the change ended now. */
    val target: BoardRect

    /** Carried with the controller: A picked it up, the D-pad moves it a cell at a time. */
    data class Carry(override val id: String, override val base: BoardLayout, override val target: BoardRect) : BoardOp

    /** Held under a finger. */
    data class Drag(override val id: String, override val base: BoardLayout, override val target: BoardRect) : BoardOp

    /** A handle is being dragged. */
    data class Resize(override val id: String, override val base: BoardLayout, override val target: BoardRect, val edge: ResizeEdge) : BoardOp
}

/** A widget that ran into a limit, shaken along the axis it was pushed in. [nonce] replays it. */
internal data class Bump(val id: String, val horizontal: Boolean, val nonce: Int)

/**
 * What arranging Home is doing: whether it is arranging at all, the change under way and the board
 * it would leave ([preview]), where a finger holds a widget, and the last widget that ran into a
 * limit. One instance per Home; everything here is UI state, saved only when a change is kept.
 */
@Stable
internal class BoardEditor {
    var arranging by mutableStateOf(false)
    var op by mutableStateOf<BoardOp?>(null)
        private set

    /** The board as it would be if [op] ended now; null without a change under way. */
    var preview by mutableStateOf<BoardLayout?>(null)
        private set

    /** Where the finger is, in the board's own pixels, while a widget is held. */
    var finger by mutableStateOf(Offset.Zero)

    /** Where in the held widget the finger took it. */
    var grab = Offset.Zero

    var bump by mutableStateOf<Bump?>(null)
        private set
    private var bumps by mutableIntStateOf(0)

    fun start(op: BoardOp) {
        this.op = op
        preview = op.base
    }

    /** Moves the change on to [target], showing the board [layout] it gives. */
    fun update(target: BoardRect, layout: BoardLayout) {
        op = when (val o = op ?: return) {
            is BoardOp.Carry -> o.copy(target = target)
            is BoardOp.Drag -> o.copy(target = target)
            is BoardOp.Resize -> o.copy(target = target)
        }
        preview = layout
    }

    /** Ends the change; returns the board to keep, or null when nothing changed. */
    fun finish(): BoardLayout? {
        val o = op ?: return null
        val result = preview?.takeIf { it != o.base }
        op = null
        preview = null
        return result
    }

    fun cancel() {
        op = null
        preview = null
    }

    fun bump(id: String, horizontal: Boolean) {
        bump = Bump(id, horizontal, ++bumps)
    }
}

/**
 * The board's cells in pixels: [cellW] by [cellH] with [gapX] and [gapY] between them, from the
 * board's top left corner.
 */
internal class BoardGeometry(val columns: Int, val cellW: Float, val cellH: Float, val gapX: Float, val gapY: Float) {
    val stepX: Float get() = cellW + gapX
    val stepY: Float get() = cellH + gapY

    fun rect(r: BoardRect): Rect = Rect(
        r.column * stepX,
        r.row * stepY,
        r.column * stepX + r.width * cellW + (r.width - 1) * gapX,
        r.row * stepY + r.height * cellH + (r.height - 1) * gapY,
    )

    /** The widget under [p], counting only its own rectangle (not the gaps). */
    fun hit(p: Offset, layout: BoardLayout): String? = layout.rects.entries.firstOrNull { rect(it.value).contains(p) }?.key

    /**
     * The spot a widget [size] would land on with its top left corner at [topLeft]: the nearest
     * cell, kept at [current] until the corner is well past halfway to the next one, so a widget
     * held near a boundary doesn't flicker between two places.
     */
    fun snap(topLeft: Offset, size: BoardSize, current: GridSpot?, lastRow: Int): GridSpot {
        fun axis(pos: Float, step: Float, now: Int?, max: Int): Int {
            val f = pos / step
            val near = f.roundToInt()
            val kept = if (now != null && abs(f - now) < HYSTERESIS) now else near
            return kept.coerceIn(0, max.coerceAtLeast(0))
        }
        return GridSpot(
            axis(topLeft.x, stepX, current?.column, columns - size.width),
            axis(topLeft.y, stepY, current?.row, lastRow),
        )
    }

    /** Cells [delta] pixels covers, rounded to the nearest whole cell. */
    fun cells(delta: Float, step: Float): Int = floor(delta / step + 0.5f).toInt()

    private companion object {
        /** How far past a cell (in cells) a held widget must go before it lands on the next one. */
        const val HYSTERESIS = 0.68f
    }
}

/**
 * Moving widgets by touch. While Home is [arranging], a widget follows the finger as soon as it moves
 * past the touch slop; otherwise a hold lifts it first (and starts arranging), so taps and scrolling
 * behave as always. While held, the board shows where it would land ([BoardEditor.preview]) and
 * scrolls by itself near its top and bottom. Touches where [ignore] says so (a resize handle, a
 * remove badge) are left alone.
 *
 * [origin] is the board's top left corner in this element's pixels (it moves as the board scrolls).
 * The gesture outlives recompositions, so every callback is read as it is now.
 */
@Composable
internal fun Modifier.boardDrag(
    editor: BoardEditor,
    geometry: () -> BoardGeometry,
    layout: () -> BoardLayout,
    origin: () -> Offset,
    arranging: () -> Boolean,
    liftMs: Long,
    viewportHeight: () -> Float,
    bottomInset: Float,
    scrollBy: suspend (Float) -> Float,
    ignore: (Offset) -> Boolean,
    onLift: (String) -> Unit,
    onTarget: () -> Unit,
    onDrop: (BoardLayout?) -> Unit,
    /** The board with the held widget put at a spot, and where it then is; the board's own rules by default. */
    move: (BoardLayout, String, Int, Int) -> Pair<BoardRect, BoardLayout>? = { base, id, column, row ->
        (BoardGrid.move(base, id, column, row) as? BoardChange.Done)?.let { d -> base.rects.getValue(id).let { BoardRect(column, row, it.width, it.height) } to d.layout }
    },
): Modifier {
    val currentMove by rememberUpdatedState(move)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentLayout by rememberUpdatedState(layout)
    val currentOrigin by rememberUpdatedState(origin)
    val currentArranging by rememberUpdatedState(arranging)
    val currentLift by rememberUpdatedState(liftMs)
    val currentHeight by rememberUpdatedState(viewportHeight)
    val currentScroll by rememberUpdatedState(scrollBy)
    val currentIgnore by rememberUpdatedState(ignore)
    val currentOnLift by rememberUpdatedState(onLift)
    val currentOnTarget by rememberUpdatedState(onTarget)
    val currentOnDrop by rememberUpdatedState(onDrop)
    return pointerInput(editor) {
        // Where the finger is on screen (this element's pixels), kept so auto-scroll can move the
        // board under a still finger.
        var screen = Offset.Zero

        fun retarget() {
            val op = editor.op as? BoardOp.Drag ?: return
            editor.finger = screen - currentOrigin()
            val g = currentGeometry()
            val size = op.target.size
            val spot = g.snap(editor.finger - editor.grab, size, op.target.spot, lastRow = op.base.rows)
            if (spot == op.target.spot) return
            val (target, next) = currentMove(op.base, op.id, spot.column, spot.row) ?: return
            if (next == editor.preview && target == op.target) return
            editor.update(target, next)
            currentOnTarget()
        }

        coroutineScope {
            // Scrolls while a held widget is near the top or the bottom; asleep otherwise.
            launch {
                while (true) {
                    snapshotFlow { editor.op is BoardOp.Drag }.first { it }
                    var last = 0L
                    while (editor.op is BoardOp.Drag) {
                        val now = withFrameNanos { it }
                        val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                        last = now
                        val speed = ReorderMath.autoScrollSpeed(screen.y, 0f, currentHeight() - bottomInset, 64.dp.toPx(), 1_200.dp.toPx())
                        if (speed != 0f && dt > 0f && currentScroll(speed * dt) != 0f) retarget()
                    }
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (currentIgnore(down.position)) return@awaitEachGesture
                val at = down.position - currentOrigin()
                val board = currentLayout()
                val id = currentGeometry().hit(at, board) ?: return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                if (currentArranging()) {
                    // Arranging: a widget moves as soon as the finger does. A touch let go first is a tap.
                    var moved = false
                    while (!moved) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        moved = (change.position - down.position).getDistance() > slop
                    }
                } else {
                    // Wait out the hold without taking anything, so taps and scrolls behave as always.
                    val early = withTimeoutOrNull(currentLift) {
                        var ended = false
                        while (!ended) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            ended = change == null || !change.pressed || change.isConsumed || (change.position - down.position).getDistance() > slop
                        }
                        true
                    }
                    if (early != null) return@awaitEachGesture
                }
                val rect = board[id] ?: return@awaitEachGesture
                screen = down.position
                editor.grab = at - currentGeometry().rect(rect).topLeft
                editor.finger = at
                editor.start(BoardOp.Drag(id, board, rect))
                currentOnLift(id)
                var cancelled = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    change.consume()
                    if (!change.pressed) break
                    screen = change.position
                    retarget()
                }
                if (cancelled) {
                    editor.cancel()
                    currentOnDrop(null)
                } else {
                    currentOnDrop(editor.finish())
                }
            }
        }
    }
}

/**
 * Resizing by touch: a handle on [edge] dragged in whole cells. Each new size is shown at once
 * ([BoardEditor.preview], with the widgets in the way moving aside); sizes past the board's edge or
 * a widget's limits stop at the last one that fits, and [onLimit] says so once each time.
 */
@Composable
internal fun Modifier.resizeHandle(
    editor: BoardEditor,
    id: String,
    edge: ResizeEdge,
    geometry: () -> BoardGeometry,
    layout: () -> BoardLayout,
    onStart: () -> Unit,
    onSize: () -> Unit,
    onLimit: () -> Unit,
    onEnd: (BoardLayout?) -> Unit,
    /** The board with the widget given a place and size; the board's own rules by default. */
    resize: (BoardLayout, String, BoardRect) -> BoardLayout? = { base, id, rect -> (BoardGrid.resize(base, id, rect) as? BoardChange.Done)?.layout },
): Modifier {
    val currentResize by rememberUpdatedState(resize)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentLayout by rememberUpdatedState(layout)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnSize by rememberUpdatedState(onSize)
    val currentOnLimit by rememberUpdatedState(onLimit)
    val currentOnEnd by rememberUpdatedState(onEnd)
    return pointerInput(id, edge) {
        var from = BoardRect(0, 0, 1, 1)
        var moved = Offset.Zero
        var limited = false
        detectDragGestures(
            onDragStart = {
                val board = currentLayout()
                from = board[id] ?: return@detectDragGestures
                moved = Offset.Zero
                limited = false
                editor.start(BoardOp.Resize(id, board, from, edge))
                currentOnStart()
            },
            onDrag = { change, delta ->
                change.consume()
                val op = editor.op as? BoardOp.Resize ?: return@detectDragGestures
                moved += delta
                val g = currentGeometry()
                val (wanted, fitted) = resized(from, edge, g.cells(moved.x, g.stepX), g.cells(moved.y, g.stepY), g.columns)
                if (wanted != fitted && !limited) currentOnLimit()
                limited = wanted != fitted
                if (fitted == op.target) return@detectDragGestures
                val result = currentResize(op.base, id, fitted) ?: return@detectDragGestures
                editor.update(fitted, result)
                currentOnSize()
            },
            onDragEnd = { currentOnEnd(editor.finish()) },
            onDragCancel = {
                editor.cancel()
                currentOnEnd(null)
            },
        )
    }
}

/**
 * [from] with its [edge] moved [dx] columns and [dy] rows: what was asked for, and the nearest that
 * fits the board (one to four cells across within its columns, one to three down).
 */
internal fun resized(from: BoardRect, edge: ResizeEdge, dx: Int, dy: Int, columns: Int): Pair<BoardRect, BoardRect> {
    val maxW = BoardGrid.maxWidth(columns)
    val maxH = BoardSize.MAX_HEIGHT
    var wanted = from
    var fitted = from
    if (edge == ResizeEdge.RIGHT || edge == ResizeEdge.CORNER) {
        val w = from.width + dx
        wanted = wanted.copy(width = w)
        fitted = fitted.copy(width = w.coerceIn(1, minOf(maxW, columns - from.column)))
    }
    if (edge == ResizeEdge.LEFT) {
        val col = from.column + dx
        wanted = wanted.copy(column = col, width = from.right - col)
        val c = col.coerceIn(maxOf(0, from.right - maxW), from.right - 1)
        fitted = fitted.copy(column = c, width = from.right - c)
    }
    if (edge == ResizeEdge.BOTTOM || edge == ResizeEdge.CORNER) {
        val h = from.height + dy
        wanted = wanted.copy(height = h)
        fitted = fitted.copy(height = h.coerceIn(1, maxH))
    }
    if (edge == ResizeEdge.TOP) {
        val row = from.row + dy
        wanted = wanted.copy(row = row, height = from.bottom - row)
        val r = row.coerceIn(maxOf(0, from.bottom - maxH), from.bottom - 1)
        fitted = fitted.copy(row = r, height = from.bottom - r)
    }
    return wanted to fitted
}

/**
 * Whether [edge] of [rect] can move at all on a board [columns] wide: grow (room and size to spare)
 * or shrink (bigger than a cell). A handle that can do neither is drawn dimmed.
 */
internal fun canResize(rect: BoardRect, edge: ResizeEdge, columns: Int): Boolean {
    val maxW = BoardGrid.maxWidth(columns)
    val wider = rect.width < maxW
    val taller = rect.height < BoardSize.MAX_HEIGHT
    return when (edge) {
        ResizeEdge.RIGHT -> (wider && rect.right < columns) || rect.width > 1
        ResizeEdge.LEFT -> (wider && rect.column > 0) || rect.width > 1
        ResizeEdge.BOTTOM -> taller || rect.height > 1
        ResizeEdge.TOP -> (taller && rect.row > 0) || rect.height > 1
        ResizeEdge.CORNER -> canResize(rect, ResizeEdge.RIGHT, columns) || canResize(rect, ResizeEdge.BOTTOM, columns)
    }
}

/** How long a hold takes to lift a widget when Home isn't being arranged. */
internal fun boardLiftMs(longPressMs: Int): Long = ReorderDefaults.liftMs(longPressMs.toLong())
