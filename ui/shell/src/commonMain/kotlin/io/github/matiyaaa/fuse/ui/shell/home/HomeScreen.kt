package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.DragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderMath
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.carried
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderHandle
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.dismissFromContinue
import io.github.matiyaaa.fuse.ui.shell.app.gameConfirmLabel
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.openApp
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.apps.appMenu
import io.github.matiyaaa.fuse.ui.shell.components.AppTile
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.GameWideTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.StageInfo
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.library.CollectionTile
import io.github.matiyaaa.fuse.ui.shell.systems.moveSystem
import io.github.matiyaaa.fuse.ui.shell.systems.moveSystemBy
import io.github.matiyaaa.fuse.ui.shell.systems.systemMenu

@Composable
fun HomeScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    if (prefs.home.mode == HomeMode.CHANNELS) ChannelHome(app) else FlowHome(app)
}

/** Flips Home between Flow and Channels, and says which one it is now. */
fun AppState.switchHomeStyle() {
    val next = if (store.prefs.value.home.mode == HomeMode.CHANNELS) HomeMode.FLOW else HomeMode.CHANNELS
    store.updatePrefs { it.copy(home = it.home.copy(mode = next)) }
    toasts.show(if (next == HomeMode.CHANNELS) "Home is now Channels" else "Home is now Flow")
}

/** "Switch to Channels" (or Flow) and "Home settings", for Home's option menus. */
fun AppState.homeStyleActions(): List<MenuAction> {
    val channels = store.prefs.value.home.mode == HomeMode.CHANNELS
    return listOf(
        MenuAction(
            "style", if (channels) "Switch to Flow" else "Switch to Channels",
            if (channels) FuseIcons.Rows else FuseIcons.Grid,
            detail = if (channels) "Rows of games under a big title" else "A board of tiles you arrange",
            onSelect = { closeOverlays(); switchHomeStyle() },
        ),
        MenuAction("home", "Home settings", FuseIcons.Dashboard, trailing = Trailing.Chevron, onSelect = { closeOverlays(); go(Route.Settings("home")) }),
    )
}

/**
 * Flow Mode: a continuous dashboard. The selected item is told big at the top (the stage) and lights
 * the room; shelves below slide so the selected shelf always sits in the same place, and each shelf
 * slides sideways under a fixed focus spot, so your eyes never have to chase the selection.
 */
