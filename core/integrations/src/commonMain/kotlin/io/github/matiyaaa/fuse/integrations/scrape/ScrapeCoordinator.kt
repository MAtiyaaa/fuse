package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.match.MatchDecision
import io.github.matiyaaa.fuse.integrations.match.PlatformEvidence
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

    /**
     * A match safe to apply. [warning] is set when an aggressive accept should be flagged in the UI.
     * [guessed] marks a best guess by name ([Guess]): its art is offered, its details never are.
     * [links] is the game's id in each provider the art came from, so it needn't be searched again.
     */
    data class Accepted(
        val candidate: ScrapeCandidate,
        val metadata: GameMetadata?,
        val artwork: List<ArtworkOption>,
        val warning: String? = null,
        override val errors: List<ProviderError> = emptyList(),
        val guessed: Boolean = false,
        val links: Map<ScrapeProviderId, String> = emptyMap(),
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
 * What [ScrapeCoordinator.scrape] does when no match is sure enough to accept. Art is cosmetic, so
 * it can come from a guess; details never do.
 */
enum class Guess {
    /** Ask the user (the matches are returned for review). */
    NONE,

    /** Take the best match whose title is the game's own (ignoring case, punctuation and tags). */
    EXACT_TITLE,

    /** Take the best match whose title is the game's own, else the best match at all. */
    BEST,
}

/**
 * Runs metadata search and artwork listing across providers in the user's order. It only reads
 * from providers and returns data: it never writes to the library or the media store.
 *
 * Identification tries metadata providers first (in priority order), then artwork-only ones. Each
 * provider is searched by the game's title and, while nothing sure turns up, by its other names
 * ([SearchNames]); only when no provider found the game by any name are they searched again by
 * its keywords. Every result is scored against the game's own names, never against the search
 * that found it. The first provider whose results the [TitleMatcher] auto-accepts under the
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

    /**
     * Identifies the game and collects artwork. With a [guess], a game with several close matches
     * still gets art: from the match [Guess] picks (marked [ScrapeOutcome.Accepted.guessed], with no
     * details) instead of a list to review.
     */
    suspend fun scrape(request: ScrapeRequest, guess: Guess = Guess.NONE): ScrapeOutcome {
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
        val failed = HashSet<ScrapeProviderId>()
        // Where each reviewed match came from, so a guess can be finished without searching again.
        val seen = HashMap<Pair<ScrapeProviderId, String>, Pair<ScrapeSource, ProviderGame>>()
        val plan = SearchNames.plan(request.query)
        // Every provider by the names first; keywords only when none of them found the game for sure.
        for ((round, keywords) in listOf(plan.names to false, plan.keywords to true)) {
            for (source in ordered) {
                if (source.id in failed || (keywords && !source.searchesByKeyword)) continue
                for (name in round) {
                    val games = when (val r = call(source.id) { source.search(request.query.named(name)) }) {
                        is ApiResult.Failure -> {
                            errors += ProviderError(source.id, r)
                            failed += source.id
                            break
                        }
                        is ApiResult.Success -> r.value.take(request.maxCandidates)
                    }
                    if (source.id !in searched) searched += source.id
                    // Whatever a name or keyword finds is scored against the game's own names.
                    val ranked = matcher.rank(request.query, games.map { it.toMatchInput() })
                    when (val decision = matcher.decide(ranked, request.strictness)) {
                        is MatchDecision.AutoAccept -> {
                            val game = games.first { it.providerGameId == decision.match.input.providerGameId }
                            return finish(request, source, game, decision.match.candidate, decision.warning, active, errors)
                        }
                        is MatchDecision.NeedsReview -> {
                            review += decision.candidates
                            for (m in decision.candidates) {
                                val g = games.firstOrNull { it.providerGameId == m.input.providerGameId } ?: continue
                                seen.getOrPut(m.candidate.provider to m.candidate.providerGameId) { source to g }
                            }
                        }
                        MatchDecision.NoCandidates -> Unit
                    }
                }
            }
        }
        if (guess != Guess.NONE && review.isNotEmpty()) {
            val possible = review.filter { it.platform != PlatformEvidence.MISMATCH }
            val pick = possible.filter { it.titleExact }.maxByOrNull { it.score }
                ?: if (guess == Guess.BEST) possible.maxByOrNull { it.score } else null
            val from = pick?.let { seen[it.candidate.provider to it.candidate.providerGameId] }
            if (pick != null && from != null) {
                return finish(request, from.first, from.second, pick.candidate, null, active, errors, guessed = true)
            }
        }
        return when {
            // A provider can list the same game twice (regional entries, paged results); show it once.
            review.isNotEmpty() -> ScrapeOutcome.NeedsReview(
                review.sortedByDescending { it.score }
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
     * "Identify game", where the user always picks. Each provider is searched by the game's names and
     * then its keywords ([SearchNames]) until one search finds a match the request's strictness
     * would accept, so an oddly named game still turns up; each provider adds up to
     * [ScrapeRequest.maxCandidates] games. Returns [ScrapeOutcome.NeedsReview] with the matches
     * (best first, each game once), [ScrapeOutcome.NotFound] when none had anything, or
     * [ScrapeOutcome.ProviderErrors] when every provider failed.
     */
    suspend fun candidates(request: ScrapeRequest): ScrapeOutcome {
        val active = activeSources(request)
        if (active.isEmpty()) return ScrapeOutcome.NotFound(emptyList())
        val errors = ArrayList<ProviderError>()
        val found = ArrayList<ScoredMatch>()
        val searched = ArrayList<ScrapeProviderId>()
        val plan = SearchNames.plan(request.query)
        for (source in active) {
            val names = if (source.searchesByKeyword) plan.all else plan.names
            val here = ArrayList<ScoredMatch>()
            for (name in names) {
                val games = when (val r = call(source.id) { source.search(request.query.named(name)) }) {
                    is ApiResult.Failure -> {
                        errors += ProviderError(source.id, r)
                        break
                    }
                    is ApiResult.Success -> r.value.take(request.maxCandidates)
                }
                if (source.id !in searched) searched += source.id
                val ranked = matcher.rank(request.query, games.map { it.toMatchInput() })
                here += ranked
                // Other names and keywords only while nothing sure turned up here.
                if (matcher.decide(ranked, request.strictness) is MatchDecision.AutoAccept) break
            }
            found += here.sortedByDescending { it.score }
                .distinctBy { it.candidate.providerGameId }
                .take(request.maxCandidates)
        }
        return when {
            found.isNotEmpty() -> ScrapeOutcome.NeedsReview(
                found.sortedByDescending { it.score }
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

    /**
     * Art (and details) for a game identified before, from the providers it has an id in ([ids]):
     * no search and no matching, so it never asks which game this is again. Other providers that
     * can add art are asked for the same game by its title. Null when no id could be looked up.
     */
    suspend fun known(request: ScrapeRequest, ids: Map<ScrapeProviderId, String>): ScrapeOutcome.Accepted? {
        if (ids.isEmpty()) return null
        val active = activeSources(request)
        val errors = ArrayList<ProviderError>()
        val found = LinkedHashMap<ScrapeProviderId, Pair<ScrapeSource, ProviderGame>>()
        for (source in active) {
            val id = ids[source.id] ?: continue
            when (val r = call(source.id) { source.byId(id, request.query) }) {
                is ApiResult.Failure -> errors += ProviderError(source.id, r)
                is ApiResult.Success -> r.value?.let { found[source.id] = source to it }
            }
        }
        // Details from the first that has them, in the user's order.
        val (lead, game) = found.values.firstOrNull { it.first.providesMetadata } ?: found.values.firstOrNull() ?: return null
        val candidate = ScrapeCandidate(
            provider = lead.id,
            providerGameId = game.providerGameId,
            title = game.title,
            platformName = game.platformNames.firstOrNull(),
            year = game.year,
            confidence = 1f,
            reasons = listOf("Identified before"),
            previewUrl = game.previewUrl,
        )
        return finish(request, lead, game, candidate, null, active, errors, known = found.mapValues { it.value.second })
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
        guessed: Boolean = false,
        known: Map<ScrapeProviderId, ProviderGame> = emptyMap(),
    ): ScrapeOutcome.Accepted {
        // A guess is good enough for art, never for details.
        val metadata = if (request.wantMetadata && source.providesMetadata && !guessed) game.metadata else null
        val links = LinkedHashMap<ScrapeProviderId, String>()
        links[source.id] = game.providerGameId
        val artwork = collectArtwork(request, source, game, active, errors, known, links)
        return ScrapeOutcome.Accepted(candidate, metadata, artwork, warning, errors.toList(), guessed, links)
    }

    private suspend fun collectArtwork(
        request: ScrapeRequest,
        winner: ScrapeSource,
        game: ProviderGame,
        active: List<ScrapeSource>,
        errors: MutableList<ProviderError>,
        known: Map<ScrapeProviderId, ProviderGame>,
        links: MutableMap<ScrapeProviderId, String>,
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
            val same = when {
                source.id == winner.id -> game
                else -> known[source.id] ?: sameGameIn(source, followUp, request.strictness, errors)
            } ?: continue
            links[source.id] = same.providerGameId
            when (val options = call(source.id) { source.artwork(same, request.query, kinds) }) {
                is ApiResult.Failure -> errors += ProviderError(source.id, options)
                is ApiResult.Success -> options.value.filter { it.kind in kinds }.forEach { out.getOrPut(it.url) { it } }
            }
        }
        return out.values.toList()
    }

    /**
     * The same game in another provider: searched by the accepted title, then by the game's own
     * names. A match the matcher would auto-accept wins; else the first result with the game's own
     * title (SteamGridDB often lists one game several times under one name, so nothing there is
     * ever sure, yet any of them has the game's art). Never one on another platform.
     */
    private suspend fun sameGameIn(
        source: ScrapeSource,
        query: ScrapeQuery,
        strictness: MatchStrictness,
        errors: MutableList<ProviderError>,
    ): ProviderGame? {
        val names = (listOf(query.title) + SearchNames.of(query)).distinct().take(FOLLOW_UP_NAMES)
        var sameName: ProviderGame? = null
        for (name in names) {
            val games = when (val r = call(source.id) { source.search(query.named(name)) }) {
                is ApiResult.Failure -> {
                    errors += ProviderError(source.id, r)
                    return sameName
                }
                is ApiResult.Success -> r.value
            }
            val ranked = matcher.rank(query, games.map { it.toMatchInput() })
            val decision = matcher.decide(ranked, strictness)
            (decision as? MatchDecision.AutoAccept)?.match?.input?.providerGameId?.let { id ->
                return games.firstOrNull { it.providerGameId == id }
            }
            if (sameName == null) {
                val exact = ranked.firstOrNull { it.titleExact && it.platform != PlatformEvidence.MISMATCH }
                sameName = exact?.let { m -> games.firstOrNull { it.providerGameId == m.input.providerGameId } }
            }
        }
        return sameName
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
