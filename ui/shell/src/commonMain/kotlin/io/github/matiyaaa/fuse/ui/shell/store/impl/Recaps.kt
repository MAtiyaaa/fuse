package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.WindowSession
import io.github.matiyaaa.fuse.model.GameId
import kotlinx.datetime.LocalDate

/**
 * A month or a year of play added up for Your Year, from the sessions Fuse saw here. Time is local:
 * [offset] is the UTC offset, so a day starts at local midnight. A session reaching across the
 * period's edge, a bar's or a day's counts only its part inside.
 */
internal object Recaps {
    /** What a period holds, before any game is turned into a card. */
    data class Totals(
        val seconds: Long,
        val sessions: Int,
        /** Days anything was played. */
        val days: Int,
        /** Seconds in each of the period's bars (its months, or its days), in order. */
        val bars: List<Long>,
        /** Each game's time, most first. */
        val games: List<Pair<GameId, Long>>,
        /** Each system's time, most first. */
        val systems: List<Pair<String, Long>>,
        /** The longest session (its part inside), its game and the local day it started. */
        val longest: Triple<GameId, Long, Long>?,
        /** The most days in a row anything was played, and the first of them (a local day number). */
        val streak: Pair<Int, Long>?,
        /** The weekday played most, 1 for Monday to 7 for Sunday. */
        val weekday: Int?,
        /** The part of the day played most ([PARTS]). */
        val partOfDay: Int?,
        /** Games first played here in the period, the earliest first. */
        val firsts: List<GameId>,
    )

    /** Morning (5 to 12), afternoon (12 to 17), evening (17 to 22) and late at night (22 to 5). */
    val PARTS = listOf("mornings", "afternoons", "evenings", "late at night")

    private fun partOf(hour: Int): Int = when (hour) {
        in 5 until 12 -> 0
        in 12 until 17 -> 1
        in 17 until 22 -> 2
        else -> 3
    }

    /** Local midnight of [year]-[month]-1 as epoch millis. */
    fun monthStart(year: Int, month: Int, offset: Long): Long = LocalDate(year, month, 1).toEpochDays().toLong() * TimeWords.DAY_MS - offset

    /** Where a year's or a month's bars begin, and the period's end (the last entry). */
    fun edges(year: Int, month: Int?, offset: Long): List<Long> = if (month == null) {
        (1..12).map { monthStart(year, it, offset) } + monthStart(year + 1, 1, offset)
    } else {
        val first = monthStart(year, month, offset)
        val next = if (month == 12) monthStart(year + 1, 1, offset) else monthStart(year, month + 1, offset)
        val days = ((next - first) / TimeWords.DAY_MS).toInt()
        (0..days).map { first + it * TimeWords.DAY_MS }
    }

    /**
     * The period from [edges]' first to its last, its bars between each pair: from [sessions] (any
     * that ran in it) and [firstPlayed], when each game was first seen played here.
     */
    fun of(sessions: List<WindowSession>, firstPlayed: Map<GameId, Long>, edges: List<Long>, offset: Long): Totals {
        val from = edges.first()
        val until = edges.last()
        val bars = LongArray(edges.size - 1)
        val perGame = HashMap<GameId, Long>()
        val perSystem = HashMap<String, Long>()
        val perDay = HashMap<Long, Long>()
        val perWeekday = LongArray(7)
        val perPart = LongArray(PARTS.size)
        var total = 0L
        var count = 0
        var longest: Triple<GameId, Long, Long>? = null
        for (s in sessions) {
            val start = maxOf(s.start, from)
            val end = minOf(s.end, until)
            if (end <= start) continue
            count++
            val ms = end - start
            total += ms
            perGame[s.game] = (perGame[s.game] ?: 0) + ms
            perSystem[s.platform] = (perSystem[s.platform] ?: 0) + ms
            if (longest == null || ms > longest.second) longest = Triple(s.game, ms, (start + offset).floorDiv(TimeWords.DAY_MS))
            // Bars, days, weekdays and parts of the day, each a piece at a time.
            var at = start
            while (at < end) {
                val local = at + offset
                val day = local.floorDiv(TimeWords.DAY_MS)
                val hour = ((local - day * TimeWords.DAY_MS) / 3_600_000L).toInt()
                // The next edge of anything counted: the hour (parts change on the hour), the day or a bar.
                val nextHour = (local.floorDiv(3_600_000L) + 1) * 3_600_000L - offset
                val bar = edges.indexOfLast { it <= at }.coerceIn(0, bars.size - 1)
                val piece = minOf(end, nextHour, edges[bar + 1]) - at
                bars[bar] += piece
                perDay[day] = (perDay[day] ?: 0) + piece
                // 1970-01-01 was a Thursday: day 0 is weekday index 3 with Monday as 0.
                perWeekday[(((day + 3) % 7 + 7) % 7).toInt()] += piece
                perPart[partOf(hour)] += piece
                at += piece
            }
        }
        val played = perDay.filterValues { it >= MIN_DAY_MS }.keys.sorted()
        var best: Pair<Int, Long>? = null
        var run = 0
        var runStart = 0L
        for ((i, d) in played.withIndex()) {
            if (i > 0 && d == played[i - 1] + 1) run++ else { run = 1; runStart = d }
            if (best == null || run > best.first) best = run to runStart
        }
        return Totals(
            seconds = total / 1000,
            sessions = count,
            days = played.size,
            bars = bars.map { it / 1000 },
            games = perGame.entries.sortedByDescending { it.value }.map { it.key to it.value / 1000 },
            systems = perSystem.entries.sortedByDescending { it.value }.map { it.key to it.value / 1000 },
            longest = longest?.let { Triple(it.first, it.second / 1000, it.third) },
            streak = best?.takeIf { it.first > 1 },
            weekday = perWeekday.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index?.plus(1),
            partOfDay = perPart.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index,
            firsts = firstPlayed.filter { (game, at) -> at in from until until && game in perGame }.entries.sortedBy { it.value }.map { it.key },
        )
    }

    /** A day counts toward days played and streaks with at least a minute of play. */
    private const val MIN_DAY_MS = 60_000L

    val WEEKDAYS = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")
}
