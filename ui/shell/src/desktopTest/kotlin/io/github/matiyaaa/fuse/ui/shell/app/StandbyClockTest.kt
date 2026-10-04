package io.github.matiyaaa.fuse.ui.shell.app

import kotlin.test.Test
import kotlin.test.assertEquals

class StandbyClockTest {
    private val limit = 5 * 60_000L
    private val check = StandbyClock.CHECK_MS

    @Test
    fun idleInFrontOfFuseBringsStandby() {
        val now = 1_000_000L
        assertEquals(StandbyClock.Verdict.DUE, StandbyClock.check(now, now - check, now - limit, 0, limit))
        assertEquals(StandbyClock.Verdict.WAIT, StandbyClock.check(now, now - check, now - limit + 1, 0, limit))
    }

    @Test
    fun aClosedLidIsNotIdleTime() {
        // An hour asleep with the lid closed: the first check after waking starts the wait over.
        val now = 10_000_000L
        assertEquals(StandbyClock.Verdict.SLEPT, StandbyClock.check(now, now - 3_600_000L, now - 3_700_000L, 0, limit))
    }

    @Test
    fun comingBackCountsAsActivity() {
        val now = 10_000_000L
        assertEquals(StandbyClock.Verdict.WAIT, StandbyClock.check(now, now - check, now - 3_600_000L, now - 1_000, limit))
    }
}
