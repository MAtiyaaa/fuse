package io.github.matiyaaa.fuse.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SearchTest {
    @Test
    fun normalisedSearchRanksPrefixMatchesFirst() = runBlocking {
        TestDb().use { t ->
            val titles = listOf("Dr. Mario", "Mario Kart - Super Circuit", "Supermario Land Hack", "Pokémon Emerald", "Assassin's Creed", "Zelda")
            t.data.indexer.apply(report(folder("/roms/gba", titles.map { scanned("/roms/gba/$it.gba") })), 1_000, testCleaner)
            val games = t.data.games

            assertEquals(
                listOf("Mario Kart - Super Circuit", "Dr. Mario", "Supermario Land Hack"),
                games.search("MARIO").map { it.displayTitle },
            )
            assertEquals(listOf("Pokémon Emerald"), games.search("pokemon").map { it.displayTitle })
            assertEquals(listOf("Assassin's Creed"), games.search("assassins").map { it.displayTitle })
            assertEquals(listOf("Mario Kart - Super Circuit"), games.search("kart super").map { it.displayTitle })
            assertEquals(emptyList(), games.search("%_"))
            assertEquals(2, games.search("mario", limit = 2).size)

            // Renamed games are still found by their file name.
            val zelda = games.idByPath("/roms/gba/Zelda.gba")!!
            games.rename(zelda, "My Favourite")
            assertEquals(listOf(zelda), games.search("zelda").map { it.id })
        }
    }

    @Test
    fun searchIsFastOnTwentyThousandGames() = runBlocking {
        TestDb().use { t ->
            val words = listOf("Super", "Mega", "Final", "Dragon", "Star", "Mario", "Sonic", "Metal", "Tales", "Legend", "Fire", "Kingdom", "Dark", "Ultra", "Pokemon", "Racing", "Soccer", "Ninja", "Space", "Street")
            val scans = (0 until 20_000).map { i ->
                val title = "${words[i % 20]} ${words[(i / 20) % 20]} ${words[(i / 400) % 20]} $i (USA)"
                scanned("/roms/mix/$title.bin", platform = "mix")
            }
            val indexTime = measureTime {
                t.data.indexer.apply(report(folder("/roms/mix", scans, platform = "mix")), 1_000, testCleaner)
            }
            println("Indexed 20000 games in $indexTime")
            val games = t.data.games
            games.search("warm up")

            for (query in listOf("mario", "dragon star", "pokemon 1999", "zzz no match", "ultra")) {
                var hits = 0
                val elapsed = measureTime { hits = games.search(query, limit = 50).size }
                println("search(\"$query\") -> $hits hits in $elapsed")
                assertTrue(elapsed.inWholeMilliseconds < 200, "search(\"$query\") took $elapsed")
            }
            assertEquals(50, games.search("mario").size)
        }
    }
}
