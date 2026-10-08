package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.shell.store.DisplaySession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DisplaySessionTest {
    @Test fun attachedAndReattachedDisplaysObserveTheSameStandby() {
        val session = DisplaySession()
        session.enterStandby()
        val main = session.standby
        val companion = session.standby
        assertTrue(main.value)
        assertTrue(companion.value)
        session.wake(1000L)
        assertFalse(main.value)
        assertFalse(companion.value)
        assertEquals(1000L, session.lastWakeAt)
        assertEquals(StandbyClock.Verdict.WAIT, StandbyClock.check(1001L, 1000L, 0L, session.lastWakeAt, 120000L))
    }
    @Test fun separateStoresAndRehearsalsNeverShareStandby() {
        val real = DisplaySession()
        val rehearsal = DisplaySession()
        real.enterStandby()
        assertFalse(rehearsal.standby.value)
        rehearsal.wake(5L)
        assertTrue(real.standby.value)
    }
}
