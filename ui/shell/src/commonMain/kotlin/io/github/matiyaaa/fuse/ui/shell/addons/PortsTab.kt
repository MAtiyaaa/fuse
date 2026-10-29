package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.components.isSelected
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PortRunner
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
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
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.downloads.ActionPill
import io.github.matiyaaa.fuse.ui.shell.downloads.TopAction
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import io.github.matiyaaa.fuse.ui.shell.store.PortView
import io.github.matiyaaa.fuse.ui.shell.store.PortsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Ports in Addons: each port in the list, with the game it plays, how it stands here (not
 * installed, downloading, needing the game's files, ready) and, when a signed list says so, that
 * the nightly check saw this exact build start. Confirm does the next thing (install, choose the
 * game's files, play); Options has the rest.
 */
@Composable
fun PortsTab(app: AppState, active: Boolean, topPadding: Dp) {
    // On Android, ports are apps: installed from their projects and attached to their games.
    if (app.store.ports.androidApps) {
        AndroidPortsTab(app, active, topPadding)
        return
    }
    val ops = app.store.ports
    val state by ops.state.collectAsState()
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    LaunchedEffect(Unit) { ops.open() }
    // Recomps the person installed themselves: shown here, chosen from each game's Play With.
    val yours by androidx.compose.runtime.produceState(emptyList<io.github.matiyaaa.fuse.ui.shell.store.FoundRecomp>(), state.loaded) { value = ops.yourRecomps() }
    val actions = buildList {
        if (state.canCheck) add(TopAction("Look Again", FuseIcons.RefreshCcw) { ops.refresh() })
        add(TopAction("Settings", FuseIcons.Settings) { app.go(Route.Settings("addons")) })
    }
    var filter by remember { mutableStateOf(PortsFilter.ALL) }
    val counts = remember(state.ports) { PortsFilter.entries.associateWith { f -> state.ports.count(f::keeps) } }
    // Yours first (installed, then ready to install here), then the rest, each by its game's name.
    val ports = remember(state.ports, filter) {
        state.ports.filter(filter::keeps).sortedWith(compareBy<PortView>({ !it.installed }, { it.build == null }, { it.port.game.lowercase() }))
    }
    val filters = PortsFilter.entries.filter { it == PortsFilter.ALL || (counts[it] ?: 0) > 0 }
    // Where the controller is: the page's actions, the filters, or the grid.
    // Until the person moves, it is on the first port (or the filters while there are none).
    var chosenZone by remember { mutableStateOf<Zone?>(null) }
    val zone = chosenZone?.takeUnless { it == Zone.GRID && ports.isEmpty() } ?: if (ports.isEmpty()) Zone.FILTERS else Zone.GRID
    var headCol by remember { mutableIntStateOf(0) }
    val grid = remember { GridSelection() }
    grid.clamp(ports.size)
    headCol = headCol.coerceIn(0, (actions.size - 1).coerceAtLeast(0))
    val current = if (zone == Zone.GRID) ports.getOrNull(grid.index) else null
    // The grid's columns as last laid out, for moving through it (set by the layout, read by input).
    val columns = remember { intArrayOf(1) }

    PageEffect(focused, zone, headCol, current, filter) {
        if (!focused) return@PageEffect
        app.hints = when {
            zone == Zone.HEAD -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(headCol)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
            zone == Zone.FILTERS -> listOf(Hint(HintButton.CONFIRM, "Show"), Hint(HintButton.BACK, "Back"))
            current != null -> listOfNotNull(next(current)?.let { Hint(HintButton.CONFIRM, it.first) }, Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.BACK, "Back"))
        }
    }
    InputLayer(enabled = focused) { e ->
        when (zone) {
            Zone.HEAD -> when (e.action) {
                NavAction.LEFT -> if (headCol > 0) { headCol--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (headCol < actions.size - 1) { headCol++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> { chosenZone = Zone.FILTERS; NavResult.MOVED }
                NavAction.SELECT -> { actions.getOrNull(headCol)?.run?.invoke(); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
            Zone.FILTERS -> {
                val at = filters.indexOf(filter).coerceAtLeast(0)
                when (e.action) {
                    NavAction.LEFT -> if (at > 0) { filter = filters[at - 1]; grid.index = 0; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.RIGHT -> if (at < filters.size - 1) { filter = filters[at + 1]; grid.index = 0; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.UP -> { chosenZone = Zone.HEAD; NavResult.MOVED }
                    NavAction.DOWN -> if (ports.isNotEmpty()) { chosenZone = Zone.GRID; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.SELECT -> { if (ports.isNotEmpty()) chosenZone = Zone.GRID; NavResult.ACTIVATED }
                    else -> NavResult.IGNORED
                }
            }
            Zone.GRID -> when (e.action) {
                NavAction.SELECT -> { current?.let { app.doNext(it) }; NavResult.ACTIVATED }
                NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.portMenu(it)) }; NavResult.ACTIVATED }
                else -> grid.move(e.action, ports.size, columns[0]).let { r ->
                    when {
                        r == NavResult.IGNORED && e.action == NavAction.UP -> { chosenZone = Zone.FILTERS; NavResult.MOVED }
                        r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT) -> NavResult.BLOCKED
                        else -> r
                    }
                }
            }
        }
    }
    val gridState = rememberLazyGridState()
    // Another filter starts at its first port.
    LaunchedEffect(filter) { gridState.scrollToItem(0) }
    FollowSelection(gridState, { grid.index }, anchor = 0.1f, enabled = { zone == Zone.GRID })
    BoxWithConstraints(Modifier.fillMaxSize().testTag("ports")) {
        val compact = maxHeight < 560.dp || maxWidth < 600.dp
        val wide = maxWidth >= 900.dp
        val gap = if (compact) Space.s else Space.m
        val tile = if (compact) 104.dp else 132.dp
        val room = maxWidth - Space.gutter * 2
        val cols = ((room + gap) / (tile + gap)).toInt().coerceAtLeast(2)
        columns[0] = cols
        val width = (room - gap * (cols - 1)) / cols
        Column(Modifier.fillMaxSize().padding(top = topPadding + subTabsRoom())) {
            Header(state, actions, focused && zone == Zone.HEAD, headCol, compact, wide) { i ->
                app.focusZone = FocusZone.CONTENT
                chosenZone = Zone.HEAD
                headCol = i
                actions[i].run()
            }
            PortStage(app, ports.getOrNull(grid.index).takeIf { state.loaded }, compact)
            FilterRow(filters, filter, counts, focused && zone == Zone.FILTERS) { f ->
                app.focusZone = FocusZone.CONTENT
                filter = f
                grid.index = 0
                chosenZone = Zone.FILTERS
            }
            when {
                !state.loaded -> Waiting(compact)
                state.ports.isEmpty() -> Note(FuseIcons.Boxes, "No ports in the list yet", "The list inside Fuse has none for now; a newer one brings them here.")
                ports.isEmpty() -> Note(FuseIcons.Boxes, "Nothing here", "No port is ${filter.empty}.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    state = gridState,
                    modifier = Modifier.weight(1f).fadingEdges(gridState, top = Space.l, bottom = Space.xl).dragScroll(gridState),
                    // Room above the first row for a chosen tile's lift, clear of the filters.
                    contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l + Space.s, bottom = Size.hintHeight + Space.xl),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap + Space.s),
                ) {
                    for ((i, v) in ports.withIndex()) {
                        item(key = v.port.id) {
                            PortTile(
                                app, v, width,
                                selected = isSelected { focused && zone == Zone.GRID && grid.index == i },
                                onClick = {
                                    app.focusZone = FocusZone.CONTENT
                                    chosenZone = Zone.GRID
                                    if (grid.index == i) app.doNext(v) else grid.index = i
                                },
                                onLongClick = {
                                    chosenZone = Zone.GRID
                                    grid.index = i
                                    app.openContextMenu(app.portMenu(v))
                                },
                            )
                        }
                    }
                    if (yours.isNotEmpty() && filter == PortsFilter.ALL) {
                        item("yours-head", span = { GridItemSpan(maxLineSpan) }) {
                            FText("Ports and recomps you installed".uppercase(), Fuse.type.overline, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.padding(top = Space.l))
                        }
                        for (y in yours) item("yours-${y.id}-${y.path}", span = { GridItemSpan(maxLineSpan) }) {
                            YourRecompCard(app, y, state.ports.firstOrNull { it.port.id == y.id }?.port?.cover, compact)
                        }
                    }
                }
            }
        }
    }
}

private enum class Zone { HEAD, FILTERS, GRID }

/** A port with no build for this computer: drawn faded, still there to read about. */
private const val UNAVAILABLE_ALPHA = 0.45f

/** The page's filters, each with what it keeps and how an empty one is said. */
private enum class PortsFilter(val label: String, val empty: String) {
    ALL("All", "in the list"),
    INSTALLED("Installed", "installed"),
    READY("Ready to Play", "ready to play"),
    UPDATES("Updates", "waiting to update"),
    AVAILABLE("Not Installed", "left to install for this computer");

    fun keeps(v: PortView): Boolean = when (this) {
        ALL -> true
        INSTALLED -> v.installed
        READY -> v.ready
        UPDATES -> v.update
        AVAILABLE -> !v.installed && v.build != null
    }
}

/** The page's line: what Ports is, which list it shows, and the few things to do. */
@Composable
private fun Header(state: PortsState, actions: List<TopAction>, inHead: Boolean, col: Int, compact: Boolean, wide: Boolean, onAction: (Int) -> Unit) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val (line, warn) = listLine(state)
            FText(line, Fuse.type.caption, color = if (warn) c.warning else c.textMuted, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            actions.forEachIndexed { i, a ->
                val chosen = inHead && col == i
                ActionPill(a, chosen, wide || chosen) { onAction(i) }
            }
        }
    }
}

