package io.github.matiyaaa.fuse.ui.shell.achievements

import io.github.matiyaaa.fuse.model.Achievement
import io.github.matiyaaa.fuse.model.AchievementSource
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed
import io.github.matiyaaa.fuse.ui.shell.store.LocalGameAchievements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RetroAchievements and the device's own trophies and achievements, on one page. */
class UnifiedAchievementsTest {
    private fun cheevo(i: Int, at: Long?, grade: String? = null, points: Int = 0) = Achievement(
        id = i.toLong(), gameId = 0, title = "A$i", description = "", points = points, badgeUrl = "b$i", badgeLockedUrl = "",
        earnedAt = at, earnedHardcoreAt = null, type = grade,
    )

    private fun set(title: String, source: AchievementSource, items: List<Achievement>) = AchievementState(
        raGameId = 0, title = title, consoleName = null, iconUrl = null, total = items.size, earned = items.count { it.earned },
        earnedHardcore = 0, points = 0, pointsEarned = 0,
        highestAward = if (items.all { it.earned }) "mastered" else null, achievements = items, fetchedAt = 0, source = source,
    )

    private val user = AchievementUser("someone", null, null, 1_234, 0, 0, null, null, null, 0)

    @Test
    fun everySourceMixesByTimeWithItsMark() {
        val ra = AchievementsFeed(
            user,
            recent = listOf(RecentAchievement(cheevo(1, null, points = 10).copy(gameId = 77), "Chrono Trigger", "SNES", null, false, earnedAt = 5_000)),
            inProgress = listOf(set("Chrono Trigger", AchievementSource.RETRO_ACHIEVEMENTS, listOf(cheevo(1, 1), cheevo(2, null))).copy(raGameId = 77)),
            recentlyMastered = emptyList(),
            earnedTotal = 40,
            masteredTotal = 2,
            lastUnlock = mapOf(77L to 5_000L),
        )
        val local = listOf(
            LocalGameAchievements(GameId(1), set("Demon's Souls", AchievementSource.TROPHIES, listOf(cheevo(1, 9_000, "gold"), cheevo(2, 3_000, "bronze"), cheevo(3, null, "platinum")))),
            LocalGameAchievements(GameId(2), set("Halo 3", AchievementSource.XENIA, listOf(cheevo(1, 7_000, points = 20), cheevo(2, 6_000, points = 30)))),
        )
        val all = UnifiedAchievements.of(ra, local)
        // Newest first, whatever the source.
        assertEquals(listOf(9_000L, 7_000L, 6_000L, 5_000L, 3_000L), all.recent.map { it.earnedAt })
        assertEquals(listOf(AchievementOrigin.PS3, AchievementOrigin.XBOX360, AchievementOrigin.XBOX360, AchievementOrigin.RETRO, AchievementOrigin.PS3), all.recent.map { it.origin })
        assertEquals("Gold", all.recent[0].worth)
        assertEquals("20 G", all.recent[1].worth)
        assertEquals("10 pts", all.recent[3].worth)
        assertEquals(GameId(1), all.recent[0].gameId)
        // In progress by last unlock; Halo 3 is all earned, so completed.
        assertEquals(listOf("Demon's Souls", "Chrono Trigger"), all.inProgress.map { it.state.title })
        assertEquals(listOf("Halo 3"), all.completed.map { it.state.title })
        val t = all.totals
        assertEquals(40 + 2 + 2, t.unlocked)
        assertEquals(2 + 1, t.completed)
        assertEquals(1_234L, t.raPoints)
        assertEquals(1, t.gold)
        assertEquals(1, t.bronze)
        assertEquals(0, t.platinum)
        assertEquals(50, t.gamerscore)
    }

    @Test
    fun withoutRetroAchievementsTheDevicesOwnStillShow() {
        val local = listOf(LocalGameAchievements(GameId(3), set("Gravity Rush", AchievementSource.VITA3K, listOf(cheevo(1, 1_000, "silver"), cheevo(2, null)))))
        val all = UnifiedAchievements.of(null, local)
        assertNull(all.user)
        assertNull(all.totals.raPoints)
        assertEquals(AchievementOrigin.VITA, all.recent.single().origin)
        assertEquals(1, all.totals.silver)
        assertTrue(!all.empty)
    }

    @Test
    fun nothingAnywhereIsEmpty() {
        assertTrue(UnifiedAchievements.of(null, emptyList()).empty)
        // A set with nothing earned yet is neither in progress nor completed.
        val none = listOf(LocalGameAchievements(GameId(4), set("Uncharted", AchievementSource.SHADPS4, listOf(cheevo(1, null)))))
        assertTrue(UnifiedAchievements.of(null, none).empty)
    }
}
