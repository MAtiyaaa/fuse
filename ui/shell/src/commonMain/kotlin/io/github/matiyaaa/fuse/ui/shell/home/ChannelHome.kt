package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.DragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridCell
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.SpatialSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.followScroll
import io.github.matiyaaa.fuse.ui.designsystem.focus.packBoard
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Columns of the board on a landscape screen, and on a narrow one (a phone held upright). */
private const val BOARD_COLUMNS = 4
private const val BOARD_COLUMNS_NARROW = 2

/** Narrower than this, the board uses [BOARD_COLUMNS_NARROW] so widgets stay big enough to read. */
private val NARROW_BELOW = 600.dp

/** Widget corners, as a share of the theme's tile corner: large widgets would look bloated with a tile's. */
private const val WIDGET_CORNER = 0.6f

/**
 * Channel Mode: Home as a board of widgets you arrange yourself, like a phone's home screen. Each
 * widget is one to four cells across and one to three down, and is designed for its size: a list
 * of games shows its first game's art with the next covers beside it, the clock draws a dial once
 * it has room, storage a ring. The room behind the board takes the focused widget's game.
 *
 * Holding a widget (or holding confirm, or Options, Arrange Home) arranges the board: widgets
 * wobble a little, show a corner to resize them by and a badge to take them off, and an Add tile
 * appears. By touch, drag a widget to move it (the others spring to their new places) or drag its
 * corner to resize it, cell by cell, with a tick for each. With the controller, A picks a widget up
 * and the D-pad moves it, and Options, Resize grows or shrinks it with the D-pad.
 */
