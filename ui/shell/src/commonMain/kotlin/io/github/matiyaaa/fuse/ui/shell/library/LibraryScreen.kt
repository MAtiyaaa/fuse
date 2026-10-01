package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import io.github.matiyaaa.fuse.ui.designsystem.media.heroDecodePx
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.gameConfirmLabel
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.app.roomArt
import io.github.matiyaaa.fuse.ui.shell.collections.addGamesPicker
import io.github.matiyaaa.fuse.ui.shell.components.GameCoverTile
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.tileSize
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsSystem
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.GameSet
import io.github.matiyaaa.fuse.ui.shell.systems.SystemShowcase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a library view lists. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data class OfPlatform(val platform: PlatformId) : LibraryScope
    data class OfCollection(val collection: CollectionId, val name: String) : LibraryScope
}

/**
 * Views of the whole library. Missing, Hidden and Removed only appear while they have games; their
 * games open a menu to restore or forget them instead of playing.
 */
enum class LibrarySegment(val label: String, val set: GameSet) {
    ALL("All", GameSet.LIBRARY),
    FAVORITES("Favourites", GameSet.LIBRARY),
    RECENT("Recently played", GameSet.LIBRARY),
    MISSING("Missing", GameSet.MISSING),
    HIDDEN("Hidden", GameSet.HIDDEN),
    REMOVED("Removed", GameSet.REMOVED),
}

/** The buttons at the end of the Library header. */
enum class LibraryButton { COLLECTIONS, ADD_GAMES, SYSTEM, SORT, VIEW }

/** Remembered per library view: which game was selected (by id, so re-sorting keeps it), and where focus was. */
@Stable
class LibraryViewState {
    val grid = GridSelection()
    var selectedId by mutableStateOf<GameId?>(null)
    var segment by mutableStateOf(LibrarySegment.ALL)
    /** The system the whole library is narrowed to, or null for every system. */
    var system by mutableStateOf<PlatformId?>(null)
    var inHeader by mutableStateOf(false)
    var headerIndex by mutableIntStateOf(0)
    var layoutOverride by mutableStateOf<LibraryLayout?>(null)
    var showHidden by mutableStateOf(false)

    /**
     * Selects [index] of [list] and remembers that game. Only the user's own moves pin a game, so a
     * list that reorders before the user moves keeps the first game selected.
     */
    fun pick(index: Int, list: List<GameCard>?) {
        grid.index = index
        selectedId = list?.getOrNull(index)?.id
    }
}

