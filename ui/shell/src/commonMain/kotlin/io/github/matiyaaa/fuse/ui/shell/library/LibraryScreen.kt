package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.GameCoverTile
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.launch

/** What a library view lists. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data class OfPlatform(val platform: PlatformId) : LibraryScope
    data class OfCollection(val collection: CollectionId, val name: String) : LibraryScope
}

/** Library filters for the All view. */
private sealed interface LibraryFilter {
    val label: String

    data object All : LibraryFilter { override val label = "All" }
    data object Favorites : LibraryFilter { override val label = "Favourites" }
    data object Recent : LibraryFilter { override val label = "Recently played" }
    data class Platform(val id: PlatformId, override val label: String) : LibraryFilter
}

/** Remembered per library view: which game was selected (by id, so re-sorting keeps it), and where focus was. */
@Stable
class LibraryViewState {
    val grid = GridSelection()
    var selectedId by mutableStateOf<GameId?>(null)
    var filterIndex by mutableIntStateOf(0)
    var inFilters by mutableStateOf(false)
    var layoutOverride by mutableStateOf<LibraryLayout?>(null)
    var sort by mutableStateOf(SortOrder.TITLE)
    var showHidden by mutableStateOf(false)
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
    val state = rememberRouteState(app.navigator, key) { LibraryViewState() }

    val filters = remember(platforms, scope) {
        if (scope != LibraryScope.All) emptyList() else buildList {
            add(LibraryFilter.All)
            add(LibraryFilter.Favorites)
            add(LibraryFilter.Recent)
            platforms.filter { it.gameCount > 0 }.forEach { add(LibraryFilter.Platform(it.platform.id, it.platform.shortName)) }
        }
    }
    val filter = filters.getOrNull(state.filterIndex) ?: LibraryFilter.All
    val query = when (scope) {
        LibraryScope.All -> when (filter) {
            LibraryFilter.All -> GameQuery(sort = state.sort, includeHidden = state.showHidden)
            LibraryFilter.Favorites -> GameQuery(favoritesOnly = true, sort = state.sort)
            LibraryFilter.Recent -> GameQuery(sort = SortOrder.RECENTLY_PLAYED)
            is LibraryFilter.Platform -> GameQuery(platform = filter.id, sort = state.sort)
        }
        is LibraryScope.OfPlatform -> GameQuery(platform = scope.platform, sort = state.sort, includeHidden = state.showHidden)
        is LibraryScope.OfCollection -> GameQuery(collection = scope.collection, sort = state.sort)
    }
    val gamesFlow = remember(query) { store.library.games(query) }
    val games by gamesFlow.collectAsState(initial = null)

    val platformId = (scope as? LibraryScope.OfPlatform)?.platform
    val layoutFlow = remember(platformId) { store.settings.observe(ScopedSettings.Layout, platformId, null) }
    val resolvedLayout by layoutFlow.collectAsState(initial = null)
    val layout = state.layoutOverride ?: resolvedLayout?.value?.takeIf { resolvedLayout?.isDefault == false } ?: prefs.defaultLayout

