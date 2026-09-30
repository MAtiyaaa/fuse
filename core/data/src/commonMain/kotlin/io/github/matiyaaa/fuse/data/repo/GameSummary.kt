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
