package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.Reveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.skeleton
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
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.TileMetrics
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
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.app.roomArt
import io.github.matiyaaa.fuse.ui.shell.collections.addGamesPicker
import io.github.matiyaaa.fuse.ui.shell.components.GameCoverTile
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.GameMarksInline
import io.github.matiyaaa.fuse.ui.shell.components.GameTileSkeleton
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsFavourite
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsSystem
import io.github.matiyaaa.fuse.ui.shell.components.PlatformTag
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.StageLine
import io.github.matiyaaa.fuse.ui.shell.components.cornerFraction
import io.github.matiyaaa.fuse.ui.shell.components.coverCornerFraction
import io.github.matiyaaa.fuse.ui.shell.components.gamesText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.components.tileSize
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.GameSet
import io.github.matiyaaa.fuse.ui.shell.systems.SystemShowcase
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
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

/**
 * The buttons at the end of the Library header. [EMULATOR] only shows on a system that has no
 * emulator, and leads to its settings.
 */
enum class LibraryButton { COLLECTIONS, ADD_GAMES, EMULATOR, SYSTEM, SORT, VIEW }

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

/** What an empty view offers to do about it: one action, on the confirm button and as a button. */
private class EmptyAction(val label: String, val icon: ImageVector, val run: () -> Unit)

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
    val platformId = (scope as? LibraryScope.OfPlatform)?.platform
    val systemCard = platforms.firstOrNull { it.platform.id == platformId }

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
    // How many games each main view holds within the system filter, for the counts on the tabs.
    val viewCounts by remember(scope, state.system, state.showHidden) {
        if (scope != LibraryScope.All) {
            flowOf(emptyMap())
        } else {
            val base = store.library.games(GameQuery(platform = state.system))
            val all = if (state.showHidden) store.library.games(GameQuery(platform = state.system, includeHidden = true)) else base
            combine(base, all) { b, a ->
                mapOf(
                    LibrarySegment.ALL to a.size,
                    LibrarySegment.FAVORITES to b.count { it.favorite },
                    LibrarySegment.RECENT to b.count { it.lastPlayedAt != null },
                )
            }
        }
    }.collectAsState(initial = emptyMap())
    val segments = if (scope != LibraryScope.All) emptyList() else LibrarySegment.entries.filter { s ->
        s.set == GameSet.LIBRARY || (extraCounts[s] ?: 0) > 0 || state.segment == s
    }
    val collectionsOn = prefs.collectionsEnabled
    val allCollections by store.collections.collections.collectAsState()
    val collection = (scope as? LibraryScope.OfCollection)?.let { s -> allCollections.firstOrNull { it.id == s.collection } }
    // A collection of the user's own (not a series Fuse keeps up to date) can be edited here.
    val ownCollection = collection?.takeIf { it.kind != CollectionKind.SERIES }
    val buttons = buildList {
        if (scope == LibraryScope.All && collectionsOn) add(LibraryButton.COLLECTIONS)
        if (ownCollection != null) add(LibraryButton.ADD_GAMES)
        if (systemCard != null && !systemCard.emulatorInstalled) add(LibraryButton.EMULATOR)
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
    PrefetchArt(remember(list) { list.orEmpty().map { it.art.logo } }, state.grid.index, size = LOGO_PREFETCH)
    val special = scope == LibraryScope.All && segment.set != GameSet.LIBRARY
    // A game with its own background image shows it; any other game shows its system's background,
    // so a system's page keeps one room while moving between its games.
    val systems = rememberSystems(app)
    // The rooms of the games next to the selection, decoded as the background decodes them, so
    // moving on shows them at once.
    val heroDp = with(androidx.compose.ui.platform.LocalDensity.current) { Fuse.quality.heroDecodePx.toDp() }
    PrefetchArt(remember(list, systems) { list.orEmpty().map { roomArt(it.art, systems[it.platformId]) } }, state.grid.index, size = heroDp, limit = 2)
    val gameSystem = selectedCard?.let { systems[it.platformId] }

    fun choose(s: LibrarySegment) {
        if (state.segment != s) {
            state.segment = s
            state.grid.index = 0
            state.selectedId = null
        }
    }

    // An empty view offers the one thing most likely to help.
    val emptyAction: EmptyAction? = when {
        list == null || list.isNotEmpty() -> null
        scope == LibraryScope.All && state.system != null -> EmptyAction("Show all systems", FuseIcons.Filter) {
            state.system = null
            state.grid.index = 0
            state.selectedId = null
        }
        scope == LibraryScope.All && segment != LibrarySegment.ALL -> EmptyAction("Show all games", FuseIcons.Library) {
            choose(LibrarySegment.ALL)
        }
        scope == LibraryScope.All -> EmptyAction("Add a game folder", FuseIcons.FolderPlus) { app.go(Route.Settings("library")) }
        ownCollection != null -> EmptyAction("Add games", FuseIcons.ListPlus) { app.addGamesPicker(ownCollection.id, ownCollection.name) }
        systemCard != null -> EmptyAction("Scan again", FuseIcons.Refresh) {
            store.sources.rescan(ScanScope.PLATFORM, systemCard.platform.id)
            app.toasts.show("Rescanning ${systemCard.platform.shortName}")
        }
        else -> null
    }

    LaunchedEffect(selectedCard?.id, selectedCard?.art, special, systemCard?.art, gameSystem?.art, emptyAction?.label) {
        app.hero = when {
            selectedCard != null -> selectedCard.room(gameSystem)
            systemCard != null -> HeroSource(systemCard.platform.id, systemCard.art.hero, systemCard.platform.accent.toColor())
            else -> null
        }
        app.hints = when {
            selectedCard == null -> listOfNotNull(emptyAction?.let { Hint(HintButton.CONFIRM, it.label) })
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
            LibraryButton.EMULATOR -> platformId?.let { app.go(Route.PlatformSettings(it)) }
            LibraryButton.SYSTEM -> app.choice = systemPicker(app, state, platforms)
            LibraryButton.SORT -> app.choice = sortPicker(app, sort)
            LibraryButton.VIEW -> app.choice = layoutPicker(app, state, platformId, layout)
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
                    selectedCard == null -> emptyAction?.run?.invoke()
                    special -> options(selectedCard)
                    else -> app.activateGame(selectedCard)
                }
                if (selectedCard != null || emptyAction != null) NavResult.ACTIVATED else NavResult.BLOCKED
            }
            NavAction.CONTEXT -> {
                if (selectedCard != null) options(selectedCard)
                else app.openContextMenu(ContextMenuSpec("Library", actions = viewActions(app, state, platformId, layout, sort)))
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    // Entering the screen reveals its header once; the content reveals again for each new view.
    val entry = rememberReveal(key)
    val reveal = rememberReveal(key, segment, state.system, layout)
    // A view that takes a moment to load shows its skeleton; one that is quick never flashes it.
    val showSkeleton by produceState(false, list == null) {
        value = false
        if (list == null) {
            delay(SKELETON_DELAY_MS)
            value = true
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
        val compactHeader = maxH < SHORT_SCREEN
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
        // Stage sizes for the Grid layout: inside a system the stage is smaller, so more games fit
        // from the start, and folded it shrinks to one line beside the toolbar, so the games get the height.
        // A folded system page lets its toolbar hang beside the one-line stage, where there is room.
        val foldTools = inSystem && layout == LibraryLayout.ICON && maxW >= STACK_WIDTH
        val stageHeight = when {
            inSystem -> (maxH * SYSTEM_STAGE_SHARE).coerceIn(SYSTEM_STAGE_MIN, SYSTEM_STAGE_MAX)
            // A short screen (a handheld) sets the title in the display face, so it keeps clear of the tabs.
            compactHeader -> (maxH * STAGE_SHARE).coerceIn(SHORT_STAGE_MIN, SHORT_STAGE_MAX)
            else -> (maxH * STAGE_SHARE).coerceIn(STAGE_MIN, STAGE_MAX)
        }
        // The stage's title: the hero face where there is room for it.
        val stageTitle = if (compactHeader) Fuse.type.display else Fuse.type.hero
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (systemCard != null) lerp(if (compactHeader) Space.xs else Space.m, Space.xxs, collapse) else 0.dp))
            Box(Modifier.reveal(entry, 0)) {
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
                    foldTools = foldTools,
                    counts = viewCounts,
                )
            }
            when {
                list == null -> if (showSkeleton) LibrarySkeleton(layout, metrics, maxW, maxH, stageHeight)
                list.isEmpty() -> LibraryEmpty(
                    scope, segment, collection,
                    filteredTo = platforms.firstOrNull { it.platform.id == state.system }?.platform?.shortName,
                    action = emptyAction,
                    selected = gridFocused,
                )
                else -> CompositionLocalProvider(
                    LocalTileShowsSystem provides !inSystem,
                    LocalTileShowsFavourite provides !(scope == LibraryScope.All && segment == LibrarySegment.FAVORITES),
                ) {
                  when (layout) {
                    LibraryLayout.ICON -> {
                        // The game's logo moves up with the folding header and makes room for another row.
                        val logo = when {
                            !inSystem && compactHeader -> STAGE_LOGO_SHORT
                            !inSystem -> STAGE_LOGO
                            compactHeader -> lerp(STAGE_LOGO_SHORT, Space.xxl, collapse)
                            else -> lerp(Space.x4, Space.xxl + Space.s, collapse)
                        }
                        val foldedStage = if (inSystem) logo + Space.xs else stageHeight * 0.66f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(lerp(stageHeight, foldedStage, collapse))
                                .padding(start = Space.gutter, end = Space.gutter + if (foldTools) lerp(0.dp, FOLDED_TOOLBAR_ROOM, collapse) else 0.dp)
                                .reveal(reveal, 1),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Stage(
                                stageOf(selectedCard), showLogo = prefs.showLogo, logoHeight = logo,
                                titleStyle = if (inSystem) androidx.compose.ui.text.lerp(Fuse.type.display, Fuse.type.title, collapse) else stageTitle,
                                fold = if (inSystem) collapse else 0f,
                                // A short screen keeps the stage to two lines, clear of the tabs.
                                inlineEyebrow = compactHeader,
                            )
                        }
                        Spacer(Modifier.height(if (inSystem) lerp(Space.m, Space.s, collapse) else lerp(Space.l, Space.s, collapse)))
                        val tileW = LocalGameArt.current.tileSize(metrics.icon).width
                        val cols = ((maxW - Space.gutter * 2 + metrics.gap) / (tileW + metrics.gap)).toInt().coerceAtLeast(2)
                        columns = cols
                        IconGrid(list, state, gridState, cols, metrics.icon, metrics.gap, reveal, onTap = { i -> tapAt(i, list) }, onLong = { i -> state.pick(i, list); options(list[i]) }, focused = gridFocused)
                    }
                    LibraryLayout.CAPSULE -> {
                        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter).reveal(reveal, 1), contentAlignment = Alignment.BottomStart) {
                            Stage(stageOf(selectedCard), showLogo = prefs.showLogo, logoHeight = if (compactHeader) CAPSULE_LOGO / 2 else CAPSULE_LOGO, titleStyle = stageTitle)
                        }
                        Spacer(Modifier.height(Space.xl))
                        CoverCarousel(
                            items = list,
                            selected = state.grid.index,
                            itemWidth = metrics.capsuleWidth * CAPSULE_SCALE,
                            onTap = { i -> tapAt(i, list) },
                            onLongPress = { i -> state.pick(i, list); options(list[i]) },
                            onSettle = { i ->
                                app.focusZone = FocusZone.CONTENT
                                state.inHeader = false
                                state.pick(i, list)
                            },
                            modifier = Modifier.reveal(reveal, 2),
                            focused = gridFocused,
                            start = Space.gutter,
                        )
                        Spacer(Modifier.height(Size.hintHeight + Space.l))
                    }
                    LibraryLayout.COVER_GRID -> {
                        val coverW = metrics.coverWidth
                        val cols = ((maxW - Space.gutter * 2 + metrics.gap) / (coverW + metrics.gap)).toInt().coerceAtLeast(2)
                        columns = cols
                        CoverGrid(list, state, gridState, cols, coverW, metrics.gap, reveal, onTap = { i -> tapAt(i, list) }, onLong = { i -> state.pick(i, list); options(list[i]) }, focused = gridFocused)
                    }
                    LibraryLayout.COMPACT_LIST -> {
                        columns = 1
                        CompactList(list, state, listState, reveal, onTap = { i -> tapAt(i, list) }, onLong = { i -> state.pick(i, list); options(list[i]) }, focused = gridFocused)
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

/** Each order gets an icon of its own in the Sort by list, so the choices read at a glance. */
private fun sortIcon(s: SortOrder) = when (s) {
    SortOrder.TITLE -> FuseIcons.SortAlpha
    SortOrder.RECENTLY_PLAYED -> FuseIcons.History
    SortOrder.RECENTLY_ADDED -> FuseIcons.CirclePlus
    SortOrder.MOST_PLAYED -> FuseIcons.TrendingUp
    SortOrder.RELEASE_YEAR -> FuseIcons.Calendar
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
            MenuAction("sys.all", "All systems", FuseIcons.Library, detail = gamesText(systems.sumOf { it.gameCount }), trailing = Trailing.Check(state.system == null), onSelect = { pick(null) }),
        ) + systems.map { p ->
            MenuAction(
                "sys.${p.platform.id.value}", p.platform.name, FuseIcons.Chip,
                detail = listOfNotNull(gamesText(p.gameCount), p.platform.manufacturer).joinToString("  ·  "),
                trailing = Trailing.Check(state.system == p.platform.id),
                onSelect = { pick(p.platform.id) },
            )
        },
    )
}

