package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.isSelected
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.integrations.ports.OpenPorts
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.madeLine
import io.github.matiyaaa.fuse.ui.shell.app.pickPortGame
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.word
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.downloads.ActionPill
import io.github.matiyaaa.fuse.ui.shell.downloads.TopAction
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortsState
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice
import io.github.matiyaaa.fuse.ui.shell.store.PortGameCopy
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which Android ports the page lists. */
internal enum class PortFilter(val label: String) {
    LIBRARY("In Your Library"),
    INSTALLED("Installed"),
    CHECKED("Checked by Fuse"),
    ALL("Everything"),
}

/**
 * Ports on Android: games rebuilt for Android, each an app its project publishes. A slim bar of the
 * page's buttons and lists, then the ports themselves, those for games in the library first, each
 * with a mark for how it stands here. Choosing one opens its own page ([AndroidPortScreen]), where
 * installing, attaching and playing are; Options has the same at once.
 *
 * Laid out for the screen it is on. A short one (a two-screen handheld's upper screen, or its lower
 * one with the menus there) lists each port as a slim card (its box art, its game, the port, how it
 * stands), so three rows show once the bar has scrolled away and the tabs above have folded. A tall
 * one shows box art under the chosen port told large; past the first row that folds to one line and
 * the art shrinks a little, so three rows of covers fit there too. The bar is the grid's first row:
 * moving down (or a finger scrolling) takes it away, and moving back up brings it back.
 */
