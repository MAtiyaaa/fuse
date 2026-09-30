package io.github.matiyaaa.fuse.integrations.igdb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.FlexLong
import io.github.matiyaaa.fuse.integrations.LooseBoolean
import io.github.matiyaaa.fuse.integrations.LooseLong
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.model.PlatformId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The user's own Twitch application credentials for IGDB. Twitch's developer agreement forbids
 * shipping a client secret inside a public app, so Fuse never bundles one: each user registers an
 * application at dev.twitch.tv and pastes the Client ID and Secret. [toString] hides the secret.
 */
class IgdbCredentials(val clientId: String, clientSecret: String) {
    internal val secret = Secret(clientSecret)

    override fun toString(): String = "IgdbCredentials(clientId=$clientId, clientSecret=***)"
    override fun equals(other: Any?): Boolean = other is IgdbCredentials && other.clientId == clientId && other.secret == secret
    override fun hashCode(): Int = clientId.hashCode() * 31 + secret.hashCode()
}

@Serializable
data class IgdbImage(@SerialName("image_id") val imageId: String = "")

@Serializable
data class IgdbNamed(val name: String = "")

@Serializable
data class IgdbPlatform(val name: String? = null, val abbreviation: String? = null)

@Serializable
data class IgdbInvolvedCompany(
    val company: IgdbNamed? = null,
    @Serializable(with = LooseBoolean::class) val developer: Boolean = false,
    @Serializable(with = LooseBoolean::class) val publisher: Boolean = false,
)

/** An IGDB game with the fields [IgdbQuery.GAME_FIELDS] asks for. [firstReleaseDate] is epoch seconds. */
@Serializable
data class IgdbGame(
    @Serializable(with = LooseLong::class) val id: Long = 0,
    val name: String = "",
    @SerialName("first_release_date") @Serializable(with = FlexLong::class) val firstReleaseDate: Long? = null,
    val summary: String? = null,
    val genres: List<IgdbNamed> = emptyList(),
    @SerialName("involved_companies") val involvedCompanies: List<IgdbInvolvedCompany> = emptyList(),
    val cover: IgdbImage? = null,
    val artworks: List<IgdbImage> = emptyList(),
    val screenshots: List<IgdbImage> = emptyList(),
    val platforms: List<IgdbPlatform> = emptyList(),
    @SerialName("game_type") @Serializable(with = FlexLong::class) val gameType: Long? = null,
) {
    val developers: List<String> get() = involvedCompanies.filter { it.developer }.mapNotNull { it.company?.name }
    val publishers: List<String> get() = involvedCompanies.filter { it.publisher }.mapNotNull { it.company?.name }
}

@Serializable
internal data class TwitchToken(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") @Serializable(with = LooseLong::class) val expiresIn: Long = 0,
    @SerialName("token_type") val tokenType: String? = null,
)

/** Apicalypse query building for `/v4/games`. */
object IgdbQuery {
    const val GAME_FIELDS = "name,first_release_date,summary,genres.name," +
        "involved_companies.company.name,involved_companies.developer,involved_companies.publisher," +
        "cover.image_id,artworks.image_id,screenshots.image_id,platforms.abbreviation,platforms.name,game_type"

    /** Escapes a value for use inside an Apicalypse string literal. */
    fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** `search "<title>"; fields ...; where version_parent = null [& platforms = (..)]; limit n;` */
    fun search(title: String, platformIds: List<Int> = emptyList(), limit: Int = 10): String = buildString {
        append("search \"").append(escape(title.trim())).append("\"; ")
        append("fields ").append(GAME_FIELDS).append("; ")
        append("where version_parent = null")
        if (platformIds.isNotEmpty()) append(" & platforms = (").append(platformIds.joinToString(",")).append(")")
        append("; limit ").append(limit.coerceIn(1, 500)).append(";")
    }

    /** Looks up one game by IGDB id. */
    fun byId(id: Long): String = "fields $GAME_FIELDS; where id = $id; limit 1;"
}

/** IGDB image sizes (`t_{size}`); [retina] appends `_2x`. */
enum class IgdbImageSize(val apiValue: String) {
    COVER_SMALL("cover_small"),
    COVER_BIG("cover_big"),
    SCREENSHOT_MED("screenshot_med"),
    SCREENSHOT_BIG("screenshot_big"),
    SCREENSHOT_HUGE("screenshot_huge"),
    LOGO_MED("logo_med"),
    THUMB("thumb"),
    P720("720p"),
    P1080("1080p"),
}

object IgdbImages {
    /** `https://images.igdb.com/igdb/image/upload/t_{size}/{image_id}.jpg`. */
    fun url(imageId: String, size: IgdbImageSize, retina: Boolean = false): String =
        "https://images.igdb.com/igdb/image/upload/t_${size.apiValue}${if (retina) "_2x" else ""}/$imageId.jpg"
}

/**
 * IGDB platform ids for Fuse platform ids (RomM slugs). Only well-known ids are listed; for others
 * search without a platform filter and let the matcher compare platform names.
 */
