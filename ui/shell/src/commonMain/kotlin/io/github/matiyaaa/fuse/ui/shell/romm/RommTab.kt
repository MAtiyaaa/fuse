package io.github.matiyaaa.fuse.ui.shell.romm

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.RommCollectionCard
import io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import io.github.matiyaaa.fuse.ui.shell.store.RommState
import io.github.matiyaaa.fuse.ui.shell.store.RommSystem
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/**
 * Addons, Fuse RomM: a RomM server's library as one more part of Fuse. A slim line says how things
 * stand (only when something needs saying) and holds the few things to do; below it, shelves of the
 * same tiles every Fuse page uses: what is new on the server, what was added lately, its systems (with
 * Fuse's own system art) and its collections. Moving down folds the line and the tabs away, so the
 * shelves have the screen.
 */
@Composable
fun RommContent(app: AppState, active: Boolean, topPadding: Dp) {
    val ops = app.store.romm
    val state by ops.state.collectAsState()
    val recent by ops.recent.collectAsState()
    val fresh by ops.newGames.collectAsState()
    val systems by ops.systems.collectAsState()
    val collections by ops.collections.collectAsState()
    val summary by app.store.transfers.summary.collectAsState()
    val library = rememberSystems(app)
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    if (state.link == RommLink.NOT_SET_UP || (state.link == RommLink.SIGNED_OUT && state.games == 0)) {
        NotSetUp(app, state, focused, topPadding)
        return
    }
    val actions = buildList {
        add(PillAction("Refresh", FuseIcons.RefreshCcw) { ops.refresh() })
        add(PillAction("All Games", FuseIcons.LayoutList) { app.go(Route.RommGames(null, "All games")) })
        if (summary.any) add(PillAction("Downloads", FuseIcons.Download) { app.go(Route.Downloads) })
        if (state.link == RommLink.SIGNED_OUT) add(PillAction("Sign In", FuseIcons.Key) { app.go(Route.RommSetup(pairing = true)) })
        add(PillAction("Settings", FuseIcons.Settings) { app.go(Route.RommSettings) })
    }
    val rows = buildList {
        add("head")
        if (fresh.isNotEmpty()) add("new")
        if (recent.isNotEmpty()) add("recent")
        if (systems.isNotEmpty()) add("systems")
        if (collections.isNotEmpty()) add("collections")
    }
    fun sizeOf(key: String) = when (key) {
        "head" -> actions.size
        "new" -> fresh.size
        "recent" -> recent.size
        "systems" -> systems.size
        "collections" -> collections.size
        else -> 0
    }
    val sel = remember { ShelfSelection() }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "head"
    val col = sel.column(row)
    val list = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.shell.components.ReportScroll(list)

    fun openGame(g: RommGame) = app.go(Route.GameInfo(g.game ?: g.card.id))
    fun gameOptions(g: RommGame) = app.openContextMenu(gameMenu(app, g))
    fun openSystem(s: RommSystem) = app.go(Route.RommGames(s.slug, s.name))
    fun openCollection(c: RommCollectionCard) = app.go(Route.RommGames(null, c.name, collection = c.id))

    LaunchedEffect(fresh.isNotEmpty(), row) {
        // Seen: the new games stop being marked new once their shelf was looked at.
        if (row == "new") ops.markNewSeen()
    }
    PageEffect(focused, row, col) {
        if (!focused) return@PageEffect
        app.hints = when (row) {
            "head" -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(col)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
            "systems", "collections" -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
        }
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { r ->
                if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT)) NavResult.BLOCKED else r
            }
            NavAction.SELECT -> {
                when (row) {
                    "head" -> actions.getOrNull(col)?.run?.invoke()
                    "new" -> fresh.getOrNull(col)?.let(::openGame)
                    "recent" -> recent.getOrNull(col)?.let(::openGame)
                    "systems" -> systems.getOrNull(col)?.let(::openSystem)
                    "collections" -> collections.getOrNull(col)?.let(::openCollection)
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                when (row) {
                    "new" -> fresh.getOrNull(col)?.let(::gameOptions)
                    "recent" -> recent.getOrNull(col)?.let(::gameOptions)
                }
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }
    // A shelf chosen comes to the top (the line and the tabs fold away); back at the line, all is in view.
    LaunchedEffect(sel.row) {
        if (sel.row <= 0) list.animateScrollToItem(0) else list.animateScrollToItem(sel.row)
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("romm")) {
        val compact = maxHeight < 560.dp
        val labels = maxWidth >= 900.dp
        val tile = LocalTileMetrics.current.icon
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = topPadding).fadingEdges(list, top = Space.xl, bottom = Space.xl),
            contentPadding = PaddingValues(top = subTabsRoom() + Space.s, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            item("head") {
                Header(state, actions, if (focused && row == "head") col else -1, compact, labels, Modifier.padding(horizontal = Space.gutter)) { i ->
                    sel.row = 0
                    sel.setColumn("head", i)
                    actions[i].run()
                }
            }
            if ("new" in rows) item("new") {
                GameShelf("New in Your Library", FuseIcons.Sparkles, fresh, if (focused && row == "new") col else -1, tile, "${fresh.size} new", onOpen = ::openGame, onOptions = ::gameOptions)
            }
            if ("recent" in rows) item("recent") {
                GameShelf("Recently Added", FuseIcons.Clock, recent, if (focused && row == "recent") col else -1, tile, null, onOpen = ::openGame, onOptions = ::gameOptions)
            }
            if ("systems" in rows) item("systems") {
                SystemShelf(systems, library, if (focused && row == "systems") col else -1, tile, onOpen = ::openSystem)
            }
            if ("collections" in rows) item("collections") {
                CollectionShelf(collections, if (focused && row == "collections") col else -1, tile, onOpen = ::openCollection)
            }
            if (rows.size == 1) item("empty") {
                Quiet(
                    if (state.syncing != null) "Bringing in your library..." else if (state.link == RommLink.OFFLINE) "The server isn't answering. Your library shows here once it does." else "Nothing on the server yet.",
                    Modifier.padding(horizontal = Space.gutter),
                )
            }
        }
    }
}