@Composable
internal fun AndroidPortsTab(app: AppState, active: Boolean, topPadding: Dp) {
    val ops = app.store.ports
    val state by ops.android.collectAsState()
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    LaunchedEffect(Unit) { ops.open() }
    val view = io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState(app.navigator, "ports.android") { AndroidPortsView() }
    val forLibrary = state.ports.count { it.here || it.elsewhere.isNotEmpty() || it.bringing != null }
    // The library's own ports first, until the person picks another list.
    val filter = view.filter ?: if (forLibrary > 0 || !state.loaded) PortFilter.LIBRARY else PortFilter.ALL
    val shown = remember(state, filter, view.query, view.system) { shownPorts(state, filter, view.query, view.system) }
    val actions = listOf(
        TopAction(if (view.query.isEmpty()) "Search" else "\u201C${view.query}\u201D", FuseIcons.Search) {
            app.textInput = TextInputSpec("Search ports", view.query, doneLabel = "Search", message = "A game, a port or a system") { q -> view.query = q.trim(); view.grid.index = 0 }
        },
        TopAction(view.system?.let { PlatformCatalog.byId(PlatformId(it))?.shortName ?: it } ?: "Every System", FuseIcons.Filter) {
            app.pickPortSystem(state, view.system) { view.system = it; view.grid.index = 0 }
        },
        TopAction(if (state.refreshing) "Looking..." else "Look Again", FuseIcons.RefreshCcw) { ops.refresh() },
        TopAction("Settings", FuseIcons.Settings) { app.go(Route.Settings("addons")) },
    )
    val filters = PortFilter.entries
    val counts = counts(state)
    // The bar is one line: the buttons, then the lists. Moving onto a list shows it, as tabs do.
    val barSize = actions.size + filters.size
    val inBar = view.inBar || shown.isEmpty()
    view.barIndex = view.barIndex.coerceIn(0, barSize - 1)
    val grid = view.grid
    grid.clamp(shown.size)
    val current = if (!inBar) shown.getOrNull(grid.index) else null
    // The grid's columns as last laid out, for moving through it (set by the layout, read by input).
    val columns = remember { intArrayOf(1) }
    fun open(v: AndroidPortView) = app.go(Route.AndroidPort(v.port.id))

    PageEffect(focused, inBar, view.barIndex, current?.port?.id, current?.job, filter) {
        if (!focused) return@PageEffect
        app.hints = when {
            inBar && view.barIndex < actions.size -> listOf(Hint(HintButton.CONFIRM, actions[view.barIndex].label), Hint(HintButton.BACK, "Back"))
            inBar -> listOfNotNull(Hint(HintButton.DPAD, "Lists"), Hint(HintButton.CONFIRM, "Show").takeIf { shown.isNotEmpty() }, Hint(HintButton.BACK, "Back"))
            current != null -> listOfNotNull(
                Hint(HintButton.CONFIRM, "View"),
                next(current)?.let { Hint(HintButton.OPTIONS, it.first) } ?: Hint(HintButton.OPTIONS, "Options"),
                Hint(HintButton.BACK, "Back"),
            )
            else -> listOf(Hint(HintButton.BACK, "Back"))
        }
    }
    // The room (and the screen above, with the menus below) takes the chosen port's game.
    PageEffect(focused, current?.port?.id, current?.cover) {
        if (!focused) return@PageEffect
        val v = current
        app.hero = v?.let { portRoom(it) }
    }
    InputLayer(enabled = focused) { e ->
        if (inBar) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> if (view.barIndex > 0) { view.barIndex--; view.barIndex.takeIf { it >= actions.size }?.let { view.filter = filters[it - actions.size]; grid.index = 0 }; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (view.barIndex < barSize - 1) { view.barIndex++; view.barIndex.takeIf { it >= actions.size }?.let { view.filter = filters[it - actions.size]; grid.index = 0 }; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (shown.isNotEmpty()) { view.inBar = false; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> {
                    if (view.barIndex < actions.size) actions[view.barIndex].run()
                    else if (shown.isNotEmpty()) view.inBar = false
                    NavResult.ACTIVATED
                }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.SELECT -> { current?.let(::open); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.portMenu(it)) }; NavResult.ACTIVATED }
            else -> grid.move(e.action, shown.size, columns[0]).let { r ->
                when {
                    r == NavResult.IGNORED && e.action == NavAction.UP -> {
                        view.inBar = true
                        // Back on the bar at the list being shown.
                        view.barIndex = actions.size + filters.indexOf(filter).coerceAtLeast(0)
                        NavResult.MOVED
                    }
                    r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT) -> NavResult.BLOCKED
                    else -> r
                }
            }
        }
    }
    val gridState = rememberLazyGridState()
    // Another list starts at its first port, with the bar in view.
    LaunchedEffect(filter, view.query, view.system) { gridState.scrollToItem(0) }
    // The bar is item 0; the first row keeps it in view, every row after it lets it go.
    FollowSelection(
        gridState,
        { if (inBar || grid.index < columns[0]) 0 else grid.index + 1 },
        anchor = 0.04f,
        enabled = { true },
    )
    // Past the bar (by the controller or a finger), everything above the ports folds away.
    val scrolled by remember(gridState) { androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > FOLD_AFTER_PX } }
    val folded = shown.isNotEmpty() && !inBar && scrolled
    val fold by io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat(if (folded) 1f else 0f, Fuse.motion.followSpring(), label = "ports fold")
    // The Addons tabs above fold with it, into their small pill.
    val tabs = io.github.matiyaaa.fuse.ui.shell.components.LocalSubTabs.current
    LaunchedEffect(tabs, folded) { tabs?.collapsed = folded }
    androidx.compose.runtime.DisposableEffect(tabs) { onDispose { tabs?.collapsed = false } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("android-ports")) {
        // A short screen lists ports as slim cards; a tall one as box art under a stage. A narrow one
        // (a second screen held upright, a phone) takes the cards too: three covers across it are each
        // so large that one row is all it shows.
        val short = maxHeight < SHORT_PAGE || maxWidth < CARDS_BELOW_WIDTH
        val narrow = maxWidth < 600.dp
        val gutter = if (narrow || short) Space.gutterCompact else Space.gutter
        val gap = if (short) Space.s else Space.m
        val room = maxWidth - gutter * 2
        val top = topPadding + subTabsRoom() * (1f - fold)
        Column(Modifier.fillMaxSize().padding(top = top)) {
            if (!short) {
                // The chosen port told large; past the first row, one line.
                val stageH = androidx.compose.ui.unit.lerp(STAGE_TALL, STAGE_FOLDED, fold)
                Box(Modifier.fillMaxWidth().height(stageH).padding(horizontal = gutter), contentAlignment = Alignment.BottomStart) {
                    AndroidPortStage(shown.getOrNull(grid.index).takeIf { state.loaded && !inBar } ?: shown.firstOrNull().takeIf { state.loaded }, compact = fold > 0.5f)
                }
            }
            // Cards on a short screen; covers on a tall one, shrinking as the stage folds (a fifth at most).
            val cardW = if (short) {
                val cols = ((room + gap) / (CARD_MIN + gap)).toInt().coerceIn(1, 4)
                columns[0] = cols
                (room - gap * (cols - 1)) / cols
            } else {
                val base = 132.dp * (1f - TILE_SHRINK * fold)
                val cols = ((room + gap) / (base + gap)).toInt().coerceAtLeast(3)
                columns[0] = cols
                (room - gap * (cols - 1)) / cols
            }
            val cols = columns[0]
            when {
                !state.loaded -> Box(Modifier.padding(horizontal = gutter, vertical = Space.l)) { Waiting(short) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    state = gridState,
                    // Faded out above the hints, never running under them.
                    modifier = Modifier.weight(1f).fadingEdges(gridState, top = Space.l, bottom = Size.hintHeight + Space.l).dragScroll(gridState),
                    contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.s, bottom = Size.hintHeight + Space.xl),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(if (short) gap else gap + Space.s),
                ) {
                    item("bar", span = { GridItemSpan(maxLineSpan) }) {
                        PortsBar(
                            actions, filters, counts, filter,
                            selected = if (focused && inBar) view.barIndex else -1,
                            wide = !narrow && !short,
                            modifier = Modifier.padding(bottom = if (short) Space.xs else Space.s),
                            onAction = { i -> app.focusZone = FocusZone.CONTENT; view.inBar = true; view.barIndex = i; actions[i].run() },
                            onFilter = { f -> app.focusZone = FocusZone.CONTENT; view.inBar = true; view.barIndex = actions.size + filters.indexOf(f); view.filter = f; grid.index = 0 },
                        )
                    }
                    if (shown.isEmpty()) {
                        item("empty", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = Space.l)) { Empty(filter, view.query, state.ports.size) } }
                    }
                    for ((i, v) in shown.withIndex()) {
                        item(key = v.port.id) {
                            val selected = isSelected { focused && !inBar && grid.index == i }
                            val tap = {
                                app.focusZone = FocusZone.CONTENT
                                view.inBar = false
                                // A first tap chooses (the card or stage tells it); a second opens its page.
                                if (grid.index == i) open(v) else grid.index = i
                            }
                            val hold = {
                                view.inBar = false
                                grid.index = i
                                app.openContextMenu(app.portMenu(v))
                            }
                            if (short) PortCard(app, v, cardW, selected, onClick = tap, onLongClick = hold)
                            else AndroidPortTile(app, v, cardW, selected = selected, onClick = tap, onLongClick = hold)
                        }
                    }
                    if (shown.isNotEmpty()) item("credit", span = { GridItemSpan(maxLineSpan) }) { Credit() }
                }
            }
        }
    }
}