    val list = games
    // Keep the same game selected when the list changes (new downloads, sorting, layout switches).
    LaunchedEffect(list) {
        if (list == null) return@LaunchedEffect
        val idx = state.selectedId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx >= 0) state.grid.index = idx else state.grid.clamp(list.size)
    }
    val selectedCard = list?.getOrNull(state.grid.index)
    LaunchedEffect(selectedCard?.id) {
        state.selectedId = selectedCard?.id
        app.hero = selectedCard?.let { HeroSource(it.id, it.art.hero ?: it.art.grid, it.accent.toColor(), it.art.heroFocusX, it.art.heroFocusY, it.art.video) }
        app.hints = if (selectedCard != null) {
            listOf(Hint(HintButton.CONFIRM, "Play"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))
        } else emptyList()
    }

    var columns by remember { mutableIntStateOf(6) }

    fun options(card: GameCard) {
        val base = app.gameMenu(card)
        app.openContextMenu(base.copy(actions = base.actions + viewActions(app, state, platformId, layout)))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        val count = list?.size ?: 0
        if (state.inFilters && filters.isNotEmpty()) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> if (state.filterIndex > 0) { state.filterIndex--; state.grid.index = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (state.filterIndex < filters.lastIndex) { state.filterIndex++; state.grid.index = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN, NavAction.SELECT -> { state.inFilters = false; NavResult.MOVED }
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
                if (r == NavResult.IGNORED && e.action == NavAction.UP && filters.isNotEmpty()) {
                    state.inFilters = true
                    NavResult.MOVED
                } else if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT || e.action == NavAction.DOWN)) {
                    NavResult.BLOCKED
                } else r
            }
            NavAction.SELECT -> { selectedCard?.let { app.play(it) }; if (selectedCard != null) NavResult.ACTIVATED else NavResult.BLOCKED }
            NavAction.CONTEXT -> {
                if (selectedCard != null) options(selectedCard)
                else app.openContextMenu(ContextMenuSpec("Library", actions = viewActions(app, state, platformId, layout)))
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = LocalTileMetrics.current
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            LibraryHeader(app, scope, platforms.firstOrNull { it.platform.id == platformId }, list?.size, filters.map { it.label }, state)
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> LibraryEmpty(scope, filter.label)
                else -> when (layout) {
                    LibraryLayout.ICON -> {
                        Box(Modifier.fillMaxWidth().height((maxHeight * 0.22f).coerceIn(110.dp, 200.dp)).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
                            Stage(selectedCard?.stage(), showLogo = prefs.showLogo, logoHeight = 84.dp)
                        }
                        Spacer(Modifier.height(Space.l))
                        val cols = ((maxWidth - Space.gutter * 2 + metrics.gap) / (metrics.icon + metrics.gap)).toInt().coerceAtLeast(2)
                        columns = cols
                        IconGrid(list, state, cols, metrics.icon, metrics.gap, onTap = { i -> tap(app, state, list, i) }, onLong = { i -> state.grid.index = i; options(list[i]) }, focused = !state.inFilters && app.focusZone == FocusZone.CONTENT)
                    }
                    LibraryLayout.CAPSULE -> {
                        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
                            Stage(selectedCard?.stage(), showLogo = prefs.showLogo, logoHeight = 128.dp)
                        }
                        Spacer(Modifier.height(Space.xl))
                        CoverCarousel(
                            items = list,
                            selected = state.grid.index,
                            itemWidth = metrics.capsuleWidth * 0.62f,
                            onTap = { i -> tap(app, state, list, i) },
                            onLongPress = { i -> state.grid.index = i; options(list[i]) },
                            focused = !state.inFilters && app.focusZone == FocusZone.CONTENT,
                        )
                        Spacer(Modifier.height(Size.hintHeight + Space.l))
                    }
                    LibraryLayout.COVER_GRID -> {
                        val coverW = metrics.coverWidth
                        val cols = ((maxWidth - Space.gutter * 2 + metrics.gap) / (coverW + metrics.gap)).toInt().coerceAtLeast(2)
                        columns = cols
                        CoverGrid(list, state, cols, coverW, metrics.gap, onTap = { i -> tap(app, state, list, i) }, onLong = { i -> state.grid.index = i; options(list[i]) }, focused = !state.inFilters && app.focusZone == FocusZone.CONTENT)
                    }
                    LibraryLayout.COMPACT_LIST -> {
                        columns = 1
                        CompactList(list, state, onTap = { i -> tap(app, state, list, i) }, onLong = { i -> state.grid.index = i; options(list[i]) }, focused = !state.inFilters && app.focusZone == FocusZone.CONTENT)
                    }
                }
            }
        }
    }
}

private fun tap(app: AppState, state: LibraryViewState, list: List<GameCard>, i: Int) {
    app.focusZone = FocusZone.CONTENT
    state.inFilters = false
    if (state.grid.index == i) app.play(list[i]) else state.grid.index = i
}

@Composable
private fun LibraryHeader(
    app: AppState,
    scope: LibraryScope,
    platform: io.github.matiyaaa.fuse.ui.shell.store.PlatformCard?,
    count: Int?,
    filters: List<String>,
    state: LibraryViewState,
) {
    val c = Fuse.colors
    if (filters.isNotEmpty()) {
        val listState = rememberLazyListState()
        FollowSelection(listState, { state.filterIndex }, anchor = 0.3f)
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            modifier = Modifier.padding(top = Space.xs),
        ) {
            itemsIndexed(filters) { i, label ->
                val focused = state.inFilters && app.focusZone == FocusZone.CONTENT && i == state.filterIndex
                Box(
                    Modifier
                        .clip(PillShape)
                        .clickable(remember { MutableInteractionSource() }, null) { state.filterIndex = i; state.grid.index = 0; state.inFilters = false }
                        .then(if (focused) Modifier.background(c.text.copy(alpha = 0.001f)) else Modifier),
                ) {
                    Chip(label, selected = i == state.filterIndex, color = if (focused) c.text else c.textMuted, background = if (focused) c.text.copy(alpha = 0.2f) else c.text.copy(alpha = 0.08f))
                }
            }
        }
    } else {
        Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            val title = when (scope) {
                is LibraryScope.OfPlatform -> platform?.platform?.name ?: scope.platform.value
                is LibraryScope.OfCollection -> scope.name
                LibraryScope.All -> "Library"
            }
            FText(title, Fuse.type.titleSmall, maxLines = 1)
            if (count != null) {
                Spacer(Modifier.width(Space.m))
                FText("$count ${if (count == 1) "game" else "games"}", Fuse.type.label, color = c.textMuted)
            }
            if (platform != null && !platform.emulatorInstalled) {
                Spacer(Modifier.width(Space.m))
                Chip("No emulator installed", icon = FuseIcons.Warning, color = c.warning)
            }
        }
    }
}

