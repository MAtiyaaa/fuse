package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.PlayStatus

/**
 * How the Journal is said, the same on a game's page, its tile, the Library and Home: each status's
 * name and line, the order they are listed in, and a rating in stars.
 */
object JournalWords {
    /** The order statuses are listed in: what is being played, wanted, on hold, then done with. */
    val order: List<PlayStatus?> = listOf(
        PlayStatus.PLAYING, PlayStatus.WANT, PlayStatus.PAUSED, PlayStatus.BEATEN, PlayStatus.COMPLETED, PlayStatus.DROPPED, null,
    )

    fun label(s: PlayStatus): String = when (s) {
        PlayStatus.WANT -> "Want to play"
        PlayStatus.PLAYING -> "Playing"
        PlayStatus.PAUSED -> "On hold"
        PlayStatus.BEATEN -> "Beaten"
        PlayStatus.COMPLETED -> "Completed"
        PlayStatus.DROPPED -> "Dropped"
    }

    /** What the status means, under its name where there is room. */
    fun blurb(s: PlayStatus): String = when (s) {
        PlayStatus.WANT -> "Up next: one to get to"
        PlayStatus.PLAYING -> "On the go right now"
        PlayStatus.PAUSED -> "Put down for now, coming back to it"
        PlayStatus.BEATEN -> "Played through to the end"
        PlayStatus.COMPLETED -> "Everything in it done"
        PlayStatus.DROPPED -> "Put down for good"
    }

    /** A list of games with [s], as the Library and Home name it. */
    fun shelf(s: PlayStatus): String = when (s) {
        PlayStatus.WANT -> "Up next"
        else -> label(s)
    }

    /** [rating] out of ten as stars: "4.5 stars"; null when not rated. */
    fun stars(rating: Int): String? {
        if (rating <= 0) return null
        val whole = rating / 2
        val text = if (rating % 2 == 0) "$whole" else "$whole.5"
        return if (rating == 2) "1 star" else "$text stars"
    }

    /** The note as one line, for a card that has room for a glimpse of it. */
    fun glimpse(journal: GameJournal, most: Int = 90): String? {
        val note = journal.note?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (note.length <= most) note else note.take(most).substringBeforeLast(' ').trimEnd(',', '.', ';', ':') + "\u2026"
    }
}