/** Where the Android Ports page was: its list, search and system, and where the controller was. */
@androidx.compose.runtime.Stable
internal class AndroidPortsView {
    var filter by mutableStateOf<PortFilter?>(null)
    var query by mutableStateOf("")
    var system by mutableStateOf<String?>(null)
    var inBar by mutableStateOf(false)
    var barIndex by mutableIntStateOf(0)
    val grid = GridSelection()
}

/** A port's room: its game's (the library's game when it has one, so the screen above shows it), else its box art, soft. */
private fun portRoom(v: AndroidPortView): io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource {
    val game = (v.attachedTo.firstOrNull() ?: v.games.firstOrNull())?.id
    return io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource(
        id = game ?: "port:${v.port.id}",
        model = v.cover,
        accent = Color(0xFF7C8CF8),
        blurred = true,
    )
}

/**
 * The page's one line: Search, the system, Look Again and Settings, then the lists (In Your Library,
 * Installed, Checked by Fuse, Everything) with how many each holds. Scrolls sideways where it runs
 * past the screen, keeping the chosen one in view.
 */
@Composable
private fun PortsBar(
    actions: List<TopAction>,
    filters: List<PortFilter>,
    counts: Map<PortFilter, Int>,
    active: PortFilter,
    selected: Int,
    wide: Boolean,
    modifier: Modifier,
    onAction: (Int) -> Unit,
    onFilter: (PortFilter) -> Unit,
) {
    val scroll = rememberScrollState()
    val c = Fuse.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        actions.forEachIndexed { i, a ->
            val chosen = selected == i
            // Search and the system name what they hold; the rest are icons until chosen, or on a wide screen.
            ActionPill(a, chosen, label = wide || chosen || i < 2) { onAction(i) }
        }
        Box(Modifier.padding(horizontal = Space.xs).size(width = 1.dp, height = 20.dp).background(c.hairlineStrong))
        io.github.matiyaaa.fuse.ui.designsystem.components.FilterPills(
            filters.map { it.label }, filters.indexOf(active), { i -> onFilter(filters[i]) },
            focused = selected >= actions.size,
            counts = filters.map { f -> counts[f]?.takeIf { it > 0 } },
        )
    }
    // The chosen one kept in view as the controller moves along the bar.
    LaunchedEffect(selected) { if (selected >= actions.size) scroll.animateScrollTo(scroll.maxValue) else if (selected in 0..1) scroll.animateScrollTo(0) }
}

/**
 * A port as a slim card, for a short screen: its game's box art at the start, the game's name, the
 * port and its system under it, and the mark for how it stands here at the end; a download's bar
 * along its foot. Ports for games not here are drawn a little quieter.
 */
@Composable
private fun PortCard(app: AppState, v: AndroidPortView, width: Dp, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val model = portArt(app, v)
    val quiet = !v.here && !v.installed && v.elsewhere.isEmpty() && v.bringing == null
    val shape = RoundedCornerShape(io.github.matiyaaa.fuse.ui.designsystem.theme.Radius.l)
    Tile(
        selected = selected, label = "${v.port.game}, ${v.port.name}",
        modifier = Modifier.width(width).height(CARD_H),
        shape = shape, cornerFraction = CARD_CORNER, showSpark = false, maxGrow = Space.xs,
        onClick = onClick, onLongClick = onLongClick,
    ) {
        Box(Modifier.fillMaxSize().background(c.surfaceRaised.copy(alpha = if (c.isDark) 0.78f else 0.9f)))
        Row(Modifier.fillMaxSize().padding(Space.s - Space.xxs), verticalAlignment = Alignment.CenterVertically) {
            val coverH = CARD_H - (Space.s - Space.xxs) * 2
            Box(Modifier.height(coverH).aspectRatio(Aspect.BOX).clip(RoundedCornerShape(io.github.matiyaaa.fuse.ui.designsystem.theme.Radius.s))) {
                Artwork(
                    model,
                    Modifier.fillMaxSize().then(if (quiet) Modifier.alpha(QUIET_ALPHA) else Modifier),
                    contentScale = ContentScale.Crop, focusY = 0.3f,
                    fallback = { GeneratedArt(v.port.game, c.accent, slot = ArtSlot.ICON, showText = false) },
                )
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
                FText(v.port.game, Fuse.type.bodyStrong, color = if (quiet && !selected) c.textMuted else c.text, maxLines = 1)
                val systems = v.port.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.shortName }.distinct().joinToString(" / ")
                FText(listOfNotNull(v.port.name, systems.ifEmpty { null }).joinToString("  \u00B7  "), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            portMark(v)?.let { (icon, tint) ->
                Spacer(Modifier.width(Space.s))
                Box(Modifier.size(Size.badge).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    FuseIcon(icon, size = Size.badgeIcon, tint = tint)
                }
            }
        }
        val job = v.job
        if (job != null && job.active) {
            val pr = (job as? PortJob.Downloading)?.progress
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(c.text.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(pr ?: 0.3f).background(c.accent))
            }
        }
    }
}

