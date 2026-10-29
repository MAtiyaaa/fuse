package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.model.PlayStatus
import kotlinx.serialization.Serializable

/**
 * The person's own marks on a game in RomM (`rom_user` on each game, `RomUserSchema` in
 * backend/endpoints/responses/rom.py): its status, the two flags beside it, a rating out of ten
 * (0 is none) and when any of them last changed.
 */
@Serializable
data class RommMarks(
    /** `RomUserStatus`: incomplete, finished, completed_100, retired or never_playing. */
    val status: String? = null,
    val backlogged: Boolean = false,
    val nowPlaying: Boolean = false,
    val rating: Int = 0,
    val updatedAt: Long = 0,
)

/**
 * Fuse's Journal in RomM's words and back. RomM keeps a status and two flags where Fuse keeps one
 * status: Want to play is RomM's backlog, Playing is "now playing" (and incomplete), On hold is
 * incomplete without it, Beaten is finished, Completed is completed_100 and Dropped is retired.
 * RomM's "never playing" reads as Dropped; a game Fuse has no status for is none of them.
 */
object RommJournal {
    const val INCOMPLETE = "incomplete"
    const val FINISHED = "finished"
    const val COMPLETED = "completed_100"
    const val RETIRED = "retired"
    const val NEVER = "never_playing"

    /** RomM's marks for [status], with [rating] as it is. */
    fun marks(status: PlayStatus?, rating: Int): RommMarks = when (status) {
        null -> RommMarks(rating = rating)
        PlayStatus.WANT -> RommMarks(backlogged = true, rating = rating)
        PlayStatus.PLAYING -> RommMarks(INCOMPLETE, nowPlaying = true, rating = rating)
        PlayStatus.PAUSED -> RommMarks(INCOMPLETE, rating = rating)
        PlayStatus.BEATEN -> RommMarks(FINISHED, rating = rating)
        PlayStatus.COMPLETED -> RommMarks(COMPLETED, rating = rating)
        PlayStatus.DROPPED -> RommMarks(RETIRED, rating = rating)
    }

    /** The status RomM's [marks] say: an ending first, then playing, on hold, wanted. */
    fun statusOf(marks: RommMarks): PlayStatus? = when {
        marks.status == COMPLETED -> PlayStatus.COMPLETED
        marks.status == FINISHED -> PlayStatus.BEATEN
        marks.status == RETIRED || marks.status == NEVER -> PlayStatus.DROPPED
        marks.nowPlaying -> PlayStatus.PLAYING
        marks.status == INCOMPLETE -> PlayStatus.PAUSED
        marks.backlogged -> PlayStatus.WANT
        else -> null
    }
}
