package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.isAchievements
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridCell
import io.github.matiyaaa.fuse.ui.designsystem.focus.SpatialSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.mouseHover
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Crossfade
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.animate
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineDp
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.fuseline.slideInVertically
import io.github.matiyaaa.fuse.ui.fuseline.slideOutVertically
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.offersToAdd
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The room Undo and Reset take above the board while it is arranged. */
private val ARRANGE_TOOLS_ROOM = 64.dp

/** A board as it was before a change, for Undo (no board saved yet is the board Home came with). */
private class KeptBoard(val board: List<HomeWidget>?)

/** Narrower than this (and held upright), the board uses two columns so items stay big enough to read. */
private val NARROW_BELOW = 600.dp

/** Thinner than this, two columns whichever way the screen is held. */
private val TINY_BELOW = 420.dp

/** Shorter than this, the board's gutters and gaps tighten (a two-screen handheld's lower screen). */
private val SMALL_BELOW = 520.dp

/** Widget corners, as a share of the theme's tile corner: large widgets would look bloated with a tile's. */
private const val WIDGET_CORNER = 0.6f

/**
 * Channel Mode: Home as a board of widgets you arrange yourself, like a phone's home screen. Each
 * widget is one to four cells across and one to three down, has its own place on the grid, and is
 * designed for its size. The room behind the board takes the focused widget's game.
 *
 * Holding a widget (or holding confirm, or Options, Arrange Home) arranges the board: the grid's free
 * cells show, widgets wobble a little, and each gets a badge to take it off. By touch, drag a widget
 * anywhere on the grid (the widgets in the way move aside, and the cells it will land on light up),
 * or drag the chosen widget's handles to resize it. With the controller, A picks a widget up and the
 * D-pad carries it a cell at a time; holding Options (X) turns the D-pad into resizing.
 */
