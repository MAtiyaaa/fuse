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
        if (status.pending > 0) "${status.hostName} isn't answering. ${status.pending} ${if (status.pending == 1) "change waits" else "changes wait"} here, safe" else "${status.hostName} isn't answering. Everything works here and catches up later",
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
