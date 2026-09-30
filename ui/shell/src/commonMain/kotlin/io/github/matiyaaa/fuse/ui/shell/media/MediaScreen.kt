package io.github.matiyaaa.fuse.ui.shell.media

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.relocation.bringIntoViewRequester

import androidx.compose.foundation.relocation.BringIntoViewRequester

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.store.ArtworkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val slots = listOf(
    MediaKind.ICON to "Icon",
    MediaKind.BOXART to "Cover",
    MediaKind.GRID to "Wide capsule",
    MediaKind.HERO to "Background",
    MediaKind.LOGO to "Logo",
    MediaKind.SCREENSHOT to "Screenshots",
    MediaKind.VIDEO to "Video",
)

private sealed interface Browser {
    data object Closed : Browser
    data object Loading : Browser
    data class Options(val kind: MediaKind, val options: List<ArtworkOption>) : Browser
    data class Message(val text: String) : Browser
}

/**
 * Manage Media for a game, system, collection or app. Each slot shows what's used now and where it
 * came from. Picking art from a source or a file saves it as yours; art you chose is never replaced
 * by automatic filling, only by another explicit choice or Reset.
 */
@Composable
fun MediaScreen(app: AppState, owner: MediaOwner, title: String) {
    val flow = remember(owner) { app.store.media.media(owner) }
    val media by flow.collectAsState(initial = MediaSet.Empty)
    val sel = remember { LinearSelection() }
    var browser by remember { mutableStateOf<Browser>(Browser.Closed) }
    val grid = remember { GridSelection() }
    var adjusting by remember { mutableStateOf<MediaKind?>(null) }
    var fx by remember { mutableFloatStateOf(0.5f) }
    var fy by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val kind = slots[sel.index].first

    fun browse(k: MediaKind) {
        browser = Browser.Loading
        grid.index = 0
        app.scope.launch {
            val result = try {
                app.store.media.artworkOptions(owner, k)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (a class that failed to load), so the spinner always gives way to a message.
                ArtworkResult.Unavailable("Fuse couldn't search for art (${e::class.simpleName}). Try again, or check your keys in Settings, Media and Scraping.")
            }
            browser = when (val r = result) {
                is ArtworkResult.Options -> if (r.options.isEmpty()) Browser.Message("No ${slotName(k).lowercase()} found. Try another source in Media and Scraping settings.") else Browser.Options(k, r.options)
                is ArtworkResult.NeedsMatch -> {
                    app.choice = ChoiceSpec(
                        title = "Which game is this?",
                        message = "Fuse found several close matches. Pick the right one and art will be searched for it.",
                        // Ids include the provider and position: two providers can share a game number.
                        options = r.candidates.mapIndexed { i, c ->
                            MenuAction("c.${c.provider}.${c.providerGameId}.$i", c.title, FuseIcons.Target, detail = listOfNotNull(c.platformName, c.year?.toString(), "${(c.confidence * 100).toInt()}% match").joinToString("  ·  "), onSelect = {
                                app.choice = null
                                val game = (owner as? MediaOwner.OfGame)?.id
                                if (game != null) app.scope.launch { app.store.media.acceptCandidate(game, c); browse(k) }
                            })
                        },
                    )
                    Browser.Closed
                }
                is ArtworkResult.Unavailable -> Browser.Message(r.reason)
            }
        }
    }

    fun slotActions(k: MediaKind): List<MenuAction> = buildList {
        add(MenuAction("browse", "Find ${slotName(k).lowercase()}", FuseIcons.Search, onSelect = { app.choice = null; browse(k) }))
        add(MenuAction("file", "Choose a file", FuseIcons.Folder, onSelect = {
            app.choice = null
            app.scope.launch {
                val path = app.platform.storage.pickImage("Choose ${slotName(k).lowercase()}") ?: return@launch
                app.store.media.setFromFile(owner, k, path)
            }
        }))
        if (k != MediaKind.SCREENSHOT && k != MediaKind.VIDEO && media.has(k)) {
            add(MenuAction("adjust", "Adjust crop and focus", FuseIcons.Crop, onSelect = {
                app.choice = null
                val item = media.first(k)
                fx = item?.focusX ?: 0.5f; fy = item?.focusY ?: 0.5f; zoom = item?.zoom ?: 1f
                adjusting = k
            }))
        }
        if (media.has(k)) add(MenuAction("reset", "Reset", FuseIcons.RotateCcw, destructive = true, onSelect = {
            app.choice = null
            app.confirm = ConfirmSpec("Reset ${slotName(k).lowercase()}?", "Removes this art from Fuse, including art you chose. Automatic filling can find new art later.", "Reset", destructive = true) {
                app.scope.launch { app.store.media.reset(owner, k) }
            }
        }))
    }

    LaunchedEffect(browser, adjusting) {
        app.hints = when {
            adjusting != null -> listOf(Hint(HintButton.DPAD, "Move focus"), Hint(HintButton.PREV, "Zoom out"), Hint(HintButton.NEXT, "Zoom in"), Hint(HintButton.CONFIRM, "Save"), Hint(HintButton.BACK, "Cancel"))
            browser is Browser.Options -> listOf(Hint(HintButton.CONFIRM, "Use this"), Hint(HintButton.BACK, "Close"))
            else -> listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.OPTIONS, "Fill missing"), Hint(HintButton.BACK, "Back"))
        }
    }

    // Artwork browser and crop adjustment are modal layers over the slot list.
    val options = (browser as? Browser.Options)
    if (options != null) {
        InputLayer(priority = LayerPriority.OVERLAY, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { browser = Browser.Closed; NavResult.CONSUMED }
                NavAction.SELECT -> {
                    options.options.getOrNull(grid.index)?.let { opt -> app.scope.launch { app.store.media.apply(owner, opt); app.toasts.show("Saved as your ${slotName(opt.kind).lowercase()}") } }
                    browser = Browser.Closed
                    NavResult.ACTIVATED
                }
                else -> grid.move(e.action, options.options.size, columns = 4).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            }
        }
    }
    val adj = adjusting
    if (adj != null) {
        InputLayer(priority = LayerPriority.OVERLAY, modal = true) { e ->
            when (e.action) {
                NavAction.LEFT -> { fx = (fx - 0.04f).coerceIn(0f, 1f); NavResult.MOVED }
                NavAction.RIGHT -> { fx = (fx + 0.04f).coerceIn(0f, 1f); NavResult.MOVED }
                NavAction.UP -> { fy = (fy - 0.04f).coerceIn(0f, 1f); NavResult.MOVED }
                NavAction.DOWN -> { fy = (fy + 0.04f).coerceIn(0f, 1f); NavResult.MOVED }
                NavAction.PREVIOUS_SECTION, NavAction.PAGE_UP -> { zoom = (zoom - 0.1f).coerceIn(1f, 3f); NavResult.MOVED }
                NavAction.NEXT_SECTION, NavAction.PAGE_DOWN -> { zoom = (zoom + 0.1f).coerceIn(1f, 3f); NavResult.MOVED }
                NavAction.SELECT -> { app.scope.launch { app.store.media.adjust(owner, adj, fx, fy, zoom) }; adjusting = null; NavResult.ACTIVATED }
                NavAction.BACK -> { adjusting = null; NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen && options == null && adj == null) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN -> sel.move(e.action, slots.size, vertical = true).let { if (it == NavResult.IGNORED && e.action == NavAction.DOWN) NavResult.BLOCKED else it }
            NavAction.SELECT -> { app.choice = ChoiceSpec(slotName(kind), sourceLine(media, kind), slotActions(kind)); NavResult.ACTIVATED }
            NavAction.CONTEXT -> {
                app.choice = ChoiceSpec(
                    "Fill art", "Custom art is never replaced by filling.",
                    listOf(
                        MenuAction("missing", "Fill missing", FuseIcons.Wand, detail = "Only empty slots", onSelect = { app.choice = null; fill(app, owner, MediaFillMode.FILL_MISSING, slots.map { it.first }.toSet()) }),
                        MenuAction("this", "Replace ${slotName(kind).lowercase()}", FuseIcons.RotateCcw, detail = "Unless you chose it yourself", onSelect = { app.choice = null; fill(app, owner, MediaFillMode.REPLACE_SELECTED, setOf(kind)) }),
                        MenuAction("all", "Replace all scraped art", FuseIcons.Refresh, onSelect = { app.choice = null; fill(app, owner, MediaFillMode.REPLACE_ALL, slots.map { it.first }.toSet()) }),
                    ),
                )
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    val c = Fuse.colors
    Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        val slotRequesters = remember { List(slots.size) { BringIntoViewRequester() } }
        LaunchedEffect(sel.index) { slotRequesters[sel.index].bringIntoView() }
        Column(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = Size.hintHeight + Space.l)) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            FText("Manage media", Fuse.type.display)
            FText(title, Fuse.type.body, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(Space.l))
            slots.forEachIndexed { i, (k, name) ->
                val selected = i == sel.index && app.focusZone == FocusZone.CONTENT && adj == null && options == null
                Row(
                    Modifier.fillMaxWidth().bringIntoViewRequester(slotRequesters[i]).clip(RoundedCornerShape(Fuse.geometry.control))
                        .background(if (selected) c.text.copy(alpha = 0.1f) else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, null) { sel.index = i; app.choice = ChoiceSpec(name, sourceLine(media, k), slotActions(k)) }
                        .padding(horizontal = Space.m, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(24.dp).background(if (selected) c.accent else Color.Transparent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(Space.m))
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                        val m = media.first(k)
                        if (m != null && k != MediaKind.VIDEO) Artwork(m.model, Modifier.fillMaxSize(), contentScale = if (k == MediaKind.LOGO) ContentScale.Fit else ContentScale.Crop)
                        else FuseIcon(if (k == MediaKind.VIDEO) FuseIcons.Film else FuseIcons.Image, size = 18.dp, tint = c.textFaint)
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(name, Fuse.type.bodyStrong)
                        FText(sourceLine(media, k), Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(Space.xxl))
        Box(Modifier.weight(1f).fillMaxHeight().padding(top = Size.hudHeight + Space.l, bottom = Size.hintHeight + Space.l)) {
            when (val b = browser) {
                Browser.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                is Browser.Message -> Column {
                    FText(b.text, Fuse.type.body, color = c.textMuted)
                    Spacer(Modifier.height(Space.m))
                    FuseButton("Close", selected = true, onClick = { browser = Browser.Closed })
                }
                is Browser.Options -> ArtworkGrid(b, grid) { opt ->
                    app.scope.launch { app.store.media.apply(owner, opt) }
                    browser = Browser.Closed
                }
                Browser.Closed -> Preview(media, kind, adj, fx, fy, zoom)
            }
        }
    }
}

@Composable
private fun Preview(media: MediaSet, kind: MediaKind, adjusting: MediaKind?, fx: Float, fy: Float, zoom: Float) {
    val c = Fuse.colors
    val m = media.first(kind)
    Column {
        SectionLabel(if (adjusting != null) "Adjusting ${slotName(kind).lowercase()}" else slotName(kind))
        Spacer(Modifier.height(Space.m))
        val aspect = kind.aspect ?: 2.6f
        Tile(selected = false, showSpark = false, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().aspectRatio(aspect).heightIn(max = 420.dp)) {
            if (m != null && kind != MediaKind.VIDEO) {
                Artwork(
                    m.model, Modifier.fillMaxSize(),
                    contentScale = if (kind == MediaKind.LOGO) ContentScale.Fit else ContentScale.Crop,
                    focusX = if (adjusting != null) fx else m.focusX,
                    focusY = if (adjusting != null) fy else m.focusY,
                    zoom = if (adjusting != null) zoom else m.zoom,
                )
                if (adjusting != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val p = Offset(size.width * fx, size.height * fy)
                        drawCircle(Color.White, radius = 14.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                        drawCircle(Color.White, radius = 3.dp.toPx(), center = p)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                    FText("Nothing yet", Fuse.type.body, color = c.textMuted)
                }
            }
        }
        if (kind == MediaKind.SCREENSHOT && media.screenshots.size > 1) {
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                media.screenshots.take(6).forEach { s -> Artwork(s.model, Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp))) }
            }
        }
        m?.let {
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Chip(sourceName(it.source), icon = if (it.isCustom) FuseIcons.Pin else FuseIcons.Cloud, color = c.textMuted)
                if (it.width != null && it.height != null) Chip("${it.width}x${it.height}", color = c.textMuted)
            }
        }
    }
}

@Composable
private fun ArtworkGrid(b: Browser.Options, grid: GridSelection, onPick: (ArtworkOption) -> Unit) {
    val state = rememberLazyGridState()
    FollowSelection(state, { grid.index }, anchor = 0.15f)
    Column {
        SectionLabel("${b.options.size} options for ${slotName(b.kind).lowercase()}")
        Spacer(Modifier.height(Space.m))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l),
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            itemsIndexed(b.options) { i, opt ->
                Column {
                    Tile(selected = i == grid.index, modifier = Modifier.fillMaxWidth().aspectRatio(b.kind.aspect ?: 2.2f), onClick = { grid.index = i; onPick(opt) }) {
                        Box(Modifier.fillMaxSize().background(Fuse.colors.surfaceRaised)) {
                            Artwork(opt.thumbUrl ?: opt.url, Modifier.fillMaxSize(), contentScale = if (b.kind == MediaKind.LOGO) ContentScale.Fit else ContentScale.Crop)
                        }
                    }
                    Spacer(Modifier.height(Space.xs))
                    FText(
                        // System art pack options carry the pack's name as their author.
                        listOfNotNull(
                            if (opt.provider == ScrapeProviderId.LOCAL && opt.author != null) opt.author else opt.provider.displayName,
                            opt.style,
                            opt.width?.let { "${it}x${opt.height}" },
                        ).joinToString("  ·  "),
                        Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun fill(app: AppState, owner: MediaOwner, mode: MediaFillMode, kinds: Set<MediaKind>) {
    val game = (owner as? MediaOwner.OfGame)?.id
    val platform = (owner as? MediaOwner.OfPlatform)?.id
    app.store.media.fill(mode, kinds, platform = platform, game = game)
    app.toasts.show("Looking for art")
}

private fun slotName(k: MediaKind) = slots.firstOrNull { it.first == k }?.second ?: k.name

private fun sourceName(s: MediaSource) = when (s) {
    MediaSource.USER -> "Chosen by you"
    MediaSource.LOCAL_FOLDER -> "From your folder"
    MediaSource.ROMM -> "RomM"
    MediaSource.STEAMGRIDDB -> "SteamGridDB"
    MediaSource.IGDB -> "IGDB"
    MediaSource.THEGAMESDB -> "TheGamesDB"
    MediaSource.SCREENSCRAPER -> "ScreenScraper"
    MediaSource.LIBRETRO -> "Libretro thumbnails"
    MediaSource.ART_PACK -> "Art Book Next"
    MediaSource.GENERATED -> "Generated"
}

private fun sourceLine(media: MediaSet, k: MediaKind): String {
    val all = media.all(k)
    val first = all.firstOrNull() ?: return "Empty"
    return if (all.size > 1) "${all.size} images  ·  ${sourceName(first.source)}" else sourceName(first.source)
}