/** Which list is shown, said in one line, and whether that needs the person's eye. */
private fun listLine(state: PortsState): Pair<String, Boolean> {
    val count = "${state.ports.size} ${if (state.ports.size == 1) "port" else "ports"}"
    val installed = state.ports.count { it.installed }.takeIf { it > 0 }?.let { "  ·  $it installed" }.orEmpty()
    return when {
        !state.loaded -> "Reading the list of ports..." to false
        state.refreshing -> "Looking for a newer list..." to false
        state.problem != null -> "${state.problem} The list Fuse has is shown." to true
        state.signed -> "$count$installed  ·  checked each night" to false
        !state.canCheck -> "$count$installed  ·  the list inside Fuse" to false
        else -> "$count$installed  ·  the list inside Fuse" to false
    }
}

/**
 * The selected port, told big above the grid: its game, the port and its version, how it stands
 * here, and what it needs. A fixed height, so the grid never jumps as the selection moves.
 */
@Composable
private fun PortStage(app: AppState, v: PortView?, compact: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Box(Modifier.fillMaxWidth().height(if (compact) 80.dp else 104.dp).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
        Swap(
            targetState = v,
            contentKey = { it?.port?.id },
            transitionSpec = {
                fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.INSTANT))
            },
            contentAlignment = Alignment.BottomStart,
            label = "portStage",
        ) { p ->
            if (p == null) return@Swap
            val (status, tone) = status(p)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                FText(
                    listOfNotNull("${p.port.name} ${p.port.version}", p.port.licence).joinToString("  ·  ").uppercase(),
                    Fuse.type.overline, color = c.textMuted, maxLines = 1,
                )
                FText(p.port.game, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.size(Size.dot).clip(CircleShape).background(toneColor(tone)))
                    FText(status, Fuse.type.body, color = if (tone == Tone.QUIET) c.textMuted else c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (p.verified != null) Chip(FuseIcons.ShieldCheck, "Verified", c.success)
                    if (p.build?.runner == PortRunner.UMU) Chip(FuseIcons.Layers, "Through Proton", c.textMuted)
                }
                (p.job as? PortJob.Downloading)?.progress?.let { pr -> Box(Modifier.widthIn(max = 420.dp)) { Progress(pr) } }
            }
        }
    }
}

