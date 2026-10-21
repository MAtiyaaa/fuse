package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import io.github.matiyaaa.fuse.ui.designsystem.effects.ditheredBackground
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
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
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
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
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortsState
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which Android ports the page lists. */
private enum class PortFilter(val label: String) {
    LIBRARY("In Your Library"),
    INSTALLED("Installed"),
    CHECKED("Checked by Fuse"),
    ALL("Everything"),
}

/**
 * Ports on Android: games rebuilt for Android, each an app its project publishes. Those for games
 * in the library come first; each card says how it was made, whose word its entry is (checked by
 * Fuse, or listed by Open Ports Games), and how it stands here (not installed, installing,
 * installed and the game it plays). Confirm does the next thing (install, attach, play); Options has
 * the rest. Search and a system narrow the list.
 */
@Composable
internal fun AndroidPortsTab(app: AppState, active: Boolean, topPadding: Dp) {
    val ops = app.store.ports
    val state by ops.android.collectAsState()
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    LaunchedEffect(Unit) { ops.open() }
    var chosenFilter by remember { mutableStateOf<PortFilter?>(null) }
    var query by remember { mutableStateOf("") }
    var system by remember { mutableStateOf<String?>(null) }
    val forLibrary = state.ports.count { it.games.isNotEmpty() }
    // The library's own ports first, until the person picks another list.
    val filter = chosenFilter ?: if (forLibrary > 0 || !state.loaded) PortFilter.LIBRARY else PortFilter.ALL
    val shown = remember(state, filter, query, system) { shownPorts(state, filter, query, system) }
    val actions = listOf(
        TopAction(if (query.isEmpty()) "Search" else "“$query”", FuseIcons.Search) {
            app.textInput = TextInputSpec("Search ports", query, doneLabel = "Search", message = "A game, a port or a system") { q -> query = q.trim() }
        },
        TopAction(system?.let { PlatformCatalog.byId(PlatformId(it))?.shortName ?: it } ?: "Every System", FuseIcons.Filter) { app.pickPortSystem(state, system) { system = it } },
        TopAction("Look Again", FuseIcons.RefreshCcw) { ops.refresh() },
        TopAction("Settings", FuseIcons.Settings) { app.go(Route.Settings("addons")) },
    )
    val filters = PortFilter.entries
    val rows = listOf("head", "filters") + shown.map { it.port.id }
    fun sizeOf(key: String) = if (key == "head") actions.size else 1
    val sel = remember { ShelfSelection(initialRow = 1) }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "head"
    val col = sel.column(row)
    val current = shown.firstOrNull { it.port.id == row }

    PageEffect(focused, row, col, current, filter) {
        if (!focused) return@PageEffect
        app.hints = when {
            row == "head" -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(col)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
            row == "filters" -> listOf(Hint(HintButton.CONFIRM, "Next List"), Hint(HintButton.BACK, "Back"))
            current != null -> listOfNotNull(next(current)?.let { Hint(HintButton.CONFIRM, it.first) }, Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.BACK, "Back"))
        }
    }
    fun step(by: Int) {
        val i = (filters.indexOf(filter) + by).mod(filters.size)
        chosenFilter = filters[i]
    }
    InputLayer(enabled = focused) { e ->
        when {
            row == "filters" && e.action == NavAction.LEFT -> { step(-1); NavResult.ACTIVATED }
            row == "filters" && e.action == NavAction.RIGHT -> { step(1); NavResult.ACTIVATED }
            else -> when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { r ->
                    if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT)) NavResult.BLOCKED else r
                }
                NavAction.SELECT -> {
                    when {
                        row == "head" -> actions.getOrNull(col)?.run?.invoke()
                        row == "filters" -> step(1)
                        else -> current?.let { app.doNext(it) }
                    }
                    NavResult.ACTIVATED
                }
                NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.portMenu(it)) }; NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        }
    }
    val list = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.designsystem.focus.GlideTo(list, sel.row.coerceAtLeast(0), settleMs = Durations.SLOW)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("android-ports")) {
        val compact = maxHeight < 560.dp || maxWidth < 600.dp
        val wide = maxWidth >= 900.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = topPadding).fadingEdges(list, top = Space.xl, bottom = Space.xl).dragScroll(list),
            contentPadding = PaddingValues(top = subTabsRoom() + Space.s, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            item("head") {
                Header(state, actions, focused && row == "head", col, compact, wide) { i ->
                    app.focusZone = FocusZone.CONTENT
                    sel.row = 0
                    sel.setColumn("head", i)
                    actions[i].run()
                }
            }
            item("filters") {
                Filters(filters, counts(state), filter, focused && row == "filters") { f ->
                    app.focusZone = FocusZone.CONTENT
                    sel.row = 1
                    chosenFilter = f
                }
            }
            when {
                !state.loaded -> item("loading") { Waiting(compact) }
                shown.isEmpty() -> item("none") { Empty(filter, query, state.ports.size) }
                else -> for ((i, v) in shown.withIndex()) {
                    item(v.port.id) {
                        AndroidPortCard(v, selected = focused && sel.row == i + 2, compact = compact, onClick = {
                            app.focusZone = FocusZone.CONTENT
                            sel.row = i + 2
                            app.doNext(v)
                        }, onLongClick = {
                            sel.row = i + 2
                            app.openContextMenu(app.portMenu(v))
                        })
                    }
                }
            }
            item("credit") { Credit() }
        }
    }
}

