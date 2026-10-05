package io.github.matiyaaa.fuse.ui.shell.jellyfin

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.jellyfin.Shelf
import io.github.matiyaaa.fuse.jellyfin.ShelfKind
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.ReportScroll
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import kotlinx.coroutines.launch

/** What the Jellyfin home keeps while you go into a page and come back. */
internal class JellyfinHomeState {
    val sel = ShelfSelection(initialRow = 1)
    var shelves by mutableStateOf<List<Shelf>?>(null)
    var libraries by mutableStateOf<List<MediaItem>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var loadedFor by mutableStateOf<String?>(null)
}

/** One row of the Jellyfin home: the buttons on top, the libraries, or a shelf of items. */
private sealed interface HomeRow {
    val key: String
    val size: Int

    class Top(val buttons: List<Pair<String, () -> Unit>>) : HomeRow {
        override val key = "top"
        override val size get() = buttons.size
    }

    class Libraries(val items: List<MediaItem>) : HomeRow {
        override val key = "libraries"
        override val size get() = items.size
    }

    class Items(val shelf: Shelf) : HomeRow {
        override val key = shelf.id
        override val size get() = shelf.items.size
    }
}

/**
 * Jellyfin in Addons: what to continue and watch next, your libraries, and what's new in each,
 * with your server's art and Fuse's own cards. Its pages keep working offline with what was last
 * seen. Before it is set up it says where to do that.
 */
@Composable
internal fun JellyfinContent(app: AppState, active: Boolean, topPadding: Dp) {
    val service = app.jellyfin ?: return
    val state by service.state.collectAsState()
    val page = rememberRouteState(app.navigator, "jellyfin.home") { JellyfinHomeState() }
    val focused = active && app.focusZone == FocusZone.CONTENT
    val account = state.account

    // Loaded once per sign-in, and again after something was played or marked.
    val revision by service.revision.collectAsState()
    LaunchedEffect(account?.userId, state.base != null || state.offline, revision) {
        if (account == null) return@LaunchedEffect
        val key = "${account.userId}:$revision"
        if (page.shelves != null && page.loadedFor == key) return@LaunchedEffect
        load(service, page)
        page.loadedFor = key
    }

    when {
        account == null -> Centered(topPadding) {
            SetUpState(app, focused, "Jellyfin isn't signed in", "Add your server's address and sign in, in Settings, Addons, Jellyfin.", "Set up Jellyfin")
        }
        state.authRequired -> Centered(topPadding) {
            SetUpState(app, focused, "Sign in again", "The server signed this device out. Sign in again in Settings, Addons, Jellyfin.", "Sign in")
        }
        page.shelves == null && page.error != null -> Centered(topPadding) {
            ErrorState(app, focused, page.error!!) { app.scope.launch { load(service, page) } }
        }
        page.shelves == null -> HomeSkeleton(topPadding)
        else -> Shelves(app, page, focused, topPadding, state.offline)
    }
}

private suspend fun load(service: io.github.matiyaaa.fuse.jellyfin.JellyfinService, page: JellyfinHomeState) {
    try {
        page.libraries = service.libraries()
        page.shelves = service.home()
        page.error = null
    } catch (e: Exception) {
        page.error = e.message ?: "The server can't be reached."
    }
}

