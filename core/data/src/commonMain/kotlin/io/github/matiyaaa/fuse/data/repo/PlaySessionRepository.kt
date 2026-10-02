package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.matiyaaa.fuse.data.DAY_MS
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.Play_session
import io.github.matiyaaa.fuse.data.enumOr
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.lastInsertId
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlaySession
import io.github.matiyaaa.fuse.model.PlaySessionSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Play time on one day, for the small chart. [dayStart] is local midnight as epoch millis. */
data class DailyPlaytime(val dayStart: Long, val seconds: Long)

/** Where the time went: today, this week, this month, per day, per game this month, per system ever. */
data class PlayReport(
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
    val monthSeconds: Long = 0,
    /** Seconds per local day, oldest first, ending today. */
    val days: List<DailyPlaytime> = emptyList(),
    /** Games by time played since the month began, most first. */
    val monthGames: List<Pair<GameId, Long>> = emptyList(),
    /** Every second per system, tracked and imported, most first. */
    val platforms: List<Pair<String, Long>> = emptyList(),
)

/** Library-wide play time. Imported time is kept apart from what Fuse observed. */
data class PlaytimeTotals(val trackedSeconds: Long, val importedSeconds: Long) {
    val totalSeconds: Long get() = trackedSeconds + importedSeconds
}

/**
 * Observed play sessions and the play-time columns they feed.
 *
 * Fuse only records what it saw: [start] when it handed a game to an emulator, [end] when the user
 * came back. A session left open (Fuse was killed while the game ran) is never given an invented
 * duration: the caller either closes it with a real end time it knows ([resumeOpen] on the next
 * return to Fuse, bounded by a maximum plausible length) or it is discarded ([discardOpen], and
 * implicitly by the next [start]). Open sessions never count towards any total.
 */
class PlaySessionRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val q get() = db.playSessionQueries

    /**
     * Opens a session and marks the game as just played. Any other open session is discarded first
     * (its end is unknown). Returns the new session id.
     */
    suspend fun start(
        gameId: GameId,
        emulatorId: EmulatorId?,
        now: Long,
        source: PlaySessionSource = PlaySessionSource.FUSE_LAUNCH,
    ): Long = withContext(dispatcher) {
        db.transactionWithResult {
            q.deleteOpen()
            q.insert(gameId.value, emulatorId?.value, now, null, source.name)
            val id = db.lastInsertId()
            db.gameQueries.markPlayStarted(now, gameId.value)
            id
        }
    }

    /**
     * Closes session [sessionId] at [now] and adds its duration to the game's tracked time, session
     * count and last played date, in one transaction. Returns the closed session, or null when it
     * does not exist or was already closed.
     */
    suspend fun end(sessionId: Long, now: Long): PlaySession? = withContext(dispatcher) {
        db.transactionWithResult { endLocked(sessionId, now) }
    }

    /**
     * On returning to Fuse after it may have been killed: closes the open session at [now] when it
     * is no longer than [maxDurationMs], otherwise discards it. Returns the closed session or null.
     */
    suspend fun resumeOpen(now: Long, maxDurationMs: Long = 12 * 3_600_000L): PlaySession? = withContext(dispatcher) {
        db.transactionWithResult {
            val open = q.selectOpen().executeAsOneOrNull() ?: return@transactionWithResult null
            if (now - open.started_at in 0..maxDurationMs) {
                endLocked(open.id, now)
            } else {
                q.deleteById(open.id)
                null
            }
        }
    }

    /** Drops open sessions without counting them. Returns how many were dropped. */
    suspend fun discardOpen(): Int = withContext(dispatcher) { q.deleteOpen().value.toInt() }

    /**
     * Sets a game's imported play time (replacing any earlier import, so re-importing is safe) and
     * where it came from. [lastPlayedAt] only ever moves the game's last played date forward.
     */
    suspend fun importPlaytime(gameId: GameId, seconds: Long, source: String, lastPlayedAt: Long? = null) =
        withContext(dispatcher) {
            db.gameQueries.setImported(
                seconds = seconds.coerceAtLeast(0),
                source = source,
                lastPlayedAt = lastPlayedAt,
                now = clock(),
                id = gameId.value,
            )
            Unit
        }

    /** The session currently open (the game being played), if any. */
    fun openSession(): Flow<PlaySession?> =
        q.selectOpen().asFlow().mapToOneOrNull(dispatcher).map { it?.toModel() }.flowOn(dispatcher)

    fun sessions(gameId: GameId): Flow<List<PlaySession>> =
        q.byGame(gameId.value).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }.flowOn(dispatcher)

    /** Library-wide totals (tracked and imported kept apart). */
    fun totalSeconds(): Flow<PlaytimeTotals> = db.gameQueries.playtimeTotals().asFlow().mapToOne(dispatcher)
        .map { PlaytimeTotals(it.tracked, it.imported) }
        .flowOn(dispatcher)

    /** Observed seconds played since [epochMs] (for example the start of this week). */
    fun secondsSince(epochMs: Long): Flow<Long> = q.endedAfter(epochMs).asFlow().mapToList(dispatcher)
        .map { rows -> rows.sumOf { (it.ended_at - maxOf(it.started_at, epochMs)).coerceAtLeast(0) } / 1000 }
        .flowOn(dispatcher)

    /** Games by total play time, highest first. */
    fun mostPlayed(limit: Int = 10): Flow<List<GameSummary>> =
        db.gameQueries.mostPlayed(limit.toLong()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toSummary() } }
            .flowOn(dispatcher)

    /**
     * Observed seconds per local day for the last [days] days including today, oldest first.
     * Sessions crossing midnight are split. [utcOffsetMs] is the local zone's offset from UTC.
     * The window is fixed from the clock when the flow is created.
     */
    fun dailyTotals(days: Int = 7, utcOffsetMs: Long = 0): Flow<List<DailyPlaytime>> {
        require(days > 0)
        val now = clock()
        val todayStart = (now + utcOffsetMs).floorDiv(DAY_MS) * DAY_MS - utcOffsetMs
        val windowStart = todayStart - (days - 1) * DAY_MS
        return q.endedAfter(windowStart).asFlow().mapToList(dispatcher).map { rows ->
            val buckets = LongArray(days)
            for (row in rows) {
                var from = maxOf(row.started_at, windowStart)
                val to = row.ended_at
                while (from < to) {
                    val index = ((from - windowStart) / DAY_MS).toInt()
                    if (index >= days) break
                    val dayEnd = windowStart + (index + 1) * DAY_MS
                    val until = minOf(to, dayEnd)
                    buckets[index] += until - from
                    from = until
                }
            }
            List(days) { DailyPlaytime(windowStart + it * DAY_MS, buckets[it] / 1000) }
        }.flowOn(dispatcher)
    }

    /**
     * The play report for windows starting at [todayStart], [weekStart] and [monthStart] (local
     * midnights the caller works out), with [days] daily totals ending today. Only observed time
     * counts toward the windows; a session crossing a boundary counts only its part inside.
     */
    fun report(todayStart: Long, weekStart: Long, monthStart: Long, days: Int = 30): Flow<PlayReport> {
        require(days > 0)
        val windowStart = todayStart - (days - 1) * DAY_MS
        val since = minOf(windowStart, weekStart, monthStart)
        return q.endedAfterWithGame(since).asFlow().mapToList(dispatcher).map { rows ->
            fun overlap(from: Long, to: Long, start: Long) = (to - maxOf(from, start)).coerceAtLeast(0)
            val buckets = LongArray(days)
            val perGame = HashMap<Long, Long>()
            var today = 0L
            var week = 0L
            var month = 0L
            for (r in rows) {
                val end = r.ended_at ?: continue
                today += overlap(r.started_at, end, todayStart)
                week += overlap(r.started_at, end, weekStart)
                val m = overlap(r.started_at, end, monthStart)
                month += m
                if (m > 0) perGame[r.game_id] = (perGame[r.game_id] ?: 0) + m
                var from = maxOf(r.started_at, windowStart)
                while (from < end) {
                    val index = ((from - windowStart) / DAY_MS).toInt()
                    if (index >= days) break
                    val until = minOf(end, windowStart + (index + 1) * DAY_MS)
                    buckets[index] += until - from
                    from = until
                }
            }
            val platforms = db.playSessionQueries.secondsByPlatform().executeAsList().map { it.platform_id to (it.seconds ?: 0L) }
            PlayReport(
                todaySeconds = today / 1000,
                weekSeconds = week / 1000,
                monthSeconds = month / 1000,
                days = List(days) { DailyPlaytime(windowStart + it * DAY_MS, buckets[it] / 1000) },
                monthGames = perGame.entries.sortedByDescending { it.value }.map { GameId(it.key) to it.value / 1000 },
                platforms = platforms,
            )
        }.flowOn(dispatcher)
    }

    private fun endLocked(sessionId: Long, now: Long): PlaySession? {
        val row = q.selectById(sessionId).executeAsOneOrNull() ?: return null
        if (row.ended_at != null) return null
        val endedAt = maxOf(now, row.started_at)
        q.setEnded(endedAt, sessionId)
        db.gameQueries.addTrackedSession(seconds = (endedAt - row.started_at) / 1000, endedAt = endedAt, id = row.game_id)
        return row.copy(ended_at = endedAt).toModel()
    }
}

private fun Play_session.toModel() = PlaySession(
    id = id,
    gameId = GameId(game_id),
    emulatorId = emulator_id?.let(::EmulatorId),
    startedAt = started_at,
    endedAt = ended_at,
    source = enumOr(source, PlaySessionSource.FUSE_LAUNCH),
)
