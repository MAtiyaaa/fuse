package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
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
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.expandHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.shrinkHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
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
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsFavourite
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsSystem
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.baseForHeight
import io.github.matiyaaa.fuse.ui.shell.components.cornerFraction
import io.github.matiyaaa.fuse.ui.shell.components.tileSize
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Home on the touch screen below while the screen above shows what is chosen (flipped mode), set out
 * as a DS's lower screen is. The games are icons to touch in a grid that runs sideways, with a ring
 * round the chosen one, over the theme's room lit in that game's colour, under a rail of filters
 * (Recent, Favourites, New, All, then each system by its logo; a system's filter, chosen, fills with
 * its picture). A touch chooses a game and the screen above shows it; touching it again, or Play,
 * starts it. Along the foot a dock holds the chosen game (its cover and name), Options, the icons'
 * size and Play, each with its controller button, and a slim bar over it shows where the grid is
 * and slides it under a finger.
 */
@Composable
fun DualHome(app: AppState) {
    val store = app.store
    val feed by store.homeFeed.collectAsState()
    val prefs by store.prefs.collectAsState()
    // How big the icons are is this device's, kept with its screens.
    val zoom = prefs.display.flippedZoom.coerceIn(0, MOST_ZOOM)
    val systems = rememberSystems(app)
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
    SideEffect { if (games != null) sel.lists[shelf.key] = games }
    val content = app.focusZone == FocusZone.CONTENT

    // The screen above shows the chosen game; the dock's buttons carry their own glyphs, so the hint
    // line stays away and leaves the whole foot of the screen to them.
    PageEffect(chosen?.id, systems) {
        app.hero = chosen?.room(systems[chosen.shownPlatform])
        app.heroHeld = false
        app.hints = emptyList()
    }

    // Touches and buttons act on where Home below is when they land, read from live state: Compose
    // keeps a callback made from these from an earlier draw, with the shelf and game it saw then.
    val shelvesNow by rememberUpdatedState(shelves)
    val countNow by rememberUpdatedState(count)
    fun shelfNow(): String = (shelvesNow.firstOrNull { it.key == sel.shelf } ?: shelvesNow.first()).key
    fun indexNow(): Int = (sel.index[shelfNow()] ?: 0).coerceIn(0, (countNow - 1).coerceAtLeast(0))

    fun choose(i: Int) {
        val at = indexNow()
        if (i == at || i !in 0 until countNow) return
        Spotlight.moved(if (i > at) 1 else -1)
        sel.index[shelfNow()] = i
    }

    fun options(card: GameCard) {
        val extra = if (shelfNow() == RECENT && feed.continuePlaying.any { it.id == card.id }) {
            listOf(MenuAction("uncontinue", "Remove from Continue Playing", FuseIcons.Close, detail = "Comes back when you play it again", onSelect = {
                app.closeOverlays()
                app.dismissFromContinue(card)
            }))
        } else emptyList()
        app.openContextMenu(app.gameMenu(card, extra = extra))
    }

    fun showShelf(key: String) {
        val all = shelvesNow
        val to = all.indexOfFirst { it.key == key }
        val step = to - all.indexOfFirst { it.key == shelfNow() }
        if (to < 0 || step == 0) return
        sel.direction = if (step > 0) 1 else -1
        sel.shelf = key
        app.platform.sounds.play(SoundCue.MOVE)
    }

    fun stepShelf(step: Int): NavResult {
        val all = shelvesNow
        val next = all.getOrNull(all.indexOfFirst { it.key == shelfNow() } + step) ?: return NavResult.BLOCKED
        showShelf(next.key)
        return NavResult.MOVED
    }

    val accent by fuselineColor(chosen?.accent?.toColor() ?: Fuse.colors.accent, Fuse.motion.fade(Durations.SLOW), label = "dual accent")

    Box(Modifier.fillMaxSize()) {
        DualBackdrop({ accent }, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gutter = if (maxWidth < NARROW) Space.gutterCompact else Space.gutter
            // What is left for the grid once the top line, the filters, the position bar and the dock
            // have their room: the rows follow from it and the icons' size.
            val gridHeight = (maxHeight - Size.hudHeight - RAIL - POSITION - DOCK - DOCK_FOOT - Space.xs * 2).coerceAtLeast(MIN_TILE)
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
                    NavAction.PAGE_PREVIOUS -> stepShelf(-1)
                    NavAction.PAGE_NEXT -> stepShelf(1)
                    NavAction.SELECT -> chosen?.let { app.activateGame(it); NavResult.ACTIVATED } ?: NavResult.BLOCKED
                    NavAction.CONTEXT -> chosen?.let { options(it); NavResult.ACTIVATED } ?: NavResult.BLOCKED
                    else -> NavResult.IGNORED
                }
            }

            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(Size.hudHeight))
                FilterRail(
                    shelves, shelf.key, gutter,
                    onPick = { showShelf(it) },
                    modifier = Modifier.fillMaxWidth().height(RAIL),
                )
                Spacer(Modifier.height(Space.xs))
                val motion = Fuse.motion
                Swap(
                    targetState = shelf.key,
                    modifier = Modifier.fillMaxWidth().height(gridHeight),
                    transitionSpec = {
                        val enter = fadeIn(motion.fade(Durations.BASE))
                        val exit = fadeOut(motion.fade(Durations.FAST))
                        if (motion.reduced) enter togetherWith exit
                        else (slideInHorizontally(motion.tween(Durations.BASE, Curves.Enter)) { (it * 0.06f * sel.direction).toInt() } + enter) togetherWith exit
                    },
                    label = "dual shelf",
                ) { key ->
                    val current = key == shelf.key
                    val list = if (current) games ?: sel.lists[key] else sel.lists[key]
                    val at = if (current) index else sel.index[key] ?: 0
                    val of = shelves.firstOrNull { it.key == key }
                    when {
                        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                        list.isEmpty() -> EmptyState(FuseIcons.Library, "Nothing here yet", Modifier.fillMaxSize(), message = "Games you add show up here")
                        // A system's own shelf needs no system tag on each game, nor Favourites a heart on each.
                        else -> CompositionLocalProvider(
                            LocalTileShowsSystem provides (of?.platform == null),
                            LocalTileShowsFavourite provides (key != FAVOURITES),
                        ) {
                            IconGrid(
                                list, key, at, rows, gutter, content && current,
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
                }
                Spacer(Modifier.height(Space.xs))
                if (count > 0) {
                    PositionBar(grid, rows, count, Modifier.fillMaxWidth().padding(horizontal = gutter + Space.l).height(POSITION))
                } else {
                    Spacer(Modifier.height(POSITION))
                }
                Dock(
                    app, chosen, canShrink, canGrow,
                    onOptions = { chosen?.let(::options) },
                    onPlay = { chosen?.let(app::activateGame) },
                    onZoom = { step ->
                        val next = (zoom + step).coerceIn(0, MOST_ZOOM)
                        store.updatePrefs { p -> p.copy(display = p.display.copy(flippedZoom = next)) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter).height(DOCK),
                )
                Spacer(Modifier.height(DOCK_FOOT))
            }
        }
    }
}

