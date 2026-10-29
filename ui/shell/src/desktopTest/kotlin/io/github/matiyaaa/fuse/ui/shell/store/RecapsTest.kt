package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.repo.WindowSession
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.impl.Recaps
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Your Year's sums: a period's bars, days, longest session, streak, weekday and firsts, in local time. */
class RecapsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** Local midnight of a date, at [offset] from UTC. */
    private fun at(y: Int, m: Int, d: Int, hours: Double = 0.0, offset: Long = 0) =
        LocalDate(y, m, d).toEpochDays().toLong() * day - offset + (hours * hour).toLong()

    @Test
    fun aMonthIsAddedUpDayByDayInLocalTime() {
        val offset = 2 * hour
        val a = GameId(1)
        val b = GameId(2)
        val sessions = listOf(
            // Saturday 3 October, two hours in the evening.
            WindowSession(a, "gba", at(2026, 10, 3, 19.0, offset), at(2026, 10, 3, 21.0, offset)),
            // From before midnight into Sunday, an hour on each side.
            WindowSession(a, "gba", at(2026, 10, 3, 23.0, offset), at(2026, 10, 4, 1.0, offset)),
            WindowSession(b, "ps2", at(2026, 10, 5, 20.0, offset), at(2026, 10, 5, 20.5, offset)),
            // Started in September: only its October part counts.
            WindowSession(b, "ps2", at(2026, 9, 30, 23.0, offset), at(2026, 10, 1, 0.5, offset)),
        )
        val edges = Recaps.edges(2026, 10, offset)
        assertEquals(32, edges.size)
        val t = Recaps.of(sessions, mapOf(a to at(2026, 10, 3, 19.0, offset), b to at(2026, 8, 1)), edges, offset)
        assertEquals(((2 + 2 + 0.5 + 0.5) * 3600).toLong(), t.seconds)
        assertEquals(4, t.sessions)
        // 1, 3, 4 and 5 October.
        assertEquals(4, t.days)
        assertEquals(31, t.bars.size)
        assertEquals(1800L, t.bars[0])
        assertEquals(3 * 3600L, t.bars[2])
        assertEquals(3600L, t.bars[3])
        assertEquals(listOf(a to 4 * 3600L, b to 3600L), t.games)
        assertEquals(listOf("gba" to 4 * 3600L, "ps2" to 3600L), t.systems)
        // The longest is the first evening's (the crossing one is as long, but came later).
        assertEquals(a, t.longest?.first)
        assertEquals(2 * 3600L, t.longest?.second)
        // 3, 4 and 5 October in a row.
        assertEquals(3, t.streak?.first)
        assertEquals(LocalDate(2026, 10, 3).toEpochDays().toLong(), t.streak?.second)
        // Saturday (6), in the evening.
        assertEquals(6, t.weekday)
        assertEquals("evenings", Recaps.PARTS[t.partOfDay!!])
        // Only the game first played in October is new to you.
        assertEquals(listOf(a), t.firsts)
    }

    @Test
    fun aYearHasTwelveBarsAndNothingPlayedIsNothing() {
        val edges = Recaps.edges(2025, null, 0)
        assertEquals(13, edges.size)
        val empty = Recaps.of(emptyList(), emptyMap(), edges, 0)
        assertEquals(0L, empty.seconds)
        assertEquals(List(12) { 0L }, empty.bars)
        assertEquals(null, empty.streak)
        assertEquals(null, empty.weekday)
        val g = GameId(9)
        val t = Recaps.of(listOf(WindowSession(g, "snes", at(2025, 3, 10, 9.0), at(2025, 3, 10, 10.0))), emptyMap(), edges, 0)
        assertEquals(3600L, t.bars[2])
        assertEquals("mornings", Recaps.PARTS[t.partOfDay!!])
        // One day is no streak.
        assertEquals(null, t.streak)
    }
}