/** The box art a port is drawn with: the library's own art where its game is here, else another copy's, the listing's, or one found by name. */
@Composable
internal fun portArt(app: AppState, v: AndroidPortView): Any? {
    val game = (v.attachedTo.firstOrNull() ?: v.games.firstOrNull())?.id
    val media by remember(game) { game?.let { app.store.media.media(io.github.matiyaaa.fuse.model.MediaOwner.OfGame(it)) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val own = media?.let(io.github.matiyaaa.fuse.ui.shell.store.Art::from)
    val elsewhereArt = (v.bringing ?: v.elsewhere.firstOrNull())?.art?.let { it.boxart ?: it.square }
    return own?.boxart ?: elsewhereArt ?: v.cover ?: own?.square ?: own?.icon
}

/** The mark for how a port stands here, with its colour; null when there is nothing to say. */
@Composable
internal fun portMark(v: AndroidPortView): Pair<ImageVector, Color>? {
    val c = Fuse.colors
    val job = v.job
    return when {
        job is PortJob.Failed -> FuseIcons.CircleX to c.warning
        job != null && job.active -> FuseIcons.Download to c.accent
        v.installed && v.attachedTo.isNotEmpty() -> FuseIcons.Play to c.success
        v.installed && v.games.isNotEmpty() -> FuseIcons.Link to c.warning
        v.installed -> FuseIcons.Check to c.success
        v.bringing != null -> FuseIcons.CloudDownload to c.accent
        v.here -> FuseIcons.Gamepad to c.accent
        v.elsewhere.isNotEmpty() -> FuseIcons.Cloud to c.textMuted
        else -> null
    }
}

/** A page shorter than this lists ports as cards (a two-screen handheld's screens). */
private val SHORT_PAGE = 480.dp

/** Narrower than this, ports are cards whatever the height. */
private val CARDS_BELOW_WIDTH = 560.dp

/** A port card: its height, its narrowest, and its corners. */
private val CARD_H = 56.dp
private val CARD_MIN = 252.dp
private const val CARD_CORNER = 0.22f

/** A tall page's stage, open and folded, and how much its covers shrink as it folds. */
private val STAGE_TALL = 120.dp
private val STAGE_FOLDED = 36.dp
private const val TILE_SHRINK = 0.2f

/** Scrolled this far, the page counts as past its top. */
private const val FOLD_AFTER_PX = 12

/** The ports [filter], [query] and [system] leave, those for the library's games first. */
private fun shownPorts(state: AndroidPortsState, filter: PortFilter, query: String, system: String?): List<AndroidPortView> {
    val q = query.lowercase()
    return state.ports.filter { v ->
        val p = v.port
        when (filter) {
            PortFilter.LIBRARY -> v.here || v.elsewhere.isNotEmpty() || v.bringing != null
            PortFilter.INSTALLED -> v.installed || v.job != null
            PortFilter.CHECKED -> p.checked
            PortFilter.ALL -> true
        } && (system == null || system in p.systems) &&
            (q.isEmpty() || (listOf(p.game, p.name) + p.titles + p.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.name }).any { it.lowercase().contains(q) })
    }.sortedWith(
        compareBy(
            // This device's games first, then those on RomM or another device.
            { if (it.here) 0 else if (it.elsewhere.isNotEmpty() || it.bringing != null) 1 else 2 },
            { if (it.installed) 0 else 1 },
            { if (it.port.verified != null) 0 else if (it.port.checked) 1 else 2 },
            { it.port.game.lowercase() },
            { it.port.status.ordinal },
        ),
    )
}

private fun counts(state: AndroidPortsState): Map<PortFilter, Int> = mapOf(
    PortFilter.LIBRARY to state.ports.count { it.here || it.elsewhere.isNotEmpty() || it.bringing != null },
    PortFilter.INSTALLED to state.ports.count { it.installed },
    PortFilter.CHECKED to state.ports.count { it.port.checked },
    PortFilter.ALL to state.ports.size,
)

/**
 * The chosen port, told above the box art on a tall screen: its game's systems, year, maker and
 * kind over its name, the port that rebuilt it, how it stands here and whose word its entry is.
 * [compact] (folded, past the first row) keeps it to one line: the game, and how it stands.
 */
@Composable
private fun AndroidPortStage(v: AndroidPortView?, compact: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Swap(
        targetState = v to compact,
        contentKey = { (pv, fold) -> pv?.port?.id to fold },
        transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) },
        contentAlignment = Alignment.BottomStart,
        label = "androidPortStage",
    ) { (pv, fold) ->
        if (pv == null) return@Swap
        val p = pv.port
        val (status, tone) = status(pv)
        if (fold) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                FText(p.game, Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Box(Modifier.size(Size.dot).clip(CircleShape).background(toneOf(tone)))
                FText(status, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            return@Swap
        }
        val systems = p.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.shortName }.distinct().joinToString(" / ")
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            // The game: its systems, year, maker and kind; then the port that rebuilt it.
            FText(
                listOfNotNull(systems.ifEmpty { null }, p.year?.toString(), p.publisher, p.genre?.let(::genreWord)).joinToString("  \u00B7  ").uppercase(),
                Fuse.type.overline, color = c.textMuted, maxLines = 1,
            )
            FText(p.game, Fuse.type.display, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FText(p.name, Fuse.type.label, color = c.text, maxLines = 1)
                Box(Modifier.size(Size.dot).clip(CircleShape).background(toneOf(tone)))
                FText(status, Fuse.type.label, color = if (tone == Tone.QUIET) c.textMuted else c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                when {
                    p.verified != null -> Chip(FuseIcons.ShieldCheck, "Verified", c.success)
                    p.checked -> Chip(FuseIcons.ShieldCheck, "Checked", c.accent)
                    else -> Chip(FuseIcons.ShieldAlert, "Unchecked", c.warning)
                }
            }
            (pv.job as? PortJob.Downloading)?.progress?.let { pr -> Box(Modifier.widthIn(max = 420.dp)) { Progress(pr) } }
        }
    }
}

