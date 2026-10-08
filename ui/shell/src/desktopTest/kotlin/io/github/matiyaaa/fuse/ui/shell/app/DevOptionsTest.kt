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
    fun aRehearsalIsAnExplicitEphemeralSession() {
        val dev = DevOptions()
        assertFalse(dev.rehearsalOpen)
        dev.rehearsalOpen = true
        assertTrue(dev.rehearsalOpen)
        dev.rehearsalOpen = false
        assertFalse(dev.rehearsalOpen)
    }
}
