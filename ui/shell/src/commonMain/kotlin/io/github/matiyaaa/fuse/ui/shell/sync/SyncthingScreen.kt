package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.data.settings.SyncthingSettings
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingDevice
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingFolder
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingPendingDevice
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingPlanFolder
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingService
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
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
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.settings.QrCode
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import io.github.matiyaaa.fuse.ui.shell.settings.toggleRow
import kotlinx.coroutines.launch

/** Syncthing's colour in Fuse: a teal of its own, so it never reads as Fuse Sync's accent. */
internal val SYNCTHING_TINT = Color(0xFF2EA8A0)

/** The page's own state while it is open: the folders Fuse would share, once worked out. */
internal class SyncthingPageState {
    val sel = LinearSelection()
    var plan by mutableStateOf<List<SyncthingPlanFolder>?>(null)
    var working by mutableStateOf(false)
}

/**
 * Settings, Addons, Syncthing: setting it up and everything after, on one page that follows where
 * things stand. Off, it says what it is and what Fuse recommends instead; not found, how to get it;
 * found, its key; connected, this device's ID (with a code to scan), the devices it syncs with,
 * the emulators' save folders it shares, how it behaves around a game, and leaving it.
 */
@Composable
internal fun SyncthingScreen(app: AppState) {
    val svc = app.store.syncthing
    val prefs by app.store.prefs.collectAsState()
    val page = rememberRouteState(app.navigator, "syncthing") { SyncthingPageState() }
    val reveal = rememberReveal()
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }
    if (svc == null) {
        val rows = listOf(infoRow("none", "Syncthing isn't part of this build", icon = FuseIcons.FolderSync))
        InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, page.sel) }
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            Panel { MenuList(rows, page.sel, modifier = Modifier.padding(Space.s)) }
        }
        return
    }
    val state by svc.state.collectAsState()
    val devices by svc.devices.collectAsState()
    val pending by svc.pendingDevices.collectAsState()
    val folders by svc.folders.collectAsState()
    // On or off is the service's: its state is Off exactly when Fuse isn't using Syncthing.
    val s = prefs.syncthing.copy(enabled = state !is SyncthingState.Off)
    // Connected: which save folders Fuse would share, worked out from the library (and again after sharing).
    LaunchedEffect(state is SyncthingState.Connected, folders.size) {
        if (state is SyncthingState.Connected) page.plan = runCatching { svc.planLibrary() }.getOrNull()
    }
    val rows = syncthingRows(app, svc, s, state, devices, pending, folders, page)
    page.sel.keepOn(rows.map { it.id })
    page.sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, page.sel)
    }
    val words = syncthingWords(if (s.enabled) state else SyncthingState.Off)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        val wide = maxWidth >= 1040.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.reveal(reveal, 0), verticalAlignment = Alignment.CenterVertically) {
                SyncthingMark(if (compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    FText("Syncthing", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(words.first)
                        Spacer(Modifier.width(Space.s))
                        FText(words.second, Fuse.type.bodyStrong, maxLines = 1)
                        FText("  ·  ${words.third}", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f, fill = !wide).fillMaxHeight().reveal(reveal, 1)) {
                    MenuList(rows, page.sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                }
                if (wide) SyncthingSide(app, state, Modifier.width(340.dp).reveal(reveal, 2))
            }
        }
    }
}

/**
 * Turns Fuse's use of Syncthing on or off. Fuse Sync and Syncthing would move the same saves, so
 * turning Syncthing on while Fuse Sync is on asks first, then turns Fuse Sync off on this device.
 */
internal fun useSyncthing(app: AppState, svc: SyncthingService, on: Boolean) {
    when {
        !on -> turnOffSyncthing(app)
        app.store.prefs.value.sync.enabled -> app.confirm = ConfirmSpec(
            title = "Use Syncthing instead of Fuse Sync?",
            message = "Both would move the same saves. Fuse Sync turns off on this device (its host and profiles stay as they are), and Syncthing takes over the save folders.",
            confirmLabel = "Use Syncthing",
        ) {
            // A Fuse Sync that is working asks once more before it stops.
            turnOffFuseSync(app) { svc.setEnabled(true) }
        }
        else -> app.scope.launch { svc.setEnabled(true) }
    }
}