@Composable
internal fun toneOf(tone: Tone): Color {
    val c = Fuse.colors
    return when (tone) {
        Tone.GOOD -> c.success
        Tone.WAIT -> c.accent
        Tone.NEEDS -> c.warning
        Tone.QUIET -> c.textMuted
    }
}

/**
 * A port in the grid: its game's box art as the library draws it (the library's own art where the
 * game is here, else another copy's, the listing's, or one drawn from its name), a mark in the corner
 * for how it stands (playing your game, to attach, your game is here, a problem), a download's bar
 * along the foot, and the port's name under it. Ports for games not here are drawn a little quieter.
 */
@Composable
private fun AndroidPortTile(app: AppState, v: AndroidPortView, width: Dp, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val model = portArt(app, v)
    val quiet = !v.here && !v.installed && v.elsewhere.isEmpty() && v.bringing == null
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Size.sparkClearance)) {
        Tile(selected = selected, label = v.port.game, modifier = Modifier.width(width).aspectRatio(Aspect.BOX), onClick = onClick, onLongClick = onLongClick) {
            Artwork(
                model,
                Modifier.fillMaxSize().then(if (quiet) Modifier.alpha(QUIET_ALPHA) else Modifier),
                contentScale = ContentScale.Crop,
                focusY = 0.3f,
                fallback = { GeneratedArt(v.port.game, c.accent, slot = ArtSlot.BOX) },
            )
            val job = v.job
            val mark = portMark(v).takeUnless { job != null && job.active }
            if (mark != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(Space.xs).size(Size.badge).clip(CircleShape).background(c.ink.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) { FuseIcon(mark.first, size = Size.badgeIcon, tint = mark.second) }
            }
            if (job != null && job.active) {
                val pr = (job as? PortJob.Downloading)?.progress
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Space.s).height(4.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(pr ?: 0.3f).clip(CircleShape).background(c.accent))
                }
            }
        }
        FText(v.port.name, Fuse.type.caption, color = if (selected) c.text else c.textMuted, maxLines = 1, modifier = Modifier.padding(horizontal = Space.xs))
    }
}

/** A port whose game isn't anywhere of yours: drawn a little quieter, still there to read about. */
private const val QUIET_ALPHA = 0.62f