/** Where Home below is: the shelf, the game chosen on each, and each shelf's scroll and games. */
@Stable
internal class DualSelection {
    var shelf by mutableStateOf<String?>(null)
    val index = mutableStateMapOf<String, Int>()
    /** Which way the last change of shelf went, for the grid's slide. */
    var direction by mutableStateOf(0)
    val grids = HashMap<String, LazyGridState>()
    val lists = HashMap<String, List<GameCard>>()
}

/** A system's look on Home below: its picture, its logo, its name, colour and how many games. */
internal data class ShelfPicture(val model: Any?, val logo: Any?, val name: String, val accent: Long, val games: Int)

/** One shelf of Home below: its games when Home's feed has them, else they are asked for ([platform], or the whole library). */
internal data class DualShelf(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val games: List<GameCard>? = null,
    val platform: PlatformId? = null,
    val picture: ShelfPicture? = null,
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
    systems.forEach { s ->
        val art = s.art
        add(
            DualShelf(
                "system:${s.platform.id.value}", s.platform.shortName.ifBlank { s.platform.name }, FuseIcons.Gamepad, platform = s.platform.id,
                picture = ShelfPicture(art.hero ?: art.boxart ?: art.square, art.logo, s.platform.name, s.platform.accent, s.gameCount),
            ),
        )
    }
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

// ------------------------------------------------------------------------------------- backdrop

/** What lies behind Home below: the theme's room, lit softly in the chosen game's colour, with shades for the filters and the dock. */
@Composable
private fun DualBackdrop(accent: () -> Color, modifier: Modifier) {
    val c = Fuse.colors
    Box(modifier) {
        RoomLook(accent)
        // The filters and the dock read over the room whatever its colour.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val top = Brush.verticalGradient(0f to c.ink.copy(alpha = 0.4f), 0.3f to Color.Transparent)
                val foot = Brush.verticalGradient(0.66f to Color.Transparent, 1f to c.ink.copy(alpha = 0.6f))
                onDrawBehind {
                    drawRect(top)
                    drawRect(foot)
                }
            },
        )
    }
}

