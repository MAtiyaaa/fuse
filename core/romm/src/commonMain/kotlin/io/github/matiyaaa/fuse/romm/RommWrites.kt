package io.github.matiyaaa.fuse.romm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A change the person made in Fuse to a game from RomM, on its way to RomM through RomM's own API:
 * a favourite (RomM's favourites collection), a game hidden or shown, a play session. Kept, in the
 * order made, until RomM takes it, so a change made while RomM is away goes once it answers.
 */
@Serializable
sealed interface RommWrite {
    /** RomM's game it is about. */
    val romId: Long

    /** Marked a favourite, or not ([RommClient.favourites], [RommClient.collectionRoms]). */
    @Serializable
    @SerialName("favourite")
    data class Favourite(override val romId: Long, val on: Boolean) : RommWrite

    /** Hidden from the library, or shown again ([RommClient.setHidden]). */
    @Serializable
    @SerialName("hidden")
    data class Hidden(override val romId: Long, val on: Boolean) : RommWrite

    /** Played, from [startedAt] to [endedAt] (epoch milliseconds, [RommClient.playSessions]). */
    @Serializable
    @SerialName("played")
    data class Played(override val romId: Long, val startedAt: Long, val endedAt: Long) : RommWrite {
        val session: RommPlayed get() = RommPlayed(romId, startedAt, endedAt)
    }
}

/** What waits to go to RomM, kept in the order it was made. */
object RommWrites {
    /** The most changes kept waiting; beyond it the oldest go first (a device away for months). */
    const val MOST = 2_000

    /** A play session shorter than this isn't one RomM takes (its start and end are kept to the second). */
    const val SHORTEST_SESSION_MS = 1_000L

    /**
     * [queue] with [w] at its end. A later favourite or hide of the same game replaces one not sent
     * yet (only where the person left it goes); every play session is its own.
     */
    fun add(queue: List<RommWrite>, w: RommWrite): List<RommWrite> {
        // A session too short for RomM, or one already waiting (counted twice), adds nothing.
        if (w is RommWrite.Played && (w.endedAt - w.startedAt < SHORTEST_SESSION_MS || w in queue)) return queue
        val kept = when (w) {
            is RommWrite.Favourite -> queue.filterNot { it is RommWrite.Favourite && it.romId == w.romId }
            is RommWrite.Hidden -> queue.filterNot { it is RommWrite.Hidden && it.romId == w.romId }
            is RommWrite.Played -> queue
        }
        return (kept + w).takeLast(MOST)
    }

    /**
     * The next change to send, and the ones that go with it: play sessions go together (as many in
     * a row as RomM takes at once), anything else alone. Empty when nothing waits.
     */
    fun next(queue: List<RommWrite>): List<RommWrite> {
        val first = queue.firstOrNull() ?: return emptyList()
        if (first !is RommWrite.Played) return listOf(first)
        return queue.takeWhile { it is RommWrite.Played }.take(RommClient.MAX_SESSIONS)
    }

    /** [queue] without [sent] (each sent change once, as it was when taken). */
    fun without(queue: List<RommWrite>, sent: List<RommWrite>): List<RommWrite> {
        val left = sent.toMutableList()
        return queue.filterNot { w -> left.remove(w) }
    }
}
