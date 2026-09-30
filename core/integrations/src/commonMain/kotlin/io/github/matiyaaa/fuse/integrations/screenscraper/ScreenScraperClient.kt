package io.github.matiyaaa.fuse.integrations.screenscraper

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.Dates
import io.github.matiyaaa.fuse.integrations.FlexListSerializer
import io.github.matiyaaa.fuse.integrations.FlexString
import io.github.matiyaaa.fuse.integrations.LooseBoolean
import io.github.matiyaaa.fuse.integrations.LooseInt
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.RawResponse
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.UrlCoding
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.integrations.map
import io.github.matiyaaa.fuse.integrations.retryAfterSeconds
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ProviderStatus
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ScreenScraper developer credentials (`devid`, `devpassword`, `softname`). ScreenScraper grants
 * them to free software on request; Fuse does not ship any. Official builds may inject them from CI
 * secrets later. [toString] hides the id and password.
 */
class ScreenScraperDevCredentials(devId: String, devPassword: String, val softName: String) {
    internal val devId = Secret(devId)
    internal val devPassword = Secret(devPassword)

    override fun toString(): String = "ScreenScraperDevCredentials(devId=***, devPassword=***, softName=$softName)"
}

/** The user's own ScreenScraper account (raises quotas and thread count). [toString] hides the password. */
class ScreenScraperUserCredentials(val userId: String, password: String) {
    internal val password = Secret(password)

    override fun toString(): String = "ScreenScraperUserCredentials(userId=$userId, password=***)"
}

/** What identifies a ROM for `jeuInfos.php`. Pass at least one hash, plus size and name when known. */
data class SsRomQuery(
    val crc32: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    val sizeBytes: Long? = null,
    /** ScreenScraper `systemeid`; see [ScreenScraperSystems]. */
    val systemId: Int? = null,
    /** "rom", "iso" or "dossier" (folder). */
    val romType: String = "rom",
    /** File name; any directory part is removed before sending. */
    val fileName: String? = null,
)

@Serializable
data class SsText(val region: String? = null, val langue: String? = null, val text: String = "")

@Serializable
data class SsIdText(@Serializable(with = FlexString::class) val id: String? = null, val text: String = "")

@Serializable
data class SsGenre(
    @Serializable(with = FlexString::class) val id: String? = null,
    @Serializable(with = LooseBoolean::class) val principale: Boolean = false,
    @Serializable(with = SsTextList::class) val noms: List<SsText> = emptyList(),
)

/** One media record. [url] has the credential query parameters removed (see [ScreenScraperClient.authorizeMediaUrl]). */
@Serializable
data class SsMedia(
    val type: String = "",
    val parent: String? = null,
    val url: String = "",
    val region: String? = null,
    val format: String? = null,
)