@Composable
fun LibraryScreen(app: AppState, scope: LibraryScope) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val platforms by store.library.platforms.collectAsState()
    val key = when (scope) {
        LibraryScope.All -> "lib.all"
        is LibraryScope.OfPlatform -> "lib.p.${scope.platform}"
        is LibraryScope.OfCollection -> "lib.c.${scope.collection.value}"
    }
    // A system or collection opens on its first game; Back from a game returns to where you were.
    val state = if (scope == LibraryScope.All) {
        rememberRouteState(app.navigator, key) { LibraryViewState() }
    } else {
        rememberPageState(app.navigator, key) { LibraryViewState() }
    }
    val sort = prefs.librarySort

    // How many games the extra views hold, so they only show when there is something in them.
    val extraCounts by remember(scope) {
        if (scope != LibraryScope.All) {
            flowOf(emptyMap())
        } else {
            combine(
                listOf(LibrarySegment.MISSING, LibrarySegment.HIDDEN, LibrarySegment.REMOVED).map { s ->
                    store.library.games(GameQuery(set = s.set)).map { s to it.size }
                },
            ) { it.toMap() }
        }
    }.collectAsState(initial = emptyMap())
    val segments = if (scope != LibraryScope.All) emptyList() else LibrarySegment.entries.filter { s ->
        s.set == GameSet.LIBRARY || (extraCounts[s] ?: 0) > 0 || state.segment == s
    }
    val collectionsOn = prefs.collectionsEnabled
    val allCollections by store.collections.collections.collectAsState()
    // A collection of the user's own (not a series Fuse keeps up to date) can be edited here.
    val ownCollection = (scope as? LibraryScope.OfCollection)?.let { s -> allCollections.firstOrNull { it.id == s.collection } }
        ?.takeIf { it.kind != io.github.matiyaaa.fuse.model.CollectionKind.SERIES }
    val buttons = buildList {
        if (scope == LibraryScope.All && collectionsOn) add(LibraryButton.COLLECTIONS)
        if (ownCollection != null) add(LibraryButton.ADD_GAMES)
        if (scope == LibraryScope.All) add(LibraryButton.SYSTEM)
        add(LibraryButton.SORT)
        add(LibraryButton.VIEW)
    }
    val header: List<HeaderItem> = segments.map { HeaderItem.View(it) } + buttons.map { HeaderItem.Button(it) }

    val segment = state.segment
    val query = when (scope) {
        LibraryScope.All -> when (segment) {
            LibrarySegment.ALL -> GameQuery(platform = state.system, sort = sort, includeHidden = state.showHidden)
            LibrarySegment.FAVORITES -> GameQuery(platform = state.system, favoritesOnly = true, sort = sort)
            LibrarySegment.RECENT -> GameQuery(platform = state.system, sort = SortOrder.RECENTLY_PLAYED)
            else -> GameQuery(platform = state.system, sort = sort, set = segment.set)
        }
        is LibraryScope.OfPlatform -> GameQuery(platform = scope.platform, sort = sort, includeHidden = state.showHidden)
        is LibraryScope.OfCollection -> GameQuery(collection = scope.collection, sort = sort)
    }
    val gamesFlow = remember(query) { store.library.games(query) }
    val games by gamesFlow.collectAsState(initial = null)

    val platformId = (scope as? LibraryScope.OfPlatform)?.platform
    val layoutFlow = remember(platformId) { store.settings.observe(ScopedSettings.Layout, platformId, null) }
    val resolvedLayout by layoutFlow.collectAsState(initial = null)
    val layout = state.layoutOverride ?: resolvedLayout?.value?.takeIf { resolvedLayout?.isDefault == false } ?: prefs.defaultLayout

    // Recently played only lists games that were played.
    val list = games?.let { g -> if (scope == LibraryScope.All && segment == LibrarySegment.RECENT) g.filter { it.lastPlayedAt != null } else g }
    // Keep the same game selected when the list changes (new downloads, sorting, layout switches).
    LaunchedEffect(list) {
        if (list == null) return@LaunchedEffect
        val idx = state.selectedId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx >= 0) state.grid.index = idx else state.grid.clamp(list.size)
    }
    val selectedCard = list?.getOrNull(state.grid.index)
    // The next tiles' art and the stage logos are ready before the selection reaches them.
    val tileArt = remember(list, layout) {
        list.orEmpty().map { a ->
            when (layout) {
                LibraryLayout.COVER_GRID -> a.art.boxart ?: a.art.grid ?: a.art.square ?: a.art.icon
                LibraryLayout.CAPSULE -> a.art.hero ?: a.art.grid ?: a.art.boxart
                else -> a.art.square ?: a.art.icon ?: a.art.boxart ?: a.art.grid
            }
        }
    }
    PrefetchArt(tileArt, state.grid.index, size = LocalTileMetrics.current.icon * 1.4f)
    PrefetchArt(remember(list) { list.orEmpty().map { it.art.logo } }, state.grid.index, size = 360.dp)
    val special = scope == LibraryScope.All && segment.set != GameSet.LIBRARY
    val systemCard = platforms.firstOrNull { it.platform.id == platformId }
    // A game with its own background image shows it; any other game shows its system's background,
    // so a system's page keeps one room while moving between its games.
    val systems = rememberSystems(app)
    // The rooms of the games next to the selection, decoded as the background decodes them, so
    // moving on shows them at once.
    val heroDp = with(androidx.compose.ui.platform.LocalDensity.current) { Fuse.quality.heroDecodePx.toDp() }
    PrefetchArt(remember(list, systems) { list.orEmpty().map { roomArt(it.art, systems[it.platformId]) } }, state.grid.index, size = heroDp, limit = 2)
    val gameSystem = selectedCard?.let { systems[it.platformId] }
    LaunchedEffect(selectedCard?.id, selectedCard?.art, special, systemCard?.art, gameSystem?.art) {
        app.hero = when {
            selectedCard != null -> selectedCard.room(gameSystem)
            systemCard != null -> HeroSource(systemCard.platform.id, systemCard.art.hero, systemCard.platform.accent.toColor())
            else -> null
        }
        app.hints = when {
            selectedCard == null -> emptyList()
            special -> listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, app.gameConfirmLabel), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))
        }
    }

    var columns by remember { mutableIntStateOf(6) }

    fun options(card: GameCard) {
        if (special) {
            app.openContextMenu(setMenu(app, card, segment))
            return
        }
        val remove = ownCollection?.let { c ->
            listOf(MenuAction("uncollect", "Remove from ${c.name}", FuseIcons.Minus, detail = "The game stays in your library", onSelect = {
                app.closeOverlays()
                app.scope.launch { store.collections.remove(c.id, card.id) }
            }))
        }.orEmpty()
        val base = app.gameMenu(card, extra = remove)
        app.openContextMenu(base.copy(actions = base.actions + viewActions(app, state, platformId, layout, sort)))
    }

    fun press(button: LibraryButton) {
        when (button) {
            LibraryButton.COLLECTIONS -> app.go(Route.Collections)
            LibraryButton.ADD_GAMES -> ownCollection?.let { app.addGamesPicker(it.id, it.name) }
            LibraryButton.SYSTEM -> app.choice = systemPicker(app, state, platforms)
            LibraryButton.SORT -> app.choice = sortPicker(app, sort)
            LibraryButton.VIEW -> app.choice = layoutPicker(app, state, platformId, layout)
        }
    }

    fun choose(s: LibrarySegment) {
        if (state.segment != s) {
            state.segment = s
            state.grid.index = 0
            state.selectedId = null
        }
    }

    // The grids live here, so a system's header can fold away as you move down its games.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    // Dragging the grid by touch decides the fold from the scroll; a controller decides it from the selection.
    val dragged by gridState.interactionSource.collectIsDraggedAsState()
    val listDragged by listState.interactionSource.collectIsDraggedAsState()
    var touchScroll by remember { mutableStateOf(false) }
    LaunchedEffect(dragged, listDragged) { if (dragged || listDragged) touchScroll = true }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        touchScroll = false
        val count = list?.size ?: 0
        if (state.inHeader && header.isNotEmpty()) {
            state.headerIndex = state.headerIndex.coerceIn(0, header.lastIndex)
            fun focus(i: Int): NavResult {
                if (i !in header.indices) return NavResult.BLOCKED
                state.headerIndex = i
                // Moving onto a view switches to it straight away, like tabs.
                (header[i] as? HeaderItem.View)?.let { choose(it.segment) }
                return NavResult.MOVED
            }
            return@InputLayer when (e.action) {
                NavAction.LEFT -> focus(state.headerIndex - 1)
                NavAction.RIGHT -> focus(state.headerIndex + 1)
                NavAction.SELECT -> when (val item = header[state.headerIndex]) {
                    is HeaderItem.Button -> { press(item.button); NavResult.ACTIVATED }
                    is HeaderItem.View -> { state.inHeader = false; NavResult.MOVED }
                }
                NavAction.DOWN -> { state.inHeader = false; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val effective = when (layout) {
                    LibraryLayout.CAPSULE -> when (e.action) {
                        NavAction.UP -> null
                        NavAction.DOWN -> null
                        else -> e.action
                    }
                    LibraryLayout.COMPACT_LIST -> when (e.action) {
                        NavAction.LEFT, NavAction.RIGHT -> null
                        else -> e.action
                    }
                    else -> e.action
                }
                val r = if (effective == null) NavResult.IGNORED else when (layout) {
                    LibraryLayout.CAPSULE -> state.grid.move(effective, count, columns = count.coerceAtLeast(1))
                    LibraryLayout.COMPACT_LIST -> state.grid.move(effective, count, columns = 1, pageRows = 8)
                    else -> state.grid.move(effective, count, columns)
                }
                if (r == NavResult.MOVED) state.pick(state.grid.index, list)
                if (r == NavResult.IGNORED && e.action == NavAction.UP && header.isNotEmpty()) {
                    state.inHeader = true
                    state.headerIndex = header.indexOfFirst { it is HeaderItem.View && it.segment == state.segment }.coerceAtLeast(0)
                    NavResult.MOVED
                } else if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT || e.action == NavAction.DOWN)) {
                    NavResult.BLOCKED
                } else r
            }
            NavAction.SELECT -> {
                when {
                    selectedCard == null -> Unit
                    special -> options(selectedCard)
                    else -> app.activateGame(selectedCard)
                }
                if (selectedCard != null) NavResult.ACTIVATED else NavResult.BLOCKED
            }
            NavAction.CONTEXT -> {
                if (selectedCard != null) options(selectedCard)
                else app.openContextMenu(ContextMenuSpec("Library", actions = viewActions(app, state, platformId, layout, sort)))
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = LocalTileMetrics.current
        val maxH = maxHeight
        val maxW = maxWidth
        val gridFocused = !state.inHeader && app.focusZone == FocusZone.CONTENT
        fun tapAt(i: Int, cards: List<GameCard>) {
            app.focusZone = FocusZone.CONTENT
            state.inHeader = false
            when {
                special -> { state.pick(i, cards); options(cards[i]) }
                state.grid.index == i -> app.activateGame(cards[i])
                else -> state.pick(i, cards)
            }
        }
        val compactHeader = maxH < 560.dp
        // Inside a system, saying which system each game is for says nothing.
        val inSystem = systemCard != null
        fun stageOf(card: GameCard?) = card?.stage()?.let { if (inSystem) it.copy(eyebrow = null) else it }
        // A system's page folds its header away once you are past the first row, so more games fit.
        val folded = when {
            systemCard == null || list.isNullOrEmpty() || layout == LibraryLayout.CAPSULE -> false
            touchScroll -> if (layout == LibraryLayout.COMPACT_LIST) listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 else gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 24
            layout == LibraryLayout.COMPACT_LIST -> state.grid.index >= 3
            else -> state.grid.index >= columns
        }
        val collapse by animateFloatAsState(if (folded) 1f else 0f, Fuse.motion.followSpring(), label = "system fold")
        // Like the Systems screen: the art pack's panel on the right unless there is a background image.
        if (systemCard != null) {
            AnimatedVisibility(
                visible = systemCard.art.hero == null && selectedCard?.art?.hero == null,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(Fuse.motion.fade(Durations.SLOW)),
                exit = fadeOut(Fuse.motion.fade(Durations.BASE)),
            ) {
                SystemShowcase(systemCard, Modifier.fillMaxHeight().width(maxH * 0.46f))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (systemCard != null) lerp(if (compactHeader) Space.xs else Space.m, Space.xxs, collapse) else 0.dp))
            LibraryHeader(
                app = app,
                scope = scope,
                platform = systemCard,
                compact = compactHeader,
                collapse = collapse,
                system = platforms.firstOrNull { it.platform.id == state.system },
                count = list?.size,
                header = header,
                sort = sort,
                layout = layout,
                state = state,
                onView = { i, s -> state.headerIndex = i; choose(s); state.inHeader = false },
                onButton = { i, b -> state.headerIndex = i; press(b) },
                // The box art view's stage keeps room on its right for the toolbar once folded.
                foldTools = inSystem && layout == LibraryLayout.ICON,
            )
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> LibraryEmpty(scope, segment)
                else -> CompositionLocalProvider(LocalTileShowsSystem provides !inSystem) {
                  when (layout) {
                    LibraryLayout.ICON -> {
                        // The game's logo moves up with the folding header and makes room for another row.
                        // Inside a system the stage is smaller, so more games fit from the start, and
                        // folded it shrinks to one line beside the toolbar, so the games get the height.
                        val stage = if (inSystem) (maxH * 0.14f).coerceIn(92.dp, 124.dp) else (maxH * 0.22f).coerceIn(110.dp, 200.dp)
                        val logo = when {
                            !inSystem -> lerp(84.dp, 60.dp, collapse)
                            compactHeader -> lerp(56.dp, 32.dp, collapse)
                            else -> lerp(64.dp, 40.dp, collapse)
                        }
                        val foldedStage = if (inSystem) logo + 4.dp else stage * 0.66f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(lerp(stage, foldedStage, collapse))
                                .padding(start = Space.gutter, end = Space.gutter + if (inSystem) lerp(0.dp, 320.dp, collapse) else 0.dp),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Stage(
                                stageOf(selectedCard), showLogo = prefs.showLogo, logoHeight = logo,
                                titleStyle = if (inSystem) androidx.compose.ui.text.lerp(Fuse.type.display, Fuse.type.title, collapse) else Fuse.type.hero,
                                fold = if (inSystem) collapse else 0f,
                            )
                        }
                        Spacer(Modifier.height(if (inSystem) lerp(Space.m, Space.s, collapse) else lerp(Space.l, Space.s, collapse)))
                        val tileW = LocalGameArt.current.tileSize(metrics.icon).width
                        val cols = ((maxW - Space.gutter * 2 + metrics.gap) / (tileW + metrics.gap)).toInt().coerceAtLeast(2)
                        columns = cols
                        IconGrid(list, state, gridState, cols, metrics.icon, metrics.gap, onTap = { i -> tapAt(i, list) }, onLong = { i -> state.pick(i, list); options(list[i]) }, focused = gridFocused)
                    }
                    LibraryLayout.CAPSULE -> {
                        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
                            Stage(stageOf(selectedCard), showLogo = prefs.showLogo, logoHeight = 128.dp)
                        }
                        Spacer(Modifier.height(Space.xl))
                        CoverCarousel(
                            items = list,
                            selected = state.grid.index,
                            itemWidth = metrics.capsuleWidth * 0.62f,
                            onTap = { i -> tapAt(i, list) },
                            onLongPress = { i -> state.pick(i, list); options(list[i]) },
                            onSettle = { i ->
                                app.focusZone = FocusZone.CONTENT
                                state.inHeader = false
                                state.pick(i, list)
                            },
                            focused = gridFocused,
                        )
                        Spacer(Modifier.height(Size.hintHeight + Space.l))
                    }
                    LibraryLayout.COVER_GRID -> {
                        val coverW = metrics.coverWidth
                        val cols = ((maxW - Space.gutter * 2 + metrics.gap) / (coverW + metrics.gap)).toInt().coerceAtLeast(2)
                        columns = cols
                        CoverGrid(list, state, gridState, cols, coverW, metrics.gap, onTap = { i -> tapAt(i, list) }, onLong = { i -> state.pick(i, list); options(list[i]) }, focused = gridFocused)
                    }
                    LibraryLayout.COMPACT_LIST -> {
                        columns = 1
                        CompactList(list, state, listState, onTap = { i -> tapAt(i, list) }, onLong = { i -> state.pick(i, list); options(list[i]) }, focused = gridFocused)
                    }
                  }
                }
            }
        }
    }
}

