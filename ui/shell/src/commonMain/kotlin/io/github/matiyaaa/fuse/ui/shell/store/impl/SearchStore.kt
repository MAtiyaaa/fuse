package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.repo.CollectionKey
import io.github.matiyaaa.fuse.data.search.FilterKey
import io.github.matiyaaa.fuse.data.search.LibrarySearch
import io.github.matiyaaa.fuse.data.search.PlayedWhen
import io.github.matiyaaa.fuse.data.search.SearchEntry
import io.github.matiyaaa.fuse.data.search.SearchFilter
import io.github.matiyaaa.fuse.data.search.SearchQuery
import io.github.matiyaaa.fuse.data.search.SearchSyntax
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.SearchChip
import io.github.matiyaaa.fuse.ui.shell.store.SearchResults
import io.github.matiyaaa.fuse.ui.shell.store.SearchSuggestion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * Search over the library: the name ranked with [io.github.matiyaaa.fuse.data.search.SearchRank],
 * filters from [SearchSyntax], chips that say each filter in words, and suggestions (filters to
 * add while nothing is typed, then values for the filter being typed). Everything stays on the
 * device. The index follows the library while search is in use and is let go a minute after.
 */
internal class LibrarySearcher(
    private val ctx: StoreContext,
    private val platforms: StateFlow<List<PlatformCard>>,
    private val collections: StateFlow<List<GameCollection>>,
    private val drives: StateFlow<List<SourceStatus>>,
    private val apps: (String) -> List<AppCard>,
    private val collectionsShown: () -> Boolean,
) {
    private val entries: StateFlow<List<SearchEntry>?> =
        ctx.data.search.observeEntries().stateIn(ctx.scope, SharingStarted.WhileSubscribed(60_000), null)

    suspend fun search(text: String): SearchResults {
        val query = SearchSyntax.parse(text)
        val all = entries.filterNotNull().first()
        val chips = query.filters.map { SearchChip(it.token, keyOf(it), describe(it)) } +
            query.invalid.map { SearchChip(it, null, it, valid = false) }
        val suggestions = suggest(text, query, all)
        if (query.isEmpty) return SearchResults(text.trim(), chips = chips, suggestions = suggestions)

        val scope = Scope(query, collectionMembers(query))
        val now = ctx.now()
        val found = LibrarySearch.run(all, query, now, ctx.services.utcOffsetMillis(), limit = 120) { e, f -> scope.keeps(e, f) }
        val games = ctx.cardsOnce(found.map { it.entry.summary })
        // With filters, only games answer; the other kinds are found by name alone.
        val named = query.filters.isEmpty() && query.needle.isNotEmpty()
        val needle = query.needle
        val systems = if (!named) emptyList() else platforms.value.filter {
            TitleText.normalize(it.platform.name).contains(needle) ||
                TitleText.normalize(it.platform.shortName) == needle ||
                it.platform.id.value.equals(needle, ignoreCase = true)
        }
        val appCards = if (named) apps(query.text) else emptyList()
        val cols = if (named && collectionsShown()) collections.value.filter { TitleText.normalize(it.name).contains(needle) } else emptyList()
        return SearchResults(text.trim(), games, systems, appCards, cols, chips, suggestions)
    }

    /** Answers the filters about things outside a game, resolved once per search. */
    private inner class Scope(query: SearchQuery, private val collectionSets: List<Set<GameId>>) {
        private val platformSets = query.filters.filterIsInstance<SearchFilter.Platform>().map { platformsFor(it.value) }
        private val driveFolders = query.filters.filterIsInstance<SearchFilter.Drive>().map { foldersFor(it.value) }

        fun keeps(e: SearchEntry, f: SearchFilter): Boolean = when (f) {
            is SearchFilter.Platform -> platformSets.all { e.summary.platformId in it }
            is SearchFilter.Drive -> driveFolders.all { roots -> roots.any { FsPath.isWithin(e.summary.folderPath, it) } }
            is SearchFilter.Collection -> collectionSets.all { e.summary.id in it }
            else -> true
        }
    }

    /** The games in the collections each `collection:` value names. */
    private suspend fun collectionMembers(query: SearchQuery): List<Set<GameId>> =
        query.filters.filterIsInstance<SearchFilter.Collection>().map { f ->
            val want = TitleText.normalize(f.value)
            collections.value.filter { TitleText.normalize(it.name).contains(want) }.flatMap { c ->
                ctx.data.collections.observeGames(CollectionKey.Manual(c.id)).first().map { it.id }
            }.toSet()
        }

    /** Systems a `platform:` value names: by id, short name, or a part of the full name. */
    private fun platformsFor(value: String): Set<PlatformId> {
        val v = TitleText.normalize(value)
        val compact = v.replace(" ", "")
        return ctx.platforms.all.filter { p ->
            p.id.value.equals(compact, ignoreCase = true) ||
                TitleText.normalize(p.shortName).replace(" ", "") == compact ||
                TitleText.normalize(p.name).contains(v)
        }.map { it.id }.toSet()
    }

    /** Library folders on the drive a `drive:` value names: its name, its kind (sd, usb), or the folder's own name. */
    private fun foldersFor(value: String): List<String> {
        val v = TitleText.normalize(value)
        return drives.value.filter { s ->
            TitleText.normalize(s.driveLabel).contains(v) ||
                TitleText.normalize(s.source.label).contains(v) ||
                (s.volume?.kind ?: s.source.volume?.kind)?.let { kindWords(it.name).any { w -> w.startsWith(v) } } == true
        }.map { it.source.path }
    }

    private fun kindWords(kind: String): List<String> = when (kind) {
        "SD_CARD" -> listOf("sd", "card", "sd card")
        "USB" -> listOf("usb")
        "INTERNAL" -> listOf("internal")
        "NETWORK" -> listOf("network", "nas")
        else -> listOf(kind.lowercase())
    }

    private fun keyOf(f: SearchFilter): FilterKey = when (f) {
        is SearchFilter.Platform -> FilterKey.PLATFORM
        is SearchFilter.Year -> FilterKey.YEAR
        is SearchFilter.Favorite -> FilterKey.FAVORITE
        is SearchFilter.Played -> FilterKey.PLAYED
        is SearchFilter.Missing -> FilterKey.MISSING
        is SearchFilter.Hidden -> FilterKey.HIDDEN
        is SearchFilter.Collection -> FilterKey.COLLECTION
        is SearchFilter.Genre -> FilterKey.GENRE
        is SearchFilter.Developer -> FilterKey.DEVELOPER
        is SearchFilter.Drive -> FilterKey.DRIVE
    }

    /** A filter in words, as its chip says it. */
    private fun describe(f: SearchFilter): String = when (f) {
        is SearchFilter.Platform -> platformsFor(f.value).let { ids ->
            when (ids.size) {
                0 -> "No system \"${f.value}\""
                1 -> ctx.platforms.byId(ids.first())?.name ?: f.value
                else -> "${ids.size} systems: ${f.value}"
            }
        }
        is SearchFilter.Year -> when {
            f.from == f.to -> "${f.from}"
            f.to == 2100 -> "${f.from} on"
            f.from == 1950 -> "Up to ${f.to}"
            f.to - f.from == 9 && f.from % 10 == 0 -> "The ${f.from}s"
            else -> "${f.from} to ${f.to}"
        }
        is SearchFilter.Favorite -> if (f.yes) "Favourites" else "Not favourites"
        is SearchFilter.Played -> when (f.time) {
            PlayedWhen.EVER -> "Played"
            PlayedWhen.NEVER -> "Never played"
            PlayedWhen.TODAY -> "Played today"
            PlayedWhen.WEEK -> "Played this week"
            PlayedWhen.MONTH -> "Played this month"
            PlayedWhen.YEAR -> "Played this year"
        }
        is SearchFilter.Missing -> if (f.yes) "Missing files" else "Files present"
        is SearchFilter.Hidden -> if (f.yes) "Hidden games" else "Not hidden"
        is SearchFilter.Collection -> "In ${f.value}"
        is SearchFilter.Genre -> f.value.replaceFirstChar { it.uppercase() }
        is SearchFilter.Developer -> "By ${f.value}"
        is SearchFilter.Drive -> "On ${f.value}"
    }

    /** What to add: filters to start with while nothing is typed, else values for the filter being typed. */
    private fun suggest(text: String, query: SearchQuery, all: List<SearchEntry>): List<SearchSuggestion> {
        val pending = query.pending
        if (pending != null) return values(text, pending.first, pending.second, all)
        if (text.isNotBlank()) return emptyList()
        val visible = all.filter { !it.summary.hidden && !it.summary.missing }
        return buildList {
            fun add(label: String, detail: String, key: FilterKey, value: String) =
                add(SearchSuggestion(label, detail, SearchSyntax.complete(text, key, value), key))
            if (visible.any { it.summary.favorite }) add("Favourites", "favorite:yes", FilterKey.FAVORITE, "yes")
            if (visible.any { it.summary.lastPlayedAt != null }) add("Played this week", "played:week", FilterKey.PLAYED, "week")
            if (visible.any { it.summary.lastPlayedAt == null }) add("Never played", "played:no", FilterKey.PLAYED, "no")
            fun start(label: String, key: FilterKey, detail: String) = add(SearchSuggestion(label, detail, text.trim().let { if (it.isEmpty()) "" else "$it " } + "${key.word}:", key))
            start("By system", FilterKey.PLATFORM, "platform:")
            if (visible.any { it.summary.releaseYear != null }) start("By year", FilterKey.YEAR, "year:1998, year:1990s")
            if (visible.any { it.genres.isNotEmpty() }) start("By genre", FilterKey.GENRE, "genre:")
            if (collectionsShown() && collections.value.isNotEmpty()) start("In a collection", FilterKey.COLLECTION, "collection:")
            if (drives.value.map { it.driveLabel }.distinct().size > 1) start("On a drive", FilterKey.DRIVE, "drive:")
            if (all.any { it.summary.missing }) add("Missing files", "missing:yes", FilterKey.MISSING, "yes")
        }
    }

    private fun values(text: String, key: FilterKey, typed: String, all: List<SearchEntry>): List<SearchSuggestion> {
        val want = TitleText.normalize(typed)
        fun s(label: String, detail: String?, value: String) = SearchSuggestion(label, detail, SearchSyntax.complete(text, key, value), key)
        val visible = all.filter { !it.summary.hidden && !it.summary.missing }
        val list = when (key) {
            FilterKey.PLATFORM -> {
                val counts = visible.groupingBy { it.summary.platformId }.eachCount()
                platforms.value.filter { counts.containsKey(it.platform.id) }.map { p ->
                    s(p.platform.name, "${games(counts[p.platform.id] ?: 0)} · ${p.platform.shortName}", p.platform.id.value)
                }.filter { want.isEmpty() || TitleText.normalize(it.label).contains(want) || TitleText.normalize(it.text).contains(want) }
            }
            FilterKey.GENRE -> visible.flatMap { it.genres }.groupingBy { it.trim() }.eachCount().entries
                .sortedByDescending { it.value }.map { (g, n) -> s(g, games(n), g) }
                .filter { want.isEmpty() || TitleText.normalize(it.label).contains(want) }
            FilterKey.COLLECTION -> collections.value.map { s(it.name, games(it.gameCount), it.name) }
                .filter { want.isEmpty() || TitleText.normalize(it.label).contains(want) }
            FilterKey.DRIVE -> drives.value.map { it.driveLabel }.distinct().map { s(it, null, it) }
                .filter { want.isEmpty() || TitleText.normalize(it.label).contains(want) }
            FilterKey.YEAR -> visible.mapNotNull { it.summary.releaseYear }.groupingBy { it / 10 * 10 }.eachCount().entries
                .sortedBy { it.key }.map { (decade, n) -> s("The ${decade}s", games(n), "${decade}s") }
                .filter { want.isEmpty() || it.text.contains(typed) }
            FilterKey.PLAYED -> listOf(
                s("Today", null, "today"), s("This week", null, "week"), s("This month", null, "month"),
                s("Ever", null, "yes"), s("Never", null, "no"),
            ).filter { want.isEmpty() || TitleText.normalize(it.label).startsWith(want) || it.text.contains(want) }
            FilterKey.FAVORITE, FilterKey.MISSING, FilterKey.HIDDEN -> listOf(s("Yes", null, "yes"), s("No", null, "no"))
                .filter { want.isEmpty() || TitleText.normalize(it.label).startsWith(want) }
            FilterKey.DEVELOPER -> emptyList()
        }
        return list.take(40)
    }

    private fun games(n: Int) = if (n == 1) "1 game" else "$n games"
}
