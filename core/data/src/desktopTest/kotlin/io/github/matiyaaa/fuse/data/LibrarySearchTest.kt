package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.search.FilterKey
import io.github.matiyaaa.fuse.data.search.LibrarySearch
import io.github.matiyaaa.fuse.data.search.PlayedWhen
import io.github.matiyaaa.fuse.data.search.SearchFilter
import io.github.matiyaaa.fuse.data.search.SearchRank
import io.github.matiyaaa.fuse.data.search.SearchSyntax
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibrarySearchTest {
    @Test
    fun filtersAreReadAndTheRestIsTheName() {
        val q = SearchSyntax.parse("""zelda platform:n64 year:1995-1999 genre:"action adventure" fav:yes played:week re:volt""")
        assertEquals("zelda re:volt", q.text)
        assertEquals(
            listOf(
                SearchFilter.Platform("n64", "platform:n64"),
                SearchFilter.Year(1995, 1999, "year:1995-1999"),
                SearchFilter.Genre("action adventure", "genre:\"action adventure\""),
                SearchFilter.Favorite(true, "fav:yes"),
                SearchFilter.Played(PlayedWhen.WEEK, "played:week"),
            ),
            q.filters,
        )
        assertTrue(q.invalid.isEmpty())
    }

    @Test
    fun yearsTakeRangesDecadesAndBounds() {
        val years = { v: String -> (SearchSyntax.parse("year:$v ").filters.singleOrNull() as? SearchFilter.Year)?.let { it.from to it.to } }
        assertEquals(1998 to 1998, years("1998"))
        assertEquals(1990 to 1999, years("1990s"))
        assertEquals(2001 to 2100, years(">2000"))
        assertEquals(1950 to 1989, years("<1990"))
        assertEquals(1995 to 1999, years("1999-1995"))
        assertNull(years("soon"))
        assertEquals(listOf("year:soon"), SearchSyntax.parse("year:soon mario").invalid)
    }

    @Test
    fun aFilterBeingTypedIsPendingNotWrong() {
        val q = SearchSyntax.parse("mario platform:sn")
        assertEquals(FilterKey.PLATFORM to "sn", q.pending)
        assertTrue(q.invalid.isEmpty())
        assertEquals("mario platform:snes ", SearchSyntax.complete("mario platform:sn", FilterKey.PLATFORM, "snes"))
        assertEquals("mario genre:\"role playing\" ", SearchSyntax.complete("mario ", FilterKey.GENRE, "role playing"))
        assertEquals("mario ", SearchSyntax.remove("mario fav:yes", "fav:yes"))
    }

    @Test
    fun namesRankWholeThenStartThenWordsThenTypos() {
        val exact = SearchRank.score("doom", "doom")!!
        val prefix = SearchRank.score("doom eternal", "doom")!!
        val word = SearchRank.score("final doom", "doom")!!
        val initials = SearchRank.score("metal gear solid", "mgs")!!
        val typo = SearchRank.score("the legend of zelda", "zelad")!!
        assertTrue(exact > prefix && prefix > word && word > initials && initials > typo)
        assertNotNull(SearchRank.score("pokemon red version", "pokmon"))
        assertNotNull(SearchRank.score("castlevania symphony of the night", "castlevana"))
        assertNull(SearchRank.score("tetris", "zelda"))
        // Short words need to be right.
        assertNull(SearchRank.score("fez", "fex"))
    }

    @Test
    fun searchFiltersTheLibrary() = TestDb().use { t ->
        runBlocking {
            val source = t.data.sources.add("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(
                report(folder("/roms/n64", listOf(
                    scanned("/roms/n64/The Legend of Zelda - Ocarina of Time.z64", platform = "n64", source = source.value),
                    scanned("/roms/n64/Wave Race 64.z64", platform = "n64", source = source.value),
                    scanned("/roms/n64/Hidden Thing.z64", platform = "n64", source = source.value),
                ), platform = "n64")),
                t.now, testCleaner,
            )
            val all = t.data.games.observeAll(includeHidden = true).first().associateBy { it.displayTitle }
            val zelda = all.getValue("The Legend of Zelda - Ocarina of Time").id
            t.data.games.setFavorite(zelda, true)
            t.data.games.applyMetadata(zelda, GameMetadata(releaseYear = 1998, developer = "Nintendo EAD", genres = listOf("Action Adventure")), onlyFillEmpty = false)
            t.data.games.setHidden(all.getValue("Hidden Thing").id, true)

            val entries = t.data.search.observeEntries().first()
            fun run(text: String) = LibrarySearch.run(entries, SearchSyntax.parse(text), t.now, 0).map { it.entry.summary.displayTitle }

            assertEquals(listOf("The Legend of Zelda - Ocarina of Time"), run("zelad"))
            assertEquals(listOf("The Legend of Zelda - Ocarina of Time"), run("year:1990s genre:action developer:nintendo"))
            assertEquals(listOf("Wave Race 64"), run("fav:no"))
            assertEquals(emptyList(), run("thing"))
            assertEquals(listOf("Hidden Thing"), run("thing hidden:yes"))
            assertEquals(emptyList(), run("played:yes"))
        }
    }
}
