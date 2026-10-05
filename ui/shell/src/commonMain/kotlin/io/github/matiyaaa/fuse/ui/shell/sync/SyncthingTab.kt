package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingDevice
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingFolder
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingPendingDevice
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingState
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.settings.QrCode
import kotlinx.coroutines.launch

/**
 * Addons, Syncthing: what Syncthing is doing with your saves, the way Addons, Sync shows Fuse Sync.
 * Where things stand with Look Over Now, Add a Device and Settings; how many devices are online,
 * the folders shared and what is still coming in; every save folder Fuse shares and how far along
 * it is. Beside it on wide screens: this device's ID to scan, and every device it syncs with.
 */
@Composable
internal fun SyncthingTab(app: AppState, active: Boolean, topPadding: Dp) {
    val svc = app.store.syncthing ?: return
    val state by svc.state.collectAsState()
    val devices by svc.devices.collectAsState()
    val pending by svc.pendingDevices.collectAsState()
    val folders by svc.folders.collectAsState()
    val prefs by app.store.prefs.collectAsState()
    val connected = state as? SyncthingState.Connected
    val words = syncthingWords(state)
    var index by remember { mutableIntStateOf(0) }
    var inList by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf(false) }
    val sel = remember { LinearSelection() }
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val reveal = rememberReveal()
    val others = devices.filterNot { it.self }
    val shared = folders.filter { it.isFuses }

    val actions = buildList<Triple<String, ImageVector, () -> Unit>> {
        if (connected != null) {
            add(Triple("Look Over Now", FuseIcons.RefreshCcw) {
                if (!looking) {
                    looking = true
                    app.scope.launch {
                        runCatching { svc.refresh() }
                        app.toasts.show("Syncthing is looking over your saves", ToastKind.SUCCESS, icon = FuseIcons.FolderSync)
                        looking = false
                    }
                }
            })
            add(Triple("Add a Device", FuseIcons.Plus) { promptAddDevice(app, svc) })
            add(Triple("Settings", FuseIcons.Settings) { app.go(Route.SyncthingSettings) })
        } else {
            add(Triple("Set Up", FuseIcons.Settings) { app.go(Route.SyncthingSettings) })
        }
    }
    val rows = shared.map { f -> folderRow(app, f, others) }
    sel.clamp(rows.size)
    PageEffect(focused, inList) {
        if (focused) app.hints = listOf(Hint(HintButton.CONFIRM, if (inList) "Open" else "Choose"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = focused) { e ->
        if (inList) {
            when {
                e.action == NavAction.UP && sel.index == 0 -> { inList = false; NavResult.MOVED }
                e.action == NavAction.LEFT || e.action == NavAction.RIGHT -> NavResult.BLOCKED
                else -> handleMenuAction(e, rows, sel)
            }
        } else {
            when (e.action) {
                NavAction.LEFT -> if (index > 0) { index--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (index < actions.size - 1) { index++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { actions.getOrNull(index)?.third?.invoke(); NavResult.ACTIVATED }
                NavAction.DOWN -> if (rows.isNotEmpty()) { inList = true; NavResult.MOVED } else NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The devices and this device's code sit beside the folders wherever there is room for both.
        val side = maxWidth >= 820.dp
        val roomy = maxWidth >= 1200.dp
        val compact = maxHeight < 560.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = Space.gutter)
                .padding(top = topPadding + subTabsRoom() + Space.m, bottom = Size.hintHeight + Space.s),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            val need = shared.sumOf { it.needBytes }
            AddonHero(
                mark = { SyncthingMark(it) },
                title = "Syncthing",
                tag = connected?.let { "On this device" },
                tagIcon = FuseIcons.MonitorSmartphone,
                tint = SYNCTHING_TINT,
                ok = words.first, status = words.second, detail = words.third,
                facts = if (connected == null) emptyList() else listOfNotNull(
                    HeroFact(FuseIcons.MonitorSmartphone, "${others.count { it.connected }}/${others.size}", "devices online"),
                    HeroFact(FuseIcons.FolderSync, "${shared.size}", if (shared.size == 1) "save folder" else "save folders"),
                    HeroFact(FuseIcons.Download, if (need > 0) bytesText(need) else "Nothing", "still coming in"),
                    HeroFact(FuseIcons.History, if (prefs.syncthing.keepVersions) "A month" else "None", "of older versions"),
                ),
                compact = compact,
                modifier = Modifier.reveal(reveal, 0),
            )
            Row(Modifier.reveal(reveal, 1), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                actions.forEachIndexed { i, (label, icon, run) ->
                    FuseButton(
                        label, selected = focused && !inList && index == i, onClick = { index = i; inList = false; run() }, icon = icon,
                        kind = if (i == 0) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                        loading = i == 0 && looking,
                        height = if (compact) 40.dp else Size.touch,
                    )
                }
            }
            Row(Modifier.weight(1f).reveal(reveal, 2), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Column {
                        SectionTitle(
                            "Save folders it shares", FuseIcons.FolderSync,
                            trailing = shared.takeIf { it.isNotEmpty() }?.let { count(it.size, "folder") },
                            modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.xs),
                            tint = SYNCTHING_TINT,
                        )
                        when {
                            connected == null -> Quiet(
                                FuseIcons.Unplug,
                                if (state is SyncthingState.NeedsKey || state is SyncthingState.NotFound) "Finish setting Syncthing up, and your save folders show here." else "Syncthing isn't answering. Its folders show here once it does.",
                            )
                            rows.isEmpty() -> Quiet(FuseIcons.FolderSync, "No save folders shared yet. Open Settings to share your emulators' save folders.")
                            else -> MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = focused && inList)
                        }
                    }
                }
                if (side) {
                    Column(Modifier.width(if (roomy) 380.dp else if (compact) 290.dp else 320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        DevicesCard(others, pending)
                        if (connected != null) ThisDeviceCard(app, connected)
                    }
                }
            }
        }
    }
}

/** A shared save folder as its row reads: its name, where it is, and how far along it is. */
private fun folderRow(app: AppState, f: SyncthingFolder, others: List<SyncthingDevice>): MenuAction {
    val with = others.filter { it.id in f.devices }.map { it.name }
    val detail = listOfNotNull(
        f.path,
        when {
            with.isEmpty() -> "not shared with a device yet"
            else -> "with " + with.joinToString(", ")
        },
    ).joinToString("  ·  ")
    return MenuAction(
        "folder.${f.id}", f.label.ifBlank { f.id }, folderIcon(f),
        detail = f.error ?: detail,
        trailing = Trailing.Value(
            when {
                f.error != null -> "Problem"
                f.paused -> "Paused"
                f.needBytes > 0 -> "${bytesText(f.needBytes)} to go"
                f.state == "scanning" -> "Looking"
                f.state == "syncing" -> "Syncing"
                else -> "Up to date"
            },
        ),
        onSelect = { app.go(Route.SyncthingSettings) },
    )
}

/** What a folder holds, at a glance: states, memory cards, or saves. */
private fun folderIcon(f: SyncthingFolder): ImageVector {
    val name = (f.label + " " + f.path).lowercase()
    return when {
        f.error != null -> FuseIcons.Warning
        "state" in name -> FuseIcons.Layers
        "card" in name || "memcard" in name -> FuseIcons.Chip
        "save" in name -> FuseIcons.Save
        else -> FuseIcons.FolderSync
    }
}

@Composable
private fun StatTile(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Row(
        modifier.clip(shape).background(c.surface.copy(alpha = 0.72f)).border(1.dp, c.hairline, shape).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(SYNCTHING_TINT.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconS, tint = SYNCTHING_TINT)
        }
        Spacer(Modifier.width(Space.m))
        Column {
            FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            FText(value, Fuse.type.bodyStrong, maxLines = 1)
        }
    }
}

