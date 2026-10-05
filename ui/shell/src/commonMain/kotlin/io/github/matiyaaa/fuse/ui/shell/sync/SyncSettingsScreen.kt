package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.HostView
import io.github.matiyaaa.fuse.sync.ProfileChange
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.SyncActivity
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
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
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.settings.choiceRow
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import io.github.matiyaaa.fuse.ui.shell.settings.toggleRow
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/** The page's own state while it is open. */
internal class SyncSettingsState {
    val sel = LinearSelection()
}

/**
 * Settings, Addons, Fuse Sync: one place for all of it. It leads with where things stand; off, it
 * says what it is and that nothing runs; not set up, it offers the two ways in (this computer as
 * the host, or connecting to one); set up, it holds who is playing, what syncs, the connection,
 * this device, the host's devices and service when this is the host, and leaving.
 */
@Composable
internal fun SyncSettingsScreen(app: AppState) {
    val svc = app.store.sync.service
    val prefs by app.store.prefs.collectAsState()
    val page = rememberRouteState(app.navigator, "sync.settings") { SyncSettingsState() }
    val reveal = rememberReveal()
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
    }
    if (svc == null) {
        val rows = listOf(infoRow("none", "Fuse Sync isn't part of this build", icon = FuseIcons.RefreshCcw))
        InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, page.sel) }
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            Panel { MenuList(rows, page.sel, modifier = Modifier.padding(Space.s)) }
        }
        return
    }
    val status by svc.status.collectAsState()
    val profiles by svc.profiles.collectAsState()
    val active by svc.activeProfile.collectAsState()
    val host by svc.host.collectAsState()
    val devices by svc.devices.collectAsState()
    val activity by svc.activity.collectAsState()
    val c = prefs.sync
    val words = syncWords(if (c.enabled) status else SyncStatus.Off)
    val rows = syncRows(app, svc, c, status, profiles, active, host, devices)
    page.sel.keepOn(rows.map { it.id })
    page.sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, page.sel)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        val wide = maxWidth >= 1040.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.reveal(reveal, 0), verticalAlignment = Alignment.CenterVertically) {
                SyncMark(if (compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    FText(SYNC_NAME, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(words.ok)
                        Spacer(Modifier.width(Space.s))
                        FText(words.title, Fuse.type.bodyStrong, maxLines = 1)
                        FText("  ·  ${words.detail}", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f, fill = !wide).fillMaxHeight().reveal(reveal, 1)) {
                    MenuList(rows, page.sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                }
                if (wide) SideCard(c, status, active, host, devices, activity, Modifier.width(320.dp).reveal(reveal, 2))
            }
        }
    }
}