@Composable
internal fun ChannelBoard(app: AppState, space: BoardSpace, page: Int, pageKey: String, active: Boolean, editor: BoardEditor, paging: HomePaging) {
    val store = app.store
    val prefs by store.prefs.collectAsState()

    val widgets = space.shown(space.config(prefs), page)
    // Every change made while arranging can be taken back, newest first; the floating Undo and
    // Reset at the top right ([ArrangeTools]) are reached by moving up past the board's top row.
    val history = remember { mutableStateListOf<KeptBoard>() }
    var onTools by remember { mutableStateOf<Int?>(null) }
    // Whose arrangement this is, this device's or the profile's, where that can be chosen.
    val ownHome: Boolean? = space.own
    fun setOwnHome(own: Boolean) = space.setOwn(own)
    // Where the board is kept once per screen, which look is being arranged.
    val look = space.look
    val arranging = editor.arranging
    val op = editor.op
    val sel = rememberRouteState(app.navigator, if (page == 0) "${space.key}.board" else "${space.key}.board.$pageKey") { SpatialSelection() }
    // Where each carousel widget stands, kept while the board is away so it comes back where it was.
    val carousels = rememberRouteState(app.navigator, "${space.key}.carousels.$pageKey") { HashMap<String, CarouselState>() }
    fun carouselOf(w: HomeWidget): CarouselState? = if (space.carousel(w)) carousels.getOrPut(w.id) { CarouselState() } else null
    fun shownAt(w: HomeWidget?): Int = w?.let { carouselOf(it)?.index } ?: 0
    // After a change is kept the board's order follows its new reading order; the same widget stays chosen.
    var reselect by remember { mutableStateOf<String?>(null) }
    reselect?.let { id ->
        val i = widgets.indexOfFirst { it.id == id }
        if (i >= 0) {
            sel.index = i
            reselect = null
        }
    }
    sel.clamp(if (arranging) widgets.size + 1 else widgets.size)
    val current = widgets.getOrNull(sel.index)
    val reveal = rememberReveal()
    val haptics = app.platform.haptics
    val router = LocalInputRouter.current
    val held by router.heldModifier.collectAsState()

    // Holding Options while arranging turns the chosen widget's look into resizing, after a beat so
    // a quick press for the menu doesn't flash it.
    val holding = arranging && held == NavAction.CONTEXT && op == null
    var resizeLook by remember { mutableStateOf(false) }
    PageEffect(holding) {
        if (holding) {
            delay(RESIZE_LOOK_MS)
            resizeLook = true
        } else {
            resizeLook = false
        }
    }

    // The room behind the board takes the focused widget's game: for a carousel, the one in front.
    val shown = current?.let { carouselOf(it)?.index } ?: 0
    val hero = space.hero(current, shown)
    PageEffect(current?.id, shown, hero, active) {
        if (!active) return@PageEffect
        app.hero = hero
        space.chosen(current)
        space.chosenPage(page)
    }
    val turns = current?.let { carouselOf(it) }?.takeIf { it.count > 1 } != null
    PageEffect(arranging, op is BoardOp.Carry, resizeLook, onTools, active, paging.count, turns, look?.editing) {
        if (!active) return@PageEffect
        val pages = (if (turns) listOf(Hint(HintButton.PAGE_PREV, ""), Hint(HintButton.PAGE_NEXT, "Browse")) else emptyList()) +
            if (paging.count > 1) listOf(Hint(HintButton.RIGHT_STICK, "Pages")) else emptyList()
        app.hints = when {
            arranging && onTools != null -> listOf(Hint(HintButton.CONFIRM, when (onTools) { 0 -> "Undo"; 1 -> space.resetLabel(page); 2 -> "New page"; else -> look?.let { "Arrange the ${it.labels[1 - it.editing]} look" } ?: if (ownHome == true) "${space.name} on all devices" else "This device's own ${space.name}" }), Hint(HintButton.BACK, "Done"))
            op is BoardOp.Carry -> listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Put down"), Hint(HintButton.BACK, "Cancel"))
            resizeLook -> listOf(Hint(HintButton.DPAD, "Resize"), Hint(HintButton.HOLD_OPTIONS, "Let go when done"))
            arranging -> listOf(Hint(HintButton.CONFIRM, "Pick up"), Hint(HintButton.HOLD_OPTIONS, "Resize"), Hint(HintButton.OPTIONS, "Edit"), Hint(HintButton.BACK, "Done"))
            widgets.isEmpty() -> listOf(Hint(HintButton.CONFIRM, "Add ${space.item}s"), Hint(HintButton.OPTIONS, "Options")) + pages
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Arrange"), Hint(HintButton.OPTIONS, "Options")) + pages
        }
    }
    // Leaving the board puts everything down where it was.
    PageEffect(app.focusZone) {
        if (app.focusZone != FocusZone.CONTENT) editor.cancel()
    }

    fun keepForUndo() {
        if (editor.arranging) history.add(KeptBoard(space.config(store.prefs.value).storedBoard(page)))
    }
    fun undo() {
        val before = history.removeLastOrNull() ?: return app.toasts.show("Nothing to undo")
        store.updatePrefs { p -> space.keep(p, space.config(p).withBoard(page, before.board)) }
        app.platform.sounds.play(io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue.BACK)
    }
    fun reset() {
        // The first page goes back as it came; a page added later is cleared.
        app.confirm = ConfirmSpec(
            title = space.resetTitle(page),
            message = space.resetMessage(page),
            confirmLabel = space.resetLabel(page),
        ) {
            keepForUndo()
            space.reset(page)
            app.toasts.show(space.resetDone(page))
        }
    }

    /**
     * Keeps [layout] (a board [columns] wide): each widget's place on this board, the new size of the
     * one [resized], and the board's order in reading order, so a board of another width that was never
     * arranged follows this one. Widgets not shown keep their places, after the others.
     */
    fun save(layout: BoardLayout, columns: Int, resized: String? = null) {
        keepForUndo()
        val reading = layout.ids.withIndex().associate { it.value to it.index }
        store.updatePrefs { p ->
            val c = space.config(p)
            val board = c.boardWidgets(page).map { w ->
                val r = layout[w.id] ?: return@map w
                val sized = if (w.id == resized) w.copy(width = r.width, height = r.height) else w
                // A packed board keeps only the order: places follow from it at any width.
                sized.copy(spots = if (space.packed) emptyMap() else w.spots + (columns to r.spot))
            }.sortedWith(compareBy({ reading[it.id] ?: Int.MAX_VALUE }, { it.order }))
            space.keep(p, c.withBoard(page, board.mapIndexed { i, w -> w.copy(order = i) }))
        }
    }
    fun saveBoard(change: (List<HomeWidget>) -> List<HomeWidget>) {
        keepForUndo()
        store.updatePrefs { p -> space.config(p).let { c -> space.keep(p, c.withBoard(page, change(c.boardWidgets(page)).mapIndexed { i, w -> w.copy(order = i) })) } }
    }
    fun remove(w: HomeWidget) {
        saveBoard { list -> space.removed(list, w) }
        app.toasts.show("Took ${space.title(w)} off ${space.name}. Add it back with Add ${space.item}")
    }
    fun add(a: Addable) {
        keepForUndo()
        store.updatePrefs { p -> space.keep(p, space.add(space.config(p), page, a)) }
        reselect = a.widget.id
        app.toasts.show("Added ${a.title}")
    }
    fun addPicker() {
        val missing = space.addable(space.config(prefs), page)
        if (missing.isEmpty()) {
            app.toasts.show(if (paging.count > 1) "Every ${space.item} is on this page already" else "Every ${space.item} is on ${space.name} already")
            return
        }
        app.choice = ChoiceSpec(
            title = "Add a ${space.item}",
            icon = FuseIcons.CirclePlus,
            message = "It takes the first free place on the board. Then drag it anywhere, or resize it by its handles",
            options = missing.map { a -> MenuAction(a.key, a.title, a.icon, detail = a.detail, onSelect = { app.choice = null; add(a) }) },
        )
    }
    fun stopArranging() {
        editor.cancel()
        editor.arranging = false
        space.arrangingEnded()
        history.clear()
        onTools = null
        sel.clamp(widgets.size)
    }

    fun open(w: HomeWidget) = space.open(w, shownAt(w))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns only on a screen held upright (a phone) or a very thin one. A small screen
        // that is wider than tall (a two-screen handheld's lower screen) keeps the full board, its
        // cells as tall as a handheld's.
        val narrow = maxWidth < NARROW_BELOW && (maxWidth < maxHeight || maxWidth < TINY_BELOW)
        val small = maxHeight < SMALL_BELOW || maxWidth < NARROW_BELOW
        val columns = space.columns(narrow, small, maxWidth)
        val gutter = if (narrow || small) Space.gutterCompact else Space.gutter
        val gapX = if (narrow || small) Space.m else Space.l
        // Rows stay clear of the spark under a focused widget.
        val gapY = Size.sparkClearance
        val cellW = (maxWidth - gutter * 2 - gapX * (columns - 1)) / columns
        val cellH = space.cellHeight(cellW, gapX, gapY, narrow)
        val density = LocalDensity.current
        val packed = space.packed
        val committed = remember(widgets, columns, packed) {
            BoardGrid.layout(widgets.map { BoardGrid.Item(it.id, if (packed) space.allowed(it.boardSize, columns) else it.boardSize, if (packed) null else it.spots[columns]) }, columns)
        }
        // A packed board whose rows all stop short of the edge (small squares in an odd number of
        // columns) is centred, so the room left over is shared by both sides.
        val spare = if (packed) (columns - (committed.rects.values.maxOfOrNull { it.right } ?: columns)).coerceAtLeast(0) else 0
        val geometry = with(density) { BoardGeometry(columns, cellW.toPx(), cellH.toPx(), gapX.toPx(), gapY.toPx(), offsetX = spare * (cellW + gapX).toPx() / 2) }
        val shown = editor.preview ?: committed
        // Arranging: the first free place adds a widget (hidden while something is being changed).
        val addRect = if (arranging && op == null) BoardGrid.layout(
            committed.rects.map { (id, r) -> BoardGrid.Item(id, r.size, r.spot) } + BoardGrid.Item(ADD_KEY, space.addSize, null),
            columns,
        )[ADD_KEY] else null
        val usedRows = maxOf(shown.rows, op?.target?.bottom ?: 0, addRect?.bottom ?: 0)
        // While arranging one spare row shows below, so there is always somewhere to put a widget.
        val rows = if (arranging) usedRows + 1 else usedRows
        val boardHeight = cellH * rows + gapY * (rows - 1).coerceAtLeast(0)
        val cells = widgets.map { w -> shown[w.id]?.toCell() ?: GridCell(0, 0, 1) } + listOfNotNull(addRect?.toCell())
        val scroll = rememberScrollState()
        val topPad = with(density) { Space.l.toPx() }
        val gutterPx = with(density) { gutter.toPx() }
        val motion = Fuse.motion
        // What covers the board's bottom: the hints, and while arranging the toolbar above them.
        val bottomCover by fuselineDp(
            Size.hintHeight + Space.l + if (arranging) ARRANGE_BAR else 0.dp,
            motion.tween(Durations.BASE),
            label = "boardBottom",
        )
        val bottomPad = with(density) { bottomCover.toPx() }
        // The focused widget (or the one being carried) always comes fully into view.
        KeepCellInView(
            scroll,
            {
                val r = (op as? BoardOp.Carry)?.target ?: cells.getOrNull(sel.index)?.let { BoardRect(it.column, it.row, it.columnSpan, it.rowSpan) }
                r?.let { geometry.rect(it).translate(0f, topPad) }
            },
            enabled = op !is BoardOp.Drag,
            margin = topPad,
            bottomMargin = bottomPad,
        )

        val start = remember { TimeSource.Monotonic.markNow() }
        var lastResize by remember { mutableStateOf(-1_000L) }
        InputLayer(
            enabled = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen,
            longPress = true,
            holdModifier = if (arranging) NavAction.CONTEXT else null,
        ) { e ->
            val w = widgets.getOrNull(sel.index)
            val carried = editor.op as? BoardOp.Carry
            val direction = e.action == NavAction.LEFT || e.action == NavAction.RIGHT || e.action == NavAction.UP || e.action == NavAction.DOWN
            val tools = onTools
            // The right stick, [ and ]: the next or previous page (not while a widget is carried).
            if ((e.action == NavAction.PAGE_PREVIOUS || e.action == NavAction.PAGE_NEXT) && carried == null) {
                onTools = null
                return@InputLayer if (paging.turn(if (e.action == NavAction.PAGE_NEXT) 1 else -1)) NavResult.MOVED else NavResult.BLOCKED
            }
            if (arranging && tools != null && carried == null && e.modifier == null) {
                return@InputLayer when (e.action) {
                    NavAction.LEFT -> if (tools > 0) { onTools = tools - 1; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.RIGHT -> if (tools < (if (ownHome != null || look != null) 3 else 2)) { onTools = tools + 1; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.DOWN -> { onTools = null; NavResult.MOVED }
                    NavAction.UP -> NavResult.BLOCKED
                    NavAction.SELECT -> {
                        when (tools) {
                            0 -> undo()
                            1 -> reset()
                            2 -> { onTools = null; paging.add() }
                            else -> if (look != null) look.pick(1 - look.editing) else setOwnHome(ownHome != true)
                        }
                        NavResult.ACTIVATED
                    }
                    NavAction.BACK -> { stopArranging(); NavResult.CONSUMED }
                    else -> NavResult.CONSUMED
                }
            }
            when {
                // Options held: the D-pad resizes the chosen widget, one cell a step, never faster
                // than a step every [RESIZE_REPEAT_MS] however long a direction is held.
                e.modifier == NavAction.CONTEXT && direction -> {
                    if (w == null || carried != null) return@InputLayer NavResult.BLOCKED
                    val now = start.elapsedNow().inWholeMilliseconds
                    if (e.isRepeat && now - lastResize < RESIZE_REPEAT_MS) return@InputLayer NavResult.CONSUMED
                    lastResize = now
                    val rect = committed[w.id] ?: return@InputLayer NavResult.BLOCKED
                    val horizontal = e.action == NavAction.LEFT || e.action == NavAction.RIGHT
                    val next = space.resizeStep(rect, e.action, columns)
                    val change = next?.let { if (space.packed) BoardGrid.resizePacked(committed, w.id, it.size, space.maxHeight) else BoardGrid.resize(committed, w.id, it) } as? BoardChange.Done
                    if (change == null) {
                        editor.bump(w.id, horizontal)
                        NavResult.BLOCKED
                    } else {
                        save(change.layout, columns, resized = w.id)
                        reselect = w.id
                        NavResult.MOVED
                    }
                }
                carried != null -> when (e.action) {
                    NavAction.LEFT, NavAction.RIGHT, NavAction.UP, NavAction.DOWN -> if (space.packed) {
                        // A packed board: the system trades places along the reading order.
                        val next = BoardGrid.carry(editor.preview ?: carried.base, carried.id, e.action)
                        if (next == null) {
                            editor.bump(carried.id, horizontal = e.action == NavAction.LEFT || e.action == NavAction.RIGHT)
                            NavResult.BLOCKED
                        } else {
                            editor.update(next.rects.getValue(carried.id), next)
                            NavResult.MOVED
                        }
                    } else {
                        val t = carried.target
                        val col = t.column + when (e.action) { NavAction.LEFT -> -1; NavAction.RIGHT -> 1; else -> 0 }
                        val row = t.row + when (e.action) { NavAction.UP -> -1; NavAction.DOWN -> 1; else -> 0 }
                        val change = if (row > carried.base.rows) null else BoardGrid.move(carried.base, carried.id, col, row) as? BoardChange.Done
                        if (change == null) {
                            editor.bump(carried.id, horizontal = e.action == NavAction.LEFT || e.action == NavAction.RIGHT)
                            NavResult.BLOCKED
                        } else {
                            editor.update(t.copy(column = col, row = row), change.layout)
                            NavResult.MOVED
                        }
                    }
                    NavAction.SELECT, NavAction.REORDER -> {
                        editor.finish()?.let { save(it, columns) }
                        reselect = carried.id
                        haptics.drop()
                        NavResult.ACTIVATED
                    }
                    NavAction.BACK -> {
                        editor.cancel()
                        NavResult.CONSUMED
                    }
                    else -> NavResult.CONSUMED
                }
                else -> when (e.action) {
                    NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, cells).let { r ->
                        when {
                            // Past the top row while arranging: Undo and Reset.
                            r != NavResult.MOVED && arranging && e.action == NavAction.UP -> { onTools = 0; NavResult.MOVED }
                            // Past the board's side: the page beside it, when there is one.
                            r != NavResult.MOVED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT) && !e.isRepeat &&
                                paging.turn(if (e.action == NavAction.RIGHT) 1 else -1, fromEdge = true) -> NavResult.MOVED
                            else -> r
                        }
                    }
                    NavAction.SELECT -> when {
                        // An empty page: A fills it.
                        widgets.isEmpty() && !arranging -> { editor.arranging = true; addPicker(); NavResult.ACTIVATED }
                        arranging && sel.index >= widgets.size -> { addPicker(); NavResult.ACTIVATED }
                        arranging && w != null -> {
                            committed[w.id]?.let { editor.start(BoardOp.Carry(w.id, committed, it)) }
                            haptics.lift()
                            NavResult.ACTIVATED
                        }
                        else -> { w?.let(::open); NavResult.ACTIVATED }
                    }
                    NavAction.REORDER -> {
                        if (w != null) {
                            editor.arranging = true
                            committed[w.id]?.let { editor.start(BoardOp.Carry(w.id, committed, it)) }
                            haptics.lift()
                        }
                        NavResult.ACTIVATED
                    }
                    // The triggers (L2, R2) turn the focused widget's carousel; the bumpers stay on the tabs.
                    NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                        val turn = w?.let { carouselOf(it) }?.takeIf { it.count > 1 && !arranging } ?: return@InputLayer NavResult.IGNORED
                        // The app's feedback gives the sound and the tick, once. Held at an end, the
                        // repeats change nothing: one bump on the first press, then quiet.
                        if (turn.step(if (e.action == NavAction.PAGE_DOWN) 1 else -1, nudge = !e.isRepeat)) NavResult.MOVED else NavResult.BLOCKED
                    }
                    NavAction.CONTEXT -> { app.openContextMenu(space.menu(w, editor.arranging, boardActions(app, space, editor, w, ::addPicker, ::stopArranging, ::remove, committed, page, paging))); NavResult.ACTIVATED }
                    NavAction.BACK -> if (arranging) { stopArranging(); NavResult.CONSUMED } else NavResult.IGNORED
                    else -> NavResult.IGNORED
                }
            }
        }

        // Badges and handles that sit on widgets while arranging: touches there are theirs, not a drag's.
        val controls = remember { mutableStateMapOf<String, Rect>() }
        var containerTopLeft by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(0f) }
        // One shared beat for the arranging wobble, read only while drawing, and only running while
        // arranging: a beat left running would wake every frame for nothing.
        val wobbling = arranging && op == null && !motion.reduced
        val beat = remember { FuselineValue(0f) }
        PageEffect(wobbling) {
            while (wobbling) {
                beat.snapTo(0f)
                beat.animateTo(1f, tween(WOBBLE_MS, easing = Curves.Linear))
            }
        }
        val wells by fuselineFloat(if (arranging) 1f else 0f, motion.fade(Durations.BASE), label = "wells")
        space.Provide {
            Column(Modifier.fillMaxSize()) {
                // While arranging the board moves down to make room for Undo and Reset above it.
                val toolsRoom by fuselineDp(if (arranging) ARRANGE_TOOLS_ROOM else 0.dp, motion.tween(Durations.BASE), label = "toolsRoom")
                Spacer(Modifier.height(space.top + toolsRoom))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            containerTopLeft = it.positionInRoot()
                            viewport = it.size.height.toFloat()
                        }
                        .fadingEdges(scroll, top = Space.l, bottom = bottomCover)
                        // A tap on the board's empty space puts a carried widget down, then stops arranging.
                        .pointerInput(arranging) {
                            if (arranging) detectTapGestures {
                                if (editor.op is BoardOp.Carry) editor.finish()?.let { save(it, columns) } else stopArranging()
                            }
                        }
                        .boardDrag(
                            editor,
                            geometry = { geometry },
                            layout = { committed },
                            origin = { Offset(gutterPx, topPad - scroll.value) },
                            arranging = { editor.arranging },
                            liftMs = boardLiftMs(prefs.input.longPressMs),
                            viewportHeight = { viewport },
                            bottomInset = bottomPad,
                            scrollBy = { scroll.scrollBy(it) },
                            ignore = { at -> controls.values.any { it.contains(containerTopLeft + at) } },
                            onLift = { id ->
                                app.focusZone = FocusZone.CONTENT
                                sel.index = widgets.indexOfFirst { it.id == id }.coerceAtLeast(0)
                                editor.arranging = true
                                haptics.lift()
                            },
                            onTarget = { haptics.slot() },
                            onDrop = { layout ->
                                val id = widgets.getOrNull(sel.index)?.id
                                layout?.let { save(it, columns) }
                                reselect = id
                                haptics.drop()
                            },
                            move = if (packed) { base, id, column, row ->
                                BoardGrid.drop(base, id, column, row).layout.let { it.rects.getValue(id) to it }
                            } else { base, id, column, row ->
                                (BoardGrid.move(base, id, column, row) as? BoardChange.Done)?.let { d -> base.rects.getValue(id).let { BoardRect(column, row, it.width, it.height) } to d.layout }
                            },
                        )
                        .verticalScroll(scroll),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = gutter, end = gutter, top = Space.l, bottom = Size.hintHeight + Space.xxl + if (arranging) ARRANGE_BAR else 0.dp)
                            .height(boardHeight),
                    ) {
                        val fraction = Fuse.geometry.tileCornerFraction * WIDGET_CORNER
                        val shape = remember(fraction) { SquircleShape.fraction(fraction) }
                        GridWells(
                            geometry = geometry,
                            rows = rows,
                            occupied = shown,
                            target = op?.target,
                            cornerFraction = fraction,
                            shown = wells,
                            modifier = Modifier.fillMaxSize(),
                        )
                        widgets.forEachIndexed { i, w ->
                            val rect = shown[w.id]
                            if (rect != null) key(w.id) {
                                val dragged = (op as? BoardOp.Drag)?.takeIf { it.id == w.id }
                                val px = if (dragged != null) {
                                    val r = geometry.rect(rect)
                                    Rect(editor.finger - editor.grab, r.size)
                                } else {
                                    geometry.rect(rect)
                                }
                                val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                                val carousel = carouselOf(w)
                                BoardItem(
                                    carousel = carousel,
                                    widget = w,
                                    size = rect.size,
                                    rect = px,
                                    reveal = { m -> m.reveal(reveal, 1 + rect.row) },
                                    glow = space.glow(w, carousel?.index ?: 0),
                                    face = { space.Face(w, rect.size, carousel?.index ?: 0) },
                                    selected = selected,
                                    lifted = dragged != null || (op is BoardOp.Carry && op.id == w.id),
                                    following = dragged != null,
                                    arranging = arranging,
                                    wobble = { if (wobbling) beat.value else null },
                                    bump = editor.bump?.takeIf { it.id == w.id },
                                    shape = shape,
                                    cornerFraction = fraction,
                                    onClick = {
                                        app.focusZone = FocusZone.CONTENT
                                        when {
                                            arranging -> sel.index = i
                                            // A tap on a widget that plays a game shows it first; the rest open
                                            // at once, and so does anything clicked with a mouse.
                                            sel.index == i || !space.firstTapChooses(w) || router.mouse -> { sel.index = i; open(w) }
                                            else -> sel.index = i
                                        }
                                    },
                                    onHover = {
                                        if (!arranging && dragged == null) {
                                            sel.index = i
                                            app.focusZone = FocusZone.CONTENT
                                        }
                                    },
                                    chrome = {
                                        if (arranging && dragged == null) {
                                        val resizingHere = (op as? BoardOp.Resize)?.takeIf { it.id == w.id }
                                        if (op == null && !resizeLook) {
                                            RemoveBadge(
                                                space.title(w),
                                                Modifier.align(Alignment.TopStart).offset(-BADGE_OUT, -BADGE_OUT)
                                                    .onGloballyPositioned { controls["x:${w.id}"] = Rect(it.positionInRoot(), it.size.toSize()) },
                                            ) { remove(w) }
                                        }
                                        if (op is BoardOp.Carry && op.id == w.id) {
                                            Badge("Moving", icon = FuseIcons.Move, modifier = Modifier.align(Alignment.TopEnd).padding(Space.s))
                                        }
                                        // Handles on the chosen widget, or the one being resized.
                                        if ((selected && op == null && !resizeLook) || resizingHere != null) {
                                            ResizeHandles(
                                                name = space.title(w),
                                                rect = rect,
                                                columns = columns,
                                                active = resizingHere?.edge,
                                                handle = { edge ->
                                                    Modifier.resizeHandle(
                                                        editor, w.id, edge,
                                                        geometry = { geometry },
                                                        layout = { committed },
                                                        onStart = {
                                                            app.focusZone = FocusZone.CONTENT
                                                            sel.index = i
                                                            haptics.lift()
                                                        },
                                                        onSize = { haptics.slot() },
                                                        onLimit = {
                                                            haptics.reject()
                                                            editor.bump(w.id, horizontal = edge == ResizeEdge.LEFT || edge == ResizeEdge.RIGHT || edge == ResizeEdge.CORNER)
                                                        },
                                                        onEnd = { layout ->
                                                            layout?.let { save(it, columns, resized = w.id) }
                                                            reselect = w.id
                                                            haptics.drop()
                                                        },
                                                        resize = if (packed) { base, id, r ->
                                                            (BoardGrid.resizePacked(base, id, space.allowed(r.size, columns), space.maxHeight) as? BoardChange.Done)?.layout
                                                        } else { base, id, r -> (BoardGrid.resize(base, id, r) as? BoardChange.Done)?.layout },
                                                        maxHeight = space.maxHeight,
                                                    )
                                                },
                                                onPlaced = { edge, r -> if (r == null) controls.remove("r$edge:${w.id}") else controls["r$edge:${w.id}"] = r },
                                                maxHeight = space.maxHeight,
                                            )
                                        }
                                        if (selected && resizeLook) ResizeFrame(rect, columns, shape, space.maxHeight)
                                        }
                                    },
                                )
                            }
                        }
                        if (addRect != null) {
                            AddTile(
                                selected = sel.index == widgets.size && app.focusZone == FocusZone.CONTENT,
                                shape = shape,
                                modifier = Modifier.boardPlace(geometry.rect(addRect), animate = true),
                                label = "Add ${space.item}",
                                onClick = { sel.index = widgets.size; addPicker() },
                            )
                        }
                    }
                }
            }
        }
        // While arranging, a toolbar floats over the board's bottom edge (above the hints), so
        // starting to arrange never moves the widget under the finger. It steps aside while a widget
        // is moved or resized (the hints say how), so it never hides the widget being changed.
        Appear(
            arranging && op == null && !resizeLook,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = gutter, end = gutter, bottom = Size.hintHeight + Space.s),
            enter = fadeIn(motion.enter(Durations.BASE)) + slideInVertically(motion.enter(Durations.BASE)) { it / 2 },
            exit = fadeOut(motion.exit(Durations.FAST)) + slideOutVertically(motion.exit(Durations.FAST)) { it / 2 },
        ) {
            ArrangeBar(
                compact = narrow || small,
                item = space.item,
                name = space.name,
                onAdd = ::addPicker,
                onDone = ::stopArranging,
                ownHome = ownHome,
                onOwnHome = { setOwnHome(it) },
                look = look,
            )
        }
        // Undo and Reset float at the top right while arranging, out of the board's way.
        Appear(
            arranging && op == null && !resizeLook,
            // In the room the board leaves above itself while arranging (under the top line on Home).
            modifier = Modifier.align(Alignment.TopEnd).padding(top = space.top, end = gutter),
            enter = fadeIn(motion.enter(Durations.BASE)) + slideInVertically(motion.enter(Durations.BASE)) { -it / 2 },
            exit = fadeOut(motion.exit(Durations.FAST)) + slideOutVertically(motion.exit(Durations.FAST)) { -it / 2 },
        ) {
            ArrangeTools(
                focused = if (app.focusZone == FocusZone.CONTENT) onTools else null,
                canUndo = history.isNotEmpty(),
                resetLabel = if (page == 0) "Reset" else "Clear",
                onUndo = { onTools = 0; undo() },
                onReset = { onTools = 1; reset() },
                onNewPage = { onTools = null; paging.add() },
                ownHome = ownHome,
                onOwnHome = { onTools = 3; setOwnHome(ownHome != true) },
                look = look,
                onLook = { onTools = 3; look?.let { it.pick(1 - it.editing) } },
            )
        }
        // Controls of widgets that are gone, or of a board no longer arranged, catch no touches.
        PageEffect(arranging, widgets.map { it.id }, sel.index) {
            if (!arranging) controls.clear()
            val live = widgets.map { it.id }.toSet()
            controls.keys.retainAll { k -> k.substringAfter(':') in live }
        }
    }
}

