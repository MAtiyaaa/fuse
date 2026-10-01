package io.github.matiyaaa.fuse.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRelaunchTest {
    private val minute = 60_000L
    private val armed = 1_800_000_000_000L

    @Test
    fun fusesOwnUpdateReopensIt() {
        assertTrue(UpdateRelaunch.shouldRelaunch(armed, lastUpdateTime = armed + 2 * minute, now = armed + 3 * minute, isHome = false))
    }

    @Test
    fun anOldOrUnmarkedUpdateDoesNot() {
        // Not marked: someone else updated Fuse.
        assertFalse(UpdateRelaunch.shouldRelaunch(0L, armed, armed + minute, isHome = false))
        // Marked, but the install didn't happen after the mark.
        assertFalse(UpdateRelaunch.shouldRelaunch(armed, armed - minute, armed + minute, isHome = false))
        // Marked long ago (the confirmation was left open for an hour).
        assertFalse(UpdateRelaunch.shouldRelaunch(armed, armed + 59 * minute, armed + 60 * minute, isHome = false))
    }

    @Test
    fun theHomeAppAlwaysComesBack() {
        assertTrue(UpdateRelaunch.shouldRelaunch(0L, armed, armed + minute, isHome = true))
    }
}