/** The ports [filter], [query] and [system] leave, those for the library's games first. */
private fun shownPorts(state: AndroidPortsState, filter: PortFilter, query: String, system: String?): List<AndroidPortView> {
    val q = query.lowercase()
    return state.ports.filter { v ->
        val p = v.port
        when (filter) {
            PortFilter.LIBRARY -> v.games.isNotEmpty() || v.attachedTo.isNotEmpty()
            PortFilter.INSTALLED -> v.installed || v.job != null
            PortFilter.CHECKED -> p.checked
            PortFilter.ALL -> true
        } && (system == null || system in p.systems) &&
            (q.isEmpty() || (listOf(p.game, p.name) + p.titles + p.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.name }).any { it.lowercase().contains(q) })
    }.sortedWith(
        compareBy(
            { if (it.games.isNotEmpty() || it.attachedTo.isNotEmpty()) 0 else 1 },
            { if (it.installed) 0 else 1 },
            { if (it.port.checked) 0 else 1 },
            { it.port.game.lowercase() },
            { it.port.status.ordinal },
        ),
    )
}

private fun counts(state: AndroidPortsState): Map<PortFilter, Int> = mapOf(
    PortFilter.LIBRARY to state.ports.count { it.games.isNotEmpty() || it.attachedTo.isNotEmpty() },
    PortFilter.INSTALLED to state.ports.count { it.installed },
    PortFilter.CHECKED to state.ports.count { it.port.checked },
    PortFilter.ALL to state.ports.size,
)

/** The page's line: what it lists, from which lists, and the few things to do. */
@Composable
private fun Header(state: AndroidPortsState, actions: List<TopAction>, inHead: Boolean, col: Int, compact: Boolean, wide: Boolean, onAction: (Int) -> Unit) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.Boxes, size = Size.iconM, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText("Games rebuilt for Android", if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
            val checked = state.ports.count { it.port.checked }
            val line = when {
                !state.loaded -> "Reading the lists of ports..."
                state.refreshing -> "Looking for newer lists..."
                state.problem != null -> "${state.problem} The lists Fuse has are shown."
                else -> listOfNotNull("${state.ports.size} ports", "$checked checked by Fuse", state.listedAt?.let { "Open Ports Games, $it" }).joinToString("  ·  ")
            }
            FText(line, Fuse.type.caption, color = if (state.problem != null) c.warning else c.textMuted, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            actions.forEachIndexed { i, a ->
                val chosen = inHead && col == i
                ActionPill(a, chosen, wide || chosen || i < 2) { onAction(i) }
            }
        }
    }
}