private class PillAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/**
 * Fuse RomM's line: the server and how it is reached, with its counts where there is room; the warning
 * band only when something is wrong (away, signed out); then the things to do.
 */
@Composable
private fun Header(state: RommState, actions: List<PillAction>, selected: Int, compact: Boolean, labels: Boolean, modifier: Modifier, onAction: (Int) -> Unit) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RommMark(if (compact) 40.dp else 48.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText("Fuse RomM", if (compact) Fuse.type.bodyStrong else Fuse.type.title, maxLines = 1)
                val route = when (state.route) {
                    NetRoute.LOCAL -> "At home"
                    NetRoute.REMOTE -> "From outside"
                    null -> null
                }
                val synced = state.syncing?.let { p -> "Syncing ${p.label.lowercase()}" + (p.total?.let { t -> " ${p.done} of $t" } ?: "") }
                    ?: state.syncedAt.takeIf { it > 0 }?.let { "synced ${TimeWords.relative(it, now, 0)}" }
                FText(
                    listOfNotNull(route, state.version.takeIf { it.isNotBlank() }?.let { "RomM $it" }, "${state.games} games".takeIf { state.games > 0 }, synced.takeIf { !compact || state.syncing != null }).joinToString("  ·  "),
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { i, a -> Pill(a, selected == i, labels || selected == i, primary = i == 0, busy = i == 0 && state.syncing != null) { onAction(i) } }
            }
        }
        val warning = when (state.link) {
            RommLink.OFFLINE -> "The server isn't answering. Browsing what Fuse kept; downloads wait for it."
            RommLink.SIGNED_OUT -> "RomM no longer accepts Fuse's sign-in. Sign in again to download."
            else -> null
        }
        if (warning != null) {
            val shape = RoundedCornerShape(Fuse.geometry.control)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(c.warning.copy(alpha = 0.12f)).border(1.dp, c.warning.copy(alpha = 0.35f), shape).padding(horizontal = Space.m, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FuseIcon(FuseIcons.CloudOff, size = Size.iconS, tint = c.warning)
                Spacer(Modifier.width(Space.s))
                FText(warning, Fuse.type.label, maxLines = 2)
            }
        }
    }
}

/** Fuse RomM's mark: Fuse's library drawn in its accent, not RomM's own logo (RomM is its own project). */
@Composable
internal fun RommMark(size: Dp) {
    val c = Fuse.colors
    Box(Modifier.size(size).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        FuseIcon(FuseIcons.LibraryBig, size = size * 0.5f, tint = c.accent)
    }
}

