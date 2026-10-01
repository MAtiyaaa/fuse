package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchNamesTest {
    private fun q(title: String, vararg aka: String) = ScrapeQuery(title, PlatformId("psx"), "PlayStation", fileName = null, alsoKnownAs = aka.toList())

    @Test
    fun titleFirstThenOtherNamesThenSpellingsThenKeywords() {
        assertEquals(listOf("Castlevania: Aria of Sorrow", "Akumajou Dracula", "Castlevania"), SearchNames.of(q("Castlevania: Aria of Sorrow", "Akumajou Dracula")))
        assertEquals(listOf("Dredge Deluxe Edition", "Dredge v1.2", "Dredge"), SearchNames.of(q("Dredge Deluxe Edition", "Dredge v1.2")))
    }

    @Test
    fun spellingsProvidersTreatDifferently() {
        assertEquals(listOf("Sonic and Knuckles"), SearchNames.variants("Sonic & Knuckles"))
        assertEquals(listOf("Ratchet & Clank"), SearchNames.variants("Ratchet and Clank"))
        assertEquals(listOf("Final Fantasy 7"), SearchNames.variants("Final Fantasy VII"))
        assertEquals(listOf("Mega Man II"), SearchNames.variants("Mega Man 2"))
        assertEquals(listOf("The Legend of Zelda"), SearchNames.variants("Legend of Zelda, The"))
        assertEquals(listOf("Simpsons Wrestling"), SearchNames.variants("The Simpsons Wrestling"))
        assertEquals(listOf("Pokemon Snap"), SearchNames.variants("Pok\u00e9mon Snap"))
        assertEquals(listOf("Spyro"), SearchNames.variants("Spyro - Year of the Dragon"))
    }

    @Test
    fun namesThatDifferOnlyInPunctuationAreAskedOnce() {
        assertEquals(listOf("Pac-Man"), SearchNames.of(q("Pac-Man", "Pac Man", "pac man")))
        // A lone letter is never a Roman numeral to swap, and years aren't numerals either.
        assertTrue(SearchNames.variants("Mega Man X").isEmpty())
        assertTrue(SearchNames.variants("Madden 2004").isEmpty())
    }

    @Test
    fun atMostFiveSearchesWithKeywordsLast() {
        val query = q("The Legend of Zelda: Ocarina of Time & More II", "A", "Zelda OoT", "Ocarina")
        val plan = SearchNames.plan(query)
        assertEquals(listOf("The Legend of Zelda: Ocarina of Time & More II", "Zelda OoT", "Ocarina"), plan.names)
        assertEquals(listOf("The Legend of Zelda Ocarina of Time More", "Legend Zelda Ocarina"), plan.keywords)
        assertEquals(plan.names + plan.keywords, SearchNames.of(query))
        // With less room keywords never take more than half of what is left after the title.
        assertEquals(listOf("The Legend of Zelda: Ocarina of Time & More II", "Zelda OoT", "The Legend of Zelda Ocarina of Time More"), SearchNames.of(query, max = 3))
        assertEquals(listOf("The Legend of Zelda: Ocarina of Time & More II"), SearchNames.of(query, max = 1))
    }

    @Test
    fun keywordsLeaveOutNumbersCodesAndWeakWords() {
        assertEquals(listOf("Contra"), SearchNames.keywords("12 Contra"))
        assertEquals(listOf("Crash Bandicoot"), SearchNames.keywords("Crash Bandicoot SCUS-94900"))
        assertEquals(listOf("Final Fantasy"), SearchNames.keywords("Final Fantasy VII"))
        assertEquals(listOf("Dredge"), SearchNames.keywords("Dredge Deluxe Edition"))
        assertEquals(listOf("Legend Zelda Breath"), SearchNames.keywords("The Legend of Zelda: Breath of the Wild"))
        assertEquals(listOf("Hollow Knight Voidheart Edition", "Hollow Knight Voidheart"), SearchNames.keywords("Hollow Knight v1.5 Voidheart Edition"))
        assertEquals(listOf("Spider-Man"), SearchNames.keywords("Spider-Man 2"))
    }

    @Test
    fun noKeywordsWhenTheyWouldOnlyRepeatTheTitle() {
        for (title in listOf("Dredge", "Super Metroid", "Castlevania: Aria of Sorrow", "Zelda BOTW", "pepsi_man_final")) {
            assertTrue(SearchNames.keywords(title).isEmpty(), title)
        }
        assertEquals(listOf("Dredge"), SearchNames.of(q("Dredge")))
        assertEquals(listOf("Final Fantasy VII", "Final Fantasy 7", "Final Fantasy"), SearchNames.of(q("Final Fantasy VII")))
        assertEquals(listOf("Final Fantasy"), SearchNames.plan(q("Final Fantasy VII")).keywords)
        // A keyword that is one of the names is asked once, as a name.
        assertEquals(SearchNames.Plan(listOf("12 Contra", "Contra", "XII Contra"), emptyList()), SearchNames.plan(q("12 Contra", "Contra")))
    }
}
