package io.github.matiyaaa.fuse.ui.shell.cartridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.app.systemRoom
import io.github.matiyaaa.fuse.ui.shell.components.ControlTile
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.RecentDownload
import kotlinx.coroutines.launch

/** One action tile: what it says, and what it does. [needsBridge] tiles wait for Cartridge 0.9.10. */
private data class CartAction(
    val label: String,
    val icon: ImageVector,
    val detail: String? = null,
    val primary: Boolean = false,
    val needsBridge: Boolean = true,
    val run: () -> Unit,
)

private const val ACTIONS = "actions"
private const val SYSTEMS = "systems"
private const val RECENT = "recent"

/**
 * Cartridge from Fuse: a hero with the connection and what is downloading right now, a row of
 * actions (open, browse, search RomM with Fuse's keyboard, downloads, upload, consoles, sync), your
 * systems to browse on RomM, and what just arrived, ready to play. Anything more opens the real
 * Cartridge (which keeps its own look); pressing Back there returns straight here and Fuse picks up
 * the new games by itself. Controller first, and every tile answers touch.
 */
@Composable
fun CartridgeScreen(app: AppState) {
    val store = app.store
    val status by store.cartridge.status.collectAsState()
    val recent by store.cartridge.recent.collectAsState()
    val platforms by store.library.platforms.collectAsState()
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val sel = rememberRouteState(app.navigator, "cartridge") { ShelfSelection() }

    LaunchedEffect(status.installed) {
        store.cartridge.refresh()
        if (!status.installed) {
            release = store.cartridge.latestRelease()
            checked = true
        }
    }

    fun open(route: CartridgeRoute) = store.cartridge.open(route)
    val bridge = status.installed && status.bridge
    fun search(platform: PlatformCard? = null) {
        app.textInput = TextInputSpec(
            title = if (platform != null) "Search ${platform.platform.shortName} on RomM" else "Search RomM",
            initial = "",
            placeholder = "A game's name",
            capitalize = false,
            doneLabel = "Search",
        ) { q -> open(CartridgeRoute.Search(q.trim(), platform?.platform?.id?.value)) }
    }

    val waiting = status.queue.count { it.state != QueueState.DOWNLOADING }.takeIf { status.queue.isNotEmpty() } ?: status.queuedDownloads
    val active = status.queue.count { it.state == QueueState.DOWNLOADING }.takeIf { status.queue.isNotEmpty() } ?: status.activeDownloads
    val actions = if (!status.installed) listOf(
        CartAction(if (installing) "Installing" else "Install Cartridge", FuseIcons.Download, detail = release?.name ?: if (checked) "Offline" else "Checking GitHub", primary = true, needsBridge = false) {
            val r = release
            if (r == null) {
                app.toasts.show("Couldn't reach GitHub to find Cartridge's latest release. Check your connection.")
            } else if (!installing) {
                app.confirm = ConfirmSpec(
                    title = "Install Cartridge ${r.tag.removePrefix("v")}?",
                    message = "Fuse downloads the official release from GitHub (github.com/MAtiyaaa/cartridge) and hands it to your system's installer, where you confirm it. Nothing installs silently.",
                    confirmLabel = "Download and install",
                    onConfirm = {
                        installing = true
                        app.scope.launch {
                            val result = store.cartridge.install(r)
                            installing = false
                            result.onFailure { app.toasts.show(it.message ?: "Couldn't install Cartridge") }
                        }
                    },
                )
            }
        },
        CartAction("About Cartridge", FuseIcons.Info, detail = "On GitHub", needsBridge = false) { app.platform.openUrl("https://github.com/MAtiyaaa/cartridge") },
    ) else listOf(
        CartAction("Open Cartridge", FuseIcons.External, detail = status.version?.let { "Version $it" }, primary = true, needsBridge = false) { open(CartridgeRoute.Home) },
        CartAction("Browse", FuseIcons.Library, detail = "Your RomM library") { open(CartridgeRoute.Library) },
        CartAction("Search", FuseIcons.Search, detail = "Find a game") { search() },
        CartAction("Upload", FuseIcons.Upload, detail = "A game to RomM") { app.uploadPicker() },
        CartAction(
            "Downloads", FuseIcons.Download,
            detail = when {
                active > 0 && waiting > 0 -> "$active active  ·  $waiting waiting"
                active > 0 -> "$active active"
                waiting > 0 -> "$waiting waiting"
                else -> "Nothing waiting"
            },
        ) { open(CartridgeRoute.Downloads) },
        CartAction("Consoles", FuseIcons.Chip, detail = "Systems on RomM") { open(CartridgeRoute.Consoles) },
        CartAction("Sync", FuseIcons.Refresh, detail = "Library and saves") { open(CartridgeRoute.Sync) },
    )
    val systems = if (bridge) platforms.filter { it.gameCount > 0 } else emptyList()
    val shownRecent = if (status.installed) recent else emptyList()
    val rows = listOfNotNull(ACTIONS, SYSTEMS.takeIf { systems.isNotEmpty() }, RECENT.takeIf { shownRecent.isNotEmpty() })
    fun sizeOf(row: String) = when (row) {
        ACTIONS -> actions.size
        SYSTEMS -> systems.size
        else -> shownRecent.size
    }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: ACTIONS
    val focused = app.focusZone == FocusZone.CONTENT

    fun runAction(a: CartAction) {
        if (a.needsBridge && !bridge) app.toasts.show("This needs Cartridge 0.9.10 or newer.") else a.run()
    }
    fun systemMenu(card: PlatformCard) {
        val p = card.platform
        app.openContextMenu(
            ContextMenuSpec(
                title = p.name,
                subtitle = "On your RomM server",
                actions = listOfNotNull(
                    MenuAction("browse", "Browse ${p.shortName} in Cartridge", FuseIcons.CloudDownload, onSelect = { app.closeOverlays(); open(CartridgeRoute.Platform(p.id.value)) }),
                    MenuAction("search", "Search ${p.shortName}", FuseIcons.Search, onSelect = { app.closeOverlays(); search(card) }),
                    if (card.bios.state == BiosState.MISSING || card.bios.state == BiosState.PARTIAL) {
                        MenuAction("bios", "Get firmware from RomM", FuseIcons.Key, detail = "${p.shortName} firmware is missing", onSelect = { app.closeOverlays(); open(CartridgeRoute.Bios(p.id.value)) })
                    } else null,
                ),
            ),
        )
    }
    fun recentMenu(r: RecentDownload) {
        val game = r.game
        val inCartridge = MenuAction("cartridge", "Open in Cartridge", FuseIcons.External, onSelect = { app.closeOverlays(); open(CartridgeRoute.Game(r.download.romId)) })
        if (game != null) app.openContextMenu(app.gameMenu(game, extra = listOf(inCartridge)))
        else open(CartridgeRoute.Game(r.download.romId))
    }
    fun activate(rowKey: String, col: Int) {
        when (rowKey) {
            ACTIONS -> actions.getOrNull(col)?.let(::runAction)
            SYSTEMS -> systems.getOrNull(col)?.let { open(CartridgeRoute.Platform(it.platform.id.value)) }
            RECENT -> shownRecent.getOrNull(col)?.let { r -> if (r.game != null) app.activateGame(r.game) else open(CartridgeRoute.Game(r.download.romId)) }
        }
    }
    fun options(rowKey: String, col: Int) {
        when (rowKey) {
            SYSTEMS -> systems.getOrNull(col)?.let(::systemMenu)
            RECENT -> shownRecent.getOrNull(col)?.let(::recentMenu)
            else -> Unit
        }
    }
    fun tap(rowKey: String, col: Int) {
        app.focusZone = FocusZone.CONTENT
        val r = rows.indexOf(rowKey)
        val already = sel.row == r && sel.column(rowKey) == col
        sel.row = r
        sel.setColumn(rowKey, col)
        // Actions do what they say at once; art tiles are chosen first, like everywhere in Fuse.
        if (already || rowKey == ACTIONS) activate(rowKey, col)
    }

    // The background follows a chosen system or game.
    val col = sel.column(row)
    LaunchedEffect(row, col, systems, shownRecent) {
        app.hero = when (row) {
            SYSTEMS -> systems.getOrNull(col)?.let(::systemRoom)
            RECENT -> shownRecent.getOrNull(col)?.game?.let { g -> g.room(platforms.firstOrNull { it.platform.id == g.platformId }) }
            else -> null
        }
        app.hints = when (row) {
            SYSTEMS -> listOf(Hint(HintButton.CONFIRM, "Browse in Cartridge"), Hint(HintButton.OPTIONS, "Options"))
            RECENT -> listOf(Hint(HintButton.CONFIRM, if (shownRecent.getOrNull(col)?.game != null) "Play" else "Open in Cartridge"), Hint(HintButton.OPTIONS, "Options"))
            else -> listOf(Hint(HintButton.CONFIRM, "Choose"))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            NavAction.UP, NavAction.DOWN -> sel.move(e.action, rows, ::sizeOf)
            NavAction.SELECT -> { activate(row, sel.column(row)); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { options(row, sel.column(row)); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val c = Fuse.colors
    val page = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val oldBridge = status.installed && !status.bridge
        val uploading = status.installed && status.uploads.isNotEmpty()
        // The page's items, so it can follow the chosen row (it fits on most screens and stays put).
        val items = listOfNotNull("hero", "old".takeIf { oldBridge }, "uploads".takeIf { uploading }, ACTIONS, SYSTEMS.takeIf { SYSTEMS in rows }, RECENT.takeIf { RECENT in rows }, "explainer".takeIf { !status.installed })
        FollowSelection(page, { if (sel.row == 0) 0 else items.indexOf(rows.getOrElse(sel.row) { ACTIONS }).coerceAtLeast(0) }, anchor = 0.1f)
        LazyColumn(
            state = page,
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight).fadingEdges(top = if (page.canScrollBackward) Space.xl else 0.dp),
            contentPadding = PaddingValues(top = if (compact) Space.s else Space.m, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.m else Space.l),
        ) {
            item(key = "hero") {
                Hero(status, release, checked, compact, platforms, onActivity = { if (bridge) open(CartridgeRoute.Downloads) })
            }
            if (oldBridge) {
                item(key = "old") {
                    Row(
                        Modifier.padding(horizontal = Space.gutter).widthIn(max = 820.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(Fuse.geometry.control))
                            .background(c.warning.copy(alpha = 0.12f))
                            .padding(horizontal = Space.l, vertical = Space.m),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FuseIcon(FuseIcons.Info, tint = c.warning)
                        FText(
                            "This Cartridge (${status.version ?: "unknown version"}) opens, but it can't be opened on a page or show its downloads here. Cartridge 0.9.10 or newer can.",
                            Fuse.type.body, maxLines = 2,
                        )
                    }
                }
            }
            if (uploading) {
                item(key = "uploads") {
                    UploadsPanel(status.uploads, Modifier.padding(horizontal = Space.gutter).widthIn(max = 720.dp), maxOthers = if (compact) 1 else 3)
                }
            }
            item(key = ACTIONS) {
                ShelfRow(
                    title = null,
                    count = actions.size,
                    selected = if (row == ACTIONS && focused) sel.column(ACTIONS) else -1,
                    remembered = sel.column(ACTIONS),
                ) { i, chosen ->
                    val a = actions[i]
                    ControlTile(
                        a.label, a.icon, selected = chosen,
                        modifier = Modifier.width(if (compact) 128.dp else 152.dp).height(if (compact) 80.dp else 92.dp),
                        active = a.primary,
                        detail = if (a.needsBridge && !bridge) "Needs Cartridge 0.9.10" else a.detail,
                        unavailable = a.needsBridge && !bridge,
                        compact = compact,
                    ) { tap(ACTIONS, i) }
                }
            }
            if (SYSTEMS in rows) {
                item(key = SYSTEMS) {
                    ShelfRow(
                        title = "Browse by system",
                        count = systems.size,
                        selected = if (row == SYSTEMS && focused) sel.column(SYSTEMS) else -1,
                        remembered = sel.column(SYSTEMS),
                    ) { i, chosen ->
                        val card = systems[i]
                        Tile(
                            selected = chosen,
                            modifier = Modifier.width(if (compact) 104.dp else 132.dp).aspectRatio(Aspect.SYSTEM_CARD),
                            glow = card.platform.accent.toColor(),
                            onClick = { tap(SYSTEMS, i) },
                            onLongClick = { sel.row = rows.indexOf(SYSTEMS); sel.setColumn(SYSTEMS, i); systemMenu(card) },
                        ) {
                            SystemCardArt(card)
                            if (card.bios.state == BiosState.MISSING || card.bios.state == BiosState.PARTIAL) {
                                Box(Modifier.align(Alignment.TopStart).padding(Space.s).size(8.dp).clip(CircleShape).background(c.warning))
                            }
                        }
                    }
                }
            }
            if (RECENT in rows) {
                item(key = RECENT) {
                    ShelfRow(
                        title = "Recently downloaded",
                        count = shownRecent.size,
                        selected = if (row == RECENT && focused) sel.column(RECENT) else -1,
                        remembered = sel.column(RECENT),
                    ) { i, chosen ->
                        RecentTile(shownRecent[i], chosen, size = if (compact) 84.dp else 100.dp, onClick = { tap(RECENT, i) }, onLongClick = {
                            sel.row = rows.indexOf(RECENT)
                            sel.setColumn(RECENT, i)
                            recentMenu(shownRecent[i])
                        })
                    }
                }
            }
            if (!status.installed) {
                item(key = "explainer") { HowItWorks(Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp)) }
            }
        }
    }
}

/**
 * A row of tiles that scrolls sideways and keeps the chosen one in view, with an optional title.
 * The gutter sits inside the row, so a chosen tile's lift and outline are never cut at the edge.
 */
@Composable
private fun ShelfRow(title: String?, count: Int, selected: Int, remembered: Int, tile: @Composable (Int, Boolean) -> Unit) {
    val state = rememberLazyListState()
    FollowSelection(state, { remembered }, anchor = 0f)
    Column {
        if (title != null) {
            SectionLabel(title, Modifier.padding(start = Space.gutter, bottom = Space.s), color = if (selected >= 0) Fuse.colors.text else Fuse.colors.textMuted)
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 2, top = Space.xs, bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            itemsIndexed(List(count) { it }, key = { i, _ -> i }) { i, _ -> tile(i, i == selected) }
        }
    }
}

/**
 * The top of the page: what Cartridge is, whether it reaches RomM, and on the right what is
 * happening now (the download in progress, else the last one) or, before installing, the release.
 */
@Composable
private fun Hero(
    status: CartridgeStatus,
    release: ReleaseInfo?,
    checked: Boolean,
    compact: Boolean,
    platforms: List<PlatformCard>,
    onActivity: () -> Unit,
) {
    val c = Fuse.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).height(if (compact) 104.dp else 132.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Get games")
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                FText("Cartridge", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                if (status.installed) ConnectionPill(status)
            }
            Spacer(Modifier.height(Space.s))
            FText(statusLine(status), Fuse.type.body, color = c.textMuted, maxLines = if (compact) 1 else 2, modifier = Modifier.widthIn(max = 560.dp))
        }
        val card = Modifier.width(if (compact) 300.dp else 400.dp).fillMaxHeight()
        if (status.installed) ActivityCard(status, platforms, card, onActivity) else ReleaseCard(release, checked, card)
    }
}