internal fun sortLabel(s: SortOrder) = when (s) {
    SortOrder.TITLE -> "Title"
    SortOrder.RECENTLY_PLAYED -> "Recently played"
    SortOrder.RECENTLY_ADDED -> "Recently added"
    SortOrder.MOST_PLAYED -> "Most played"
    SortOrder.RELEASE_YEAR -> "Release year"
}

internal fun layoutLabel(l: LibraryLayout) = when (l) {
    LibraryLayout.ICON -> "Grid"
    LibraryLayout.CAPSULE -> "Capsules"
    LibraryLayout.COVER_GRID -> "Cover grid"
    LibraryLayout.COMPACT_LIST -> "List"
}

/** Narrows the whole library to one system, in the user's system order. */
private fun systemPicker(app: AppState, state: LibraryViewState, platforms: List<io.github.matiyaaa.fuse.ui.shell.store.PlatformCard>): ChoiceSpec {
    fun pick(id: PlatformId?) {
        state.system = id
        state.grid.index = 0
        state.selectedId = null
        state.inHeader = false
        app.choice = null
    }
    val systems = platforms.filter { it.gameCount > 0 }
    return ChoiceSpec(
        title = "Show games from",
        message = "Your library, narrowed to one system. Systems follow the order you gave them.",
        options = listOf(
            MenuAction("sys.all", "All systems", FuseIcons.Library, detail = "${systems.sumOf { it.gameCount }} games", trailing = Trailing.Check(state.system == null), onSelect = { pick(null) }),
        ) + systems.map { p ->
            MenuAction(
                "sys.${p.platform.id.value}", p.platform.name, FuseIcons.Chip,
                detail = listOfNotNull("${p.gameCount} ${if (p.gameCount == 1) "game" else "games"}", p.platform.manufacturer).joinToString("  ·  "),
                trailing = Trailing.Check(state.system == p.platform.id),
                onSelect = { pick(p.platform.id) },
            )
        },
    )
}

