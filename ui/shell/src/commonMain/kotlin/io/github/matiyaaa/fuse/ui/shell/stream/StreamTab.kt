package io.github.matiyaaa.fuse.ui.shell.stream

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
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
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
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.downloads.ActionPill
import io.github.matiyaaa.fuse.ui.shell.downloads.TopAction
import io.github.matiyaaa.fuse.ui.shell.store.StreamHostView
import kotlinx.coroutines.launch

/**
 * Streaming in Addons: each computer at home with its state (ready, asleep and wakeable, not
 * answering), and its apps as big tiles. Confirm streams the app (waking the computer first when it
 * sleeps); Options on a computer wakes it, edits it or adds an app.
 */
@Composable
fun StreamTab(app: AppState, active: Boolean, topPadding: Dp) {
    val ops = app.store.streaming
    val hosts by ops.hosts.collectAsState()
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    LaunchedEffect(Unit) { ops.refresh() }
    val actions = listOf(
        TopAction("Look Again", FuseIcons.RefreshCcw) { ops.refresh() },
        TopAction("Add Computer", FuseIcons.Plus) { app.addStreamHost() },
        TopAction("Settings", FuseIcons.Settings) { app.go(Route.Settings("addons")) },
    )
    val rows = listOf("head") + hosts.map { it.host.id }
    fun sizeOf(key: String) = if (key == "head") actions.size else hosts.firstOrNull { it.host.id == key }?.host?.apps?.size?.plus(1) ?: 0
    val sel = remember { ShelfSelection(initialRow = if (hosts.isEmpty()) 0 else 1) }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "head"
    val col = sel.column(row)
    val current = hosts.firstOrNull { it.host.id == row }
    val client = remember { ops.client }

    fun activate(h: StreamHostView, i: Int) {
        val apps = h.host.apps
        if (i == apps.size) app.addStreamApp(h) else apps.getOrNull(i)?.let { ops.stream(h.host.id, it) }
    }
    PageEffect(focused, row, col) {
        if (!focused) return@PageEffect
        app.hints = when {
            row == "head" -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(col)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
            current != null && col == current.host.apps.size -> listOf(Hint(HintButton.CONFIRM, "Add app"), Hint(HintButton.OPTIONS, "Computer"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, if (current?.online == false && current.canWake) "Wake and stream" else "Stream"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
        }
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { r ->
                if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT)) NavResult.BLOCKED else r
            }
            NavAction.SELECT -> {
                if (row == "head") actions.getOrNull(col)?.run?.invoke() else current?.let { activate(it, col) }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(hostMenu(app, it, it.host.apps.getOrNull(col))) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(sel.row) { list.animateScrollToItem(sel.row.coerceAtLeast(0)) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("streaming")) {
        val compact = maxHeight < 560.dp
        val wide = maxWidth >= 900.dp
        val tile = if (compact) 136.dp else 168.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = topPadding).fadingEdges(list, top = Space.xl, bottom = Space.xl),
            contentPadding = PaddingValues(top = subTabsRoom() + Space.s, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.m else Space.l),
        ) {
            item("head") {
                Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(Fuse.colors.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        FuseIcon(FuseIcons.MonitorPlay, size = Size.iconM, tint = Fuse.colors.accent)
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText("Stream from your computer", if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
                        FText(
                            if (client != null) "Moonlight is ready  ·  ${hosts.count { it.online == true }} of ${hosts.size} computers answering"
                            else "Moonlight isn't installed here: get it, pair it with your computer once, then stream from Fuse",
                            Fuse.type.caption, color = if (client != null) Fuse.colors.textMuted else Fuse.colors.warning, maxLines = 1,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        actions.forEachIndexed { i, a ->
                            val chosen = focused && row == "head" && col == i
                            ActionPill(a, chosen, wide || chosen) { sel.row = 0; sel.setColumn("head", i); a.run() }
                        }
                    }
                }
            }
            if (hosts.isEmpty()) item("none") {
                FText(
                    "Add the computer that runs Sunshine (or Apollo) by its address. Fuse wakes it when it sleeps and starts Moonlight on the game you choose.",
                    Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(horizontal = Space.gutter),
                )
            }
            for ((ri, h) in hosts.withIndex()) {
                item(h.host.id) {
                    val chosen = if (focused && sel.row == ri + 1) sel.column(h.host.id) else -1
                    HostShelf(h, chosen, tile) { i ->
                        app.focusZone = FocusZone.CONTENT
                        sel.row = ri + 1
                        sel.setColumn(h.host.id, i)
                        activate(h, i)
                    }
                }
            }
        }
    }
}

/** A computer's line (name, state) and its apps as tiles, with one to add another. */
@Composable
private fun HostShelf(h: StreamHostView, chosen: Int, tile: Dp, onClick: (Int) -> Unit) {
    val c = Fuse.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(h.online)
            Spacer(Modifier.width(Space.s))
            FText(h.host.name, Fuse.type.bodyStrong, maxLines = 1)
            Spacer(Modifier.width(Space.s))
            FText(stateWords(h), Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        val listState = rememberLazyListState()
        LaunchedEffect(chosen) { if (chosen >= 0) listState.animateScrollToItem((chosen - 1).coerceAtLeast(0)) }
        LazyRow(state = listState, contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            itemsIndexed(h.host.apps) { i, a -> AppTile(a, appIcon(a), chosen == i, tile, dim = h.online == false && !h.canWake) { onClick(i) } }
            item("add") { AppTile("Add App", FuseIcons.Plus, chosen == h.host.apps.size, tile, quiet = true) { onClick(h.host.apps.size) } }
        }
    }
}

@Composable
private fun AppTile(name: String, icon: ImageVector, selected: Boolean, size: Dp, quiet: Boolean = false, dim: Boolean = false, onClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val border by fuselineColor(if (selected) c.focus else Color.Transparent, Fuse.motion.tween(Durations.FAST), label = "streamTile")
    Column(
        Modifier.width(size).height(size * 0.72f).clip(shape)
            .background(if (quiet) c.text.copy(alpha = 0.05f) else c.surfaceRaised)
            .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = if (quiet) 0f else 0.14f), Color.Transparent)))
            .border(2.dp, border, shape)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(Space.m),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconS, tint = if (dim) c.textFaint else if (quiet) c.textMuted else c.accent)
        }
        FText(name, Fuse.type.bodyStrong, maxLines = 2, color = if (dim) c.textMuted else c.text)
    }
}