@Composable
private fun ConnectionPill(s: CartridgeStatus) {
    val c = Fuse.colors
    Row(
        Modifier.clip(PillShape).background(c.text.copy(alpha = 0.08f)).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s.connected)
        FText(
            when (s.connected) { true -> "Connected to RomM"; false -> "Not connected"; null -> "Status unknown" },
            Fuse.type.label, maxLines = 1,
        )
    }
}

/** A raised card in the hero: rounded, faintly lit from the top, tinted by [tint] when there is one. */
@Composable
private fun HeroCard(modifier: Modifier, tint: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val top = tint?.copy(alpha = 0.22f) ?: c.surfaceRaised.copy(alpha = 0.8f)
    Box(
        modifier
            .clip(shape)
            .background(c.surfaceRaised.copy(alpha = 0.7f))
            .background(Brush.linearGradient(listOf(top, Color.Transparent)))
            .border(1.dp, c.text.copy(alpha = 0.07f), shape)
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick) else Modifier)
            .padding(Space.l),
    ) { content() }
}

/** What is downloading now, over its system's colour; else the last download; else a quiet note. */
@Composable
private fun ActivityCard(status: CartridgeStatus, platforms: List<PlatformCard>, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val current = status.queue.firstOrNull { it.state == QueueState.DOWNLOADING }
    val downloading = current != null || status.activeDownloads > 0
    val slug = current?.platformSlug ?: status.currentPlatform
    val system = slug?.let { s -> platforms.firstOrNull { it.platform.id.value == s } }
    HeroCard(modifier, tint = if (downloading) (system?.platform?.accent?.toColor() ?: c.accent) else null, onClick = onClick) {
        if (downloading) {
            val progress = current?.progress ?: status.progress
            val others = (status.queue.size - 1).coerceAtLeast(0).takeIf { status.queue.isNotEmpty() } ?: status.queuedDownloads
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SystemMark(system, slug, Modifier.weight(1f))
                    progress?.let { FText("${(it * 100).toInt()}%", Fuse.type.label, color = c.accent, maxLines = 1) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FText(current?.title ?: status.currentTitle ?: "Preparing", Fuse.type.titleSmall, maxLines = 1)
                    ProgressBar(progress, Modifier.fillMaxWidth(), height = 6.dp)
                    val sizes = current?.let { q -> q.total?.let { "${bytesText(q.received)} of ${bytesText(it)}" } }
                    FText(
                        listOfNotNull(sizes, if (others > 0) "$others more waiting" else null).joinToString("  ·  ").ifEmpty { "Downloading" },
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                }
            }
        } else {
            val last = status.recent.maxByOrNull { it.finishedAt }
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    FuseIcon(if (last != null) FuseIcons.Check else FuseIcons.CloudDownload, tint = if (last != null) c.success else c.textMuted)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(if (last != null) "LAST DOWNLOAD" else "DOWNLOADS", Fuse.type.overline, color = c.textMuted, maxLines = 1)
                    FText(last?.title ?: "Nothing downloading", Fuse.type.bodyStrong, maxLines = 1)
                    FText(
                        last?.let { "Arrived ${agoText(it.finishedAt)}" } ?: "Games you download from RomM show up here",
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A system's logo in white, or its short name, for the activity card. */
@Composable
private fun SystemMark(system: PlatformCard?, slug: String?, modifier: Modifier) {
    val logo = system?.art?.logo
    val name = system?.platform?.shortName ?: slug?.uppercase() ?: "RomM"
    Box(modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
        if (logo != null) {
            Artwork(logo, Modifier.height(24.dp).width(110.dp), contentScale = ContentScale.Fit, focusX = 0f, tint = Color.White, fadeIn = false, fallback = {
                FText(name, Fuse.type.overline, maxLines = 1)
            })
        } else {
            FText(name, Fuse.type.overline, color = Fuse.colors.textMuted, maxLines = 1)
        }
    }
}

/** Before Cartridge is installed: its latest release, from GitHub. */
@Composable
private fun ReleaseCard(release: ReleaseInfo?, checked: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    HeroCard(modifier, tint = c.accent) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.CloudDownload, tint = c.accent)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText("LATEST RELEASE", Fuse.type.overline, color = c.textMuted, maxLines = 1)
                FText(release?.name ?: if (checked) "Couldn't reach GitHub" else "Checking GitHub", Fuse.type.bodyStrong, maxLines = 1)
                FText("github.com/MAtiyaaa/cartridge", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** A game that just arrived: its tile, its name, and whether it is ready to play. */
@Composable
private fun RecentTile(r: RecentDownload, selected: Boolean, size: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val game = r.game
    Column(Modifier.width(size)) {
        if (game != null) {
            GameIconTile(game, selected, size = size, onClick = onClick, onLongClick = onLongClick)
        } else {
            Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick, onLongClick = onLongClick) {
                GeneratedArt(r.download.title, 0xFF5B6475.toColor(), slot = ArtSlot.ICON, label = r.download.platformSlug.uppercase())
            }
        }
        Spacer(Modifier.height(Space.s))
        FText(game?.title ?: r.download.title, Fuse.type.label, color = if (selected) c.text else c.text.copy(alpha = 0.85f), maxLines = 1)
        FText(if (game != null) "Ready to play" else "Finding it", Fuse.type.caption, color = if (game != null) c.success else c.textMuted, maxLines = 1)
    }
}

/** Before installing: the three steps, each a card with its icon. */
@Composable
private fun HowItWorks(modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier) {
        SectionLabel("How it works", Modifier.padding(bottom = Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            listOf(
                Triple(FuseIcons.Server, "Connect", "Cartridge connects to your RomM server and lets you browse it with a controller."),
                Triple(FuseIcons.CloudDownload, "Download", "Games go straight into your ROM folders, BIOS included when RomM has it."),
                Triple(FuseIcons.Gamepad, "Play", "Press Back and Fuse already has the new game, with its art."),
            ).forEachIndexed { i, (icon, title, text) ->
                Panel(Modifier.weight(1f)) {
                    Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                FuseIcon(icon, size = 18.dp, tint = c.accent)
                            }
                            Spacer(Modifier.weight(1f))
                            FText("${i + 1}", Fuse.type.title, color = c.textFaint, maxLines = 1)
                        }
                        FText(title, Fuse.type.titleSmall, maxLines = 1)
                        FText(text, Fuse.type.caption, color = c.textMuted, maxLines = 3)
                    }
                }
            }
        }
    }
}

private fun statusLine(s: CartridgeStatus): String = when {
    !s.installed -> "Cartridge brings your RomM server's games to this device, each into the right system folder. Fuse shows them the moment they land."
    s.activeDownloads > 0 -> "Downloading ${s.activeDownloads + s.queuedDownloads} ${if (s.activeDownloads + s.queuedDownloads == 1) "game" else "games"}. They'll appear in Fuse on their own."
    s.connected == false -> "Cartridge isn't connected to your RomM server right now."
    else -> "Find a game, download it, press Back, and it's ready to play here."
}
