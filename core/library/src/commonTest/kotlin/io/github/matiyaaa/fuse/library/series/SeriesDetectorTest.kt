package io.github.matiyaaa.fuse.library.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeriesDetectorTest {

    private fun detect(vararg titles: String, franchises: Map<String, String> = emptyMap()) =
        SeriesDetector.detect(titles.map { SeriesInput(it, it, franchises[it]) })

    private fun List<DetectedSeries<String>>.named(name: String) = first { it.name == name }

    @Test
    fun sharedTitleStartsMakeSeries() {
        val found = detect(
            "Super Mario World", "Super Mario 64", "Super Mario Sunshine", "Super Mario Bros. 3",
            "Mario Kart 64", "Mario Kart DS", "Mario Kart Wii",
            "Tetris",
        )
        assertEquals(listOf("Mario Kart", "Super Mario"), found.map { it.name })
        assertEquals(4, found.named("Super Mario").members.size)
        assertTrue(found.none { it.fromDetails })
    }

    @Test
    fun seriesNamesNeverEndOnSmallWordsAndKeepThe() {
        val found = detect(
            "The Legend of Zelda: A Link to the Past",
            "The Legend of Zelda: Ocarina of Time",
            "The Legend of Zelda: Majora's Mask",
            "Legend of Mana",
        )
        assertEquals(listOf("The Legend of Zelda"), found.map { it.name })
        assertEquals(3, found.single().members.size)
    }

    @Test
    fun commonWordsAndPairsAreNotSeries() {
        assertTrue(detect("Super Metroid", "Super Castlevania IV", "Super Punch-Out!!").isEmpty())
        assertTrue(detect("Dragon Quest", "Dragon Ball Z", "Dragon Warrior").isEmpty())
        assertTrue(detect("Metroid Fusion", "Metroid Zero Mission").isEmpty(), "two games are not enough from titles")
        val pokemon = detect("Pokemon Red", "Pokemon Blue", "Pokemon Yellow")
        assertEquals(listOf("Pokemon"), pokemon.map { it.name })
    }

    @Test
    fun detailsSeriesWinAndTitleGroupsJoinThem() {
        val found = detect(
            "Metroid Fusion", "Metroid Zero Mission", "Super Metroid",
            "Castlevania", "Castlevania II", "Castlevania III",
            franchises = mapOf("Metroid Fusion" to "Metroid", "Super Metroid" to "Metroid", "Castlevania" to "Castlevania"),
        )
        val metroid = found.named("Metroid")
        assertTrue(metroid.fromDetails)
        assertEquals(setOf("Metroid Fusion", "Super Metroid"), metroid.members.toSet())
        // One game naming a series isn't enough, but three titles sharing its start are.
        val castlevania = found.named("Castlevania")
        assertTrue(!castlevania.fromDetails)
        assertEquals(3, castlevania.members.size)
    }

    @Test
    fun titleGroupNamedLikeADetailsSeriesMergesIntoIt() {
        val found = detect(
            "Final Fantasy VII", "Final Fantasy VIII",
            "Final Fantasy Tactics", "Final Fantasy X", "Final Fantasy IX",
            franchises = mapOf("Final Fantasy VII" to "Final Fantasy", "Final Fantasy VIII" to "Final Fantasy"),
        )
        assertEquals(1, found.size)
        assertEquals(5, found.single().members.size)
        assertTrue(found.single().fromDetails)
    }

    @Test
    fun wordsIgnoreTagsPunctuationAndTheInFront() {
        assertEquals(listOf("legend", "of", "zelda", "a", "link", "to", "the", "past"), SeriesDetector.words("The Legend of Zelda: A Link to the Past (USA) [!]"))
        assertEquals(listOf("super", "mario", "bros", "3"), SeriesDetector.words("Super Mario Bros. 3"))
    }
}