private fun appIcon(name: String): ImageVector = when {
    name.equals("Desktop", ignoreCase = true) -> FuseIcons.Monitor
    "steam" in name.lowercase() -> FuseIcons.Gamepad
    else -> FuseIcons.Play
}

private fun stateWords(h: StreamHostView): String = when {
    h.online == null -> "Looking..."
    h.online && h.info?.busy == true -> "Streaming now"
    h.online -> "Ready"
    h.canWake -> "Asleep: wakes when you stream"
    else -> "Not answering"
}

/** A computer's options: stream, wake, apps, edit, remove. */
private fun hostMenu(app: AppState, h: StreamHostView, focusedApp: String?): ContextMenuSpec {
    val ops = app.store.streaming
    return ContextMenuSpec(
        title = h.host.name,
        subtitle = listOfNotNull(h.host.address, stateWords(h)).joinToString("  ·  "),
        icon = FuseIcons.Monitor,
        actions = listOfNotNull(
            focusedApp?.let { a -> MenuAction("stream", "Stream $a", FuseIcons.Play, onSelect = { app.closeOverlays(); ops.stream(h.host.id, a) }) },
            MenuAction("wake", "Wake It", FuseIcons.Power, detail = if (h.host.mac.isBlank()) "Add its network card address first" else "Sends Wake-on-LAN to ${h.host.mac}", enabled = h.host.mac.isNotBlank(), onSelect = {
                app.closeOverlays()
                app.scope.launch {
                    if (!ops.wake(h.host.id)) {
                        app.toasts.show("Couldn't send the wake signal from this device", ToastKind.WARNING)
                        return@launch
                    }
                    app.toasts.show("Woken: ${h.host.name} should answer in a moment", ToastKind.SUCCESS, icon = FuseIcons.Power)
                    kotlinx.coroutines.delay(15_000)
                    ops.refresh()
                }
            }),
            MenuAction("app", "Add App", FuseIcons.Plus, detail = "By its name in Sunshine", onSelect = { app.closeOverlays(); app.addStreamApp(h) }),
            focusedApp?.let { a ->
                MenuAction("removeapp", "Remove $a", FuseIcons.Minus, onSelect = {
                    app.closeOverlays()
                    app.scope.launch { ops.changeHost(h.host.id) { it.copy(apps = it.apps - a) } }
                })
            },
            MenuAction("mac", "Network Card Address", FuseIcons.Network, detail = h.host.mac.ifBlank { "Not known: needed to wake it" }, onSelect = {
                app.closeOverlays()
                app.textInput = TextInputSpec("Network card address (MAC)", h.host.mac, "AA:BB:CC:DD:EE:FF", capitalize = false, doneLabel = "Save") { v ->
                    val mac = v.trim().uppercase().replace('-', ':')
                    if (mac.isNotEmpty() && !io.github.matiyaaa.fuse.integrations.stream.WakeOnLan.isMac(mac)) {
                        app.toasts.show("That isn't a network card address (six pairs, like AA:BB:CC:DD:EE:FF)", ToastKind.WARNING)
                    } else {
                        app.scope.launch { ops.changeHost(h.host.id) { it.copy(mac = mac) } }
                    }
                }
            }),
            MenuAction("rename", "Rename", FuseIcons.Pencil, onSelect = {
                app.closeOverlays()
                app.textInput = TextInputSpec("Name", h.host.name, doneLabel = "Save") { v -> if (v.isNotBlank()) app.scope.launch { ops.changeHost(h.host.id) { it.copy(name = v.trim()) } } }
            }),
            MenuAction("remove", "Remove Computer", FuseIcons.Trash, onSelect = {
                app.closeOverlays()
                app.confirm = io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec("Remove ${h.host.name}?", "Fuse forgets it. Nothing changes on the computer or in Moonlight.", "Remove", destructive = true) {
                    app.scope.launch { ops.removeHost(h.host.id) }
                }
            }),
        ),
    )
}

/** Asks for a computer's address and adds it, after asking it who it is. */
internal fun AppState.addStreamHost() {
    textInput = TextInputSpec("Computer's address", "", "192.168.1.20 or its name", capitalize = false, doneLabel = "Add") { address ->
        if (address.isBlank()) return@TextInputSpec
        scope.launch {
            toasts.show("Asking $address...", icon = FuseIcons.Search)
            store.streaming.addHost(address)
                .onSuccess { toasts.show("${it.name} added", ToastKind.SUCCESS, icon = FuseIcons.MonitorPlay) }
                .onFailure { e -> toasts.show(e.message ?: "Couldn't add it", ToastKind.WARNING) }
        }
    }
}

/** Asks for an app's name as Sunshine lists it, and adds it to [h]. */
internal fun AppState.addStreamApp(h: StreamHostView) {
    textInput = TextInputSpec("App name, as Sunshine lists it", "", "Desktop", doneLabel = "Add") { name ->
        val n = name.trim()
        if (n.isEmpty() || n in h.host.apps) return@TextInputSpec
        scope.launch { store.streaming.changeHost(h.host.id) { it.copy(apps = it.apps + n) } }
    }
}