/** Where things stand, as a dot (well, not, neither), a word, and a sentence. */
internal fun syncthingWords(state: SyncthingState): Triple<Boolean?, String, String> = when (state) {
    SyncthingState.Off -> Triple(null, "Off", "Fuse Sync is the one Fuse recommends; Syncthing is here for people who already run it")
    SyncthingState.Looking -> Triple(null, "Looking", "Finding Syncthing on this device")
    is SyncthingState.NotFound -> Triple(false, "Not found", if (state.installed) "It's installed but not running. Start it, then look again" else "Syncthing isn't running on this device")
    is SyncthingState.NeedsKey -> Triple(null, if (state.refused) "Wrong key" else "Needs its key", "Found at ${state.address}. Fuse needs its API key once")
    is SyncthingState.Connected -> Triple(true, "Connected", "Syncthing ${state.version} on ${state.deviceName.ifBlank { "this device" }}")
    is SyncthingState.Unreachable -> Triple(false, "Not answering", "It was at ${state.address}. Is it still running?")
}

private fun syncthingRows(
    app: AppState,
    svc: SyncthingService,
    s: SyncthingSettings,
    state: SyncthingState,
    devices: List<SyncthingDevice>,
    pending: List<SyncthingPendingDevice>,
    folders: List<SyncthingFolder>,
    page: SyncthingPageState,
): List<MenuAction> = buildList {
    fun busy(what: suspend () -> Unit) {
        if (page.working) return
        page.working = true
        app.scope.launch {
            try { what() } finally { page.working = false }
        }
    }
    add(toggleRow("enabled", "Use Syncthing", FuseIcons.Power, s.enabled, "Keep your emulators' save folders in step through the Syncthing you run") { v -> useSyncthing(app, svc, v) })
    if (!s.enabled) {
        add(MenuAction("fusesync", "Fuse Sync Instead", FuseIcons.RefreshCcw, detail = "Knows each game whatever its file is called, adds up play time, and gives each person their own saves. Syncthing keeps one save per game for everyone", trailing = Trailing.Chevron, onSelect = {
            app.go(io.github.matiyaaa.fuse.ui.shell.app.Route.SyncSettings)
        }))
        return@buildList
    }
    val setup = "Setting it up"
    when (state) {
        SyncthingState.Off, SyncthingState.Looking -> add(infoRow("looking", "Looking for Syncthing", icon = FuseIcons.Search).copy(section = setup))
        is SyncthingState.NotFound -> {
            val install = svc.install
            if (state.installed && install.canStart) {
                add(MenuAction("start", "Start ${install.name}", FuseIcons.Play, detail = "Opens it so it starts, then come back here", section = setup, onSelect = {
                    if (svc.startApp()) app.toasts.show("Starting ${install.name}. Come back once it is running") else app.toasts.show("Couldn't open ${install.name}")
                }))
            } else {
                // From Fuse's own Store where there is one: installed there, setup carries on here by itself.
                val storeKey = app.store.appStore.takeIf { it.supported }?.keyFor(SYNCTHING_FORK_PACKAGE)
                add(MenuAction(
                    "get", "Get ${install.name}", FuseIcons.Download,
                    detail = if (storeKey != null) "From the Store, here in Fuse. Setup carries on once it is installed" else install.note,
                    trailing = Trailing.Chevron, section = setup,
                    onSelect = {
                        if (storeKey != null) {
                            app.awaitingSyncthing = true
                            app.go(io.github.matiyaaa.fuse.ui.shell.app.Route.StoreApp(storeKey))
                        } else {
                            app.platform.openUrl(install.url)
                        }
                    },
                ))
            }
            add(MenuAction("again", "Look Again", FuseIcons.RefreshCcw, section = setup, onSelect = { busy { svc.find() } }))
            add(manualRow(app, svc, section = setup))
        }
        is SyncthingState.NeedsKey -> {
            add(MenuAction("key", if (state.refused) "Enter the Key Again" else "Enter Its API Key", FuseIcons.Key, detail = keyWhere(svc), section = setup, onSelect = {
                app.textInput = TextInputSpec("Syncthing's API key", "", "Paste the key", secret = true, capitalize = false, doneLabel = "Connect") { key ->
                    if (key.isBlank()) return@TextInputSpec
                    busy {
                        val r = svc.connect(state.address, key)
                        when (r.getOrNull()) {
                            is SyncthingState.Connected -> app.toasts.show("Connected to Syncthing", ToastKind.SUCCESS)
                            is SyncthingState.NeedsKey -> app.toasts.show("Syncthing didn't take that key", ToastKind.ERROR)
                            else -> app.toasts.show(r.exceptionOrNull()?.message ?: "Couldn't connect", ToastKind.ERROR)
                        }
                    }
                }
            }))
            add(manualRow(app, svc, section = setup))
        }
        is SyncthingState.Unreachable -> {
            if (svc.install.canStart) add(MenuAction("start", "Start ${svc.install.name}", FuseIcons.Play, section = setup, onSelect = { svc.startApp() }))
            add(MenuAction("again", "Look Again", FuseIcons.RefreshCcw, section = setup, onSelect = { busy { svc.find() } }))
        }
        is SyncthingState.Connected -> {
            // This device, to add on the others.
            val me = "This device"
            add(MenuAction("id", "Device ID", FuseIcons.QrCode, detail = state.deviceId.take(15) + "…", trailing = Trailing.Value("Show"), section = me, onSelect = {
                showDeviceId(app, state.deviceId, state.deviceName)
            }))
            // The devices it syncs with, and any waiting to be added.
            val others = devices.filterNot { it.self }
            val people = "Devices"
            for (p in pending) {
                add(MenuAction("pending.${p.id}", "Add ${p.name}", FuseIcons.UserPlus, detail = "Asked to connect" + (p.address?.let { " from $it" } ?: ""), trailing = Trailing.Value("Waiting"), section = people, onSelect = {
                    busy {
                        svc.addDevice(p.id, p.name)
                            .onSuccess { app.toasts.show("${p.name} added. Fuse's save folders are shared with it", ToastKind.SUCCESS) }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't add it", ToastKind.ERROR) }
                    }
                }))
            }
            for (d in others) {
                add(MenuAction(
                    "device.${d.id}", d.name, if (d.connected) FuseIcons.MonitorSmartphone else FuseIcons.Unplug,
                    detail = when {
                        d.paused -> "Paused in Syncthing"
                        d.connected -> "Connected" + (d.address?.let { ", $it" } ?: "")
                        else -> "Not connected now"
                    },
                    trailing = Trailing.Value(if (d.connected) "Online" else "Offline"),
                    section = people,
                    onSelect = {
                        app.confirm = ConfirmSpec("Remove ${d.name}?", "Your save folders stop syncing with it. Anything else you share with it in Syncthing carries on, and the files on both devices stay as they are.", "Remove", destructive = true) {
                            busy { svc.removeDevice(d.id) }
                        }
                    },
                ))
            }
            add(MenuAction("add", "Add a Device", FuseIcons.Plus, detail = if (others.isEmpty()) "Type or paste your other device's ID, from its Syncthing" else "Another of your devices", section = people, onSelect = {
                promptAddDevice(app, svc)
            }))
            // The save folders: shared, waiting to be, or not possible here.
            val saves = "Save folders"
            val plan = page.plan
            if (plan == null) {
                add(infoRow("plan", "Finding your emulators' save folders", icon = FuseIcons.Search).copy(section = saves))
            } else if (plan.isEmpty()) {
                add(infoRow("plan", "No save folders yet", detail = "Add games to your library, and their emulators' save folders show here", icon = FuseIcons.FolderOpen).copy(section = saves))
            } else {
                val waiting = plan.filter { !it.shared && it.blocked == null }
                if (waiting.isNotEmpty()) {
                    add(MenuAction("share", "Share ${count(waiting.size, "Save Folder")}", FuseIcons.FolderSync, detail = "With every device you add, keeping older versions" + if (others.isEmpty()) ". Add a device too" else "", section = saves, onSelect = {
                        busy {
                            svc.share(waiting, s.keepVersions)
                                .onSuccess { n -> app.toasts.show("Sharing ${count(n, "folder")}", ToastKind.SUCCESS); page.plan = svc.planLibrary() }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't share them", ToastKind.ERROR) }
                        }
                    }))
                }
                for (f in plan) {
                    val live = folders.firstOrNull { it.id == f.id || it.path == f.path }
                    add(MenuAction(
                        "folder.${f.id}", "${f.emulator} ${kindWord(f.kind)}", kindIcon(f.kind),
                        detail = f.blocked ?: f.path,
                        trailing = Trailing.Value(
                            when {
                                f.blocked != null -> "Can't"
                                live == null -> "Not shared"
                                live.error != null -> "Problem"
                                live.paused -> "Paused"
                                others.none { it.id in live.devices } -> "Only here"
                                live.needBytes > 0 -> "${bytesText(live.needBytes)} to go"
                                live.state == "scanning" -> "Looking"
                                live.state == "syncing" -> "Syncing"
                                else -> "Up to date"
                            },
                        ),
                        section = saves,
                        onSelect = {
                            // A folder Fuse can't reach: choosing where it is happens on Save Folders.
                            if (f.blocked != null) app.go(io.github.matiyaaa.fuse.ui.shell.app.Route.SaveFolders)
                            else if (live != null) {
                                app.confirm = ConfirmSpec("Stop sharing ${f.emulator} ${kindWord(f.kind)}?", "Syncthing stops syncing this folder. The saves in it stay on every device.", "Stop sharing", destructive = true) {
                                    busy { svc.unshare(live.id); page.plan = svc.planLibrary() }
                                }
                            }
                        },
                    ))
                }
            }
            add(MenuAction("where", "Where Saves Are", FuseIcons.FolderOpen, detail = "Each emulator's save folder here, and one to choose where Fuse can't find it", trailing = Trailing.Chevron, section = saves, onSelect = {
                app.go(io.github.matiyaaa.fuse.ui.shell.app.Route.SaveFolders)
            }))
            // Syncthing moves folders, not people's saves: say so where it matters.
            add(infoRow(
                "everyone", "One Save per Game, for Everyone",
                detail = "Syncthing keeps one save per game for everyone who plays on these devices. Fuse Sync gives each person their own",
                icon = FuseIcons.Users,
            ).copy(section = saves))
            // Around each game.
            val play = "Around a game"
            add(toggleRow("wait", "Bring In the Newest Save First", FuseIcons.Timer, s.waitBeforePlaying, "Before a game starts, a moment for Syncthing to bring in what another device saved") { v ->
                app.store.updatePrefs { it.copy(syncthing = it.syncthing.copy(waitBeforePlaying = v)) }
            }.copy(section = play))
            add(toggleRow("versions", "Keep Older Versions", FuseIcons.History, s.keepVersions, "Folders Fuse shares keep a month of old saves, thinned out as they age") { v ->
                app.store.updatePrefs { it.copy(syncthing = it.syncthing.copy(keepVersions = v)) }
            }.copy(section = play))
            add(MenuAction("leave", "Disconnect Syncthing", FuseIcons.LogOut, destructive = true, detail = "Fuse forgets its key. Syncthing and your folders carry on as they are", section = "Leaving", onSelect = {
                // Connected, so it asks twice: what stops, then once more.
                confirmTurnOff(
                    app, "Syncthing", working = true,
                    first = "Fuse stops using it and forgets its key. Syncthing keeps running and syncing what it shares.",
                    second = "Fuse won't bring in the newest save before a game, or ask when two devices both played, until you connect it again.",
                ) { busy { svc.disconnect() } }
            }))
        }
    }
}

