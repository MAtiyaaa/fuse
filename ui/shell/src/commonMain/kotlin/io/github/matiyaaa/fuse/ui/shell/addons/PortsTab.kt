package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PortRunner
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
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
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.downloads.ActionPill
import io.github.matiyaaa.fuse.ui.shell.downloads.TopAction
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import io.github.matiyaaa.fuse.ui.shell.store.PortView
import io.github.matiyaaa.fuse.ui.shell.store.PortsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ports in Addons: each port in the list, with the game it plays, how it stands here (not
 * installed, downloading, needing the game's files, ready) and, when a signed list says so, that
 * the nightly check saw this exact build start. Confirm does the next thing (install, choose the
 * game's files, play); Options has the rest.
 */
@Composable
fun PortsTab(app: AppState, active: Boolean, topPadding: Dp) {
    val ops = app.store.ports
    val state by ops.state.collectAsState()
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    LaunchedEffect(Unit) { ops.open() }
    val actions = buildList {
        if (state.canCheck) add(TopAction("Look Again", FuseIcons.RefreshCcw) { ops.refresh() })
        add(TopAction("Settings", FuseIcons.Settings) { app.go(Route.Settings("addons")) })
    }
    val ports = state.ports
    val rows = listOf("head") + ports.map { it.port.id }
    fun sizeOf(key: String) = if (key == "head") actions.size else 1
    val sel = remember { ShelfSelection(initialRow = if (ports.isEmpty()) 0 else 1) }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "head"
    val col = sel.column(row)
    val current = ports.firstOrNull { it.port.id == row }

    PageEffect(focused, row, col, current) {
        if (!focused) return@PageEffect
        app.hints = when {
            row == "head" -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(col)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
            current != null -> listOfNotNull(next(current)?.let { Hint(HintButton.CONFIRM, it.first) }, Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.BACK, "Back"))
        }
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { r ->
                if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT)) NavResult.BLOCKED else r
            }
            NavAction.SELECT -> {
                if (row == "head") actions.getOrNull(col)?.run?.invoke() else current?.let { app.doNext(it) }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.portMenu(it)) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }
    val list = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.designsystem.focus.GlideTo(list, sel.row.coerceAtLeast(0), settleMs = Durations.SLOW)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("ports")) {
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
            when {
                !state.loaded -> item("loading") { Waiting(compact) }
                ports.isEmpty() -> item("none") {
                    Note(FuseIcons.Boxes, "No ports in the list yet", "The list inside Fuse has none for now; a newer one brings them here.")
                }
                else -> for ((i, v) in ports.withIndex()) {
                    item(v.port.id) {
                        PortCard(v, selected = focused && sel.row == i + 1, compact = compact, onClick = {
                            app.focusZone = FocusZone.CONTENT
                            sel.row = i + 1
                            app.doNext(v)
                        }, onLongClick = {
                            sel.row = i + 1
                            app.openContextMenu(app.portMenu(v))
                        })
                    }
                }
            }
        }
    }
}

/** The page's line: what Ports is, which list it shows, and the few things to do. */
@Composable
private fun Header(state: PortsState, actions: List<TopAction>, inHead: Boolean, col: Int, compact: Boolean, wide: Boolean, onAction: (Int) -> Unit) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.Boxes, size = Size.iconM, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText("Games rebuilt to run natively", if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
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
    return when {
        !state.loaded -> "Reading the list of ports..." to false
        state.refreshing -> "Looking for a newer list..." to false
        state.problem != null -> "${state.problem} The list Fuse has is shown." to true
        state.signed -> "$count  ·  signed list, revision ${state.revision}" to false
        !state.canCheck -> "$count  ·  the list inside Fuse; nothing is verified until Fuse carries the key that signs newer lists" to false
        else -> "$count  ·  the list inside Fuse" to false
    }
}

@Composable
private fun PortCard(v: PortView, selected: Boolean, compact: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val border by fuselineColor(if (selected) c.focus else Color.Transparent, Fuse.motion.tween(Durations.FAST), label = "portCard")
    val (status, tone) = status(v)
    val toneColor = when (tone) {
        Tone.GOOD -> c.success
        Tone.WAIT -> c.accent
        Tone.NEEDS -> c.warning
        Tone.QUIET -> c.textMuted
    }
    Row(
        Modifier.padding(horizontal = Space.gutter).widthIn(max = 960.dp).fillMaxWidth().clip(shape)
            .background(c.surfaceRaised)
            .ditheredBackground(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.10f), Color.Transparent)))
            .border(2.dp, border, shape)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(if (compact) Space.s else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(v.port.game, if (compact) 48.dp else 64.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(v.port.game, if (compact) Fuse.type.bodyStrong else Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (v.verified != null) {
                    Spacer(Modifier.width(Space.s))
                    Chip(FuseIcons.ShieldCheck, "Verified", c.success)
                }
                if (v.build?.runner == PortRunner.UMU) {
                    Spacer(Modifier.width(Space.s))
                    Chip(FuseIcons.Layers, "Through Proton", c.textMuted)
                }
            }
            FText(listOfNotNull("${v.port.name} ${v.port.version}", v.port.licence).joinToString("  ·  "), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor))
                Spacer(Modifier.width(Space.s))
                FText(status, Fuse.type.caption, color = if (tone == Tone.QUIET) c.textMuted else c.text, maxLines = if (compact) 1 else 2)
            }
            (v.job as? PortJob.Downloading)?.progress?.let { p -> Progress(p) }
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

/** The game's initials on the accent, standing in for art the list doesn't carry. */
@Composable
private fun Monogram(game: String, size: Dp) {
    val c = Fuse.colors
    val letters = game.split(' ', '-', ':').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(Fuse.geometry.control))
            .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.55f), c.accent.copy(alpha = 0.18f)))),
        contentAlignment = Alignment.Center,
    ) {
        FText(letters.ifEmpty { "?" }, Fuse.type.title, color = c.text, maxLines = 1)
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, color: Color) {
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
private fun Progress(p: Float) {
    val c = Fuse.colors
    Box(Modifier.padding(top = Space.xs).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).clip(CircleShape).background(c.accent))
    }
}

/** The list being read for the first time: the shapes the cards will take. */
@Composable
private fun Waiting(compact: Boolean) {
    val c = Fuse.colors
    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        repeat(3) {
            Box(Modifier.widthIn(max = 960.dp).fillMaxWidth().height(if (compact) 64.dp else 88.dp).clip(RoundedCornerShape(Fuse.geometry.panel)).background(c.text.copy(alpha = 0.05f)))
        }
    }
}

@Composable
private fun Note(icon: ImageVector, title: String, detail: String) {
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

private enum class Tone { GOOD, WAIT, NEEDS, QUIET }

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

/** Asks for the folder of [v]'s game files [dataId], and sets the port up with it. */
private fun AppState.choosePortFolder(v: PortView, dataId: String) {
    val data = v.port.data.firstOrNull { it.id == dataId } ?: return
    scope.launch {
        val folder = platform.storage.pickFolder(data.name) ?: return@launch
        val problem = store.ports.chooseFolder(v.port.id, dataId, folder)
        if (problem == null) toasts.show("${v.port.game} reads its files from there", ToastKind.SUCCESS, icon = FuseIcons.FolderOpen)
        else toasts.show(problem, ToastKind.WARNING)
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
                choosePortFolder(v, d.id)
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