private fun sortPicker(app: AppState, current: SortOrder) = ChoiceSpec(
    title = "Sort by",
    options = SortOrder.entries.map { s ->
        MenuAction(s.name, sortLabel(s), sortIcon(s), trailing = Trailing.Check(s == current), onSelect = {
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
                app.store.sources.rescan(ScanScope.PLATFORM, card.platformId)
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

/**
 * The Grid layout: game tiles in rows under the stage. Its first rows rise into place as the view
 * opens, one row a beat after another; rows scrolled to later are simply there. The top and bottom
 * edges soften only while there is more to scroll, so the last row never runs into the hint line.
 */
@Composable
private fun IconGrid(
    list: List<GameCard>,
    state: LibraryViewState,
    grid: LazyGridState,
    columns: Int,
    size: Dp,
    gap: Dp,
    reveal: Reveal,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    FollowSelection(grid, { state.grid.index }, anchor = 0.05f)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        modifier = Modifier.padding(bottom = Size.hintHeight).fadingEdges(grid, top = Space.xl, bottom = BOTTOM_FADE),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Space.xxl),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap + Space.s),
    ) {
        itemsIndexed(list, key = { _, g -> g.id.value }) { i, card ->
            GameIconTile(
                card,
                selected = focused && i == state.grid.index,
                size = size,
                modifier = Modifier.reveal(reveal, 2 + i / columns),
                onClick = { onTap(i) },
                onLongClick = { onLong(i) },
            )
        }
    }
}

/**
 * The Cover grid: portrait covers, with the selected game's title and details on one line above them
 * (the covers carry their own names, so the line names the one you are on).
 */
@Composable
private fun CoverGrid(
    list: List<GameCard>,
    state: LibraryViewState,
    grid: LazyGridState,
    columns: Int,
    width: Dp,
    gap: Dp,
    reveal: Reveal,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    FollowSelection(grid, { state.grid.index }, anchor = 0.1f)
    val selected = list.getOrNull(state.grid.index)
    val showsSystem = LocalTileShowsSystem.current
    Column {
        StageLine(
            selected?.stage()?.let { if (showsSystem) it else it.copy(eyebrow = null) },
            Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = CONTENT_TOP, bottom = Space.s).reveal(reveal, 1),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.padding(bottom = Size.hintHeight).fadingEdges(grid, top = Space.xl, bottom = BOTTOM_FADE),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Space.xxl),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap + Space.m),
        ) {
            itemsIndexed(list, key = { _, g -> g.id.value }) { i, card ->
                GameCoverTile(
                    card,
                    selected = focused && i == state.grid.index,
                    width = width,
                    aspect = Aspect.BOX,
                    modifier = Modifier.reveal(reveal, 2 + i / columns),
                    onClick = { onTap(i) },
                    onLongClick = { onLong(i) },
                )
            }
        }
    }
}