/** Home below's own room: the theme's, lit softly from above in the chosen game's colour, with a fine grain of dots. */
@Composable
private fun RoomLook(accent: () -> Color) {
    val c = Fuse.colors
    Box(
        Modifier.fillMaxSize().drawBehind {
            val a = accent()
            drawRect(
                Brush.radialGradient(
                    0f to a.copy(alpha = 0.26f), 0.6f to a.copy(alpha = 0.07f), 1f to Color.Transparent,
                    center = Offset(size.width * 0.3f, 0f), radius = size.maxDimension * 0.85f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    0f to a.copy(alpha = 0.12f), 1f to Color.Transparent,
                    center = Offset(size.width, size.height), radius = size.maxDimension * 0.6f,
                ),
            )
        },
    ) {
        Dots(Modifier.fillMaxSize(), c.text.copy(alpha = if (c.isDark) 0.05f else 0.07f))
    }
}

/** A fine, even grain of dots, fading towards the foot, drawn once for its size. */
@Composable
private fun Dots(modifier: Modifier, color: Color) {
    Box(
        modifier.drawWithCache {
            val step = DOT_STEP.toPx()
            val r = DOT.toPx() / 2
            val dots = Path()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    dots.addOval(Rect(Offset(x, y), r))
                    x += step
                }
                y += step
            }
            val fade = Brush.verticalGradient(0f to color, 1f to color.copy(alpha = color.alpha * 0.2f))
            onDrawBehind { drawPath(dots, fade) }
        },
    )
}

// -------------------------------------------------------------------------------------- filters

/** The shelves to choose from: pills a thumb can hit, a system's by its logo, the chosen one filled. */
@Composable
private fun FilterRail(
    shelves: List<DualShelf>,
    chosen: String,
    gutter: Dp,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = rememberLazyListState()
    val at = shelves.indexOfFirst { it.key == chosen }
    // The chosen shelf stays in view as the right stick steps along them.
    LaunchedEffect(at) {
        if (at < 0) return@LaunchedEffect
        val item = row.layoutInfo.visibleItemsInfo.firstOrNull { it.index == at }
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
        items(shelves, key = { it.key }) { s ->
            val picture = s.picture
            if (picture != null) SystemFilter(s, picture, s.key == chosen) { onPick(s.key) }
            else Filter(s, s.key == chosen) { onPick(s.key) }
        }
    }
}