/** A game from `jeuInfos.php` or `jeuRecherche.php`. */
@Serializable
data class SsGame(
    @Serializable(with = FlexString::class) val id: String? = null,
    @Serializable(with = FlexString::class) val romid: String? = null,
    @Serializable(with = LooseBoolean::class) val notgame: Boolean = false,
    @Serializable(with = SsTextList::class) val noms: List<SsText> = emptyList(),
    val systeme: SsIdText? = null,
    val editeur: SsIdText? = null,
    val developpeur: SsIdText? = null,
    val joueurs: SsIdText? = null,
    val note: SsIdText? = null,
    @Serializable(with = SsTextList::class) val synopsis: List<SsText> = emptyList(),
    @Serializable(with = SsTextList::class) val dates: List<SsText> = emptyList(),
    @Serializable(with = SsGenreList::class) val genres: List<SsGenre> = emptyList(),
    @Serializable(with = SsMediaList::class) val medias: List<SsMedia> = emptyList(),
    /** The ROM ScreenScraper matched (jeuInfos). */
    val rom: SsRom? = null,
    @Serializable(with = SsRomList::class) val roms: List<SsRom> = emptyList(),
) {
    /** True when a matched ROM has the same MD5 or CRC32 as the query, i.e. a checksum identification. */
    fun matchesChecksum(md5: String?, crc32: String?): Boolean = (listOfNotNull(rom) + roms).any { r ->
        (md5 != null && r.rommd5.equals(md5, ignoreCase = true)) ||
            (crc32 != null && r.romcrc.equals(crc32, ignoreCase = true))
    }

    /** The name for the first matching region in [regions], else world, else the first name. */
    fun name(regions: List<String> = emptyList()): String {
        val order = regions.map { it.lowercase() } + listOf("wor", "ss", "us", "eu")
        return order.firstNotNullOfOrNull { r -> noms.firstOrNull { it.region.equals(r, ignoreCase = true) }?.text }
            ?: noms.firstOrNull()?.text.orEmpty()
    }

    /** Earliest release year across regions. */
    val year: Int? get() = dates.mapNotNull { Dates.yearIn(it.text) }.minOrNull()

    /** Metadata in [language] (falling back to English, then anything). */
    fun toMetadata(language: String = "en"): GameMetadata {
        fun pick(list: List<SsText>): String? =
            (list.firstOrNull { it.langue.equals(language, true) } ?: list.firstOrNull { it.langue.equals("en", true) }
                ?: list.firstOrNull())?.text?.takeIf { it.isNotBlank() }
        return GameMetadata(
            description = pick(synopsis),
            releaseYear = year,
            developer = developpeur?.text?.takeIf { it.isNotBlank() },
            publisher = editeur?.text?.takeIf { it.isNotBlank() },
            genres = genres.mapNotNull { pick(it.noms) }.distinct(),
            players = joueurs?.text?.takeIf { it.isNotBlank() },
            // ScreenScraper rates out of 20.
            rating = note?.text?.toIntOrNull()?.let { (it * 5).coerceIn(0, 100) },
            source = MetadataSource.SCREENSCRAPER,
        )
    }

    /** Artwork options, each kind ordered by [regions] preference (then world). */
    fun artwork(regions: List<String> = emptyList()): List<ArtworkOption> {
        val order = regions.map { it.lowercase() } + listOf("wor", "ss", "us", "eu", "jp")
        fun rank(m: SsMedia): Int = order.indexOf(m.region?.lowercase()).let { if (it < 0) order.size else it }
        return medias.mapNotNull { m -> ssMediaKind(m.type)?.let { m to it } }
            .sortedWith(compareBy({ it.second.ordinal }, { rank(it.first) }))
            .map { (m, kind) ->
                ArtworkOption(
                    provider = ScrapeProviderId.SCREENSCRAPER,
                    kind = kind,
                    url = m.url,
                    thumbUrl = null,
                    width = null,
                    height = null,
                    style = if (m.type == "sstitle") "title" else null,
                    animated = kind == MediaKind.VIDEO,
                )
            }
    }
}

/** A ROM record inside a game. */
@Serializable
data class SsRom(
    @Serializable(with = FlexString::class) val romfilename: String? = null,
    @Serializable(with = FlexString::class) val romsize: String? = null,
    @Serializable(with = FlexString::class) val romcrc: String? = null,
    @Serializable(with = FlexString::class) val rommd5: String? = null,
    @Serializable(with = FlexString::class) val romsha1: String? = null,
)

/** ScreenScraper media type -> Fuse media kind; null for types Fuse does not use. */
fun ssMediaKind(type: String): MediaKind? = when (type) {
    "box-2D" -> MediaKind.BOXART
    "ss", "sstitle" -> MediaKind.SCREENSHOT
    "wheel", "wheel-hd" -> MediaKind.LOGO
    "fanart" -> MediaKind.HERO
    "steamgrid" -> MediaKind.GRID
    "video-normalized", "video" -> MediaKind.VIDEO
    else -> null
}

/** Quotas reported in `ssuser`. */
@Serializable
data class SsUser(
    val id: String? = null,
    @Serializable(with = LooseInt::class) val maxthreads: Int = 0,
    @Serializable(with = LooseInt::class) val maxrequestspermin: Int = 0,
    @Serializable(with = LooseInt::class) val requeststoday: Int = 0,
    @Serializable(with = LooseInt::class) val maxrequestsperday: Int = 0,
)

@Serializable
internal data class SsBody(
    val ssuser: SsUser? = null,
    val jeu: SsGame? = null,
    @Serializable(with = SsGameList::class) val jeux: List<SsGame> = emptyList(),
)

