package io.github.matiyaaa.fuse.library.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommenderTest {
    private val now = 1_800_000_000_000L
    private val day = Recommender.DAY_MS

    private fun g(
        key: String,
        platform: String = "snes",
        favorite: Boolean = false,
        playedDaysAgo: Int? = null,
        hours: Double = 0.0,
        year: Int? = null,
        genres: List<String> = emptyList(),
        developer: String? = null,
        franchise: String? = null,
        rating: Int? = null,
        addedDaysAgo: Int = 400,
    ) = RecGame(
        key, platform, key, favorite,
        lastPlayedAt = playedDaysAgo?.let { now - it * day },
        playSeconds = (hours * 3_600).toLong(),
        sessions = if (playedDaysAgo != null) 1 else 0,
        addedAt = now - addedDaysAgo * day,
        year = year, genres = genres, developer = developer, franchise = franchise, rating = rating,
    )

    @Test
    fun aFavouriteLeftAloneIsSuggestedWithHowLong() {
        val r = Recommender.recommend(listOf(g("Chrono Trigger", favorite = true, playedDaysAgo = 100, hours = 3.0), g("Tetris")), now)
        val pick = r.single { it.key == "Chrono Trigger" }
        assertEquals(RecKind.FORGOTTEN_FAVORITE, pick.kind)
        assertEquals("A favourite, last played 3 months ago", pick.reason)
    }

    @Test
    fun gamesPlayedLatelyAreLeftToContinuePlaying() {
        val r = Recommender.recommend(listOf(g("Chrono Trigger", favorite = true, playedDaysAgo = 3, hours = 10.0)), now)
        assertTrue(r.none { it.key == "Chrono Trigger" })
    }

    @Test
    fun theNextGameOfASeriesComesAfterTheOnePlayed() {
        val games = listOf(
            g("Metroid Fusion", platform = "gba", playedDaysAgo = 30, hours = 8.0, year = 2002, franchise = "Metroid"),
            g("Metroid Zero Mission", platform = "gba", year = 2004, franchise = "Metroid"),
            g("Super Metroid", year = 1994, franchise = "Metroid"),
        )
        val pick = Recommender.recommend(games, now).first { it.kind == RecKind.NEXT_IN_SERIES }
        assertEquals("Metroid Zero Mission", pick.key)
        assertEquals("Next in the Metroid series, after Metroid Fusion", pick.reason)
    }

    @Test
    fun makersAndKindsFollowWhatIsPlayedMost() {
        val games = listOf(
            g("Mega Man X", playedDaysAgo = 20, hours = 15.0, developer = "Capcom", genres = listOf("Platformer", "Action")),
            g("Breath of Fire", developer = "Capcom", genres = listOf("RPG")),
            g("Super Mario World", genres = listOf("Platformer")),
            g("F-Zero", genres = listOf("Racing")),
        )
        val r = Recommender.recommend(games, now).associateBy { it.key }
        assertEquals("From Capcom, who made Mega Man X", r.getValue("Breath of Fire").reason)
        assertEquals("Platformer, like Mega Man X", r.getValue("Super Mario World").reason)
        assertNull(r["F-Zero"])
    }

    @Test
    fun aGameLeftAfterRealTimeAndNeverStartedOnesAreSuggested() {
        val games = listOf(
            g("Final Fantasy VI", playedDaysAgo = 200, hours = 12.0),
            g("Earthbound", rating = 92),
            g("New Game", addedDaysAgo = 2),
        )
        val r = Recommender.recommend(games, now).associateBy { it.key }
        assertEquals("12 hours played, last 6 months ago", r.getValue("Final Fantasy VI").reason)
        assertEquals("Rated 92, not played yet", r.getValue("Earthbound").reason)
        assertEquals("New in your library, not played yet", r.getValue("New Game").reason)
    }

    @Test
    fun eachGameOnceWithItsStrongestReasonAndNoLongRunsOfOneReason() {
        val favourites = (1..6).map { g("Fav $it", favorite = true) }
        val rated = (1..3).map { g("Rated $it", rating = 85) }
        val r = Recommender.recommend(favourites + rated + g("Rated Fav", favorite = true, rating = 99), now)
        assertEquals(r.size, r.map { it.key }.distinct().size)
        assertEquals(RecKind.FORGOTTEN_FAVORITE, r.single { it.key == "Rated Fav" }.kind)
        // Never three of one reason in a row while another waits.
        val firstRated = r.indexOfFirst { it.kind == RecKind.WELL_RATED }
        assertTrue(firstRated in 0..2, r.toString())
    }

    @Test
    fun theSameLibraryGivesTheSameSuggestions() {
        val games = (1..30).map { g("Game $it", favorite = it % 3 == 0, rating = 70 + it, genres = listOf("Action")) } +
            g("Seed", playedDaysAgo = 30, hours = 5.0, genres = listOf("Action"))
        assertEquals(Recommender.recommend(games, now), Recommender.recommend(games.reversed(), now))
    }

    @Test
    fun similarGamesStartWithTheSeriesThenTheMakerThenTheKind() {
        val games = listOf(
            g("Metroid Fusion", developer = "Nintendo R&D1", franchise = "Metroid", genres = listOf("Action", "Adventure")),
            g("Super Metroid", developer = "Nintendo R&D1", franchise = "Metroid"),
            g("Kid Icarus", developer = "Nintendo R&D1", genres = listOf("Platformer")),
            g("Castlevania", genres = listOf("Action", "Adventure")),
            g("Tetris", genres = listOf("Puzzle")),
        )
        val r = Recommender.similar("Metroid Fusion", games)
        assertEquals(listOf("Super Metroid", "Kid Icarus", "Castlevania"), r.map { it.key })
        assertEquals("Also in the Metroid series", r[0].reason)
        assertEquals("Also from Nintendo R&D1", r[1].reason)
        assertEquals("Action, Adventure", r[2].reason)
    }

    @Test
    fun theSpotlightChangesDailyAndKeepsToItsSystem() {
        val games = (1..8).map { g("Game $it", platform = if (it % 2 == 0) "gba" else "snes", rating = 80 + it) }
        val today = Recommender.spotlight(games, now)
        assertNotNull(today)
        assertEquals(today, Recommender.spotlight(games, now + 60_000))
        val week = (0 until 7).mapNotNull { Recommender.spotlight(games, now + it * day)?.key }.toSet()
        assertTrue(week.size > 1, "a different game on different days: $week")
        val gba = Recommender.spotlight(games, now, platform = "gba")
        assertTrue(gba!!.key in games.filter { it.platform == "gba" }.map { it.key })
        assertNull(Recommender.spotlight(games, now, platform = "n64"))
    }

    @Test
    fun aCollectionWithNothingToSuggestStillHasASpotlight() {
        val games = listOf(g("Only Game", playedDaysAgo = 1, hours = 1.0))
        val s = Recommender.spotlight(games, now)
        assertEquals("Only Game", s?.key)
        assertEquals("From your collection", s?.reason)
    }
}