@Composable
private fun toneColor(tone: Tone): Color {
    val c = Fuse.colors
    return when (tone) {
        Tone.GOOD -> c.success
        Tone.WAIT -> c.accent
        Tone.NEEDS -> c.warning
        Tone.QUIET -> c.textMuted
    }
}

/** The filters as one row of pills, the chosen one filled; each says how many it keeps. */
@Composable
private fun FilterRow(filters: List<PortsFilter>, chosen: PortsFilter, counts: Map<PortsFilter, Int>, focused: Boolean, onPick: (PortsFilter) -> Unit) {
    io.github.matiyaaa.fuse.ui.designsystem.components.FilterPills(
        filters.map { it.label }, filters.indexOf(chosen), { i -> onPick(filters[i]) },
        Modifier.padding(horizontal = Space.gutter).padding(top = Space.m),
        focused = focused,
        counts = filters.map { counts[it] ?: 0 },
    )
}

/**
 * A port in the grid: its game's box art, as the library draws a game, with how it stands here in
 * the corner (installed, an update, files it needs, a download under way).
 */
@Composable
private fun PortTile(app: AppState, v: PortView, width: Dp, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val media by remember(v.game) { v.game?.let { app.store.media.media(MediaOwner.OfGame(it)) } ?: flowOf(null) }.collectAsState(null)
    val own = media?.let(Art::from)
    val model = own?.boxart ?: v.port.cover ?: own?.square ?: own?.icon
    val unavailable = v.build == null && !v.installed
    // Several ports can play one game (five for Doom): each says which it is under its box.
    // Clear of the line a chosen tile draws under itself.
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
    Tile(
        selected = selected,
        label = v.port.game,
        modifier = Modifier.width(width).aspectRatio(Aspect.BOX),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Artwork(
            model,
            Modifier.fillMaxSize().then(if (unavailable) Modifier.alpha(UNAVAILABLE_ALPHA) else Modifier),
            contentScale = ContentScale.Crop,
            focusY = 0.3f,
            // Art not found yet: the game's own drawn cover, as the library draws a game without art.
            fallback = { GeneratedArt(v.port.game, c.accent, slot = ArtSlot.BOX) },
        )
        val job = v.job
        val mark: Pair<ImageVector, Color>? = when {
            job is PortJob.Failed -> FuseIcons.CircleX to c.warning
            job != null && job.active -> null
            v.update -> FuseIcons.Download to c.accent
            v.installed && v.missing.isNotEmpty() -> FuseIcons.FolderOpen to c.warning
            v.installed -> FuseIcons.Check to c.success
            else -> null
        }
        if (mark != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(Space.xs).size(Size.badge).clip(CircleShape).background(c.ink.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) { FuseIcon(mark.first, size = Size.badgeIcon, tint = mark.second) }
        }
        if (job != null && job.active) {
            val p = (job as? PortJob.Downloading)?.progress
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Space.s).height(4.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.6f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p ?: 0.3f).clip(CircleShape).background(c.accent))
            }
        }
    }
    FText(v.port.name, Fuse.type.caption, color = if (selected) c.text else c.textMuted, maxLines = 1, modifier = Modifier.padding(horizontal = Space.xs))
    }
}

