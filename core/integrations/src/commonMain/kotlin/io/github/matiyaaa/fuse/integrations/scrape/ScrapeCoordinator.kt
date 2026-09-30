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
 * Identification tries metadata providers first (in priority order), then artwork-only ones. The
 * first provider whose results the [TitleMatcher] auto-accepts under the request's strictness wins;
 * otherwise every candidate seen is returned for review. Artwork for an accepted game comes from
 * the winning provider and from each other configured provider that also confidently matches the
 * accepted title.
 */
class ScrapeCoordinator(
    sources: List<ScrapeSource>,
    private val matcher: TitleMatcher = TitleMatcher(),
) {
    private val sources: Map<ScrapeProviderId, ScrapeSource> = sources.associateBy { it.id }

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
        for (source in ordered) {
            val games = when (val r = source.search(request.query)) {
                is ApiResult.Failure -> {
                    errors += ProviderError(source.id, r)
                    continue
                }
                is ApiResult.Success -> r.value.take(request.maxCandidates)
            }
            searched += source.id
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
        for (source in active) {
            val games = when (val r = source.search(request.query)) {
                is ApiResult.Failure -> {
                    errors += ProviderError(source.id, r)
                    continue
                }
                is ApiResult.Success -> r.value.take(request.maxCandidates)
            }
            searched += source.id
            found += matcher.rank(request.query, games.map { it.toMatchInput() })
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
        val games = when (val r = source.search(request.query.copy(title = candidate.title))) {
            is ApiResult.Failure -> return ScrapeOutcome.ProviderErrors(listOf(ProviderError(source.id, r)))
            is ApiResult.Success -> r.value
        }
        val game = games.firstOrNull { it.providerGameId == candidate.providerGameId }
            ?: return ScrapeOutcome.NotFound(listOf(source.id))
        return finish(request, source, game, candidate, null, activeSources(request), errors)
    }

    private fun activeSources(request: ScrapeRequest): List<ScrapeSource> = request.priority.distinct().mapNotNull { id ->
        sources[id]?.takeIf { !id.needsCredentials || id in request.configured }
    }

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
        val followUp = request.query.copy(title = game.title, year = request.query.year ?: game.year)
        for (source in order) {
            if (!request.collectAllArtwork && kinds.all { k -> out.values.any { it.kind == k } }) break
            val options = if (source.id == winner.id) {
                source.artwork(game, request.query, kinds)
            } else {
                sameGameIn(source, followUp, request.strictness, errors)?.let { source.artwork(it, request.query, kinds) }
            } ?: continue
            when (options) {
                is ApiResult.Failure -> errors += ProviderError(source.id, options)
                is ApiResult.Success -> options.value.filter { it.kind in kinds }.forEach { out.getOrPut(it.url) { it } }
            }
        }
        return out.values.toList()
    }

    /** The same game in another provider, only when the matcher would auto-accept it. */
    private suspend fun sameGameIn(
        source: ScrapeSource,
        query: ScrapeQuery,
        strictness: MatchStrictness,
        errors: MutableList<ProviderError>,
    ): ProviderGame? {
        val games = when (val r = source.search(query)) {
            is ApiResult.Failure -> {
                errors += ProviderError(source.id, r)
                return null
            }
            is ApiResult.Success -> r.value
        }
        val decision = matcher.match(query, games.map { it.toMatchInput() }, strictness)
        val id = (decision as? MatchDecision.AutoAccept)?.match?.input?.providerGameId ?: return null
        return games.firstOrNull { it.providerGameId == id }
    }
}
