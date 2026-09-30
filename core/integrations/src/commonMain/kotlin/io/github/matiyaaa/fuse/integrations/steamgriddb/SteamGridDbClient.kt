package io.github.matiyaaa.fuse.integrations.steamgriddb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.FlexLong
import io.github.matiyaaa.fuse.integrations.LooseBoolean
import io.github.matiyaaa.fuse.integrations.LooseInt
import io.github.matiyaaa.fuse.integrations.LooseLong
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.UrlCoding
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.integrations.map
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The four SteamGridDB asset collections. */
enum class SgdbAssetType(val path: String) { GRID("grids"), HERO("heroes"), LOGO("logos"), ICON("icons") }

/** SteamGridDB styles; [appliesTo] lists the asset types that accept each one. */
enum class SgdbStyle(val apiValue: String, val appliesTo: Set<SgdbAssetType>) {
    ALTERNATE("alternate", setOf(SgdbAssetType.GRID, SgdbAssetType.HERO)),
    BLURRED("blurred", setOf(SgdbAssetType.GRID, SgdbAssetType.HERO)),
    WHITE_LOGO("white_logo", setOf(SgdbAssetType.GRID)),
    MATERIAL("material", setOf(SgdbAssetType.GRID, SgdbAssetType.HERO)),
    NO_LOGO("no_logo", setOf(SgdbAssetType.GRID)),
    OFFICIAL("official", setOf(SgdbAssetType.LOGO, SgdbAssetType.ICON)),
    WHITE("white", setOf(SgdbAssetType.LOGO)),
    BLACK("black", setOf(SgdbAssetType.LOGO)),
    CUSTOM("custom", setOf(SgdbAssetType.LOGO, SgdbAssetType.ICON)),
}

/** Accepted image types. */
enum class SgdbMime(val apiValue: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    WEBP("image/webp"),
    ICO("image/vnd.microsoft.icon"),
}

/** Static or animated assets. */
enum class SgdbAnimation(val apiValue: String) { STATIC("static"), ANIMATED("animated") }

/** Tri-state content filter for nsfw/humor/epilepsy. SteamGridDB's default is [EXCLUDE]. */
enum class SgdbContentFilter(val apiValue: String) { EXCLUDE("false"), ONLY("true"), ANY("any") }

/** Width x height as SteamGridDB writes it ("600x900"). */
data class SgdbDimension(val width: Int, val height: Int) {
    val apiValue: String get() = "${width}x$height"

    val isPortraitCapsule: Boolean get() = this in PORTRAIT

    companion object {
        val GRID_460x215 = SgdbDimension(460, 215)
        val GRID_920x430 = SgdbDimension(920, 430)
        val GRID_600x900 = SgdbDimension(600, 900)
        val GRID_342x482 = SgdbDimension(342, 482)
        val GRID_660x930 = SgdbDimension(660, 930)
        val GRID_512x512 = SgdbDimension(512, 512)
        val GRID_1024x1024 = SgdbDimension(1024, 1024)
        val HERO_1920x620 = SgdbDimension(1920, 620)
        val HERO_3840x1240 = SgdbDimension(3840, 1240)
        val HERO_1600x650 = SgdbDimension(1600, 650)

        /** Grid sizes that are portrait capsules, i.e. box art. */
        val PORTRAIT: Set<SgdbDimension> = setOf(GRID_600x900, GRID_342x482, GRID_660x930)
    }
}

/**
 * Filters for grids/heroes/logos/icons. Styles that do not apply to the requested asset type are
 * dropped. nsfw, humor and epilepsy default to excluded; [limit] is clamped to 1..50.
 */
