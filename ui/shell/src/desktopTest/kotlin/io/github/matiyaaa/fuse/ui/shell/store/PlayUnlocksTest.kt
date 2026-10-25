package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.Achievement
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.RecentAchievement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Unlocks noticed while a game runs: only that game's, only since it started, each once. */
class PlayUnlocksTest {
    private val start = 1_700_000_000_000L
    private val game = GameId(7L)

    private fun achievement(id: Long, gameId: Long = 11L, points: Int = 10, earnedAt: Long? = null) =
        Achievement(id, gameId, "A$id", "Do thing $id", points, "badge/$id", "badge/$id-lock", earnedAt, null)

    private fun recent(id: Long, at: Long, gameId: Long = 11L, hardcore: Boolean = false, points: Int = 10) =
        RecentAchievement(achievement(id, gameId, points), "Final Fantasy VII", "PlayStation", null, hardcore, at)

    private val set = AchievementState(
        raGameId = 11L, title = "Final Fantasy VII", consoleName = "PlayStation", iconUrl = null,
        total = 4, earned = 1, earnedHardcore = 0, points = 40, pointsEarned = 10, highestAward = null,
        achievements = listOf(achievement(1, earnedAt = start - 86_400_000L), achievement(2), achievement(3), achievement(4)),
        fetchedAt = start,
    )

    @Test
    fun onlyThisGamesUnlocksSinceItStartedCountOnce() {
        val p = PlayUnlocks(game, since = start, set = set)
        val next = p.with(
            listOf(
                recent(3, start + 120_000),
                recent(2, start + 60_000),
                // Another game's, and one from before this game started.
                recent(9, start + 30_000, gameId = 99L),
                recent(1, start - 86_400_000L),
            ),
            raGameId = 11L,
        )
        assertEquals(listOf(2L, 3L), next.unlocks.map { it.achievement.id })
        assertEquals(3, next.set!!.earned)
        assertEquals(30, next.set.pointsEarned)
        // Asked again, the same answer adds nothing.
        assertSame(next, next.with(listOf(recent(2, start + 60_000), recent(3, start + 120_000)), raGameId = 11L))
    }

    @Test
    fun aClockALittleBehindStillCounts() {
        val p = PlayUnlocks(game, since = start, set = set)
        assertEquals(1, p.with(listOf(recent(2, start - 60_000)), 11L).unlocks.size)
        assertEquals(0, p.with(listOf(recent(2, start - 10 * 60_000)), 11L).unlocks.size)
    }

    @Test
    fun aHardcoreUnlockOfAnEarnedOneLightsHardcoreOnly() {
        val s = set.withUnlocks(listOf(recent(1, start + 1_000, hardcore = true)))
        assertEquals(1, s.earned)
        assertEquals(1, s.earnedHardcore)
        assertEquals(10, s.pointsEarned)
    }

    @Test
    fun aSetWithoutItsListStillCounts() {
        val bare = set.copy(achievements = emptyList())
        val s = bare.withUnlocks(listOf(recent(2, start + 1_000), recent(3, start + 2_000, hardcore = true)))
        assertEquals(3, s.earned)
        assertEquals(1, s.earnedHardcore)
        assertEquals(30, s.pointsEarned)
    }

    @Test
    fun theRecapSaysHowManyInWhichGameAndThePoints() {
        val p = PlayUnlocks(game, since = start, endedAt = start + 3_600_000L, set = set)
        assertNull(io.github.matiyaaa.fuse.ui.shell.app.recapWords(p))
        val one = p.with(listOf(recent(2, start + 1_000, points = 25)), 11L)
        assertEquals("1 achievement unlocked in Final Fantasy VII, 25 points", io.github.matiyaaa.fuse.ui.shell.app.recapWords(one))
        val three = one.with(listOf(recent(3, start + 2_000), recent(4, start + 3_000)), 11L)
        assertEquals("3 achievements unlocked in Final Fantasy VII, 45 points", io.github.matiyaaa.fuse.ui.shell.app.recapWords(three))
    }
}