object IgdbPlatforms {
    private val ids: Map<String, Int> = mapOf(
        "n64" to 4, "wii" to 5, "win" to 6, "psx" to 7, "ps2" to 8, "ps3" to 9, "xbox" to 11, "xbox360" to 12,
        "dos" to 13, "nes" to 18, "snes" to 19, "nds" to 20, "ngc" to 21, "gbc" to 22, "dc" to 23, "gba" to 24,
        "genesis" to 29, "sega32" to 30, "saturn" to 32, "gb" to 33, "gamegear" to 35, "3ds" to 37, "psp" to 38,
        "wiiu" to 41, "psvita" to 46, "arcade" to 52, "atari2600" to 59, "atari7800" to 60, "lynx" to 61,
        "jaguar" to 62, "sms" to 64, "segacd" to 78, "tg16" to 86, "virtualboy" to 87, "switch" to 130,
    )

    fun idFor(platform: PlatformId): Int? = ids[platform.value]
}

/**
 * IGDB v4 client. Gets a Twitch app access token with the client-credentials grant, keeps it until
 * shortly before it expires, and retries once with a fresh token when IGDB answers 401. Requests
 * are paced to IGDB's 4 per second.
 */
class IgdbClient(
    http: HttpClient,
    private val credentials: IgdbCredentials,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 250, maxConcurrency = 4),
    private val nowMillis: () -> Long = RateLimiter.monotonicClock(),
    private val tokenUrl: String = TOKEN_URL,
    private val apiUrl: String = API_URL,
) {
    constructor(http: HttpClient, clientId: String, clientSecret: String) :
        this(http, IgdbCredentials(clientId, clientSecret))

    private var token: Secret? = null
    private var tokenExpiresAt = 0L
    private val tokenLock = Mutex()
    private val api = ProviderHttp(http, "IGDB", limiter, { listOfNotNull(credentials.secret, token) })
    private val twitch = ProviderHttp(http, "Twitch", null, { listOfNotNull(credentials.secret, token) })

    /** Obtains a token; AuthError means the Client ID or Secret is wrong. */
    suspend fun verifyCredentials(): ApiResult<Unit> = accessToken(forceRefresh = true).flatMap { ApiResult.Success(Unit) }

    /** Searches main games (no versions) by title, optionally limited to IGDB [platformIds]. */
    suspend fun searchGames(title: String, platformIds: List<Int> = emptyList(), limit: Int = 10): ApiResult<List<IgdbGame>> =
        query("games", IgdbQuery.search(title, platformIds, limit))

    /** One game by IGDB id, or Success(null). */
    suspend fun gameById(id: Long): ApiResult<IgdbGame?> =
        query("games", IgdbQuery.byId(id)).flatMap { ApiResult.Success(it.firstOrNull()) }

    /** Runs a raw Apicalypse [body] against `/v4/{endpoint}` returning games. */
    suspend fun query(endpoint: String, body: String): ApiResult<List<IgdbGame>> {
        var retried = false
        while (true) {
            val bearer = when (val t = accessToken(forceRefresh = false)) {
                is ApiResult.Success -> t.value
                is ApiResult.Failure -> return t
            }
            val result = api.execute {
                method = HttpMethod.Post
                url("$apiUrl/$endpoint")
                header("Client-ID", credentials.clientId)
                bearerAuth(bearer.reveal())
                contentType(ContentType.Text.Plain)
                setBody(body)
            }
            val raw = when (result) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return result
            }
            if (raw.status == 401 && !retried) {
                retried = true
                invalidateToken(bearer)
                continue
            }
            return api.failureFor(raw) ?: api.decode(raw, ListSerializer(IgdbGame.serializer()))
        }
    }

    private suspend fun invalidateToken(stale: Secret) = tokenLock.withLock {
        if (token == stale) {
            token = null
            tokenExpiresAt = 0
        }
    }

    private suspend fun accessToken(forceRefresh: Boolean): ApiResult<Secret> = tokenLock.withLock {
        val current = token
        if (!forceRefresh && current != null && nowMillis() < tokenExpiresAt) return@withLock ApiResult.Success(current)
        if (credentials.clientId.isBlank() || credentials.secret.isBlank) {
            return@withLock ApiResult.NotConfigured("IGDB needs your own Twitch Client ID and Client Secret")
        }
        val result = twitch.execute {
            method = HttpMethod.Post
            url(tokenUrl)
            parameter("client_id", credentials.clientId)
            parameter("client_secret", credentials.secret.reveal())
            parameter("grant_type", "client_credentials")
        }.flatMap { raw ->
            when {
                raw.status == 400 || raw.status == 401 || raw.status == 403 ->
                    ApiResult.AuthError("Twitch rejected the IGDB Client ID or Secret (HTTP ${raw.status})")
                else -> twitch.failureFor(raw) ?: twitch.decode(raw, TwitchToken.serializer())
            }
        }
        when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val t = result.value
                if (t.accessToken.isBlank()) return@withLock ApiResult.InvalidResponse("Twitch returned no access token")
                val secret = Secret(t.accessToken)
                token = secret
                // Refresh a minute early so a request never races the expiry.
                tokenExpiresAt = nowMillis() + (t.expiresIn - 60).coerceAtLeast(0) * 1000
                ApiResult.Success(secret)
            }
        }
    }

    override fun toString(): String = "IgdbClient(clientId=${credentials.clientId})"

    companion object {
        const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        const val API_URL = "https://api.igdb.com/v4"
    }
}