/** Asks for another device's ID and its name, then adds it and shares Fuse's folders with it. */
internal fun promptAddDevice(app: AppState, svc: SyncthingService) {
    app.textInput = TextInputSpec("The other device's ID", "", "XXXXXXX-XXXXXXX-…", capitalize = false, doneLabel = "Next") { id ->
        val normal = SyncthingService.normaliseDeviceId(id)
        if (normal == null) {
            app.toasts.show("That isn't a device ID: eight groups of seven letters and numbers", ToastKind.ERROR)
            return@TextInputSpec
        }
        app.textInput = TextInputSpec("What is it called?", "", "Steam Deck", doneLabel = "Add") { name ->
            app.scope.launch {
                svc.addDevice(normal, name.trim())
                    .onSuccess { app.toasts.show("Added. Accept this device on it too, and the folders follow", ToastKind.SUCCESS) }
                    .onFailure { app.toasts.show(it.message ?: "Couldn't add it", ToastKind.ERROR) }
            }
        }
    }
}

/** Where to find Syncthing's API key, on this kind of device. */
private fun keyWhere(svc: SyncthingService): String = if (svc.install.canStart) {
    "In Syncthing-Fork, open its Web GUI, then Actions, Settings, General: API Key"
} else {
    "In Syncthing's web page (127.0.0.1:8384), Actions, Settings, General: API Key"
}

