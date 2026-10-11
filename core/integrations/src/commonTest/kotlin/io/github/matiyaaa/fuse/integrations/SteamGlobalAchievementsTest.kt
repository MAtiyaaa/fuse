package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.steam.SteamGlobalAchievements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Steam's public achievement percentages, as numbers or as text, and nothing from a bad answer. */
class SteamGlobalAchievementsTest {
    @Test
    fun percentagesAreReadWhicheverWaySteamWritesThem() {
        val json = """{"achievementpercentages":{"achievements":[
            {"name":"ACH_WIN_ONE_GAME","percent":64.6},
            {"name":"ACH_RARE","percent":"2.4"},
            {"name":"","percent":"5"},
            {"name":"ACH_BROKEN","percent":"lots"}
        ]}}"""
        assertEquals(mapOf("ACH_WIN_ONE_GAME" to 64.6f, "ACH_RARE" to 2.4f), SteamGlobalAchievements.parse(json))
    }

    @Test
    fun anAnswerThatIsntOneGivesNothing() {
        assertNull(SteamGlobalAchievements.parse("<html>busy</html>"))
        assertNull(SteamGlobalAchievements.parse("""{"error":"no"}"""))
        assertEquals("https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/?gameid=620", SteamGlobalAchievements.url(620))
    }
}
