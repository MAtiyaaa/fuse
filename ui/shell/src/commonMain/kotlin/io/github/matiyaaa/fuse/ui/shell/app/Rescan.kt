package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A rescan the person asked for: it says it started, and once it ends what it found ("2 new games,
 * 1 gone", or that nothing changed), so pressing Rescan never ends in silence. [what] names what is
 * read again, for the first notice.
 */
fun AppState.rescanAndReport(scope: ScanScope, platform: PlatformId? = null, what: String = "your library") {
    val sources = store.sources
    sources.rescan(scope, platform)
    toasts.show("Rescanning $what")
    this.scope.launch {
        // The scan this asked for: it starts (leaves rest), then comes to an end.
        withTimeoutOrNull(START_WAIT_MS) { sources.scan.first { it.phase !in RESTING } } ?: return@launch
        val end = withTimeoutOrNull(FINISH_WAIT_MS) { sources.scan.first { it.phase in RESTING } } ?: return@launch
        when (end.phase) {
            ScanPhase.FAILED -> toasts.show("The rescan couldn't finish. Check that the folders can be read", ToastKind.ERROR)
            else -> toasts.show(rescanWords(end.added, end.removed, end.changed))
        }
    }
}

/** "2 new games and 1 gone", "1 game changed", or "Up to date: nothing new" when it found no change. */
internal fun rescanWords(added: Int, removed: Int, changed: Int): String {
    val parts = buildList {
        if (added > 0) add("$added new ${if (added == 1) "game" else "games"}")
        if (removed > 0) add("$removed gone")
        if (changed > 0) add("$changed changed")
    }
    return if (parts.isEmpty()) "Up to date: nothing new" else "Rescanned: " + parts.joinToString(", ")
}

private val RESTING = setOf(ScanPhase.IDLE, ScanPhase.DONE, ScanPhase.FAILED)
private const val START_WAIT_MS = 5_000L
private const val FINISH_WAIT_MS = 30 * 60_000L