@Composable
private fun Pill(a: PillAction, selected: Boolean, label: Boolean, primary: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(
        when {
            selected -> c.text
            primary -> c.accent.copy(alpha = 0.18f)
            else -> c.text.copy(alpha = 0.07f)
        },
        Fuse.motion.tween(Durations.FAST), label = "rommPill",
    )
    val fg = when {
        selected -> c.ink
        primary -> c.accent
        else -> c.text
    }
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (label) Space.m else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(size = 18.dp, color = fg) else FuseIcon(a.icon, size = Size.iconS, tint = fg)
        if (label) {
            Spacer(Modifier.width(Space.s))
            FText(a.label, Fuse.type.label, color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun ShelfTitle(title: String, icon: ImageVector, trailing: String?) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconS, tint = c.textMuted)
        Spacer(Modifier.width(Space.s))
        FText(title, Fuse.type.label, color = c.textMuted, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            FText(trailing, Fuse.type.caption, color = c.textFaint, maxLines = 1)
        }
    }
}

/** A shelf of RomM games, as Fuse tiles: the same art, the same rules, a quiet mark for what isn't here yet. */
@Composable
internal fun GameShelf(
    title: String,
    icon: ImageVector,
    games: List<RommGame>,
    selected: Int,
    tile: Dp,
    trailing: String?,
    onOpen: (RommGame) -> Unit,
    onOptions: (RommGame) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle(title, icon, trailing)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap),
        ) {
            itemsIndexed(games, key = { _, g -> g.romId }) { i, g ->
                RommTile(g, selected == i, tile, onClick = { onOpen(g) }, onLongClick = { onOptions(g) })
            }
        }
    }
}

/** A RomM game's tile: the Fuse tile, with a small mark saying it is on the server, coming, or here. */
@Composable
internal fun RommTile(g: RommGame, selected: Boolean, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    Box(modifier) {
        GameIconTile(g.card, selected, size = size, onClick = onClick, onLongClick = onLongClick)
        val (icon, tint) = when (g.presence) {
            RommPresence.ON_SERVER -> FuseIcons.Cloud to c.text
            RommPresence.QUEUED -> FuseIcons.Hourglass to c.text
            RommPresence.DOWNLOADING -> FuseIcons.Download to c.accent
            RommPresence.INSTALLED, RommPresence.PARTLY_INSTALLED -> null to c.text
        }
        if (icon != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(c.surfaceOverlay),
                contentAlignment = Alignment.Center,
            ) { FuseIcon(icon, size = 13.dp, tint = tint) }
        }
        if (g.isNew) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(c.accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
                FText("NEW", Fuse.type.overline, color = c.onAccent, maxLines = 1)
            }
        }
    }
}

