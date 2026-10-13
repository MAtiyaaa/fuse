package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.matiyaaa.fuse.sync.SaveProbeResult
import io.github.matiyaaa.fuse.sync.SyncNotice
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState

/**
 * Fuse Sync's quiet news as toasts: a save that reached the host (after a game, or while it ran), a
 * save from another device that can't be used here, and a save that couldn't be kept to send, each
 * with why and what to do.
 */
@Composable
internal fun SyncNotices(app: AppState) {
    val svc = app.store.sync.service ?: return
    LaunchedEffect(svc) {
        svc.notices.collect { n ->
            if (!app.store.sync.inUse) return@collect
            when (n) {
                is SyncNotice.Sent -> app.toasts.show(
                    "${n.title}: ${n.kind.label.lowercase()} sent to your host",
                    ToastKind.SUCCESS,
                    icon = FuseIcons.CloudUpload,
                )
                is SyncNotice.NotSynced -> app.toasts.show(
                    "${n.title}: ${n.kind.label.lowercase()} not sent. ${n.why}",
                    ToastKind.WARNING,
                    icon = FuseIcons.CloudOff,
                    durationMs = 9_000L,
                )
                is SyncNotice.Brought -> app.toasts.show(
                    if (n.count == 1) "Your profile is on ${n.hostName} now" else "Your ${n.count} profiles are on ${n.hostName} now",
                    ToastKind.SUCCESS,
                    icon = FuseIcons.Users,
                )
                is SyncNotice.KeptAside -> app.toasts.show(
                    "${n.title}: a save already here belonged to no one, so it was kept aside. Say whose it is in Sync.",
                    ToastKind.INFO,
                    icon = FuseIcons.Archive,
                    durationMs = 8_000L,
                )
                is SyncNotice.CantUse -> app.toasts.show(
                    "${n.title}: the newest ${n.kind.label.lowercase()} can't be used here. ${n.why}",
                    ToastKind.WARNING,
                    icon = FuseIcons.CloudOff,
                    durationMs = 8_000L,
                )
            }
        }
    }
    // What a Find Where It Saves test run showed, as soon as its game closes.
    LaunchedEffect(svc) {
        svc.probeResults.collect { r -> app.choice = probeResultSheet(app, r) }
    }
}

/** A test run's result, said plainly: where the save is, or what was seen and why it can't be used. */
private fun probeResultSheet(app: AppState, r: SaveProbeResult): ChoiceSpec {
    val kept = r.keptAt?.let { "\n\nThe test save is kept aside in $it." }.orEmpty()
    val close = listOf(MenuAction("ok", "Done", FuseIcons.Check, onSelect = { app.choice = null }))
    return when (r) {
        is SaveProbeResult.Found -> ChoiceSpec(
            title = "Found where ${r.title} saves",
            icon = FuseIcons.CircleCheck,
            message = (if (r.learned) "Its save goes in ${r.where}. Fuse keeps it in step there from now on." else "Its save goes in ${r.where}, where Fuse already looks.") +
                (r.settled?.let { " $it." } ?: "") + kept,
            options = close,
        )
        is SaveProbeResult.CantUse -> ChoiceSpec(
            title = "${r.title}'s save is somewhere Fuse can't use yet",
            icon = FuseIcons.Alert,
            message = "These changed while it ran:\n" + r.seen.joinToString("\n") +
                "\n\nFuse doesn't know how to keep them in step yet, so nothing was changed. It's also listed in Fuse One's activity." + kept,
            options = close,
        )
        is SaveProbeResult.NothingSeen -> ChoiceSpec(
            title = "No save appeared",
            icon = FuseIcons.FolderSearch,
            message = "Nothing new appeared in the folders Fuse watched:\n" + r.watched.joinToString("\n") +
                "\n\nIf you saved in the game, its emulator keeps saves elsewhere: choose that folder in Settings, Save folders.",
            options = close,
        )
    }
}
