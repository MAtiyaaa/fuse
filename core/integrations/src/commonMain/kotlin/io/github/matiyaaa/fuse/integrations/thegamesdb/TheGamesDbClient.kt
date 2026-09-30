package io.github.matiyaaa.fuse.integrations.thegamesdb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.FlexLong
import io.github.matiyaaa.fuse.integrations.FlexString
import io.github.matiyaaa.fuse.integrations.LooseInt
import io.github.matiyaaa.fuse.integrations.LooseLong
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.RawResponse
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.integrations.flexLong
import io.github.matiyaaa.fuse.integrations.flexString
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** TheGamesDB image types for `/v1/Games/Images` `filter[type]`. */
enum class TgdbImageType(val apiValue: String) {
    FANART("fanart"),
    BANNER("banner"),
    BOXART("boxart"),
    SCREENSHOT("screenshot"),
    CLEARLOGO("clearlogo"),
    TITLESCREEN("titlescreen"),
}

/** TheGamesDB's `base_url` object: prefixes for each image size. */
@Serializable
data class TgdbBaseUrl(
    val original: String? = null,
    val small: String? = null,
    val thumb: String? = null,
    @SerialName("cropped_center_thumb") val croppedCenterThumb: String? = null,
    val medium: String? = null,
    val large: String? = null,
)

/** One image record. [filename] is relative to a [TgdbBaseUrl] prefix. */
@Serializable
data class TgdbImage(
    @Serializable(with = LooseLong::class) val id: Long = 0,
    val type: String = "",
    val side: String? = null,
    val filename: String = "",
    val resolution: String? = null,
)

/** A game from `/v1.1/Games/ByGameName`. [platform] is TheGamesDB's platform id. */
@Serializable
data class TgdbGame(
    @Serializable(with = LooseLong::class) val id: Long = 0,
    @SerialName("game_title") val gameTitle: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    @Serializable(with = LooseInt::class) val platform: Int = 0,
    @SerialName("region_id") @Serializable(with = FlexLong::class) val regionId: Long? = null,
    val overview: String? = null,
    @Serializable(with = FlexString::class) val players: String? = null,
    val rating: String? = null,
    val developers: List<Long> = emptyList(),
    val publishers: List<Long> = emptyList(),
    val genres: List<Long> = emptyList(),
)

/** Monthly allowance TheGamesDB reports with every response. */
data class TgdbAllowance(
    val remainingMonthly: Long?,
    val extra: Long?,
    /** Seconds until the monthly allowance refreshes. */
    val refreshInSeconds: Long?,
)

/** Result of a game search, with the boxart included by `include=boxart,platform`. */
data class TgdbSearchResult(
    val games: List<TgdbGame>,
    /** Platform names by TheGamesDB platform id. */
    val platformNames: Map<Int, String>,
    val boxartBaseUrl: TgdbBaseUrl?,
    /** Boxart images by game id. */
    val boxart: Map<Long, List<TgdbImage>>,
    val allowance: TgdbAllowance,
) {
    /** Front box art options for [gameId]. */
    fun boxartFor(gameId: Long): List<ArtworkOption> =
        boxart[gameId].orEmpty().mapNotNull { it.toArtworkOption(boxartBaseUrl) }
}

/** Images of one or more games. */
data class TgdbImagesResult(
    val baseUrl: TgdbBaseUrl?,
    val images: Map<Long, List<TgdbImage>>,
    val allowance: TgdbAllowance,
) {
    /** Artwork options for [gameId] (unmapped types are skipped). */
    fun artworkFor(gameId: Long): List<ArtworkOption> = images[gameId].orEmpty().mapNotNull { it.toArtworkOption(baseUrl) }
}

/**
 * TheGamesDB API client (`https://api.thegamesdb.net`, key in the `apikey` parameter). Public keys
 * have a small monthly allowance per IP, so Fuse asks each user for their own key and surfaces the
 * remaining allowance ([TgdbAllowance]) that every response reports.
 */
