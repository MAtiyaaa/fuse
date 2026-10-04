package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaSort
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import kotlinx.coroutines.launch

/** What an item's page holds while it is open. */
internal class MediaPageState {
    val sel = ShelfSelection()
    var item by mutableStateOf<MediaItem?>(null)
    var seasons by mutableStateOf<List<MediaItem>>(emptyList())
    var season by mutableStateOf(0)
    var episodes by mutableStateOf<List<MediaItem>>(emptyList())
    var children by mutableStateOf<List<MediaItem>>(emptyList())
    var nextUp by mutableStateOf<MediaItem?>(null)
    var error by mutableStateOf<String?>(null)
}

/** A focusable row on an item's page. */
private sealed interface PageRow {
    val key: String
    val size: Int

    class Buttons(val buttons: List<PageButton>) : PageRow {
        override val key = "buttons"
        override val size get() = buttons.size
    }

    class Seasons(val seasons: List<MediaItem>) : PageRow {
        override val key = "seasons"
        override val size get() = seasons.size
    }

    class Cards(override val key: String, val title: String, val items: List<MediaItem>, val wide: Boolean) : PageRow {
        override val size get() = items.size
    }

    class Track(val index: Int, val item: MediaItem) : PageRow {
        override val key = "t${item.id}"
        override val size = 1
    }

    class Cast(val item: MediaItem) : PageRow {
        override val key = "cast"
        override val size get() = item.cast.size
    }
}

private class PageButton(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val primary: Boolean = false, val run: () -> Unit)

/**
 * An item's page: its backdrop behind everything, its logo (or name), what it is, the story, and
 * what you can do; then a show's seasons and episodes, an album's tracks, a collection's titles, an
 * artist's albums, and who is in it.
 */
