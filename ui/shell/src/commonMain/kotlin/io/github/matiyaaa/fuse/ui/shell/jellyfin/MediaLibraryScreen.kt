package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
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
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll

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

    // A page that doesn't come is asked for again by itself a couple of times before the grid says
    // so (see QUIET_RETRIES_MS); one called off (the sort changed, the page closed) never failed.
    suspend fun loadMore(reset: Boolean = false) {
        if (page.loading) return
        if (!reset && page.loaded && page.items.size >= page.total) return
        page.loading = true
        try {
            val start = if (reset) 0 else page.items.size
            var attempt = 0
            while (true) {
                try {
                    val p = service.page(id, types, page.sort, page.filter, start)
                    page.items = if (reset) p.items else page.items + p.items
                    page.total = p.total
                    page.loaded = true
                    page.error = null
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val wait = QUIET_RETRIES_MS.getOrNull(attempt++)
                    if (wait == null) {
                        page.error = e.message ?: "The server can't be reached."
                        break
                    }
                    kotlinx.coroutines.delay(wait)
                }
            }
        } finally {
            page.loading = false
        }
    }
    LaunchedEffect(id, page.sort, page.filter) { if (!page.loaded || page.items.isEmpty()) loadMore(reset = true) }
    // Played or marked since: what is loaded is asked again (ticks, how far in), keeping the place.
    val revision by service.revision.collectAsState()
    var seenRevision by remember { mutableStateOf(revision) }
    LaunchedEffect(revision) {
        if (revision == seenRevision || !page.loaded) return@LaunchedEffect
        seenRevision = revision
        val keep = page.items.size
        runCatching {
            var fresh = emptyList<MediaItem>()
            while (fresh.size < keep) {
                val p = service.page(id, types, page.sort, page.filter, fresh.size)
                if (p.items.isEmpty()) break
                fresh = fresh + p.items
                page.total = p.total
            }
            page.items = fresh
        }
    }

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
        // The chosen title told whole above the grid, as the Library tells a game: moving through the
        // posters shows each one's name, facts and story without opening it. A phone held upright
        // gives its height to the posters.
        val staged = maxWidth >= 600.dp && maxHeight >= 420.dp
        // Posters sized to the screen both ways: three across a phone held upright, and on a short
        // screen (a handheld's upper screen at its largest display size) small enough that a row and
        // the start of the next show under the stage, never one row filling the page.
        val narrow = maxWidth < NARROW_GRID
        val gap = if (narrow) Space.m else Space.l
        val gutter = if (narrow) Space.gutterCompact else Space.gutter
        val view = maxHeight - Size.hudHeight - if (staged) (if (compact) STAGE_COMPACT else STAGE_TALL) + Space.s * 2 else 0.dp
        val byHeight = (view / ROWS_IN_VIEW - POSTER_WORDS - gap) / POSTER_ASPECT
        val byWidth = if (narrow) (maxWidth - gutter * 2 - gap * 2) / 3 else if (compact) 128.dp else 160.dp
        val target = minOf(byWidth, byHeight).coerceAtLeast(MIN_POSTER)
        // As many as fit at that size, then the posters widened to fill the row exactly.
        val columns = kotlin.math.ceil((maxWidth - gutter * 2 + gap) / (target + gap) - 0.05f).toInt().coerceAtLeast(2)
        val cell = (maxWidth - gutter * 2 - gap * (columns - 1)) / columns
        val current = page.items.getOrNull(page.index)
        if (!page.top) PrefetchMediaArt(page.items, page.index)

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
        // The chosen row glides to a steady place as you move (held or pushed once), like the Library.
        val fade = with(LocalDensity.current) { Space.xl.roundToPx() }
        FollowSelection(grid, { if (page.top) 0 else page.index + 1 }, anchor = 0.12f, insetPx = fade, relayout = columns)
        Column(Modifier.fillMaxSize().padding(top = Size.hudHeight)) {
        if (staged) MediaStage(current.takeIf { !page.top && focused }, compact, Modifier.padding(horizontal = gutter, vertical = Space.s))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            // Below the top line, so the grid never scrolls under it.
            modifier = Modifier.weight(1f).fillMaxWidth().fadingEdges(top = if (grid.canScrollBackward) Space.xl else 0.dp).dragScroll(grid),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.l, bottom = Size.hintHeight + Space.xl),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
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
}

