package io.github.matiyaaa.fuse.ui.shell.collections

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
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
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
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
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A card on the Collections screen: a collection. */
private sealed interface CollectionItem {
    data class Of(val collection: GameCollection) : CollectionItem
}

/** The two views of the Collections tab. Your own collections come first. */
private enum class CollectionsView(val label: String) { COLLECTIONS("Collections"), SERIES("Series") }

/** Which view is showing, whether focus is on the tabs, and each view's own place. */
private class CollectionsViewState {
    var view by mutableStateOf(CollectionsView.COLLECTIONS)
    var inTabs by mutableStateOf(false)
    /** In the header, on the New collection button rather than a view. */
    var onNew by mutableStateOf(false)
    private val selections = mutableMapOf<CollectionsView, GridSelection>()
    fun selection(v: CollectionsView): GridSelection = selections.getOrPut(v) { GridSelection() }
}

/**
 * Collections in two views: your own, and the series Fuse found. Each is a grid of cards made of the
 * games' art. A tap or A opens one; X has its options. Up from the first row reaches the header:
 * Left and Right switch the views, and go on to New collection at the end of the row.
 */
@Composable
fun CollectionsScreen(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val all by store.collections.collections.collectAsState()
    val mine = all.filter { it.kind != CollectionKind.SERIES }
    val series = all.filter { it.kind == CollectionKind.SERIES }.sortedBy { it.name.lowercase() }
    val state = rememberRouteState(app.navigator, "collections") { CollectionsViewState() }
    val view = state.view
    val inTabs = state.inTabs && app.focusZone == FocusZone.CONTENT
    val items: List<CollectionItem> = when (view) {
        CollectionsView.COLLECTIONS -> mine.map { CollectionItem.Of(it) }
        CollectionsView.SERIES -> series.map { CollectionItem.Of(it) }
    }
    val sel = state.selection(view)
    sel.clamp(items.size)
    var columns = 4
    val current = (items.getOrNull(sel.index) as? CollectionItem.Of)?.collection?.takeIf { !inTabs }

    // The room is lit by the focused collection: its own background, else its first game's.
    val currentGames by remember(current?.id) {
        current?.let { store.library.games(GameQuery(collection = it.id)) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val currentMedia by remember(current?.id) {
        current?.let { store.media.media(MediaOwner.OfCollection(it.id)) } ?: kotlinx.coroutines.flow.flowOf(MediaSet.Empty)
    }.collectAsState(initial = MediaSet.Empty)
    val accent = Fuse.colors.accent
    LaunchedEffect(current?.id, currentGames.firstOrNull()?.id, currentMedia, inTabs, items.isEmpty()) {
        val first = currentGames.firstOrNull()
        app.hero = current?.let { c ->
            HeroSource(c.id, currentMedia.hero()?.model() ?: first?.art?.hero ?: first?.art?.grid, first?.accent?.toColor() ?: accent)
        }
        app.hints = when {
            inTabs && state.onNew -> listOf(Hint(HintButton.CONFIRM, "New collection"))
            inTabs -> listOf(Hint(HintButton.CONFIRM, "Choose"))
            items.isEmpty() && view == CollectionsView.COLLECTIONS -> listOf(Hint(HintButton.CONFIRM, "New collection"))
            items.isEmpty() -> if (prefs.autoSeries) emptyList() else listOf(Hint(HintButton.CONFIRM, "Turn on Automatic series"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"))
        }
    }

    fun open(item: CollectionItem) {
        when (item) {
            is CollectionItem.Of -> app.go(Route.CollectionGames(item.collection.id, item.collection.name))
        }
    }
    fun show(v: CollectionsView) {
        app.focusZone = FocusZone.CONTENT
        state.view = v
    }
    fun turnOnSeries() = app.scope.launch { store.updatePrefs { it.copy(autoSeries = true) } }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        val views = CollectionsView.entries
        if (inTabs) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> when {
                    state.onNew -> { state.onNew = false; NavResult.MOVED }
                    view.ordinal > 0 -> { show(views[view.ordinal - 1]); NavResult.MOVED }
                    else -> NavResult.BLOCKED
                }
                NavAction.RIGHT -> when {
                    state.onNew -> NavResult.BLOCKED
                    view.ordinal < views.lastIndex -> { show(views[view.ordinal + 1]); NavResult.MOVED }
                    else -> { state.onNew = true; NavResult.MOVED }
                }
                NavAction.SELECT -> {
                    if (state.onNew) app.newCollection() else state.inTabs = false
                    NavResult.ACTIVATED
                }
                NavAction.DOWN -> { state.inTabs = false; state.onNew = false; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val r = if (items.isEmpty()) NavResult.IGNORED else sel.move(e.action, items.size, columns)
                when {
                    r == NavResult.IGNORED && e.action == NavAction.UP -> { state.inTabs = true; NavResult.MOVED }
                    r == NavResult.IGNORED -> NavResult.BLOCKED
                    else -> r
                }
            }
            NavAction.SELECT -> {
                when {
                    items.isNotEmpty() -> items.getOrNull(sel.index)?.let(::open)
                    view == CollectionsView.COLLECTIONS -> app.newCollection()
                    !prefs.autoSeries -> turnOnSeries()
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.collectionMenu(it)) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val entry = rememberReveal()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        // Sized like the Systems screen's cards: about six across, never smaller than a thumb.
        val gap = Space.m
        val usable = maxWidth - Space.gutter * 2
        val target = (maxWidth * 0.135f).coerceAtLeast(Size.touch * 2 + Space.l)
        columns = ((usable + gap) / (target + gap)).toInt().coerceIn(if (maxWidth < Size.touch * 12) 2 else 3, 8)
        val cardWidth = (usable - gap * (columns - 1)) / columns
        val artHeight = cardWidth / Aspect.SYSTEM_CARD
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.xs else Space.m))
            // The views, and at the end of the same line the way to make a new collection.
            Row(Modifier.fillMaxWidth().reveal(entry, 0), verticalAlignment = Alignment.CenterVertically) {
                ViewTabs(
                    items = listOf(
                        ViewTab(CollectionsView.COLLECTIONS.label, icon = FuseIcons.Bookmark, badge = mine.size.toString()),
                        ViewTab(CollectionsView.SERIES.label, icon = FuseIcons.Sparkles, badge = series.size.takeIf { it > 0 }?.toString()),
                    ),
                    active = view.ordinal,
                    focused = view.ordinal.takeIf { inTabs && !state.onNew },
                    onSelect = { i -> show(CollectionsView.entries[i]); state.inTabs = false; state.onNew = false },
                    modifier = Modifier.weight(1f),
                )
                FuseButton(
                    "New collection",
                    selected = inTabs && state.onNew,
                    icon = FuseIcons.Plus,
                    kind = if (mine.isEmpty()) io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind.PRIMARY else io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind.SECONDARY,
                    height = if (compact) 40.dp else Size.touch,
                    onClick = { app.focusZone = FocusZone.CONTENT; app.newCollection() },
                    modifier = Modifier.padding(start = Space.m, end = Space.gutter),
                )
            }
            if (items.isEmpty()) {
                val selected = !inTabs && app.focusZone == FocusZone.CONTENT
                if (view == CollectionsView.SERIES) {
                    SeriesEmpty(prefs.autoSeries, selected = selected, compact = compact, onTurnOn = ::turnOnSeries)
                } else {
                    CollectionsEmpty(selected = selected, compact = compact, onNew = { app.focusZone = FocusZone.CONTENT; app.newCollection() })
                }
                return@Column
            }
            // Each view keeps its own scroll, so switching back finds you where you were, and its
            // cards rise in again when it is shown.
            key(view) {
                val reveal = rememberReveal(view)
                val grid = rememberLazyGridState()
                FollowSelection(grid, { sel.index }, anchor = 0.2f)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier = Modifier.weight(1f).fadingEdges(grid, top = Space.xl, bottom = Size.hintHeight + Space.l),
                    contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                ) {
                    itemsIndexed(items, key = { _, item -> if (item is CollectionItem.Of) item.collection.id.value else -1L }) { i, item ->
                        val selected = !inTabs && i == sel.index && app.focusZone == FocusZone.CONTENT
                        val tap = {
                            app.focusZone = FocusZone.CONTENT
                            state.inTabs = false
                            sel.index = i
                            open(item)
                        }
                        // Row by row as the view opens.
                        val rise = Modifier.reveal(reveal, 1 + i / columns)
                        when (item) {
                            is CollectionItem.Of -> CollectionCard(
                                app, item.collection, selected, artHeight, tap,
                                onLongClick = { state.inTabs = false; sel.index = i; app.openContextMenu(app.collectionMenu(item.collection)) },
                                modifier = rise,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Your own collections, none yet: what they are for, and the button that makes the first. */
@Composable
private fun ColumnScope.CollectionsEmpty(selected: Boolean, compact: Boolean, onNew: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xl).padding(bottom = Size.hintHeight),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            FuseIcons.Bookmark,
            "No collections yet",
            message = "Gather games into shelves of your own: couch co-op, a weekend queue, the ones you love. You can also add a game to one from its options.",
            actionLabel = "New collection",
            actionIcon = FuseIcons.Plus,
            actionSelected = selected,
            onAction = onNew,
            compact = compact,
        )
    }
}

/** The Series view with nothing in it: what it is for, and the switch when it is off. */
@Composable
private fun ColumnScope.SeriesEmpty(on: Boolean, selected: Boolean, compact: Boolean, onTurnOn: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xl).padding(bottom = Size.hintHeight),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            FuseIcons.Sparkles,
            if (on) "No series yet" else "Automatic series is off",
            message = if (on) {
                "Fuse gathers the games of one series into a collection of their own once it finds two or more of them."
            } else {
                "Turn it on and Fuse gathers the games of one series into collections of their own."
            },
            actionLabel = if (on) null else "Turn on Automatic series",
            actionIcon = FuseIcons.Sparkles,
            actionSelected = selected,
            onAction = onTurnOn,
            compact = compact,
        )
    }
}

private fun MediaSet.hero(): io.github.matiyaaa.fuse.model.MediaItem? = all(io.github.matiyaaa.fuse.model.MediaKind.HERO).firstOrNull()

private fun io.github.matiyaaa.fuse.model.MediaItem.model(): Any? = localPath ?: remoteUrl

/**
 * The face of a card with nothing to show yet: a well in the theme's dim surface, made opaque over
 * the room (a translucent face would let the tile's shadow through as a dark box when it lifts),
 * with a soft light from the top and, when [dashed], a dashed edge drawn just inside the outline so
 * the tile's clip never halves it.
 */
@Composable
private fun SlotFace(shape: androidx.compose.ui.graphics.Shape, dashed: Boolean = false, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val c = Fuse.colors
    val fill = c.surfaceDim.compositeOver(c.ink)
    val light = c.text.copy(alpha = if (c.isDark) 0.04f else 0.03f)
    val dash = c.hairlineStrong
    Box(
        Modifier
            .fillMaxSize()
            .background(fill)
            .background(Brush.verticalGradient(listOf(light, Color.Transparent)))
            .drawWithCache {
                val stroke = Size.focusStroke.toPx()
                val inset = stroke / 2 + 1f
                val inner = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                val outline = Path().apply {
                    addOutline(shape.createOutline(inner, layoutDirection, this@drawWithCache))
                    translate(Offset(inset, inset))
                }
                val on = Space.s.toPx()
                val style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, on * 0.75f)))
                onDrawBehind { if (dashed) drawPath(outline, dash, style = style) }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun CollectionCard(
    app: AppState,
    collection: GameCollection,
    selected: Boolean,
    artHeight: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val c = Fuse.colors
    val games by remember(collection.id) { app.store.library.games(GameQuery(collection = collection.id)) }.collectAsState(initial = emptyList())
    val media by remember(collection.id) { app.store.media.media(MediaOwner.OfCollection(collection.id)) }.collectAsState(initial = MediaSet.Empty)
    val fraction = Fuse.geometry.tileCornerFraction * 0.6f
    val shape = remember(fraction) { SquircleShape.fraction(fraction) }
    Column(modifier) {
        Tile(
            selected = selected,
            modifier = Modifier.fillMaxWidth().height(artHeight),
            shape = shape,
            cornerFraction = fraction,
            glow = games.firstOrNull()?.accent?.toColor() ?: c.accent,
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            val own = media.all(io.github.matiyaaa.fuse.model.MediaKind.BOXART).firstOrNull() ?: media.hero()
            when {
                own != null && media.hero() == null -> io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(own.model(), Modifier.fillMaxSize())
                games.isNotEmpty() -> CoverCollage(games, artHeight, background = media.hero()?.model())
                // Its games are still on their way: the card holds its place.
                collection.gameCount > 0 -> Box(Modifier.fillMaxSize().skeleton())
                else -> SlotFace(shape) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        FuseIcon(if (collection.kind == CollectionKind.SERIES) FuseIcons.Sparkles else FuseIcons.Bookmark, size = Size.iconM, tint = c.textMuted)
                        FText("No games yet", Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
                    }
                }
            }
        }
        CardLabel(collection.name, countText(collection.gameCount), selected)
    }
}

/**
 * A card's name and count under its art. It starts below the spark's reach, so the bar under a
 * lifted card never touches the words.
 */
@Composable
private fun CardLabel(title: String, detail: String, selected: Boolean) {
    val c = Fuse.colors
    Spacer(Modifier.height(Size.sparkClearance))
    FText(title, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 1)
    Spacer(Modifier.height(Space.xxs))
    FText(detail, Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1)
}

private fun countText(n: Int) = "$n ${if (n == 1) "game" else "games"}"

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
        subtitle = if (series) "Series Fuse found  ·  ${countText(c.gameCount)}" else countText(c.gameCount),
        icon = if (series) FuseIcons.Sparkles else FuseIcons.Bookmark,
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
            message = "${countText(members.size)}. Select games to add or remove them.",
            options = listOf(MenuAction("done", "Done", FuseIcons.Check, onSelect = { choice = null })) +
                games.map { g: GameCard ->
                    val inIt = g.id in members
                    MenuAction(
                        "g${g.id.value}", g.title, null, detail = g.platformShort, trailing = Trailing.Check(inIt),
                        // Every game shows its art, so a long list is quick to scan.
                        art = MenuArt(g.art.square ?: g.art.icon ?: g.art.boxart, square = true, fallbackTitle = g.title, accent = g.accent, wide = false),
                        section = "Your library",
                        onSelect = {
                        scope.launch {
                            if (inIt) store.collections.remove(id, g.id) else store.collections.add(id, g.id)
                            if (inIt) members.remove(g.id) else members.add(g.id)
                            choice = spec()
                        }
                    },
                    )
                },
        )
        choice = spec()
    }
}
