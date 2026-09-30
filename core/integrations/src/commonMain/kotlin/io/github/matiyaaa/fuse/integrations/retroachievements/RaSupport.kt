package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.Dates
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.model.Achievement
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.RecentAchievement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The user's RetroAchievements login for the Web API: their username and the web API key from
 * retroachievements.org/settings. Supplied by the app from its secure storage, never from the
 * environment. [toString] never prints the key.
 */
class RaCredentials(val username: String, webApiKey: String) {
    internal val key = Secret(webApiKey)

    override fun toString(): String = "RaCredentials(username=$username, webApiKey=***)"
    override fun equals(other: Any?): Boolean = other is RaCredentials && other.username == username && other.key == key
    override fun hashCode(): Int = username.hashCode() * 31 + key.hashCode()
}

/**
 * How long callers should keep each RetroAchievements response before asking again. RA enforces an
 * unpublished rate limit, so the data layer must cache: the client itself never caches.
 */
object RaCachePolicy {
    val PROFILE: Duration = 10.minutes
    val SUMMARY: Duration = 10.minutes
    val RECENT_ACHIEVEMENTS: Duration = 2.minutes
    val RECENTLY_PLAYED: Duration = 5.minutes
    val GAME_PROGRESS: Duration = 15.minutes
    val COMPLETION_PROGRESS: Duration = 15.minutes
    val AWARDS: Duration = 30.minutes
    val GAME_LIST: Duration = 7.days
    val GAME_HASHES: Duration = 7.days
    val CONSOLE_IDS: Duration = 30.days

    /** True while data fetched at [fetchedAt] (epoch millis) is younger than [ttl] at [now]. */
    fun isFresh(fetchedAt: Long, now: Long, ttl: Duration): Boolean = now - fetchedAt in 0 until ttl.inWholeMilliseconds
}

/** RetroAchievements image URLs. The API returns paths such as "/Badge/250336.png". */
object RaMedia {
    const val BASE = "https://media.retroachievements.org"

    /** Absolute URL for an RA media path; absolute URLs pass through; blank gives null. */
    fun url(path: String?): String? {
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) return null
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        return BASE + (if (p.startsWith("/")) p else "/$p")
    }

    fun badgeUrl(badgeName: String?): String = "$BASE/Badge/${badgeName.orEmpty().ifBlank { "00000" }}.png"

    /** The greyed-out variant shown for locked achievements. */
    fun badgeLockedUrl(badgeName: String?): String = "$BASE/Badge/${badgeName.orEmpty().ifBlank { "00000" }}_lock.png"
}

/** Maps a profile to the model. The profile has no rank; [rank] comes from the summary when known. */
fun RaUserProfile.toAchievementUser(fetchedAt: Long, rank: Long? = null): AchievementUser = AchievementUser(
    username = user,
    ulid = ulid,
    avatarUrl = RaMedia.url(userPic) ?: RaMedia.url("/UserPic/$user.png"),
    points = totalPoints,
    softcorePoints = totalSoftcorePoints,
    truePoints = totalTruePoints,
    rank = rank,
    motto = motto?.takeIf { it.isNotBlank() },
    richPresence = richPresenceMsg?.takeIf { it.isNotBlank() },
    fetchedAt = fetchedAt,
)

fun RaUserSummary.toAchievementUser(fetchedAt: Long): AchievementUser = AchievementUser(
    username = user,
    ulid = ulid,
    avatarUrl = RaMedia.url(userPic) ?: RaMedia.url("/UserPic/$user.png"),
    points = totalPoints,
    softcorePoints = totalSoftcorePoints,
    truePoints = totalTruePoints,
    rank = rank?.takeIf { it > 0 },
    motto = motto?.takeIf { it.isNotBlank() },
    richPresence = richPresenceMsg?.takeIf { it.isNotBlank() },
    fetchedAt = fetchedAt,
)

fun RaGameAchievement.toModel(gameId: Long): Achievement = Achievement(
    id = id,
    gameId = gameId,
    title = title,
    description = description,
    points = points,
    badgeUrl = RaMedia.badgeUrl(badgeName),
    badgeLockedUrl = RaMedia.badgeLockedUrl(badgeName),
    earnedAt = Dates.parseEpochMillis(dateEarned),
    earnedHardcoreAt = Dates.parseEpochMillis(dateEarnedHardcore),
    type = type?.takeIf { it.isNotBlank() },
    displayOrder = displayOrder,
)

/** Maps game info + the user's progress to the model, achievements in display order. */
fun RaGameProgress.toAchievementState(fetchedAt: Long): AchievementState {
    val list = achievements.map { it.toModel(id) }.sortedWith(compareBy({ it.displayOrder }, { it.id }))
    return AchievementState(
        raGameId = id,
        title = title,
        consoleName = consoleName,
        iconUrl = RaMedia.url(imageIcon),
        total = maxOf(numAchievements, list.size),
        earned = if (list.isEmpty()) numAwardedToUser else list.count { it.earned },
        earnedHardcore = if (list.isEmpty()) numAwardedToUserHardcore else list.count { it.earnedHardcoreAt != null },
        points = list.sumOf { it.points },
        pointsEarned = list.filter { it.earned }.sumOf { it.points },
        highestAward = highestAwardKind?.takeIf { it.isNotBlank() },
        achievements = list,
        fetchedAt = fetchedAt,
    )
}

/** Maps one recent unlock. [RecentAchievement.localGameId] is left for the data layer to fill. */
fun RaRecentAchievement.toModel(): RecentAchievement {
    val at = Dates.parseEpochMillis(date) ?: 0L
    return RecentAchievement(
        achievement = Achievement(
            id = achievementId,
            gameId = gameId,
            title = title,
            description = description,
            points = points,
            badgeUrl = RaMedia.url(badgeUrl) ?: RaMedia.badgeUrl(badgeName),
            badgeLockedUrl = RaMedia.badgeLockedUrl(badgeName),
            earnedAt = at,
            earnedHardcoreAt = if (hardcoreMode) at else null,
            type = type?.takeIf { it.isNotBlank() },
        ),
        gameTitle = gameTitle,
        consoleName = consoleName,
        gameIconUrl = RaMedia.url(gameIcon),
        hardcore = hardcoreMode,
        earnedAt = at,
    )
}