@Composable
fun FlowHome(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.library.home.collectAsState()
    val cartridge by store.cartridge.status.collectAsState()
    val achievementsOn by store.achievements.configured.collectAsState()
    val shelves = remember(prefs.home, feed, achievementsOn, cartridge.installed) {
        buildShelves(prefs.home.widgets.filter { app.offers(it.kind) }, if (prefs.collectionsEnabled) feed else feed.copy(collections = emptyList()), achievementsOn, cartridge.installed)
    }
    val sel = rememberRouteState(app.navigator, "home.flow") { ShelfSelection() }
    val keys = shelves.map { it.key }
    sel.clamp(keys) { k -> shelves.firstOrNull { it.key == k }?.items?.size ?: 0 }
    var reorder by remember { mutableStateOf<String?>(null) }
    // A system picked up on the Systems shelf (hold confirm); left and right move it.
    var movingSystem by remember { mutableStateOf(false) }
    // Shelves are held by their title (or a widget) and dragged up and down by touch.
    val shelfDrag = rememberDragReorderState()
    fun moveShelfTo(from: Int, to: Int) {
        val blocks = shelves.map { sh -> sh.widgets.map { it.id } }
        store.updatePrefs { p -> p.copy(home = p.home.copy(widgets = HomeArrange.moveBlock(p.home.widgets, blocks, from, to))) }
    }

    val shelf = shelves.getOrNull(sel.row)
    val item = shelf?.items?.getOrNull(sel.column(shelf.key))

    if (shelves.isEmpty()) {
        HomeEmpty(app)
        return
    }

    // The room and the stage follow the selection.
    val systems = rememberSystems(app)
    LaunchedEffect(item?.key, reorder, movingSystem, systems) {
        app.hero = item?.hero(systems)
        app.hints = when {
            reorder != null || movingSystem -> listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Done"))
            item is ShelfItem.Game -> listOf(Hint(HintButton.CONFIRM, app.gameConfirmLabel), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))
            item is ShelfItem.System -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to move"), Hint(HintButton.OPTIONS, "Options"))
            item is ShelfItem.Widget -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to arrange"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))
        }
    }

    fun activate(i: ShelfItem) {
        when (i) {
            is ShelfItem.Game -> app.activateGame(i.card)
            is ShelfItem.System -> app.go(Route.PlatformGames(i.card.platform.id))
            is ShelfItem.App -> app.openApp(i.card)
            is ShelfItem.Collection -> app.go(Route.CollectionGames(i.collection.id, i.collection.name))
            is ShelfItem.Widget -> openWidget(app, i.kind)
        }
    }

    fun options(i: ShelfItem) {
        val s = shelf ?: return
        when (i) {
            is ShelfItem.Game -> {
                val extra = if (s.widgets.any { it.kind == WidgetKind.CONTINUE_PLAYING }) {
                    listOf(MenuAction("uncontinue", "Remove from Continue Playing", FuseIcons.Close, detail = "Comes back when you play it again", onSelect = {
                        app.closeOverlays()
                        app.dismissFromContinue(i.card)
                    }))
                } else emptyList()
                // Shelves of games have no options of their own, so moving one is offered here.
                val move = MenuAction("arrange", "Move this shelf", FuseIcons.Move, detail = "Then drag it, or use up and down", onSelect = {
                    app.closeOverlays()
                    reorder = s.key
                    shelfDrag.arm(s.key)
                })
                app.openContextMenu(app.gameMenu(i.card, extra = extra + move))
            }
            is ShelfItem.System -> app.openContextMenu(app.systemMenu(i.card) { movingSystem = true })
            is ShelfItem.App -> app.openContextMenu(app.appMenu(i.card))
            else -> app.openContextMenu(shelfMenu(app, s) { reorder = s.key; shelfDrag.arm(s.key) })
        }
    }

    fun moveShelf(key: String, down: Boolean): NavResult {
        val idx = shelves.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return NavResult.BLOCKED
        val to = if (down) idx + 1 else idx - 1
        if (to !in shelves.indices) return NavResult.BLOCKED
        moveShelfTo(idx, to)
        sel.row = to
        return NavResult.MOVED
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen, longPress = true) { e ->
        if (movingSystem && shelf != null) {
            return@InputLayer when (e.action) {
                NavAction.LEFT, NavAction.RIGHT -> {
                    val from = sel.column(shelf.key)
                    val key = feed.systems.getOrNull(from)?.platform?.id?.value
                    val to = if (key == null) -1 else app.moveSystemBy(key, if (e.action == NavAction.LEFT) -1 else 1)
                    if (to < 0 || to == from) NavResult.BLOCKED else { sel.setColumn(shelf.key, to); NavResult.MOVED }
                }
                NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> { movingSystem = false; NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
        val moving = reorder
        if (moving != null) {
            return@InputLayer when (e.action) {
                NavAction.UP -> moveShelf(moving, down = false)
                NavAction.DOWN -> moveShelf(moving, down = true)
                NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> { reorder = null; NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT ->
                sel.move(e.action, keys) { k -> shelves.firstOrNull { it.key == k }?.items?.size ?: 0 }
            NavAction.SELECT -> { item?.let(::activate); if (item != null) NavResult.ACTIVATED else NavResult.BLOCKED }
            NavAction.CONTEXT -> { item?.let(::options); NavResult.ACTIVATED }
            NavAction.REORDER -> {
                if (item is ShelfItem.System) movingSystem = true else reorder = shelf?.key
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    val arranging = reorder != null || movingSystem
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap outside the tiles puts a carried shelf or system down.
            .pointerInput(arranging) { if (arranging) detectTapGestures { reorder = null; movingSystem = false; shelfDrag.arm(null) } },
    ) {
        val maxH = maxHeight
        val stageHeight = (maxH * 0.3f).coerceIn(150.dp, 280.dp)
        val rows = rememberLazyListState()
        // While a shelf is held the list stays under the finger.
        FollowSelection(rows, { sel.row }, anchor = 0f, enabled = { shelfDrag.heldKey == null })
        val shown = shelfDrag.arrange(shelves) { it.key }
        val selectedKey = shelves.getOrNull(sel.row)?.key
        val selectedAt = shown.indexOfFirst { it.key == selectedKey }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            Box(Modifier.fillMaxWidth().height(stageHeight).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
                Stage(item?.stage(feed), showLogo = prefs.showLogo)
            }
            Spacer(Modifier.height(Space.xl))
            LazyColumn(
                state = rows,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .fadingEdges(top = if (rows.canScrollBackward) 24.dp else 0.dp)
                    .dragReorder(
                        shelfDrag,
                        visibleKeys = { rows.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { rows.scrollBy(it) },
                        keepScroll = { rows.requestScrollToItem(rows.firstVisibleItemIndex, rows.firstVisibleItemScrollOffset) },
                        vertical = true,
                        enabled = !movingSystem,
                        longPressMs = ReorderDefaults.liftMs(prefs.input.longPressMs.toLong()),
                        endInset = Size.hintHeight,
                        requireHandle = true,
                        lane = ReorderMath.Lane.COLUMN,
                        onLift = { key ->
                            sel.row = shelves.indexOfFirst { it.key == key }.coerceAtLeast(0)
                            app.focusZone = FocusZone.CONTENT
                            app.platform.haptics.lift()
                        },
                        onTarget = { app.platform.haptics.slot() },
                        // A hold let go where it started opens the shelf's options.
                        onHoldReleased = { key ->
                            shelves.firstOrNull { it.key == key }?.let { sh -> app.openContextMenu(shelfMenu(app, sh) { reorder = sh.key; shelfDrag.arm(sh.key) }) }
                        },
                        onDrop = { key, to ->
                            val from = shelves.indexOfFirst { it.key == key }
                            if (from >= 0 && to != from) moveShelfTo(from, to)
                            sel.row = to
                            reorder = null
                            app.platform.haptics.drop()
                        },
                    ),
                // Only room for the hint line: the list ends where its content ends, by stick or by touch.
                contentPadding = PaddingValues(bottom = Size.hintHeight + Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(shown, key = { _, s -> s.key }) { shownAt, s ->
                    val index = shelves.indexOf(s)
                    val held = shelfDrag.heldKey == s.key
                    val rowAlpha by animateFloatAsState(
                        // Shelves above the selection dim rather than vanish, so touch scrolling always shows them.
                        if (shownAt == selectedAt || held) 1f else if (shownAt < selectedAt) 0.55f else 0.72f,
                        Fuse.motion.fade(Durations.BASE),
                        label = "shelf",
                    )
                    ShelfRow(
                        app = app,
                        shelf = s,
                        selectedColumn = if (index == sel.row && app.focusZone == FocusZone.CONTENT) sel.column(s.key) else -1,
                        rememberedColumn = sel.column(s.key),
                        moving = reorder == s.key,
                        lifted = reorder == s.key || held,
                        shelfDrag = shelfDrag,
                        movingItem = movingSystem && index == sel.row,
                        feed = feed,
                        cartridge = cartridge,
                        clock24h = prefs.clock24h,
                        modifier = Modifier
                            // The held shelf follows the finger; the others slide out of its way.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (held) null else ReorderDefaults.Placement)
                            .reorderItem(shelfDrag, s.key, liftScale = 1.02f)
                            .graphicsLayer { alpha = rowAlpha },
                        onTap = { col ->
                            if (arranging) {
                                // A tap while arranging puts the carried shelf or system down.
                                reorder = null
                                movingSystem = false
                                shelfDrag.arm(null)
                                return@ShelfRow
                            }
                            val wasSelected = sel.row == index && sel.column(s.key) == col
                            sel.row = index
                            sel.setColumn(s.key, col)
                            app.focusZone = FocusZone.CONTENT
                            // Systems, apps, collections and widgets open on the first tap. A game is
                            // shown first and played on the second, so browsing never starts one.
                            val item = s.items.getOrNull(col)
                            if (item != null && (wasSelected || item !is ShelfItem.Game)) activate(item)
                        },
                        onLongPress = { col ->
                            sel.row = index
                            sel.setColumn(s.key, col)
                            s.items.getOrNull(col)?.let(::options)
                        },
                        // Systems are also held and dragged into place by touch.
                        onMoveSystem = if (arranging) null else { from, to ->
                            sel.row = index
                            val key = feed.systems.getOrNull(from)?.platform?.id?.value
                            val placed = if (key != null && to != from) app.moveSystem(key, to) else to
                            sel.setColumn(s.key, if (placed >= 0) placed else to)
                        },
                        onLift = { col ->
                            sel.row = index
                            sel.setColumn(s.key, col)
                            app.focusZone = FocusZone.CONTENT
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    app: AppState,
    shelf: Shelf,
    selectedColumn: Int,
    rememberedColumn: Int,
    moving: Boolean,
    lifted: Boolean,
    shelfDrag: DragReorderState,
    movingItem: Boolean,
    feed: io.github.matiyaaa.fuse.ui.shell.store.HomeFeed,
    cartridge: io.github.matiyaaa.fuse.model.CartridgeStatus,
    clock24h: Boolean,
    modifier: Modifier,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onMoveSystem: ((from: Int, to: Int) -> Unit)? = null,
    onLift: (Int) -> Unit = {},
) {
    val c = Fuse.colors
    val metrics = LocalTileMetrics.current
    val row = rememberLazyListState()
    val drag = rememberDragReorderState()
    val reorderable = shelf.style == ShelfStyle.SYSTEM && onMoveSystem != null
    val items = if (shelf.style == ShelfStyle.SYSTEM) drag.arrange(shelf.items) { it.key } else shelf.items
    FollowSelection(row, { rememberedColumn }, anchor = 0f)
    // What the tiles and the stage will draw next, decoded ahead into memory.
    val focus = if (selectedColumn >= 0) selectedColumn else -1
    val posters = LocalGameArt.current == io.github.matiyaaa.fuse.model.GameArtStyle.POSTER
    PrefetchArt(
        remember(shelf.items, shelf.style, posters) {
            shelf.items.map { item ->
                when (item) {
                    is ShelfItem.Game -> if (shelf.style == ShelfStyle.WIDE) item.card.art.hero ?: item.card.art.grid ?: item.card.art.boxart
                    else if (posters) item.card.art.boxart ?: item.card.art.square ?: item.card.art.icon ?: item.card.art.grid
                    else item.card.art.square ?: item.card.art.icon ?: item.card.art.boxart ?: item.card.art.grid
                    is ShelfItem.System -> item.card.art.square ?: item.card.art.icon ?: item.card.art.boxart
                    else -> null
                }
            }
        },
        focus,
        size = metrics.icon * 1.4f,
    )
    PrefetchArt(
        remember(shelf.items) {
            shelf.items.map { item ->
                when (item) {
                    is ShelfItem.Game -> item.card.art.logo
                    is ShelfItem.System -> item.card.art.logo
                    else -> null
                }
            }
        },
        focus,
        size = 360.dp,
    )
    // A shelf being moved sits on a raised panel, so it reads as one thing in your hand.
    val lift by animateFloatAsState(if (lifted) 1f else 0f, Fuse.motion.focusSpring(), label = "shelf lift")
    val panel = Fuse.geometry.panel
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (lift <= 0.01f) return@drawBehind
                val inset = Space.l.toPx()
                val pad = Space.s.toPx()
                val r = CornerRadius(panel.toPx())
                val topLeft = Offset(inset, -pad)
                val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height + pad)
                // A soft shadow in three steps, then the panel and its hairline.
                for (i in 3 downTo 1) {
                    val spread = i * 6.dp.toPx()
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.10f * lift),
                        topLeft = topLeft + Offset(-spread / 2, spread / 2),
                        size = androidx.compose.ui.geometry.Size(box.width + spread, box.height + spread / 2),
                        cornerRadius = CornerRadius(r.x + spread / 2),
                    )
                }
                drawRoundRect(c.surfaceRaised.copy(alpha = 0.94f * lift), topLeft, box, r)
                drawRoundRect(c.text.copy(alpha = 0.1f * lift), topLeft, box, r, style = Stroke(1.dp.toPx()))
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // Holding a shelf's title picks the whole shelf up.
                .reorderHandle(shelfDrag, shelf.key)
                .padding(start = Space.gutter, end = Space.gutter, bottom = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(shelf.title, color = if (selectedColumn >= 0 || lifted) c.text else c.textMuted)
            if (lifted || movingItem) {
                Spacer(Modifier.width(Space.s))
                FuseIcon(FuseIcons.Move, size = 14.dp, tint = c.accent)
                Spacer(Modifier.width(Space.xs))
                FText(
                    when {
                        movingItem -> "Left and right to place it"
                        moving -> "Drag it, or up and down to place it"
                        else -> "Drag to place it"
                    },
                    Fuse.type.caption, color = c.accent,
                )
            }
        }
        val height = when (shelf.style) {
            ShelfStyle.WIDE -> metrics.icon * 1.25f
            else -> metrics.icon
        }
        LazyRow(
            state = row,
            modifier = if (!reorderable) Modifier else Modifier.dragReorder(
                drag,
                visibleKeys = { row.layoutInfo.visibleItemsInfo.map { it.key } },
                scrollBy = { row.scrollBy(it) },
                keepScroll = { row.requestScrollToItem(row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset) },
                vertical = false,
                lane = ReorderMath.Lane.ROW,
                longPressMs = ReorderDefaults.liftMs(app.store.prefs.value.input.longPressMs.toLong()),
                onLift = { key ->
                    onLift(items.indexOfFirst { it.key == key }.coerceAtLeast(0))
                    app.platform.haptics.lift()
                },
                onTarget = { app.platform.haptics.slot() },
                onHoldReleased = { key -> shelf.items.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let(onLongPress) },
                onDrop = { key, to ->
                    shelf.items.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { from -> onMoveSystem?.invoke(from, to) }
                    app.platform.haptics.drop()
                },
            ),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 3, bottom = Space.l),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            itemsIndexed(items, key = { _, i -> i.key }) { col, item ->
                val selected = drag.heldKey?.let { it == item.key } ?: (col == selectedColumn)
                val carried = movingItem && selected
                val carry by animateFloatAsState(if (carried) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                val tileShape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction)
                // Tiles slide to their new places; a carried one floats a little above the row.
                Box(
                    Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (drag.heldKey == item.key) null else ReorderDefaults.Placement)
                        .then(if (shelf.style == ShelfStyle.SYSTEM) Modifier.reorderItem(drag, item.key, shape = tileShape) else Modifier)
                        // Widgets have no options of their own: holding one picks up its shelf.
                        .then(if (item is ShelfItem.Widget) Modifier.reorderHandle(shelfDrag, shelf.key) else Modifier)
                        .zIndex(if (carried) 1f else 0f)
                        .carried({ carry }, tileShape),
                ) {
                when (item) {
                    is ShelfItem.Game -> if (shelf.style == ShelfStyle.WIDE) {
                        GameWideTile(item.card, selected, height = height, caption = item.caption, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    } else {
                        GameIconTile(item.card, selected, size = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    }
                    is ShelfItem.System -> SystemTile(item.card, selected, size = height, onClick = { onTap(col) }, onLongClick = if (reorderable) null else ({ onLongPress(col) }))
                    is ShelfItem.App -> AppTile(item.card, selected, size = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    is ShelfItem.Collection -> CollectionTile(item.collection, selected, height = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    // A widget's hold belongs to its shelf: let go without moving, and the shelf's options open.
                    is ShelfItem.Widget -> WidgetCard(item.kind, item.span, feed, cartridge, selected, clock24h, onClick = { onTap(col) }, height = height)
                }
                }
            }
        }
    }
}


/** What the stage shows for a shelf item. */
private fun ShelfItem.stage(feed: io.github.matiyaaa.fuse.ui.shell.store.HomeFeed): StageInfo = when (this) {
    is ShelfItem.Game -> card.stage()
    is ShelfItem.System -> card.stage()
    is ShelfItem.App -> StageInfo(key = key, title = card.entry.displayTitle, eyebrow = when (card.entry.kind) {
        io.github.matiyaaa.fuse.model.AppKind.GAME -> "ANDROID GAME"
        io.github.matiyaaa.fuse.model.AppKind.EMULATOR -> "EMULATOR"
        io.github.matiyaaa.fuse.model.AppKind.APP -> "APP"
    })
    is ShelfItem.Collection -> StageInfo(key = key, title = collection.name, eyebrow = "COLLECTION", meta = listOf("${collection.gameCount} games"))
    is ShelfItem.Widget -> StageInfo(
        key = key,
        title = kind.title(),
        eyebrow = "AT A GLANCE",
        meta = when (kind) {
            WidgetKind.PLAYTIME_WEEK, WidgetKind.PLAYTIME_TOTAL -> listOf("Time Fuse saw you play. Imported time is kept separate.")
            else -> emptyList()
        },
    )
}

/** The backdrop for an item, keyed by its game or system (the second screen shows what the key names). */
private fun ShelfItem.hero(systems: Map<io.github.matiyaaa.fuse.model.PlatformId, io.github.matiyaaa.fuse.ui.shell.store.PlatformCard>): HeroSource? = when (this) {
    is ShelfItem.Game -> card.room(systems[card.platformId])
    is ShelfItem.System -> HeroSource(card.platform.id, card.art.hero, card.platform.accent.toColor())
    else -> null
}

private fun openWidget(app: AppState, kind: WidgetKind) {
    when (kind) {
        WidgetKind.CARTRIDGE_DOWNLOADS -> app.selectTab(Destination.CARTRIDGE)
        WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.ACHIEVEMENT_PROGRESS,
        WidgetKind.RECENTLY_MASTERED -> app.selectTab(Destination.ACHIEVEMENTS)
        WidgetKind.STORAGE -> app.go(Route.Storage)
        WidgetKind.CLOCK -> app.quickMenuOpen = true
        else -> app.selectTab(Destination.LIBRARY)
    }
}

private fun shelfMenu(app: AppState, shelf: Shelf, onArrange: () -> Unit): ContextMenuSpec = ContextMenuSpec(
    title = shelf.title,
    subtitle = "Home",
    actions = listOf(
        MenuAction("arrange", "Move this shelf", FuseIcons.Move, detail = "Or hold the confirm button on any shelf", onSelect = {
            app.closeOverlays()
            onArrange()
        }),
        MenuAction("hide", "Hide this shelf", FuseIcons.EyeOff, onSelect = {
            app.store.updatePrefs { p ->
                val ids = shelf.widgets.map { it.id }.toSet()
                p.copy(home = p.home.copy(widgets = p.home.widgets.map { if (it.id in ids) it.copy(visible = false) else it }))
            }
            app.closeOverlays()
        }),
    ) + app.homeStyleActions(),
)