/** In Your Library, Installed, Checked by Fuse, Everything: each with how many it holds. */
@Composable
private fun Filters(filters: List<PortFilter>, counts: Map<PortFilter, Int>, active: PortFilter, focused: Boolean, onPick: (PortFilter) -> Unit) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        for (f in filters) {
            val on = f == active
            val bg by fuselineColor(
                when {
                    on && focused -> c.text
                    on -> c.accent.copy(alpha = 0.18f)
                    else -> Color.Transparent
                },
                Fuse.motion.tween(Durations.FAST), label = "portFilter",
            )
            val fg = when {
                on && focused -> c.ink
                on -> c.accent
                else -> c.textMuted
            }
            Row(
                Modifier.height(32.dp).clip(CircleShape).background({ bg })
                    .then(if (!on) Modifier.border(1.dp, c.hairline, CircleShape) else Modifier)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(f) }
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FText(f.label, Fuse.type.label, color = fg, maxLines = 1)
                val n = counts[f] ?: 0
                if (n > 0) {
                    Spacer(Modifier.width(6.dp))
                    FText("$n", Fuse.type.numericSmall, color = fg.copy(alpha = 0.75f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AndroidPortCard(v: AndroidPortView, selected: Boolean, compact: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val p = v.port
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val border by fuselineColor(if (selected) c.focus else Color.Transparent, Fuse.motion.tween(Durations.FAST), label = "androidPortCard")
    val (status, tone) = status(v)
    val toneColor = when (tone) {
        Tone.GOOD -> c.success
        Tone.WAIT -> c.accent
        Tone.NEEDS -> c.warning
        Tone.QUIET -> c.textMuted
    }
    val systems = p.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.shortName }.distinct().joinToString(" / ")
    Row(
        Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp).fillMaxWidth().clip(shape)
            .background(c.surfaceRaised)
            .ditheredBackground(Brush.horizontalGradient(listOf(c.accent.copy(alpha = if (v.games.isNotEmpty()) 0.14f else 0.06f), Color.Transparent)))
            .border(2.dp, border, shape)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(if (compact) Space.s else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(p.game, if (compact) 48.dp else 64.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(p.game, if (compact) Fuse.type.bodyStrong else Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(Space.s))
                if (p.checked) Chip(FuseIcons.ShieldCheck, "Checked", c.success) else Chip(FuseIcons.ShieldAlert, "Unchecked", c.warning)
            }
            FText(
                listOfNotNull(systems.ifEmpty { null }, p.name, "${p.madeLine()}, ${p.status.word()}", p.licence).joinToString("  ·  "),
                Fuse.type.caption, color = c.textMuted, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor))
                Spacer(Modifier.width(Space.s))
                FText(status, Fuse.type.caption, color = if (tone == Tone.QUIET) c.textMuted else c.text, maxLines = if (compact) 1 else 2)
            }
            (v.job as? PortJob.Downloading)?.progress?.let { pr -> Progress(pr) }
        }
        next(v)?.let { (label, icon) ->
            Spacer(Modifier.width(Space.m))
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(if (selected) c.text else c.text.copy(alpha = 0.07f)).padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FuseIcon(icon, size = Size.iconS, tint = if (selected) c.ink else c.text)
                if (!compact || selected) {
                    Spacer(Modifier.width(Space.xs))
                    FText(label, Fuse.type.label, color = if (selected) c.ink else c.text, maxLines = 1)
                }
            }
        }
    }
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
            "${OpenPorts.CREDIT}. Fuse checked the ones marked Checked against each project's own source; the rest are as listed. Each app comes from its own project's releases, and takes your game's files itself.",
            Fuse.type.caption, color = c.textFaint, maxLines = 4,
        )
    }
}