/**
 * The List layout: one game per row, with the selected game's cover and details beside the list on
 * screens wide enough for both. One highlight glides from row to row as the selection moves, with
 * the accent bar at its start, like the menus.
 */
@Composable
private fun CompactList(
    list: List<GameCard>,
    state: LibraryViewState,
    listState: LazyListState,
    reveal: Reveal,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    FollowSelection(listState, { state.grid.index }, anchor = 0.35f)
    val selected = list.getOrNull(state.grid.index)
    val showsSystem = LocalTileShowsSystem.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth >= SIDE_PANEL_MIN
        Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            GlidingList(
                list, state.grid.index, listState, focused,
                Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
            ) { i, card, sel ->
                GameRow(
                    card, sel, showsSystem,
                    Modifier.reveal(reveal, 2 + i),
                    onClick = { onTap(i) },
                    onLongClick = { onLong(i) },
                )
            }
            if (side && selected != null) {
                Spacer(Modifier.width(Space.xxl))
                ListPreview(selected, showsSystem, Modifier.weight(PREVIEW_WEIGHT).fillMaxHeight().reveal(reveal, 1))
            }
        }
    }
}

/**
 * A list whose selected row is marked by one highlight that glides between rows: its top and bottom
 * edges move on their own springs (the leading one quicker), so it stretches a little as it travels.
 * A long jump (a page, a held direction) glides in from the neighbouring row only, since the list is
 * already scrolling to follow. It snaps under Reduced motion.
 */