/**
 * A recomp the person installed themselves: what it is, how it was installed, and where to choose
 * it (the game's own Play With, beside its emulator).
 */
@Composable
private fun YourRecompCard(app: AppState, y: io.github.matiyaaa.fuse.ui.shell.store.FoundRecomp, cover: String?, compact: Boolean) {
    val c = Fuse.colors
    val name = io.github.matiyaaa.fuse.launch.ports.NativePorts.name(y.id) ?: return
    val game = io.github.matiyaaa.fuse.launch.ports.NativePorts.game(y.id) ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.panel))
            .background(c.surfaceRaised.copy(alpha = 0.6f))
            .padding(if (compact) Space.s else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortCover(app, null, cover, game, if (compact) 48.dp else 60.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FText(game, Fuse.type.bodyStrong, maxLines = 1)
            FText(listOfNotNull(name, "Your ${y.how}", y.version).joinToString("  ·  "), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            FText("Choose it in the game's Play With, beside its emulator. Its saves are kept in step on every device.", Fuse.type.caption, color = c.textMuted, maxLines = if (compact) 1 else 2)
        }
        Spacer(Modifier.width(Space.m))
        Chip(FuseIcons.Check, "Found", c.success)
    }
}

/** The game's initials on the accent, standing in for art the list doesn't carry. */
@Composable
internal fun Monogram(game: String, size: Dp) {
    MonogramBox(game, Modifier.size(size).clip(RoundedCornerShape(Fuse.geometry.control)))
}