/** The server's systems, drawn with the same system art as Fuse's Systems page. */
@Composable
private fun SystemShelf(systems: List<RommSystem>, library: Map<PlatformId, PlatformCard>, selected: Int, tile: Dp, onOpen: (RommSystem) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle("Systems", FuseIcons.Chip, "${systems.size}")
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap)) {
            itemsIndexed(systems, key = { _, s -> s.slug }) { i, s ->
                Column(Modifier.width(tile)) {
                    val card = systemCard(s, library)
                    if (card != null) {
                        SystemTile(card, selected == i, size = tile, onClick = { onOpen(s) })
                    } else {
                        Box(
                            Modifier.size(tile).clip(RoundedCornerShape(Fuse.geometry.control)).background(Fuse.colors.surface)
                                .then(if (selected == i) Modifier.border(Size.focusStroke, Fuse.colors.focus, RoundedCornerShape(Fuse.geometry.control)) else Modifier)
                                .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(s) },
                            contentAlignment = Alignment.Center,
                        ) { FText(s.name, Fuse.type.label, maxLines = 3, modifier = Modifier.padding(Space.s)) }
                    }
                    Spacer(Modifier.height(Space.xs))
                    FText(
                        if (s.installed > 0) "${s.installed} of ${s.games} here" else "${s.games} games",
                        Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The Systems page's card for a RomM system, or one made from Fuse's catalogue for a system the library hasn't got yet. */
internal fun systemCard(s: RommSystem, library: Map<PlatformId, PlatformCard>): PlatformCard? {
    val id = s.platform ?: return null
    library[id]?.let { return it }
    val p = io.github.matiyaaa.fuse.library.PlatformCatalog.byId(id) ?: return null
    return PlatformCard(p, 0, Art.None, null, false, 0, io.github.matiyaaa.fuse.model.BiosStatus.NotRequired, p.defaultLayout, emptyList())
}

@Composable
private fun CollectionShelf(collections: List<RommCollectionCard>, selected: Int, tile: Dp, onOpen: (RommCollectionCard) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle("Collections", FuseIcons.Layers, "${collections.size}")
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap)) {
            itemsIndexed(collections, key = { _, c -> c.id }) { i, c ->
                Column(Modifier.width(tile)) {
                    Box(
                        Modifier.size(tile).clip(RoundedCornerShape(Fuse.geometry.control))
                            .then(if (selected == i) Modifier.border(Size.focusStroke, Fuse.colors.focus, RoundedCornerShape(Fuse.geometry.control)) else Modifier)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.covers.isNotEmpty()) io.github.matiyaaa.fuse.ui.shell.components.CoverCollage(c.covers, tile)
                        else FuseIcon(FuseIcons.Layers, size = Size.iconL, tint = Fuse.colors.textMuted)
                    }
                    Spacer(Modifier.height(Space.xs))
                    FText(c.name, Fuse.type.label, maxLines = 1)
                    FText("${c.games} games", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun Quiet(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = Space.xxl), contentAlignment = Alignment.Center) {
        FText(text, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 3)
    }
}

/** Fuse RomM on, with no server set up (or signed out with nothing kept): one clear way in. */
@Composable
private fun NotSetUp(app: AppState, state: RommState, focused: Boolean, topPadding: Dp) {
    PageEffect(focused) { if (focused) app.hints = listOf(Hint(HintButton.CONFIRM, if (state.link == RommLink.SIGNED_OUT) "Sign In" else "Set Up"), Hint(HintButton.BACK, "Back")) }
    InputLayer(enabled = focused) { e ->
        if (e.action == NavAction.SELECT) { app.go(Route.RommSetup(pairing = state.link == RommLink.SIGNED_OUT)); NavResult.ACTIVATED } else NavResult.IGNORED
    }
    Column(
        Modifier.fillMaxSize().padding(top = topPadding + subTabsRoom() + Space.l).padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RommMark(64.dp)
        Spacer(Modifier.height(Space.l))
        FText("Your RomM library, inside Fuse", Fuse.type.title, maxLines = 2)
        Spacer(Modifier.height(Space.s))
        FText(
            "Browse your RomM server's games, download them straight into your library, and send your own back. Pair once from RomM; nothing long to type.",
            Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 4,
        )
        Spacer(Modifier.height(Space.xl))
        FuseButton(
            if (state.link == RommLink.SIGNED_OUT) "Sign In" else "Set Up Fuse RomM", selected = focused, icon = FuseIcons.Link, kind = ButtonKind.PRIMARY,
            onClick = { app.go(Route.RommSetup(pairing = state.link == RommLink.SIGNED_OUT)) },
        )
    }
}

/** A RomM game's options: open its page, download it (or what it is missing), or see it in Downloads. */
internal fun gameMenu(app: AppState, g: RommGame): ContextMenuSpec {
    val ops = app.store.romm
    fun queue(what: RommDownloadWhat) {
        app.closeOverlays()
        app.scope.launch {
            val why = ops.download(g.romId, what)
            if (why != null) app.toasts.show(why, io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.WARNING)
            else app.toasts.show("${g.card.title} is on its way", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.SUCCESS, icon = FuseIcons.Download)
        }
    }
    return ContextMenuSpec(
        title = g.card.title,
        subtitle = listOfNotNull(g.card.platformShort, g.card.year?.toString(), io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf(g.sizeBytes).takeIf { g.sizeBytes > 0 }).joinToString("  ·  "),
        art = g.card.art.boxart ?: g.card.art.square,
        accent = g.card.accent,
        actions = buildList {
            add(MenuAction("romm.open", "Open", FuseIcons.Info, onSelect = { app.closeOverlays(); app.go(Route.GameInfo(g.game ?: g.card.id)) }))
            when (g.presence) {
                RommPresence.ON_SERVER -> {
                    add(MenuAction("romm.download", "Download", FuseIcons.Download, onSelect = { queue(RommDownloadWhat.Game) }))
                    add(MenuAction("romm.everything", "Download Everything", FuseIcons.CloudDownload, detail = "With its updates and DLC, where RomM has them", onSelect = { queue(RommDownloadWhat.Everything) }))
                }
                RommPresence.DOWNLOADING, RommPresence.QUEUED -> add(MenuAction("romm.downloads", "See in Downloads", FuseIcons.Download, onSelect = { app.closeOverlays(); app.go(Route.Downloads) }))
                else -> add(MenuAction("romm.content", "Download Missing Content", FuseIcons.CloudDownload, onSelect = { queue(RommDownloadWhat.Everything) }))
            }
        },
    )
}