/** A board's own options: arranging, and for a chosen item moving, resizing and removing it; pages. */
private fun boardActions(
    app: AppState,
    space: BoardSpace,
    editor: BoardEditor,
    w: HomeWidget?,
    addPicker: () -> Unit,
    stopArranging: () -> Unit,
    remove: (HomeWidget) -> Unit,
    board: BoardLayout,
    page: Int,
    paging: HomePaging,
): List<MenuAction> = listOfNotNull(
    if (!editor.arranging) {
        MenuAction("arrange", "Arrange ${space.name}", FuseIcons.Grid, detail = "Move, resize, add and remove ${space.item}s, on as many pages as you like", onSelect = {
            app.closeOverlays()
            editor.arranging = true
        })
    } else {
        null
    },
    w?.let {
        MenuAction("move", "Move", FuseIcons.Move, detail = "Then drag it anywhere, or carry it with the D-pad", onSelect = {
            app.closeOverlays()
            editor.arranging = true
            board[it.id]?.let { r -> editor.start(BoardOp.Carry(it.id, board, r)) }
        })
    },
    w?.let {
        val s = board[it.id]?.size ?: it.boardSize
        MenuAction("resize", "Resize", FuseIcons.Scaling, detail = "Now ${s.width} by ${s.height}. Hold Options and use the D-pad, or drag a handle", onSelect = {
            app.closeOverlays()
            editor.arranging = true
            app.toasts.show("Hold Options and press a direction to resize ${space.title(it)}")
        })
    },
    w?.let { MenuAction("remove", "Remove from ${space.name}", FuseIcons.Minus, destructive = true, onSelect = { app.closeOverlays(); remove(it) }) },
    if (editor.arranging) MenuAction("add", "Add a ${space.item}", FuseIcons.CirclePlus, onSelect = { app.closeOverlays(); addPicker() }) else null,
    MenuAction("page.new", "New page", FuseIcons.CopyPlus, detail = "A page of its own for more ${space.item}s. The right stick or a swipe turns pages", onSelect = { app.closeOverlays(); paging.add() }),
    if (page > 0) MenuAction("page.remove", "Remove this page", FuseIcons.Trash, destructive = true, onSelect = { app.closeOverlays(); paging.remove(page) }) else null,
    if (editor.arranging) MenuAction("done", "Done arranging", FuseIcons.Check, onSelect = { app.closeOverlays(); stopArranging() }) else null,
)