/** A shelf of games: its icon and name, filled when chosen. */
@Composable
private fun Filter(shelf: DualShelf, selected: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val fill by fuselineColor(if (selected) c.text else c.surface.copy(alpha = 0.62f), motion.tween(Durations.FAST), label = "filter")
    val tint by fuselineColor(if (selected) c.ink else c.text, motion.tween(Durations.FAST), label = "filter text")
    Row(
        Modifier
            .height(FILTER)
            .clip(PillShape)
            .background(fill)
            .border(Size.stroke, c.text.copy(alpha = if (selected) 0f else 0.12f), PillShape)
            .fuseClickable(shape = PillShape, role = Role.Tab, onClickLabel = shelf.title, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
    ) {
        FuseIcon(shelf.icon, tint = { tint }, size = Size.iconS)
        FText(shelf.title, Fuse.type.label, color = { tint }, maxLines = 1)
    }
}

/**
 * A system's filter: its logo (its short name until there is one). Chosen, the pill fills with the
 * system's colour, opening from its middle as a widening circle, with its picture through it where
 * it has one; the pill gives a small bounce, glows in its colour, a light runs across it once, and it
 * opens a little to say how many games the system has.
 */
@Composable
private fun SystemFilter(shelf: DualShelf, picture: ShelfPicture, selected: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val open by fuselineFloat(if (selected) 1f else 0f, if (selected) motion.tween(Durations.SLOW, Curves.Enter) else motion.tween(Durations.BASE, Curves.Exit), label = "system filter")
    val tint = picture.accent.toColor()
    // The fill runs from the system's colour to a deeper shade of it; writing is white on it, or near
    // black on a colour light enough that white would not read.
    val deep = lerp(tint, Color.Black, 0.2f)
    val onTint = if (deep.luminance() > LIGHT_TINT) Color(0xFF14161A) else Color.White
    val ring by fuselineColor(if (selected) Color.White.copy(alpha = 0.34f) else c.text.copy(alpha = 0.12f), motion.tween(Durations.BASE), label = "system filter ring")
    val ink by fuselineColor(if (selected) onTint else c.text, motion.tween(Durations.BASE), label = "system filter logo")
    // A choice, not the first draw, bounces the pill and runs a light across it.
    val pop = remember { FuselineValue(0f) }
    val sweep = remember { FuselineValue(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(selected) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (!selected || motion.reduced) return@LaunchedEffect
        launch {
            pop.snapTo(1f)
            pop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 420f))
        }
        sweep.snapTo(0f)
        sweep.animateTo(1f, motion.tween(SWEEP_MS, Curves.Standard))
    }
    Box(
        Modifier
            .height(FILTER)
            .graphicsLayer {
                val s = 1f + 0.08f * pop.value
                scaleX = s
                scaleY = s
                // A glow in the system's colour under the chosen pill.
                shadowElevation = GLOW.toPx() * open
                shape = PillShape
                ambientShadowColor = tint
                spotShadowColor = tint
            }
            .clip(PillShape)
            .background(c.surface.copy(alpha = 0.62f))
            .fuseClickable(shape = PillShape, role = Role.Tab, onClickLabel = shelf.title, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The fill, inside the pill only, opening from its middle and settling from a little larger.
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    val r = open
                    if (r <= 0.001f) return@drawWithContent
                    if (r >= 0.999f) {
                        drawContent()
                        return@drawWithContent
                    }
                    val o = Offset(size.width / 2, size.height / 2)
                    val far = hypot(size.width / 2, size.height / 2)
                    clipPath(Path().apply { addOval(Rect(o, far * r)) }) { this@drawWithContent.drawContent() }
                }
                .graphicsLayer {
                    val s = 1.18f - 0.18f * open
                    scaleX = s
                    scaleY = s
                },
        ) {
            // The system's colour, lit from the top left.
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val field = Brush.linearGradient(
                        0f to tint, 0.5f to deep, 1f to lerp(tint, Color.Black, 0.42f),
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    )
                    val light = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent,
                        center = Offset(size.width * 0.2f, 0f), radius = size.width * 0.6f,
                    )
                    onDrawBehind {
                        drawRect(field)
                        drawRect(light)
                    }
                },
            )
            // Its picture through the colour, where it has one.
            if (picture.model != null) {
                Artwork(picture.model, Modifier.matchParentSize().graphicsLayer { alpha = 0.6f }, contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.5f), tint.copy(alpha = 0.12f)))))
            }
            // The light running across once, a slanted band.
            Box(
                Modifier.matchParentSize().drawBehind {
                    val t = sweep.value
                    if (t >= 0.999f) return@drawBehind
                    val band = size.height * 1.6f
                    val x = -band + (size.width + band * 2) * t
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.42f), 1f to Color.Transparent,
                            start = Offset(x - band / 2, size.height), end = Offset(x + band / 2, 0f),
                        ),
                    )
                },
            )
        }
        Row(
            Modifier.padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name: @Composable () -> Unit = { FText(shelf.title, Fuse.type.label, color = { ink }, maxLines = 1) }
            if (picture.logo != null) {
                // As wide as the logo is at its height, once its shape is known.
                var aspect by remember(picture.logo) { mutableStateOf(FILTER_LOGO_ASPECT) }
                Artwork(
                    picture.logo,
                    Modifier.height(FILTER_LOGO).width((FILTER_LOGO * aspect).coerceIn(FILTER_LOGO, FILTER_LOGO_WIDE)),
                    contentScale = ContentScale.Fit,
                    tint = ink,
                    onAspect = { aspect = it },
                    fallback = name,
                )
            } else {
                name()
            }
            // Chosen, how many games it has.
            Appear(
                selected,
                enter = expandHorizontally(motion.tween(Durations.BASE, Curves.Standard), expandFrom = Alignment.Start) + fadeIn(motion.fade(Durations.BASE)),
                exit = shrinkHorizontally(motion.tween(Durations.FAST, Curves.Standard), shrinkTowards = Alignment.Start) + fadeOut(motion.fade(Durations.FAST)),
                label = "system filter count",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(Space.s))
                    Box(Modifier.size(3.dp).clip(PillShape).background(onTint.copy(alpha = 0.7f)))
                    Spacer(Modifier.width(Space.s))
                    FText("${picture.games}", Fuse.type.label, color = onTint, maxLines = 1)
                }
            }
        }
        // The ring: quiet when not chosen, a fine light edge when it is.
        Box(Modifier.matchParentSize().border(Size.stroke, ring, PillShape))
    }
}