/** This device's ID as a code to scan, and grouped to type. */
@Composable
private fun ThisDeviceCard(app: AppState, state: SyncthingState.Connected) {
    val modules = remember(state.deviceId) { app.phoneLink?.qr(state.deviceId) }
    Card("This device", FuseIcons.QrCode) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (modules != null) {
                QrCode(modules, 104.dp)
                Spacer(Modifier.width(Space.m))
            }
            FText(
                state.deviceId.split('-').chunked(2).joinToString("\n") { it.joinToString("-") },
                Fuse.type.caption.tabular(), color = Fuse.colors.textMuted, maxLines = 4,
            )
        }
    }
}

/** Every device Syncthing syncs with, online first, and any asking to join. */
@Composable
private fun DevicesCard(others: List<SyncthingDevice>, pending: List<SyncthingPendingDevice>) {
    val c = Fuse.colors
    Card("Devices", FuseIcons.MonitorSmartphone) {
        if (others.isEmpty() && pending.isEmpty()) {
            FText("None yet. Add your other devices, and accept this one on each.", Fuse.type.caption, color = c.textMuted, maxLines = 3)
        }
        for (d in others.sortedByDescending { it.connected }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.MonitorSmartphone, size = Size.iconS, tint = c.text)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(d.name, Fuse.type.bodyStrong, maxLines = 1)
                    FText(if (d.paused) "Paused" else if (d.connected) "Online" else "Offline", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.connected) c.success else c.textFaint))
            }
        }
        for (p in pending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.UserPlus, size = Size.iconS, tint = SYNCTHING_TINT)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(p.name, Fuse.type.label, maxLines = 1)
                    FText("Asking to join. Add it in Settings", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}