/**
 * One widget on the board: its face in a tile at [rect] (in the board's pixels), and while arranging
 * its [chrome] (remove badge, handles). Lifted (held by a finger or carried by the controller) it
 * floats over a shadow, a little larger; arranging, it wobbles on its own beat. A widget that ran
 * into a limit ([bump]) shakes along the axis it was pushed in.
 */
@Composable
private fun BoardItem(
    carousel: CarouselState?,
    widget: HomeWidget,
    size: BoardSize,
    rect: Rect,
    reveal: (Modifier) -> Modifier,
    glow: androidx.compose.ui.graphics.Color,
    face: @Composable () -> Unit,
    selected: Boolean,
    lifted: Boolean,
    following: Boolean,
    arranging: Boolean,
    wobble: () -> Float?,
    bump: Bump?,
    shape: Shape,
    cornerFraction: Float,
    onClick: () -> Unit,
    onHover: () -> Unit,
    chrome: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val motion = Fuse.motion
    val lift by fuselineFloat(if (lifted) 1f else 0f, motion.focusSpring(), label = "lift")
    val phase = remember(widget.id) { (widget.id.hashCode() and 0xFF) / 255f }
    // Wider widgets wobble less, so a large one doesn't swing its corners about.
    val swing = WOBBLE_DEGREES / size.width.coerceAtLeast(1)
    val shake = remember { FuselineValue(0f) }
    PageEffect(bump?.nonce) {
        if (bump == null) return@PageEffect
        shake.snapTo(0f)
        shake.animateTo(1f, tween(SHAKE_MS, easing = Curves.Linear))
        shake.snapTo(0f)
    }
    val shadow = Fuse.colors.shadow
    Box(
        reveal(Modifier.boardPlace(rect, animate = !following))
            .zIndex(if (lifted) 2f else 0f)
            // Pointing at it with the mouse highlights it, as the D-pad would; one click then opens it.
            .mouseHover(!arranging) { onHover() }
            .graphicsLayer {
                val t = wobble()
                if (t != null && !lifted) rotationZ = sin((t + phase) * 2f * PI.toFloat()) * swing
                // Lifted, it grows by the same few points whatever its size.
                val s = 1f + 2f * LIFT_GROW.toPx() / this.size.width.coerceAtLeast(1f) * lift
                scaleX = s
                scaleY = s
                if (lift > 0f) {
                    this.shape = shape
                    shadowElevation = Elevation.tileFocused.shadow.toPx() * 2.4f * lift
                    spotShadowColor = shadow
                    ambientShadowColor = shadow
                }
                // A short, damped shake along the axis the widget was pushed in.
                val k = shake.value
                if (k > 0f && bump != null) {
                    val d = sin(k * PI.toFloat() * 6f) * exp(-k * 3.5f) * SHAKE.toPx()
                    if (bump.horizontal) translationX = d else translationY = d
                }
            },
    ) {
        Tile(
            selected = selected && !lifted,
            modifier = Modifier.fillMaxSize(),
            // While arranging, the spark under the chosen widget would sit on its bottom handle.
            showSpark = !arranging,
            cornerFraction = cornerFraction,
            shape = shape,
            glow = glow,
            maxGrow = FOCUS_GROW,
            // A carousel draws its own cards (the next one peeking): no box around the empty parts.
            surface = carousel == null,
            onClick = onClick,
        ) {
            // A new shape gets its own face, crossfading from the old one.
            Crossfade(FaceSize.of(size), animationSpec = motion.fade(Durations.BASE), label = "face") { _ ->
                CompositionLocalProvider(LocalCarousel provides carousel) { face() }
            }
        }
        if (arranging) chrome()
    }
}