data class SgdbFilters(
    val styles: Set<SgdbStyle> = emptySet(),
    val dimensions: Set<SgdbDimension> = emptySet(),
    val mimes: Set<SgdbMime> = emptySet(),
    val types: Set<SgdbAnimation> = emptySet(),
    val nsfw: SgdbContentFilter = SgdbContentFilter.EXCLUDE,
    val humor: SgdbContentFilter = SgdbContentFilter.EXCLUDE,
    val epilepsy: SgdbContentFilter = SgdbContentFilter.EXCLUDE,
    val limit: Int = 50,
    val page: Int = 0,
) {
    /** Query parameters for [asset], in a stable order. */
    fun toParameters(asset: SgdbAssetType): List<Pair<String, String>> = buildList {
        val validStyles = styles.filter { asset in it.appliesTo }
        if (validStyles.isNotEmpty()) add("styles" to validStyles.joinToString(",") { it.apiValue })
        if (dimensions.isNotEmpty() && asset != SgdbAssetType.LOGO) {
            add("dimensions" to dimensions.joinToString(",") { it.apiValue })
        }
        if (mimes.isNotEmpty()) add("mimes" to mimes.joinToString(",") { it.apiValue })
        if (types.isNotEmpty()) add("types" to types.joinToString(",") { it.apiValue })
        add("nsfw" to nsfw.apiValue)
        add("humor" to humor.apiValue)
        add("epilepsy" to epilepsy.apiValue)
        add("limit" to limit.coerceIn(1, MAX_LIMIT).toString())
        add("page" to page.coerceAtLeast(0).toString())
    }

    companion object {
        const val MAX_LIMIT = 50
    }
}

/** A SteamGridDB game (search result or lookup). [releaseDate] is epoch seconds. */
@Serializable
data class SgdbGame(
    @Serializable(with = LooseLong::class) val id: Long = 0,
    val name: String = "",
    val types: List<String> = emptyList(),
    @Serializable(with = LooseBoolean::class) val verified: Boolean = false,
    @SerialName("release_date") @Serializable(with = FlexLong::class) val releaseDate: Long? = null,
)

@Serializable
data class SgdbAuthor(val name: String? = null, val steam64: String? = null, val avatar: String? = null)

/** One grid, hero, logo or icon. */
@Serializable
data class SgdbAsset(
    @Serializable(with = LooseLong::class) val id: Long = 0,
    @Serializable(with = LooseInt::class) val score: Int = 0,
    val style: String? = null,
    val url: String = "",
    val thumb: String? = null,
    @Serializable(with = LooseInt::class) val width: Int = 0,
    @Serializable(with = LooseInt::class) val height: Int = 0,
    val mime: String? = null,
    val language: String? = null,
    @Serializable(with = LooseBoolean::class) val nsfw: Boolean = false,
    @Serializable(with = LooseBoolean::class) val humor: Boolean = false,
    @Serializable(with = LooseBoolean::class) val epilepsy: Boolean = false,
    val author: SgdbAuthor? = null,
)

/** One page of assets with SteamGridDB's paging fields. */
@Serializable
data class SgdbAssetPage(
    @Serializable(with = LooseInt::class) val page: Int = 0,
    @Serializable(with = LooseInt::class) val total: Int = 0,
    @Serializable(with = LooseInt::class) val limit: Int = 0,
    val data: List<SgdbAsset> = emptyList(),
)

@Serializable
internal data class SgdbEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val errors: List<String> = emptyList(),
)

/**
 * SteamGridDB API v2 client (`https://www.steamgriddb.com/api/v2`, bearer auth). The key belongs to
 * the user (steamgriddb.com/profile/preferences/api); it is never logged or put into messages.
 */
