package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.match.MatchDecision
import io.github.matiyaaa.fuse.integrations.match.ScoredMatch
import io.github.matiyaaa.fuse.integrations.match.TitleMatcher
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.ScrapeCandidate
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One scrape job for one game. */
data class ScrapeRequest(
    val query: ScrapeQuery,
    /** The user's provider order (Settings -> Media & Scraping). LOCAL and ROMM are skipped here. */
    val priority: List<ScrapeProviderId>,
    /** Providers that have the credentials they need. Keyless providers need not be listed. */
    val configured: Set<ScrapeProviderId>,
    val strictness: MatchStrictness = MatchStrictness.NORMAL,
    /** Artwork kinds to collect, normally [FillPlan.fetch]. Empty for a metadata-only job. */
    val artworkKinds: Set<MediaKind> = emptySet(),
    val wantMetadata: Boolean = true,
    /** Keep asking lower-priority providers after every kind has an option (for artwork pickers). */
    val collectAllArtwork: Boolean = false,
    val maxCandidates: Int = 10,
)

/** A provider that failed during a job. */
data class ProviderError(val provider: ScrapeProviderId, val failure: ApiResult.Failure)

/** What a scrape found. Nothing has been written anywhere; the data layer decides what to apply. */
sealed interface ScrapeOutcome {
    val errors: List<ProviderError>

    /** A match safe to apply. [warning] is set when an aggressive accept should be flagged in the UI. */
    data class Accepted(
        val candidate: ScrapeCandidate,
        val metadata: GameMetadata?,
        val artwork: List<ArtworkOption>,
        val warning: String? = null,
        override val errors: List<ProviderError> = emptyList(),
    ) : ScrapeOutcome

    /** No confident match: let the user pick one of [candidates] (best first). */
    data class NeedsReview(
        val candidates: List<ScrapeCandidate>,
        override val errors: List<ProviderError> = emptyList(),
    ) : ScrapeOutcome

    /** Every provider answered, none had the game. */
    data class NotFound(
        val searched: List<ScrapeProviderId>,
        override val errors: List<ProviderError> = emptyList(),
    ) : ScrapeOutcome

    /** Every provider asked failed (offline, keys rejected, quotas). */
    data class ProviderErrors(override val errors: List<ProviderError>) : ScrapeOutcome
}

/**
 * Runs metadata search and artwork listing across providers in the user's order. It only reads
 * from providers and returns data: it never writes to the library or the media store.
 *
 * Identification tries metadata providers first (in priority order), then artwork-only ones. Each
 * provider is searched by the game's title and, while nothing sure turns up, by its other names
 * ([SearchNames]). The first provider whose results the [TitleMatcher] auto-accepts under the
 * request's strictness wins; otherwise every candidate seen is returned for review. Artwork for an
 * accepted game comes from the winning provider and from each other configured provider that also
 * confidently matches the accepted title, until every kind asked for has an option.
 *
 * A provider that runs out of requests (a quota, a monthly allowance, a long rate limit), rejects
 * its key or keeps failing to answer rests for a while: the jobs that follow skip it and the other
 * providers take over, and each skip is reported as that job's error so nothing is remembered as
 * missing because of it. Short rate limits are waited out once.
 */