@Composable
fun ChannelHome(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.library.home.collectAsState()
    val cartridge by store.cartridge.status.collectAsState()

    // Without a single game, system or app the board would be a wall of empty widgets: Home says
    // how to begin instead (after a moment for the library to load, shown as the board's outline).
    val nothing = feed.systems.isEmpty() && feed.pinnedApps.isEmpty() && feed.continuePlaying.isEmpty()
    val loading = rememberHomeLoading(app, feed, nothing)
    if (nothing) {
        if (loading) HomeSkeleton(app, channels = true) else HomeEmpty(app)
        return
    }

    val widgets = prefs.home.boardWidgets().filter { onBoard(it, app, prefs, cartridge) }
    val drag = rememberDragReorderState()
    // While a widget is held, the board shows the order it would land in.
    val shown = drag.arrange(widgets) { it.id }
    val sel = rememberRouteState(app.navigator, "home.board") { SpatialSelection() }
    var arranging by remember { mutableStateOf(false) }
    // The controller carries the selected widget (moving it), or resizes it.
    var carrying by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    // A size shown before it is kept: while a corner is dragged, or the D-pad resizes.
    var preview by remember { mutableStateOf<Pair<String, BoardSize>?>(null) }
    sel.clamp(if (arranging) widgets.size + 1 else widgets.size)
    val current = widgets.getOrNull(sel.index)
    val reveal = rememberReveal()
    val haptics = app.platform.haptics

    val systems = rememberSystems(app)
    LaunchedEffect(current?.id, systems) {
        val game = current?.let { firstGame(it.kind, feed) }
        app.hero = game?.room(systems[game.platformId])
    }
    LaunchedEffect(arranging, carrying, resizing) {
        app.hints = when {
            resizing -> listOf(Hint(HintButton.DPAD, "Resize"), Hint(HintButton.CONFIRM, "Keep"), Hint(HintButton.BACK, "Cancel"))
            carrying -> listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Put down"))
            arranging -> listOf(Hint(HintButton.CONFIRM, "Pick up"), Hint(HintButton.OPTIONS, "Edit"), Hint(HintButton.BACK, "Done"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Arrange"), Hint(HintButton.OPTIONS, "Options"))
        }
    }
    // Leaving the board puts everything down.
    LaunchedEffect(app.focusZone) {
        if (app.focusZone != FocusZone.CONTENT) {
            carrying = false
            resizing = false
            preview = null
        }
    }

    /** Saves the whole board (every widget, shown here or not) after [change], renumbered in order. */
    fun saveBoard(change: (List<HomeWidget>) -> List<HomeWidget>) {
        store.updatePrefs { p -> p.copy(home = p.home.copy(board = change(p.home.boardWidgets()).mapIndexed { i, w -> w.copy(order = i) })) }
    }
    fun moveTo(from: Int, to: Int) {
        if (from !in widgets.indices || to !in widgets.indices || from == to) return
        val ids = widgets.map { it.id }
        saveBoard { HomeArrange.moveOne(it, ids, ids[from], to) }
        sel.index = to
    }
    fun resize(id: String, size: BoardSize) = saveBoard { list -> list.map { if (it.id == id) it.copy(width = size.width, height = size.height) else it } }
    fun remove(w: HomeWidget) {
        saveBoard { list -> list.filterNot { it.id == w.id } }
        app.toasts.show("Took ${w.kind.title()} off Home. Add it back with Add widget")
    }
    fun add(kind: WidgetKind) {
        saveBoard { list -> list + HomeWidget(kind.name.lowercase(), kind, list.size) }
        sel.index = widgets.size
        app.toasts.show("Added ${kind.title()}")
    }
    fun addPicker() {
        val missing = WidgetKind.entries.filter { k -> prefs.home.boardWidgets().none { it.kind == k } && app.offers(k) }
        if (missing.isEmpty()) {
            app.toasts.show("Every widget is on Home already")
            return
        }
        app.choice = ChoiceSpec(
            title = "Add a widget",
            icon = FuseIcons.CirclePlus,
            message = "It goes at the end of the board. Drag its corner, or use Options, Resize, to make it bigger",
            options = missing.map { k -> MenuAction("add.$k", k.title(), widgetIcon(k), onSelect = { app.choice = null; add(k) }) },
        )
    }
    fun stopArranging() {
        arranging = false
        carrying = false
        resizing = false
        preview = null
        sel.clamp(widgets.size)
    }

    fun menu(w: HomeWidget?) = ContextMenuSpec(
        title = w?.kind?.title() ?: "Home",
        subtitle = "Home",
        actions = listOfNotNull(
            if (!arranging) {
                MenuAction("arrange", "Arrange Home", FuseIcons.Grid, detail = "Move, resize, add and remove widgets", onSelect = {
                    app.closeOverlays()
                    arranging = true
                })
            } else {
                null
            },
            w?.let {
                MenuAction("move", "Move", FuseIcons.Move, detail = "Then drag it, or use the D-pad", onSelect = {
                    app.closeOverlays()
                    arranging = true
                    carrying = true
                    drag.arm(it.id)
                })
            },
            w?.let {
                val s = it.boardSize
                MenuAction("resize", "Resize", FuseIcons.Maximize, detail = "Now ${s.width} by ${s.height}. Use the D-pad, or drag its corner", onSelect = {
                    app.closeOverlays()
                    arranging = true
                    resizing = true
                    preview = it.id to s
                })
            },
            w?.let { MenuAction("remove", "Remove from Home", FuseIcons.Minus, destructive = true, onSelect = { app.closeOverlays(); remove(it) }) },
            if (arranging) MenuAction("add", "Add a widget", FuseIcons.CirclePlus, onSelect = { app.closeOverlays(); addPicker() }) else null,
            if (arranging) MenuAction("done", "Done arranging", FuseIcons.Check, onSelect = { app.closeOverlays(); stopArranging() }) else null,
        ) + if (arranging) emptyList() else app.homeStyleActions(),
    )

    fun open(w: HomeWidget) = app.openWidget(w.kind, feed, firstGame(w.kind, feed))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_BELOW
        val columns = if (narrow) BOARD_COLUMNS_NARROW else BOARD_COLUMNS
        val gutter = if (narrow) Space.gutterCompact else Space.gutter
        val gapX = if (narrow) Space.m else Space.l
        // Rows stay clear of the spark under a focused widget.
        val gapY = Size.sparkClearance
        val cellW = (maxWidth - gutter * 2 - gapX * (columns - 1)) / columns
        val cellH = (cellW * if (narrow) 0.86f else 0.6f).coerceIn(CELL_MIN, CELL_MAX)
        val sizeOf: (HomeWidget) -> BoardSize = { w -> (preview?.takeIf { it.first == w.id }?.second ?: w.boardSize).fit(columns) }
        val cells = remember(shown, columns, preview, arranging) {
            packBoard(shown.map { sizeOf(it).let { s -> s.width to s.height } } + if (arranging) listOf(1 to 1) else emptyList(), columns)
        }
        val density = LocalDensity.current
        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }
        val gapXpx = with(density) { gapX.toPx() }
        val gapYpx = with(density) { gapY.toPx() }
        fun rectOf(c: GridCell) = Rect(
            c.column * (cellWpx + gapXpx),
            c.row * (cellHpx + gapYpx),
            c.column * (cellWpx + gapXpx) + c.columnSpan * cellWpx + (c.columnSpan - 1) * gapXpx,
            c.row * (cellHpx + gapYpx) + c.rowSpan * cellHpx + (c.rowSpan - 1) * gapYpx,
        )
        val rows = cells.maxOfOrNull { it.row + it.rowSpan } ?: 0
        val boardHeight = cellH * rows + gapY * (rows - 1).coerceAtLeast(0)
        val scroll = rememberScrollState()
        val topPad = with(density) { Space.l.toPx() }
        val motion = Fuse.motion
        // What covers the board's bottom: the hints, and while arranging the toolbar above them.
        val bottomCover by animateDpAsState(
            Size.hintHeight + Space.l + if (arranging) ARRANGE_BAR else 0.dp,
            motion.tween(Durations.BASE),
            label = "boardBottom",
        )
        val bottomPad = with(density) { bottomCover.toPx() }
        // The focused widget always comes fully into view, clear of the hints and the toolbar.
        KeepCellInView(
            scroll,
            { cells.getOrNull(sel.index)?.let(::rectOf)?.translate(0f, topPad) },
            enabled = drag.heldKey == null,
            margin = topPad,
            bottomMargin = bottomPad,
        )

        InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen, longPress = true) { e ->
            val w = widgets.getOrNull(sel.index)
            when {
                resizing && w != null -> {
                    val now = preview?.takeIf { it.first == w.id }?.second ?: w.boardSize
                    val next = when (e.action) {
                        NavAction.LEFT -> now.copy(width = now.width - 1)
                        NavAction.RIGHT -> now.copy(width = now.width + 1)
                        NavAction.UP -> now.copy(height = now.height - 1)
                        NavAction.DOWN -> now.copy(height = now.height + 1)
                        NavAction.SELECT, NavAction.REORDER -> {
                            resize(w.id, now.fit(columns))
                            resizing = false
                            preview = null
                            return@InputLayer NavResult.ACTIVATED
                        }
                        NavAction.BACK -> {
                            resizing = false
                            preview = null
                            return@InputLayer NavResult.CONSUMED
                        }
                        else -> return@InputLayer NavResult.CONSUMED
                    }.fit(columns)
                    if (next == now) NavResult.BLOCKED else { preview = w.id to next; NavResult.MOVED }
                }
                carrying -> when (e.action) {
                    NavAction.LEFT -> if (sel.index > 0) { moveTo(sel.index, sel.index - 1); NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.RIGHT -> if (sel.index < widgets.lastIndex) { moveTo(sel.index, sel.index + 1); NavResult.MOVED } else NavResult.BLOCKED
                    // Up and down take the place of the widget above or below, the same one plain moves land on.
                    NavAction.UP, NavAction.DOWN -> {
                        val probe = SpatialSelection(sel.index)
                        if (probe.move(e.action, cells.take(widgets.size)) == NavResult.MOVED) {
                            moveTo(sel.index, probe.index)
                            NavResult.MOVED
                        } else {
                            NavResult.BLOCKED
                        }
                    }
                    NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> {
                        carrying = false
                        drag.arm(null)
                        NavResult.CONSUMED
                    }
                    else -> NavResult.CONSUMED
                }
                else -> when (e.action) {
                    NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, cells)
                    NavAction.SELECT -> when {
                        arranging && sel.index >= widgets.size -> { addPicker(); NavResult.ACTIVATED }
                        arranging -> { carrying = true; NavResult.ACTIVATED }
                        else -> { w?.let(::open); NavResult.ACTIVATED }
                    }
                    NavAction.REORDER -> {
                        if (w != null) {
                            arranging = true
                            carrying = true
                        }
                        NavResult.ACTIVATED
                    }
                    NavAction.CONTEXT -> { app.openContextMenu(menu(w)); NavResult.ACTIVATED }
                    NavAction.BACK -> if (arranging) { stopArranging(); NavResult.CONSUMED } else NavResult.IGNORED
                    else -> NavResult.IGNORED
                }
            }
        }

        // Corners and badges that sit on widgets while arranging: touches there are theirs, not a drag's.
        val controls = remember { mutableStateMapOf<String, Rect>() }
        val time = rememberClockText(prefs.clock24h)
        // One shared beat for the arranging wobble, read only while drawing, and only running while
        // arranging: a beat left running would wake every frame for nothing.
        val wobbling = arranging && !motion.reduced
        val beat = remember { Animatable(0f) }
        LaunchedEffect(wobbling) {
            while (wobbling) {
                beat.snapTo(0f)
                beat.animateTo(1f, tween(WOBBLE_MS, easing = LinearEasing))
            }
        }
        CompositionLocalProvider(LocalHomeTime provides time) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(Size.hudHeight))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fadingEdges(scroll, top = Space.l, bottom = bottomCover)
                        // A tap on the board's empty space puts a carried widget down, then stops arranging.
                        .pointerInput(arranging, carrying) {
                            if (arranging) detectTapGestures { if (carrying) carrying = false else stopArranging() }
                        }
                        .dragReorder(
                            drag,
                            visibleKeys = { widgets.map { it.id } },
                            scrollBy = { scroll.scrollBy(it) },
                            // While arranging a touch moves a widget at once; otherwise a hold does.
                            longPressMs = if (arranging) 0L else ReorderDefaults.liftMs(prefs.input.longPressMs.toLong()),
                            endInset = Size.hintHeight,
                            ignore = { at -> controls.values.any { it.contains(at) } },
                            onLift = { key ->
                                app.focusZone = FocusZone.CONTENT
                                sel.index = widgets.indexOfFirst { it.id == key }.coerceAtLeast(0)
                                arranging = true
                                carrying = false
                                haptics.lift()
                            },
                            onTarget = { haptics.slot() },
                            // A hold let go where it started leaves the board arranging, the widget chosen.
                            onHoldReleased = { key -> sel.index = widgets.indexOfFirst { it.id == key }.coerceAtLeast(0) },
                            onDrop = { key, to ->
                                moveTo(widgets.indexOfFirst { it.id == key }, to)
                                sel.index = to
                                haptics.drop()
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
                        shown.forEachIndexed { at, w ->
                            key(w.id) {
                                val i = widgets.indexOf(w)
                                val cell = cells.getOrNull(at)
                                if (cell != null) {
                                    BoardItem(
                                        widget = w,
                                        size = sizeOf(w),
                                        rect = rectOf(cell),
                                        reveal = { m -> m.reveal(reveal, 1 + cell.row) },
                                        feed = feed,
                                        cartridge = cartridge,
                                        clock24h = prefs.clock24h,
                                        selected = i == sel.index && app.focusZone == FocusZone.CONTENT,
                                        carrying = carrying,
                                        held = drag.heldKey == w.id,
                                        arranging = arranging,
                                        wobble = { if (wobbling) beat.value else null },
                                        resizingHere = preview?.first == w.id,
                                        shape = shape,
                                        cornerFraction = fraction,
                                        drag = drag,
                                        onControl = { name, r -> if (r == null) controls.remove("$name:${w.id}") else controls["$name:${w.id}"] = r },
                                        onClick = {
                                            app.focusZone = FocusZone.CONTENT
                                            when {
                                                arranging -> sel.index = i
                                                // Widgets that play a game show it first; the rest open at once.
                                                sel.index == i || w.kind !in playWidgets -> { sel.index = i; open(w) }
                                                else -> sel.index = i
                                            }
                                        },
                                        onRemove = { remove(w) },
                                        resizeDrag = Modifier.resizeDrag(
                                            start = { w.boardSize.fit(columns) },
                                            cell = { Offset(cellWpx + gapXpx, cellHpx + gapYpx) },
                                            columns = columns,
                                            onStart = {
                                                app.focusZone = FocusZone.CONTENT
                                                sel.index = i
                                                haptics.lift()
                                            },
                                            onSize = { s ->
                                                preview = w.id to s
                                                haptics.slot()
                                            },
                                            onEnd = { s ->
                                                if (s != w.boardSize.fit(columns)) resize(w.id, s)
                                                preview = null
                                                haptics.drop()
                                            },
                                            onCancel = { preview = null },
                                        ),
                                    )
                                }
                            }
                        }
                        // Arranging: the last place adds a widget.
                        val addCell = cells.getOrNull(shown.size)
                        if (arranging && addCell != null) {
                            AddTile(
                                selected = sel.index == widgets.size && app.focusZone == FocusZone.CONTENT,
                                shape = shape,
                                modifier = Modifier.boardPlace(rectOf(addCell), animate = true),
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
        AnimatedVisibility(
            arranging && !carrying && !resizing && drag.heldKey == null && preview == null,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = gutter, end = gutter, bottom = Size.hintHeight + Space.s),
            enter = fadeIn(motion.enter(Durations.BASE)) + slideInVertically(motion.enter(Durations.BASE)) { it / 2 },
            exit = fadeOut(motion.exit(Durations.FAST)) + slideOutVertically(motion.exit(Durations.FAST)) { it / 2 },
        ) {
            ArrangeBar(
                compact = narrow,
                onAdd = ::addPicker,
                onDone = ::stopArranging,
            )
        }
        // Controls of widgets that are gone, or of a board no longer arranged, catch no touches.
        LaunchedEffect(arranging, widgets.map { it.id }) {
            val live = if (arranging) widgets.flatMap { listOf("x:${it.id}", "r:${it.id}") }.toSet() else emptySet()
            controls.keys.retainAll(live)
        }
    }
}

/**
 * One widget on the board: its face in a tile at [rect], and while arranging its remove badge and
 * resize corner. Lifted (held by a finger or carried by the controller) it floats over a shadow
 * with a well marking where it will land; arranging, it wobbles on its own beat.
 */
@Composable
private fun BoardItem(
    widget: HomeWidget,
    size: BoardSize,
    rect: Rect,
    reveal: (Modifier) -> Modifier,
    feed: HomeFeed,
    cartridge: CartridgeStatus,
    clock24h: Boolean,
    selected: Boolean,
    carrying: Boolean,
    held: Boolean,
    arranging: Boolean,
    wobble: () -> Float?,
    resizingHere: Boolean,
    shape: Shape,
    cornerFraction: Float,
    drag: DragReorderState,
    onControl: (String, Rect?) -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    resizeDrag: Modifier,
) {
    val motion = Fuse.motion
    val carried = selected && carrying
    val lift by animateFloatAsState(if (carried || held) 1f else 0f, motion.focusSpring(), label = "lift")
    val well by animateFloatAsState(if (carried || held) 1f else 0f, motion.fade(Durations.FAST), label = "well")
    val phase = remember(widget.id) { (widget.id.hashCode() and 0xFF) / 255f }
    // Wider widgets wobble less, so a large one doesn't swing its corners about.
    val swing = WOBBLE_DEGREES / size.width.coerceAtLeast(1)
    Box(
        reveal(Modifier.boardPlace(rect, animate = !held))
            // Where the widget will land stays marked while it floats.
            .dropWell({ well }, shape)
            .reorderItem(drag, widget.id, liftScale = 1f, shape = shape)
            .zIndex(if (carried || held) 1f else 0f)
            .graphicsLayer {
                val t = wobble()
                if (t != null && !held && !carried) rotationZ = sin((t + phase) * 2f * PI.toFloat()) * swing
                // Lifted, it grows by the same few points whatever its size.
                val s = 1f + 2f * LIFT_GROW.toPx() / this.size.width.coerceAtLeast(1f) * lift
                scaleX = s
                scaleY = s
            },
    ) {
        Tile(
            selected = selected,
            modifier = Modifier.fillMaxSize(),
            cornerFraction = cornerFraction,
            shape = shape,
            glow = widgetGlow(widget.kind, feed, cartridge),
            maxGrow = FOCUS_GROW,
            onClick = onClick,
        ) {
            // A new shape gets its own face, crossfading from the old one.
            Crossfade(FaceSize.of(size), animationSpec = motion.fade(Durations.BASE), label = "face") { _ ->
                BoardFace(widget.kind, size, feed, cartridge, clock24h)
            }
        }
        if (arranging && !held) {
            RemoveBadge(
                widget.kind.title(),
                // The disc's centre sits just inside the corner, clear of the widget's name.
                Modifier.align(Alignment.TopStart).offset(-BADGE_OUT, -BADGE_OUT)
                    .onGloballyPositioned { onControl("x", Rect(it.positionInRoot(), it.size.toSize())) },
                onRemove,
            )
            ResizeGrip(
                widget.kind.title(),
                active = resizingHere,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .onGloballyPositioned { onControl("r", Rect(it.positionInRoot(), it.size.toSize())) }
                    .then(resizeDrag),
            )
        }
    }
}

/** Whether [w] is shown on the board now: hidden, unavailable or switched-off widgets wait off it. */
private fun onBoard(w: HomeWidget, app: AppState, prefs: UiPrefs, cartridge: CartridgeStatus): Boolean =
    w.visible && app.offers(w.kind) &&
        (w.kind != WidgetKind.CARTRIDGE_DOWNLOADS || cartridge.installed) &&
        (w.kind != WidgetKind.COLLECTIONS || prefs.collectionsEnabled)

private fun firstGame(kind: WidgetKind, feed: HomeFeed): GameCard? = boardGames(kind, feed).firstOrNull()

/** A widget's glow when focused: its game's colour for game widgets, else its own (the accent for neutral ones). */
@Composable
private fun widgetGlow(kind: WidgetKind, feed: HomeFeed, cartridge: CartridgeStatus) =
    firstGame(kind, feed)?.accent?.toColor() ?: widgetTint(kind, feed, cartridge).let { if (it == Fuse.colors.text) Fuse.colors.accent else it }

/** Widgets whose confirm plays a game, so a first tap only shows it. */
private val playWidgets = setOf(
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.PINNED_GAMES, WidgetKind.CURRENT_GAME,
)

/** "1 game", "12 games". */
internal fun gamesText(count: Int): String = "$count ${if (count == 1) "game" else "games"}"

/**
 * Places a widget at [rect] (in the board's pixels), springing to a new place or size when the
 * board reflows around a move or a resize, the way a phone's home screen does; [animate] off for the
 * widget under a finger, which must never lag behind it.
 */
@Composable
private fun Modifier.boardPlace(rect: Rect, animate: Boolean): Modifier {
    val x = remember { Animatable(rect.left) }
    val y = remember { Animatable(rect.top) }
    val w = remember { Animatable(rect.width) }
    val h = remember { Animatable(rect.height) }
    val spec = remember { spring<Float>(dampingRatio = 0.78f, stiffness = 420f) }
    LaunchedEffect(rect, animate) {
        if (!animate) {
            x.snapTo(rect.left)
            y.snapTo(rect.top)
            w.snapTo(rect.width)
            h.snapTo(rect.height)
            return@LaunchedEffect
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

/**
 * Resizing by touch: dragging the corner changes the size in whole cells (one [cell] apart in each
 * direction), never past the board's [columns] or three rows. [onSize] fires for each new size,
 * [onEnd] with the size the finger left it at.
 */
private fun Modifier.resizeDrag(
    start: () -> BoardSize,
    cell: () -> Offset,
    columns: Int,
    onStart: () -> Unit,
    onSize: (BoardSize) -> Unit,
    onEnd: (BoardSize) -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(columns) {
    var from = BoardSize(1, 1)
    var shown = from
    var moved = Offset.Zero
    detectDragGestures(
        onDragStart = {
            from = start()
            shown = from
            moved = Offset.Zero
            onStart()
        },
        onDrag = { change, delta ->
            change.consume()
            moved += delta
            val step = cell()
            val next = BoardSize(
                from.width + (moved.x / step.x).roundToInt(),
                from.height + (moved.y / step.y).roundToInt(),
            ).fit(columns)
            if (next != shown) {
                shown = next
                onSize(next)
            }
        },
        onDragEnd = { onEnd(shown) },
        onDragCancel = onCancel,
    )
}

/** Brings the focused widget fully into view as the selection moves, unless a finger holds one. */
@Composable
private fun KeepCellInView(scroll: ScrollState, rect: () -> Rect?, enabled: Boolean, margin: Float, bottomMargin: Float) {
    val current by rememberUpdatedState(rect)
    val on by rememberUpdatedState(enabled)
    val below by rememberUpdatedState(bottomMargin)
    val spec = Fuse.motion.followScroll()
    LaunchedEffect(scroll) {
        snapshotFlow { current() to scroll.viewportSize }.collect { (r, viewport) ->
            if (r == null || !on || viewport <= 0) return@collect
            val top = scroll.value.toFloat()
            val bottom = top + viewport
            val target = when {
                r.top - margin < top -> (r.top - margin).coerceAtLeast(0f)
                r.bottom + below > bottom -> r.bottom + below - viewport
                else -> return@collect
            }
            scroll.animateScrollTo(target.roundToInt().coerceIn(0, scroll.maxValue), spec)
        }
    }
}

/**
 * The toolbar floating over the board while it is arranged: what to do, and Add widget and Done
 * for touch (the controller has the same in its hints). On a narrow screen only the buttons.
 */
@Composable
private fun ArrangeBar(compact: Boolean, onAdd: () -> Unit, onDone: () -> Unit) {
    val c = Fuse.colors
    Panel(raised = true, shape = RoundedCornerShape(Radius.pill)) {
        Row(Modifier.padding(start = if (compact) Space.s else Space.xl, end = Space.s, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                Column(Modifier.widthIn(max = ARRANGE_TEXT)) {
                    FText("Arranging Home", Fuse.type.bodyStrong, maxLines = 1)
                    FText("Drag a widget to move it, or its corner to resize it", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
                Spacer(Modifier.width(Space.xl))
            }
            FuseButton("Add widget", selected = false, onClick = onAdd, kind = ButtonKind.SECONDARY, icon = FuseIcons.Plus)
            Spacer(Modifier.width(Space.s))
            FuseButton("Done", selected = false, onClick = onDone, kind = ButtonKind.PRIMARY)
        }
    }
}

/** The badge that takes a widget off Home while arranging: a minus in a disc, in a target big enough to tap. */
@Composable
private fun RemoveBadge(name: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        modifier
            .size(Size.touch * 0.8f)
            .semantics { contentDescription = "Remove $name" }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Size.badge + Space.xxs)
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                    shadowElevation = Elevation.raised.shadow.toPx()
                }
                .background(c.surfaceOverlay)
                .border(Size.stroke, c.hairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(FuseIcons.Minus, size = Size.iconS, tint = c.text)
        }
    }
}

/**
 * The corner a widget is resized by: a short arc along its bottom right corner, in the accent while
 * in use, inside a finger-sized target.
 */
@Composable
private fun ResizeGrip(name: String, active: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    val tone by animateFloatAsState(if (active) 1f else 0f, Fuse.motion.fade(Durations.FAST), label = "grip")
    val idle = c.onArt
    val lit = c.accent
    val shadow = c.shadow
    Canvas(
        modifier
            .size(Size.touch)
            .semantics { contentDescription = "Resize $name" },
    ) {
        val inset = 7.dp.toPx()
        val r = 18.dp.toPx()
        val w = 3.5.dp.toPx()
        val right = size.width - inset
        val bottom = size.height - inset
        val path = Path().apply {
            moveTo(right, bottom - r)
            quadraticTo(right, bottom, right - r, bottom)
        }
        // A soft shadow under the arc so it reads on any art.
        drawPath(path, shadow.copy(alpha = 0.45f), style = Stroke(w + 3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, lerp(idle, lit, tone), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** The last place on a board being arranged: a dashed outline with a plus that adds a widget. */
@Composable
private fun AddTile(selected: Boolean, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val edge = if (selected) c.accent else c.hairlineStrong
    Box(
        modifier
            .clip(shape)
            .background(c.surfaceDim.copy(alpha = if (c.isDark) 0.5f else 0.7f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { contentDescription = "Add a widget" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@Canvas)) }
            drawPath(
                path,
                edge,
                style = Stroke(
                    width = (if (selected) Size.focusStroke else Size.stroke).toPx() * 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(Space.s.toPx(), (Space.xs + Space.xxs).toPx())),
                ),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(Size.chip).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Plus, size = Size.iconM, tint = if (selected) c.accent else c.textMuted)
            }
            Spacer(Modifier.height(Space.s))
            FText("Add widget", Fuse.type.label, color = if (selected) c.text else c.textMuted, maxLines = 1)
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

/** The room the arranging toolbar takes over the board's bottom, and the widest its words get. */
private val ARRANGE_BAR = 72.dp
private val ARRANGE_TEXT = 360.dp

/** How far the remove badge's target reaches out past the widget's corner. */
private val BADGE_OUT = 17.dp

/** The smallest and largest cell height, so widgets stay readable on a handheld and sane on a TV. */
private val CELL_MIN = 96.dp
private val CELL_MAX = 240.dp

/** The wobble while arranging: one beat this long, at most this many degrees (for a one-cell widget). */
private const val WOBBLE_MS = 520
private const val WOBBLE_DEGREES = 0.9f
