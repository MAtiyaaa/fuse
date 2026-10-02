package io.github.matiyaaa.fuse.ui.shell.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevOptionsTest {
    @Test
    fun fiveTapsTurnTheOptionsOn() {
        val dev = DevOptions()
        assertEquals(listOf(4, 3, 2, 1), List(4) { dev.tap() })
        assertFalse(dev.enabled)
        assertEquals(0, dev.tap())
        assertTrue(dev.enabled)
        // Further taps change nothing.
        assertEquals(0, dev.tap())
        assertTrue(dev.enabled)
    }

    @Test
    fun aRehearsalIsOnlyWhileItsPreferencesAreKept() {
        val dev = DevOptions()
        assertFalse(dev.rehearsing)
        dev.rehearsalPrefs = io.github.matiyaaa.fuse.ui.shell.store.UiPrefs()
        assertTrue(dev.rehearsing)
        dev.rehearsalPrefs = null
        assertFalse(dev.rehearsing)
    }
}