private fun sortPicker(app: AppState, current: SortOrder) = ChoiceSpec(
    title = "Sort by",
    options = SortOrder.entries.map { s ->
        MenuAction(s.name, sortLabel(s), FuseIcons.Sort, trailing = Trailing.Check(s == current), onSelect = {
            app.store.updatePrefs { it.copy(librarySort = s) }
            app.choice = null
        })
    },
)

private fun layoutPicker(app: AppState, state: LibraryViewState, platform: PlatformId?, current: LibraryLayout) = ChoiceSpec(
    title = "View as",
    message = if (platform != null) "Saved for this system" else "Saved as your default",
    options = LibraryLayout.entries.map { l ->
        MenuAction(l.name, layoutLabel(l), layoutIcon(l), trailing = Trailing.Check(l == current), onSelect = {
            state.layoutOverride = l
            app.scope.launch {
                if (platform != null) app.store.settings.setLayout(platform, l)
                else app.store.updatePrefs { it.copy(defaultLayout = l) }
            }
            app.choice = null
        })
    },
)

/** What can be done with a missing, hidden or removed game. Files are never touched. */
private fun setMenu(app: AppState, card: GameCard, segment: LibrarySegment): ContextMenuSpec {
    val lib = app.store.library
    fun run(message: String, block: suspend () -> Unit) {
        app.closeOverlays()
        app.scope.launch { block(); app.toasts.show(message) }
    }
    val actions = when (segment) {
        LibrarySegment.MISSING -> listOf(
            MenuAction("rescan", "Scan for it again", FuseIcons.Refresh, detail = "If you moved it back, Fuse finds it and keeps its art and play time", onSelect = {
                app.closeOverlays()
                app.store.sources.rescan(io.github.matiyaaa.fuse.model.ScanScope.PLATFORM, card.platformId)
                app.toasts.show("Rescanning ${card.platformShort}")
            }),
            MenuAction("forget", "Forget this game", FuseIcons.Trash, destructive = true, detail = "Removes its art, play time and collections from Fuse", onSelect = {
                app.closeOverlays()
                app.confirm = ConfirmSpec("Forget ${card.title}?", "Fuse removes what it kept for this game. Your files are not touched.", "Forget", destructive = true) {
                    app.scope.launch { lib.forgetMissing(card.id); app.toasts.show("Forgot ${card.title}") }
                }
            }),
        )
        LibrarySegment.HIDDEN -> listOf(
            MenuAction("show", "Show in the library again", FuseIcons.Eye, onSelect = { run("${card.title} is back in your library") { lib.restore(card.id) } }),
        )
        else -> listOf(
            MenuAction("restore", "Restore to Fuse", FuseIcons.Undo, onSelect = { run("${card.title} is back in your library") { lib.restore(card.id) } }),
        )
    }
    return ContextMenuSpec(title = card.title, subtitle = "${card.platformShort}  ·  ${segment.label}", art = card.art.tile, actions = actions)
}