// ----------------------------------------------------------------------------------------- grid

/** The games as icons in rows that run sideways, a ring round the chosen one; the mouse wheel scrolls it too. */
@Composable
private fun IconGrid(
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
                val chosen = i == index
                Box(Modifier.fillMaxHeight().cursor(chosen && focused, art.tileSize(base)), contentAlignment = Alignment.Center) {
                    GameIconTile(g, selected = chosen && focused, size = base, onClick = { onTap(i) }, onLongClick = { onHold(i) })
                }
            }
        }
    }
}

/**
 * The chosen game's ring, as a DS's cursor: an accent outline just outside the icon, fading in as
 * it arrives and out as it leaves. It never pulses, so a screen left on Home below stays still.
 */
@Composable
private fun Modifier.cursor(on: Boolean, tile: androidx.compose.ui.unit.DpSize): Modifier {
    val c = Fuse.colors
    val shown by fuselineFloat(if (on) 1f else 0f, Fuse.motion.fade(Durations.FAST), label = "cursor")
    val fraction = LocalGameArt.current.cornerFraction()
    return drawWithContent {
        drawContent()
        val a = shown
        if (a <= 0.01f) return@drawWithContent
        val w = tile.width.toPx()
        val h = tile.height.toPx()
        val out = CURSOR_GAP.toPx()
        val stroke = CURSOR.toPx()
        val left = (size.width - w) / 2 - out
        val top = (size.height - h) / 2 - out
        val r = minOf(w, h) * fraction + out
        drawRoundRect(
            c.accent.copy(alpha = a),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(w + out * 2, h + out * 2),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(stroke),
        )
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
    val lit = c.text.copy(alpha = 0.6f)
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
        val w = (size.width * span).coerceAtLeast(h * 4)
        val x = ((size.width - w) * (start / (1f - span).coerceAtLeast(0.0001f))).coerceIn(0f, size.width - w)
        drawRoundRect(lit, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = r)
    }
}

// ----------------------------------------------------------------------------------------- dock

/**
 * The dock along the foot: the chosen game (its cover, name and system), then Options, the icons'
 * size and Play. Each button shows its controller button, so the screen needs no line of hints.
 */