/** [Monogram] filling [modifier]'s box, of any shape. */
@Composable
private fun MonogramBox(game: String, modifier: Modifier) {
    val c = Fuse.colors
    val letters = game.split(' ', '-', ':').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier.background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.55f), c.accent.copy(alpha = 0.18f)))),
        contentAlignment = Alignment.Center,
    ) {
        FText(letters.ifEmpty { "?" }, Fuse.type.title, color = c.text, maxLines = 1)
    }
}

/**
 * The box art of a port's game, [height] tall in a box's shape: the library's own art for it when
 * [game] is in the library (so it looks as the library shows it), else [cover] (another copy's art,
 * the port's listing's, or one found by its name), else the game's initials.
 */
@Composable
internal fun PortCover(app: AppState, game: GameId?, cover: Any?, title: String, height: Dp) {
    val media by remember(game) { game?.let { app.store.media.media(MediaOwner.OfGame(it)) } ?: flowOf(null) }.collectAsState(null)
    val own = media?.let(Art::from)
    val model = own?.boxart ?: cover ?: own?.square ?: own?.icon
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Artwork(
        model,
        Modifier.height(height).width(height * BOX_ART).clip(shape).background(Fuse.colors.surfaceRaised).border(1.dp, Fuse.colors.hairline, shape),
        contentScale = ContentScale.Crop,
        focusY = 0.3f,
        fallback = { MonogramBox(title, Modifier.fillMaxSize()) },
    )
}

/** A game box's width for its height (portrait box art). */
private const val BOX_ART = 0.72f

@Composable
internal fun Chip(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = Space.s, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 12.dp, tint = color)
        Spacer(Modifier.width(4.dp))
        FText(text, Fuse.type.caption, color = color, maxLines = 1)
    }
}

@Composable
internal fun Progress(p: Float) {
    val c = Fuse.colors
    Box(Modifier.padding(top = Space.xs).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).clip(CircleShape).background(c.accent))
    }
}

/** The list being read for the first time: the shapes the cards will take. */
@Composable
internal fun Waiting(compact: Boolean) {
    val c = Fuse.colors
    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        repeat(3) {
            Box(Modifier.widthIn(max = 960.dp).fillMaxWidth().height(if (compact) 64.dp else 88.dp).clip(RoundedCornerShape(Fuse.geometry.panel)).background(c.text.copy(alpha = 0.05f)))
        }
    }
}

@Composable
internal fun Note(icon: ImageVector, title: String, detail: String) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconM, tint = c.textMuted)
        Spacer(Modifier.width(Space.m))
        Column {
            FText(title, Fuse.type.bodyStrong, maxLines = 1)
            FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 2)
        }
    }
}

internal enum class Tone { GOOD, WAIT, NEEDS, QUIET }

/** How [v] stands here, in a few words. */
private fun status(v: PortView): Pair<String, Tone> {
    val job = v.job
    return when {
        job is PortJob.Downloading -> (job.progress?.let { "Downloading, ${(it * 100).toInt()}%" } ?: "Downloading...") to Tone.WAIT
        job == PortJob.Unpacking -> "Checking and unpacking..." to Tone.WAIT
        job == PortJob.SettingUp -> "Setting it up with your game's files..." to Tone.WAIT
        job is PortJob.Failed -> job.message to Tone.NEEDS
        v.build == null && !v.installed -> (v.whyNot ?: "Not published for this computer.") to Tone.QUIET
        !v.installed -> "Not installed  ·  ${size(v.build?.size)}" to Tone.QUIET
        v.missing.isNotEmpty() -> {
            val d = v.port.data.firstOrNull { it.id in v.missing }
            val why = v.missing[d?.id]
            (if (why == null || why == "Not chosen") "Needs ${d?.name ?: "its game files"}" else why) to Tone.NEEDS
        }
        v.update -> "Ready  ·  ${v.port.version} is out (${v.installedVersion} here)" to Tone.GOOD
        v.game == null -> "Installed  ·  setting up..." to Tone.WAIT
        else -> "Ready to play" to Tone.GOOD
    }
}

