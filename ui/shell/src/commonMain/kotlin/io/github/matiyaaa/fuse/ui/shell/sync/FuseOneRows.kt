package io.github.matiyaaa.fuse.ui.shell.sync

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.HostView
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.RecoveryJob
import io.github.matiyaaa.fuse.sync.SyncApi
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.settings.choiceRow
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import io.github.matiyaaa.fuse.ui.shell.settings.toggleRow
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/**
 * Fuse One's own rows for Settings, Fuse One: whether it runs, setting it up, who is playing, what
 * syncs, the connection, this device as the host, and leaving, each under its own heading. The
 * section arranges them ([io.github.matiyaaa.fuse.ui.shell.settings.fuseOneRows]).
 */
internal fun syncRows(
    app: AppState,
    svc: SyncService,
    c: SyncSettings,
    status: SyncStatus,
    profiles: List<ProfileInfo>,
    active: ProfileInfo?,
    host: HostView?,
    devices: List<DeviceInfo>,
    hosts: io.github.matiyaaa.fuse.sync.HostsView? = null,
    backups: io.github.matiyaaa.fuse.sync.HouseholdBackupsState? = null,
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

    // Who is playing, and every profile, are in Settings, Profiles: never a second place for them here.

    // What syncs -----------------------------------------------------------------------------------
    val what = "What syncs"
    // Saves move with one of Fuse One, RomM or Syncthing on this device (or none): chosen here.
    add(saveProviderRow(app, section = what, id = "saves"))
    // Android may stop Fuse soon after a game takes the screen: the save then goes only once Fuse is
    // open again. Said where saves are turned on, with the way to lift it.
    app.platform.background?.takeIf { c.saves && it.limited }?.let { limits ->
        add(MenuAction(
            "background", "Saves in the Background", FuseIcons.BatteryWarning,
            detail = if (limits.restricted) {
                "Android restricts Fuse in the background, so a save waits until Fuse is open again. Set its battery use to Unrestricted"
            } else {
                "Android's battery optimization can stop Fuse while a game plays, so a save may wait until Fuse is open again. Set its battery use to Unrestricted"
            },
            trailing = Trailing.Chevron, section = what,
            onSelect = { app.platform.openBackgroundSettings() },
        ))
    }
    // Whose saves move on this device: its own choice (a child's handheld that never takes a
    // grown-up's saves, a profile kept to one device).
    if (c.saves && profiles.isNotEmpty()) {
        val names = profiles.associate { it.id to it.name }
        fun summary(ids: List<String>) = ids.mapNotNull { names[it] }.let { if (it.isEmpty()) "Nobody" else if (it.size <= 2) it.joinToString(", ") else "${it.size} profiles" }
        add(MenuAction(
            "pulloff", "Don't Take Saves From", FuseIcons.CloudOff,
            detail = "Their saves made elsewhere never come to this device. This device still sends theirs",
            trailing = Trailing.Value(summary(c.noPullProfiles)), section = what,
            onSelect = { profileChoices(app, "Don't take saves from", "Saves these people make on other devices stay off this one. Play time and the library still sync.", profiles, c.noPullProfiles.toSet()) { s, ids -> s.copy(noPullProfiles = ids) } },
        ))
        add(MenuAction(
            "saveoff", "Don't Sync Saves For", FuseIcons.CircleSlash,
            detail = "Their saves on this device stay here only: nothing comes in, nothing goes out",
            trailing = Trailing.Value(summary(c.noSyncProfiles)), section = what,
            onSelect = { profileChoices(app, "Don't sync saves for", "These people's saves on this device never leave it, and none come in. Play time and the library still sync.", profiles, c.noSyncProfiles.toSet()) { s, ids -> s.copy(noSyncProfiles = ids) } },
        ))
    }
    add(MenuAction(
        "folders", "Save Folders", FuseIcons.FolderOpen,
        detail = "Where each emulator keeps its saves here, and a folder to choose where Fuse can't find them",
        trailing = Trailing.Chevron, section = what,
        onSelect = { app.go(Route.SaveFolders) },
    ))
    add(MenuAction("import", "Import Saves", FuseIcons.FileUp, detail = "Copy a file, folder or save archive, with a preview and safety history", section = what, onSelect = { openSaveImporter(app) }))
    // Save states go with Fuse One's saves: with RomM or Syncthing moving them, there is nothing to turn on here.
    if (c.saves) add(toggleRow("states", "Save States", FuseIcons.Layers, c.states, "Snapshots from the emulator's own menu, for the same emulator elsewhere") { v -> configure { it.copy(states = v) } }.copy(section = what))
    add(toggleRow("records", "Play Time and Library", FuseIcons.Clock, c.records, "Play time, Last Played, favourites, hidden and pinned games, names and collections") { v -> configure { it.copy(records = v) } }.copy(section = what))
    add(toggleRow("settings", "Settings", FuseIcons.Palette, c.settings, "Your theme, Home, tabs, quick menu, sounds and music. Controllers, screens and drives stay this device's own") { v -> configure { it.copy(settings = v) } }.copy(section = what))
    add(toggleRow(
        "signins", "Share Sign-ins", FuseIcons.Key, c.shareSignIns,
        "RomM, Jellyfin with each person's own account, and your keys (SteamGridDB, TheGamesDB, IGDB, ScreenScraper, RetroAchievements) go to your other devices and come to this one. Sealed, never in plain text. A key removed here stays off this device only",
    ) { v -> app.scope.launch { app.store.sync.setShareSignIns(v) } }.copy(section = what))
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
    // Home was reset on this device: it can go back, and rejoin the profile's if the reset left it.
    if (app.store.prefs.value.canUndoHomeReset) {
        add(MenuAction(
            "home.undo", "Undo Home Reset", FuseIcons.Undo,
            detail = "Home on this device goes back as it was before the reset", section = what,
            onSelect = {
                app.store.undoHomeReset()
                app.toasts.show("Home is back as it was", icon = FuseIcons.Undo)
            },
        ))
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
        // A host keeps running the Fuse it started with; said here, with how it catches up.
        val hostFuse = svc.hostFuse.value
        val here = app.store.updates.currentVersion
        if (hostFuse.isNotEmpty() && here.isNotEmpty() && SyncApi.older(hostFuse, here)) {
            val hostName = (status as? SyncStatus.Online)?.hostName ?: "The host"
            add(infoRow(
                "hostolder", "$hostName runs Fuse $hostFuse",
                detail = "Older than this device's $here, so some of what's new waits for it. Open Fuse on that computer once and the host starts again on the new version; from 0.4.2.6 on it updates itself. A Fuse One Host in Docker updates with docker compose pull",
                icon = FuseIcons.CircleArrowDown,
            ).copy(section = connection))
        }
        add(MenuAction(
            "pair", "Add a Device", FuseIcons.Plus,
            detail = "A code for another device to type. Or ask to join from it, and say yes here",
            trailing = Trailing.Chevron, section = connection,
            onSelect = { app.pairing = true },
        ))
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
                    app.scope.launch { app.store.sync.configure { it.copy(remoteAddress = v.trim(), remoteFromHost = false) }; svc.setEnabled(true) }
                }
            },
        ))
    }
    add(MenuAction(
        "name", "This Device's Name", FuseIcons.MonitorSmartphone,
        detail = "How it shows on the host and in save history",
        // The name the host really shows (the computer's own, until one is given), never a stand-in.
        trailing = Trailing.Value(c.deviceName.ifBlank { svc.defaultName.ifBlank { "This device" } }), section = connection,
        onSelect = {
            app.textInput = TextInputSpec("This device's name", c.deviceName.ifBlank { svc.defaultName }, "Steam Deck") { v ->
                val name = v.trim().take(32)
                if (name.isEmpty()) return@TextInputSpec
                app.scope.launch {
                    app.store.sync.configure { it.copy(deviceName = name) }
                    svc.renameDevice(c.deviceId, name)
                }
            }
        },
    ))

    // Signed in as the host, from this device ---------------------------------------------------
    if (c.role != "HOST") {
        val hostName = c.hostName.ifBlank { "the host" }
        val hostSection = "The host"
        val me = devices.firstOrNull { it.id == c.deviceId }
        if (me?.manager == true) {
            add(infoRow(
                "managing", "Signed In as the Host",
                detail = "This device manages $hostName: its devices, its account and its address from outside. It stays signed in through updates and restarts, of the host and of this device",
                icon = FuseIcons.ShieldCheck,
            ).copy(section = hostSection))
            val linked = devices.filterNot { it.revoked }
            add(MenuAction(
                "hostdevices", "Devices", FuseIcons.MonitorSmartphone,
                detail = linked.joinToString(", ") { it.name }.ifBlank { "Only this one so far" },
                trailing = Trailing.Value(count(linked.size, "device")), section = hostSection,
                onSelect = { manageDevices(app, svc, linked, c.deviceId, svc.hostFuse.value) },
            ))
            add(MenuAction(
                "hostoutside", "Address From Outside, for Every Device", FuseIcons.Globe,
                detail = "Every device uses it away from home, and the Hub opens there after signing in",
                trailing = Trailing.Value(c.remoteAddress.removePrefix("https://").removePrefix("http://").ifBlank { "Not set" }), section = hostSection,
                onSelect = {
                    app.textInput = TextInputSpec("Address from outside", c.remoteAddress, "https://sync.example.com", capitalize = false) { v ->
                        app.scope.launch {
                            svc.setOutsideAddress(v).onSuccess { app.toasts.show(if (v.isBlank()) "No address from outside" else "Every device will use it away from home", ToastKind.SUCCESS) }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't set it", ToastKind.ERROR) }
                        }
                    }
                },
            ))
            add(MenuAction(
                "hostaccount", "Host Account", FuseIcons.UserRound,
                detail = "Change its username or password. This device stays signed in either way",
                trailing = Trailing.Chevron, section = hostSection,
                onSelect = { hostAccount(app, svc, current = "", canRemove = false) },
            ))
            add(MenuAction(
                "hostsignout", "Sign Out as the Host", FuseIcons.LogOut,
                detail = "This device keeps syncing; it just stops managing $hostName",
                section = hostSection,
                onSelect = {
                    app.confirm = ConfirmSpec(
                        "Sign out as the host here?",
                        "This device stays linked and keeps syncing. You can sign in as the host again any time with the host's username and password.",
                        "Sign Out",
                    ) {
                        app.scope.launch {
                            svc.signOutAsHost().onSuccess { app.toasts.show("Signed out as the host. Everything keeps syncing", icon = FuseIcons.LogOut) }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't sign out", ToastKind.ERROR) }
                        }
                    }
                },
            ))
        } else {
            add(MenuAction(
                "hostsignin", "Sign In as the Host", FuseIcons.LogIn,
                detail = "With the host's username and password, once. This device then manages $hostName, and stays signed in through updates and restarts",
                trailing = Trailing.Chevron, section = hostSection,
                onSelect = { signInAsHost(app, svc, hostName) },
            ))
        }
    }

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
            onSelect = { manageDevices(app, svc, linked, c.deviceId, host?.status?.hello?.fuseVersion.orEmpty()) },
        ))
        val outside = host?.outside.orEmpty()
        add(MenuAction(
            "outside", "Address From Outside", FuseIcons.Globe,
            detail = "Every device uses it away from home, and the Hub opens there after signing in. Found by itself when a device connects through a tunnel",
            trailing = Trailing.Value(outside.removePrefix("https://").removePrefix("http://").ifBlank { "Not set" }), section = hostSection,
            onSelect = {
                app.textInput = TextInputSpec("Address from outside", outside, "https://sync.example.com", capitalize = false) { v ->
                    app.scope.launch {
                        svc.setOutsideAddress(v).onSuccess { app.toasts.show(if (v.isBlank()) "No address from outside" else "Every device will use it away from home", ToastKind.SUCCESS) }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't set it", ToastKind.ERROR) }
                    }
                }
            },
        ))
        val account = host?.accountName
        add(MenuAction(
            "account", "Host Account", FuseIcons.UserRound,
            detail = "A username and password, for letting a device in and opening the Hub when you're away from this computer",
            trailing = Trailing.Value(account?.ifBlank { "Set" } ?: "Not set"), section = hostSection,
            onSelect = { hostAccount(app, svc, account) },
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
            "hub", "The Hub in a Browser", value = if (outside.isNotBlank()) "${outside.removePrefix("https://")}/hub" else "127.0.0.1:${c.hostPort}/hub",
            detail = "Profiles, devices and storage at a glance, with Fuse closed too: here at 127.0.0.1:${c.hostPort}/hub" +
                if (outside.isNotBlank()) ", and from away after signing in with the host account" else "",
            icon = FuseIcons.Globe,
        ).copy(section = hostSection))
        host?.addresses?.takeIf { it.isNotEmpty() }?.let { a ->
            add(infoRow("addresses", "Its Addresses", value = a.first(), detail = "Other devices on this network find it by themselves" + if (a.size > 1) ". Also ${a.drop(1).joinToString(", ")}" else "", icon = FuseIcons.Network).copy(section = hostSection))
        }
        add(MenuAction(
            "householdbackups", "Household Backups", FuseIcons.ArchiveRestore,
            detail = backupsDetail(backups),
            trailing = Trailing.Chevron, section = hostSection,
            onSelect = { householdBackupsSheet(app, svc) },
        ))
        add(MenuAction(
            "recovery", "Recovery Package", FuseIcons.Lock,
            detail = "The whole household in one file sealed with a password, to keep offline or to move the host to a new computer",
            trailing = Trailing.Chevron, section = hostSection,
            onSelect = { recoverySheet(app, svc) },
        ))
        add(MenuAction(
            "hostdata", "Where Saves Are Kept", FuseIcons.HardDrive,
            detail = c.hostDataDir.ifBlank { "Fuse's own folder" } + ". Choose another folder to move them there",
            trailing = Trailing.Chevron, section = hostSection,
            onSelect = { moveHostData(app, svc) },
        ))
    }

    // Backup hosts -------------------------------------------------------------------------------
    addAll(backupHostRows(app, svc, c, hosts))

    // Leaving --------------------------------------------------------------------------------------
    val leave = "Leave"
    if (c.role == "HOST") {
        add(MenuAction(
            "stop", "Stop Hosting", FuseIcons.CircleSlash, destructive = true,
            detail = "Other devices stop syncing until there is a host again. Everything stays on this computer",
            section = leave,
            onSelect = {
                app.confirm = ConfirmSpec(
                    "Stop hosting Fuse One?",
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
        add(MenuAction(
            "delete", "Delete This Host", FuseIcons.Trash, destructive = true,
            detail = "Every profile, save and device link kept here. This computer keeps its own games, settings and Home",
            section = leave,
            onSelect = {
                app.confirm = ConfirmSpec(
                    "Delete this host?",
                    "Every profile, every save and its history, and every device's link are deleted from this computer. This computer keeps its own games, library, settings and Home as they are now. Your other devices keep everything they have, and see the host gone.",
                    "Continue", destructive = true,
                ) {
                    app.confirm = ConfirmSpec(
                        "Delete it for good?",
                        "This can't be undone. Saves only the host had (from devices that are gone) go with it.",
                        "Delete This Host", destructive = true,
                    ) {
                        app.scope.launch {
                            svc.deleteHost().onSuccess { app.toasts.show("The host is deleted. Everything of this computer's own stays", icon = FuseIcons.Trash) }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't delete the host", ToastKind.ERROR) }
                        }
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
                    keepProfilesHere(app) { keep ->
                        app.scope.launch {
                            svc.unlink(keep).onSuccess { app.toasts.show("Unlinked. Everything here stays as it was") }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't unlink", ToastKind.ERROR) }
                        }
                    }
                }
            },
        ))
    }
}

/**
 * The host's account: made with a username and a password typed twice; once made, its password or
 * username can change, or it can go (then devices join only with someone at a screen, and the Hub
 * opens on this computer only).
 */
/**
 * The profiles, each ticked or not for one of this device's save choices; choosing one switches it
 * and the list stays open with the change, so several can be set in a row.
 */
private fun profileChoices(app: AppState, title: String, message: String, profiles: List<ProfileInfo>, chosen: Set<String>, set: (SyncSettings, List<String>) -> SyncSettings) {
    app.choice = ChoiceSpec(
        title, message,
        profiles.map { p ->
            MenuAction("p.${p.id}", p.name, FuseIcons.CircleUser, trailing = Trailing.Check(p.id in chosen), onSelect = {
                val next = if (p.id in chosen) chosen - p.id else chosen + p.id
                app.scope.launch { app.store.sync.configure { s -> set(s, profiles.map { it.id }.filter { it in next } + next.filter { id -> profiles.none { it.id == id } }) } }
                profileChoices(app, title, message, profiles, next, set)
            })
        },
        icon = FuseIcons.Users,
    )
}

/**
 * Signing this device in as the host: the host account's username, then its password. Said plainly
 * when it works, and when it doesn't, why.
 */
private fun signInAsHost(app: AppState, svc: SyncService, hostName: String) {
    app.textInput = TextInputSpec("The host's username", "", "The username set on $hostName", capitalize = false, doneLabel = "Next") { user ->
        if (user.isBlank()) return@TextInputSpec
        app.textInput = TextInputSpec("Password for ${user.trim()}", "", "", secret = true, capitalize = false, doneLabel = "Sign In") { pass ->
            if (pass.isEmpty()) return@TextInputSpec
            app.scope.launch {
                svc.signInAsHost(user, pass)
                    .onSuccess { app.toasts.show("Signed in as the host. This device stays signed in", ToastKind.SUCCESS, icon = FuseIcons.ShieldCheck) }
                    .onFailure { app.toasts.show(it.message ?: "Couldn't sign in as the host", ToastKind.ERROR) }
            }
        }
    }
}

private fun hostAccount(app: AppState, svc: SyncService, current: String?, canRemove: Boolean = true) {
    fun askPassword(user: String) {
        app.textInput = TextInputSpec("A password for $user", "", "At least 8 characters", secret = true, capitalize = false, doneLabel = "Next") { first ->
            if (first.length < 8) return@TextInputSpec app.toasts.show("Choose a password of at least 8 characters", ToastKind.WARNING)
            app.textInput = TextInputSpec("The same password again", "", "", secret = true, capitalize = false, doneLabel = "Save") { again ->
                if (again != first) return@TextInputSpec app.toasts.show("The two passwords weren't the same. Nothing changed", ToastKind.WARNING)
                app.scope.launch {
                    svc.setHostAccount(user, first).onSuccess { app.toasts.show("The host account is ready", ToastKind.SUCCESS, icon = FuseIcons.UserRound) }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't save the account", ToastKind.ERROR) }
                }
            }
        }
    }
    fun askName(then: (String) -> Unit) {
        app.textInput = TextInputSpec("Username", current.orEmpty(), "Up to 40 characters", capitalize = false, doneLabel = "Next") { v ->
            v.trim().take(40).takeIf { it.isNotEmpty() }?.let(then)
        }
    }
    if (current == null) return askName { askPassword(it) }
    app.choice = ChoiceSpec(
        title = "Host Account",
        icon = FuseIcons.UserRound,
        message = if (current.isNotBlank()) "Signed in as $current from away" else null,
        options = listOfNotNull(
            MenuAction("pw", "Change the Password", FuseIcons.Lock, onSelect = {
                app.choice = null
                // The background service's host doesn't say its username: ask for it with the new password.
                if (current.isBlank()) askName { askPassword(it) } else askPassword(current)
            }),
            MenuAction("name", "Change the Username", FuseIcons.Pencil, onSelect = {
                app.choice = null
                askName { name -> app.scope.launch { svc.setHostAccount(name, null).onFailure { app.toasts.show(it.message ?: "Couldn't change it", ToastKind.ERROR) } } }
            }),
            MenuAction("remove", "Remove the Account", FuseIcons.Trash, destructive = true, onSelect = {
                app.choice = null
                app.confirm = ConfirmSpec(
                    "Remove the host account?",
                    "Devices then join only with someone at a screen to let them in, and the Hub opens on this computer only. Devices already in stay in.",
                    "Remove", destructive = true,
                ) { app.scope.launch { svc.clearHostAccount() } }
            }).takeIf { canRemove },
        ),
    )
}

/** Moves everyone's saves to a folder picked here, after saying what happens. */
private fun moveHostData(app: AppState, svc: SyncService) {
    app.scope.launch {
        val to = app.platform.storage.pickFolder("Move everyone's saves to") ?: return@launch
        app.confirm = ConfirmSpec(
            "Move the saves here?",
            "$to\n\nThe host stops for a moment while every save, version and profile is copied and checked. Then the old folder is cleared. The folder must be empty.",
            "Move",
        ) {
            app.scope.launch {
                app.toasts.show("Moving the saves", icon = FuseIcons.HardDrive)
                svc.moveHostData(to).onSuccess { app.toasts.show("The saves are in their new folder", ToastKind.SUCCESS) }
                    .onFailure { app.toasts.show(it.message ?: "Couldn't move the saves. Nothing changed", ToastKind.ERROR) }
            }
        }
    }
}

/** Which Fuse [d] runs, and whether it is older than the host's; null before it has called. */
private fun deviceVersion(d: DeviceInfo, hostVersion: String): String? = when {
    d.fuseVersion.isNotEmpty() && hostVersion.isNotEmpty() && SyncApi.older(d.fuseVersion, hostVersion) -> "Fuse ${d.fuseVersion}, older than the host's. Update it to use everything new"
    d.fuseVersion.isNotEmpty() -> "Fuse ${d.fuseVersion}"
    d.lastSeen > 0 -> "Fuse 0.4.1 or older. Update it to use everything new"
    else -> null
}

/**
 * The host's devices: when each was last seen and which Fuse it runs (one older than the host's
 * is said to be, since it can't use what is newer), and renaming or unlinking one.
 */
private fun manageDevices(app: AppState, svc: SyncService, devices: List<DeviceInfo>, self: String, hostVersion: String) {
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
                detail = listOfNotNull(
                    if (d.lastSeen > 0) "Seen ${TimeWords.relative(d.lastSeen, now, offset)}" else "Not seen yet",
                    deviceVersion(d, hostVersion),
                ).joinToString("  ·  "),
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

/**
 * Backup hosts: on the host, the computers that keep a copy and those offering to; on a joined
 * computer, keeping a copy itself, how the host and the copy stand, and taking over when the host is
 * gone for good.
 */
private fun backupHostRows(app: AppState, svc: SyncService, c: SyncSettings, hosts: io.github.matiyaaa.fuse.sync.HostsView?): List<MenuAction> = buildList {
    val section = "Backup Hosts"
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    val leader = hosts?.hosts?.firstOrNull { it.nodeId == hosts.leader }
    when {
        c.role == "HOST" && hosts?.thisRole == io.github.matiyaaa.fuse.sync.Leadership.FENCED -> add(infoRow(
            "fenced", "Another Computer Keeps the Household",
            detail = "A backup became the host while this computer was away. This one takes no changes until the new host has taken back what it kept meanwhile",
            icon = FuseIcons.ShieldAlert,
        ).copy(section = section))
        c.role == "HOST" && hosts?.thisRole == io.github.matiyaaa.fuse.sync.Leadership.BACKUP -> add(infoRow(
            "isbackup", "This Computer Is a Backup Now",
            detail = "${leader?.name ?: "Another computer"} keeps the household. This one keeps a copy and can take over again",
            icon = FuseIcons.DatabaseBackup,
        ).copy(section = section))
        c.role == "HOST" -> {
            val backups = hosts?.hosts.orEmpty().filter { it.nodeId != hosts?.leader }
            val offers = hosts?.offers.orEmpty()
            add(MenuAction(
                "backups", "Backup Hosts", FuseIcons.DatabaseBackup,
                detail = if (offers.isNotEmpty()) "${offers.size} waiting to be accepted" else "Computers that keep a copy of the household and take over if this one is gone",
                trailing = if (offers.isNotEmpty()) Trailing.Badge("${offers.size}") else Trailing.Value(if (backups.isEmpty()) "None" else "${backups.size}"),
                section = section,
                onSelect = { backupHostsSheet(app, svc, hosts) },
            ))
        }
        svc.canHost -> {
            val on = c.backupHost
            val role = hosts?.thisRole
            val detail = when {
                !on -> "Keep a copy of the household on this computer, so it can take over if the host is gone"
                hosts?.problem != null -> hosts.problem
                hosts?.waiting == true -> "Waiting for the host to accept it, in its Fuse One settings"
                hosts?.hostAwaySince != null && hosts.hostAwaySince > 0 -> "The host hasn't answered since ${TimeWords.relative(hosts.hostAwaySince, now, offset)}. The copy here is as of then"
                (hosts?.copiedAt ?: 0) > 0 -> "A copy of the household here, brought up to date ${TimeWords.relative(hosts!!.copiedAt, now, offset)}"
                role == io.github.matiyaaa.fuse.sync.Leadership.BACKUP -> "A copy of the household here, kept up to date"
                else -> "Getting a copy of the household"
            }
            add(toggleRow("backup", "Be a Backup Host", FuseIcons.DatabaseBackup, on, detail) { v ->
                if (v) {
                    app.confirm = ConfirmSpec(
                        "Keep a copy of the household here?",
                        "This computer keeps a copy of everything the host keeps: every profile, save and device link, sealed on the way. The host accepts it first. If the host is ever gone for good, this computer can become the host with nothing lost. It needs to be on now and then to stay up to date.",
                        "Offer This Computer",
                    ) {
                        app.scope.launch {
                            svc.setBackupHost(true).onSuccess { app.toasts.show("Offered. Accept it on the host", icon = FuseIcons.DatabaseBackup) }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't offer this computer", ToastKind.ERROR) }
                        }
                    }
                } else {
                    app.confirm = ConfirmSpec(
                        "Stop being a backup host?",
                        "The copy of the household on this computer is deleted, and the host stops counting on it. Nothing else here changes.",
                        "Stop", destructive = true,
                    ) {
                        app.scope.launch {
                            svc.setBackupHost(false).onSuccess { app.toasts.show("This computer keeps no copy now") }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't change that", ToastKind.ERROR) }
                        }
                    }
                }
            }.copy(section = section))
            if (on && hosts?.canTakeOver == true) {
                add(MenuAction(
                    "takeover", "Make This Computer the Host", FuseIcons.Crown,
                    detail = "The host has been away a while. Only if it is gone for good: when it comes back, it hands over what it kept meanwhile",
                    section = section,
                    onSelect = {
                        app.confirm = ConfirmSpec(
                            "Make this computer the host?",
                            "Devices move to this computer and carry on from its copy. If the old host comes back, it takes no more changes, hands this computer every save it kept while away (kept as versions, never put over newer ones) and becomes a backup. Do this only if the host is gone for good, or for a long while.",
                            "Make This the Host", destructive = true,
                        ) {
                            app.scope.launch {
                                svc.takeOverAsHost().onSuccess { app.toasts.show("This computer keeps the household now", ToastKind.SUCCESS) }
                                    .onFailure { app.toasts.show(it.message ?: "Couldn't take over", ToastKind.ERROR) }
                            }
                        }
                    },
                ))
            }
        }
    }
}

/** The host's backups, with those offering to be one, each to accept or take out. */
private fun backupHostsSheet(app: AppState, svc: SyncService, hosts: io.github.matiyaaa.fuse.sync.HostsView?) {
    val backups = hosts?.hosts.orEmpty().filter { it.nodeId != hosts?.leader }
    val offers = hosts?.offers.orEmpty()
    val rows = offers.map { o ->
        MenuAction(
            "offer.${o.deviceId}", o.name, FuseIcons.UserPlus,
            detail = "Offers to keep a copy" + (o.local?.let { " at $it" } ?: ""),
            trailing = Trailing.Value("Accept"), section = "Waiting",
            onSelect = {
                app.choice = null
                app.confirm = ConfirmSpec(
                    "Make ${o.name} a backup host?",
                    "It gets a copy of everything this host keeps, every profile, save and device link and the household's sign-ins, sealed with its own key, and keeps it up to date. If this computer is ever gone, it can take over. Only accept a computer of your own household.",
                    "Accept",
                ) {
                    app.scope.launch {
                        svc.acceptBackup(o.deviceId.orEmpty()).onSuccess { app.toasts.show("${o.name} is a backup host now", ToastKind.SUCCESS) }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't accept it", ToastKind.ERROR) }
                    }
                }
            },
        )
    } + backups.map { b ->
        MenuAction(
            "backup.${b.nodeId}", b.name, FuseIcons.DatabaseBackup,
            detail = "Keeps a copy" + (b.local?.let { " at $it" } ?: ""),
            trailing = Trailing.Chevron, section = "Backups",
            onSelect = {
                app.choice = null
                app.confirm = ConfirmSpec(
                    "Take ${b.name} out?",
                    "It stops being a backup host and gets no more copies. Turn Be a Backup Host off on it to delete the copy it has.",
                    "Take Out", destructive = true,
                ) {
                    app.scope.launch {
                        svc.removeBackup(b.nodeId).onSuccess { app.toasts.show("${b.name} is no longer a backup host") }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't take it out", ToastKind.ERROR) }
                    }
                }
            },
        )
    }
    app.choice = ChoiceSpec(
        title = "Backup Hosts",
        icon = FuseIcons.DatabaseBackup,
        message = if (rows.isEmpty()) {
            "None yet. On another computer of the household, turn on Be a Backup Host in its Fuse One settings, then accept it here. With two backups, they choose a new host by themselves if this one is gone; with one, a person decides."
        } else {
            "Backups keep a copy of the household. With two or more, a majority of the hosts chooses a new one if this computer is gone; with one, a person on it decides."
        },
        options = rows,
    )
}

/** What the household's backups say at a glance: the newest, or that none was made yet. */
private fun backupsDetail(view: io.github.matiyaaa.fuse.sync.HouseholdBackupsState?): String {
    if (view == null) return "Copies of the whole household, every day, week and month"
    view.problem?.let { return it }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val newest = view.snapshots.firstOrNull() ?: return "None yet. The first is made within the hour"
    return "Newest ${TimeWords.relative(newest.at, now, localOffsetMillis(now))}, ${view.snapshots.size} kept"
}

/**
 * The household's backups: made by themselves every day (kept a week), week (kept a month) and month
 * (kept half a year), one made now, where they go, and putting the household back as one of them.
 */
private fun householdBackupsSheet(app: AppState, svc: SyncService) {
    app.scope.launch {
        svc.refreshHouseholdBackups()
        val view = svc.householdBackups.value
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val offset = localOffsetMillis(now)
        fun kinds(k: List<String>) = k.joinToString(", ") { when (it) {
            "daily" -> "daily"; "weekly" -> "weekly"; "monthly" -> "monthly"; "manual" -> "made by you"; "before-restore" -> "before a restore"; else -> it
        } }.replaceFirstChar { it.uppercase() }
        val snaps = view?.snapshots.orEmpty().map { s ->
            MenuAction(
                "snap.${s.name}", TimeWords.relative(s.at, now, offset).replaceFirstChar { it.uppercase() }, FuseIcons.ArchiveRestore,
                detail = "${kinds(s.kinds)}  ·  ${s.profiles} profiles, ${s.revisions} save versions  ·  ${sizeText(s.bytes)}",
                trailing = Trailing.Chevron, section = "Kept",
                onSelect = {
                    app.choice = null
                    app.confirm = ConfirmSpec(
                        "Put the household back as it was ${TimeWords.relative(s.at, now, offset)}?",
                        "Profiles, every save and its versions, records and device links go back to this backup. The household as it is now is kept as a backup first, so this can be undone. Devices that joined after it have to connect again, and saves made since stay on the devices that made them and come back with their next sync.",
                        "Put It Back", destructive = true,
                    ) {
                        app.scope.launch {
                            svc.restoreHousehold(s.name).onSuccess { app.toasts.show("The household is back as it was", ToastKind.SUCCESS) }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't put it back", ToastKind.ERROR) }
                        }
                    }
                },
            )
        }
        val actions = listOf(
            MenuAction("now", "Back Up Now", FuseIcons.DatabaseBackup, detail = "A copy of the whole household, kept beside the scheduled ones", section = "", onSelect = {
                app.choice = null
                app.scope.launch {
                    svc.backUpHouseholdNow().onSuccess { app.toasts.show("The household is backed up", ToastKind.SUCCESS) }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't back it up", ToastKind.ERROR) }
                }
            }),
            MenuAction("where", "Where Backups Go", FuseIcons.FolderOpen, detail = view?.folder ?: "Fuse's own folder", trailing = Trailing.Chevron, section = "", onSelect = {
                app.choice = null
                app.scope.launch {
                    val folder = app.platform.storage.pickFolder("Where should the household's backups go?") ?: return@launch
                    svc.setHouseholdBackupFolder(folder).onSuccess { app.toasts.show("Backups go to $folder now") }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't use that folder", ToastKind.ERROR) }
                }
            }),
        )
        app.choice = ChoiceSpec(
            title = "Household Backups",
            icon = FuseIcons.ArchiveRestore,
            message = "The whole household, kept by itself every day for a week, every week for a month and every month for half a year. Each save file is kept once, however many backups use it. Best on another drive than the host's.",
            options = actions + snaps,
        )
    }
}

/**
 * The household in one file sealed with a password: made here to keep offline (another drive, a
 * USB stick), or restored on a new host computer, with the two ways to move the host said plainly.
 */
private fun recoverySheet(app: AppState, svc: SyncService) {
    app.scope.launch {
        val last = svc.recoveryJob()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val offset = localOffsetMillis(now)
        val lastRow = last?.takeIf { !it.running }?.let { j ->
            infoRow(
                "last", if (j.kind == RecoveryJob.WRITE) "Last Package" else "Last Restore",
                value = if (j.state == RecoveryJob.DONE) TimeWords.relative(j.startedAt, now, offset).replaceFirstChar { it.uppercase() } else "Didn't finish",
                detail = if (j.state == RecoveryJob.DONE) j.file else j.message ?: "",
                icon = if (j.state == RecoveryJob.DONE) FuseIcons.PackageCheck else FuseIcons.Alert,
            )
        }
        app.choice = ChoiceSpec(
            title = "Recovery Package",
            icon = FuseIcons.Lock,
            message = "Everything the host keeps (profiles, every save and its history, records, device links) in one file sealed with a password. " +
                "To move the host to a new computer, either make the new one a backup host and then Make This Computer the Host there, or make a package here and restore it on the new host.",
            options = listOfNotNull(
                MenuAction("make", "Make a Recovery Package", FuseIcons.HardDriveDownload, detail = "Best kept on another drive or a USB stick, with its password kept apart", trailing = Trailing.Chevron, onSelect = { makePackage(app, svc) }),
                MenuAction("restore", "Restore From a Package", FuseIcons.ArchiveRestore, detail = "This computer becomes the household's host, as the package kept it", trailing = Trailing.Chevron, onSelect = { restorePackage(app, svc) }),
                lastRow,
            ),
        )
    }
}

private fun makePackage(app: AppState, svc: SyncService) {
    app.choice = null
    app.scope.launch {
        val folder = app.platform.storage.pickFolder("Where should the recovery package go?") ?: return@launch
        askPassword(app, "A password for the package", "At least ${RecoveryJob.MIN_PASSWORD} characters. Without it the package can't be opened, so keep it somewhere safe, apart from the package.") { password ->
            askPassword(app, "The password again", "To be sure it is the one you meant.") { again ->
                if (again != password) {
                    app.toasts.show("The passwords weren't the same, so nothing was made", ToastKind.WARNING)
                    return@askPassword
                }
                val stamp = kotlin.time.Clock.System.now().toString().take(10)
                val file = folder.trimEnd('/', '\\') + "/Fuse One household $stamp.fuserecovery"
                runRecovery(app, svc, RecoveryJob.WRITE, file, password)
            }
        }
    }
}

private fun restorePackage(app: AppState, svc: SyncService) {
    app.choice = null
    app.scope.launch {
        val file = app.platform.storage.pickFile("Choose the recovery package", "Fuse recovery packages", listOf("fuserecovery")) ?: return@launch
        val (name, made) = svc.peekRecoveryPackage(file) ?: run {
            app.toasts.show("That isn't a Fuse recovery package", ToastKind.WARNING)
            return@launch
        }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        app.confirm = ConfirmSpec(
            "Restore $name's household from ${TimeWords.relative(made, now, localOffsetMillis(now))}?",
            "This computer becomes the household's host, as the package kept it: profiles, every save and its history, records and device links. " +
                "The household here now is kept as a household backup first. Every file is checked before anything changes, and a wrong password or a damaged package changes nothing. " +
                "Devices at home find this host by themselves; one that was away needs this host's address from outside.",
            "Continue",
        ) {
            askPassword(app, "The package's password", null) { password -> runRecovery(app, svc, RecoveryJob.RESTORE, file, password) }
        }
    }
}

private fun askPassword(app: AppState, title: String, message: String?, then: (String) -> Unit) {
    app.textInput = TextInputSpec(title, "", "Password", secret = true, capitalize = false, doneLabel = "Continue", message = message) { v ->
        app.textInput = null
        if (v.isNotEmpty()) then(v)
    }
}

/** Starts making or restoring a package and follows it to the end, saying how far it is. */
private fun runRecovery(app: AppState, svc: SyncService, kind: String, file: String, password: String) {
    app.scope.launch {
        val started = svc.startRecovery(kind, file, password).getOrElse {
            app.toasts.show(it.message ?: "Couldn't start", ToastKind.ERROR)
            return@launch
        }
        app.toasts.show(if (kind == RecoveryJob.WRITE) "Making the recovery package" else "Checking the package and restoring the household")
        var job: RecoveryJob = started
        var shown = 0L
        while (job.running) {
            kotlinx.coroutines.delay(1_000)
            job = svc.recoveryJob() ?: break
            // A word now and then for a big household, not a toast a second.
            if (job.total > 0 && job.done - shown >= maxOf(50L, job.total / 4)) {
                shown = job.done
                app.toasts.show("${job.done} of ${job.total} files")
            }
        }
        when {
            job.state == RecoveryJob.DONE && kind == RecoveryJob.WRITE ->
                app.toasts.show("The household is in the package: ${job.profiles} profiles, ${job.revisions} save versions", ToastKind.SUCCESS)
            job.state == RecoveryJob.DONE -> app.toasts.show("This computer is the household's host now, as the package kept it", ToastKind.SUCCESS)
            else -> app.toasts.show(job.message ?: "It didn't finish", ToastKind.ERROR)
        }
    }
}