@Composable
private fun GlidingList(
    list: List<GameCard>,
    selectedIndex: Int,
    listState: LazyListState,
    focused: Boolean,
    modifier: Modifier,
    row: @Composable (Int, GameCard, Boolean) -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val target = selectedIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)).toFloat()
    val top = remember { Animatable(target) }
    val bottom = remember { Animatable(target) }
    val shown by animateFloatAsState(if (focused) 1f else 0f, motion.tween(if (focused) Durations.FAST else Durations.INSTANT), label = "list highlight")
    LaunchedEffect(target, list.size) {
        if (motion.reduced || shown < 0.05f) {
            top.snapTo(target)
            bottom.snapTo(target)
            return@LaunchedEffect
        }
        if (abs(target - top.value) > 1.5f || abs(target - bottom.value) > 1.5f) {
            val from = if (target > top.value) target - 1f else target + 1f
            top.snapTo(from)
            bottom.snapTo(from)
        }
        val down = target >= bottom.value
        launch { top.animateTo(target, if (down) motion.glideTrail() else motion.glide()) }
        launch { bottom.animateTo(target, if (down) motion.glide() else motion.glideTrail()) }
    }
    val fill = c.text.copy(alpha = if (c.isDark) ROW_FILL else ROW_FILL_LIGHT)
    val accent = c.accent
    val outline = if (Fuse.look.highContrastFocus) c.focus else null
    val corner = Fuse.geometry.control
    LazyColumn(
        state = listState,
        modifier = modifier
            .padding(bottom = Size.hintHeight)
            .fadingEdges(listState, top = Space.xl, bottom = BOTTOM_FADE)
            .drawBehind {
                if (shown <= 0.01f || list.isEmpty()) return@drawBehind
                val info = listState.layoutInfo
                val gap = Space.xxs.toPx()
                val t = rowEdge(top.value, info, gap, bottom = false) ?: return@drawBehind
                val b = rowEdge(bottom.value, info, gap, bottom = true) ?: return@drawBehind
                if (b <= t) return@drawBehind
                val h = b - t
                val r = corner.toPx().coerceAtMost(h / 2)
                clipRect {
                    drawRoundRect(fill, Offset(0f, t), size.copy(height = h), CornerRadius(r), alpha = shown)
                    if (outline != null) {
                        val sw = Size.focusStroke.toPx()
                        drawRoundRect(
                            outline,
                            Offset(sw / 2, t + sw / 2),
                            androidx.compose.ui.geometry.Size(size.width - sw, h - sw),
                            CornerRadius((r - sw / 2).coerceAtLeast(0f)),
                            alpha = shown,
                            style = Stroke(sw),
                        )
                    }
                    val bh = ROW_BAR_HEIGHT.toPx().coerceAtMost(h - Space.s.toPx())
                    // On strongly rounded highlights (pill themes) the bar steps in to stay inside the curve.
                    val curve = if (r > bh / 2) r - sqrt(r * r - (bh / 2) * (bh / 2)) else 0f
                    drawRoundRect(
                        accent,
                        Offset(curve, t + (h - bh) / 2),
                        androidx.compose.ui.geometry.Size(Size.sparkHeight.toPx(), bh),
                        CornerRadius(Size.sparkHeight.toPx() / 2),
                        alpha = shown,
                    )
                }
            },
        contentPadding = PaddingValues(top = CONTENT_TOP, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        itemsIndexed(list, key = { _, g -> g.id.value }) { i, card ->
            row(i, card, focused && i == selectedIndex)
        }
    }
}