@Composable
private fun Shelves(app: AppState, page: JellyfinHomeState, focused: Boolean, topPadding: Dp, offline: Boolean) {
    val sel = page.sel
    val shelves = page.shelves.orEmpty()
    val rows: List<HomeRow> = buildList {
        add(
            HomeRow.Top(
                listOf(
                    "Search" to { app.go(Route.MediaSearch) },
                    "Refresh" to {
                        app.scope.launch {
                            app.jellyfin?.changed()
                            app.jellyfin?.let { load(it, page) }
                        }
                    },
                    "Settings" to { app.go(Route.JellyfinSettings) },
                ),
            ),
        )
        // What you were in the middle of first, then your libraries, then what's new.
        shelves.filter { it.kind == ShelfKind.CONTINUE || it.kind == ShelfKind.NEXT_UP }.forEach { add(HomeRow.Items(it)) }
        if (page.libraries.isNotEmpty()) add(HomeRow.Libraries(page.libraries))
        shelves.filter { it.kind != ShelfKind.CONTINUE && it.kind != ShelfKind.NEXT_UP }.forEach { add(HomeRow.Items(it)) }
    }
    val keys = rows.map { it.key }
    fun sizeOf(key: String) = rows.firstOrNull { it.key == key }?.size ?: 0
    sel.clamp(keys, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: rows.first()
    val col = sel.column(row.key)
    val current: MediaItem? = when (row) {
        is HomeRow.Items -> row.shelf.items.getOrNull(col)
        is HomeRow.Libraries -> row.items.getOrNull(col)
        else -> null
    }
    PrefetchMediaArt(
        when (row) {
            is HomeRow.Items -> row.shelf.items
            is HomeRow.Libraries -> row.items
            else -> emptyList()
        },
        col,
    )

    fun activate(r: HomeRow, i: Int) {
        when (r) {
            is HomeRow.Top -> r.buttons.getOrNull(i)?.second?.invoke()
            is HomeRow.Libraries -> r.items.getOrNull(i)?.let { app.go(Route.MediaLibrary(it.id, it.name, it.library?.name)) }
            is HomeRow.Items -> r.shelf.items.getOrNull(i)?.let { item ->
                // Something you were watching plays on; anything else opens its page.
                if (r.shelf.kind == ShelfKind.CONTINUE || r.shelf.kind == ShelfKind.NEXT_UP) app.play(item) else app.openMedia(item)
            }
        }
    }

    PageEffect(row.key, current?.id, focused) {
        if (!focused) return@PageEffect
        app.hero = current?.hero()
        app.hints = when {
            row is HomeRow.Items && (row.shelf.kind == ShelfKind.CONTINUE || row.shelf.kind == ShelfKind.NEXT_UP) ->
                listOf(Hint(HintButton.CONFIRM, "Play"), Hint(HintButton.OPTIONS, "More"))
            current != null -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "More"))
            else -> listOf(Hint(HintButton.CONFIRM, "Choose"))
        }
    }

    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, keys, ::sizeOf).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            NavAction.UP, NavAction.DOWN -> sel.move(e.action, keys, ::sizeOf)
            NavAction.SELECT -> { activate(row, col); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { current?.takeIf { row !is HomeRow.Libraries }?.let { app.mediaOptions(it) }; NavResult.ACTIVATED }
            NavAction.SEARCH -> { app.go(Route.MediaSearch); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val list = rememberLazyListState()
    // The first shelf keeps the page at its top, so Search, Refresh and Settings stay in view when
    // Jellyfin opens on it; further down, the chosen shelf sits near the top. The offline banner,
    // when shown, is the list's first item.
    FollowSelection(list, { if (sel.row <= 1) 0 else sel.row + if (offline) 1 else 0 }, anchor = 0.12f)
    ReportScroll(list)
    val room = subTabsRoom()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        // A phone held upright gets smaller cards, so the next one always peeks in.
        val narrow = maxWidth < 600.dp
        val poster = when {
            narrow -> 120.dp
            compact -> 132.dp
            else -> 168.dp
        }
        val wide = when {
            narrow -> (maxWidth * 0.7f).coerceAtMost(248.dp)
            compact -> 248.dp
            else -> 320.dp
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = topPadding).fadingEdges(top = if (list.canScrollBackward) Space.xl else 0.dp),
            contentPadding = PaddingValues(top = room + if (compact) Space.s else Space.m, bottom = Size.hintHeight + Space.xl),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.l else Space.xl),
        ) {
            if (offline) item(key = "offline") { OfflineBanner() }
            for ((ri, r) in rows.withIndex()) {
                item(key = r.key) {
                    val chosen = if (focused && sel.row == ri) sel.column(r.key) else -1
                    fun tap(i: Int) {
                        app.focusZone = FocusZone.CONTENT
                        sel.row = ri
                        sel.setColumn(r.key, i)
                        activate(r, i)
                    }
                    when (r) {
                        is HomeRow.Top -> TopButtons(r.buttons, chosen, narrow, ::tap)
                        is HomeRow.Libraries -> ShelfRow("Libraries", FuseIcons.LibraryBig, r.items.size, chosen >= 0, sel.column(r.key)) {
                            itemsIndexed(r.items, key = { _, it -> it.id }) { i, item -> LibraryCard(item, i == chosen, wide, onClick = { tap(i) }) }
                        }
                        is HomeRow.Items -> {
                            val wideCards = r.shelf.kind == ShelfKind.CONTINUE || r.shelf.kind == ShelfKind.NEXT_UP
                            ShelfRow(r.shelf.title, shelfIcon(r.shelf.kind), r.shelf.items.size, chosen >= 0, sel.column(r.key)) {
                                itemsIndexed(r.shelf.items, key = { _, it -> it.id }) { i, item ->
                                    if (wideCards) {
                                        WideCard(item, i == chosen, wide, onClick = { tap(i) }, onLongClick = { app.mediaOptions(item) })
                                    } else {
                                        PosterCard(item, i == chosen, if (item.type == MediaType.ALBUM) poster * 1.2f else poster, onClick = { tap(i) }, onLongClick = { app.mediaOptions(item) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (shelves.isEmpty() && page.libraries.isEmpty()) {
                item(key = "empty") {
                    EmptyState(FuseIcons.Film, "Nothing here yet", Modifier.fillMaxWidth().padding(Space.gutter), message = "Your server's libraries show here once they have something in them.")
                }
            }
        }
    }
}

private fun shelfIcon(kind: ShelfKind) = when (kind) {
    ShelfKind.CONTINUE -> FuseIcons.History
    ShelfKind.NEXT_UP -> FuseIcons.SkipForward
    ShelfKind.FAVORITES -> FuseIcons.Heart
    ShelfKind.COLLECTIONS -> FuseIcons.Layers
    ShelfKind.MUSIC -> FuseIcons.Music
    else -> FuseIcons.Sparkles
}

/** A titled row of cards that scrolls on its own and keeps its place. */
@Composable
internal fun ShelfRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    count: Int,
    active: Boolean,
    remembered: Int,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    FollowSelection(list, { remembered }, anchor = 0f)
    Column {
        SectionLabel(
            title, Modifier.padding(start = Space.gutter, bottom = Space.s),
            color = if (active) Fuse.colors.text else Fuse.colors.textMuted, count = count.toString(), icon = icon,
        )
        LazyRow(
            state = list,
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 2, top = Space.xs, bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            content = content,
        )
    }
}

@Composable
private fun TopButtons(buttons: List<Pair<String, () -> Unit>>, chosen: Int, narrow: Boolean, onClick: (Int) -> Unit) {
    val icons = listOf(FuseIcons.Search, FuseIcons.Refresh, FuseIcons.Settings2)
    Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { i, (label, _) ->
            // Narrow, only Search keeps its word; the others are their icons.
            if (narrow && i > 0) {
                io.github.matiyaaa.fuse.ui.designsystem.components.IconButton(icons[i], selected = i == chosen, onClick = { onClick(i) }, size = 40.dp, contentDescription = label)
            } else {
                FuseButton(label, selected = i == chosen, onClick = { onClick(i) }, icon = icons.getOrNull(i), height = 40.dp)
            }
        }
    }
}

/** Offline: a calm line saying what still works. */
@Composable
internal fun OfflineBanner() {
    Row(
        Modifier.padding(horizontal = Space.gutter).background(Fuse.colors.surfaceRaised, RoundedCornerShape(Fuse.geometry.control)).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(FuseIcons.WifiOff, size = 18.dp, tint = Fuse.colors.textMuted)
        Spacer(Modifier.width(Space.m))
        FText("The server can't be reached. You're seeing what Fuse kept from last time; playing waits for the server.", Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 2)
    }
}

@Composable
private fun Centered(topPadding: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = topPadding + subTabsRoom(), bottom = Size.hintHeight), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SetUpState(app: AppState, focused: Boolean, title: String, message: String, action: String) {
    PageEffect(focused) { if (focused) { app.hero = null; app.hints = listOf(Hint(HintButton.CONFIRM, action)) } }
    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        if (e.action == NavAction.SELECT) { app.go(Route.JellyfinSettings); NavResult.ACTIVATED } else NavResult.IGNORED
    }
    EmptyState(FuseIcons.Film, title, message = message, actionLabel = action, actionSelected = focused, onAction = { app.go(Route.JellyfinSettings) }, actionIcon = FuseIcons.Settings2)
}

@Composable
private fun ErrorState(app: AppState, focused: Boolean, message: String, retry: () -> Unit) {
    PageEffect(focused) { if (focused) { app.hero = null; app.hints = listOf(Hint(HintButton.CONFIRM, "Try again")) } }
    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        if (e.action == NavAction.SELECT) { retry(); NavResult.ACTIVATED } else NavResult.IGNORED
    }
    EmptyState(FuseIcons.WifiOff, "Jellyfin can't be reached", message = message, actionLabel = "Try again", actionSelected = focused, onAction = retry, actionIcon = FuseIcons.Refresh)
}

@Composable
private fun HomeSkeleton(topPadding: Dp) {
    Column(Modifier.fillMaxSize().padding(top = topPadding + subTabsRoom() + Space.m, start = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        repeat(3) { r ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Skeleton(Modifier.width(140.dp).height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    repeat(6) { Skeleton(Modifier.width(if (r == 0) 320.dp else 168.dp).height(if (r == 0) 180.dp else 252.dp)) }
                }
            }
        }
    }
}

/** Opens an item's page (an album or artist opens theirs; a song plays). */
internal fun AppState.openMedia(item: MediaItem) {
    when (item.type) {
        MediaType.SONG -> play(item)
        MediaType.LIBRARY, MediaType.FOLDER -> go(Route.MediaLibrary(item.id, item.name, item.library?.name))
        else -> go(Route.MediaPage(item.id))
    }
}

/** X on an item: play, from the start, go to the show, watched, favourite. */
internal fun AppState.mediaOptions(item: MediaItem) {
    val service = jellyfin ?: return
    fun close() = closeOverlays()
    openContextMenu(
        ContextMenuSpec(
            title = if (item.type == MediaType.EPISODE) item.seriesName ?: item.name else item.name,
            subtitle = if (item.type == MediaType.EPISODE) listOfNotNull(item.episodeLabel, item.name).joinToString("  ·  ") else item.year?.toString(),
            art = item.poster.at(POSTER_WIDTH),
            actions = listOfNotNull(
                MenuAction("play", if (item.resumeMs > 0) "Resume" else "Play", FuseIcons.Play, onSelect = { close(); play(item) }).takeIf { item.isPlayable || item.type == MediaType.SERIES || item.type == MediaType.SEASON },
                MenuAction("start", "Play from the start", FuseIcons.RotateCcw, onSelect = { close(); play(item, fromStart = true) }).takeIf { item.resumeMs > 0 },
                MenuAction("page", "Details", FuseIcons.Info, onSelect = { close(); go(Route.MediaPage(item.id)) }),
                item.seriesId?.let { sid -> MenuAction("series", "Go to ${item.seriesName ?: "the show"}", FuseIcons.Tv, onSelect = { close(); go(Route.MediaPage(sid)) }) },
                MenuAction("played", if (item.played) "Mark unwatched" else "Mark watched", if (item.played) FuseIcons.EyeOff else FuseIcons.Eye, onSelect = {
                    close()
                    scope.launch { runCatching { service.setPlayed(item.id, !item.played) }.onFailure { toasts.show(it.message ?: "Couldn't change that") } }
                }).takeIf { item.type != MediaType.ARTIST && item.type != MediaType.PERSON },
                MenuAction("favorite", if (item.favorite) "Remove from favourites" else "Add to favourites", if (item.favorite) FuseIcons.HeartOff else FuseIcons.Heart, onSelect = {
                    close()
                    scope.launch { runCatching { service.setFavorite(item.id, !item.favorite) }.onFailure { toasts.show(it.message ?: "Couldn't change that") } }
                }),
            ),
        ),
    )
}
