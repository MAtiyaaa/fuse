package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.LibraryKind
import io.github.matiyaaa.fuse.jellyfin.MediaFilter
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaSort
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import kotlinx.coroutines.launch

/** A library's grid while it is open: what is loaded, the order, the filter and where you are. */
internal class LibraryPageState {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
    var total by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var sort by mutableStateOf(MediaSort.NAME)
    var filter by mutableStateOf(MediaFilter.ALL)
    /** -1 is the row of buttons above the grid. */
    var index by mutableStateOf(0)
    var top by mutableStateOf(false)
}

/**
 * A library as a grid of posters, loaded a page at a time as you go: sorted A to Z, by when it was
 * added or released, or by rating; all of it, what you haven't watched, or your favourites.
 */
@Composable
internal fun MediaLibraryScreen(app: AppState, id: String, name: String, kind: String?) {
    val service = app.jellyfin ?: return
    val page = rememberRouteState(app.navigator, "library.$id") { LibraryPageState() }
    val focused = app.focusZone == FocusZone.CONTENT
    val library = kind?.let { runCatching { LibraryKind.valueOf(it) }.getOrNull() }
    val types = when (library) {
        LibraryKind.MOVIES -> "Movie"
        LibraryKind.SHOWS -> "Series"
        LibraryKind.MUSIC -> "MusicAlbum"
        LibraryKind.COLLECTIONS -> "BoxSet"
        else -> null
    }

    suspend fun loadMore(reset: Boolean = false) {
        if (page.loading) return
        if (!reset && page.loaded && page.items.size >= page.total) return
        page.loading = true
        try {
            val start = if (reset) 0 else page.items.size
            val p = service.page(id, types, page.sort, page.filter, start)
            page.items = if (reset) p.items else page.items + p.items
            page.total = p.total
            page.loaded = true
            page.error = null
        } catch (e: Exception) {
            page.error = e.message ?: "The server can't be reached."
        } finally {
            page.loading = false
        }
    }
    LaunchedEffect(id, page.sort, page.filter) { if (!page.loaded || page.items.isEmpty()) loadMore(reset = true) }

    val buttons = listOf(
        Triple("Sort: ${page.sort.label}", FuseIcons.Sort) {
            app.choice = ChoiceSpec(
                title = "Sort by",
                icon = FuseIcons.Sort,
                options = MediaSort.entries.filter { it != MediaSort.PLAYED }.map { s ->
                    MenuAction(s.name, s.label, trailing = Trailing.Check(s == page.sort), onSelect = {
                        app.choice = null
                        page.sort = s
                        page.loaded = false
                        page.index = 0
                        app.scope.launch { loadMore(reset = true) }
                    })
                },
            )
        },
        Triple(page.filter.label, FuseIcons.Filter) {
            app.choice = ChoiceSpec(
                title = "Show",
                icon = FuseIcons.Filter,
                options = MediaFilter.entries.map { f ->
                    MenuAction(f.name, f.label, trailing = Trailing.Check(f == page.filter), onSelect = {
                        app.choice = null
                        page.filter = f
                        page.loaded = false
                        page.index = 0
                        app.scope.launch { loadMore(reset = true) }
                    })
                },
            )
        },
    )
    var buttonIndex by remember { mutableStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val cell = if (compact) 128.dp else 160.dp
        val columns = ((maxWidth - Space.gutter * 2 + Space.l) / (cell + Space.l)).toInt().coerceAtLeast(2)
        val current = page.items.getOrNull(page.index)

        PageEffect(current?.id, page.top, focused) {
            if (!focused) return@PageEffect
            app.hero = if (page.top) null else current?.hero()
            app.hints = if (page.top) listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back")) else listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "More"), Hint(HintButton.BACK, "Back"))
        }
        // The next page loads as you near the end of what is loaded.
        LaunchedEffect(page.index, page.items.size) {
            if (page.items.isNotEmpty() && page.index >= page.items.size - columns * 3) loadMore()
        }

        InputLayer(enabled = focused && !app.overlayOpen, repeats = setOf(NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT)) { e ->
            if (page.top) {
                when (e.action) {
                    NavAction.LEFT -> { buttonIndex = (buttonIndex - 1).coerceAtLeast(0); NavResult.MOVED }
                    NavAction.RIGHT -> { buttonIndex = (buttonIndex + 1).coerceAtMost(buttons.lastIndex); NavResult.MOVED }
                    NavAction.DOWN -> { if (page.items.isNotEmpty()) page.top = false; NavResult.MOVED }
                    NavAction.SELECT -> { buttons[buttonIndex].third(); NavResult.ACTIVATED }
                    else -> NavResult.IGNORED
                }
            } else {
                val n = page.items.size
                when (e.action) {
                    NavAction.LEFT -> if (page.index % columns > 0) { page.index--; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.RIGHT -> if (page.index % columns < columns - 1 && page.index + 1 < n) { page.index++; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.UP -> if (page.index >= columns) { page.index -= columns; NavResult.MOVED } else { page.top = true; NavResult.MOVED }
                    NavAction.DOWN -> if (page.index + columns < n) { page.index += columns; NavResult.MOVED } else if (page.index / columns < (n - 1) / columns) { page.index = n - 1; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.PAGE_DOWN -> { page.index = (page.index + columns * 3).coerceAtMost(n - 1); NavResult.MOVED }
                    NavAction.PAGE_UP -> { page.index = (page.index - columns * 3).coerceAtLeast(0); NavResult.MOVED }
                    NavAction.SELECT -> { current?.let { app.openMedia(it) }; NavResult.ACTIVATED }
                    NavAction.CONTEXT -> { current?.let { app.mediaOptions(it) }; NavResult.ACTIVATED }
                    else -> NavResult.IGNORED
                }
            }
        }

        val grid = rememberLazyGridState()
        LaunchedEffect(page.index, page.top) {
            if (!page.top) {
                val target = page.index + 1 // the header is item 0
                val visible = grid.layoutInfo.visibleItemsInfo
                if (visible.none { it.index == target } || visible.lastOrNull()?.index == target) grid.animateScrollToItem(target, -grid.layoutInfo.viewportSize.height / 4)
            } else {
                grid.animateScrollToItem(0)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            // Below the top line, so the grid never scrolls under it.
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight).fadingEdges(top = if (grid.canScrollBackward) Space.xl else 0.dp),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.xl),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m), modifier = Modifier.padding(bottom = Space.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FText(name, Fuse.type.title, maxLines = 1)
                        Spacer(Modifier.width(Space.m))
                        if (page.total > 0) FText(page.total.toString(), Fuse.type.label.tabular(), color = Fuse.colors.textMuted, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        buttons.forEachIndexed { i, (label, icon, run) ->
                            FuseButton(label, selected = focused && page.top && i == buttonIndex, onClick = { buttonIndex = i; run() }, icon = icon, height = 40.dp)
                        }
                    }
                }
            }
            itemsIndexed(page.items, key = { _, it -> it.id }) { i, item ->
                PosterCard(item, focused && !page.top && i == page.index, cell, onClick = {
                    app.focusZone = FocusZone.CONTENT
                    page.top = false
                    page.index = i
                    app.openMedia(item)
                }, onLongClick = { app.mediaOptions(item) })
            }
            if (!page.loaded && page.error == null) {
                items(columns * 2) { Skeleton(Modifier.width(cell).height(cell * 1.5f)) }
            }
            if (page.loaded && page.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(FuseIcons.Filter, if (page.filter == MediaFilter.ALL) "Nothing here yet" else "Nothing matches", message = if (page.filter == MediaFilter.ALL) "This library is empty on the server." else "Show everything to see the rest.")
                }
            }
            page.error?.let { err ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.m)) {
                        FuseIcon(FuseIcons.WifiOff, size = 18.dp, tint = Fuse.colors.textMuted)
                        Spacer(Modifier.width(Space.s))
                        FText(err, Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 2)
                    }
                }
            }
        }
    }
}