/**
 * The top (or [bottom]) edge, in the list's own pixels, of the row at a fractional position, blended
 * between the rows either side. Rows out of view are estimated from the ones in view (every row has
 * the same height), so the highlight can travel in from off screen.
 */
private fun rowEdge(pos: Float, info: LazyListLayoutInfo, gapPx: Float, bottom: Boolean): Float? {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    // Item offsets start after the top content padding; the drawing starts at the list's own top.
    val shift = -info.viewportStartOffset.toFloat()
    val first = visible.first()
    val step = first.size + gapPx
    fun span(i: Int): Pair<Float, Float> {
        visible.firstOrNull { it.index == i }?.let { return it.offset + shift to it.offset + it.size + shift }
        val top = first.offset + (i - first.index) * step + shift
        return top to top + first.size
    }
    val i = pos.toInt()
    val f = (pos - i).coerceIn(0f, 1f)
    val a = span(i)
    val b = if (f > 0f) span(i + 1) else a
    val ea = if (bottom) a.second else a.first
    val eb = if (bottom) b.second else b.first
    return ea + (eb - ea) * f
}

/**
 * One game in the List layout: its art, its title, its marks, its platform tag and its play time in
 * a column of tabular figures. Hover and press come from [fuseClickable]; selection is drawn by the
 * list's gliding highlight.
 */