/** A genre as the catalogue names it ("action-adventure"), as Fuse shows it ("Action adventure"). */
internal fun genreWord(genre: String): String = when (genre) {
    "rpg" -> "RPG"
    else -> genre.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

/** The list being empty, said for the list chosen. */
@Composable
private fun Empty(filter: PortFilter, query: String, all: Int) {
    when {
        query.isNotEmpty() -> Note(FuseIcons.Search, "Nothing matches “$query”", "Search another game, port or system, or look in Everything.")
        filter == PortFilter.LIBRARY -> Note(FuseIcons.Boxes, "No port plays a game in your library yet", "Everything lists the $all ports Fuse knows. A game's Play With offers its own.")
        filter == PortFilter.INSTALLED -> Note(FuseIcons.Download, "No port installed yet", "Install one here, or from a game's Play With.")
        filter == PortFilter.CHECKED -> Note(FuseIcons.ShieldCheck, "No port checked by Fuse here", "Everything lists the ports Open Ports Games lists too.")
        else -> Note(FuseIcons.Boxes, "No ports in the lists yet", "The lists inside Fuse have none for now; newer ones bring them here.")
    }
}

/** Where the listings come from, as their licence asks. */
@Composable
private fun Credit() {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s).widthIn(max = 960.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
        FuseIcon(FuseIcons.Info, size = Size.iconS, tint = c.textFaint)
        Spacer(Modifier.width(Space.s))
        FText(
            "${OpenPorts.CREDIT}. Fuse checked the ones marked Checked against each project's own source, and each night reads the APK it would install of the ones marked Verified: the app listed, signed, made for this kind of device. The rest are as listed. Each app comes from its own project's releases, and takes your game's files itself.",
            Fuse.type.caption, color = c.textFaint, maxLines = 5,
        )
    }
}

/** How [v] stands here, in a few words. */
internal fun status(v: AndroidPortView): Pair<String, Tone> {
    val job = v.job
    val games = v.games.map { it.title }
    val bringing = v.bringing
    val away = v.elsewhere.firstOrNull()
    fun some(titles: List<String>) = titles.first() + if (titles.size > 1) " and ${titles.size - 1} more" else ""
    return when {
        job is PortJob.Downloading -> (job.progress?.let { "Downloading, ${(it * 100).toInt()}%" } ?: "Downloading...") to Tone.WAIT
        job is PortJob.Installing -> (if (job.waitingTurn) "Waiting for another install to finish..." else "Android is asking you to confirm...") to Tone.WAIT
        job is PortJob.Failed -> job.message to Tone.NEEDS
        v.installed && v.attachedTo.isNotEmpty() -> listOfNotNull("Installed", v.installedVersion, "plays ${some(v.attachedTo.map { it.title })}").joinToString("  ·  ") to Tone.GOOD
        bringing != null -> "Getting your copy from ${bringing.from}  ·  ${if (v.installed) "it plays once it's in" else "install the port to play it"}" to Tone.WAIT
        v.installed && games.isNotEmpty() -> "Installed  ·  attach it to ${some(games)}" to Tone.NEEDS
        v.installed && away != null -> "Installed  ·  your ${v.port.game} is ${where(v.elsewhere)}. Get it here to play" to Tone.NEEDS
        v.installed -> listOfNotNull("Installed", v.installedVersion).joinToString("  ·  ") to Tone.GOOD
        !v.port.installable -> "Get it from its project's page" to Tone.QUIET
        games.isNotEmpty() -> "Not installed  ·  for your ${some(games)}" to Tone.QUIET
        away != null -> "Not installed  ·  your copy is ${where(v.elsewhere)}" to Tone.QUIET
        else -> "Not installed" to Tone.QUIET
    }
}

/** Where [copies] of a game are, in a few words: "on RomM", "on Gaming PC and RomM". */
internal fun where(copies: List<PortGameCopy>): String {
    val places = copies.map { it.from }.distinct()
    return "on " + when (places.size) {
        0 -> "another device"
        1 -> places[0]
        2 -> "${places[0]} and ${places[1]}"
        else -> "${places[0]} and ${places.size - 1} other places"
    }
}

/** What Confirm does for [v] now, with its icon; null when there is nothing to do but look. */
internal fun next(v: AndroidPortView): Pair<String, ImageVector>? {
    val job = v.job
    return when {
        job != null && job.active -> "Stop" to FuseIcons.CircleX
        job is PortJob.Failed -> "Try Again" to FuseIcons.RefreshCcw
        !v.installed && !v.port.installable -> "Project Page" to FuseIcons.External
        !v.installed -> "Install" to FuseIcons.Download
        v.attachedTo.isNotEmpty() -> "Play" to FuseIcons.Play
        v.games.isNotEmpty() -> "Attach" to FuseIcons.Link
        v.bringing != null -> "Downloads" to FuseIcons.HardDriveDownload
        v.elsewhere.isNotEmpty() -> "Get the Game" to FuseIcons.CloudDownload
        else -> "Open" to FuseIcons.External
    }
}

internal fun AppState.doNext(v: AndroidPortView) {
    val ops = store.ports
    val job = v.job
    when {
        job != null && job.active -> ops.cancel(v.port.id)
        job is PortJob.Failed -> { ops.cancel(v.port.id); installPort(v) }
        !v.installed && !v.port.installable -> platform.openUrl(v.port.repo)
        !v.installed -> installPort(v)
        v.attachedTo.isNotEmpty() -> playAttached(v.attachedTo.first())
        v.games.isNotEmpty() -> attachPort(v)
        v.bringing != null -> go(Route.Downloads)
        v.elsewhere.isNotEmpty() -> offerGame(v, installing = false)
        else -> if (!ops.openAndroid(v.port.id)) toasts.show("${v.port.name} can't be opened.", ToastKind.WARNING)
    }
}

/**
 * Asks whether to bring [v]'s game here from where it is (RomM, or another of the household's
 * devices), and from where when it is in more than one place; [installing]: as the port installs.
 * Once it is in, the port plays it.
 */
