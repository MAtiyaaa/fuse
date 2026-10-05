package io.github.matiyaaa.fuse.ui.shell.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel

/**
 * The menu music's remote, for the quick menu's Now playing: which song is on, a pause that lasts
 * until it is lifted (or Fuse restarts), and skips forward and back. The menu music itself reads
 * and answers it.
 */
internal object MenuMusicRemote {
    /** The song playing now, by bundled id or [BundledMusic.OWN_SONG]; null while there is none. */
    var current by mutableStateOf<String?>(null)

    /** Paused from the quick menu. */
    var paused by mutableStateOf(false)

    /** How many skips have been asked for, for the skip animation (the sign is the direction). */
    var skipCount by mutableIntStateOf(0)
        private set

    /** The last skip's direction: 1 forward, -1 back. */
    var lastSkip by mutableIntStateOf(1)
        private set

    internal val skips = Channel<Int>(Channel.UNLIMITED)

    fun skip(direction: Int) {
        lastSkip = if (direction < 0) -1 else 1
        skipCount++
        paused = false
        skips.trySend(lastSkip)
    }

    fun toggle() {
        paused = !paused
    }

    /**
     * The song a skip lands on without shuffle: the next or previous bundled song in album order,
     * wrapping round; from the user's own song, the first or last bundled one.
     */
    fun neighbour(current: String?, direction: Int): String {
        val all = BundledMusic.tracks
        val i = all.indexOfFirst { it.id == current }
        if (i < 0) return if (direction < 0) all.last().id else all.first().id
        return all[(i + direction).mod(all.size)].id
    }
}