@Composable
private fun GameRow(card: GameCard, selected: Boolean, showsSystem: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val thumbCorner = Fuse.geometry.tileCornerFraction.coerceAtLeast(THUMB_CORNER_MIN) + THUMB_CORNER_EXTRA
    val thumb = remember(thumbCorner) { SquircleShape.fraction(thumbCorner) }
    Row(
        modifier
            .fillMaxWidth()
            .height(Size.row)
            .clip(shape)
            .fuseClickable(shape = shape, scale = false, onLongClick = onLongClick, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = Size.sparkHeight + Space.m, end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareGameArt(
            card.art,
            Modifier.size(Size.thumb).clip(thumb),
            fallback = { GeneratedArt(card.title, card.accent.toColor(), slot = ArtSlot.ICON) },
        )
        Spacer(Modifier.width(Space.m))
        FText(
            card.title,
            if (selected) Fuse.type.bodyStrong else Fuse.type.body,
            color = if (card.missing) c.textFaint else c.text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        GameMarksInline(card, Modifier.padding(start = Space.m), emphasised = selected)
        if (showsSystem) {
            Spacer(Modifier.width(Space.m))
            PlatformTag(card.platformShort, emphasised = selected)
        }
        Spacer(Modifier.width(Space.m))
        FText(
            if (card.playSeconds > 0) playtimeText(card.playSeconds) else "",
            Fuse.type.numericSmall,
            color = if (selected) c.textMuted else c.textFaint,
            maxLines = 1,
            align = TextAlign.End,
            modifier = Modifier.width(PLAYTIME_COLUMN),
        )
    }
}

/** Beside the list: the selected game's cover, lifted like a tile, and its stage underneath. */
@Composable
private fun ListPreview(card: GameCard, showsSystem: Boolean, modifier: Modifier) {
    val corner = coverCornerFraction()
    val shape = remember(corner) { SquircleShape.fraction(corner) }
    BoxWithConstraints(modifier.padding(top = CONTENT_TOP, bottom = Size.hintHeight + Space.l)) {
        val coverHeight = (maxHeight * PREVIEW_COVER).coerceAtMost(maxWidth / Aspect.BOX)
        Column {
            Tile(selected = false, modifier = Modifier.height(coverHeight).aspectRatio(Aspect.BOX), shape = shape, cornerFraction = corner, showSpark = false) {
                Artwork(
                    card.art.boxart ?: card.art.grid ?: card.art.square ?: card.art.icon,
                    Modifier.fillMaxSize(),
                    // The stage underneath names the game, so a generated cover carries its initials,
                    // as its row's thumbnail does, rather than the title twice.
                    fallback = { GeneratedArt(card.title, card.accent.toColor(), slot = ArtSlot.ICON) },
                )
            }
            Spacer(Modifier.height(Space.l))
            Stage(
                card.stage().let { if (showsSystem) it else it.copy(eyebrow = null) },
                logoHeight = Space.x4,
                titleStyle = Fuse.type.display,
            )
        }
    }
}

/**
 * What a view looks like while its games are on their way: the stage and the tiles (or rows) in
 * their own places and shapes, with the calm shared shimmer passing over them, so nothing jumps
 * when the games arrive.
 */
@Composable
internal fun LibrarySkeleton(layout: LibraryLayout, metrics: TileMetrics, maxW: Dp, maxH: Dp, stageHeight: Dp) {
    val bar = RoundedCornerShape(BAR_RADIUS)
    Column(Modifier.fillMaxSize().clipToBounds()) {
        when (layout) {
            LibraryLayout.ICON -> {
                StageSkeleton(Modifier.fillMaxWidth().height(stageHeight).padding(horizontal = Space.gutter))
                Spacer(Modifier.height(Space.l))
                val style = LocalGameArt.current
                val tile = style.tileSize(metrics.icon)
                val cols = ((maxW - Space.gutter * 2 + metrics.gap) / (tile.width + metrics.gap)).toInt().coerceAtLeast(2)
                Column(Modifier.padding(start = Space.gutter, top = Space.s), verticalArrangement = Arrangement.spacedBy(metrics.gap + Space.s)) {
                    repeat(SKELETON_ROWS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                            repeat(cols) { GameTileSkeleton(Modifier.size(tile), style.cornerFraction()) }
                        }
                    }
                }
            }
            LibraryLayout.COVER_GRID -> {
                Box(Modifier.padding(start = Space.gutter, top = CONTENT_TOP + Space.xs, bottom = Space.s + Space.xs).width(maxW * 0.3f).height(Space.l + Space.xs).skeleton(bar))
                val cols = ((maxW - Space.gutter * 2 + metrics.gap) / (metrics.coverWidth + metrics.gap)).toInt().coerceAtLeast(2)
                Row(Modifier.padding(start = Space.gutter, top = Space.m), horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                    repeat(cols) { GameTileSkeleton(Modifier.width(metrics.coverWidth).aspectRatio(Aspect.BOX), coverCornerFraction()) }
                }
            }
            LibraryLayout.CAPSULE -> {
                StageSkeleton(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter))
                Spacer(Modifier.height(Space.xl))
                val w = metrics.capsuleWidth * CAPSULE_SCALE
                Row(
                    Modifier.fillMaxWidth().height(w * 1.5f * 1.22f).padding(start = Space.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(SKELETON_CAPSULES) { i ->
                        GameTileSkeleton(Modifier.width(if (i == 0) w else w * 0.88f).aspectRatio(Aspect.CAPSULE), coverCornerFraction())
                    }
                }
                Spacer(Modifier.height(Size.hintHeight + Space.l))
            }
            LibraryLayout.COMPACT_LIST -> {
                Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = CONTENT_TOP), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    val rows = ((maxH - Size.hudHeight) / (Size.row + Space.xxs)).toInt().coerceIn(1, SKELETON_LIST_ROWS)
                    repeat(rows) { i ->
                        Row(
                            Modifier.fillMaxWidth(LIST_WEIGHT / (LIST_WEIGHT + PREVIEW_WEIGHT)).height(Size.row).padding(start = Size.sparkHeight + Space.m, end = Space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GameTileSkeleton(Modifier.size(Size.thumb), Fuse.geometry.tileCornerFraction.coerceAtLeast(THUMB_CORNER_MIN) + THUMB_CORNER_EXTRA)
                            Spacer(Modifier.width(Space.m))
                            // Titles of different lengths, so the column reads as a list of names.
                            Box(Modifier.fillMaxWidth(SKELETON_TITLE[i % SKELETON_TITLE.size]).height(Space.m + Space.xxs).skeleton(bar))
                        }
                    }
                }
            }
        }
    }
}

