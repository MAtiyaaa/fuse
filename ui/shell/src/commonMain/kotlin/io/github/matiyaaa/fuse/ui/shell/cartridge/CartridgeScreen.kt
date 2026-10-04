package io.github.matiyaaa.fuse.ui.shell.cartridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.CartridgeQueueItem
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.skeleton
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.CartridgeBrand
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
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
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.ReportScroll
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.components.tileSize
import io.github.matiyaaa.fuse.ui.shell.home.CartridgeEmblem
import io.github.matiyaaa.fuse.ui.shell.home.LocalCartridgeIcon
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.AppIconModel
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.RecentDownload
import kotlinx.coroutines.launch

/** One action card: what it says, and what it does. [needsBridge] cards wait for Cartridge 0.9.10. */
private data class CartAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val detail: String? = null,
    val primary: Boolean = false,
    val needsBridge: Boolean = true,
    val run: () -> Unit,
)

private const val ACTIONS = "actions"
private const val DOWNLOADING = "downloading"
private const val SYSTEMS = "systems"
private const val RECENT = "recent"

/** Cartridge as its own section (where there is no Store, so no Addons). */
@Composable
fun CartridgeScreen(app: AppState) = CartridgeContent(app, embedded = false, active = true)

/**
 * Cartridge from Fuse: its status (version, whether it reaches your RomM server), what it is doing,
 * and the way into each part of it: open, browse, search RomM with Fuse's keyboard, downloads,
 * upload, consoles, sync. A download in progress gets a card of its own, then your systems on RomM
 * and what just arrived, ready to play. Anything more opens the real Cartridge (which keeps its own
 * look); Back there returns straight here and Fuse picks up the new games by itself.
 *
 * Before Cartridge is installed the page explains it in three steps and offers the official
 * release from GitHub, installed through the system's own installer.
 *
 * [embedded] inside Addons, the page leaves its title to Addons and shows a compact status line.
 * [active] false hands the controller to whoever contains it (Addons' tabs). Up from the first row
 * is left to the container ([NavResult.IGNORED]).
 */
