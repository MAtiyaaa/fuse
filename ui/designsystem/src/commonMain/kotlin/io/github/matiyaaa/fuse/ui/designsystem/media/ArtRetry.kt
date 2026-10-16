package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/**
 * Art that failed to load is asked for again, a little later each time. A game's art is its web
 * address until Fuse keeps a copy, and a source busy for a moment, the network coming back or a
 * copy still being written made that first load fail: the tile or the room behind it then showed
 * nothing for as long as it was shown, though the art was there (opening the game, which asked
 * again, showed it). Shown again later, a failed picture is asked for anew anyway.
 */
object ArtRetry {
    /** How long after each failure the picture is asked for again; then it stays as it is. */
    val DELAYS_MS = longArrayOf(1_500, 5_000, 15_000, 45_000)

    /** Keeps each attempt its own request (and its own entry in the memory cache). */
    const val EXTRA = "fuse.retry"

    /** The attempt after [attempt], or null when there are no more to make. */
    fun delayAfter(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)

    /** While [failed], calls [again] after the next delay, until the attempts run out. */
    @Composable
    fun Watch(failed: Boolean, attempt: Int, again: () -> Unit) {
        val next by rememberUpdatedState(again)
        LaunchedEffect(failed, attempt) {
            if (!failed) return@LaunchedEffect
            val wait = delayAfter(attempt) ?: return@LaunchedEffect
            delay(wait)
            next()
        }
    }
}
