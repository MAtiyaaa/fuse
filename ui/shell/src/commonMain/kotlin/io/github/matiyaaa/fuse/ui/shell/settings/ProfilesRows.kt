package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ReorderEntry
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ReorderSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.sync.ProfileEditSpec
import io.github.matiyaaa.fuse.ui.shell.sync.WhoMode
import kotlinx.coroutines.launch

/**
 * Settings, Profiles: everyone who plays on this device, with or without Fuse Sync. Each profile
 * keeps its own saves, play time, favourites, Home and theme; with a host they follow the person to
 * every device. Add someone, edit a profile (name, picture, PIN), put them in order, delete one,
 * and choose who Fuse starts as.
 */
@Composable
internal fun profilesRows(app: AppState): List<MenuAction> {
    val svc = app.store.sync.service ?: return listOf(infoRow("none", "Profiles aren't part of this build", icon = FuseIcons.Users))
    val profiles by svc.profiles.collectAsState()
    val active by svc.activeProfile.collectAsState()
    val status by svc.status.collectAsState()
    val prefs by app.store.prefs.collectAsState()
    val c = prefs.sync
    val hosted = c.enabled && status !is SyncStatus.Off && status !is SyncStatus.NotSetUp
    return buildList {
        if (profiles.isEmpty()) {
            add(infoRow(
                "about", "Everyone gets their own",
                detail = "A profile keeps a person's saves, play time, favourites, Home and theme apart from everyone else's on this device. No host needed. With Fuse Sync they follow each person to every device",
                icon = FuseIcons.Users,
            ))
        }
        val people = if (hosted) "On ${c.hostName.ifBlank { "your host" }}" else "On this device"
        for (p in profiles) {
            add(MenuAction(
                "p.${p.id}", p.name, FuseAvatars.of(p.avatar).glyph,
                detail = listOfNotNull(
                    "Playing here".takeIf { p.id == active?.id },
                    "This computer only".takeIf { p.hostOnly },
                    "PIN".takeIf { p.protected },
                    io.github.matiyaaa.fuse.ui.shell.sync.sizeText(p.storageBytes).takeIf { p.storageBytes > 0 }?.let { "$it of saves" },
                ).joinToString("  ·  ").ifBlank { null },
                trailing = Trailing.Chevron, section = people,
                onSelect = { profileMenu(app, svc, p, active, profiles, hosted) },
            ))
        }
        add(MenuAction(
            "add", "Add Profile", FuseIcons.UserPlus,
            detail = "Someone else who plays here: their own saves, play time, Home and theme",
            trailing = Trailing.Chevron, section = people,
            onSelect = { app.whoAreYou = WhoMode.ADD },
        ))
        if (profiles.isEmpty()) return@buildList
        val here = "This device"
        add(MenuAction(
            "who", "Playing As", FuseIcons.UserRound,
            detail = if (active == null) "Choose who is playing here" else "Switching brings in their library, saves, Home and theme, without a restart",
            trailing = Trailing.Value(active?.name ?: "No one yet"), section = here,
            onSelect = { app.whoAreYou = WhoMode.SWITCH },
        ))
        if (profiles.size > 1) {
            add(MenuAction(
                "order", "Profile Order", FuseIcons.MoveVertical,
                detail = "The order Who's playing? shows them in" + if (hosted) ", on every device" else "",
                trailing = Trailing.Chevron, section = here,
                onSelect = { reorderProfiles(app, svc, profiles) },
            ))
        }
        add(app.choiceRow(
            "startup", "At Startup", FuseIcons.Power,
            if (c.startup == "PROFILE") "PROFILE:${c.startupProfile}" else c.startup,
            listOf("LAST" to "The last one used", "ASK" to "Ask who's playing") + profiles.map { "PROFILE:${it.id}" to "Always ${it.name}" },
            detail = "Who Fuse starts as on this device",
        ) { v ->
            app.scope.launch {
                app.store.sync.configure { s -> if (v.startsWith("PROFILE:")) s.copy(startup = "PROFILE", startupProfile = v.removePrefix("PROFILE:")) else s.copy(startup = v, startupProfile = "") }
            }
        }.copy(section = here))
    }
}