private fun size(bytes: Long?): String = when {
    bytes == null -> "size unknown"
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    else -> "${(bytes + (1L shl 20) - 1) / (1L shl 20)} MB"
}

/** What Confirm does for [v] now, with its icon; null when there is nothing to do but look. */
private fun next(v: PortView): Pair<String, ImageVector>? {
    val job = v.job
    return when {
        job != null && job.active -> "Stop" to FuseIcons.CircleX
        job is PortJob.Failed -> "Try Again" to FuseIcons.RefreshCcw
        v.build == null && !v.installed -> null
        !v.installed -> "Install" to FuseIcons.Download
        v.missing.isNotEmpty() -> "Choose Files" to FuseIcons.FolderOpen
        v.update -> "Update" to FuseIcons.Download
        v.game != null -> "Play" to FuseIcons.Play
        else -> null
    }
}

private fun AppState.doNext(v: PortView) {
    val ops = store.ports
    val job = v.job
    when {
        job != null && job.active -> ops.cancel(v.port.id)
        job is PortJob.Failed -> { ops.cancel(v.port.id); ops.install(v.port.id) }
        v.build == null && !v.installed -> toasts.show(v.whyNot ?: "Not published for this computer.")
        !v.installed || v.update -> ops.install(v.port.id)
        v.missing.isNotEmpty() -> v.port.data.firstOrNull { it.id in v.missing }?.let { choosePortFolder(v, it.id) }
        v.game != null -> playPort(v)
    }
}

/**
 * Asks for [v]'s game files [dataId] and sets the port up with them: the person's copy already in
 * the library, when one has what the port needs, or a folder they pick.
 */
private fun AppState.choosePortFolder(v: PortView, dataId: String, ask: Boolean = false) {
    val data = v.port.data.firstOrNull { it.id == dataId } ?: return
    fun said(problem: String?) {
        if (problem == null) toasts.show("${v.port.game} reads its files from there", ToastKind.SUCCESS, icon = FuseIcons.FolderOpen)
        else toasts.show(problem, ToastKind.WARNING)
    }
    // A recompilation is made from one ROM file; any other port reads a folder.
    fun pick() = scope.launch {
        val folder = (if (data.rom != null) platform.storage.pickFile(data.name, "N64 ROM", listOf("z64", "n64", "v64")) else platform.storage.pickFolder(data.name)) ?: return@launch
        said(store.ports.chooseFolder(v.port.id, dataId, folder))
    }
    scope.launch {
        val copies = store.ports.copiesInLibrary(v.port.id, dataId)
        // This computer's copy first; else the person's copy on RomM or another device, brought here.
        val away = if (copies.isEmpty()) store.ports.copiesElsewhere(v.port.id, dataId) else emptyList()
        if (copies.isEmpty() && away.isEmpty()) {
            pick()
            return@launch
        }
        // One copy in the library: used at once, nothing to choose (Options still picks another).
        copies.singleOrNull()?.takeIf { away.isEmpty() && !ask }?.let { c ->
            val problem = store.ports.useCopy(v.port.id, dataId, c)
            if (problem == null) toasts.show("${v.port.game} uses your copy in the library: ${c.title}", ToastKind.SUCCESS, icon = FuseIcons.FolderOpen)
            else toasts.show(problem, ToastKind.WARNING)
            return@launch
        }
        val elsewhere = if (away.isNotEmpty()) " Yours is on ${away.map { it.from }.distinct().joinToString(" and ")}: Fuse can bring it here, and ${v.port.name} reads it once it's in." else ""
        choice = ChoiceSpec(
            icon = FuseIcons.FolderOpen,
            title = "Choose ${data.name}",
            message = (if (data.rom != null) "Your copy in the library, or another ROM file. ${v.port.name} reads it where it is, or a copy Fuse makes in the form it reads; your file never changes."
                else "Your copy in the library, or another folder. ${v.port.name} reads its files where they are; Fuse never changes them.") + elsewhere,
            options = copies.map { c ->
                MenuAction("copy.${c.game.value}", c.title, FuseIcons.Library, detail = "${c.system}  ·  ${c.folder}", onSelect = {
                    choice = null
                    scope.launch { said(store.ports.useCopy(v.port.id, dataId, c)) }
                })
            } + away.map { c ->
                MenuAction(
                    "away.${c.place.name}.${c.id.value}",
                    if (c.place == io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.ROMM) "Get It From RomM" else "Get It From ${c.from}",
                    if (c.place == io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.ROMM) FuseIcons.Server else FuseIcons.Monitor,
                    detail = listOfNotNull(c.title, c.sizeBytes.takeIf { it > 0 }?.let(::size), if (!c.online) "Away now: it comes when it's back" else null).joinToString("  ·  "),
                    onSelect = {
                        choice = null
                        scope.launch {
                            val problem = store.ports.bringGame(v.port.id, c)
                            if (problem != null) toasts.show(problem, ToastKind.WARNING)
                            else toasts.show("Bringing ${v.port.game} here from ${c.from}. ${v.port.name} reads it once it's in", ToastKind.INFO, icon = FuseIcons.CloudDownload, durationMs = 6_000L)
                        }
                    },
                )
            } + MenuAction("folder", if (data.rom != null) "Choose a ROM File" else "Choose a Folder", FuseIcons.FolderSearch, detail = data.about, onSelect = {
                choice = null
                pick()
            }),
        )
    }
}

