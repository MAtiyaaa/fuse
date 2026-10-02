package io.github.matiyaaa.fuse.data.search

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.data.repo.toSummary
import io.github.matiyaaa.fuse.model.GameMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A game as search sees it: its summary, its searchable name, its genres and (read when asked) its developer. */
class SearchEntry(
    val summary: GameSummary,
    /** Display and original title, normalised ([TitleText.normalize]). */
    val name: String,
    val genres: List<String>,
    private val details: String?,
) {
    /** Developer and publisher, normalised; decoded only for a `developer:` search. */
    val makers: String by lazy {
        val m = decodeOrNull(GameMetadata.serializer(), details) ?: return@lazy ""
        TitleText.normalize(listOfNotNull(m.developer, m.publisher).joinToString(" "))
    }
}

/** Every game search can find, kept current as the library changes. */
class SearchRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    fun observeEntries(): Flow<List<SearchEntry>> = db.searchQueries.searchRows().asFlow().mapToList(dispatcher).map { rows ->
        val genres = db.searchQueries.searchGenres().executeAsList().groupBy({ it.game_id }, { it.genre })
        val details = db.searchQueries.searchDetails().executeAsList().associate { it.id to it.metadata_json }
        rows.map { r -> SearchEntry(r.toSummary(), r.search_title, genres[r.id].orEmpty(), details[r.id]) }
    }.flowOn(dispatcher)
}

/** A game found, and how well it matched (higher first). */
class SearchMatch(val entry: SearchEntry, val score: Int)

/**
 * Runs a [SearchQuery] over [SearchEntry]s. Hidden and missing games stay out unless a filter asks
 * for them. Filters about things outside the game itself (its system, collections, drive) are
 * answered by [outside]: true keeps the game, false drops it.
 */
object LibrarySearch {
    private const val DAY_MS = 86_400_000L

    fun run(
        entries: List<SearchEntry>,
        query: SearchQuery,
        now: Long,
        utcOffsetMillis: Long,
        limit: Int = 200,
        outside: (SearchEntry, SearchFilter) -> Boolean = { _, _ -> true },
    ): List<SearchMatch> {
        if (query.isEmpty) return emptyList()
        val needle = query.needle
        val wantsHidden = query.filters.any { it is SearchFilter.Hidden && it.yes }
        val wantsMissing = query.filters.any { it is SearchFilter.Missing && it.yes }
        val today = (now + utcOffsetMillis).floorDiv(DAY_MS) * DAY_MS - utcOffsetMillis
        val out = ArrayList<SearchMatch>()
        for (e in entries) {
            val s = e.summary
            if (s.hidden && !wantsHidden) continue
            if (s.missing && !wantsMissing) continue
            if (!query.filters.all { f -> keeps(e, f, now, today) && outside(e, f) }) continue
            val score = SearchRank.score(e.name, needle) ?: continue
            out += SearchMatch(e, score)
        }
        // Equal matches: favourites, then the most recently played, then by name.
        return out.sortedWith(
            compareByDescending<SearchMatch> { it.score }
                .thenByDescending { it.entry.summary.favorite }
                .thenByDescending { it.entry.summary.lastPlayedAt ?: 0L }
                .thenBy { it.entry.summary.sortKey },
        ).take(limit)
    }

    private fun keeps(e: SearchEntry, f: SearchFilter, now: Long, today: Long): Boolean {
        val s = e.summary
        return when (f) {
            is SearchFilter.Year -> s.releaseYear?.let { it in f.from..f.to } ?: false
            is SearchFilter.Favorite -> s.favorite == f.yes
            is SearchFilter.Missing -> s.missing == f.yes
            is SearchFilter.Hidden -> s.hidden == f.yes
            is SearchFilter.Played -> {
                val at = s.lastPlayedAt
                when (f.time) {
                    PlayedWhen.EVER -> at != null
                    PlayedWhen.NEVER -> at == null
                    PlayedWhen.TODAY -> at != null && at >= today
                    PlayedWhen.WEEK -> at != null && at >= now - 7 * DAY_MS
                    PlayedWhen.MONTH -> at != null && at >= now - 30 * DAY_MS
                    PlayedWhen.YEAR -> at != null && at >= now - 365 * DAY_MS
                }
            }
            is SearchFilter.Genre -> {
                val want = TitleText.normalize(f.value)
                e.genres.any { TitleText.normalize(it).contains(want) }
            }
            is SearchFilter.Developer -> {
                val want = TitleText.normalize(f.value)
                want.isNotEmpty() && e.makers.contains(want)
            }
            is SearchFilter.Platform, is SearchFilter.Collection, is SearchFilter.Drive -> true
        }
    }
}