/** The stage's eyebrow, title and meta line as placeholder bars, sitting where they will. */
@Composable
private fun StageSkeleton(modifier: Modifier) {
    val bar = RoundedCornerShape(BAR_RADIUS)
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Box(Modifier.width(Space.x5).height(Space.m - Space.xxs).skeleton(bar))
            Box(Modifier.width(Space.x5 * 3).height(Space.xxl + Space.s).skeleton(RoundedCornerShape(Space.s)))
            Box(Modifier.width(Space.x5 * 2).height(Space.m + Space.xxs).skeleton(bar))
        }
    }
}

/**
 * An empty view: what it is for, how it fills up, and the one thing that helps most as a button
 * (also on the confirm button, since focus stays on the view). Errors never land here: an empty
 * list is a real state, told plainly.
 */
@Composable
private fun LibraryEmpty(
    scope: LibraryScope,
    segment: LibrarySegment,
    collection: GameCollection?,
    filteredTo: String?,
    action: EmptyAction?,
    selected: Boolean,
) {
    val series = collection?.kind == CollectionKind.SERIES
    val (icon, title, body) = when (scope) {
        is LibraryScope.OfCollection -> Triple(
            if (series) FuseIcons.Sparkles else FuseIcons.Bookmark,
            "This collection is empty",
            if (series) "Fuse adds games of this series here as it finds them." else "Add games here, or from any game's options.",
        )
        is LibraryScope.OfPlatform -> Triple(
            FuseIcons.FolderSearch,
            "No games for this system yet",
            "Put games in this system's folder, or get them from your RomM server with Cartridge. They appear here on their own.",
        )
        LibraryScope.All -> when (segment) {
            LibrarySegment.FAVORITES -> Triple(FuseIcons.Heart, "No favourites yet", "Mark a game as a favourite from its options and it waits for you here.")
            LibrarySegment.RECENT -> Triple(FuseIcons.History, "Nothing played yet", "Games you play show up here, newest first.")
            LibrarySegment.MISSING -> Triple(FuseIcons.CheckCheck, "Nothing is missing", "Games whose files disappear are listed here, so you can find them again or let Fuse forget them.")
            LibrarySegment.HIDDEN -> Triple(FuseIcons.EyeOff, "No hidden games", "Games you hide from their options wait here.")
            LibrarySegment.REMOVED -> Triple(FuseIcons.Undo, "Nothing removed", "Games you remove from Fuse wait here, in case you want them back.")
            LibrarySegment.ALL -> Triple(FuseIcons.LibraryBig, "Nothing here yet", "Add a folder of games and Fuse finds them, with their art.")
        }
    }
    // A system filter can be what empties a view, so the message says so.
    val message = if (scope == LibraryScope.All && filteredTo != null) "Showing $filteredTo games only. $body" else body
    Box(
        Modifier.fillMaxSize().padding(start = Space.gutter, end = Space.gutter, bottom = Size.hintHeight + Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(icon = icon, title = title, message = message)
            // The action sits outside the state's arrival layer, so its focus ring is never cut.
            if (action != null) {
                Spacer(Modifier.height(Space.xl))
                FuseButton(action.label, selected = selected, onClick = action.run, kind = ButtonKind.PRIMARY, icon = action.icon)
            }
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
        MenuAction("hidden", if (state.showHidden) "Hide hidden games" else "Show hidden games", if (state.showHidden) FuseIcons.EyeOff else FuseIcons.Eye, onSelect = {
            state.showHidden = !state.showHidden
            app.closeOverlays()
        }),
        MenuAction("rescan", if (platform != null) "Rescan this system" else "Rescan library", FuseIcons.Refresh, onSelect = {
            app.store.sources.rescan(ScanScope.PLATFORM, platform)
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

/**
 * A collection on Home and in Search: a tile of its own, lit like the others, with its mark in a
 * small well at the top and its name and count at the bottom.
 */
@Composable
fun CollectionTile(collection: GameCollection, selected: Boolean, height: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val series = collection.kind == CollectionKind.SERIES
    Tile(selected = selected, modifier = Modifier.size(width = height * COLLECTION_ASPECT, height = height), onClick = onClick, onLongClick = onLongClick) {
        Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
            Box(
                Modifier.size(Size.chipCompact).clip(PillShape).background(c.text.copy(alpha = if (c.isDark) WELL_FILL else WELL_FILL_LIGHT)),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(if (series) FuseIcons.Sparkles else FuseIcons.Bookmark, size = Size.iconS, tint = c.textMuted)
            }
            Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                FText(collection.name, Fuse.type.titleSmall, maxLines = 2)
                FText(gamesText(collection.gameCount), Fuse.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** Loading that takes longer than this shows the skeleton; anything quicker never flashes it. */
private const val SKELETON_DELAY_MS = 160L

/** Rows of tile placeholders in the Grid layout's skeleton, and placeholders in other layouts. */
private const val SKELETON_ROWS = 3
private const val SKELETON_CAPSULES = 8
private const val SKELETON_LIST_ROWS = 14

/** Widths of the list skeleton's title bars, so the column reads as names of different lengths. */
private val SKELETON_TITLE = floatArrayOf(0.42f, 0.56f, 0.34f, 0.5f, 0.38f, 0.6f, 0.46f)

/** Room between the header and a layout that starts right under it (the Cover grid, the List). */
private val CONTENT_TOP = Space.m

/** Placeholder text bars are rounded like a line of type. */
private val BAR_RADIUS = Space.s

/**
 * Lists end above the hint line, so the hints never sit on top of art, and their bottom edge
 * softens over this much while more games wait below.
 */
private val BOTTOM_FADE = Space.xxl + Space.s

/** Screens shorter than this get the compact header and stage (handhelds in landscape). */
private val SHORT_SCREEN = 560.dp

/** The stage's height as a share of the screen's, and its bounds; inside a system it is smaller. */
private const val STAGE_SHARE = 0.22f
private val STAGE_MIN = 110.dp
private val STAGE_MAX = 200.dp
private val SHORT_STAGE_MIN = 100.dp
private val SHORT_STAGE_MAX = 120.dp
private const val SYSTEM_STAGE_SHARE = 0.14f
private val SYSTEM_STAGE_MIN = 92.dp
private val SYSTEM_STAGE_MAX = 124.dp

/** The selected game's logo on the stage, and on a short screen. */
private val STAGE_LOGO = 84.dp
private val STAGE_LOGO_SHORT = 56.dp

/** Logos ahead of the selection are decoded at this size, ready for the stage. */
private val LOGO_PREFETCH = 360.dp

/** Room a folded system stage keeps on its right for the toolbar hanging beside it. */
private val FOLDED_TOOLBAR_ROOM = 320.dp

/** Capsule Mode's covers, against the capsule size, and the stage logo above them. */
private const val CAPSULE_SCALE = 0.62f
private val CAPSULE_LOGO = 128.dp

/** The List layout's split between the list and the preview, and when the preview has room. */
private const val LIST_WEIGHT = 1.15f
private const val PREVIEW_WEIGHT = 0.85f
private val SIDE_PANEL_MIN = 720.dp

/** The preview cover's height, as a share of the panel's. */
private const val PREVIEW_COVER = 0.56f

/** The column of play times in the List layout: wide enough for "12 h 30 min". */
private val PLAYTIME_COLUMN = Space.x4 + Space.l

/** The accent bar at the start of the selected row, as tall as the menus' bar. */
private val ROW_BAR_HEIGHT = 22.dp

/** The selected row's fill in dark and light themes, as in the menus. */
private const val ROW_FILL = 0.1f
private const val ROW_FILL_LIGHT = 0.07f

/** Row thumbnails take the tiles' corners, at least this round, a little rounder for their size. */
private const val THUMB_CORNER_MIN = 0.12f
private const val THUMB_CORNER_EXTRA = 0.06f

/** A collection tile's width against its height, and the fill of its icon well. */
private const val COLLECTION_ASPECT = 1.6f
private const val WELL_FILL = 0.08f
private const val WELL_FILL_LIGHT = 0.06f