/** "1 game", "12 games". */
internal fun gamesText(count: Int): String = "$count ${if (count == 1) "game" else "games"}"

/**
 * Places a widget at [rect] (in the board's pixels), springing to a new place or size when the
 * board reflows around a move or a resize, the way a phone's home screen does; [animate] off for the
 * widget under a finger, which must never lag behind it.
 */
@Composable
private fun Modifier.boardPlace(rect: Rect, animate: Boolean): Modifier {
    val x = remember { FuselineValue(rect.left) }
    val y = remember { FuselineValue(rect.top) }
    val w = remember { FuselineValue(rect.width) }
    val h = remember { FuselineValue(rect.height) }
    val spec = remember { spring(dampingRatio = 0.78f, stiffness = 420f) }
    PageEffect(rect, animate) {
        if (!animate) {
            x.snapTo(rect.left)
            y.snapTo(rect.top)
            w.snapTo(rect.width)
            h.snapTo(rect.height)
            return@PageEffect
        }
        launch { x.animateTo(rect.left, spec) }
        launch { y.animateTo(rect.top, spec) }
        launch { w.animateTo(rect.width, spec) }
        h.animateTo(rect.height, spec)
    }
    return layout { measurable, _ ->
        val width = w.value.roundToInt().coerceAtLeast(1)
        val height = h.value.roundToInt().coerceAtLeast(1)
        val p = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { p.place(x.value.roundToInt(), y.value.roundToInt()) }
    }
}