@Composable
fun CartridgeContent(app: AppState, embedded: Boolean, active: Boolean, topPadding: Dp = Size.hudHeight) {
    val store = app.store
    val status by store.cartridge.status.collectAsState()
    val recent by store.cartridge.recent.collectAsState()
    val platforms by store.library.platforms.collectAsState()
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var checked by remember { mutableStateOf(status.installed) }
    var installing by remember { mutableStateOf(false) }
    val sel = rememberRouteState(app.navigator, "cartridge") { ShelfSelection() }

    LaunchedEffect(status.installed) {
        store.cartridge.refresh()
        if (!status.installed) {
            release = store.cartridge.latestRelease()
        }
        checked = true
    }

    fun open(route: CartridgeRoute) = store.cartridge.open(route)
    val bridge = status.installed && status.bridge
    val oldBridge = status.installed && !status.bridge
    fun search(platform: PlatformCard? = null) {
        app.textInput = TextInputSpec(
            title = if (platform != null) "Search ${platform.platform.shortName} on RomM" else "Search RomM",
            initial = "",
            placeholder = "A game's name",
            capitalize = false,
            doneLabel = "Search",
        ) { q -> open(CartridgeRoute.Search(q.trim(), platform?.platform?.id?.value)) }
    }
    fun install() {
        val r = release
        when {
            r == null -> app.toasts.show("Couldn't reach GitHub to find Cartridge's latest release. Check your connection.")
            installing -> Unit
            else -> app.confirm = ConfirmSpec(
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
    }
    fun update() {
        app.scope.launch {
            val r = store.cartridge.latestRelease() ?: return@launch app.toasts.show("Couldn't reach GitHub to find Cartridge's latest release. Check your connection.")
            app.confirm = ConfirmSpec(
                title = "Update Cartridge to ${r.tag.removePrefix("v")}?",
                message = "Fuse downloads the official release from GitHub and hands it to your system's installer, where you confirm it.",
                confirmLabel = "Download and install",
                onConfirm = { app.scope.launch { store.cartridge.install(r).onFailure { app.toasts.show(it.message ?: "Couldn't update Cartridge") } } },
            )
        }
    }

    val waiting = status.queue.count { it.state != QueueState.DOWNLOADING }.takeIf { status.queue.isNotEmpty() } ?: status.queuedDownloads
    val running = status.queue.count { it.state == QueueState.DOWNLOADING }.takeIf { status.queue.isNotEmpty() } ?: status.activeDownloads
    val actions = if (!status.installed) listOf(
        CartAction("install", if (installing) "Installing" else "Install Cartridge", FuseIcons.Download, detail = release?.name ?: if (checked) "Offline" else "Checking GitHub", primary = true, needsBridge = false) { install() },
        CartAction("about", "About Cartridge", FuseIcons.Info, detail = "On GitHub", needsBridge = false) { app.platform.openUrl("https://github.com/MAtiyaaa/cartridge") },
    ) else listOfNotNull(
        CartAction("open", "Open Cartridge", FuseIcons.External, detail = status.version?.let { "Version $it" }, primary = true, needsBridge = false) { open(CartridgeRoute.Home) },
        CartAction("update", "Update Cartridge", FuseIcons.Refresh, detail = "For the full Fuse bridge", needsBridge = false) { update() }.takeIf { oldBridge },
        CartAction("browse", "Browse", FuseIcons.Library, detail = "Your RomM library") { open(CartridgeRoute.Library) },
        CartAction("search", "Search", FuseIcons.Search, detail = "Find a game") { search() },
        CartAction(
            "downloads", "Downloads", FuseIcons.Download,
            detail = when {
                running > 0 && waiting > 0 -> "$running active  ·  $waiting waiting"
                running > 0 -> "$running active"
                waiting > 0 -> "$waiting waiting"
                else -> "Nothing waiting"
            },
        ) { open(CartridgeRoute.Downloads) },
        CartAction("upload", "Upload", FuseIcons.Upload, detail = "A game to RomM") { app.uploadPicker() },
        CartAction("consoles", "Consoles", FuseIcons.Chip, detail = "Systems on RomM") { open(CartridgeRoute.Consoles) },
        CartAction("sync", "Sync", FuseIcons.Refresh, detail = "Library and saves") { open(CartridgeRoute.Sync) },
    )
    val systems = if (bridge) platforms.filter { it.gameCount > 0 } else emptyList()
    val shownRecent = if (status.installed) recent else emptyList()
    val current = status.queue.firstOrNull { it.state == QueueState.DOWNLOADING }
    val downloading = status.installed && (current != null || status.activeDownloads > 0)
    val rows = listOfNotNull(
        DOWNLOADING.takeIf { downloading },
        ACTIONS,
        SYSTEMS.takeIf { systems.isNotEmpty() },
        RECENT.takeIf { shownRecent.isNotEmpty() },
    )
    fun sizeOf(row: String) = when (row) {
        ACTIONS -> actions.size
        DOWNLOADING -> 1
        SYSTEMS -> systems.size
        else -> shownRecent.size
    }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: ACTIONS
    val focused = app.focusZone == FocusZone.CONTENT && active

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
                    MenuAction("browse", "Browse ${p.shortName} in Cartridge", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks.Cartridge, onSelect = { app.closeOverlays(); open(CartridgeRoute.Platform(p.id.value)) }),
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
            DOWNLOADING -> if (bridge) open(CartridgeRoute.Downloads) else open(CartridgeRoute.Home)
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
        // Everything opens at once, except a game, which is shown first and played on a second tap.
        if (already || rowKey != RECENT) activate(rowKey, col)
    }

    // The background follows a chosen system or game.
    val col = sel.column(row)
    LaunchedEffect(row, col, systems, shownRecent, active) {
        if (!active) return@LaunchedEffect
        app.hero = when (row) {
            SYSTEMS -> systems.getOrNull(col)?.let(::systemRoom)
            RECENT -> shownRecent.getOrNull(col)?.game?.let { g -> g.room(platforms.firstOrNull { it.platform.id == g.platformId }) }
            else -> null
        }
        app.hints = when (row) {
            SYSTEMS -> listOf(Hint(HintButton.CONFIRM, "Browse in Cartridge"), Hint(HintButton.OPTIONS, "Options"))
            RECENT -> listOf(Hint(HintButton.CONFIRM, if (shownRecent.getOrNull(col)?.game != null) "Play" else "Open in Cartridge"), Hint(HintButton.OPTIONS, "Options"))
            DOWNLOADING -> listOf(Hint(HintButton.CONFIRM, "Downloads"))
            else -> listOf(Hint(HintButton.CONFIRM, "Choose"))
        }
    }

    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            NavAction.UP, NavAction.DOWN -> sel.move(e.action, rows, ::sizeOf)
            NavAction.SELECT -> { activate(row, sel.column(row)); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { options(row, sel.column(row)); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val cartridgeIcon = remember { if (store.apps.supported) AppIconModel(io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol.PACKAGE_NAME) else null }
    val page = rememberLazyListState()
    // Inside Addons, the tabs above fold away as the page scrolls.
    ReportScroll(page)
    val room = subTabsRoom()
    androidx.compose.runtime.CompositionLocalProvider(LocalCartridgeIcon provides cartridgeIcon.takeIf { status.installed }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 600.dp
            val items = listOfNotNull(
                "header", "notice".takeIf { oldBridge || (status.installed && status.connected == false) },
                "uploads".takeIf { status.installed && status.uploads.isNotEmpty() },
                DOWNLOADING.takeIf { DOWNLOADING in rows }, ACTIONS, SYSTEMS.takeIf { SYSTEMS in rows }, RECENT.takeIf { RECENT in rows },
            )
            FollowSelection(page, { if (sel.row == 0) 0 else items.indexOf(rows.getOrElse(sel.row) { ACTIONS }).coerceAtLeast(0) }, anchor = 0.1f)
            if (!status.installed && !checked) {
                // The first read of Cartridge's state and its latest release: the page's shape, quietly.
                CartridgeSkeleton(Modifier.padding(top = topPadding + room + Space.l).padding(horizontal = Space.gutter))
            } else LazyColumn(
                state = page,
                modifier = Modifier.fillMaxSize().padding(top = topPadding).fadingEdges(top = if (page.canScrollBackward) Space.xl else 0.dp),
                contentPadding = PaddingValues(top = room + if (compact) Space.s else Space.m, bottom = Size.hintHeight + Space.xl),
                verticalArrangement = Arrangement.spacedBy(if (compact) Space.l else Space.xl),
            ) {
                item(key = "header") {
                    if (!status.installed) {
                        Pitch(
                            release = release,
                            checked = checked,
                            installing = installing,
                            compact = compact,
                            selected = if (row == ACTIONS && focused) sel.column(ACTIONS) else -1,
                            onInstall = { tap(ACTIONS, 0) },
                            onAbout = { tap(ACTIONS, 1) },
                            modifier = Modifier.padding(horizontal = Space.gutter),
                        )
                    } else {
                        Header(status, embedded = embedded, compact = compact, modifier = Modifier.padding(horizontal = Space.gutter))
                    }
                }
                if ("notice" in items) {
                    item(key = "notice") {
                        val text = if (oldBridge) {
                            "This Cartridge (${status.version ?: "an older version"}) opens, but can't be opened on a page or share its downloads with Fuse. Update it from here to get everything below."
                        } else {
                            "Cartridge isn't connected to your RomM server right now. Open Cartridge to sign in again; downloads resume by themselves."
                        }
                        Notice(if (oldBridge) FuseIcons.Info else FuseIcons.CloudOff, Fuse.colors.warning, text, Modifier.padding(horizontal = Space.gutter).widthIn(max = 880.dp))
                    }
                }
                if ("uploads" in items) {
                    item(key = "uploads") {
                        UploadsPanel(
                            status.uploads, Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp), maxOthers = if (compact) 1 else 3,
                            platforms = platforms, compact = compact,
                        )
                    }
                }
                if (DOWNLOADING in rows) {
                    item(key = DOWNLOADING) {
                        NowDownloading(
                            status, current, platforms,
                            selected = row == DOWNLOADING && focused,
                            compact = compact,
                            modifier = Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp),
                        ) { tap(DOWNLOADING, 0) }
                    }
                }
                if (status.installed) {
                    item(key = ACTIONS) {
                        ShelfRow(
                            title = null,
                            count = actions.size,
                            selected = if (row == ACTIONS && focused) sel.column(ACTIONS) else -1,
                            remembered = sel.column(ACTIONS),
                        ) { i, chosen ->
                            val a = actions[i]
                            ActionCard(
                                a, chosen,
                                unavailable = a.needsBridge && !bridge,
                                compact = compact,
                            ) { tap(ACTIONS, i) }
                        }
                    }
                }
                if (SYSTEMS in rows) {
                    item(key = SYSTEMS) {
                        ShelfRow(
                            title = "Your systems on RomM",
                            count = systems.size,
                            selected = if (row == SYSTEMS && focused) sel.column(SYSTEMS) else -1,
                            remembered = sel.column(SYSTEMS),
                        ) { i, chosen ->
                            val card = systems[i]
                            Tile(
                                selected = chosen,
                                modifier = Modifier.width(if (compact) 120.dp else 152.dp).aspectRatio(Aspect.SYSTEM_CARD),
                                glow = card.platform.accent.toColor(),
                                onClick = { tap(SYSTEMS, i) },
                                onLongClick = { sel.row = rows.indexOf(SYSTEMS); sel.setColumn(SYSTEMS, i); systemMenu(card) },
                            ) {
                                SystemCardArt(card)
                                if (card.bios.state == BiosState.MISSING || card.bios.state == BiosState.PARTIAL) {
                                    Box(Modifier.align(Alignment.TopStart).padding(Space.s).size(8.dp).clip(CircleShape).background(Fuse.colors.warning))
                                }
                            }
                        }
                    }
                }
                if (RECENT in rows) {
                    item(key = RECENT) {
                        ShelfRow(
                            title = "Just arrived",
                            count = shownRecent.size,
                            selected = if (row == RECENT && focused) sel.column(RECENT) else -1,
                            remembered = sel.column(RECENT),
                        ) { i, chosen ->
                            RecentTile(shownRecent[i], chosen, size = if (compact) 88.dp else 104.dp, onClick = { tap(RECENT, i) }, onLongClick = {
                                sel.row = rows.indexOf(RECENT)
                                sel.setColumn(RECENT, i)
                                recentMenu(shownRecent[i])
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * A row of cards that scrolls sideways and keeps the chosen one in view, under an optional title.
 * The gutter sits inside the row, so a chosen card's lift and outline are never cut at the edge.
 */
@Composable
private fun ShelfRow(title: String?, count: Int, selected: Int, remembered: Int, tile: @Composable (Int, Boolean) -> Unit) {
    val state = rememberLazyListState()
    FollowSelection(state, { remembered }, anchor = 0f)
    Column {
        if (title != null) {
            SectionLabel(title, Modifier.padding(start = Space.gutter, bottom = Space.s), color = if (selected >= 0) Fuse.colors.text else Fuse.colors.textMuted, count = count.toString())
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
 * The page's top: Cartridge's mark (its own icon once installed), its name and version, and whether
 * it reaches your RomM server, said in one calm line underneath. Inside Addons the name is already
 * in the tabs, so the line is all there is.
 */
@Composable
private fun Header(status: CartridgeStatus, embedded: Boolean, compact: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CartridgeEmblem(if (embedded || compact) 48.dp else 64.dp)
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                FText(
                    if (embedded) "Cartridge${status.version?.let { "  $it" } ?: ""}" else "Cartridge",
                    if (embedded || compact) Fuse.type.titleSmall else Fuse.type.display,
                    maxLines = 1,
                )
                ConnectionChip(status)
            }
            Spacer(Modifier.height(Space.xs))
            FText(statusLine(status), Fuse.type.body, color = c.textMuted, maxLines = 2, modifier = Modifier.widthIn(max = 720.dp))
        }
    }
}

/** Whether Cartridge reaches RomM, as a small pill with a light; only when it doesn't, since that is what needs saying. */
@Composable
private fun ConnectionChip(s: CartridgeStatus) {
    if (s.connected == true) return
    val c = Fuse.colors
    Row(
        Modifier.clip(PillShape).background(c.text.copy(alpha = 0.07f)).border(1.dp, c.text.copy(alpha = 0.06f), PillShape)
            .padding(horizontal = Space.m, vertical = Space.xs + Space.xxs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s.connected)
        FText(
            when (s.connected) { true, false -> "Not connected to RomM"; null -> if (s.bridge) "Status unknown" else "Status needs a newer Cartridge" },
            Fuse.type.label, maxLines = 1,
        )
    }
}

/** A note that matters now, in its colour, with an icon: an old Cartridge, a lost connection. */
@Composable
private fun Notice(icon: ImageVector, tint: Color, text: String, modifier: Modifier) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Row(
        modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.1f)).border(1.dp, tint.copy(alpha = 0.22f), shape)
            .padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, tint = tint)
        FText(text, Fuse.type.body, color = c.text, maxLines = 3)
    }
}

/**
 * One way into Cartridge, as a card: its icon in a lit well, its name and what it opens. The first
 * (Open Cartridge) is lit in Cartridge's colour. A card that needs a newer Cartridge says so and
 * stays quiet.
 */
@Composable
private fun ActionCard(action: CartAction, selected: Boolean, unavailable: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val brand = io.github.matiyaaa.fuse.ui.shell.home.CARTRIDGE_TINT.toColor()
    val shape = remember { SquircleShape.fraction(0.14f) }
    Tile(
        selected = selected,
        modifier = Modifier.width(if (compact) 168.dp else 196.dp).height(if (compact) 104.dp else 120.dp),
        shape = shape,
        cornerFraction = 0.14f,
        glow = brand,
        onClick = onClick,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                if (action.primary) Brush.linearGradient(listOf(lerp(brand, Color.White, 0.06f), lerp(brand, Color.Black, 0.45f)))
                else Brush.linearGradient(listOf(c.surfaceRaised, c.surface)),
            ),
        )
        Column(Modifier.fillMaxSize().padding(Space.m)) {
            val ink = if (action.primary) Color.White else c.text
            Box(
                Modifier.size(Size.chip).clip(SquircleShape.fraction(0.3f)).background(if (action.primary) Color.White.copy(alpha = 0.18f) else brand.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(action.icon, size = Size.iconM, tint = if (action.primary) Color.White else if (unavailable) c.textFaint else brand)
            }
            Spacer(Modifier.weight(1f))
            FText(action.label, Fuse.type.bodyStrong, color = if (unavailable) c.textMuted else ink, maxLines = 1)
            val detail = if (unavailable) "Needs Cartridge 0.9.10" else action.detail
            if (detail != null) FText(detail, Fuse.type.caption, color = if (action.primary) Color.White.copy(alpha = 0.78f) else c.textMuted, maxLines = 1)
        }
    }
}

/**
 * The download in progress, as a card of its own: art on the left (its system's, or one drawn for
 * the game when Fuse doesn't have its system yet), what it is and what waits behind it, a bar in the
 * system's colour, and on the right how far along it is and how long is left. Opens the downloads.
 */
@Composable
private fun NowDownloading(
    status: CartridgeStatus,
    current: CartridgeQueueItem?,
    platforms: List<PlatformCard>,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val slug = current?.platformSlug ?: status.currentPlatform
    val system = slug?.let { s -> platforms.firstOrNull { it.platform.id.value == s } }
    val title = current?.title ?: status.currentTitle ?: "Preparing"
    val others = (status.queue.size - 1).coerceAtLeast(0).takeIf { status.queue.isNotEmpty() } ?: status.queuedDownloads
    val eta = rememberTimeLeft(current?.received, current?.total)
    TransferCard(
        label = "NOW DOWNLOADING",
        icon = FuseIcons.Download,
        title = title,
        system = system,
        systemName = system?.platform?.name ?: slug?.uppercase().orEmpty(),
        slug = slug,
        progress = current?.progress ?: status.progress,
        sizes = current?.let { q -> q.total?.let { "${bytesText(q.received)} of ${bytesText(it)}" } },
        timeLeft = eta,
        waiting = others,
        selected = selected,
        compact = compact,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * A transfer in progress (a download from RomM, or an upload to it), drawn the same either way:
 * art on the left, what it is in the middle over a bar in its system's colour, and how far along
 * it is, large, with the time left under it, on the right. The card is as tall as what it says.
 */
@Composable
internal fun TransferCard(
    label: String,
    icon: ImageVector,
    title: String,
    system: PlatformCard?,
    systemName: String,
    slug: String?,
    progress: Float?,
    sizes: String?,
    timeLeft: String?,
    waiting: Int,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    note: String? = null,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val tint = system?.platform?.accent?.toColor() ?: c.accent
    val shape = remember { SquircleShape.fraction(0.1f) }
    val minHeight = if (compact) 104.dp else 132.dp
    val artWidth = minHeight * Aspect.SYSTEM_CARD
    Tile(selected = selected, modifier = modifier.fillMaxWidth().heightIn(min = minHeight), shape = shape, cornerFraction = 0.1f, glow = tint, maxGrow = 6.dp, onClick = onClick) {
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(lerp(tint, Color.Black, 0.55f), lerp(tint, Color.Black, 0.82f)))))
        // The art, fading into the card on its right edge.
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.width(artWidth).fillMaxHeight().drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, lerp(tint, Color.Black, 0.6f)), startX = size.width * 0.55f, endX = size.width))
                },
            ) {
                if (system != null) SystemCardArt(system) else GeneratedArt(title, tint, slot = ArtSlot.ICON, label = slug?.uppercase())
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(artWidth))
            Column(
                Modifier.weight(1f).heightIn(min = minHeight).padding(start = Space.l, end = Space.m, top = Space.m, bottom = Space.m),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(icon, size = Size.iconS, tint = c.onArtMuted)
                    Spacer(Modifier.width(Space.s))
                    FText(label, Fuse.type.overline, color = c.onArtMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (waiting > 0) {
                        Spacer(Modifier.width(Space.s))
                        FText("+$waiting waiting", Fuse.type.caption, color = c.onArtMuted, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(Space.xs))
                FText(title, Fuse.type.title, color = c.onArt, maxLines = 1)
                Spacer(Modifier.height(Space.s))
                ProgressBar(progress, Modifier.fillMaxWidth(), color = lerp(tint, Color.White, 0.25f), height = 4.dp)
                Spacer(Modifier.height(Space.xs + Space.xxs))
                // The system gives way (it ellipsizes); the sizes never do.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FText(note ?: systemName, Fuse.type.caption, color = c.onArtMuted, maxLines = 1, modifier = Modifier.weight(1f))
                    if (sizes != null) {
                        Spacer(Modifier.width(Space.m))
                        FText(sizes, Fuse.type.caption.tabular(), color = c.onArt, maxLines = 1)
                    }
                }
            }
            // How far along, large, and how long is left.
            Column(
                Modifier.padding(end = Space.l).widthIn(min = if (compact) 64.dp else 88.dp),
                horizontalAlignment = Alignment.End,
            ) {
                FText(progress?.let { "${(it * 100).toInt()}%" } ?: "…", Fuse.type.numericLarge, color = c.onArt, maxLines = 1)
                if (timeLeft != null) FText(timeLeft, Fuse.type.caption.tabular(), color = c.onArtMuted, maxLines = 1)
            }
        }
    }
}