@Composable
private fun IconGrid(
    list: List<GameCard>,
    state: LibraryViewState,
    columns: Int,
    size: Dp,
    gap: Dp,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    val grid = rememberLazyGridState()
    FollowSelection(grid, { state.grid.index }, anchor = 0.05f)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
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
    columns: Int,
    width: Dp,
    gap: Dp,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    val grid = rememberLazyGridState()
    FollowSelection(grid, { state.grid.index }, anchor = 0.1f)
    val selected = list.getOrNull(state.grid.index)
    Column {
        Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            FText(selected?.title ?: "", Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (selected != null) {
                Spacer(Modifier.width(Space.m))
                FText(listOfNotNull(selected.platformShort, selected.year?.toString(), selected.playSeconds.takeIf { it > 0 }?.let(::playtimeText)).joinToString("  ·  "), Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
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
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    focused: Boolean,
) {
    val c = Fuse.colors
    val listState = rememberLazyListState()
    FollowSelection(listState, { state.grid.index }, anchor = 0.35f)
    val selected = list.getOrNull(state.grid.index)
    Row(Modifier.fillMaxSize().padding(start = Space.gutter, end = Space.gutter)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
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
                    Artwork(
                        card.art.icon ?: card.art.boxart,
                        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                        fallback = { GeneratedArt(card.title, card.accent.toColor(), slot = ArtSlot.ICON) },
                    )
                    Spacer(Modifier.width(Space.m))
                    FText(card.title, if (sel) Fuse.type.bodyStrong else Fuse.type.body, color = if (card.missing) c.textFaint else c.text, maxLines = 1, modifier = Modifier.weight(1f))
                    if (card.favorite) FuseIcon(FuseIcons.Heart, size = 14.dp, tint = c.textMuted)
                    Spacer(Modifier.width(Space.m))
                    FText(card.platformShort, Fuse.type.caption, color = c.textMuted, maxLines = 1)
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
                        selected.art.boxart ?: selected.art.grid ?: selected.art.icon,
                        Modifier.fillMaxSize(),
                        fallback = { GeneratedArt(selected.title, selected.accent.toColor(), slot = ArtSlot.BOX, label = selected.platformShort) },
                    )
                }
                Spacer(Modifier.height(Space.l))
                Stage(selected.stage(), logoHeight = 64.dp)
            }
        }
    }
}

@Composable
private fun LibraryEmpty(scope: LibraryScope, filterLabel: String) {
    val c = Fuse.colors
    Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            FText(
                when (scope) {
                    is LibraryScope.OfCollection -> "This collection is empty"
                    is LibraryScope.OfPlatform -> "No games for this system yet"
                    LibraryScope.All -> if (filterLabel == "Favourites") "No favourites yet" else "Nothing here yet"
                },
                Fuse.type.display,
            )
            FText(
                when (scope) {
                    is LibraryScope.OfCollection -> "Add games from any game's options (Add to Collection)."
                    is LibraryScope.OfPlatform -> "Put games in this system's folder, or get them from your RomM server with Cartridge. They appear here on their own."
                    LibraryScope.All -> if (filterLabel == "Favourites") "Mark games as favourites from their options." else "Games you add show up here."
                },
                Fuse.type.body,
                color = c.textMuted,
            )
        }
    }
}

/** View options appended to the game menu inside a library: layout, sort, hidden games, rescan. */
private fun viewActions(app: AppState, state: LibraryViewState, platform: PlatformId?, current: LibraryLayout): List<MenuAction> {
    fun layoutLabel(l: LibraryLayout) = when (l) {
        LibraryLayout.ICON -> "Icons"
        LibraryLayout.CAPSULE -> "Capsules"
        LibraryLayout.COVER_GRID -> "Cover grid"
        LibraryLayout.COMPACT_LIST -> "List"
    }
    fun sortLabel(s: SortOrder) = when (s) {
        SortOrder.TITLE -> "Title"
        SortOrder.RECENTLY_PLAYED -> "Recently played"
        SortOrder.RECENTLY_ADDED -> "Recently added"
        SortOrder.MOST_PLAYED -> "Most played"
        SortOrder.RELEASE_YEAR -> "Release year"
    }
    return listOf(
        MenuAction("layout", "View as", FuseIcons.Grid, trailing = Trailing.Value(layoutLabel(current)), onSelect = {
            app.contextMenu = null
            app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
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
        }),
        MenuAction("sort", "Sort by", FuseIcons.Sliders, trailing = Trailing.Value(sortLabel(state.sort)), onSelect = {
            app.contextMenu = null
            app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                title = "Sort by",
                options = SortOrder.entries.map { s ->
                    MenuAction(s.name, sortLabel(s), FuseIcons.Sliders, trailing = Trailing.Check(s == state.sort), onSelect = {
                        state.sort = s
                        app.choice = null
                    })
                },
            )
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