private fun manualRow(app: AppState, svc: SyncthingService, section: String) =
    MenuAction("manual", "Enter Its Address", FuseIcons.Link2, detail = "When it answers somewhere else than 127.0.0.1:8384", section = section, onSelect = {
        app.textInput = TextInputSpec("Syncthing's address", "127.0.0.1:8384", "127.0.0.1:8384", capitalize = false, doneLabel = "Next") { address ->
            app.textInput = TextInputSpec("Its API key", "", "Paste the key", secret = true, capitalize = false, doneLabel = "Connect") { key ->
                app.scope.launch {
                    svc.connect(address.trim(), key.trim()).fold(
                        onSuccess = { st -> if (st is SyncthingState.Connected) app.toasts.show("Connected to Syncthing", ToastKind.SUCCESS) else app.toasts.show("Syncthing didn't take that key", ToastKind.ERROR) },
                        onFailure = { app.toasts.show(it.message ?: "Couldn't connect", ToastKind.ERROR) },
                    )
                }
            }
        }
    })

/** This device's ID, large and grouped, with its code to scan from Syncthing on the other device. */
private fun showDeviceId(app: AppState, id: String, name: String) {
    app.choice = ChoiceSpec(
        title = name.ifBlank { "This device" },
        icon = FuseIcons.QrCode,
        message = "On your other device, add this one in Syncthing: type the ID, or scan its code on a wider screen.",
        options = id.split('-').chunked(4).mapIndexed { i, groups ->
            MenuAction("id.$i", groups.joinToString("-"), FuseIcons.Key, onSelect = { app.choice = null })
        },
    )
}