/** The profiles in a list to drag, or pick up with A and move with the D-pad. */
private fun reorderProfiles(app: AppState, svc: SyncService, profiles: List<ProfileInfo>) {
    app.reorder = ReorderSpec(
        title = "Profile Order",
        icon = FuseIcons.MoveVertical,
        message = "The order Who's playing? shows them in. Drag a row by its grip, or press A to pick it up and move it with the D-pad",
        entries = profiles.map { p -> ReorderEntry(p.id, p.name, FuseAvatars.of(p.avatar).glyph) },
        onMoved = { ids -> app.scope.launch { svc.reorderProfiles(ids).onFailure { app.toasts.show(it.message ?: "Couldn't change the order", ToastKind.ERROR) } } },
    )
}

/**
 * One profile: edit it, move it, or delete it. A profile with a PIN asks for it first, so someone
 * else picking up the device can't change or remove it.
 */
private fun profileMenu(app: AppState, svc: SyncService, p: ProfileInfo, active: ProfileInfo?, profiles: List<ProfileInfo>, hosted: Boolean) {
    fun withPin(then: (String?) -> Unit) {
        if (!p.protected) return then(null)
        app.textInput = TextInputSpec("${p.name}'s PIN", "", "Their PIN", secret = true, capitalize = false, doneLabel = "Next") { v ->
            val pin = v.filter { it.isDigit() }
            app.scope.launch {
                svc.openProfile(p.id, pin).onSuccess { then(pin) }.onFailure { app.toasts.show(it.message ?: "That PIN isn't right", ToastKind.ERROR) }
            }
        }
    }
    val at = profiles.indexOfFirst { it.id == p.id }
    fun move(to: Int) {
        app.choice = null
        val ids = profiles.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(at))
        app.scope.launch { svc.reorderProfiles(ids).onFailure { app.toasts.show(it.message ?: "Couldn't move it", ToastKind.ERROR) } }
    }
    app.choice = ChoiceSpec(
        title = p.name,
        icon = FuseAvatars.of(p.avatar).glyph,
        message = if (p.id == active?.id) "Playing here now" else null,
        options = listOfNotNull(
            MenuAction("edit", "Edit Profile", FuseIcons.SquarePen, detail = "Name, picture and PIN", trailing = Trailing.Chevron, onSelect = {
                app.choice = null
                withPin { pin -> app.profileEdit = ProfileEditSpec(p, pin) }
            }),
            MenuAction("switch", "Play as ${p.name}", FuseIcons.UserRound, onSelect = {
                app.choice = null
                // A PIN is typed on Who's playing?'s own pad; without one, they arrive at once.
                if (p.protected) app.whoAreYou = WhoMode.SWITCH
                else app.scope.launch {
                    svc.switchTo(p.id).onSuccess { app.profileArrival = p }.onFailure { app.toasts.show(it.message ?: "Couldn't switch profiles", ToastKind.ERROR) }
                }
            }).takeIf { p.id != active?.id },
            MenuAction("up", "Move Up", FuseIcons.ArrowUp, onSelect = { move(at - 1) }).takeIf { at > 0 },
            MenuAction("down", "Move Down", FuseIcons.ArrowDown, onSelect = { move(at + 1) }).takeIf { at in 0 until profiles.size - 1 },
            MenuAction("delete", "Delete Profile", FuseIcons.Trash, destructive = true, onSelect = {
                app.choice = null
                withPin { _ ->
                    app.confirm = ConfirmSpec(
                        "Delete ${p.name}?",
                        (if (hosted) "Their saves, versions and records leave the host. What each device has right now stays on it."
                        else "Their play time and settings leave this device. Their saves are kept as plain files in Fuse Sync's kept folder.") +
                            if (p.id == active?.id) " No one is playing here until you choose someone." else "",
                        "Delete", destructive = true,
                    ) {
                        app.confirm = ConfirmSpec("Delete ${p.name} for good?", "This can't be undone.", "Delete ${p.name}", destructive = true) {
                            app.scope.launch {
                                svc.deleteProfile(p.id).onSuccess { app.toasts.show("${p.name} was deleted", icon = FuseIcons.Trash) }
                                    .onFailure { app.toasts.show(it.message ?: "Couldn't delete the profile", ToastKind.ERROR) }
                            }
                        }
                    }
                }
            }).takeIf { !p.hostOnly },
        ),
    )
}