/** Brings the focused widget fully into view as the selection moves, unless a finger holds one. */
@Composable
private fun KeepCellInView(scroll: ScrollState, rect: () -> Rect?, enabled: Boolean, margin: Float, bottomMargin: Float) {
    val current by rememberUpdatedState(rect)
    val on by rememberUpdatedState(enabled)
    val below by rememberUpdatedState(bottomMargin)
    val spec = Fuse.motion.followScroll()
    PageEffect(scroll) {
        snapshotFlow { current() to scroll.viewportSize }.collect { (r, viewport) ->
            if (r == null || !on || viewport <= 0) return@collect
            val top = scroll.value.toFloat()
            val bottom = top + viewport
            val target = when {
                r.top - margin < top -> (r.top - margin).coerceAtLeast(0f)
                r.bottom + below > bottom -> r.bottom + below - viewport
                else -> return@collect
            }
            scroll.fuselineScrollTo(target.roundToInt().coerceIn(0, scroll.maxValue), spec)
        }
    }
}

/** A small object resting on the room: clipped to [shape], over a tile's contact shadow, with a lit top edge. */
@Composable
internal fun Modifier.lifted(shape: Shape): Modifier {
    val c = Fuse.colors
    val shadow = c.shadow
    return graphicsLayer {
        this.shape = shape
        clip = true
        shadowElevation = Elevation.tile.shadow.toPx() * 2
        spotShadowColor = shadow
        ambientShadowColor = shadow.copy(alpha = shadow.alpha * 0.5f)
    }.lightEdge(shape, Elevation.tile.edgeAlpha(c.isDark))
}

