package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
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
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import kotlinx.coroutines.launch

/** The search page while it is open: what was asked, what came back, and where you are. */
internal class MediaSearchState {
    val sel = ShelfSelection()
    var query by mutableStateOf("")
    var results by mutableStateOf<Map<MediaType, List<MediaItem>>?>(null)
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** The keyboard opens by itself once, when the page first opens. */
    var asked = false
}

/** The groups results come in, in the order they're shown. */
private val GROUPS = listOf(
    MediaType.MOVIE to "Films", MediaType.SERIES to "Shows", MediaType.EPISODE to "Episodes",
    MediaType.COLLECTION to "Collections", MediaType.PERSON to "People", MediaType.ARTIST to "Artists",
    MediaType.ALBUM to "Albums", MediaType.SONG to "Songs",
)

private fun groupIcon(type: MediaType) = when (type) {
    MediaType.MOVIE -> FuseIcons.Film
    MediaType.SERIES, MediaType.EPISODE -> FuseIcons.Tv
    MediaType.COLLECTION -> FuseIcons.Layers
    MediaType.PERSON -> FuseIcons.Users
    MediaType.ARTIST -> FuseIcons.Mic
    MediaType.ALBUM -> FuseIcons.Disc
    else -> FuseIcons.Music
}

/**
 * Searching your Jellyfin server, and nothing else: films, shows, episodes, collections, people
 * and music, each in its own row. The field heads the page; A on it (or Y anywhere) types, on
 * screen or from a phone.
 */
@Composable
internal fun MediaSearchScreen(app: AppState) {
    val service = app.jellyfin ?: return
    val page = rememberRouteState(app.navigator, "jellyfin.search") { MediaSearchState() }
    val focused = app.focusZone == FocusZone.CONTENT

    fun run(term: String) {
        page.query = term
        if (term.isBlank()) {
            page.results = null
            return
        }
        page.searching = true
        app.scope.launch {
            try {
                page.results = service.search(term)
                page.error = null
                page.sel.row = if (page.results.orEmpty().values.any { it.isNotEmpty() }) 1 else 0
            } catch (e: Exception) {
                page.error = e.message ?: "The server can't be reached."
            } finally {
                page.searching = false
            }
        }
    }
    fun type() {
        app.textInput = TextInputSpec("Search Jellyfin", page.query, "Films, shows, people, music", doneLabel = "Search") { run(it.trim()) }
    }
    LaunchedEffect(Unit) {
        if (!page.asked && page.query.isEmpty()) {
            page.asked = true
            type()
        }
    }

    val groups = GROUPS.mapNotNull { (type, title) -> page.results?.get(type)?.takeIf { it.isNotEmpty() }?.let { Triple(type, title, it) } }
    val keys = listOf("field") + groups.map { it.first.name }
    fun sizeOf(key: String) = if (key == "field") 1 else groups.firstOrNull { it.first.name == key }?.third?.size ?: 0
    page.sel.clamp(keys, ::sizeOf)
    val rowKey = keys.getOrElse(page.sel.row) { "field" }
    val current = groups.firstOrNull { it.first.name == rowKey }?.third?.getOrNull(page.sel.column(rowKey))

    PageEffect(rowKey, current?.id, focused) {
        if (!focused) return@PageEffect
        app.hero = current?.hero()
        app.hints = if (current == null) listOf(Hint(HintButton.CONFIRM, "Type"), Hint(HintButton.BACK, "Back"))
        else listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "More"), Hint(HintButton.SEARCH, "Search again"))
    }
    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> page.sel.move(e.action, keys, ::sizeOf).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            NavAction.UP, NavAction.DOWN -> page.sel.move(e.action, keys, ::sizeOf)
            NavAction.SELECT -> { if (current == null) type() else app.openMedia(current); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { current?.takeIf { it.type != MediaType.PERSON }?.let { app.mediaOptions(it) }; NavResult.ACTIVATED }
            NavAction.SEARCH -> { type(); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val list = rememberLazyListState()
    FollowSelection(list, { page.sel.row }, anchor = 0.12f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val poster = if (compact) 120.dp else 152.dp
        val wide = if (compact) 232.dp else 296.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().fadingEdges(top = if (list.canScrollBackward) Space.xl else 0.dp),
            contentPadding = PaddingValues(top = Size.hudHeight + if (compact) Space.s else Space.l, bottom = Size.hintHeight + Space.xl),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.l else Space.xl),
        ) {
            item(key = "field") {
                SearchField(page.query, page.searching, focused && page.sel.row == 0, page.results?.values?.sumOf { it.size }) {
                    app.focusZone = FocusZone.CONTENT
                    page.sel.row = 0
                    type()
                }
            }
            for ((ri, g) in groups.withIndex()) {
                val (type, title, items) = g
                item(key = type.name) {
                    val row = ri + 1
                    val chosen = if (focused && page.sel.row == row) page.sel.column(type.name) else -1
                    fun tap(i: Int) {
                        app.focusZone = FocusZone.CONTENT
                        page.sel.row = row
                        page.sel.setColumn(type.name, i)
                        app.openMedia(items[i])
                    }
                    ShelfRow(title, groupIcon(type), items.size, chosen >= 0, page.sel.column(type.name)) {
                        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                            when (type) {
                                MediaType.EPISODE -> WideCard(item, i == chosen, wide, onClick = { tap(i) }, onLongClick = { app.mediaOptions(item) })
                                MediaType.PERSON -> PersonCard(item.name, null, item.poster.at(POSTER_WIDTH), i == chosen, if (compact) 96.dp else 112.dp) { tap(i) }
                                else -> PosterCard(item, i == chosen, poster, onClick = { tap(i) }, onLongClick = { app.mediaOptions(item) })
                            }
                        }
                    }
                }
            }
            val r = page.results
            when {
                page.error != null -> item(key = "error") {
                    EmptyState(FuseIcons.WifiOff, "Couldn't search", Modifier.fillMaxWidth().padding(Space.gutter), message = page.error)
                }
                r != null && groups.isEmpty() && !page.searching -> item(key = "none") {
                    EmptyState(FuseIcons.SearchX, "Nothing called \"${page.query}\"", Modifier.fillMaxWidth().padding(Space.gutter), message = "Try fewer words, or another spelling.")
                }
                r == null && !page.searching -> item(key = "hint") {
                    EmptyState(FuseIcons.Search, "Search your Jellyfin", Modifier.fillMaxWidth().padding(Space.gutter), message = "Films, shows, episodes, collections, people and music from your server. Your games aren't searched here.")
                }
            }
        }
    }
}

/** The search field: what was asked (or what can be), how many results, and a spinner while it looks. */
@Composable
private fun SearchField(query: String, searching: Boolean, selected: Boolean, count: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Row(
        Modifier.padding(horizontal = Space.gutter).widthIn(max = 720.dp).fillMaxWidth().height(56.dp)
            .background(Fuse.colors.surfaceRaised, shape)
            .border(2.dp, if (selected) Fuse.colors.focus else Fuse.colors.surfaceRaised, shape)
            .fuseClickable(shape = shape, onClick = onClick)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(FuseIcons.Search, size = 20.dp, tint = if (selected) Fuse.colors.text else Fuse.colors.textMuted)
        Spacer(Modifier.width(Space.m))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) FText("Search your Jellyfin", Fuse.type.body, color = Fuse.colors.textFaint, maxLines = 1)
            else FText(query, Fuse.type.bodyStrong, maxLines = 1)
        }
        when {
            searching -> Spinner(size = 20.dp, color = Fuse.colors.textMuted)
            count != null -> FText(if (count == 1) "1 result" else "$count results", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        }
    }
}
