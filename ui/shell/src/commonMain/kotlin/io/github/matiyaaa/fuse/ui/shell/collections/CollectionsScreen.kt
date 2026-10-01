package io.github.matiyaaa.fuse.ui.shell.collections

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
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
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
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
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.CoverCollage
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A card on the Collections screen: the "new collection" card, or a collection. */
private sealed interface CollectionItem {
    data object New : CollectionItem
    data class Of(val collection: GameCollection) : CollectionItem
}

/**
 * Every collection as a card made of its games' art: the user's own first (after a card to make a
 * new one), then the series Fuse found. A tap or A opens one; X has its options.
 */
@Composable
fun CollectionsScreen(app: AppState) {
    val store = app.store
    val all by store.collections.collections.collectAsState()
    val mine = all.filter { it.kind != CollectionKind.SERIES }
    val series = all.filter { it.kind == CollectionKind.SERIES }.sortedBy { it.name.lowercase() }
    val items: List<CollectionItem> = listOf(CollectionItem.New) + (mine + series).map { CollectionItem.Of(it) }
    val sel = rememberRouteState(app.navigator, "collections") { GridSelection() }
    sel.clamp(items.size)
    var columns = 4
    val current = (items.getOrNull(sel.index) as? CollectionItem.Of)?.collection

    // The room is lit by the focused collection: its own background, else its first game's.
    val currentGames by remember(current?.id) {
        current?.let { store.library.games(GameQuery(collection = it.id)) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val currentMedia by remember(current?.id) {
        current?.let { store.media.media(MediaOwner.OfCollection(it.id)) } ?: kotlinx.coroutines.flow.flowOf(MediaSet.Empty)
    }.collectAsState(initial = MediaSet.Empty)
    val accent = Fuse.colors.accent
    LaunchedEffect(current?.id, currentGames.firstOrNull()?.id, currentMedia) {
        val first = currentGames.firstOrNull()
        app.hero = current?.let { c ->
            HeroSource(c.id, currentMedia.hero()?.model() ?: first?.art?.hero ?: first?.art?.grid, first?.accent?.toColor() ?: accent)
        }
        app.hints = when (items.getOrNull(sel.index)) {
            CollectionItem.New -> listOf(Hint(HintButton.CONFIRM, "New collection"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"))
        }
    }

    fun open(item: CollectionItem) {
        when (item) {
            CollectionItem.New -> app.newCollection()
            is CollectionItem.Of -> app.go(Route.CollectionGames(item.collection.id, item.collection.name))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN ->
                sel.move(e.action, items.size, columns).let { if (it == NavResult.IGNORED && e.action != NavAction.UP) NavResult.BLOCKED else it }
            NavAction.SELECT -> { items.getOrNull(sel.index)?.let(::open); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.collectionMenu(it)) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 560.dp
        val gap = Space.l
        val usable = maxWidth - Space.gutter * 2
        columns = ((usable + gap) / (300.dp + gap)).toInt().coerceIn(2, 5)
        val cardWidth = (usable - gap * (columns - 1)) / columns
        val artHeight = cardWidth * 0.56f
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.Bottom) {
                FText("Collections", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                Spacer(Modifier.width(Space.l))
                FText(
                    listOfNotNull(
                        "${mine.size} yours",
                        series.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "series" else "series"} found" },
                    ).joinToString("  ·  "),
                    Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = Space.xs),
                )
            }
            val grid = rememberLazyGridState()
            FollowSelection(grid, { sel.index }, anchor = 0.2f)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.weight(1f).fadingEdges(top = if (grid.canScrollBackward) 24.dp else 0.dp),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(if (compact) Space.l else Space.xl),
            ) {
                itemsIndexed(items, key = { _, item -> if (item is CollectionItem.Of) item.collection.id.value else -1L }) { i, item ->
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val tap = {
                        app.focusZone = FocusZone.CONTENT
                        if (sel.index == i) open(item) else sel.index = i
                    }
                    when (item) {
                        CollectionItem.New -> NewCollectionCard(selected, artHeight, tap)
                        is CollectionItem.Of -> CollectionCard(
                            app, item.collection, selected, artHeight, tap,
                            onLongClick = { sel.index = i; app.openContextMenu(app.collectionMenu(item.collection)) },
                        )
                    }
                }
            }
        }
    }
}

private fun MediaSet.hero(): io.github.matiyaaa.fuse.model.MediaItem? = all(io.github.matiyaaa.fuse.model.MediaKind.HERO).firstOrNull()

private fun io.github.matiyaaa.fuse.model.MediaItem.model(): Any? = localPath ?: remoteUrl

@Composable
private fun NewCollectionCard(selected: Boolean, artHeight: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Column {
        Tile(
            selected = selected,
            modifier = Modifier.fillMaxWidth().height(artHeight),
            shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.6f),
            cornerFraction = Fuse.geometry.tileCornerFraction * 0.6f,
            onClick = onClick,
        ) {
            Box(Modifier.fillMaxSize().background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FuseIcon(FuseIcons.Plus, size = 30.dp, tint = c.text)
                    Spacer(Modifier.height(Space.s))
                    FText("New collection", Fuse.type.bodyStrong, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        FText("Pick games for it next", Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun CollectionCard(
    app: AppState,
    collection: GameCollection,
    selected: Boolean,
    artHeight: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Fuse.colors
    val games by remember(collection.id) { app.store.library.games(GameQuery(collection = collection.id)) }.collectAsState(initial = emptyList())
    val media by remember(collection.id) { app.store.media.media(MediaOwner.OfCollection(collection.id)) }.collectAsState(initial = MediaSet.Empty)
    Column {
        Tile(
            selected = selected,
            modifier = Modifier.fillMaxWidth().height(artHeight),
            shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.6f),
            cornerFraction = Fuse.geometry.tileCornerFraction * 0.6f,
            glow = games.firstOrNull()?.accent?.toColor() ?: c.accent,
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            val own = media.all(io.github.matiyaaa.fuse.model.MediaKind.BOXART).firstOrNull() ?: media.hero()
            when {
                own != null && media.hero() == null -> io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(own.model(), Modifier.fillMaxSize())
                games.isNotEmpty() -> CoverCollage(games, artHeight, background = media.hero()?.model())
                else -> Box(Modifier.fillMaxSize().background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    FText(if (collection.kind == CollectionKind.SERIES) "No games" else "Empty. Add games from its options", Fuse.type.caption, color = c.textMuted, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FText(collection.name, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.9f), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (collection.kind == CollectionKind.SERIES) Chip("Series", icon = FuseIcons.Sparkles, color = c.textMuted)
        }
        FText("${collection.gameCount} ${if (collection.gameCount == 1) "game" else "games"}", Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** Asks for a name, makes the collection, then offers to pick its games. */
fun AppState.newCollection() {
    textInput = TextInputSpec("New collection", "", "Collection name", doneLabel = "Create") { name ->
        if (name.isBlank()) return@TextInputSpec
        scope.launch {
            val id = store.collections.create(name.trim())
            toasts.show("Made ${name.trim()}")
            addGamesPicker(id, name.trim())
        }
    }
}

/** Options for a collection (X on its card, or a long press). */
fun AppState.collectionMenu(c: GameCollection): ContextMenuSpec {
    val series = c.kind == CollectionKind.SERIES
    val owner = MediaOwner.OfCollection(c.id)
    return ContextMenuSpec(
        title = c.name,
        subtitle = if (series) "Series Fuse found  ·  ${c.gameCount} games" else "${c.gameCount} games",
        actions = buildList {
            add(MenuAction("open", "Open", FuseIcons.Grid, onSelect = { closeOverlays(); go(Route.CollectionGames(c.id, c.name)) }))
            if (series) {
                add(MenuAction("keep", "Keep as my collection", FuseIcons.Bookmark, detail = "Fuse stops changing it; you add and remove its games", onSelect = {
                    closeOverlays()
                    store.updatePrefs { it.copy(hiddenSeries = (it.hiddenSeries + c.name.lowercase()).distinct()) }
                    scope.launch { store.collections.keepSeries(c.id); toasts.show("${c.name} is yours now") }
                }))
            } else {
                add(MenuAction("add", "Add or remove games", FuseIcons.ListPlus, trailing = Trailing.Chevron, onSelect = { closeOverlays(); addGamesPicker(c.id, c.name) }))
                add(MenuAction("rename", "Rename", FuseIcons.TextCursor, onSelect = {
                    closeOverlays()
                    textInput = TextInputSpec("Rename collection", c.name) { name -> scope.launch { store.collections.rename(c.id, name) } }
                }))
            }
            add(MenuAction("art", "Change art", FuseIcons.Image, detail = "Made from its games until you choose your own", trailing = Trailing.Chevron, onSelect = {
                closeOverlays(); go(Route.Media(owner, c.name))
            }))
            if (series) {
                add(MenuAction("hide", "Hide this series", FuseIcons.EyeOff, detail = "Fuse won't make it again. Bring it back in Settings, Library", onSelect = {
                    closeOverlays()
                    store.updatePrefs { it.copy(hiddenSeries = (it.hiddenSeries + c.name.lowercase()).distinct()) }
                    toasts.show("${c.name} hidden")
                }))
            } else {
                add(MenuAction("delete", "Delete collection", FuseIcons.Trash, destructive = true, detail = "Its games stay in your library", onSelect = {
                    closeOverlays()
                    confirm = ConfirmSpec("Delete ${c.name}?", "The collection goes away. Its games stay in your library, untouched.", "Delete", destructive = true) {
                        scope.launch { store.collections.delete(c.id) }
                    }
                }))
            }
        },
    )
}

/**
 * Every game in the library with a check for the ones in the collection. Each choice is saved at
 * once, and the list stays open until Done.
 */
fun AppState.addGamesPicker(id: io.github.matiyaaa.fuse.model.CollectionId, name: String) {
    scope.launch {
        val games = store.library.games(GameQuery(sort = SortOrder.TITLE)).first()
        val members = store.library.games(GameQuery(collection = id)).first().map { it.id }.toMutableSet()
        fun spec(): ChoiceSpec = ChoiceSpec(
            title = "Games in $name",
            message = "${members.size} ${if (members.size == 1) "game" else "games"}. Select games to add or remove them.",
            options = listOf(MenuAction("done", "Done", FuseIcons.Check, onSelect = { choice = null })) +
                games.map { g: GameCard ->
                    val inIt = g.id in members
                    MenuAction("g${g.id.value}", g.title, null, detail = g.platformShort, trailing = Trailing.Check(inIt), onSelect = {
                        scope.launch {
                            if (inIt) store.collections.remove(id, g.id) else store.collections.add(id, g.id)
                            if (inIt) members.remove(g.id) else members.add(g.id)
                            choice = spec()
                        }
                    })
                },
        )
        choice = spec()
    }
}
