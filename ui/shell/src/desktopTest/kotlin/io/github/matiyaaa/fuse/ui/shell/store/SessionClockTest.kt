package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.ui.shell.store.impl.SessionClock
import kotlin.test.Test
import kotlin.test.assertEquals

/** Play time counts only the time a game was in front of the person. */
class SessionClockTest {
    private val min = 60_000L

    @Test
    fun `time with the screen off is not played`() {
        val c = SessionClock(startedAt = 0)
        c.screen(on = false, now = 10 * min)
        c.screen(on = true, now = 8 * 60 * min)
        // 10 minutes, then the night with the screen off, then 20 more.
        assertEquals(30 * min, c.endAt(8 * 60 * min + 20 * min))
    }

    @Test
    fun `a session that ends with the screen still off counts until it went off`() {
        val c = SessionClock(startedAt = 0)
        c.screen(on = false, now = 15 * min)
        assertEquals(15 * min, c.endAt(5 * 60 * min))
    }

    @Test
    fun `a computer asleep between two waits is not played`() {
        val c = SessionClock(startedAt = 0, tickMs = 30_000)
        c.tick(from = 0, now = 31_000) // a second late: an ordinary wait under a busy game
        c.tick(from = 31_000, now = 31_000 + 30_000 + 3 * 60 * min) // the lid closed for three hours
        val end = 31_000 + 30_000 + 3 * 60 * min + 5 * min
        assertEquals(end - 3 * 60 * min, c.endAt(end))
    }

    @Test
    fun `a session never ends before it began`() {
        val c = SessionClock(startedAt = 1_000)
        c.screen(on = false, now = 1_000)
        assertEquals(1_000, c.endAt(500_000))
    }

    @Test
    fun `a heartbeat ends a session Fuse did not see end where it was last seen`() {
        val c = SessionClock(startedAt = 0, tickMs = 30_000)
        c.tick(from = 0, now = 30_000)
        c.screen(on = false, now = 40_000)
        c.screen(on = true, now = 100_000)
        c.tick(from = 100_000, now = 130_000)
        val beat = c.heartbeat(session = 7, now = 130_000)
        assertEquals(7, beat.session)
        assertEquals(130_000 - 60_000, beat.endAt)
    }
}