internal fun AppState.offerGame(v: AndroidPortView, installing: Boolean) {
    val copies = v.elsewhere
    if (copies.isEmpty() || v.bringing != null) return
    val p = v.port
    val lead = if (installing) "${p.name} plays your own copy of ${p.game}, and this device hasn't got one." else "${p.game} isn't on this device yet."
    val only = copies.singleOrNull()
    if (only != null) {
        val size = only.sizeBytes.takeIf { it > 0 }?.let { " (${bytesText(it)})" }.orEmpty()
        val away = if (only.place == CopyPlace.DEVICE && !only.online) " ${only.from} is away now, so it comes when it's back." else ""
        confirm = ConfirmSpec(
            if (installing) "Get ${p.game} too?" else "Get ${p.game}?",
            "$lead Yours is on ${only.from}. Fuse brings it here$size, and ${p.name} plays it once it's in.$away",
            "Get It",
        ) { offerFrom(v, only) }
        return
    }
    choice = ChoiceSpec(
        icon = FuseIcons.CloudDownload,
        title = "Get ${p.game} from where?",
        message = "$lead Yours is ${where(copies)}. Fuse brings it here from the one you choose, and ${p.name} plays it once it's in.",
        options = copies.map { c -> copyRow(c) { choice = null; offerFrom(v, c) } } +
            MenuAction("later", "Not Now", FuseIcons.Close, section = "", onSelect = { choice = null }),
    )
}

/** One place a port's game is, as a row: RomM or a device, its size, and whether the device is on. */
internal fun copyRow(c: PortGameCopy, onSelect: () -> Unit): MenuAction = MenuAction(
    "copy.${c.place.name}.${c.id.value}",
    if (c.place == CopyPlace.ROMM) "From RomM" else "From ${c.from}",
    if (c.place == CopyPlace.ROMM) FuseIcons.Server else FuseIcons.Monitor,
    detail = listOfNotNull(
        c.title,
        c.sizeBytes.takeIf { it > 0 }?.let(::bytesText),
        if (c.place == CopyPlace.DEVICE && !c.online) "Away now: it comes when it's back" else null,
    ).joinToString("  ·  "),
    onSelect = onSelect,
)

/** Installs [v], for its one game in the library, or for the copy the person picks when there are several. */
internal fun AppState.installPort(v: AndroidPortView) {
    fun go(game: PortGameChoice?) {
        store.ports.installAndroid(v.port.id, game?.id)
        toasts.show(
            if (game != null) "Installing ${v.port.name}. Android asks you to confirm, then ${game.title} plays with it" else "Installing ${v.port.name}. Android asks you to confirm",
            ToastKind.INFO, icon = FuseIcons.Download, durationMs = 6_000L,
        )
    }
    if (v.games.size <= 1) {
        go(v.games.firstOrNull())
        // Its game on RomM or another device, not here: offered as it installs.
        if (v.games.isEmpty() && v.attachedTo.isEmpty()) offerGame(v, installing = true)
        return
    }
    choice = ChoiceSpec(
        icon = FuseIcons.Boxes,
        title = "Which ${v.port.game} plays with it?",
        message = "Your library has more than one copy. Play on the one you choose starts ${v.port.name}.",
        options = v.games.map { g ->
            MenuAction("g.${g.id.value}", g.title, FuseIcons.Gamepad, detail = g.system, onSelect = { choice = null; go(g) })
        },
    )
}

/** Attaches installed [v] to its game, or to the one the person picks. */
internal fun AppState.attachPort(v: AndroidPortView) {
    val appId = v.appId ?: return
    fun attach(game: PortGameChoice) {
        scope.launch {
            val problem = store.ports.attach(appId, game.id)
            if (problem != null) toasts.show(problem, ToastKind.WARNING)
            else toasts.show("${game.title} plays with ${v.port.name} from now on", ToastKind.SUCCESS, icon = FuseIcons.Link)
        }
    }
    val only = v.games.singleOrNull()
    if (only != null) {
        attach(only)
        return
    }
    if (v.games.isEmpty()) {
        pickPortGame(v.port.name, v.packageName.orEmpty()) { attach(it) }
        return
    }
    choice = ChoiceSpec(
        icon = FuseIcons.Link,
        title = "Which game does ${v.port.name} play?",
        message = "Play on the game you choose starts it from now on.",
        options = v.games.map { g ->
            MenuAction("g.${g.id.value}", g.title, FuseIcons.Gamepad, detail = g.system, onSelect = { choice = null; attach(g) })
        } + MenuAction("other", "Another Game", FuseIcons.Search, section = "", onSelect = {
            choice = null
            pickPortGame(v.port.name, v.packageName.orEmpty()) { attach(it) }
        }),
    )
}

/** Plays [game] as the library plays it: with its port, its time its own. */
private fun AppState.playAttached(game: PortGameChoice) {
    scope.launch {
        val card = store.library.games(GameQuery(includeHidden = true)).first().firstOrNull { it.id == game.id }
        if (card == null) toasts.show("${game.title} isn't in the library any more.", ToastKind.WARNING) else play(card)
    }
}