/** On wide screens: who is playing, the host and its devices, and what Fuse Sync did lately. */
@Composable
private fun SideCard(
    c: SyncSettings,
    status: SyncStatus,
    active: ProfileInfo?,
    host: HostView?,
    devices: List<DeviceInfo>,
    activity: List<SyncActivity>,
    modifier: Modifier,
) {
    val colors = Fuse.colors
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (!c.enabled || c.role.isEmpty()) {
                FText(SYNC_BYLINE, Fuse.type.bodyStrong, maxLines = 1)
                for ((icon, line) in listOf(
                    FuseIcons.Save to "Saves and save states, there before you play and kept after",
                    FuseIcons.Clock to "Play time that adds up: 30 minutes here and 20 offline elsewhere is 50",
                    FuseIcons.Users to "A profile for each person, with their own library, Home and theme",
                    FuseIcons.Server to "On your own computer at home. Nothing goes to anyone else",
                )) {
                    Row(verticalAlignment = Alignment.Top) {
                        FuseIcon(icon, size = Size.iconS, tint = colors.accent)
                        Spacer(Modifier.width(Space.s))
                        FText(line, Fuse.type.caption, color = colors.textMuted, maxLines = 3)
                    }
                }
                return@Column
            }
            if (active != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(active.avatar, 52.dp)
                    Spacer(Modifier.width(Space.m))
                    Column {
                        FText("Playing as", Fuse.type.caption, color = colors.textMuted, maxLines = 1)
                        FText(active.name, Fuse.type.title, maxLines = 1)
                    }
                }
            }
            Fact("Host", c.hostName.ifBlank { host?.name ?: "Not connected" })
            Fact("Reaching it", when (status) {
                is SyncStatus.Online -> if (status.route == io.github.matiyaaa.fuse.sync.Route.LOCAL) "At home" else "From outside"
                is SyncStatus.Offline -> "Not right now"
                else -> "Not yet"
            })
            if (devices.isNotEmpty()) Fact("Devices", devices.filterNot { it.revoked }.joinToString(", ") { it.name })
            if (activity.isNotEmpty()) {
                FText("Lately", Fuse.type.caption, color = colors.textMuted, maxLines = 1)
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val offset = localOffsetMillis(now)
                for (a in activity.take(4)) {
                    Column {
                        FText(a.text, Fuse.type.label, maxLines = 2)
                        FText(TimeWords.relative(a.at, now, offset).replaceFirstChar { it.uppercase() }, Fuse.type.caption, color = colors.textFaint, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FText(label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        FText(value, Fuse.type.label, maxLines = 2)
    }
}

private fun syncRows(
    app: AppState,
    svc: SyncService,
    c: SyncSettings,
    status: SyncStatus,
    profiles: List<ProfileInfo>,
    active: ProfileInfo?,
    host: HostView?,
    devices: List<DeviceInfo>,
): List<MenuAction> = buildList {
    fun configure(change: (SyncSettings) -> SyncSettings) = app.scope.launch { app.store.sync.configure(change) }
    add(toggleRow("enabled", "Use $SYNC_NAME", FuseIcons.Power, c.enabled, "Your saves, play time, library and settings on every device, from a host of your own") { v ->
        if (v) app.scope.launch { app.store.sync.setEnabled(true) } else turnOffFuseSync(app)
    })
    if (!c.enabled) {
        add(infoRow("off", "Nothing runs while it's off", detail = "No profiles, no Sync tab and nothing in the background. Everything on this device stays exactly as it is", icon = FuseIcons.ShieldCheck))
        return@buildList
    }
    if (c.role.isEmpty() || status == SyncStatus.NotSetUp) {
        val setup = "Set up"
        if (svc.canHost) {
            add(MenuAction(
                "host", "Make This the Host", FuseIcons.Server,
                detail = "This computer keeps everyone's saves and records. Best on one that is often on",
                trailing = Trailing.Chevron, section = setup,
                onSelect = { app.go(Route.SyncSetup(host = true)) },
            ))
        }
        add(MenuAction(
            "connect", "Connect to a Host", FuseIcons.Link,
            detail = "Join the host you set up on another device, with the code it shows",
            trailing = Trailing.Chevron, section = setup,
            onSelect = { app.go(Route.SyncSetup(host = false)) },
        ))
        if (!svc.canHost) add(infoRow("android", "This device connects to a host", detail = "A computer at home is the host. Android stops background work to save battery, so it joins one instead", icon = FuseIcons.Info).copy(section = setup))
        return@buildList
    }

    // You ------------------------------------------------------------------------------------------
    val you = "You"
    add(MenuAction(
        "who", "Playing As", FuseIcons.UserRound,
        detail = if (active == null) "Choose who is playing on this device" else "Switching brings in their library, saves, Home and theme, without a restart",
        trailing = Trailing.Value(active?.name ?: "No one yet"), section = you,
        onSelect = { app.whoAreYou = WhoMode.SWITCH },
    ))
    add(MenuAction(
        "profiles", "Profiles", FuseIcons.Users,
        detail = "Add someone, rename a profile, set or remove a PIN",
        trailing = Trailing.Value(count(profiles.size, "profile")), section = you,
        onSelect = { manageProfiles(app, svc, profiles, active) },
    ))
    add(app.choiceRow(
        "startup", "At Startup", FuseIcons.Power,
        if (c.startup == "PROFILE") "PROFILE:${c.startupProfile}" else c.startup,
        listOf("LAST" to "The last one used", "ASK" to "Ask who's playing") + profiles.map { "PROFILE:${it.id}" to "Always ${it.name}" },
        detail = "Who Fuse starts as on this device",
    ) { v ->
        configure { s -> if (v.startsWith("PROFILE:")) s.copy(startup = "PROFILE", startupProfile = v.removePrefix("PROFILE:")) else s.copy(startup = v, startupProfile = "") }
    }.copy(section = you))

    // What syncs -----------------------------------------------------------------------------------
    val what = "What syncs"
    add(toggleRow("saves", "Saves", FuseIcons.Save, c.saves, "Each game's saves, put in place before it starts and kept after it closes") { v -> configure { it.copy(saves = v) } }.copy(section = what))
    add(MenuAction(
        "folders", "Save Folders", FuseIcons.FolderOpen,
        detail = "Where each emulator keeps its saves here, and a folder to choose where Fuse can't find them",
        trailing = Trailing.Chevron, section = what,
        onSelect = { app.go(Route.SaveFolders) },
    ))
    add(toggleRow("states", "Save States", FuseIcons.Layers, c.states, "Snapshots from the emulator's own menu, for the same emulator elsewhere") { v -> configure { it.copy(states = v) } }.copy(section = what))
    add(toggleRow("records", "Play Time and Library", FuseIcons.Clock, c.records, "Play time, Last Played, favourites, hidden and pinned games, names and collections") { v -> configure { it.copy(records = v) } }.copy(section = what))
    add(toggleRow("settings", "Settings", FuseIcons.Palette, c.settings, "Your theme, Home, tabs, quick menu, sounds and music. Controllers, screens and drives stay this device's own") { v -> configure { it.copy(settings = v) } }.copy(section = what))
    // Home's scope only means something while settings follow a profile.
    if (c.settings && active != null) {
        add(app.choiceRow(
            "home", "Home on This Device", FuseIcons.Home, if (c.homeScope == "DEVICE") "DEVICE" else "PROFILE",
            listOf("PROFILE" to "All devices on this profile", "DEVICE" to "This device only"),
            detail = "The same Home everywhere you play, or one laid out for this screen",
            optionDetail = {
                if (it == "DEVICE") "Changes here stay here. The profile's Home is kept for when you switch back"
                else "Widgets, pages and layout follow you. This device's own Home is kept for later"
            },
        ) { v -> app.scope.launch { app.store.sync.setOwnHome(v == "DEVICE") } }.copy(section = what))
    }

    // Connection -----------------------------------------------------------------------------------
    val connection = "Connection"
    add(MenuAction(
        "now", "Sync Now", FuseIcons.RefreshCcw,
        detail = syncWords(status).detail,
        trailing = Trailing.Value(syncWords(status).title), section = connection,
        onSelect = {
            app.scope.launch {
                svc.syncNow().onSuccess { app.toasts.show("Up to date", ToastKind.SUCCESS, icon = FuseIcons.CloudCheck) }
                    .onFailure { app.toasts.show(it.message ?: "The host isn't answering", ToastKind.WARNING) }
            }
        },
    ))
    if (c.role != "HOST") {
        add(MenuAction(
            "local", "Home Address", FuseIcons.Home,
            detail = "Used on the same network. Found by itself when you connected",
            trailing = Trailing.Value(c.localAddress.ifBlank { "Not set" }), section = connection,
            onSelect = {
                app.textInput = TextInputSpec("Home address", c.localAddress, "192.168.1.20:47311", capitalize = false) { v ->
                    app.scope.launch { app.store.sync.configure { it.copy(localAddress = v.trim()) }; svc.setEnabled(true) }
                }
            },
        ))
        add(MenuAction(
            "remote", "Outside Address", FuseIcons.Globe,
            detail = "For away from home: an address that reaches your host over the internet (a VPN such as Tailscale, or a tunnel with https)",
            trailing = Trailing.Value(c.remoteAddress.ifBlank { "Not set" }), section = connection,
            onSelect = {
                app.textInput = TextInputSpec("Outside address", c.remoteAddress, "https://sync.example.com", capitalize = false) { v ->
                    app.scope.launch { app.store.sync.configure { it.copy(remoteAddress = v.trim()) }; svc.setEnabled(true) }
                }
            },
        ))
    }
    add(MenuAction(
        "name", "This Device's Name", FuseIcons.MonitorSmartphone,
        detail = "How it shows on the host and in save history",
        trailing = Trailing.Value(c.deviceName.ifBlank { "This device" }), section = connection,
        onSelect = {
            app.textInput = TextInputSpec("This device's name", c.deviceName, "Steam Deck") { v ->
                val name = v.trim().take(32)
                if (name.isEmpty()) return@TextInputSpec
                app.scope.launch {
                    app.store.sync.configure { it.copy(deviceName = name) }
                    svc.renameDevice(c.deviceId, name)
                }
            }
        },
    ))

    // Host -----------------------------------------------------------------------------------------
    if (c.role == "HOST") {
        val hostSection = "This computer is the host"
        add(MenuAction(
            "pair", "Add a Device", FuseIcons.Plus,
            detail = "Shows a code to type on the other device. It works once, for ten minutes",
            trailing = Trailing.Chevron, section = hostSection,
            onSelect = { app.pairing = true },
        ))
        val linked = devices.filterNot { it.revoked }
        add(MenuAction(
            "devices", "Devices", FuseIcons.MonitorSmartphone,
            detail = linked.joinToString(", ") { it.name }.ifBlank { "Only this one so far" },
            trailing = Trailing.Value(count(linked.size, "device")), section = hostSection,
            onSelect = { manageDevices(app, svc, linked, c.deviceId) },
        ))
        val service = host?.service
        if (service != null) {
            add(toggleRow(
                "service", "Keep Running When Fuse Is Closed", FuseIcons.ServerCog, service.installed,
                listOfNotNull(service.description, service.caveat).joinToString(". "),
                enabled = service.supported,
            ) { v ->
                app.scope.launch {
                    val r = if (v) svc.installService() else svc.removeService()
                    r.onSuccess { app.toasts.show(if (v) "The host now runs on its own" else "The host runs while Fuse is open", ToastKind.SUCCESS) }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't change that", ToastKind.ERROR) }
                }
            }.copy(section = hostSection))
        }
        host?.status?.let { s ->
            add(infoRow(
                "storage", "Kept on the Host",
                value = sizeText(s.storageBytes),
                detail = "${count(s.revisionCount, "save version")} for ${count(s.profiles.size, "profile")}" + if (s.freeBytes >= 0) ", ${sizeText(s.freeBytes)} free" else "",
                icon = FuseIcons.HardDrive,
            ).copy(section = hostSection))
        }
        add(infoRow(
            "hub", "The Hub in a Browser", value = "127.0.0.1:${c.hostPort}/hub",
            detail = "Profiles, devices and storage at a glance, on this computer, with Fuse closed too",
            icon = FuseIcons.Globe,
        ).copy(section = hostSection))
        host?.addresses?.takeIf { it.isNotEmpty() }?.let { a ->
            add(infoRow("addresses", "Its Addresses", value = a.first(), detail = "Other devices on this network find it by themselves" + if (a.size > 1) ". Also ${a.drop(1).joinToString(", ")}" else "", icon = FuseIcons.Network).copy(section = hostSection))
        }
    }

    // Leaving --------------------------------------------------------------------------------------
    val leave = "Leave"
    if (c.role == "HOST") {
        add(MenuAction(
            "stop", "Stop Hosting", FuseIcons.CircleSlash, destructive = true,
            detail = "Other devices stop syncing until there is a host again. Everything stays on this computer",
            section = leave,
            onSelect = {
                app.confirm = ConfirmSpec(
                    "Stop hosting Fuse Sync?",
                    "Other devices keep everything they have and stop syncing until there is a host again. Every save, version and profile stays on this computer, so hosting again here carries on where it was.",
                    "Stop Hosting", destructive = true,
                ) {
                    app.scope.launch {
                        svc.stopHosting().onSuccess { app.toasts.show("This computer is no longer the host") }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't stop hosting", ToastKind.ERROR) }
                    }
                }
            },
        ))
    } else {
        add(MenuAction(
            "unlink", "Unlink This Device", FuseIcons.Unplug, destructive = true,
            detail = "Stops syncing here. Nothing on this device or the host is deleted",
            section = leave,
            onSelect = {
                app.confirm = ConfirmSpec(
                    "Unlink from ${c.hostName.ifBlank { "the host" }}?",
                    "This device keeps every game, save, setting and record exactly as it is now and stops syncing. The host keeps everything too. You can connect again any time.",
                    "Unlink", destructive = true,
                ) {
                    app.scope.launch {
                        svc.unlink().onSuccess { app.toasts.show("Unlinked. Everything here stays as it was") }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't unlink", ToastKind.ERROR) }
                    }
                }
            },
        ))
    }
}

/** Profiles, each with what can be changed, and adding one. */
private fun manageProfiles(app: AppState, svc: SyncService, profiles: List<ProfileInfo>, active: ProfileInfo?) {
    app.choice = ChoiceSpec(
        title = "Profiles",
        icon = FuseIcons.Users,
        message = "Each person's saves, play time, library and settings, on every device they use",
        options = profiles.map { p ->
            MenuAction(
                "p.${p.id}", p.name, io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars.of(p.avatar).glyph,
                detail = listOfNotNull(
                    "Playing here".takeIf { p.id == active?.id },
                    "PIN".takeIf { p.protected },
                    sizeText(p.storageBytes).takeIf { p.storageBytes > 0 }?.let { "$it of saves" },
                ).joinToString("  ·  ").ifBlank { null },
                trailing = Trailing.Chevron,
                onSelect = { profileMenu(app, svc, p, active) },
            )
        } + MenuAction("add", "Add Profile", FuseIcons.UserPlus, onSelect = {
            app.choice = null
            app.whoAreYou = WhoMode.ADD
        }),
    )
}

private fun profileMenu(app: AppState, svc: SyncService, p: ProfileInfo, active: ProfileInfo?) {
    fun run(block: suspend () -> Result<*>, done: String) {
        app.choice = null
        app.scope.launch { block().onSuccess { app.toasts.show(done, ToastKind.SUCCESS) }.onFailure { app.toasts.show(it.message ?: "Couldn't change that", ToastKind.ERROR) } }
    }
    fun withPin(title: String, next: (String?) -> Unit) {
        if (!p.protected) return next(null)
        app.textInput = TextInputSpec(title, "", "Current PIN", secret = true, capitalize = false, doneLabel = "Next") { next(it.filter(Char::isDigit)) }
    }
    app.choice = ChoiceSpec(
        title = p.name,
        icon = io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars.of(p.avatar).glyph,
        options = listOfNotNull(
            MenuAction("rename", "Rename", FuseIcons.Pencil, onSelect = {
                app.choice = null
                app.textInput = TextInputSpec("Rename ${p.name}", p.name, "Name") { v ->
                    val name = v.trim().take(24)
                    if (name.isNotEmpty()) withPin("${p.name}'s PIN") { pin -> run({ svc.changeProfile(p.id, ProfileChange(name = name, currentPin = pin)) }, "Renamed to $name") }
                }
            }),
            MenuAction("avatar", "Change Avatar", FuseIcons.Palette, onSelect = {
                app.choice = ChoiceSpec(
                    title = "Avatar",
                    icon = FuseIcons.Palette,
                    options = io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars.all.map { a ->
                        MenuAction("a.${a.id}", a.name, a.glyph, trailing = Trailing.Check(a.id == p.avatar), onSelect = {
                            withPin("${p.name}'s PIN") { pin -> run({ svc.changeProfile(p.id, ProfileChange(avatar = a.id, currentPin = pin)) }, "Avatar changed") }
                        })
                    },
                )
            }),
            MenuAction("pin", if (p.protected) "Change PIN" else "Set a PIN", FuseIcons.Lock, onSelect = {
                app.choice = null
                withPin("${p.name}'s current PIN") { current ->
                    app.textInput = TextInputSpec("New PIN for ${p.name}", "", "4 to 8 digits", secret = true, capitalize = false, doneLabel = "Set PIN") { v ->
                        val digits = v.filter(Char::isDigit)
                        if (digits.length !in 4..8) app.toasts.show("A PIN is 4 to 8 digits", ToastKind.WARNING)
                        else run({ svc.changeProfile(p.id, ProfileChange(pin = digits, currentPin = current)) }, "PIN set")
                    }
                }
            }),
            MenuAction("nopin", "Remove PIN", FuseIcons.LockOpen, onSelect = {
                app.choice = null
                withPin("${p.name}'s PIN") { pin -> run({ svc.changeProfile(p.id, ProfileChange(removePin = true, currentPin = pin)) }, "PIN removed") }
            }).takeIf { p.protected },
            MenuAction("delete", "Delete Profile", FuseIcons.Trash, destructive = true, onSelect = {
                app.choice = null
                app.confirm = ConfirmSpec(
                    "Delete ${p.name}?",
                    "Their saves, versions and records leave the host. What each device has right now stays on it." + if (p.id == active?.id) " This device stops using it." else "",
                    "Delete", destructive = true,
                ) { run({ svc.deleteProfile(p.id) }, "${p.name} was deleted") }
            }),
        ),
    )
}

/** The host's devices: when each was last seen, and renaming or unlinking one. */
private fun manageDevices(app: AppState, svc: SyncService, devices: List<DeviceInfo>, self: String) {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    app.choice = ChoiceSpec(
        title = "Devices",
        icon = FuseIcons.MonitorSmartphone,
        message = "Every device connected to this host",
        options = devices.map { d ->
            val icon = when (d.platform) {
                "ANDROID" -> FuseIcons.Smartphone
                else -> FuseIcons.Laptop
            }
            MenuAction(
                "d.${d.id}", d.name + if (d.id == self) " (this one)" else "", icon,
                detail = if (d.lastSeen > 0) "Seen ${TimeWords.relative(d.lastSeen, now, offset)}" else "Not seen yet",
                trailing = Trailing.Chevron,
                onSelect = {
                    app.choice = ChoiceSpec(
                        title = d.name,
                        icon = icon,
                        options = listOfNotNull(
                            MenuAction("rename", "Rename", FuseIcons.Pencil, onSelect = {
                                app.choice = null
                                app.textInput = TextInputSpec("Rename ${d.name}", d.name, "Name") { v ->
                                    val name = v.trim().take(32)
                                    if (name.isNotEmpty()) app.scope.launch { svc.renameDevice(d.id, name) }
                                }
                            }),
                            MenuAction("revoke", "Unlink", FuseIcons.Unplug, destructive = true, onSelect = {
                                app.choice = null
                                app.confirm = ConfirmSpec(
                                    "Unlink ${d.name}?",
                                    "It stops syncing at once and needs a new code to connect again. Everything on it, and everything it sent here, stays.",
                                    "Unlink", destructive = true,
                                ) { app.scope.launch { svc.revokeDevice(d.id).onSuccess { app.toasts.show("${d.name} was unlinked") } } }
                            }).takeIf { d.id != self },
                        ),
                    )
                },
            )
        },
    )
}