private fun kindWord(kind: SaveKind) = when (kind) {
    SaveKind.SAVE -> "saves"
    SaveKind.STATE -> "save states"
    SaveKind.MEMORY_CARD -> "memory cards"
}

private fun kindIcon(kind: SaveKind) = when (kind) {
    SaveKind.SAVE -> FuseIcons.Save
    SaveKind.STATE -> FuseIcons.History
    SaveKind.MEMORY_CARD -> FuseIcons.Archive
}

/** On wide screens: what Syncthing does in Fuse, and what Fuse Sync does that it can't. */
@Composable
private fun SyncthingSide(app: AppState, state: SyncthingState, modifier: Modifier) {
    val c = Fuse.colors
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (state is SyncthingState.Connected) {
                // This device's ID as a code: Syncthing on a phone adds a device by scanning it.
                val modules = remember(state.deviceId) { app.phoneLink?.qr(state.deviceId) }
                FText("Add this device elsewhere", Fuse.type.bodyStrong, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (modules != null) {
                        QrCode(modules, 112.dp)
                        Spacer(Modifier.width(Space.m))
                    }
                    FText(
                        state.deviceId.split('-').chunked(2).joinToString("\n") { it.joinToString("-") },
                        Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 4,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            }
            FText("With Syncthing", Fuse.type.bodyStrong, maxLines = 1)
            for ((icon, line) in listOf(
                FuseIcons.FolderSync to "Your emulators' save folders, shared through the Syncthing you already run",
                FuseIcons.Timer to "Before a game starts, the newest save is brought in; after it closes, it goes out",
                FuseIcons.GitCompare to "When two devices both played, Fuse asks which save to keep",
            )) {
                Row(verticalAlignment = Alignment.Top) {
                    FuseIcon(icon, size = Size.iconS, tint = SYNCTHING_TINT)
                    Spacer(Modifier.width(Space.s))
                    FText(line, Fuse.type.caption, color = c.textMuted, maxLines = 3)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            FText("Fuse Sync does more", Fuse.type.bodyStrong, maxLines = 1)
            FText(
                "Syncthing moves files as they are named, so a save only matches where the game's file has the same name on every device, and everyone shares one save per game. Fuse Sync knows each game by what it is, adds up play time, and gives each person their own saves.",
                Fuse.type.caption, color = c.textMuted, maxLines = 6,
            )
        }
    }
}

/** Syncthing's mark in Fuse: linked folders in a well of its teal. */
@Composable
internal fun SyncthingMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(SYNCTHING_TINT.copy(alpha = 0.3f), SYNCTHING_TINT.copy(alpha = 0.1f)))),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(FuseIcons.FolderSync, size = size * 0.48f, tint = SYNCTHING_TINT)
    }
}

/** Syncthing-Fork's package, as the Store offers it. */
internal const val SYNCTHING_FORK_PACKAGE = "com.github.catfriend1.syncthingfork"