@Serializable
internal data class SsEnvelope(val response: SsBody? = null)

internal object SsTextList : FlexListSerializer<SsText>(SsText.serializer())
internal object SsGenreList : FlexListSerializer<SsGenre>(SsGenre.serializer())
internal object SsMediaList : FlexListSerializer<SsMedia>(SsMedia.serializer())
internal object SsGameList : FlexListSerializer<SsGame>(SsGame.serializer())
internal object SsRomList : FlexListSerializer<SsRom>(SsRom.serializer())

/**
 * ScreenScraper API v2 client (`https://api.screenscraper.fr/api2/`). Without developer credentials
 * it makes no request at all and reports [status] "needs ScreenScraper developer credentials".
 * Thread and per-minute limits from `ssuser` adjust the limiter after each answer.
 */
class ScreenScraperClient(
    http: HttpClient,
    private val devCredentials: ScreenScraperDevCredentials?,
    private val userCredentials: ScreenScraperUserCredentials? = null,
    private val baseUrl: String = BASE_URL,
) {
    private val api = ProviderHttp(http, "ScreenScraper", RateLimiter(minIntervalMillis = 1_000, maxConcurrency = 1), {
        listOfNotNull(devCredentials?.devId, devCredentials?.devPassword, userCredentials?.password)
    })

    /** Threads ScreenScraper currently allows this user (1 until an answer says otherwise). */
    var maxThreads: Int = 1
        private set

    val isConfigured: Boolean get() = devCredentials != null

    /** Readiness for Settings -> Media & Scraping. */
    fun status(enabled: Boolean = true): ProviderStatus = ProviderStatus(
        id = ScrapeProviderId.SCREENSCRAPER,
        enabled = enabled,
        configured = isConfigured,
        note = when {
            !isConfigured -> NOTE_NEEDS_DEV
            userCredentials == null -> "Works without an account; a ScreenScraper account raises quotas"
            else -> null
        },
    )

    /** `jeuInfos.php`: identifies a ROM by hash/size/name. Success(null) when it is unknown. */
    suspend fun gameInfo(rom: SsRomQuery): ApiResult<SsGame?> = call("jeuInfos.php") {
        rom.crc32?.let { parameter("crc", it.uppercase()) }
        rom.md5?.let { parameter("md5", it.lowercase()) }
        rom.sha1?.let { parameter("sha1", it.lowercase()) }
        rom.sizeBytes?.let { parameter("romtaille", it) }
        rom.systemId?.let { parameter("systemeid", it) }
        parameter("romtype", rom.romType)
        rom.fileName?.let { parameter("romnom", it.substringAfterLast('/').substringAfterLast('\\')) }
    }.map { body -> body?.jeu?.takeIf { !it.id.isNullOrBlank() } }

    /** `jeuRecherche.php`: title search, optionally within one system. */
    suspend fun search(name: String, systemId: Int? = null): ApiResult<List<SsGame>> = call("jeuRecherche.php") {
        parameter("recherche", name.trim())
        systemId?.let { parameter("systemeid", it) }
    }.map { body -> body?.jeux.orEmpty().filter { !it.id.isNullOrBlank() } }

    /**
     * Media URLs are stored without credentials. Call this right before downloading one to put the
     * developer and user credentials back.
     */
    fun authorizeMediaUrl(storedUrl: String): String {
        val dev = devCredentials ?: return storedUrl
        val extra = buildList {
            add("devid" to dev.devId.reveal())
            add("devpassword" to dev.devPassword.reveal())
            add("softname" to dev.softName)
            userCredentials?.let {
                add("ssid" to it.userId)
                add("sspassword" to it.password.reveal())
            }
        }.joinToString("&") { (k, v) -> "$k=${UrlCoding.encode(v)}" }
        return storedUrl + (if ('?' in storedUrl) "&" else "?") + extra
    }

    private suspend fun call(endpoint: String, params: HttpRequestBuilder.() -> Unit): ApiResult<SsBody?> {
        val dev = devCredentials ?: return ApiResult.NotConfigured(NOTE_NEEDS_DEV)
        return api.execute {
            url("$baseUrl$endpoint")
            parameter("devid", dev.devId.reveal())
            parameter("devpassword", dev.devPassword.reveal())
            parameter("softname", dev.softName)
            parameter("output", "json")
            userCredentials?.let {
                parameter("ssid", it.userId)
                parameter("sspassword", it.password.reveal())
            }
            params()
        }.flatMap { raw ->
            if (raw.status == 404) return@flatMap ApiResult.Success(null)
            mapError(raw) ?: api.decode(raw, SsEnvelope.serializer()).map { env ->
                env.response?.let { body -> body.copy(jeu = body.jeu?.withoutCredentials(), jeux = body.jeux.map { it.withoutCredentials() }) }
                    ?.also { body -> body.ssuser?.let(::adoptQuota) }
            }
        }
    }

    private fun adoptQuota(user: SsUser) {
        val threads = user.maxthreads.coerceIn(1, 16)
        val interval = if (user.maxrequestspermin > 0) (60_000L + user.maxrequestspermin - 1) / user.maxrequestspermin else 1_000L
        val current = api.limiter
        if (current == null || current.maxConcurrency != threads || current.minIntervalMillis != interval) {
            maxThreads = threads
            api.limiter = RateLimiter(minIntervalMillis = interval, maxConcurrency = threads)
        }
    }

    /** ScreenScraper's documented status codes. */
    private fun mapError(raw: RawResponse): ApiResult.Failure? = when (raw.status) {
        in 200..299 -> null
        400 -> ApiResult.HttpError(400, "ScreenScraper rejected the request as malformed")
        401 -> ApiResult.AuthError("ScreenScraper is closed to non-members right now; add a ScreenScraper account")
        403 -> ApiResult.AuthError("ScreenScraper rejected the developer or user login")
        423 -> ApiResult.HttpError(423, "ScreenScraper's API is closed at the moment")
        426 -> ApiResult.HttpError(426, "ScreenScraper blocked this Fuse version; update Fuse")
        429 -> ApiResult.RateLimited(retryAfterSeconds(raw.headers), "ScreenScraper thread limit reached")
        430 -> ApiResult.RateLimited(null, "ScreenScraper daily request quota used up")
        431 -> ApiResult.RateLimited(null, "ScreenScraper daily quota of unrecognised ROMs used up")
        else -> api.failureFor(raw)
    }

    override fun toString(): String = "ScreenScraperClient(configured=$isConfigured)"

    companion object {
        const val BASE_URL = "https://api.screenscraper.fr/api2/"
        const val NOTE_NEEDS_DEV = "needs ScreenScraper developer credentials"

        private val credentialParams = setOf("devid", "devpassword", "softname", "ssid", "sspassword")

        /** Removes credential query parameters from a ScreenScraper media URL. */
        fun stripCredentials(url: String): String {
            val q = url.indexOf('?')
            if (q < 0) return url
            val kept = url.substring(q + 1).split('&').filter { part ->
                part.isNotEmpty() && part.substringBefore('=').lowercase() !in credentialParams
            }
            return url.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }

        private fun SsGame.withoutCredentials(): SsGame = copy(medias = medias.map { it.copy(url = stripCredentials(it.url)) })
    }
}

/**
 * ScreenScraper `systemeid` values for Fuse platform ids. Only well-known ids; the authoritative
 * list is ScreenScraper's systemesListe.php.
 */
object ScreenScraperSystems {
    private val ids: Map<String, Int> = mapOf(
        "genesis" to 1, "sms" to 2, "nes" to 3, "snes" to 4, "gb" to 9, "gbc" to 10, "gba" to 12, "ngc" to 13,
        "n64" to 14, "nds" to 15, "wii" to 16, "sega32" to 19, "segacd" to 20, "gamegear" to 21, "saturn" to 22,
        "dc" to 23, "atari2600" to 26, "jaguar" to 27, "lynx" to 28, "tg16" to 31, "atari7800" to 41,
        "wonderswan" to 45, "wonderswan-color" to 46, "psx" to 57, "ps2" to 58, "psp" to 61, "arcade" to 75,
    )

    fun idFor(platform: PlatformId): Int? = ids[platform.value]
}