/** Every system the ports play, to narrow the list to one. */
private fun AppState.pickPortSystem(state: AndroidPortsState, current: String?, onPick: (String?) -> Unit) {
    val bySystem = state.ports.flatMap { v -> v.port.systems.map { it to v } }.groupBy({ it.first }, { it.second })
    val systems = bySystem.keys.mapNotNull { id -> PlatformCatalog.byId(PlatformId(id))?.let { it to bySystem[id].orEmpty().size } }.sortedBy { it.first.name.lowercase() }
    choice = ChoiceSpec(
        icon = FuseIcons.Filter,
        title = "Which system?",
        message = "Ports of that system's games only.",
        options = listOf(
            MenuAction("all", "Every System", FuseIcons.Layers, trailing = Trailing.Check(current == null), onSelect = { choice = null; onPick(null) }),
        ) + systems.map { (p, n) ->
            MenuAction("s.${p.id.value}", p.name, null, detail = "$n ${if (n == 1) "port" else "ports"}", trailing = Trailing.Check(current == p.id.value), onSelect = {
                choice = null
                onPick(p.id.value)
            })
        },
    )
}

/** Brings [v]'s game here from [copy], and says so. */
internal fun AppState.offerFrom(v: AndroidPortView, copy: PortGameCopy) {
    scope.launch {
        val problem = store.ports.bringGame(v.port.id, copy)
        if (problem != null) {
            toasts.show(problem, ToastKind.WARNING)
        } else {
            toasts.show("Bringing ${v.port.game} here from ${copy.from}. ${v.port.name} plays it once it's in", ToastKind.INFO, icon = FuseIcons.CloudDownload, durationMs = 6_000L)
        }
    }
}

/** How many places a port's game is offered from in its menu. */
private const val MAX_PLACES = 4

/** Everything that can be done with [v]. */
internal fun AppState.portMenu(v: AndroidPortView): ContextMenuSpec {
    val ops = store.ports
    val p = v.port
    val acts = buildList {
        next(v)?.let { (label, icon) -> add(MenuAction("next", label, icon, onSelect = { closeOverlays(); doNext(v) })) }
        if (v.installed) {
            val appId = v.appId
            if (next(v)?.first != "Attach") {
                add(MenuAction("attach", if (v.attachedTo.isEmpty()) "Attach to a Game" else "Attach to Another Game", FuseIcons.Link, detail = "Play on that game starts ${p.name}", onSelect = { closeOverlays(); attachPort(v) }))
            }
            if (appId != null && v.attachedTo.isNotEmpty()) {
                add(MenuAction("detach", "Detach From ${if (v.attachedTo.size == 1) v.attachedTo.first().title else "Its Games"}", FuseIcons.Unlink, detail = "The game plays with its emulator again", onSelect = {
                    closeOverlays()
                    scope.launch { ops.detach(appId) }
                }))
            }
            if (next(v)?.first != "Open") add(MenuAction("open", "Open ${p.name}", FuseIcons.External, detail = "Its own settings, and where it takes your game's files", onSelect = { closeOverlays(); ops.openAndroid(p.id) }))
            if (p.installable) {
                add(MenuAction("update", "Install Its Newest Release", FuseIcons.Download, detail = "From its project's releases. Its saves stay in the app", onSelect = { closeOverlays(); ops.installAndroid(p.id, v.attachedTo.firstOrNull()?.id) }))
            }
        }
        // Its game on RomM or another device: brought here from the one chosen.
        if (v.bringing == null && !v.here) {
            for (c in v.elsewhere.take(MAX_PLACES)) add(copyRow(c) { closeOverlays(); offerFrom(v, c) })
        }
        add(
            MenuAction(
                "about", "Project Page", FuseIcons.External,
                detail = listOfNotNull(p.about, p.needs?.let { "It asks for: $it" }).joinToString(" ").ifEmpty { p.repo },
                onSelect = { closeOverlays(); platform.openUrl(p.repo) },
            ),
        )
        if (v.installed) {
            add(MenuAction("uninstall", "Uninstall", FuseIcons.Trash, detail = "Android asks you first. Its saves go with it", destructive = true, onSelect = {
                closeOverlays()
                confirm = ConfirmSpec("Uninstall ${p.name}?", "Android removes the app and what it keeps, its saves too. Your game's own files stay where they are.", "Uninstall", destructive = true) {
                    ops.uninstallAndroid(p.id)
                }
            }))
        }
    }
    return ContextMenuSpec(
        title = p.game,
        subtitle = listOfNotNull(
            p.name,
            when {
                p.verified != null -> "verified ${p.verified}" + (p.verifiedVersion?.let { ", version $it" } ?: "")
                p.checked -> "checked by Fuse"
                else -> "listed by Open Ports Games, unchecked"
            },
            p.licence,
            v.cover?.let { _ -> p.coverCredit?.let { "box art: $it" } },
        ).joinToString("  ·  "),
        icon = FuseIcons.Boxes,
        actions = acts,
    )
}
