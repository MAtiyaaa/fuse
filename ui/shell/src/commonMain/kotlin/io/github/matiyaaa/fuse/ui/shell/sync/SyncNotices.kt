package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.matiyaaa.fuse.sync.SyncNotice
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
                is SyncNotice.CantUse -> app.toasts.show(
                    "${n.title}: the newest ${n.kind.label.lowercase()} can't be used here. ${n.why}",
                    ToastKind.WARNING,
                    icon = FuseIcons.CloudOff,
                    durationMs = 8_000L,
                )
            }
        }
    }
}
