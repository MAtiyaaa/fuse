package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.PlayStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.JournalWords
import kotlinx.coroutines.launch

/** Each status's mark: the same on a game's page, its tile, the Journal sheet, the Library and Home. */
internal fun statusIcon(s: PlayStatus): ImageVector = when (s) {
    PlayStatus.WANT -> FuseIcons.Bookmark
    PlayStatus.PLAYING -> FuseIcons.Joystick
    PlayStatus.PAUSED -> FuseIcons.CirclePause
    PlayStatus.BEATEN -> FuseIcons.Flag
    PlayStatus.COMPLETED -> FuseIcons.Crown
    PlayStatus.DROPPED -> FuseIcons.CircleSlash
}

/** "Playing since 3 Oct", "Beaten yesterday": the status with when it was set, or its name alone when that isn't known. */
internal fun statusSince(s: PlayStatus, at: Long?, now: Long): String {
    if (at == null || at <= 0) return JournalWords.label(s)
    val day = io.github.matiyaaa.fuse.ui.shell.reach.dayText(at, now)
    return when (s) {
        PlayStatus.WANT -> "On the list since $day"
        PlayStatus.PLAYING -> "Playing since $day"
        PlayStatus.PAUSED -> "On hold since $day"
        else -> "${JournalWords.label(s)} $day"
    }
}

/**
 * [title]'s Journal: where the person is with it (a status, or none), their rating and their note.
 * Choosing a status keeps the sheet open on the same row, so the rest can be set in one go; the
 * rating and the note open their own sheet and input, and come back here.
 */
fun AppState.journalSheet(game: GameId, title: String, journal: GameJournal) {
    contextMenu = null
    fun write(next: GameJournal) {
        scope.launch { store.library.setJournal(game, next) }
        journalSheet(game, title, next)
    }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val where = "Where you are"
    val statuses = JournalWords.order.filterNotNull().map { s ->
        MenuAction(
            "status.${s.name}", JournalWords.label(s), statusIcon(s),
            detail = if (journal.status == s) statusSince(s, journal.statusAt, now) else JournalWords.blurb(s),
            trailing = Trailing.Check(journal.status == s),
            section = where,
            // Set again, it keeps when it was first set; a new status is set now.
            onSelect = { write(journal.copy(status = s, statusAt = if (journal.status == s) journal.statusAt else now)) },
        )
    } + MenuAction(
        "status.none", "No status", FuseIcons.CircleDot,
        detail = "Not on any of your lists",
        trailing = Trailing.Check(journal.status == null),
        section = where,
        onSelect = { write(journal.copy(status = null, statusAt = null)) },
    )
    val thoughts = "What you think"
    val rating = MenuAction(
        "rating", "Your rating", FuseIcons.Star,
        detail = JournalWords.stars(journal.rating) ?: "Not rated yet",
        trailing = Trailing.Rating(journal.rating),
        section = thoughts,
        onSelect = { ratingSheet(game, title, journal) },
    )
    val note = MenuAction(
        "note", if (journal.note.isNullOrBlank()) "Write a note" else "Your note", FuseIcons.SquarePen,
        detail = JournalWords.glimpse(journal) ?: "Where you got to, what to try next",
        trailing = Trailing.Chevron,
        section = thoughts,
        onSelect = {
            choice = null
            textInput = TextInputSpec(
                "Your note",
                journal.note.orEmpty(),
                placeholder = "Where you got to, what to try next",
                message = "Only yours: kept with your profile on every device.",
                doneLabel = "Keep",
                onCancel = { journalSheet(game, title, journal) },
            ) { text -> write(journal.copy(note = text.trim().ifEmpty { null })) }
        },
    )
    choice = ChoiceSpec(
        title = title,
        icon = FuseIcons.NotebookPen,
        message = "Your Journal for it. It goes with your profile, and to RomM for a game from there.",
        options = statuses + rating + note,
    )
}

/** The rating sheet: five stars to half a star, or none. Back and a choice both return to the Journal. */
private fun AppState.ratingSheet(game: GameId, title: String, journal: GameJournal) {
    fun pick(r: Int) {
        val next = journal.copy(rating = r)
        scope.launch { store.library.setJournal(game, next) }
        journalSheet(game, title, next)
    }
    choice = ChoiceSpec(
        title = "Your rating",
        icon = FuseIcons.Star,
        message = title,
        options = (GameJournal.MOST_RATING downTo 1).map { r ->
            MenuAction("rate.$r", JournalWords.stars(r)!!, trailing = Trailing.Rating(r), detail = if (journal.rating == r) "Your rating now" else null, onSelect = { pick(r) })
        } + MenuAction("rate.0", "Not rated", FuseIcons.CircleDot, trailing = Trailing.Check(journal.rating == 0), onSelect = { pick(0) }),
        onBack = { journalSheet(game, title, journal) },
    )
}