class TheGamesDbClient(
    http: HttpClient,
    apiKey: String,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 250, maxConcurrency = 2),
    private val baseUrl: String = BASE_URL,
) {
    private val key = Secret(apiKey)
    private val api = ProviderHttp(http, "TheGamesDB", limiter, { listOf(key) })

    /** Checks the key with a real search; AuthError means it was rejected. */
    suspend fun verifyKey(): ApiResult<TgdbAllowance> = searchByName("Tetris").flatMap { ApiResult.Success(it.allowance) }

    /** `/v1.1/Games/ByGameName` with overview/players/publishers/genres and included boxart + platform. */
    suspend fun searchByName(name: String, platformIds: List<Int> = emptyList(), page: Int? = null): ApiResult<TgdbSearchResult> =
        request("v1.1/Games/ByGameName") {
            parameter("name", name.trim())
            parameter("fields", "players,publishers,genres,overview,rating")
            parameter("include", "boxart,platform")
            if (platformIds.isNotEmpty()) parameter("filter[platform]", platformIds.joinToString(","))
            if (page != null) parameter("page", page)
        }.flatMap { root -> parse { parseSearch(root) } }

    /** `/v1/Games/Images` for [gameIds], limited to [types] (all types when empty). */
    suspend fun images(gameIds: List<Long>, types: Set<TgdbImageType> = emptySet()): ApiResult<TgdbImagesResult> =
        request("v1/Games/Images") {
            parameter("games_id", gameIds.joinToString(","))
            if (types.isNotEmpty()) parameter("filter[type]", types.joinToString(",") { it.apiValue })
        }.flatMap { root -> parse { parseImages(root) } }

    private suspend fun request(path: String, params: io.ktor.client.request.HttpRequestBuilder.() -> Unit): ApiResult<JsonObject> =
        api.execute {
            url("$baseUrl/$path")
            parameter("apikey", key.reveal())
            params()
        }.flatMap { raw -> failureFor(raw) ?: parse { api.json.parseToJsonElement(raw.body).jsonObject } }

    private fun failureFor(raw: RawResponse): ApiResult.Failure? {
        if (raw.isSuccess) return null
        // An exhausted allowance is reported as 403 with a message about the allowance.
        if (raw.status == 403 && raw.body.contains("allowance", ignoreCase = true)) {
            return ApiResult.RateLimited(null, "TheGamesDB monthly allowance is used up")
        }
        return api.failureFor(raw)
    }

    private inline fun <T> parse(block: () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: IllegalArgumentException) {
        ApiResult.InvalidResponse(api.safe("TheGamesDB sent an unreadable response: ${e.message?.take(200)}"))
    }

    private fun parseSearch(root: JsonObject): TgdbSearchResult {
        val json = api.json
        val data = root["data"] as? JsonObject
        val games = (data?.get("games"))?.let { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(TgdbGame.serializer()), it) }
            .orEmpty()
        val include = root["include"] as? JsonObject
        val boxart = include?.get("boxart") as? JsonObject
        val platform = include?.get("platform") as? JsonObject
        val platformEntries = (platform?.get("data") as? JsonObject) ?: platform
        val platformNames = platformEntries?.entries?.mapNotNull { (k, v) ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            val name = (v as? JsonObject)?.get("name").flexString() ?: return@mapNotNull null
            id to name
        }?.toMap().orEmpty()
        return TgdbSearchResult(
            games = games,
            platformNames = platformNames,
            boxartBaseUrl = boxart?.get("base_url")?.let { json.decodeFromJsonElement(TgdbBaseUrl.serializer(), it) },
            boxart = imagesById(boxart?.get("data")),
            allowance = allowance(root),
        )
    }

    private fun parseImages(root: JsonObject): TgdbImagesResult {
        val data = root["data"] as? JsonObject
        return TgdbImagesResult(
            baseUrl = data?.get("base_url")?.let { api.json.decodeFromJsonElement(TgdbBaseUrl.serializer(), it) },
            images = imagesById(data?.get("images")),
            allowance = allowance(root),
        )
    }

    private fun imagesById(element: JsonElement?): Map<Long, List<TgdbImage>> {
        val obj = element as? JsonObject ?: return emptyMap()
        val list = kotlinx.serialization.builtins.ListSerializer(TgdbImage.serializer())
        return obj.entries.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            id to runCatching { api.json.decodeFromJsonElement(list, v) }.getOrDefault(emptyList())
        }.toMap()
    }

    private fun allowance(root: JsonObject) = TgdbAllowance(
        remainingMonthly = root["remaining_monthly_allowance"].flexLong(),
        extra = root["extra_allowance"].flexLong(),
        refreshInSeconds = root["allowance_refresh_timer"].flexLong(),
    )

    override fun toString(): String = "TheGamesDbClient(apiKey=***)"

    companion object {
        const val BASE_URL = "https://api.thegamesdb.net"
    }
}

/**
 * TheGamesDB platform ids for Fuse platform ids. Only well-known ids; when a platform is missing,
 * search without the filter and let the matcher compare platform names.
 */
object TgdbPlatforms {
    private val ids: Map<String, Int> = mapOf(
        "win" to 1, "ngc" to 2, "n64" to 3, "gb" to 4, "gba" to 5, "snes" to 6, "nes" to 7, "nds" to 8,
        "wii" to 9, "psx" to 10, "ps2" to 11, "ps3" to 12, "psp" to 13, "xbox" to 14, "xbox360" to 15,
        "dc" to 16, "saturn" to 17, "genesis" to 18, "gamegear" to 20, "segacd" to 21, "atari2600" to 22,
        "arcade" to 23, "atari7800" to 27, "jaguar" to 28, "sega32" to 33, "tg16" to 34, "sms" to 35,
        "wiiu" to 38, "psvita" to 39, "gbc" to 41,
    )

    fun idFor(platform: PlatformId): Int? = ids[platform.value]
}

/** Pixel size parsed from "1000x1400". */
private fun String?.resolution(): Pair<Int, Int>? {
    val parts = this?.lowercase()?.split('x') ?: return null
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    return w to h
}

/** The media kind of a TheGamesDB image type; back covers are not offered. */
fun TgdbImage.mediaKind(): MediaKind? = when (type.lowercase()) {
    "boxart" -> if (side == null || side.equals("front", ignoreCase = true)) MediaKind.BOXART else null
    "fanart" -> MediaKind.HERO
    "banner" -> MediaKind.GRID
    "screenshot", "titlescreen" -> MediaKind.SCREENSHOT
    "clearlogo" -> MediaKind.LOGO
    else -> null
}

/**
 * Maps an image record using the response's own [baseUrl] (TheGamesDB may move its CDN, so the
 * prefix is never hard-coded). Null when the type is not used by Fuse or no base URL was sent.
 */
fun TgdbImage.toArtworkOption(baseUrl: TgdbBaseUrl?): ArtworkOption? {
    val kind = mediaKind() ?: return null
    val original = baseUrl?.original ?: baseUrl?.large ?: return null
    val size = resolution.resolution()
    return ArtworkOption(
        provider = ScrapeProviderId.THEGAMESDB,
        kind = kind,
        url = joinUrl(original, filename),
        thumbUrl = (baseUrl?.thumb ?: baseUrl?.small)?.let { joinUrl(it, filename) },
        width = size?.first,
        height = size?.second,
        style = if (type == "titlescreen" || type == "banner") type else null,
    )
}

private fun joinUrl(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')
