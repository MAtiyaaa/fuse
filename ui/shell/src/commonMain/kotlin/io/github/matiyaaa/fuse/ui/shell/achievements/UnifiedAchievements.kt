package io.github.matiyaaa.fuse.ui.shell.achievements

import io.github.matiyaaa.fuse.model.AchievementSource
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed
import io.github.matiyaaa.fuse.ui.shell.store.LocalGameAchievements

/** Where an unlock or a set comes from, as its small mark says. */
enum class AchievementOrigin(val mark: String, val label: String) {
    RETRO("RA", "RetroAchievements"),
    PS3("PS3", "PS3 trophies"),
    PS4("PS4", "PS4 trophies"),
    VITA("Vita", "Vita trophies"),
    XBOX360("360", "Xbox 360 achievements"),
    STEAM("Steam", "Steam achievements");

    companion object {
        fun of(source: AchievementSource): AchievementOrigin = when (source) {
            AchievementSource.RETRO_ACHIEVEMENTS -> RETRO
            AchievementSource.TROPHIES -> PS3
            AchievementSource.SHADPS4 -> PS4
            AchievementSource.VITA3K -> VITA
            AchievementSource.XENIA -> XBOX360
            AchievementSource.STEAM -> STEAM
        }
    }
}

/** One unlock, from any source: what, in which game, when, and what it was worth. */
data class UnlockEntry(
    val origin: AchievementOrigin,
    val title: String,
    val game: String,
    val badge: String?,
    val earnedAt: Long,
    /** "10 pts", "Gold", "20 G"; null where it carries no worth (Steam). */
    val worth: String?,
    val hardcore: Boolean = false,
    /** The library game it opens, when Fuse knows it. */
    val gameId: GameId? = null,
    /** The RetroAchievements game, for RetroAchievements unlocks. */
    val raGameId: Long = 0,
)

/** One game's set, from any source, with when it last had an unlock. */
data class GameEntry(
    val origin: AchievementOrigin,
    val state: AchievementState,
    val lastUnlock: Long,
    val gameId: GameId? = null,
) {
    val completed: Boolean get() = state.mastered || (state.total > 0 && state.earned >= state.total)
}

/** The page's totals across every source. */
data class AchievementTotals(
    val unlocked: Int = 0,
    val completed: Int = 0,
    /** RetroAchievements points; null when it isn't set up. */
    val raPoints: Long? = null,
    val platinum: Int = 0,
    val gold: Int = 0,
    val silver: Int = 0,
    val bronze: Int = 0,
    /** Xbox 360 gamerscore earned. */
    val gamerscore: Int = 0,
    val steam: Int = 0,
) {
    val trophies: Int get() = platinum + gold + silver + bronze
}

/**
 * Every achievement in one place: RetroAchievements (when it is set up) and what this device keeps
 * for the library's games (PS3, PS4, Vita and Xbox 360, Steam), merged into one list of unlocks by
 * time and one of games in progress and completed, each item marked with where it is from.
 */
data class UnifiedAchievements(
    val user: AchievementUser?,
    val recent: List<UnlockEntry>,
    val inProgress: List<GameEntry>,
    val completed: List<GameEntry>,
    val totals: AchievementTotals,
) {
    val empty: Boolean get() = recent.isEmpty() && inProgress.isEmpty() && completed.isEmpty()

    companion object {
        const val RECENT_LIMIT = 24
        const val SHELF_LIMIT = 20

        fun of(feed: AchievementsFeed?, local: List<LocalGameAchievements>): UnifiedAchievements {
            val unlocks = ArrayList<UnlockEntry>()
            val games = ArrayList<GameEntry>()
            var platinum = 0
            var gold = 0
            var silver = 0
            var bronze = 0
            var gamerscore = 0
            var steam = 0
            var localUnlocked = 0

            feed?.let { f ->
                f.recent.mapTo(unlocks) { r ->
                    UnlockEntry(
                        AchievementOrigin.RETRO, r.achievement.title, r.gameTitle, r.achievement.badgeUrl.ifBlank { null }, r.earnedAt,
                        "${r.achievement.points} pts", r.hardcore, r.localGameId, r.achievement.gameId,
                    )
                }
                val matched = f.recent.mapNotNull { r -> r.localGameId?.let { r.achievement.gameId to it } }.toMap()
                (f.inProgress + f.recentlyMastered).distinctBy { it.raGameId }.mapTo(games) { s ->
                    GameEntry(AchievementOrigin.RETRO, s, f.lastUnlock[s.raGameId] ?: 0L, matched[s.raGameId])
                }
            }

            for (l in local) {
                val origin = AchievementOrigin.of(l.state.source)
                val earned = l.state.achievements.filter { it.earned }
                localUnlocked += earned.size
                for (a in earned) {
                    when (origin) {
                        AchievementOrigin.XBOX360 -> gamerscore += a.points
                        AchievementOrigin.STEAM -> steam++
                        else -> when (a.type?.lowercase()) {
                            "platinum", "p" -> platinum++
                            "gold", "g" -> gold++
                            "silver", "s" -> silver++
                            else -> bronze++
                        }
                    }
                    // An unlock with no time (kept without one) still counts, but has no place in a timeline.
                    val at = a.earnedAt ?: continue
                    if (at <= 0L) continue
                    val worth = when (origin) {
                        AchievementOrigin.XBOX360 -> "${a.points} G"
                        AchievementOrigin.STEAM -> a.rarity?.let { "${formatRarity(it)} have it" }
                        else -> a.type?.let(::grade)
                    }
                    unlocks += UnlockEntry(origin, a.title, l.state.title, a.badgeUrl.ifBlank { null }, at, worth, gameId = l.game)
                }
                if (earned.isNotEmpty()) {
                    val last = earned.maxOf { it.earnedAt ?: 0L }
                    games += GameEntry(origin, l.state, last, l.game)
                }
            }

            val ordered = games.sortedByDescending { it.lastUnlock }
            val unlocked = (feed?.earnedTotal ?: 0) + localUnlocked
            val completedCount = (feed?.masteredTotal ?: 0) + local.count { it.state.total > 0 && it.state.earned >= it.state.total }
            return UnifiedAchievements(
                user = feed?.user,
                recent = unlocks.sortedByDescending { it.earnedAt }.take(RECENT_LIMIT),
                inProgress = ordered.filter { !it.completed && it.state.earned > 0 }.take(SHELF_LIMIT),
                completed = ordered.filter { it.completed }.take(SHELF_LIMIT),
                totals = AchievementTotals(
                    unlocked = unlocked,
                    completed = completedCount,
                    raPoints = feed?.user?.points,
                    platinum = platinum, gold = gold, silver = silver, bronze = bronze,
                    gamerscore = gamerscore,
                    steam = steam,
                ),
            )
        }

        private fun grade(type: String): String? = when (type.lowercase()) {
            "platinum", "p" -> "Platinum"
            "gold", "g" -> "Gold"
            "silver", "s" -> "Silver"
            "bronze", "b" -> "Bronze"
            else -> null
        }

        private fun formatRarity(percent: Float): String = when {
            percent < 1f -> "${(percent * 10).toInt() / 10f}%"
            else -> "${percent.toInt()}%"
        }
    }
}