@Composable
private fun IconGrid(
    list: List<GameCard>,
    state: LibraryViewState,
    grid: LazyGridState,
    columns: Int,
    size: Dp,
    gap: Dp,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    FollowSelection(grid, { state.grid.index }, anchor = 0.05f)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        modifier = Modifier.fadingEdges(top = if (grid.canScrollBackward) 24.dp else 0.dp),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Size.hintHeight + Space.x4),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap + Space.s),
    ) {
        itemsIndexed(list, key = { _, g -> g.id.value }) { i, card ->
            GameIconTile(card, selected = focused && i == state.grid.index, size = size, onClick = { onTap(i) }, onLongClick = { onLong(i) })
        }
    }
}

@Composable
private fun CoverGrid(
    list: List<GameCard>,
    state: LibraryViewState,
    grid: LazyGridState,
    columns: Int,
    width: Dp,
    gap: Dp,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    FollowSelection(grid, { state.grid.index }, anchor = 0.1f)
    val selected = list.getOrNull(state.grid.index)
    Column {
        Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            FText(selected?.title ?: "", Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (selected != null) {
                Spacer(Modifier.width(Space.m))
                FText(listOfNotNull(selected.platformShort.takeIf { LocalTileShowsSystem.current }, selected.year?.toString(), selected.playSeconds.takeIf { it > 0 }?.let(::playtimeText)).joinToString("  ·  "), Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fadingEdges(top = if (grid.canScrollBackward) 24.dp else 0.dp),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Size.hintHeight + Space.x4),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap + Space.m),
        ) {
            itemsIndexed(list, key = { _, g -> g.id.value }) { i, card ->
                GameCoverTile(card, selected = focused && i == state.grid.index, width = width, aspect = Aspect.BOX, onClick = { onTap(i) }, onLongClick = { onLong(i) })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CompactList(
    list: List<GameCard>,
    state: LibraryViewState,
    listState: LazyListState,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    val c = Fuse.colors
    FollowSelection(listState, { state.grid.index }, anchor = 0.35f)
    val selected = list.getOrNull(state.grid.index)
    Row(Modifier.fillMaxSize().padding(start = Space.gutter, end = Space.gutter)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1.1f).fillMaxHeight().fadingEdges(top = if (listState.canScrollBackward) 24.dp else 0.dp),
            contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            itemsIndexed(list, key = { _, g -> g.id.value }) { i, card ->
                val sel = focused && i == state.grid.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Size.rowCompact + 8.dp)
                        .clip(RoundedCornerShape(Fuse.geometry.control))
                        .background(if (sel) c.text.copy(alpha = 0.11f) else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = { onLong(i) }) { onTap(i) }
                        .padding(horizontal = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(20.dp).background(if (sel) c.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(Space.m))
                    SquareGameArt(
                        card.art,
                        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                        fallback = { GeneratedArt(card.title, card.accent.toColor(), slot = ArtSlot.ICON) },
                    )
                    Spacer(Modifier.width(Space.m))
                    FText(card.title, if (sel) Fuse.type.bodyStrong else Fuse.type.body, color = if (card.missing) c.textFaint else c.text, maxLines = 1, modifier = Modifier.weight(1f))
                    if (card.favorite) FuseIcon(FuseIcons.Heart, size = 14.dp, tint = c.textMuted)
                    Spacer(Modifier.width(Space.m))
                    if (LocalTileShowsSystem.current) FText(card.platformShort, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    Spacer(Modifier.width(Space.m))
                    FText(if (card.playSeconds > 0) playtimeText(card.playSeconds) else "", Fuse.type.caption, color = c.textFaint, maxLines = 1, modifier = Modifier.width(72.dp))
                }
            }
        }
        Spacer(Modifier.width(Space.xl))
        Column(Modifier.weight(0.9f).padding(top = Space.s)) {
            if (selected != null) {
                Tile(selected = false, modifier = Modifier.fillMaxWidth(0.55f).height(260.dp), showSpark = false) {
                    Artwork(
                        selected.art.boxart ?: selected.art.grid ?: selected.art.square ?: selected.art.icon,
                        Modifier.fillMaxSize(),
                        fallback = { GeneratedArt(selected.title, selected.accent.toColor(), slot = ArtSlot.BOX, label = selected.platformShort.takeIf { LocalTileShowsSystem.current }) },
                    )
                }
                Spacer(Modifier.height(Space.l))
                Stage(selected.stage().let { if (LocalTileShowsSystem.current) it else it.copy(eyebrow = null) }, logoHeight = 64.dp)
            }
        }
    }
}

@Composable
private fun LibraryEmpty(scope: LibraryScope, segment: LibrarySegment) {
    val c = Fuse.colors
    val (title, body) = when (scope) {
        is LibraryScope.OfCollection -> "This collection is empty" to "Add games from any game's options (Add to Collection)."
        is LibraryScope.OfPlatform -> "No games for this system yet" to
            "Put games in this system's folder, or get them from your RomM server with Cartridge. They appear here on their own."
        LibraryScope.All -> when (segment) {
            LibrarySegment.FAVORITES -> "No favourites yet" to "Mark games as favourites from their options."
            LibrarySegment.RECENT -> "Nothing played yet" to "Games you play show up here, newest first."
            LibrarySegment.MISSING -> "Nothing is missing" to "Games whose files disappear are listed here, so you can find them again or let Fuse forget them."
            LibrarySegment.HIDDEN -> "No hidden games" to "Games you hide from their options wait here."
            LibrarySegment.REMOVED -> "Nothing removed" to "Games you remove from Fuse wait here, in case you want them back."
            LibrarySegment.ALL -> "Nothing here yet" to "Games you add show up here."
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            FText(title, Fuse.type.display)
            FText(body, Fuse.type.body, color = c.textMuted)
        }
    }
}

/** View options appended to the game menu inside a library: layout, sort, hidden games, rescan. */
private fun viewActions(app: AppState, state: LibraryViewState, platform: PlatformId?, current: LibraryLayout, sort: SortOrder): List<MenuAction> {
    return listOf(
        MenuAction("layout", "View as", FuseIcons.Grid, trailing = Trailing.Value(layoutLabel(current)), onSelect = {
            app.contextMenu = null
            app.choice = layoutPicker(app, state, platform, current)
        }),
        MenuAction("sort", "Sort by", FuseIcons.Sort, trailing = Trailing.Value(sortLabel(sort)), onSelect = {
            app.contextMenu = null
            app.choice = sortPicker(app, sort)
        }),
        MenuAction("hidden", if (state.showHidden) "Hide hidden games" else "Show hidden games", FuseIcons.Eye, onSelect = {
            state.showHidden = !state.showHidden
            app.closeOverlays()
        }),
        MenuAction("rescan", if (platform != null) "Rescan this system" else "Rescan library", FuseIcons.Refresh, onSelect = {
            app.store.sources.rescan(io.github.matiyaaa.fuse.model.ScanScope.PLATFORM, platform)
            app.closeOverlays()
            app.toasts.show("Rescanning in the background")
        }),
    ) + if (platform != null) listOf(
        MenuAction("sys", "System settings", FuseIcons.Settings, trailing = Trailing.Chevron, onSelect = {
            app.closeOverlays(); app.go(Route.PlatformSettings(platform))
        }),
    ) else emptyList()
}

fun layoutIcon(l: LibraryLayout) = when (l) {
    LibraryLayout.ICON -> FuseIcons.Grid
    LibraryLayout.CAPSULE -> FuseIcons.Carousel
    LibraryLayout.COVER_GRID -> FuseIcons.Grid3
    LibraryLayout.COMPACT_LIST -> FuseIcons.List
}

/** A collection on Home and in Search: its name over a soft tint. */
@Composable
fun CollectionTile(collection: GameCollection, selected: Boolean, height: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    Tile(selected = selected, modifier = Modifier.size(width = height * 1.6f, height = height), onClick = onClick, onLongClick = onLongClick) {
        Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
            FuseIcon(FuseIcons.Bookmark, tint = c.textMuted, modifier = Modifier.align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart)) {
                FText(collection.name, Fuse.type.titleSmall, maxLines = 2)
                FText("${collection.gameCount} ${if (collection.gameCount == 1) "game" else "games"}", Fuse.type.caption, color = c.textMuted)
            }
        }
    }
}
