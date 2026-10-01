package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchNamesTest {
    private fun q(title: String, vararg aka: String) = ScrapeQuery(title, PlatformId("psx"), "PlayStation", fileName = null, alsoKnownAs = aka.toList())

    @Test
    fun titleFirstThenOtherNamesThenSpellings() {
        assertEquals(listOf("Castlevania: Aria of Sorrow", "Akumajou Dracula", "Castlevania"), SearchNames.of(q("Castlevania: Aria of Sorrow", "Akumajou Dracula")))
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
    fun atMostThreeNames() {
        assertEquals(3, SearchNames.of(q("The Legend of Zelda: Ocarina of Time & More II", "A", "Zelda OoT", "Ocarina")).size)
    }
}
