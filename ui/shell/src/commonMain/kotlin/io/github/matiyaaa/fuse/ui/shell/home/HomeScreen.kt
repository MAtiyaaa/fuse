package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import androidx.compose.animation.core.animateFloatAsState
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.dismissFromContinue
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.apps.appMenu
import io.github.matiyaaa.fuse.ui.shell.components.AppTile
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.GameWideTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.StageInfo
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.library.CollectionTile
import io.github.matiyaaa.fuse.ui.shell.systems.moveSystem
import io.github.matiyaaa.fuse.ui.shell.systems.systemMenu
import kotlinx.coroutines.launch

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
        buildShelves(prefs.home.widgets, feed, achievementsOn, cartridge.installed)
    }
    val sel = rememberRouteState(app.navigator, "home.flow") { ShelfSelection() }
    val keys = shelves.map { it.key }
    sel.clamp(keys) { k -> shelves.firstOrNull { it.key == k }?.items?.size ?: 0 }
    var reorder by remember { mutableStateOf<String?>(null) }
    // A system picked up on the Systems shelf (hold confirm); left and right move it.
    var movingSystem by remember { mutableStateOf(false) }

    val shelf = shelves.getOrNull(sel.row)
    val item = shelf?.items?.getOrNull(sel.column(shelf.key))

    if (shelves.isEmpty()) {
        HomeEmpty(app)
        return
    }

    // The room and the stage follow the selection.
    LaunchedEffect(item?.key, reorder, movingSystem) {
        app.hero = item?.hero()
        app.hints = when {
            reorder != null || movingSystem -> listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Done"))
            item is ShelfItem.Game -> listOf(Hint(HintButton.CONFIRM, "Play"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))
            item is ShelfItem.System -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to move"), Hint(HintButton.OPTIONS, "Options"))
            item is ShelfItem.Widget -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to arrange"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))
        }
    }

    fun activate(i: ShelfItem) {
        when (i) {
            is ShelfItem.Game -> app.play(i.card)
            is ShelfItem.System -> app.go(Route.PlatformGames(i.card.platform.id))
            is ShelfItem.App -> app.scope.launch { store.apps.launch(i.card) }
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
                app.openContextMenu(app.gameMenu(i.card, extra = extra))
            }
            is ShelfItem.System -> app.openContextMenu(app.systemMenu(i.card))
            is ShelfItem.App -> app.openContextMenu(app.appMenu(i.card))
            else -> app.openContextMenu(shelfMenu(app, s) { reorder = s.key })
        }
    }

    fun moveShelf(key: String, down: Boolean): NavResult {
        val s = shelves.firstOrNull { it.key == key } ?: return NavResult.BLOCKED
        val idx = shelves.indexOf(s)
        val other = shelves.getOrNull(if (down) idx + 1 else idx - 1) ?: return NavResult.BLOCKED
        store.updatePrefs { p ->
            val ordered = p.home.widgets.sortedBy { it.order }.toMutableList()
            val mine = s.widgets.map { it.id }.toSet()
            val theirs = other.widgets.map { it.id }.toSet()
            val a = ordered.filter { it.id in mine }
            val b = ordered.filter { it.id in theirs }
            val first = ordered.indexOfFirst { it.id in mine || it.id in theirs }
            val rest = ordered.filterNot { it.id in mine || it.id in theirs }.toMutableList()
            rest.addAll(first.coerceAtMost(rest.size), if (down) b + a else a + b)
            p.copy(home = p.home.copy(widgets = rest.mapIndexed { i, w -> w.copy(order = i) }))
        }
        sel.row = (if (down) idx + 1 else idx - 1).coerceIn(0, shelves.lastIndex)
        return NavResult.MOVED
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen, longPress = true) { e ->
        if (movingSystem && shelf != null) {
            return@InputLayer when (e.action) {
                NavAction.LEFT, NavAction.RIGHT -> {
                    val from = sel.column(shelf.key)
                    val to = app.moveSystem(feed.systems, from, if (e.action == NavAction.LEFT) -1 else 1)
                    if (to == from) NavResult.BLOCKED else { sel.setColumn(shelf.key, to); NavResult.MOVED }
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
            .pointerInput(arranging) { if (arranging) detectTapGestures { reorder = null; movingSystem = false } },
    ) {
        val maxH = maxHeight
        val stageHeight = (maxH * 0.3f).coerceIn(150.dp, 280.dp)
        val rows = rememberLazyListState()
        FollowSelection(rows, { sel.row }, anchor = 0f)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            Box(Modifier.fillMaxWidth().height(stageHeight).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
                Stage(item?.stage(feed), showLogo = prefs.showLogo)
            }
            Spacer(Modifier.height(Space.xl))
            LazyColumn(
                state = rows,
                modifier = Modifier.fillMaxWidth().weight(1f).fadingEdges(top = if (rows.canScrollBackward) 24.dp else 0.dp),
                // Only room for the hint line: the list ends where its content ends, by stick or by touch.
                contentPadding = PaddingValues(bottom = Size.hintHeight + Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(shelves, key = { _, s -> s.key }) { index, s ->
                    val rowAlpha by animateFloatAsState(
                        // Shelves above the selection dim rather than vanish, so touch scrolling always shows them.
                        if (index == sel.row) 1f else if (index < sel.row) 0.55f else 0.72f,
                        Fuse.motion.fade(Durations.BASE),
                        label = "shelf",
                    )
                    ShelfRow(
                        app = app,
                        shelf = s,
                        selectedColumn = if (index == sel.row && app.focusZone == FocusZone.CONTENT) sel.column(s.key) else -1,
                        rememberedColumn = sel.column(s.key),
                        moving = reorder == s.key,
                        movingItem = movingSystem && index == sel.row,
                        feed = feed,
                        cartridge = cartridge,
                        clock24h = prefs.clock24h,
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            .graphicsLayer { alpha = rowAlpha },
                        onTap = { col ->
                            if (arranging) {
                                // A tap while arranging puts the carried shelf or system down.
                                reorder = null
                                movingSystem = false
                                return@ShelfRow
                            }
                            val wasSelected = sel.row == index && sel.column(s.key) == col
                            sel.row = index
                            sel.setColumn(s.key, col)
                            app.focusZone = FocusZone.CONTENT
                            if (wasSelected) s.items.getOrNull(col)?.let(::activate)
                        },
                        onLongPress = { col ->
                            sel.row = index
                            sel.setColumn(s.key, col)
                            s.items.getOrNull(col)?.let(::options)
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
    movingItem: Boolean,
    feed: io.github.matiyaaa.fuse.ui.shell.store.HomeFeed,
    cartridge: io.github.matiyaaa.fuse.model.CartridgeStatus,
    clock24h: Boolean,
    modifier: Modifier,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val c = Fuse.colors
    val metrics = LocalTileMetrics.current
    val row = rememberLazyListState()
    FollowSelection(row, { rememberedColumn }, anchor = 0f)
    PrefetchArt(
        remember(shelf.items) {
            shelf.items.map { item ->
                when (item) {
                    is ShelfItem.Game -> item.card.art.grid ?: item.card.art.icon ?: item.card.art.boxart
                    is ShelfItem.System -> item.card.art.icon ?: item.card.art.boxart
                    else -> null
                }
            }
        },
        if (selectedColumn >= 0) selectedColumn else -1,
    )
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (moving) Modifier.padding(horizontal = Space.l).border(1.5.dp, c.focus.copy(alpha = 0.7f), RoundedCornerShape(Fuse.geometry.panel)).padding(vertical = Space.s)
                else Modifier,
            ),
    ) {
        Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(shelf.title, color = if (selectedColumn >= 0) c.text else c.textMuted)
            if (moving || movingItem) {
                Spacer(Modifier.padding(horizontal = Space.xs))
                FText(if (moving) "Moving: up and down to place it" else "Moving: left and right to place it", Fuse.type.caption, color = c.accent)
            }
        }
        Spacer(Modifier.height(Space.m))
        val height = when (shelf.style) {
            ShelfStyle.WIDE -> metrics.icon * 1.25f
            else -> metrics.icon
        }
        LazyRow(
            state = row,
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 3, bottom = Space.l),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            itemsIndexed(shelf.items, key = { _, i -> i.key }) { col, item ->
                val selected = col == selectedColumn
                val carried = movingItem && selected
                val lifted by animateFloatAsState(if (carried) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                // Tiles slide to their new places; a carried one floats a little above the row.
                Box(
                    Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                        .zIndex(if (carried) 1f else 0f)
                        .graphicsLayer {
                            val scale = 1f + 0.05f * lifted
                            scaleX = scale
                            scaleY = scale
                            translationY = -6.dp.toPx() * lifted
                        },
                ) {
                when (item) {
                    is ShelfItem.Game -> if (shelf.style == ShelfStyle.WIDE) {
                        GameWideTile(item.card, selected, height = height, caption = item.caption, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    } else {
                        GameIconTile(item.card, selected, size = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    }
                    is ShelfItem.System -> SystemTile(item.card, selected, size = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    is ShelfItem.App -> AppTile(item.card, selected, size = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
                    is ShelfItem.Collection -> CollectionTile(item.collection, selected, height = height, onClick = { onTap(col) }, onLongClick = { onLongPress(col) })
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
    is ShelfItem.App -> StageInfo(key = key, title = card.entry.displayTitle, eyebrow = if (card.entry.isGame) "ANDROID GAME" else "APP")
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

private fun ShelfItem.hero(): HeroSource? = when (this) {
    is ShelfItem.Game -> HeroSource(key, card.art.hero ?: card.art.grid, card.accent.toColor(), card.art.heroFocusX, card.art.heroFocusY, card.art.video)
    is ShelfItem.System -> HeroSource(key, card.art.hero, card.platform.accent.toColor())
    else -> null
}

private fun openWidget(app: AppState, kind: WidgetKind) {
    when (kind) {
        WidgetKind.CARTRIDGE_DOWNLOADS -> app.selectTab(Destination.CARTRIDGE)
        WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.ACHIEVEMENT_PROGRESS,
        WidgetKind.RECENTLY_MASTERED -> app.selectTab(Destination.ACHIEVEMENTS)
        WidgetKind.STORAGE -> app.go(Route.Settings("storage"))
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