/**
 * How long a transfer has left, worked out from how fast [done] has been growing toward [total]
 * (a smoothed rate, so it doesn't jump with every reading). Null until there is a rate to go by.
 */
@Composable
internal fun rememberTimeLeft(done: Long?, total: Long?): String? {
    val clock = remember { longArrayOf(0L, 0L) }
    var rate by remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    LaunchedEffect(done) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val bytes = done ?: return@LaunchedEffect
        val (lastAt, lastBytes) = clock[0] to clock[1]
        if (lastAt > 0 && bytes > lastBytes && now > lastAt) {
            val r = (bytes - lastBytes) * 1000.0 / (now - lastAt)
            rate = if (rate <= 0.0) r else rate + 0.3 * (r - rate)
        } else if (bytes < lastBytes) {
            rate = 0.0
        }
        clock[0] = now
        clock[1] = bytes
    }
    val left = (total ?: return null) - (done ?: return null)
    if (rate <= 0.0 || left <= 0) return null
    val seconds = (left / rate).toLong()
    return when {
        seconds < 60 -> "Under a minute left"
        seconds < 3_600 -> "${(seconds + 59) / 60} min left"
        else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min left"
    }
}

/** A game that just arrived: its tile, its name, and whether it is ready to play. */
@Composable
private fun RecentTile(r: RecentDownload, selected: Boolean, size: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val game = r.game
    val style = LocalGameArt.current
    val tile = style.tileSize(size)
    Column(Modifier.width(tile.width)) {
        if (game != null) {
            GameIconTile(game, selected, size = size, onClick = onClick, onLongClick = onLongClick)
        } else {
            Tile(selected = selected, modifier = Modifier.size(tile), onClick = onClick, onLongClick = onLongClick) {
                GeneratedArt(
                    r.download.title, 0xFF5B6475.toColor(),
                    slot = if (style == GameArtStyle.POSTER) ArtSlot.BOX else ArtSlot.ICON, label = r.download.platformSlug.uppercase(),
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        FText(game?.title ?: r.download.title, Fuse.type.label, color = if (selected) c.text else c.text.copy(alpha = 0.85f), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(if (game != null) FuseIcons.CircleCheck else FuseIcons.Clock, size = 12.dp, tint = if (game != null) c.success else c.textFaint)
            Spacer(Modifier.width(Space.xs))
            FText(if (game != null) "Ready to play" else "Finding it", Fuse.type.caption, color = if (game != null) c.success else c.textMuted, maxLines = 1)
        }
    }
}

/**
 * Before Cartridge is installed: what it is, in a panel lit in its colour with its mark, the three
 * steps from RomM to playing, and the way to get it (the official release from GitHub, through the
 * system's installer). [selected] is the button the controller is on (0 install, 1 about).
 */
@Composable
private fun Pitch(
    release: ReleaseInfo?,
    checked: Boolean,
    installing: Boolean,
    compact: Boolean,
    selected: Int,
    onInstall: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier,
) {
    val c = Fuse.colors
    val brand = io.github.matiyaaa.fuse.ui.shell.home.CARTRIDGE_TINT.toColor()
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Column(modifier.fillMaxWidth().widthIn(max = 1040.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(shape)
                // Cartridge's own surface: its near-black, warmed by its orange where its mark is.
                .background(Brush.linearGradient(listOf(lerp(CartridgeBrand.INK.toColor(), brand, 0.2f), CartridgeBrand.INK.toColor(), CartridgeBrand.DEEP.toColor())))
                .background(Brush.radialGradient(listOf(brand.copy(alpha = 0.22f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(160f, 160f)))
                .border(1.dp, brand.copy(alpha = 0.22f), shape)
                .padding(if (compact) Space.l else Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CartridgeEmblem(if (compact) 72.dp else 104.dp)
                Spacer(Modifier.width(if (compact) Space.l else Space.xl))
                Column(Modifier.weight(1f)) {
                    FText("CARTRIDGE", Fuse.type.overline, color = c.onArtMuted, maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    FText("Your RomM library, on this device", if (compact) Fuse.type.title else Fuse.type.display, color = c.onArt, maxLines = 2)
                    Spacer(Modifier.height(Space.s))
                    FText(
                        "Cartridge connects to your RomM server, and the games you pick land in the right system folders, firmware included. Fuse shows them the moment they arrive.",
                        Fuse.type.body, color = c.onArtMuted, maxLines = 3,
                    )
                    Spacer(Modifier.height(Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        FuseButton(
                            if (installing) "Installing" else "Install Cartridge",
                            selected = selected == 0,
                            onClick = onInstall,
                            kind = ButtonKind.PRIMARY,
                            icon = FuseIcons.Download,
                            // Pressed offline, it says why rather than looking switched off.
                            loading = installing || !checked,
                        )
                        FuseButton("About Cartridge", selected = selected == 1, onClick = onAbout, icon = FuseIcons.External)
                        Spacer(Modifier.width(Space.s))
                        FText(
                            when {
                                release != null -> "${release.name}  ·  official release from GitHub"
                                checked -> "Fuse couldn't reach GitHub. Check your connection"
                                else -> "Finding the latest release"
                            },
                            Fuse.type.caption, color = c.onArtMuted, maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            listOf(
                Triple(FuseIcons.Server, "Connect", "Sign in to your RomM server in Cartridge, once."),
                Triple(FuseIcons.CloudDownload, "Download", "Browse with a controller; games go straight into your ROM folders."),
                Triple(FuseIcons.Gamepad, "Play", "Press Back and the new game is already here, with its art."),
            ).forEachIndexed { i, (icon, title, text) ->
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = if (c.isDark) 0.05f else 0.04f)).padding(Space.l),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Box(Modifier.size(Size.chip).clip(CircleShape).background(brand.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        FuseIcon(icon, size = Size.iconS, tint = lerp(brand, Color.White, 0.2f))
                    }
                    Column(Modifier.weight(1f)) {
                        FText("${i + 1}  $title", Fuse.type.bodyStrong, maxLines = 1)
                        FText(text, Fuse.type.caption, color = c.textMuted, maxLines = 3)
                    }
                }
            }
        }
    }
}

/** The page's outline while Cartridge's state is first read. */
@Composable
private fun CartridgeSkeleton(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(SquircleShape.fraction(0.3f)).skeleton())
            Spacer(Modifier.width(Space.l))
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(Modifier.width(220.dp).height(22.dp).clip(PillShape).skeleton())
                Box(Modifier.width(360.dp).height(14.dp).clip(PillShape).skeleton())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            repeat(5) { Box(Modifier.width(196.dp).height(120.dp).clip(SquircleShape.fraction(0.14f)).skeleton()) }
        }
    }
}

private fun statusLine(s: CartridgeStatus): String = when {
    s.activeDownloads > 0 -> "Downloading ${s.activeDownloads + s.queuedDownloads} ${if (s.activeDownloads + s.queuedDownloads == 1) "game" else "games"}. They'll appear in Fuse on their own."
    s.connected == false -> "Cartridge can't reach your RomM server right now."
    else -> "Find a game, download it, press Back, and it's ready to play here."
}
