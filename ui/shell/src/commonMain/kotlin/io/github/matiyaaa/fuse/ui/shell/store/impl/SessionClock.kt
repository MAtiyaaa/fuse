package io.github.matiyaaa.fuse.ui.shell.store.impl

import kotlinx.serialization.Serializable

/**
 * The time a game was really in front of the person, from its start to its end, less the time
 * nobody could have been playing it:
 *
 * - **The screen off** (Android): a handheld put to sleep with its game open, its lid closed or its
 *   power button pressed. From the moment the screen goes off to the moment it comes back.
 * - **The computer asleep**: a laptop's lid closed suspends Fuse with everything else, so nothing
 *   tells it; it sees its own clock jump instead. A wait of [tickMs] that took far longer than that
 *   was the computer sleeping, and the difference is taken off.
 *
 * Before this, a game left open overnight counted every hour of the night as played.
 */
internal class SessionClock(val startedAt: Long, private val tickMs: Long = TICK_MS) {
    private var away = 0L
    private var screenOffAt: Long? = null

    /** The last moment Fuse saw the session going, for a session Fuse didn't see end. */
    var lastAlive: Long = startedAt
        private set

    /** The screen went on or off at [now]. */
    fun screen(on: Boolean, now: Long) {
        val off = screenOffAt
        if (on && off != null) {
            away += (now - off).coerceAtLeast(0)
            screenOffAt = null
        } else if (!on && off == null) {
            screenOffAt = now
        }
        if (on) lastAlive = maxOf(lastAlive, now)
    }

    /**
     * A wait of [tickMs] began at [from] and ended at [now]. Waits run late by a few seconds under a
     * heavy game; one late by more than [SLEEP_SLACK_MS] was the computer asleep.
     */
    fun tick(from: Long, now: Long) {
        if (screenOffAt == null) {
            val late = now - from - tickMs
            if (late > SLEEP_SLACK_MS) away += late
            lastAlive = maxOf(lastAlive, now)
        }
    }

    /** Time not played so far, at [now]. */
    fun awayMs(now: Long): Long = away + (screenOffAt?.let { (now - it).coerceAtLeast(0) } ?: 0L)

    /** When the session ends, counted as played time: [now] less the time away, never before it began. */
    fun endAt(now: Long): Long = (now - awayMs(now)).coerceAtLeast(startedAt)

    /** What's kept while it runs, for [Heartbeat]. */
    fun heartbeat(session: Long, now: Long): Heartbeat = Heartbeat(session, startedAt, lastAlive, awayMs(now))

    /**
     * What Fuse keeps of a running session every minute or so, so a session Fuse didn't see end (it
     * was closed or stopped while the game ran) ends where Fuse last saw it going, not at the next
     * start of Fuse hours later.
     */
    @Serializable
    data class Heartbeat(val session: Long, val startedAt: Long, val alive: Long, val awayMs: Long) {
        val endAt: Long get() = (alive - awayMs).coerceAtLeast(startedAt)
    }

    companion object {
        const val TICK_MS = 30_000L
        const val SLEEP_SLACK_MS = 45_000L
    }
}
