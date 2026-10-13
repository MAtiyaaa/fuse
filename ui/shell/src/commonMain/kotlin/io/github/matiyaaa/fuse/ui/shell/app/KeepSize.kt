package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After a change to how large Fuse draws, asks whether to keep it, and goes back by itself unless
 * Keep is chosen within [KEEP_SECONDS]. A size too large for the screen can push every button out
 * of sight; then nothing needs to be reached: waiting is enough. Back, or anything else closing the
 * question, goes back too. [undo] puts the size before the change back.
 */
fun AppState.keepSizeOrUndo(undo: () -> Unit) {
    // A second change while the question is open: going back still returns to the size before both.
    val back = keepSize?.takeIf { it.job.isActive }?.let { it.job.cancel(); it.undo } ?: undo
    var kept = false
    fun spec(left: Int) = ChoiceSpec(
        title = KEEP_TITLE,
        message = "Fuse goes back to the size before in $left ${if (left == 1) "second" else "seconds"}",
        icon = FuseIcons.Scaling,
        options = listOf(
            MenuAction("keep", "Keep This Size", FuseIcons.Check, onSelect = { kept = true; choice = null }),
            MenuAction("undo", "Go Back", FuseIcons.Undo, onSelect = { choice = null }),
        ),
    )
    choice = spec(KEEP_SECONDS)
    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        // Closed one way or another, or the time is up: whichever comes first.
        var left = KEEP_SECONDS
        withTimeoutOrNull(KEEP_SECONDS * 1_000L) {
            val tick = launch {
                while (left > 1) {
                    delay(1_000)
                    left--
                    if (choice?.title == KEEP_TITLE) choice = spec(left)
                }
            }
            snapshotFlow { choice?.title == KEEP_TITLE }.first { !it }
            tick.cancel()
        }
        keepSize = null
        if (kept) return@launch
        if (choice?.title == KEEP_TITLE) choice = null
        back()
        toasts.show("Back to the size before")
    }
    keepSize = PendingSize(job, back)
    job.start()
}

/** A size change waiting for Keep: the countdown, and what puts the size before it back. */
internal class PendingSize(val job: Job, val undo: () -> Unit)

private const val KEEP_TITLE = "Keep this size?"
private const val KEEP_SECONDS = 5