class ScrapeCoordinator(
    sources: List<ScrapeSource>,
    private val matcher: TitleMatcher = TitleMatcher(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val sources: Map<ScrapeProviderId, ScrapeSource> = sources.associateBy { it.id }

    /** A provider taking a break until [until] (epoch ms), and why. */
    private data class Rest(val until: Long, val reason: String)

    private val lock = Mutex()
    private val rests = HashMap<ScrapeProviderId, Rest>()
    private val failuresInARow = HashMap<ScrapeProviderId, Int>()

    /** Providers resting now, with the reason (safe to show). */
    suspend fun resting(): Map<ScrapeProviderId, String> = lock.withLock {
        val t = now()
        rests.entries.filter { it.value.until > t }.associate { it.key to it.value.reason }
    }

    /** Identifies the game and collects artwork. */
    suspend fun scrape(request: ScrapeRequest): ScrapeOutcome {
        val active = activeSources(request)
        if (active.isEmpty()) return ScrapeOutcome.NotFound(emptyList())
        val ordered = if (request.wantMetadata) {
            active.filter { it.providesMetadata } + active.filterNot { it.providesMetadata }
        } else {
            active
        }
        val errors = ArrayList<ProviderError>()
        val review = ArrayList<ScoredMatch>()
        val searched = ArrayList<ScrapeProviderId>()
        val names = SearchNames.of(request.query)
        for (source in ordered) {
            var answered = false
            for (name in names) {
                val games = when (val r = call(source.id) { source.search(request.query.named(name)) }) {
                    is ApiResult.Failure -> {
                        errors += ProviderError(source.id, r)
                        break
                    }
                    is ApiResult.Success -> r.value.take(request.maxCandidates)
                }
                answered = true
                val ranked = matcher.rank(request.query, games.map { it.toMatchInput() })
                when (val decision = matcher.decide(ranked, request.strictness)) {
                    is MatchDecision.AutoAccept -> {
                        val game = games.first { it.providerGameId == decision.match.input.providerGameId }
                        return finish(request, source, game, decision.match.candidate, decision.warning, active, errors)
                    }
                    is MatchDecision.NeedsReview -> review += decision.candidates
                    MatchDecision.NoCandidates -> Unit
                }
            }
            if (answered) searched += source.id
        }
        return when {
            // A provider can list the same game twice (regional entries, paged results); show it once.
            review.isNotEmpty() -> ScrapeOutcome.NeedsReview(
                review.sortedByDescending { it.candidate.confidence }
                    .distinctBy { it.candidate.provider to it.candidate.providerGameId }
                    .take(request.maxCandidates)
                    .map { it.candidate },
                errors,
            )
            searched.isEmpty() && errors.isNotEmpty() -> ScrapeOutcome.ProviderErrors(errors)
            else -> ScrapeOutcome.NotFound(searched, errors)
        }
    }

    /**
     * Every game the active providers list for the query, ranked, without accepting any of them: for
     * "Identify game", where the user always picks. Returns [ScrapeOutcome.NeedsReview] with the
     * matches (best first), [ScrapeOutcome.NotFound] when none had anything, or
     * [ScrapeOutcome.ProviderErrors] when every provider failed.
     */
    suspend fun candidates(request: ScrapeRequest): ScrapeOutcome {
        val active = activeSources(request)
        if (active.isEmpty()) return ScrapeOutcome.NotFound(emptyList())
        val errors = ArrayList<ProviderError>()
        val found = ArrayList<ScoredMatch>()
        val searched = ArrayList<ScrapeProviderId>()
        val names = SearchNames.of(request.query)
        for (source in active) {
            // Other names only when the title found nothing at all here.
            for (name in names) {
                val games = when (val r = call(source.id) { source.search(request.query.named(name)) }) {
                    is ApiResult.Failure -> {
                        errors += ProviderError(source.id, r)
                        break
                    }
                    is ApiResult.Success -> r.value.take(request.maxCandidates)
                }
                if (source.id !in searched) searched += source.id
                found += matcher.rank(request.query, games.map { it.toMatchInput() })
                if (games.isNotEmpty()) break
            }
        }
        return when {
            found.isNotEmpty() -> ScrapeOutcome.NeedsReview(
                found.sortedByDescending { it.candidate.confidence }
                    .distinctBy { it.candidate.provider to it.candidate.providerGameId }
                    .map { it.candidate },
                errors,
            )
            searched.isEmpty() && errors.isNotEmpty() -> ScrapeOutcome.ProviderErrors(errors)
            else -> ScrapeOutcome.NotFound(searched, errors)
        }
    }

    /**
     * Completes a job with the candidate the user picked from [ScrapeOutcome.NeedsReview]: searches
     * that provider again and returns its metadata and artwork (plus other providers' artwork).
     */
    suspend fun accept(request: ScrapeRequest, candidate: ScrapeCandidate): ScrapeOutcome {
        val source = sources[candidate.provider] ?: return ScrapeOutcome.NotFound(emptyList())
        val errors = ArrayList<ProviderError>()
        val games = when (val r = call(source.id) { source.search(request.query.copy(title = candidate.title)) }) {
            is ApiResult.Failure -> return ScrapeOutcome.ProviderErrors(listOf(ProviderError(source.id, r)))
            is ApiResult.Success -> r.value
        }
        val game = games.firstOrNull { it.providerGameId == candidate.providerGameId }
            ?: return ScrapeOutcome.NotFound(listOf(source.id))
        return finish(request, source, game, candidate, null, activeSources(request), errors)
    }

    /** The art kinds at least one active source (in [priority], with [configured] keys) can return. */
    fun availableKinds(priority: List<ScrapeProviderId>, configured: Set<ScrapeProviderId>): Set<MediaKind> =
        activeSources(priority, configured).flatMapTo(LinkedHashSet()) { it.artworkKinds }

    /** Whether an active source can fill in details (description, year, genres). */
    fun providesMetadata(priority: List<ScrapeProviderId>, configured: Set<ScrapeProviderId>): Boolean =
        activeSources(priority, configured).any { it.providesMetadata }

    private fun activeSources(request: ScrapeRequest): List<ScrapeSource> = activeSources(request.priority, request.configured)

    private fun activeSources(priority: List<ScrapeProviderId>, configured: Set<ScrapeProviderId>): List<ScrapeSource> =
        priority.distinct().mapNotNull { id -> sources[id]?.takeIf { !id.needsCredentials || id in configured } }

    private suspend fun finish(
        request: ScrapeRequest,
        source: ScrapeSource,
        game: ProviderGame,
        candidate: ScrapeCandidate,
        warning: String?,
        active: List<ScrapeSource>,
        errors: MutableList<ProviderError>,
    ): ScrapeOutcome.Accepted {
        val metadata = if (request.wantMetadata && source.providesMetadata) game.metadata else null
        val artwork = collectArtwork(request, source, game, active, errors)
        return ScrapeOutcome.Accepted(candidate, metadata, artwork, warning, errors.toList())
    }

    private suspend fun collectArtwork(
        request: ScrapeRequest,
        winner: ScrapeSource,
        game: ProviderGame,
        active: List<ScrapeSource>,
        errors: MutableList<ProviderError>,
    ): List<ArtworkOption> {
        val kinds = request.artworkKinds
        if (kinds.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, ArtworkOption>()
        // The winner first when it is in the list, then the rest in the user's order.
        val order = (listOf(winner) + active).distinctBy { it.id }
        // Kinds no source can return never hold the loop open.
        val reachable = kinds.filter { k -> order.any { k in it.artworkKinds } }
        val followUp = request.query.copy(title = game.title, year = request.query.year ?: game.year)
        for (source in order) {
            if (!request.collectAllArtwork && reachable.all { k -> out.values.any { it.kind == k } }) break
            if (source.artworkKinds.none { it in kinds }) continue
            val options = if (source.id == winner.id) {
                call(source.id) { source.artwork(game, request.query, kinds) }
            } else {
                sameGameIn(source, followUp, request.strictness, errors)?.let { call(source.id) { source.artwork(it, request.query, kinds) } }
            } ?: continue
            when (options) {
                is ApiResult.Failure -> errors += ProviderError(source.id, options)
                is ApiResult.Success -> options.value.filter { it.kind in kinds }.forEach { out.getOrPut(it.url) { it } }
            }
        }
        return out.values.toList()
    }

    /**
     * The same game in another provider, only when the matcher would auto-accept it: searched by
     * the accepted title, then by the game's own names.
     */
    private suspend fun sameGameIn(
        source: ScrapeSource,
        query: ScrapeQuery,
        strictness: MatchStrictness,
        errors: MutableList<ProviderError>,
    ): ProviderGame? {
        val names = (listOf(query.title) + SearchNames.of(query)).distinct().take(FOLLOW_UP_NAMES)
        for (name in names) {
            val games = when (val r = call(source.id) { source.search(query.named(name)) }) {
                is ApiResult.Failure -> {
                    errors += ProviderError(source.id, r)
                    return null
                }
                is ApiResult.Success -> r.value
            }
            val decision = matcher.match(query, games.map { it.toMatchInput() }, strictness)
            val id = (decision as? MatchDecision.AutoAccept)?.match?.input?.providerGameId ?: continue
            return games.firstOrNull { it.providerGameId == id }
        }
        return null
    }

    /**
     * One provider call, minding rests: a resting provider isn't asked, a short rate limit is waited
     * out once, and running out of requests, a rejected key or repeated failures start a rest.
     */
    private suspend fun <T> call(provider: ScrapeProviderId, block: suspend () -> ApiResult<T>): ApiResult<T> {
        lock.withLock { rests[provider]?.takeIf { it.until > now() } }?.let { return ApiResult.RateLimited(null, it.reason) }
        var result = block()
        val wait = (result as? ApiResult.RateLimited)?.retryAfterSeconds
        if (wait != null && wait <= SHORT_WAIT_SECONDS) {
            delay(wait.coerceAtLeast(1) * 1000)
            result = block()
        }
        lock.withLock {
            when (result) {
                is ApiResult.Success -> failuresInARow.remove(provider)
                is ApiResult.RateLimited -> {
                    val ms = result.retryAfterSeconds?.let { (it * 1000).coerceAtLeast(MIN_REST_MS) } ?: QUOTA_REST_MS
                    rests[provider] = Rest(now() + ms, result.message)
                }
                is ApiResult.AuthError -> rests[provider] = Rest(now() + AUTH_REST_MS, result.message)
                is ApiResult.NetworkError, is ApiResult.InvalidResponse -> failed(provider, result.message)
                // Server trouble counts; a 404 or another answer about one request does not.
                is ApiResult.HttpError -> if (result.code >= 500) failed(provider, result.message)
                is ApiResult.NotConfigured -> Unit
            }
        }
        return result
    }

    /** Counts a failed answer; enough in a row and the provider rests. Call with [lock] held. */
    private fun failed(provider: ScrapeProviderId, reason: String) {
        val n = (failuresInARow[provider] ?: 0) + 1
        failuresInARow[provider] = n
        if (n >= FAILURES_BEFORE_REST) {
            rests[provider] = Rest(now() + FAILING_REST_MS, reason)
            failuresInARow.remove(provider)
        }
    }

    private companion object {
        /** Rate limits this short are waited out; longer ones start a rest. */
        const val SHORT_WAIT_SECONDS = 15L
        const val MIN_REST_MS = 30_000L
        /** A quota or allowance with no reset time: rest, then try again. */
        const val QUOTA_REST_MS = 30 * 60_000L
        const val AUTH_REST_MS = 30 * 60_000L
        const val FAILURES_BEFORE_REST = 3
        const val FAILING_REST_MS = 5 * 60_000L
        /** Names another provider is searched by to find the accepted game there. */
        const val FOLLOW_UP_NAMES = 2
    }
}

/** This query searching by [name] instead of its title (the same query for the title itself). */
private fun ScrapeQuery.named(name: String): ScrapeQuery = if (name == title) this else copy(title = name)