/** A system as a small card in its colour, with its logo (or short name) in the colour that reads on art. */
@Composable
internal fun SystemChip(s: PlatformCard, shape: Shape, modifier: Modifier) {
    val c = Fuse.colors
    val accent = s.platform.accent.toColor()
    // A lit gradient from the system's colour into a deeper shade of it, like the system tiles.
    val deep = c.shadow.copy(alpha = 1f)
    val brush = remember(accent, deep) { Brush.linearGradient(listOf(lerp(accent, c.onArt, 0.06f), lerp(accent, deep, 0.42f))) }
    Box(modifier.clip(shape).background(brush).lightEdge(shape, Elevation.raised.edgeAlpha(true)), contentAlignment = Alignment.Center) {
        val name: @Composable () -> Unit = { FText(s.platform.shortName, Fuse.type.bodyStrong, color = c.onArt, maxLines = 1) }
        if (s.art.logo != null) {
            Artwork(s.art.logo, Modifier.fillMaxSize().padding(Space.s), contentScale = ContentScale.Fit, tint = c.onArt, fallback = name)
        } else {
            name()
        }
    }
}

/** How far a widget grows a side when focused, and when lifted to move. */
private val FOCUS_GROW = 6.dp
private val LIFT_GROW = 10.dp

/** How far the remove badge's target reaches out past the widget's corner. */
private val BADGE_OUT = 17.dp

/** The wobble while arranging: one beat this long, at most this many degrees (for a one-cell widget). */
private const val WOBBLE_MS = 520
private const val WOBBLE_DEGREES = 0.9f

/** A widget that can't go further shakes this far, for this long. */
private val SHAKE = 7.dp
private const val SHAKE_MS = 360

/** How long Options is held before the chosen widget shows it is being resized. */
private const val RESIZE_LOOK_MS = 150L

/** The quickest a held direction resizes, one step each. */
private const val RESIZE_REPEAT_MS = 200L

/** Stands for the Add tile when finding its place. */
private const val ADD_KEY = "\u0000add"
