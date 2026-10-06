package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.sync.Route as SyncRoute
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlinx.coroutines.launch

/** Fuse Sync's name wherever it is presented as one of Fuse's own. */
internal const val SYNC_NAME = "Fuse Sync"
internal const val SYNC_BYLINE = "Fuse Sync by Fuse"

/** Where Fuse Sync stands, in a few words and a sentence, with whether it is well (null: neither). */
internal data class SyncWords(val ok: Boolean?, val title: String, val detail: String)

internal fun syncWords(status: SyncStatus): SyncWords = when (status) {
    SyncStatus.Off -> SyncWords(null, "Off", "Turn it on to keep your saves, play time and settings on every device")
    SyncStatus.NotSetUp -> SyncWords(null, "Not set up", "Make this the host, or connect to the one you have")
    is SyncStatus.Connecting -> SyncWords(null, "Connecting", "Reaching ${status.hostName}")
    is SyncStatus.Online -> SyncWords(
        true,
        if (status.working) "Syncing" else "Up to date",
        (if (status.route == SyncRoute.LOCAL) "With ${status.hostName} at home" else "With ${status.hostName} from outside") +
            if (status.pending > 0) ", ${status.pending} waiting to go" else "",
    )
    is SyncStatus.Offline -> SyncWords(
        false, "Offline",
        if (status.pending > 0) "${status.hostName} isn't answering. ${status.pending} ${if (status.pending == 1) "change is" else "changes are"} kept here and ${if (status.pending == 1) "goes" else "go"} up when it's back" else "${status.hostName} isn't answering. Everything works here and catches up later",
    )
    is SyncStatus.NeedsAttention -> SyncWords(false, "Needs you", status.reason)
}

/** True while Fuse Sync is on and set up, so its parts (profiles, the Sync tab) belong on screen. */
internal val AppState.syncInUse: Boolean
    get() = store.sync.service != null && store.prefs.value.sync.enabled

/**
 * Fuse Sync's mark: its two arrows in a rounded well lit by the accent, the way each addon's page
 * opens with its own.
 */
@Composable
internal fun SyncMark(size: Dp, modifier: Modifier = Modifier, tint: Color = Fuse.colors.accent) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.26f), tint.copy(alpha = 0.10f)))),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(FuseIcons.RefreshCcw, size = size * 0.48f, tint = tint)
    }
}

/** "3 devices", "1 device". */
internal fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/**
 * Turning off a sync that is connected and working asks twice: once to say what stops, and once
 * more, plainly, before anything changes. Off already, or not working, it simply turns off.
 */
internal fun confirmTurnOff(app: AppState, name: String, working: Boolean, first: String, second: String, off: () -> Unit) {
    if (!working) {
        off()
        return
    }
    app.confirm = io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec("Turn off $name?", first, "Turn Off") {
        app.confirm = io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec("Are you sure?", second, "Turn Off $name", destructive = true) { off() }
    }
}

/**
 * Leaving a host: whether the people who played here stay as this device's own profiles (with their
 * saves and play time) or go too. Asked only when there is someone to keep; [then] gets the answer.
 */
internal fun keepProfilesHere(app: AppState, then: (Boolean) -> Unit) {
    val people = app.store.sync.service?.profiles?.value.orEmpty().filterNot { it.hostOnly }
    if (people.isEmpty()) return then(true)
    app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
        title = "Profiles on This Device",
        icon = FuseIcons.Users,
        message = "Whoever played here can stay, without a host. Join one again later and they come along.",
        options = listOf(
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                "keep", "Keep Profiles Here", FuseIcons.UserRound,
                detail = "Each keeps their saves, play time, favourites and theme on this device",
                onSelect = { app.choice = null; then(true) },
            ),
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                "forget", "Forget Them Too", FuseIcons.Trash, destructive = true,
                detail = "No profiles here. The library and the saves in the emulators' folders stay as they are now",
                onSelect = { app.choice = null; then(false) },
            ),
        ),
    )
}

/**
 * Fuse Sync off: this device forgets its host, and keeps its games, saves, library, settings and
 * Home as they are now; the people who played here stay as its own profiles unless they should go
 * too. Asks twice while it is in touch with its host, once while it is set up but not, and not at
 * all when there is nothing to forget.
 */
internal fun turnOffFuseSync(app: AppState, then: suspend () -> Unit = {}) {
    val status = app.store.sync.service?.status?.value
    val working = status is SyncStatus.Online
    val host = app.store.prefs.value.sync.role == "HOST"
    val first = "This device forgets its host. Its games, saves, library, settings and Home stay exactly as they are now." +
        if (host) " This computer stops hosting; everyone's saves stay on it until you delete them." else ""
    val second = "Turning it on again starts fresh: find the host, join, and bring your profiles along. " +
        if (host) "Your other devices stop syncing until there is a host again." else "Saves made here won't reach your other devices until then."
    fun off() {
        fun go(keep: Boolean) {
            app.scope.launch {
                app.store.sync.setEnabled(false, keep)
                then()
            }
        }
        // Without a host, the profiles here are this device's own already: turning Fuse Sync off leaves them be.
        if (app.store.prefs.value.sync.role.isEmpty()) go(true) else keepProfilesHere(app, ::go)
    }
    when {
        working -> confirmTurnOff(app, SYNC_NAME, working = true, first = first, second = second) { off() }
        status != null && status !is SyncStatus.Off && status !is SyncStatus.NotSetUp ->
            app.confirm = io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec("Turn off $SYNC_NAME?", "$first $second", "Turn Off", destructive = true) { off() }
        else -> off()
    }
}

/** Syncthing off, asking twice while it is connected. */
internal fun turnOffSyncthing(app: AppState, then: suspend () -> Unit = {}) {
    val svc = app.store.syncthing ?: return
    val working = svc.state.value is io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.Connected
    confirmTurnOff(
        app, "Syncthing", working,
        first = "It's connected and sharing your save folders. Turned off, Fuse stops bringing in the newest save before a game; Syncthing itself keeps running.",
        second = "Saves could fall out of step between your devices while Fuse isn't watching. Turn it off anyway?",
    ) {
        app.scope.launch {
            svc.setEnabled(false)
            then()
        }
    }
}

