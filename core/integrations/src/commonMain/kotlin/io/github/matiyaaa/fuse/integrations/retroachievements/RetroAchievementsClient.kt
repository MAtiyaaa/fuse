package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.RawResponse
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.integrations.map
import io.github.matiyaaa.fuse.integrations.systemEpochMillis
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * RetroAchievements Web API client (`https://retroachievements.org/API/API_<Name>.php`). The web API
 * key goes in the `y` parameter and the target user in `u` (username or ULID; usernames can change,
 * so store the ULID). Fuse only reads and displays this data; emulators unlock achievements.
 *
 * RA rate limits without publishing numbers, so the default [limiter] is conservative (one request
 * at a time, 400 ms apart) and responses must be cached by the caller per [RaCachePolicy].
 */
class RetroAchievementsClient(
    http: HttpClient,
    private val credentials: RaCredentials,
    limiter: RateLimiter = defaultLimiter(),
    private val clock: () -> Long = ::systemEpochMillis,
    private val baseUrl: String = BASE_URL,
) {
    private val api = ProviderHttp(http, "RetroAchievements", limiter, { listOf(credentials.key) })

    /** Onboarding check: loads the credential owner's profile. AuthError means a wrong key. */
    suspend fun verifyCredentials(): ApiResult<AchievementUser> =
        userProfile(credentials.username).map { it.toAchievementUser(clock()) }

    /** API_GetUserProfile. */
    suspend fun userProfile(user: String = credentials.username): ApiResult<RaUserProfile> =
        get("GetUserProfile", RaUserProfile.serializer(), "u" to user)

    /** API_GetUserSummary with [recentGames] (g) and [recentAchievements] (a) entries. */
    suspend fun userSummary(
        user: String = credentials.username,
        recentGames: Int = 5,
        recentAchievements: Int = 10,
    ): ApiResult<RaUserSummary> =
        get("GetUserSummary", RaUserSummary.serializer(), "u" to user, "g" to recentGames, "a" to recentAchievements)

    /** The model user including rank (from the summary). */
    suspend fun achievementUser(user: String = credentials.username): ApiResult<AchievementUser> =
        userSummary(user, recentGames = 0, recentAchievements = 0).map { it.toAchievementUser(clock()) }

    /** API_GetUserRecentAchievements: unlocks in the last [minutes] (m), mapped to the model. */
    suspend fun recentAchievements(user: String = credentials.username, minutes: Int = 60): ApiResult<List<RecentAchievement>> =
        get("GetUserRecentAchievements", RaRecentAchievementList, "u" to user, "m" to minutes)
            .map { list -> list.map { it.toModel() }.sortedByDescending { it.earnedAt } }

    /** API_GetGameInfoAndUserProgress (g, u, a=1) as the raw response. */
    suspend fun gameInfoAndProgress(gameId: Long, user: String = credentials.username): ApiResult<RaGameProgress> =
        get("GetGameInfoAndUserProgress", RaGameProgress.serializer(), "g" to gameId, "u" to user, "a" to 1)

    /** [gameInfoAndProgress] mapped to the model. */
    suspend fun gameProgress(gameId: Long, user: String = credentials.username): ApiResult<AchievementState> =
        gameInfoAndProgress(gameId, user).map { it.toAchievementState(clock()) }

    /** One page of API_GetUserCompletionProgress; [count] (c) is capped at 500. */
    suspend fun completionProgress(
        user: String = credentials.username,
        count: Int = MAX_COMPLETION_PAGE,
        offset: Int = 0,
    ): ApiResult<RaCompletionPage> = get(
        "GetUserCompletionProgress",
        RaCompletionPage.serializer(),
        "u" to user,
        "c" to count.coerceIn(1, MAX_COMPLETION_PAGE),
        "o" to offset.coerceAtLeast(0),
    )

    /** Every page of API_GetUserCompletionProgress, at most [maxPages] requests. */
    suspend fun allCompletionProgress(user: String = credentials.username, maxPages: Int = 20): ApiResult<List<RaCompletionEntry>> {
        val all = ArrayList<RaCompletionEntry>()
        var offset = 0
        repeat(maxPages) {
            when (val page = completionProgress(user, MAX_COMPLETION_PAGE, offset)) {
                is ApiResult.Failure -> return page
                is ApiResult.Success -> {
                    all += page.value.results
                    offset += page.value.results.size
                    if (page.value.results.isEmpty() || offset >= page.value.total) return ApiResult.Success(all)
                }
            }
        }
        return ApiResult.Success(all)
    }

    /** API_GetUserAwards. */
    suspend fun userAwards(user: String = credentials.username): ApiResult<RaUserAwards> =
        get("GetUserAwards", RaUserAwards.serializer(), "u" to user)

    /** API_GetUserRecentlyPlayedGames; [count] (c) is capped at 50. */
    suspend fun recentlyPlayed(
        user: String = credentials.username,
        count: Int = 10,
        offset: Int = 0,
    ): ApiResult<List<RaRecentlyPlayedGame>> = get(
        "GetUserRecentlyPlayedGames",
        RaRecentlyPlayedList,
        "u" to user,
        "c" to count.coerceIn(1, MAX_RECENTLY_PLAYED),
        "o" to offset.coerceAtLeast(0),
    )

    /** API_GetGameHashes: every ROM hash RA accepts for [gameId]. */
    suspend fun gameHashes(gameId: Long): ApiResult<List<RaGameHash>> =
        get("GetGameHashes", RaGameHashesResponse.serializer(), "i" to gameId).map { it.results }

    /** API_GetGameList for a console (i); f=1 keeps games with achievements, h=1 adds hashes. */
    suspend fun gameList(
        consoleId: Int,
        onlyWithAchievements: Boolean = true,
        withHashes: Boolean = true,
    ): ApiResult<List<RaGameListEntry>> = get(
        "GetGameList",
        RaGameList,
        "i" to consoleId,
        "f" to if (onlyWithAchievements) 1 else 0,
        "h" to if (withHashes) 1 else 0,
    )

    /** API_GetConsoleIDs; a=1 limits to active systems, g=1 to game systems. */
    suspend fun consoleIds(activeOnly: Boolean = true, gameSystemsOnly: Boolean = true): ApiResult<List<RaConsole>> = get(
        "GetConsoleIDs",
        RaConsoleList,
        "a" to if (activeOnly) 1 else 0,
        "g" to if (gameSystemsOnly) 1 else 0,
    )

    private suspend fun <T> get(
        endpoint: String,
        deserializer: DeserializationStrategy<T>,
        vararg params: Pair<String, Any?>,
    ): ApiResult<T> = api.execute {
        url("$baseUrl$API_PREFIX$endpoint.php")
        params.forEach { (name, value) -> if (value != null) parameter(name, value.toString()) }
        parameter("y", credentials.key.reveal())
    }.flatMap { raw -> api.failureFor(raw) ?: embeddedError(raw) ?: api.decode(raw, deserializer) }

    /** RA occasionally answers 200 with {"Error": "..."} instead of a status code. */
    private fun embeddedError(raw: RawResponse): ApiResult.Failure? {
        val body = raw.body.trimStart()
        if (!body.startsWith("{")) return null
        val obj = runCatching { api.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val message = (obj["Error"] ?: obj["error"]) as? JsonPrimitive ?: return null
        if (obj.size > 3 && obj.containsNonErrorPayload()) return null
        val text = api.safe(message.content)
        return if (text.contains("key", ignoreCase = true) || text.contains("auth", ignoreCase = true)) {
            ApiResult.AuthError("RetroAchievements: $text")
        } else {
            ApiResult.HttpError(raw.status, "RetroAchievements: $text")
        }
    }

    private fun JsonObject.containsNonErrorPayload(): Boolean = keys.any { it == "ID" || it == "User" || it == "Results" }

    override fun toString(): String = "RetroAchievementsClient(user=${credentials.username})"

    companion object {
        const val BASE_URL = "https://retroachievements.org/"
        private const val API_PREFIX = "API/API_"
        const val MAX_COMPLETION_PAGE = 500
        const val MAX_RECENTLY_PLAYED = 50

        /** One request at a time, 400 ms apart. */
        fun defaultLimiter(): RateLimiter = RateLimiter(minIntervalMillis = 400, maxConcurrency = 1)
    }
}