/** Plays [v]'s game as the library plays it (its saves kept, its time counted). */
private fun AppState.playPort(v: PortView) {
    val id = v.game ?: return
    scope.launch {
        val card = store.library.games(GameQuery(includeHidden = true)).first().firstOrNull { it.id == id }
        if (card == null) toasts.show("${v.port.game} isn't in the library. Choose its files again to add it.", ToastKind.WARNING) else play(card)
    }
}

/** Everything that can be done with [v]. */
private fun AppState.portMenu(v: PortView): ContextMenuSpec {
    val ops = store.ports
    val acts = buildList {
        next(v)?.let { (label, icon) -> add(MenuAction("next", label, icon, onSelect = { closeOverlays(); doNext(v) })) }
        // The game's files can be chosen before it is installed: installing then sets it up at once.
        for (d in v.port.data) {
            val chosen = v.folders[d.id]
            if (next(v)?.first == "Choose Files" && chosen == null && v.port.data.size == 1) continue
            add(MenuAction("data.${d.id}", if (chosen == null) "Choose ${d.name}" else "Choose Another Folder", FuseIcons.FolderOpen, detail = chosen ?: d.about, onSelect = {
                closeOverlays()
                choosePortFolder(v, d.id, ask = true)
            }))
        }
        v.game?.let { g -> add(MenuAction("page", "Open Its Page", FuseIcons.Info, detail = "Steam, play time and saves, as for every game", onSelect = { closeOverlays(); go(Route.GameInfo(g)) })) }
        add(MenuAction("about", "${v.port.name}'s Project Page", FuseIcons.External, detail = (listOf(v.port.about) + v.port.notes).joinToString(" "), onSelect = { closeOverlays(); platform.openUrl(v.port.homepage) }))
        if (v.installed) {
            add(MenuAction("remove", "Remove", FuseIcons.Trash, detail = "Your saves and settings stay, for when you install it again", destructive = true, onSelect = {
                closeOverlays()
                confirm = ConfirmSpec("Remove ${v.port.name}?", "Fuse's copy and its links go. Your game's own files and your saves stay where they are.", "Remove", destructive = true) {
                    scope.launch { ops.remove(v.port.id) }
                }
            }))
        }
    }
    return ContextMenuSpec(
        title = v.port.game,
        subtitle = listOfNotNull("${v.port.name} ${v.port.version}", v.verified?.let { "verified $it" }).joinToString("  ·  "),
        icon = FuseIcons.Boxes,
        actions = acts,
    )
}
