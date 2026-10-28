package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Light row for list screens (Library grids, Home rows, search). Loads no JSON and no children;
 * open [Game] with [GameRepository.get] when the user selects one.
 */
data class GameSummary(
    val id: GameId,
    val platformId: PlatformId,
    val titles: GameTitles,
    val favorite: Boolean,
    val hidden: Boolean,
    val pinned: Boolean,
    /** The file is gone. Only [GameRepository.observeMissing] lists these. */
    val missing: Boolean,
    /** Removed from Fuse by the user. Only [GameRepository.observeRemoved] lists these. */
    val removed: Boolean,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val trackedSeconds: Long,
    val importedSeconds: Long,
    val sessions: Int,
    val releaseYear: Int?,
    val dlcCount: Int,
    val updateCount: Int,
    val discCount: Int,
    /** An installed app played as a game ([io.github.matiyaaa.fuse.model.AppGames]), not a file. */
    val isApp: Boolean = false,
    /** The platform folder the scanner found the game in; tells which library folder (and drive) holds it. */
    val folderPath: String = "",
    /** Where the person is with it (the Journal) and since when; its rating out of ten, 0 for none. */
    val status: io.github.matiyaaa.fuse.model.PlayStatus? = null,
    val statusAt: Long? = null,
    val rating: Int = 0,
) {
    val displayTitle: String get() = titles.display
    val sortKey: String get() = titles.sortKey
    val totalSeconds: Long get() = trackedSeconds + importedSeconds
    val hasDlc: Boolean get() = dlcCount > 0
}

/** A full [Game] plus the status flags the shared model does not carry. */
data class GameRecord(
    val game: Game,
    val pinned: Boolean,
    val missing: Boolean,
    val missingSince: Long?,
    /** Epoch millis of "Remove from Fuse", or null. */
    val removedAt: Long?,
)

/** A game as moving in matches it: its system, its files and its Steam id. */
data class MoveInRow(val id: GameId, val platform: String, val path: String, val launchPath: String, val steamAppId: Long?)

/**
 * What moving in may change of a game, as it was: favourite, hidden, imported play time and where
 * from, last played, and the Journal. Kept so a move in can be put back exactly.
 */
@kotlinx.serialization.Serializable
data class PlayState(
    val id: GameId,
    val favorite: Boolean,
    val hidden: Boolean,
    val importedSeconds: Long,
    val importedSource: String?,
    val lastPlayedAt: Long?,
    val journal: io.github.matiyaaa.fuse.model.GameJournal,
)

/** A game as suggestions read it: its details and what was done with it here. */
data class RecommendationRow(
    val id: GameId,
    val platformId: PlatformId,
    val title: String,
    val favorite: Boolean,
    val lastPlayedAt: Long?,
    val playSeconds: Long,
    val sessions: Int,
    val addedAt: Long,
    val year: Int?,
    val genres: List<String>,
    val developer: String?,
    val franchise: String?,
    val rating: Int?,
    /** The most people who can play it at once, when its details say ([mostPlayers]). */
    val players: Int? = null,
    /** Where the person is with it, in their Journal, and since when; their own rating out of ten. */
    val status: io.github.matiyaaa.fuse.model.PlayStatus? = null,
    val statusAt: Long? = null,
    val stars: Int = 0,
)
