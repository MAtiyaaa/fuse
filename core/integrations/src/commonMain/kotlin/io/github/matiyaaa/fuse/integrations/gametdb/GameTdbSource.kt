package io.github.matiyaaa.fuse.integrations.gametdb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.map
import io.github.matiyaaa.fuse.integrations.match.PlatformEvidence
import io.github.matiyaaa.fuse.integrations.scrape.ProviderGame
import io.github.matiyaaa.fuse.integrations.scrape.ScrapeSource
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * GameTDB permits artwork use in software with attribution. Only identified GC/Wii disc IDs
 * are queried; no website scraping or title-search database is involved. See Main/FAQ on GameTDB.
 * Images retain their original owners' copyright and are downloaded, never bundled.
 */
class GameTdbSource(
    http: HttpClient,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 500, maxConcurrency = 1),
    private val baseUrl: String = "https://art.gametdb.com",
) : ScrapeSource {
    override val id = ScrapeProviderId.GAMETDB
    override val providesMetadata = false
    override val artworkKinds = setOf(MediaKind.BOXART)
    override val searchesByKeyword = false
    private val api = ProviderHttp(http, "GameTDB", limiter, { emptyList() })
    private val probes = Mutex()
    private val cached = LinkedHashMap<String, Pair<Long, String?>>()

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> {
        val serial = discId(query) ?: return ApiResult.Success(emptyList())
        return lookup(serial, query).map { listOfNotNull(it) }
    }

    override suspend fun byId(id: String, query: ScrapeQuery): ApiResult<ProviderGame?> {
        if (!validId(id, query)) return ApiResult.Success(null)
        return lookup(id, query)
    }

    private suspend fun lookup(serial: String, query: ScrapeQuery): ApiResult<ProviderGame?> {
        // A region is part of the disc identity. Never substitute artwork from a different title ID.
        val region = when (serial[3]) {
            'E' -> "US"
            'J' -> "JA"
            'K' -> "KO"
            else -> query.preferredLanguage.uppercase().takeIf { it in palLanguages } ?: "EN"
        }
        val image = "$baseUrl/wii/cover/$region/$serial.png"
        return probe(image).map { found -> found?.let {
            ProviderGame(
                provider = id, providerGameId = serial, title = query.title,
                platformEvidence = PlatformEvidence.MATCH, previewUrl = it,
                artwork = listOf(ArtworkOption(id, MediaKind.BOXART, it, it, null, null)),
            )
        } }
    }

    // Name aliases in one scrape plan still mean one disc-ID/region request. Serialising the
    // cache lookup with its probe coalesces simultaneous callers without caching failures.
    private suspend fun probe(image: String): ApiResult<String?> = probes.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        cached[image]?.takeIf { it.first > now }?.let { return@withLock ApiResult.Success(it.second) }
        val result: ApiResult<String?> = when (val response = api.execute { method = HttpMethod.Head; url(image) }) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> when {
                response.value.isSuccess -> ApiResult.Success(image)
                response.value.status == 404 -> ApiResult.Success(null)
                else -> api.failureFor(response.value) ?: ApiResult.Success(null)
            }
        }
        if (result is ApiResult.Success) {
            cached.remove(image)
            cached[image] = (now + if (result.value == null) 5 * 60_000L else 6 * 60 * 60_000L) to result.value
            while (cached.size > 512) cached.remove(cached.keys.first())
        }
        result
    }

    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>): ApiResult<List<ArtworkOption>> =
        ApiResult.Success(game.artwork.filter { it.kind in kinds })

    companion object {
        const val ATTRIBUTION_URL = "https://www.gametdb.com/"
        private val disc = Regex("^[GRS][A-Z0-9]{2}[EJPDFISKUXYZWA][A-Z0-9]{2}$")
        private val bracketId = Regex("\\[([GRS][A-Z0-9]{5})]")
        private val leadingId = Regex("^([GRS][A-Z0-9]{5})(?:\\s+-\\s+|\\s+)")
        private val palLanguages = setOf("EN", "FR", "DE", "ES", "IT", "NL", "PT")

        fun validId(serial: String, query: ScrapeQuery): Boolean = disc.matches(serial) && when (query.platform.value) {
            "ngc" -> serial.startsWith('G')
            "wii" -> serial.startsWith('R') || serial.startsWith('S')
            else -> false
        }

        /** Explicit scanner serial or labelled disc ID only; never arbitrary six-letter title words. */
        fun discId(query: ScrapeQuery): String? {
            val serial = query.serial?.uppercase()
            if (serial != null && validId(serial, query)) return serial
            val name = query.fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
            val candidates = bracketId.findAll(name).map { it.groupValues[1] }.toList() +
                listOfNotNull(leadingId.find(name)?.groupValues?.get(1))
            return candidates.filter { validId(it, query) }.distinct().singleOrNull()
        }
    }
}
