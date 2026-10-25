package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.repo.DailyPlaytime
import io.github.matiyaaa.fuse.data.repo.PlaytimeTotals
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaySessionRepositoryTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private suspend fun TestDb.twoGames(): Pair<GameId, GameId> {
        data.indexer.apply(report(folder("/roms/gba", listOf(scanned("/roms/gba/A.gba"), scanned("/roms/gba/B.gba")))), 1_000, testCleaner)
        return data.games.idByPath("/roms/gba/A.gba")!! to data.games.idByPath("/roms/gba/B.gba")!!
    }

    @Test
    fun sessionsFeedTotalsAndThisWeek() = runBlocking {
        TestDb().use { t ->
            val (a, b) = t.twoGames()
            val sessions = t.data.playSessions
            val monday = TestDb.BASE_TIME - 12 * hour // 2026-09-28 00:00 UTC

            val s1 = sessions.start(a, EmulatorId("mgba"), now = monday - 2 * hour)
            assertEquals(a, sessions.openSession().first()?.gameId)
            sessions.end(s1, now = monday + 30 * minute) // 2 h 30 min, 30 min of it this week
            assertNull(sessions.openSession().first())
            assertNull(sessions.end(s1, now = monday + hour), "a closed session cannot be closed twice")

            val s2 = sessions.start(b, null, now = monday + 2 * hour)
            sessions.end(s2, now = monday + 3 * hour)

            assertEquals(PlaytimeTotals(9_000 + 3_600, 0), sessions.totalSeconds().first())
            assertEquals(30 * 60L + 3_600, sessions.secondsSince(monday).first())
            assertEquals(listOf(a, b), sessions.mostPlayed(5).first().map { it.id })

            sessions.importPlaytime(b, 20_000, "Steam", lastPlayedAt = monday - 30 * 24 * hour)
            sessions.importPlaytime(b, 20_000, "Steam") // re-import replaces, never adds
            assertEquals(PlaytimeTotals(12_600, 20_000), sessions.totalSeconds().first())
            assertEquals(30 * 60L + 3_600, sessions.secondsSince(monday).first(), "imported time has no dates")
            assertEquals(listOf(b, a), sessions.mostPlayed(5).first().map { it.id })

            val game = t.data.games.get(a)!!
            assertEquals(monday + 30 * minute, game.play.lastPlayedAt)
            assertEquals(1, game.play.sessions)
            assertEquals(monday + 3 * hour, t.data.games.get(b)!!.play.lastPlayedAt, "an older import never moves last played back")
        }
    }

    @Test
    fun aWeekBesideLastWeekUpToTheSameMoment(): Unit = runBlocking {
        TestDb().use { t ->
            val (a, b) = t.twoGames()
            val sessions = t.data.playSessions
            val monday = TestDb.BASE_TIME - 12 * hour
            val day = 24 * hour
            // Last week: an hour on Monday, two on Friday.
            sessions.end(sessions.start(a, null, now = monday - 7 * day + 10 * hour), now = monday - 7 * day + 11 * hour)
            sessions.end(sessions.start(b, null, now = monday - 3 * day + 10 * hour), now = monday - 3 * day + 12 * hour)
            // This week: half an hour of A on Monday, two hours of B on Tuesday.
            sessions.end(sessions.start(a, null, now = monday + 9 * hour), now = monday + 9 * hour + 30 * minute)
            sessions.end(sessions.start(b, null, now = monday + day + 9 * hour), now = monday + day + 11 * hour)
            // Seen on Wednesday morning.
            val w = sessions.week(monday) { monday + 2 * day + 8 * hour }.first()
            assertEquals(2 * 3_600L + 1_800, w.seconds)
            assertEquals(3 * 3_600L, w.lastWeekSeconds)
            // By last Wednesday morning, only Monday's hour had been played.
            assertEquals(3_600L, w.lastWeekSoFar)
            assertEquals(2, w.sessions)
            assertEquals(listOf(b to 7_200L, a to 1_800L), w.games)
        }
    }

    @Test
    fun aSessionFromSteamCountsOnceAndLeavesAnOpenOneAlone(): Unit = runBlocking {
        TestDb().use { t ->
            val (a, b) = t.twoGames()
            val sessions = t.data.playSessions
            val start = TestDb.BASE_TIME - 2 * hour
            // A game is being played through Fuse right now; Steam's report of another is counted beside it.
            val open = sessions.start(b, null, now = TestDb.BASE_TIME)
            assertEquals(true, sessions.record(a, start, start + 45 * minute, io.github.matiyaaa.fuse.model.PlaySessionSource.STEAM))
            assertEquals(b, sessions.openSession().first()?.gameId)
            // Reported again: not counted twice.
            assertEquals(false, sessions.record(a, start, start + 45 * minute, io.github.matiyaaa.fuse.model.PlaySessionSource.STEAM))
            assertEquals(false, sessions.record(a, start + hour, start + hour, io.github.matiyaaa.fuse.model.PlaySessionSource.STEAM))
            val game = t.data.games.get(a)!!
            assertEquals(45 * 60L, game.play.trackedSeconds)
            assertEquals(1, game.play.sessions)
            assertEquals(start + 45 * minute, game.play.lastPlayedAt)
            assertEquals("STEAM", sessions.sessions(a).first().single().source.name)
            assertNotNull(sessions.end(open, TestDb.BASE_TIME + minute))
        }
    }

    @Test
    fun theReportSplitsTimeAtTodayTheWeekAndTheMonth() = runBlocking {
        TestDb().use { t ->
            val (a, b) = t.twoGames()
            val sessions = t.data.playSessions
            val monday = TestDb.BASE_TIME - 12 * hour // 2026-09-28 00:00 UTC
            val today = monday + 24 * hour // Tuesday
            val month = monday - 27 * 24 * hour // 2026-09-01

            // Across last Sunday's midnight: one hour before the week, one in it.
            sessions.end(sessions.start(a, null, now = monday - hour), now = monday + hour)
            // Today, twice on B.
            sessions.end(sessions.start(b, null, now = today + hour), now = today + 2 * hour)
            sessions.end(sessions.start(b, null, now = today + 3 * hour), now = today + 3 * hour + 30 * minute)
            // Last month only.
            sessions.end(sessions.start(a, null, now = month - 5 * hour), now = month - 4 * hour)

            val r = t.data.playSessions.report(today, monday, month, days = 3).first()
            assertEquals(5_400L, r.todaySeconds)
            assertEquals(3_600L + 5_400, r.weekSeconds)
            assertEquals(7_200L + 5_400, r.monthSeconds)
            assertEquals(listOf(3_600L, 3_600L, 5_400L), r.days.map { it.seconds })
            assertEquals(listOf(b to 5_400L, a to 7_200L).sortedByDescending { it.second }, r.monthGames)
            assertEquals(listOf("gba" to 7_200L + 5_400 + 3_600), r.platforms)
        }
    }

    @Test
    fun openSessionsNeverGetInventedDurations() = runBlocking {
        TestDb().use { t ->
            val (a, b) = t.twoGames()
            val sessions = t.data.playSessions
            val start = TestDb.BASE_TIME

            // Fuse was killed while A ran, then B was launched: A's session is dropped, not guessed.
            sessions.start(a, null, now = start)
            val sb = sessions.start(b, null, now = start + hour)
            assertEquals(sb, sessions.openSession().first()?.id)
            sessions.end(sb, now = start + 2 * hour)
            assertEquals(0, t.data.games.get(a)!!.play.trackedSeconds)
            assertEquals(0, t.data.games.get(a)!!.play.sessions)

            // Returning to Fuse after a plausible time closes it at the return time...
            sessions.start(a, null, now = start + 3 * hour)
            val resumed = sessions.resumeOpen(now = start + 4 * hour)
            assertNotNull(resumed)
            assertEquals(3_600, t.data.games.get(a)!!.play.trackedSeconds)

            // ...an implausibly long one is discarded.
            sessions.start(a, null, now = start + 5 * hour)
            assertNull(sessions.resumeOpen(now = start + 5 * hour + 13 * hour))
            assertNull(sessions.openSession().first())
            assertEquals(3_600, t.data.games.get(a)!!.play.trackedSeconds)

            sessions.start(b, null, now = start + 30 * hour)
            assertEquals(1, sessions.discardOpen())
            assertEquals(PlaytimeTotals(7_200, 0), sessions.totalSeconds().first())
            assertEquals(1, sessions.sessions(a).first().size)
        }
    }

    @Test
    fun dailyTotalsSplitAtLocalMidnight() = runBlocking {
        TestDb().use { t ->
            val (a, _) = t.twoGames()
            val sessions = t.data.playSessions
            val offset = 2 * hour // UTC+2
            val todayStart = TestDb.BASE_TIME - 12 * hour - offset // local midnight
            val s = sessions.start(a, null, now = todayStart - 30 * minute)
            sessions.end(s, now = todayStart + 45 * minute)
            val old = sessions.start(a, null, now = todayStart - 10 * 24 * hour)
            sessions.end(old, now = todayStart - 10 * 24 * hour + hour)

            val days = sessions.dailyTotals(days = 3, utcOffsetMs = offset).first()
            assertEquals(
                listOf(
                    DailyPlaytime(todayStart - 2 * 24 * hour, 0),
                    DailyPlaytime(todayStart - 24 * hour, 30 * 60),
                    DailyPlaytime(todayStart, 45 * 60),
                ),
                days,
            )
        }
    }
}