/** How [v] stands here, in a few words. */
private fun status(v: AndroidPortView): Pair<String, Tone> {
    val job = v.job
    val games = v.games.map { it.title }
    fun some(titles: List<String>) = titles.first() + if (titles.size > 1) " and ${titles.size - 1} more" else ""
    return when {
        job is PortJob.Downloading -> (job.progress?.let { "Downloading, ${(it * 100).toInt()}%" } ?: "Downloading...") to Tone.WAIT
        job is PortJob.Installing -> (if (job.waitingTurn) "Waiting for another install to finish..." else "Android is asking you to confirm...") to Tone.WAIT
        job is PortJob.Failed -> job.message to Tone.NEEDS
        v.installed && v.attachedTo.isNotEmpty() -> listOfNotNull("Installed", v.installedVersion, "plays ${some(v.attachedTo.map { it.title })}").joinToString("  ·  ") to Tone.GOOD
        v.installed && games.isNotEmpty() -> "Installed  ·  attach it to ${some(games)}" to Tone.NEEDS
        v.installed -> listOfNotNull("Installed", v.installedVersion).joinToString("  ·  ") to Tone.GOOD
        !v.port.installable -> "Get it from its project's page" to Tone.QUIET
        games.isNotEmpty() -> "Not installed  ·  for your ${some(games)}" to Tone.QUIET
        else -> "Not installed" to Tone.QUIET
    }
}

/** What Confirm does for [v] now, with its icon; null when there is nothing to do but look. */
private fun next(v: AndroidPortView): Pair<String, ImageVector>? {
    val job = v.job
    return when {
        job != null && job.active -> "Stop" to FuseIcons.CircleX
        job is PortJob.Failed -> "Try Again" to FuseIcons.RefreshCcw
        !v.installed && !v.port.installable -> "Project Page" to FuseIcons.External
        !v.installed -> "Install" to FuseIcons.Download
        v.attachedTo.isNotEmpty() -> "Play" to FuseIcons.Play
        v.games.isNotEmpty() -> "Attach" to FuseIcons.Link
        else -> "Open" to FuseIcons.External
    }
}

private fun AppState.doNext(v: AndroidPortView) {
    val ops = store.ports
    val job = v.job
    when {
        job != null && job.active -> ops.cancel(v.port.id)
        job is PortJob.Failed -> { ops.cancel(v.port.id); installPort(v) }
        !v.installed && !v.port.installable -> platform.openUrl(v.port.repo)
        !v.installed -> installPort(v)
        v.attachedTo.isNotEmpty() -> playAttached(v.attachedTo.first())
        v.games.isNotEmpty() -> attachPort(v)
        else -> if (!ops.openAndroid(v.port.id)) toasts.show("${v.port.name} can't be opened.", ToastKind.WARNING)
    }
}

/** Installs [v], for its one game in the library, or for the copy the person picks when there are several. */
private fun AppState.installPort(v: AndroidPortView) {
    fun go(game: PortGameChoice?) {
        store.ports.installAndroid(v.port.id, game?.id)
        toasts.show(
            if (game != null) "Installing ${v.port.name}. Android asks you to confirm, then ${game.title} plays with it" else "Installing ${v.port.name}. Android asks you to confirm",
            ToastKind.INFO, icon = FuseIcons.Download, durationMs = 6_000L,
        )
    }
    if (v.games.size <= 1) {
        go(v.games.firstOrNull())
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
private fun AppState.attachPort(v: AndroidPortView) {
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

/** Everything that can be done with [v]. */
private fun AppState.portMenu(v: AndroidPortView): ContextMenuSpec {
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
        subtitle = listOfNotNull(p.name, if (p.checked) "checked by Fuse" else "listed by Open Ports Games, unchecked", p.licence).joinToString("  ·  "),
        icon = FuseIcons.Boxes,
        actions = acts,
    )
}