@Composable
internal fun MediaItemScreen(app: AppState, id: String) {
    val service = app.jellyfin ?: return
    val page = rememberRouteState(app.navigator, "media.$id") { MediaPageState() }
    val focused = app.focusZone == FocusZone.CONTENT
    val scope = app.scope

    suspend fun refresh() {
        try {
            val item = service.item(id)
            page.item = item
            page.error = null
            when (item.type) {
                MediaType.SERIES -> {
                    page.seasons = service.seasons(item.id)
                    page.nextUp = runCatching { service.nextUpFor(item.id) }.getOrNull()
                    // Opens on the season of the next episode to watch.
                    val target = page.nextUp?.seasonId?.let { sid -> page.seasons.indexOfFirst { it.id == sid } }?.takeIf { it >= 0 } ?: 0
                    if (page.episodes.isEmpty()) page.season = target
                    page.seasons.getOrNull(page.season)?.let { page.episodes = service.episodes(item.id, it.id) }
                }
                MediaType.SEASON -> page.episodes = service.episodes(item.seriesId ?: item.parentId ?: item.id, item.id)
                MediaType.ALBUM -> page.children = service.albumTracks(item.id)
                MediaType.ARTIST -> page.children = service.albumsBy(item.id)
                MediaType.COLLECTION, MediaType.PLAYLIST, MediaType.FOLDER -> page.children = service.children(item.id).items
                MediaType.PERSON -> page.children = runCatching {
                    service.call { b, a -> service.client.query(b, a, types = "Movie,Series", sort = MediaSort.RELEASED, extra = mapOf("personIds" to item.id), limit = 60).items }
                }.getOrDefault(emptyList())
                MediaType.EPISODE -> item.seriesId?.let { s -> page.episodes = service.episodes(s, item.seasonId) }
                else -> Unit
            }
        } catch (e: Exception) {
            page.error = e.message ?: "The server can't be reached."
        }
    }
    // Again whenever something was played or marked, so resume points and ticks are current.
    val revision by service.revision.collectAsState()
    LaunchedEffect(id, revision) { refresh() }

    val item = page.item
    if (item == null) {
        if (page.error != null) {
            PageEffect(focused) { if (focused) app.hints = listOf(Hint(HintButton.CONFIRM, "Try again"), Hint(HintButton.BACK, "Back")) }
            InputLayer(enabled = focused && !app.overlayOpen) { e -> if (e.action == NavAction.SELECT) { scope.launch { refresh() }; NavResult.ACTIVATED } else NavResult.IGNORED }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(FuseIcons.WifiOff, "This can't open right now", message = page.error, actionLabel = "Try again", actionSelected = focused, onAction = { scope.launch { refresh() } })
            }
        } else {
            PageSkeleton()
        }
        return
    }

    fun toggle(played: Boolean? = null, favorite: Boolean? = null) {
        scope.launch {
            runCatching {
                played?.let { service.setPlayed(item.id, it) }
                favorite?.let { service.setFavorite(item.id, it) }
            }.onFailure { app.toasts.show(it.message ?: "Couldn't change that") }
            refresh()
        }
    }

    val buttons = buildList {
        val target = if (item.type == MediaType.SERIES) page.nextUp else null
        when (item.type) {
            MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO -> {
                add(PageButton(if (item.resumeMs > 0) "Resume" else "Play", FuseIcons.Play, primary = true) { app.play(item) })
                if (item.resumeMs > 0) add(PageButton("From the start", FuseIcons.RotateCcw) { app.play(item, fromStart = true) })
            }
            MediaType.SERIES, MediaType.SEASON -> add(PageButton(target?.episodeLabel?.let { "Play $it" } ?: "Play", FuseIcons.Play, primary = true) { app.play(target ?: item) })
            MediaType.ALBUM -> {
                add(PageButton("Play", FuseIcons.Play, primary = true) { page.children.firstOrNull()?.let { app.play(it, queue = page.children) } })
                add(PageButton("Shuffle", FuseIcons.Shuffle) { page.children.shuffled().let { q -> q.firstOrNull()?.let { app.play(it, queue = q) } } })
            }
            else -> Unit
        }
        if (item.type != MediaType.PERSON && item.type != MediaType.ARTIST) {
            add(PageButton(if (item.played) "Watched" else "Mark watched", if (item.played) FuseIcons.CircleCheck else FuseIcons.Check) { toggle(played = !item.played) })
        }
        add(PageButton(if (item.favorite) "Favourite" else "Favourite", if (item.favorite) FuseIcons.HeartFilled else FuseIcons.Heart) { toggle(favorite = !item.favorite) })
        item.seriesId?.takeIf { item.type == MediaType.EPISODE || item.type == MediaType.SEASON }?.let { sid ->
            add(PageButton("Go to the show", FuseIcons.Tv) { app.go(Route.MediaPage(sid)) })
        }
    }

    val rows: List<PageRow> = buildList {
        add(PageRow.Buttons(buttons))
        when (item.type) {
            MediaType.SERIES -> {
                if (page.seasons.size > 1) add(PageRow.Seasons(page.seasons))
                if (page.episodes.isNotEmpty()) add(PageRow.Cards("episodes", page.seasons.getOrNull(page.season)?.name ?: "Episodes", page.episodes, wide = true))
            }
            MediaType.SEASON -> if (page.episodes.isNotEmpty()) add(PageRow.Cards("episodes", "Episodes", page.episodes, wide = true))
            MediaType.EPISODE -> page.episodes.filter { it.id != item.id }.takeIf { it.isNotEmpty() }?.let { add(PageRow.Cards("episodes", "More from ${item.seasonName ?: "this season"}", it, wide = true)) }
            MediaType.ALBUM -> page.children.forEachIndexed { i, t -> add(PageRow.Track(i, t)) }
            MediaType.ARTIST -> if (page.children.isNotEmpty()) add(PageRow.Cards("albums", "Albums", page.children, wide = false))
            MediaType.PERSON -> if (page.children.isNotEmpty()) add(PageRow.Cards("credits", "Films and shows", page.children, wide = false))
            MediaType.COLLECTION, MediaType.PLAYLIST, MediaType.FOLDER -> if (page.children.isNotEmpty()) add(PageRow.Cards("children", "In this collection", page.children, wide = false))
            else -> Unit
        }
        if (item.cast.isNotEmpty()) add(PageRow.Cast(item))
    }
    val sel = page.sel
    val keys = rows.map { it.key }
    fun sizeOf(key: String) = rows.firstOrNull { it.key == key }?.size ?: 0
    sel.clamp(keys, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: rows.first()
    val col = sel.column(row.key)
    val currentCard = (row as? PageRow.Cards)?.items?.getOrNull(col) ?: (row as? PageRow.Track)?.item

    fun chooseSeason(i: Int) {
        if (i == page.season) return
        page.season = i
        scope.launch {
            val s = page.seasons.getOrNull(i) ?: return@launch
            page.episodes = runCatching { service.episodes(item.id, s.id) }.getOrDefault(emptyList())
            sel.setColumn("episodes", 0)
        }
    }
    fun activate(r: PageRow, i: Int) {
        when (r) {
            is PageRow.Buttons -> r.buttons.getOrNull(i)?.run?.invoke()
            is PageRow.Seasons -> chooseSeason(i)
            is PageRow.Cards -> r.items.getOrNull(i)?.let { c -> if (c.type == MediaType.EPISODE) app.play(c) else app.openMedia(c) }
            is PageRow.Track -> app.play(r.item, queue = page.children.drop(r.index))
            is PageRow.Cast -> r.item.cast.getOrNull(i)?.id?.let { app.go(Route.MediaPage(it)) }
        }
    }

    PageEffect(item.id, row.key, col, focused) {
        if (!focused) return@PageEffect
        app.hero = item.hero()
        app.hints = when (row) {
            is PageRow.Cards -> listOf(Hint(HintButton.CONFIRM, if (currentCard?.type == MediaType.EPISODE) "Play" else "Open"), Hint(HintButton.OPTIONS, "More"), Hint(HintButton.BACK, "Back"))
            is PageRow.Track -> listOf(Hint(HintButton.CONFIRM, "Play from here"), Hint(HintButton.OPTIONS, "More"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
        }
    }

    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> {
                val r = sel.move(e.action, keys, ::sizeOf)
                // Moving along the seasons shows that season's episodes.
                if (row is PageRow.Seasons) chooseSeason(sel.column(row.key))
                if (r == NavResult.IGNORED) NavResult.BLOCKED else r
            }
            NavAction.UP, NavAction.DOWN -> sel.move(e.action, keys, ::sizeOf)
            NavAction.SELECT -> { activate(row, col); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { (currentCard ?: item.takeIf { row is PageRow.Buttons })?.let { app.mediaOptions(it) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val list = rememberLazyListState()
    FollowSelection(list, { sel.row }, anchor = 0.3f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val narrow = maxWidth < 700.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().fadingEdges(top = if (list.canScrollBackward) Space.xl else 0.dp),
            contentPadding = PaddingValues(top = Size.hudHeight + if (compact) Space.l else maxHeight * 0.16f, bottom = Size.hintHeight + Space.xl),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.l else Space.xl),
        ) {
            item(key = "head") { Header(item, compact, narrow, buttons, if (focused && sel.row == 0) col else -1) { i -> sel.row = 0; sel.setColumn("buttons", i); activate(rows[0], i) } }
            rows.withIndex().filter { it.value !is PageRow.Buttons }.forEach { (ri, r) ->
                item(key = r.key) {
                    val chosen = if (focused && sel.row == ri) sel.column(r.key) else -1
                    fun tap(i: Int) {
                        app.focusZone = FocusZone.CONTENT
                        sel.row = ri
                        sel.setColumn(r.key, i)
                        activate(r, i)
                    }
                    when (r) {
                        is PageRow.Seasons -> SeasonChips(r.seasons, page.season, chosen, ::tap)
                        is PageRow.Cards -> ShelfRow(r.title, null, r.items.size, chosen >= 0, sel.column(r.key)) {
                            itemsIndexed(r.items, key = { _, it -> it.id }) { i, c ->
                                if (r.wide) {
                                    EpisodeCard(c, i == chosen, if (compact) 248.dp else 300.dp, onClick = { tap(i) }, onLongClick = { app.mediaOptions(c) })
                                } else {
                                    PosterCard(c, i == chosen, if (compact) 132.dp else 160.dp, onClick = { tap(i) }, onLongClick = { app.mediaOptions(c) })
                                }
                            }
                        }
                        is PageRow.Track -> TrackRow(r.index, r.item, chosen == 0) { tap(0) }
                        is PageRow.Cast -> ShelfRow("Cast", FuseIcons.Users, r.item.cast.size, chosen >= 0, sel.column(r.key)) {
                            itemsIndexed(r.item.cast, key = { i, p -> "${p.id}-$i" }) { i, p ->
                                PersonCard(p.name, p.role, p.photo.at(POSTER_WIDTH), i == chosen, if (compact) 96.dp else 112.dp) { tap(i) }
                            }
                        }
                        is PageRow.Buttons -> Unit
                    }
                }
            }
        }
    }
}

/** The top of the page: logo or name, facts, tagline, the story, and the buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(item: MediaItem, compact: Boolean, narrow: Boolean, buttons: List<PageButton>, chosen: Int, onClick: (Int) -> Unit) {
    val c = Fuse.colors
    Column(Modifier.padding(horizontal = Space.gutter).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m)) {
        val logo = item.logo
        if (logo != null) {
            Artwork(logo.at(800), Modifier.height(if (compact) 72.dp else 110.dp).widthIn(max = 420.dp).fillMaxWidth(), contentScale = ContentScale.Fit, backdrop = true, fallback = {
                FText(item.name, Fuse.type.display, maxLines = 2)
            })
        } else {
            FText(if (item.type == MediaType.EPISODE) item.seriesName ?: item.name else item.name, if (narrow) Fuse.type.title else Fuse.type.display, maxLines = 2)
        }
        if (item.type == MediaType.EPISODE) {
            FText(listOfNotNull(item.episodeLabel, item.name).joinToString("  ·  "), Fuse.type.titleSmall, maxLines = 1)
        }
        Facts(item)
        item.tagline?.let { FText(it, Fuse.type.bodyStrong, color = c.text.copy(alpha = 0.9f), maxLines = 1) }
        item.overview?.let { FText(it, Fuse.type.body, color = c.text.copy(alpha = 0.82f), maxLines = if (compact) 3 else 4) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.xs)) {
            buttons.forEachIndexed { i, b ->
                FuseButton(b.label, selected = i == chosen, onClick = { onClick(i) }, icon = b.icon, kind = if (b.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY, height = if (compact) 40.dp else 48.dp)
            }
        }
    }
}

/** Year, rating, length, age rating, picture and sound, and genres, on one quiet line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(item: MediaItem) {
    val c = Fuse.colors
    val parts = listOfNotNull(
        item.year?.let { y -> if (item.type == MediaType.SERIES && item.endYear != null && item.endYear != y) "$y to ${item.endYear}" else "$y" },
        item.runtimeMs?.takeIf { item.type != MediaType.SERIES }?.let { minutes(it) },
        item.officialRating,
        item.videoFacts,
        item.audioFacts,
        item.status?.takeIf { item.type == MediaType.SERIES && it == "Continuing" }?.let { "Still airing" },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
        item.rating?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.Star, size = 15.dp, tint = Color(0xFFFFC857))
                Spacer(Modifier.width(Space.xs))
                FText(((r * 10).toInt() / 10.0).toString(), Fuse.type.label.tabular(), maxLines = 1)
            }
        }
        parts.forEach { FText(it, Fuse.type.label, color = c.text.copy(alpha = 0.86f), maxLines = 1) }
        if (item.genres.isNotEmpty()) FText(item.genres.take(3).joinToString(", "), Fuse.type.label, color = c.textMuted, maxLines = 1)
        item.leftMs?.takeIf { item.resumeMs > 0 }?.let { FText("${minutes(it)} left", Fuse.type.label, color = c.accent, maxLines = 1) }
    }
}

@Composable
private fun SeasonChips(seasons: List<MediaItem>, shown: Int, chosen: Int, onClick: (Int) -> Unit) {
    val c = Fuse.colors
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.id }) { i, s ->
            val on = i == shown
            val focus = i == chosen
            Row(
                Modifier
                    .height(40.dp)
                    .background(
                        when {
                            focus -> c.text
                            on -> c.accentSoft
                            else -> c.text.copy(alpha = 0.08f)
                        },
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { onClick(i) }
                    .padding(horizontal = Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FText(s.name, Fuse.type.label, color = if (focus) c.ink else if (on) c.accent else c.text, maxLines = 1)
                s.unplayed?.takeIf { it > 0 }?.let {
                    Spacer(Modifier.width(Space.s))
                    FText(it.toString(), Fuse.type.caption.tabular(), color = if (focus) c.ink.copy(alpha = 0.7f) else c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** An episode: its still with how far in, its number and name, and a line of its story. */
@Composable
private fun EpisodeCard(item: MediaItem, selected: Boolean, width: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        WideCardArt(item, selected, onClick, onLongClick)
        FText(listOfNotNull(item.episode?.let { "$it." }, item.name).joinToString(" "), Fuse.type.label, maxLines = 1)
        val line = listOfNotNull(item.runtimeMs?.let { minutes(it) }, item.leftMs?.takeIf { item.resumeMs > 0 }?.let { "${minutes(it)} left" }).joinToString("  ·  ")
        if (line.isNotEmpty()) FText(line, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        item.overview?.let { FText(it, Fuse.type.caption, color = Fuse.colors.text.copy(alpha = 0.72f), maxLines = 2) }
    }
}

@Composable
private fun WideCardArt(item: MediaItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    io.github.matiyaaa.fuse.ui.designsystem.components.Tile(
        selected = selected,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        glow = accentOf(item.name), onClick = onClick, onLongClick = onLongClick,
    ) {
        Artwork(item.thumb.at(WIDE_WIDTH) ?: item.poster.at(WIDE_WIDTH), Modifier.fillMaxSize(), fallback = {
            io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt(item.name, accentOf(item.name), Modifier.fillMaxSize(), slot = io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot.WIDE)
        })
        if (item.played) {
            Box(Modifier.align(Alignment.TopEnd).padding(Space.s).size(24.dp).background(Fuse.colors.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Check, size = 14.dp, tint = Fuse.colors.onAccent)
            }
        }
        item.progress?.let { p ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                Box(Modifier.fillMaxWidth(p).height(4.dp).background(Fuse.colors.accent))
            }
        }
    }
}

/** A song on an album: its number, name, artists when they differ, and length. */
@Composable
private fun TrackRow(index: Int, item: MediaItem, selected: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(
        Modifier
            .padding(horizontal = Space.gutter)
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .background(if (selected) c.surfaceRaised else Color.Transparent, RoundedCornerShape(Fuse.geometry.control))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FText((item.episode ?: index + 1).toString(), Fuse.type.label.tabular(), color = if (selected) c.accent else c.textMuted, maxLines = 1, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            FText(item.name, Fuse.type.bodyStrong, maxLines = 1)
            item.artists.takeIf { it.isNotEmpty() }?.let { FText(it.joinToString(", "), Fuse.type.caption, color = c.textMuted, maxLines = 1) }
        }
        item.runtimeMs?.let { FText(trackTime(it), Fuse.type.label.tabular(), color = c.textMuted, maxLines = 1) }
    }
}

@Composable
private fun PageSkeleton() {
    Column(Modifier.fillMaxSize().padding(start = Space.gutter, top = Size.hudHeight + 120.dp), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Skeleton(Modifier.width(320.dp).height(72.dp))
        Skeleton(Modifier.width(260.dp).height(16.dp))
        SkeletonText(lines = 3, style = Fuse.type.body, modifier = Modifier.widthIn(max = 640.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { repeat(3) { Skeleton(Modifier.width(140.dp).height(48.dp)) } }
    }
}

/** 3:42 for a song. */
internal fun trackTime(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