class SteamGridDbClient(
    http: HttpClient,
    apiKey: String,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 100, maxConcurrency = 4),
    private val baseUrl: String = BASE_URL,
) {
    private val key = Secret(apiKey)
    private val api = ProviderHttp(http, "SteamGridDB", limiter, { listOf(key) })

    /** Checks the key with a real search call; AuthError means the key was rejected. */
    suspend fun verifyKey(): ApiResult<Unit> = searchAutocomplete("fuse").map { }

    /** `/search/autocomplete/{term}`. */
    suspend fun searchAutocomplete(term: String): ApiResult<List<SgdbGame>> =
        get("search/autocomplete/${UrlCoding.encode(term.trim())}", ListSerializer(SgdbGame.serializer()))
            .map { it ?: emptyList() }

    /** `/games/id/{id}`; Success(null) when the game does not exist. */
    suspend fun gameById(gameId: Long): ApiResult<SgdbGame?> = nullOn404(get("games/id/$gameId", SgdbGame.serializer()))

    /** `/games/steam/{appId}`; Success(null) when SteamGridDB has no such app. */
    suspend fun gameBySteamAppId(appId: Long): ApiResult<SgdbGame?> =
        nullOn404(get("games/steam/$appId", SgdbGame.serializer()))

    suspend fun grids(gameId: Long, filters: SgdbFilters = SgdbFilters()): ApiResult<SgdbAssetPage> =
        assets(SgdbAssetType.GRID, gameId, filters)

    suspend fun heroes(gameId: Long, filters: SgdbFilters = SgdbFilters()): ApiResult<SgdbAssetPage> =
        assets(SgdbAssetType.HERO, gameId, filters)

    suspend fun logos(gameId: Long, filters: SgdbFilters = SgdbFilters()): ApiResult<SgdbAssetPage> =
        assets(SgdbAssetType.LOGO, gameId, filters)

    suspend fun icons(gameId: Long, filters: SgdbFilters = SgdbFilters()): ApiResult<SgdbAssetPage> =
        assets(SgdbAssetType.ICON, gameId, filters)

    /** `/{grids|heroes|logos|icons}/game/{gameId}` with [filters]. */
    suspend fun assets(type: SgdbAssetType, gameId: Long, filters: SgdbFilters = SgdbFilters()): ApiResult<SgdbAssetPage> =
        api.execute {
            url("$baseUrl/${type.path}/game/$gameId")
            bearerAuth(key.reveal())
            filters.toParameters(type).forEach { (k, v) -> parameter(k, v) }
        }.flatMap { raw ->
            if (raw.status == 404) return@flatMap ApiResult.Success(SgdbAssetPage())
            api.failureFor(raw) ?: api.decode(raw, SgdbAssetPage.serializer())
        }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): ApiResult<T?> = api.execute {
        url("$baseUrl/$path")
        bearerAuth(key.reveal())
    }.flatMap { raw ->
        api.failureFor(raw) ?: api.decode(raw, SgdbEnvelope.serializer(serializer)).flatMap { env ->
            if (env.success) {
                ApiResult.Success(env.data)
            } else {
                ApiResult.InvalidResponse(api.safe("SteamGridDB: ${env.errors.joinToString("; ").ifBlank { "request failed" }}"))
            }
        }
    }

    private fun <T> nullOn404(result: ApiResult<T?>): ApiResult<T?> =
        if (result is ApiResult.HttpError && result.code == 404) ApiResult.Success(null) else result

    override fun toString(): String = "SteamGridDbClient(apiKey=***)"

    companion object {
        const val BASE_URL = "https://www.steamgriddb.com/api/v2"
    }
}

/** The media kind an asset fills: portrait grids are box art, other grids stay grids. */
fun SgdbAssetType.mediaKind(width: Int, height: Int): MediaKind = when (this) {
    SgdbAssetType.GRID -> if (SgdbDimension(width, height).isPortraitCapsule) MediaKind.BOXART else MediaKind.GRID
    SgdbAssetType.HERO -> MediaKind.HERO
    SgdbAssetType.LOGO -> MediaKind.LOGO
    SgdbAssetType.ICON -> MediaKind.ICON
}

/** Maps an asset to an [ArtworkOption]. [animated] is set when the request asked for animated assets. */
fun SgdbAsset.toArtworkOption(type: SgdbAssetType, animated: Boolean = false): ArtworkOption = ArtworkOption(
    provider = ScrapeProviderId.STEAMGRIDDB,
    kind = type.mediaKind(width, height),
    url = url,
    thumbUrl = thumb,
    width = width.takeIf { it > 0 },
    height = height.takeIf { it > 0 },
    style = style,
    score = score,
    animated = animated || mime == "image/gif",
    author = author?.name,
)