/**
 * The chosen title above a library's grid: its logo (or name), a line of facts (year, length, age
 * rating, the server's score, genres) and the start of its story. On a short screen it is one line,
 * the name and its facts, so every poster below still shows whole with its title. It keeps one
 * height whatever it shows, so the grid under it never moves; with nothing chosen it holds its place.
 */
@Composable
private fun MediaStage(item: MediaItem?, compact: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    val height = if (compact) STAGE_COMPACT else STAGE_TALL
    io.github.matiyaaa.fuse.ui.fuseline.Crossfade(item, modifier.fillMaxWidth().height(height), animationSpec = Fuse.motion.fade(), label = "mediaStage") { it ->
        val m = it ?: return@Crossfade
        val facts = stageFacts(m)
        val rating: @Composable () -> Unit = { StageRating(m) }
        if (compact) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                FText(m.name, Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                rating()
                if (facts.isNotEmpty()) FText(facts, Fuse.type.label, color = c.text.copy(alpha = 0.86f), maxLines = 1)
            }
            return@Crossfade
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.xs, Alignment.Bottom)) {
            val name: @Composable () -> Unit = { FText(m.name, Fuse.type.title, maxLines = 1) }
            val logo = m.logo
            if (logo != null) {
                io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(
                    logo.at(640), Modifier.height(64.dp).fillMaxWidth(0.4f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit, focusX = 0f, focusY = 1f, fallback = name,
                )
            } else {
                name()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                rating()
                FText(facts, Fuse.type.label, color = c.text.copy(alpha = 0.86f), maxLines = 1)
            }
            m.overview?.let { FText(it, Fuse.type.body, color = c.textMuted, maxLines = 2, modifier = Modifier.widthIn(max = 760.dp)) }
        }
    }
}

/** A title's facts in one line: its years, its length, its age rating, two genres, and what is left of it. */
internal fun stageFacts(m: MediaItem): String = listOfNotNull(
    m.year?.let { y -> if (m.endYear != null && m.endYear != y) "$y to ${m.endYear}" else "$y" },
    m.runtimeMs?.let { minutes(it) },
    m.officialRating,
    m.genres.take(2).joinToString(", ").ifEmpty { null },
    m.leftMs?.takeIf { m.resumeMs > 0 }?.let { "${minutes(it)} left" },
).joinToString("  \u00B7  ")

/** The server's score for a title, after a gold star; nothing where it has none. */
@Composable
internal fun StageRating(m: MediaItem) {
    val r = m.rating ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        FuseIcon(FuseIcons.Star, size = 14.dp, tint = androidx.compose.ui.graphics.Color(0xFFFFC857))
        FText(((r * 10).toInt() / 10.0).toString(), Fuse.type.label.tabular(), maxLines = 1)
    }
}

private val STAGE_TALL = 156.dp
private val STAGE_COMPACT = 36.dp

/** Narrower than this (a phone held upright), the grid is three posters across with tighter edges. */
private val NARROW_GRID = 480.dp

/** How many rows of posters the grid's height is sized for: a row and the start of the next, at least. */
private const val ROWS_IN_VIEW = 1.6f

/** A poster's height for its width (2:3), and the room its name and line take under it. */
private const val POSTER_ASPECT = 1.5f
private val POSTER_WORDS = 50.dp

/** The smallest a poster gets, so its art and name still read. */
private val MIN_POSTER = 80.dp