@Composable
private fun Dock(
    app: AppState,
    chosen: GameCard?,
    canShrink: Boolean,
    canGrow: Boolean,
    onOptions: () -> Unit,
    onPlay: () -> Unit,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Row(
        modifier
            .shadow(DOCK_SHADOW, shape, clip = false)
            .clip(shape)
            .background(c.surfaceRaised.copy(alpha = 0.86f))
            .border(Size.stroke, c.text.copy(alpha = 0.1f), shape)
            .padding(start = Space.s, end = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Chosen(chosen, Modifier.weight(1f))
        RoundButton(FuseIcons.More, HintButton.OPTIONS, "Options", enabled = chosen != null, onClick = onOptions)
        ZoomButtons(canShrink, canGrow, onZoom)
        PlayButton(app.gameConfirmLabel, enabled = chosen != null, onClick = onPlay)
    }
}

/** The chosen game in the dock: its cover, its name and where it is from, or nothing yet. */
@Composable
private fun Chosen(card: GameCard?, modifier: Modifier) {
    val c = Fuse.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        if (card == null) return@Row
        val accent = card.accent.toColor()
        Artwork(
            card.art.boxart ?: card.art.square ?: card.art.icon,
            Modifier.size(COVER).clip(RoundedCornerShape(Fuse.geometry.control)),
            contentScale = ContentScale.Crop,
            fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.ICON, showText = false) },
        )
        Column(Modifier.weight(1f)) {
            FText(card.title, Fuse.type.bodyStrong, maxLines = 1)
            val about = listOfNotNull(card.platformShort, card.lastPlayedAt?.let { agoText(it) }).joinToString("  ·  ")
            FText(about, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** A round button with an icon, its controller button in its corner. */
@Composable
private fun RoundButton(icon: ImageVector, glyph: HintButton, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(Modifier.size(BUTTON)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(PillShape)
                .background(c.text.copy(alpha = if (enabled) 0.1f else 0.05f))
                .fuseClickable(enabled = enabled, shape = PillShape, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(icon, tint = if (enabled) c.text else c.textFaint, size = Size.iconM)
        }
        ButtonGlyph(glyph, size = CORNER_GLYPH, color = c.text, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
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

/** Play, in the accent, with its controller button. */
@Composable
private fun PlayButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val on = c.onAccent
    Row(
        Modifier
            .height(BUTTON)
            .clip(PillShape)
            .background(if (enabled) c.accent else c.text.copy(alpha = 0.08f))
            .fuseClickable(enabled = enabled, shape = PillShape, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = Space.s, end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        ButtonGlyph(HintButton.CONFIRM, size = GLYPH, color = if (enabled) on else c.textFaint)
        FText(label, Fuse.type.bodyStrong, color = if (enabled) on else c.textFaint, maxLines = 1)
    }
}

/** Narrower than this, the screen's sides keep a smaller margin. */
private val NARROW = 600.dp

/** The filters' rail, a filter, a system's logo in one, and the widest a logo may be. */
private val RAIL = 48.dp
private val FILTER = 40.dp
private val FILTER_LOGO = 18.dp
private val FILTER_LOGO_WIDE = 84.dp
/** A logo's shape until its own is known: as most system logos are, a little over twice as wide as high. */
private const val FILTER_LOGO_ASPECT = 2.4f
/** A chosen system filter's glow in its colour, and how long its light takes to run across. */
private val GLOW = 10.dp
private const val SWEEP_MS = 640
/** A system colour this light or lighter takes near black writing rather than white (white would fall under 3:1). */
private const val LIGHT_TINT = 0.3f

/** The position bar and its track. */
private val POSITION = 18.dp
private val TRACK = 4.dp

/** The dock, the room under it, its shadow, a button in it, the cover in it, and the glyphs. */
private val DOCK = 64.dp
private val DOCK_FOOT = 10.dp
private val DOCK_SHADOW = 18.dp
private val BUTTON = 44.dp
private val COVER = 44.dp
private val GLYPH = 22.dp
private val CORNER_GLYPH = 18.dp

/** Between icons, the room kept round them for the lift and the ring, and the ring itself. */
private val GAP = 12.dp
private val TILE_ROOM = 9.dp
private val CURSOR = 3.dp
private val CURSOR_GAP = 4.dp
private val MIN_TILE = 72.dp

/** An icon's size at the zoom Home starts with, and the smallest any zoom makes one. */
private val COMFORTABLE = 104.dp
private val SMALLEST = 56.dp
private const val MOST_ZOOM = 2
private const val MAX_ROWS = 6

/** The grain of dots behind Home below. */
private val DOT_STEP = 18.dp
private val DOT = 2.dp

/** How far one notch of a mouse wheel slides the grid. */
private val WHEEL_PX = 48.dp
