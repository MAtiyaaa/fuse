package io.github.matiyaaa.fuse.ui.shell.quick

import io.github.matiyaaa.fuse.model.DualScreenMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** The quick menu's Second screen tile: Off, On and Companion, in turn. */
class SecondScreenTileTest {
    @Test
    fun cyclesOffOnAndCompanion() {
        assertEquals(DualScreenMode.LIBRARY_COMPANION, nextSecondScreen(DualScreenMode.OFF))
        assertEquals(DualScreenMode.GAME_COMPANION, nextSecondScreen(DualScreenMode.LIBRARY_COMPANION))
        assertEquals(DualScreenMode.OFF, nextSecondScreen(DualScreenMode.GAME_COMPANION))
        // Playing on the second screen is a Settings choice; the tile leaves it for Off.
        assertEquals(DualScreenMode.OFF, nextSecondScreen(DualScreenMode.REVERSE))
    }

    @Test
    fun saysWhichIsOn() {
        assertEquals("Off", secondScreenText(DualScreenMode.OFF))
        assertEquals("On", secondScreenText(DualScreenMode.LIBRARY_COMPANION))
        assertEquals("Companion", secondScreenText(DualScreenMode.GAME_COMPANION))
    }
}
