package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * One observed play session. Fuse records the time it handed the game to an emulator and the time
 * the user came back to Fuse; that is all it can honestly see without extra permissions.
 */
@Serializable
data class PlaySession(
    val id: Long,
    val gameId: GameId,
    val emulatorId: EmulatorId?,
    val startedAt: Long,
    val endedAt: Long?,
    val source: PlaySessionSource = PlaySessionSource.FUSE_LAUNCH,
) {
    val durationSeconds: Long? get() = endedAt?.let { (it - startedAt).coerceAtLeast(0) / 1000 }
}

@Serializable
enum class PlaySessionSource {
    /** Launch to return, as seen by Fuse. */
    FUSE_LAUNCH,
    /** Refined with Android usage statistics (optional permission). */
    USAGE_STATS,
    /** Imported from another source; kept separate from observed time. */
    IMPORTED,
}

@Serializable
data class GameCollection(
    val id: CollectionId,
    val name: String,
    val kind: CollectionKind,
    val gameCount: Int = 0,
    val order: Int = 0,
)

@Serializable
enum class CollectionKind {
    MANUAL,
    FAVORITES,
    RECENT,
    PLAYING,
    COMPLETED,
    UNPLAYED,
    /** Rule-based: one platform, genre or series. */
    SMART,
}

/** RetroAchievements data, cached. Fuse only displays it; emulators unlock achievements. */
@Serializable
data class AchievementUser(
    val username: String,
    val ulid: String?,
    val avatarUrl: String?,
    val points: Long,
    val softcorePoints: Long,
    val truePoints: Long,
    val rank: Long?,
    val motto: String?,
    val richPresence: String?,
    val fetchedAt: Long,
)

@Serializable
data class Achievement(
    val id: Long,
    val gameId: Long,
    val title: String,
    val description: String,
    val points: Int,
    val badgeUrl: String,
    val badgeLockedUrl: String,
    val earnedAt: Long?,
    val earnedHardcoreAt: Long?,
    val type: String? = null,
    val displayOrder: Int = 0,
) {
    val earned: Boolean get() = earnedAt != null || earnedHardcoreAt != null
}

@Serializable
data class AchievementState(
    val raGameId: Long,
    val title: String,
    val consoleName: String?,
    val iconUrl: String?,
    val total: Int,
    val earned: Int,
    val earnedHardcore: Int,
    val points: Int,
    val pointsEarned: Int,
    /** "mastered", "completed", "beaten-hardcore", "beaten-softcore" or null. */
    val highestAward: String?,
    val achievements: List<Achievement> = emptyList(),
    val fetchedAt: Long,
) {
    val progress: Float get() = if (total == 0) 0f else earned.toFloat() / total
    val mastered: Boolean get() = highestAward == "mastered"
}

@Serializable
data class RecentAchievement(
    val achievement: Achievement,
    val gameTitle: String,
    val consoleName: String?,
    val gameIconUrl: String?,
    val hardcore: Boolean,
    val earnedAt: Long,
    /** The local game, when Fuse matched the RetroAchievements game to one in the library. */
    val localGameId: GameId? = null,
)
