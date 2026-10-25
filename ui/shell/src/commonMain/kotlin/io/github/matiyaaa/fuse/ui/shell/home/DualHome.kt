package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdgesHorizontal
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Spotlight
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.dismissFromContinue
import io.github.matiyaaa.fuse.ui.shell.app.gameConfirmLabel
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.baseForHeight
import io.github.matiyaaa.fuse.ui.shell.components.tileSize
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import kotlinx.coroutines.launch

/**
 * Home on the touch screen below while the screen above shows what is chosen (flipped mode), set out
 * as a DS's lower screen is: the games as icons to touch, in a grid that runs sideways, the shelves
 * to choose from above it (Recent, Favourites, New, All and each system), and a bar of buttons below
 * that also show the controller's button for each. A touch chooses a game and the screen above shows
 * it; touching it again, or Play, starts it. A slim bar over the buttons shows where the grid is and
 * takes a finger to slide through it, and the zoom buttons set how big the icons are, from one row
 * of large ones to several of small.
 */
@Composable
fun DualHome(app: AppState) {
    val store = app.store
    val feed by store.homeFeed.collectAsState()
    val prefs by store.prefs.collectAsState()
    // How big the icons are is this device's, kept with its screens.
    val zoom = prefs.display.flippedZoom.coerceIn(0, MOST_ZOOM)
    val shelves = remember(feed) { dualShelves(feed) }
    val loading = rememberHomeLoading(app, feed, shelves.isEmpty())
    if (shelves.isEmpty()) {
        if (loading) HomeSkeleton(app, channels = false) else HomeEmpty(app)
        return
    }
    val sel = rememberRouteState(app.navigator, "home.dual") { DualSelection() }
    val shelf = shelves.firstOrNull { it.key == sel.shelf } ?: shelves.first()
    val games = rememberShelfGames(store, shelf)
    val count = games?.size ?: 0
    val index = (sel.index[shelf.key] ?: 0).coerceIn(0, (count - 1).coerceAtLeast(0))
    val chosen = games?.getOrNull(index)
    // Each shelf keeps its own scroll, made before the grid and its position bar first read it, and
    // its games, so a shelf on its way out shows them to the end instead of a spinner.
    val grid = sel.grids.getOrPut(shelf.key) { LazyGridState(firstVisibleItemIndex = (index - 2).coerceAtLeast(0)) }
    androidx.compose.runtime.SideEffect { if (games != null) sel.lists[shelf.key] = games }
    val systems = rememberSystems(app)
    val content = app.focusZone == FocusZone.CONTENT

    // The screen above shows the chosen game; the buttons below carry their own glyphs, so the hint
    // line stays away and leaves the whole foot of the screen to them.
    PageEffect(chosen?.id, systems) {
        app.hero = chosen?.room(systems[chosen.shownPlatform])
        app.heroHeld = false
        app.hints = emptyList()
    }

    fun choose(i: Int) {
        if (i == index || i !in 0 until count) return
        Spotlight.moved(if (i > index) 1 else -1)
        sel.index[shelf.key] = i
    }

    fun options(card: GameCard) {
        val extra = if (shelf.key == RECENT && feed.continuePlaying.any { it.id == card.id }) {
            listOf(MenuAction("uncontinue", "Remove from Continue Playing", FuseIcons.Close, detail = "Comes back when you play it again", onSelect = {
                app.closeOverlays()
                app.dismissFromContinue(card)
            }))
        } else emptyList()
        app.openContextMenu(app.gameMenu(card, extra = extra))
    }

    fun showShelf(step: Int): NavResult {
        val at = shelves.indexOfFirst { it.key == shelf.key }
        val next = shelves.getOrNull(at + step) ?: return NavResult.BLOCKED
        sel.direction = step
        sel.shelf = next.key
        return NavResult.MOVED
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (maxWidth < NARROW) Space.gutterCompact else Space.gutter
        // What is left for the grid once the top line, the shelves, the position bar and the buttons
        // have their room: the rows follow from it and the icons' size.
        val gridHeight = (maxHeight - Size.hudHeight - SHELVES - POSITION - BAR - Space.s * 2).coerceAtLeast(MIN_TILE)
        val rows = rowsFor(gridHeight, zoom)
        // Zoom buttons only where a step changes the rows.
        val canShrink = zoom < MOST_ZOOM && rowsFor(gridHeight, zoom + 1) != rows
        val canGrow = zoom > 0 && rowsFor(gridHeight, zoom - 1) != rows
        val visibleColumns = visibleColumnsFor(maxWidth - gutter * 2, gridHeight, rows, LocalGameArt.current)

        InputLayer(enabled = content && !app.overlayOpen) { e ->
            fun go(to: Int): NavResult {
                if (to == index || to !in 0 until count) return NavResult.BLOCKED
                sel.index[shelf.key] = to
                return NavResult.MOVED
            }
            when (e.action) {
                NavAction.LEFT -> go(index - rows)
                // Into the last column, partly filled, from a row it doesn't reach: its last game.
                NavAction.RIGHT -> if (index / rows < (count - 1) / rows) go((index + rows).coerceAtMost(count - 1)) else NavResult.BLOCKED
                NavAction.UP -> if (index % rows > 0) go(index - 1) else NavResult.BLOCKED
                NavAction.DOWN -> if (index % rows < rows - 1) go(index + 1) else NavResult.BLOCKED
                NavAction.PAGE_UP -> go((index - rows * visibleColumns).coerceAtLeast(index % rows))
                NavAction.PAGE_DOWN -> go((index + rows * visibleColumns).coerceAtMost(count - 1))
                NavAction.PAGE_PREVIOUS -> showShelf(-1)
                NavAction.PAGE_NEXT -> showShelf(1)
                NavAction.SELECT -> chosen?.let { app.activateGame(it); NavResult.ACTIVATED } ?: NavResult.BLOCKED
                NavAction.CONTEXT -> chosen?.let { options(it); NavResult.ACTIVATED } ?: NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
        }

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            ShelfTabs(
                shelves, shelf.key, gutter,
                onPick = { key ->
                    val step = shelves.indexOfFirst { it.key == key } - shelves.indexOfFirst { it.key == shelf.key }
                    if (step != 0) {
                        sel.direction = if (step > 0) 1 else -1
                        sel.shelf = key
                        app.platform.sounds.play(SoundCue.MOVE)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(SHELVES),
            )
            Spacer(Modifier.height(Space.s))
            val motion = Fuse.motion
            Swap(
                targetState = shelf.key,
                modifier = Modifier.fillMaxWidth().height(gridHeight),
                transitionSpec = {
                    val enter = fadeIn(motion.fade(Durations.BASE))
                    val exit = fadeOut(motion.fade(Durations.FAST))
                    if (motion.reduced) enter togetherWith exit
                    else (slideInHorizontally(motion.tween(Durations.BASE)) { (it * 0.06f * sel.direction).toInt() } + enter) togetherWith exit
                },
                label = "dual shelf",
            ) { key ->
                val current = key == shelf.key
                val list = if (current) games ?: sel.lists[key] else sel.lists[key]
                val at = if (current) index else sel.index[key] ?: 0
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                    list.isEmpty() -> EmptyState(FuseIcons.Library, "Nothing here yet", Modifier.fillMaxSize(), message = "Games you add show up here")
                    else -> IconGrid(
                        app, list, key, at, rows, gutter, content && current,
                        grid = sel.grids.getOrPut(key) { LazyGridState() },
                        // A shelf on its way out takes no touches.
                        onTap = { i ->
                            if (current) {
                                app.focusZone = FocusZone.CONTENT
                                // A DS's way: the first touch chooses, the second starts it.
                                if (i == index) {
                                    list.getOrNull(i)?.let(app::activateGame)
                                } else {
                                    choose(i)
                                    app.platform.sounds.play(SoundCue.MOVE)
                                    app.platform.haptics.tick()
                                }
                            }
                        },
                        onHold = { i ->
                            if (current) {
                                app.focusZone = FocusZone.CONTENT
                                choose(i)
                                list.getOrNull(i)?.let(::options)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(Space.s))
            if (count > 0) {
                PositionBar(grid, rows, count, Modifier.fillMaxWidth().padding(horizontal = gutter).height(POSITION))
            } else {
                Spacer(Modifier.height(POSITION))
            }
            ButtonBar(
                app, chosen, gutter, canShrink, canGrow,
                onOptions = { chosen?.let(::options) },
                onPlay = { chosen?.let(app::activateGame) },
                onZoom = { step ->
                    val next = (zoom + step).coerceIn(0, MOST_ZOOM)
                    store.updatePrefs { p -> p.copy(display = p.display.copy(flippedZoom = next)) }
                },
                modifier = Modifier.fillMaxWidth().height(BAR),
            )
        }
    }
}

/** Where Home below is: the shelf, the game chosen on each, and each shelf's scroll. */
@Stable
internal class DualSelection {
    var shelf by mutableStateOf<String?>(null)
    val index = mutableStateMapOf<String, Int>()
    /** Which way the last change of shelf went, for the grid's slide. */
    var direction by mutableIntStateOf(0)
    val grids = HashMap<String, LazyGridState>()
    val lists = HashMap<String, List<GameCard>>()
}

/** One shelf of Home below: its games when Home's feed has them, else they are asked for ([platform], or the whole library). */
internal data class DualShelf(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val games: List<GameCard>? = null,
    val platform: PlatformId? = null,
)

internal const val RECENT = "recent"
private const val FAVOURITES = "favourites"
private const val NEW = "new"
private const val ALL = "all"

/** The shelves Home below offers, from Home's feed: those with games, then every system. */
internal fun dualShelves(feed: HomeFeed): List<DualShelf> = buildList {
    val recent = (feed.continuePlaying + feed.recentlyPlayed).distinctBy { it.id }
    if (recent.isNotEmpty()) add(DualShelf(RECENT, "Recent", FuseIcons.History, games = recent))
    if (feed.favorites.isNotEmpty()) add(DualShelf(FAVOURITES, "Favourites", FuseIcons.Heart, games = feed.favorites))
    if (feed.recentlyAdded.isNotEmpty()) add(DualShelf(NEW, "New", FuseIcons.Sparkles, games = feed.recentlyAdded))
    val systems = feed.systems.filter { it.gameCount > 0 }
    if (isNotEmpty() || systems.isNotEmpty()) add(DualShelf(ALL, "All", FuseIcons.Grid))
    systems.forEach { s -> add(DualShelf("system:${s.platform.id.value}", s.platform.shortName.ifBlank { s.platform.name }, FuseIcons.Gamepad, platform = s.platform.id)) }
}

/** A shelf's games: the feed's own, or the library's (all, or one system's) by title. Null while they load. */
@Composable
private fun rememberShelfGames(store: FuseStore, shelf: DualShelf): List<GameCard>? {
    if (shelf.games != null) return shelf.games
    val flow = remember(shelf.key) { store.library.games(GameQuery(platform = shelf.platform, sort = SortOrder.TITLE)) }
    val games by flow.collectAsState(initial = null)
    return games
}

/**
 * How many rows of icons [height] holds at [zoom]: as many as fit at a comfortable size (1), one
 * fewer and larger (0, down to one row), or one more and smaller (2), never smaller than a thumb.
 */
internal fun rowsFor(height: Dp, zoom: Int): Int {
    val fit = ((height + GAP) / (COMFORTABLE + GAP)).toInt().coerceAtLeast(1)
    val most = ((height + GAP) / (SMALLEST + GAP)).toInt().coerceAtLeast(1)
    val rows = when {
        zoom <= 0 -> fit - 1
        zoom == 1 -> fit
        else -> fit + 1
    }
    return rows.coerceIn(1, minOf(most, MAX_ROWS))
}

/** The icon size, in its art style's base, for [rows] rows in [height]. */
private fun cellFor(height: Dp, rows: Int): Dp = (height - TILE_ROOM * 2 - GAP * (rows - 1)) / rows

/** Whole columns of icons [width] shows, for paging by a screen. */
private fun visibleColumnsFor(width: Dp, height: Dp, rows: Int, art: io.github.matiyaaa.fuse.model.GameArtStyle): Int {
    val tile = art.tileSize(art.baseForHeight(cellFor(height, rows))).width
    return ((width + GAP) / (tile + GAP)).toInt().coerceAtLeast(1)
}

/** The shelves to choose from, as a row of large pills a thumb can hit, the chosen one filled. */
@Composable
private fun ShelfTabs(shelves: List<DualShelf>, chosen: String, gutter: Dp, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val row = rememberLazyListState()
    val at = shelves.indexOfFirst { it.key == chosen }
    // The chosen shelf stays in view as the right stick steps along them.
    LaunchedEffect(at) {
        if (at < 0) return@LaunchedEffect
        val visible = row.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == at }
        val end = row.layoutInfo.viewportEndOffset
        if (item == null || item.offset < 0 || item.offset + item.size > end) row.animateScrollToItem((at - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = row,
        modifier = modifier
            .dragScroll(row, Orientation.Horizontal)
            .fadingEdgesHorizontal(start = row.canScrollBackward, end = row.canScrollForward),
        contentPadding = PaddingValues(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(shelves, key = { it.key }) { s -> ShelfTab(s, s.key == chosen) { onPick(s.key) } }
    }
}

@Composable
private fun ShelfTab(shelf: DualShelf, selected: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val fill by fuselineColor(if (selected) c.text else c.text.copy(alpha = 0.08f), motion.tween(Durations.FAST), label = "shelf tab")
    val tint by fuselineColor(if (selected) c.ink else c.textMuted, motion.tween(Durations.FAST), label = "shelf tab text")
    Row(
        Modifier
            .height(TAB)
            .clip(PillShape)
            .background(fill)
            .fuseClickable(shape = PillShape, role = Role.Tab, onClickLabel = shelf.title, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
    ) {
        FuseIcon(shelf.icon, tint = { tint }, size = Size.iconS)
        FText(shelf.title, Fuse.type.label, color = { tint }, maxLines = 1)
    }
}

/** The games as icons in rows that run sideways, the chosen one lit; the mouse wheel scrolls it too. */
@Composable
private fun IconGrid(
    app: AppState,
    games: List<GameCard>,
    key: String,
    index: Int,
    rows: Int,
    gutter: Dp,
    focused: Boolean,
    grid: LazyGridState,
    onTap: (Int) -> Unit,
    onHold: (Int) -> Unit,
) {
    val art = LocalGameArt.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = art.baseForHeight(cellFor(maxHeight, rows))
        // A move by the controller keeps the chosen game whole on screen, with a column beside it.
        LaunchedEffect(key, index, rows) {
            val info = grid.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset - info.afterContentPadding
            if (item == null || item.offset.x < start + info.beforeContentPadding || item.offset.x + item.size.width > end) {
                grid.animateScrollToItem((index - index % rows - rows).coerceAtLeast(0))
            }
        }
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            state = grid,
            modifier = Modifier
                .fillMaxSize()
                .dragScroll(grid, Orientation.Horizontal)
                .fadingEdgesHorizontal(start = grid.canScrollBackward, end = grid.canScrollForward)
                // A mouse wheel turned up or down slides the grid along, as a finger does.
                .pointerInput(grid) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            val d = event.changes.fold(Offset.Zero) { acc, ch -> acc + ch.scrollDelta }
                            val amount = if (kotlin.math.abs(d.y) > kotlin.math.abs(d.x)) d.y else d.x
                            if (amount == 0f) continue
                            event.changes.forEach { it.consume() }
                            scope.launch { grid.animateScrollBy(amount * WHEEL_PX.toPx()) }
                        }
                    }
                },
            contentPadding = PaddingValues(horizontal = gutter, vertical = TILE_ROOM),
            horizontalArrangement = Arrangement.spacedBy(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            itemsIndexed(games, key = { _, g -> g.id.value }) { i, g ->
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                    GameIconTile(g, selected = i == index && focused, size = base, onClick = { onTap(i) }, onLongClick = { onHold(i) })
                }
            }
        }
    }
}

/**
 * Where the grid is, as on a 3DS: a slim track with the part on screen lit. A touch or a drag along
 * it takes the grid there.
 */
@Composable
private fun PositionBar(grid: LazyGridState, rows: Int, count: Int, modifier: Modifier) {
    val c = Fuse.colors
    val scope = rememberCoroutineScope()
    val columns = (count + rows - 1) / rows
    // The lit part: where the first column on screen starts, and how much of the whole is on screen.
    val shown by remember(grid, rows, columns) {
        derivedStateOf {
            val visible = grid.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || columns == 0) {
                0f to 1f
            } else {
                val firstColumn = visible.first().index / rows
                val width = visible.first().size.width.coerceAtLeast(1)
                val within = (grid.firstVisibleItemScrollOffset.toFloat() / width).coerceIn(0f, 1f)
                val span = (visible.last().index / rows - firstColumn + 1).toFloat()
                ((firstColumn + within) / columns) to (span / columns).coerceAtMost(1f)
            }
        }
    }
    if (columns <= 1 || shown.second >= 1f) {
        Spacer(modifier)
        return
    }
    fun go(fraction: Float) {
        val column = (fraction * columns - shown.second * columns / 2).toInt().coerceIn(0, columns - 1)
        scope.launch { grid.scrollToItem(column * rows) }
    }
    val track = c.text.copy(alpha = 0.1f)
    val lit = c.text.copy(alpha = 0.55f)
    Canvas(
        modifier
            .pointerInput(columns) { detectTapGestures { o -> go(o.x / size.width) } }
            .pointerInput(columns) { detectHorizontalDragGestures { change, _ -> go(change.position.x / size.width) } },
    ) {
        val h = TRACK.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(track, topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = r)
        val (start, span) = shown
        val w = (size.width * span).coerceAtLeast(h * 3)
        val x = ((size.width - w) * (start / (1f - span).coerceAtLeast(0.0001f))).coerceIn(0f, size.width - w)
        drawRoundRect(lit, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = r)
    }
}

/**
 * The buttons along the foot of the screen: Options, the icons' size, and Play with the game it
 * starts. Each shows its controller button, so the screen needs no line of hints.
 */
@Composable
private fun ButtonBar(
    app: AppState,
    chosen: GameCard?,
    gutter: Dp,
    canShrink: Boolean,
    canGrow: Boolean,
    onOptions: () -> Unit,
    onPlay: () -> Unit,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(horizontal = gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        BarButton(HintButton.OPTIONS, "Options", enabled = chosen != null, onClick = onOptions)
        ZoomButtons(canShrink, canGrow, onZoom)
        // Play takes the rest of the bar: the largest target on the screen, with the game it starts.
        PlayButton(app.gameConfirmLabel, chosen?.title, enabled = chosen != null, onClick = onPlay, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BarButton(glyph: HintButton, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(
        Modifier
            .height(BUTTON)
            .clip(PillShape)
            .background(c.text.copy(alpha = if (enabled) 0.1f else 0.05f))
            .fuseClickable(enabled = enabled, shape = PillShape, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = Space.s, end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        ButtonGlyph(glyph, size = GLYPH, color = c.text)
        FText(label, Fuse.type.bodyStrong, color = if (enabled) c.text else c.textFaint, maxLines = 1)
    }
}

/** Smaller and larger icons, as a 3DS's zoom: two halves of one pill. */
@Composable
private fun ZoomButtons(canShrink: Boolean, canGrow: Boolean, onZoom: (Int) -> Unit) {
    val c = Fuse.colors
    Row(Modifier.height(BUTTON).clip(PillShape).background(c.text.copy(alpha = 0.1f)), verticalAlignment = Alignment.CenterVertically) {
        // Zoomed in is fewer, larger icons; out, more and smaller.
        ZoomHalf(FuseIcons.ZoomOut, "Smaller icons", enabled = canShrink) { onZoom(1) }
        Box(Modifier.width(1.dp).height(BUTTON / 2).background(c.text.copy(alpha = 0.16f)))
        ZoomHalf(FuseIcons.ZoomIn, "Larger icons", enabled = canGrow) { onZoom(-1) }
    }
}

@Composable
private fun ZoomHalf(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        Modifier.size(BUTTON).fuseClickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, tint = if (enabled) c.text else c.textFaint, size = Size.iconM)
    }
}

/** Play, in the accent, with the game it starts under it. */
@Composable
private fun PlayButton(label: String, title: String?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val on = c.onAccent
    Row(
        modifier
            .height(BUTTON)
            .clip(PillShape)
            .background(if (enabled) c.accent else c.text.copy(alpha = 0.08f))
            .fuseClickable(enabled = enabled, shape = PillShape, role = Role.Button, onClickLabel = listOfNotNull(label, title).joinToString(" "), onClick = onClick)
            .padding(start = Space.s, end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        ButtonGlyph(HintButton.CONFIRM, size = GLYPH, color = if (enabled) on else c.textFaint)
        Column(Modifier.weight(1f)) {
            FText(label, Fuse.type.bodyStrong, color = if (enabled) on else c.textFaint, maxLines = 1)
            if (title != null) FText(title, Fuse.type.caption, color = on.copy(alpha = 0.82f), maxLines = 1)
        }
    }
}

/** Narrower than this, the screen's sides keep a smaller margin. */
private val NARROW = 600.dp

/** The shelves' row, a shelf's pill, the position bar, the bar of buttons and a button in it. */
private val SHELVES = 48.dp
private val TAB = 40.dp
private val POSITION = 20.dp
private val TRACK = 4.dp
private val BAR = 64.dp
private val BUTTON = 48.dp
private val GLYPH = 24.dp

/** Between icons, and the room kept round them for the chosen one's lift. */
private val GAP = 10.dp
private val TILE_ROOM = 8.dp
private val MIN_TILE = 72.dp

/** An icon's size at the zoom Home starts with, and the smallest any zoom makes one. */
private val COMFORTABLE = 104.dp
private val SMALLEST = 56.dp
private const val MOST_ZOOM = 2
private const val MAX_ROWS = 6

/** How far one notch of a mouse wheel slides the grid. */
private val WHEEL_PX = 48.dp
