package io.github.matiyaaa.fuse.desktop.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** gamescope is asked to compose Fuse in Game Mode only, unless turned off. */
class GamescopeCompositeTest {
    @Test
    fun gameModeOnly() {
        assertTrue(GamescopeComposite.wanted(DisplaySession.STEAM_GAME_MODE) { null })
        for (s in listOf(DisplaySession.X11, DisplaySession.WAYLAND, DisplaySession.GAMESCOPE_NESTED, DisplaySession.UNKNOWN)) {
            assertFalse(GamescopeComposite.wanted(s) { null }, s.name)
        }
    }

    @Test
    fun aLaunchOptionTurnsItOff() {
        assertFalse(GamescopeComposite.wanted(DisplaySession.STEAM_GAME_MODE) { if (it == "FUSE_COMPOSITE") "off" else null })
        assertTrue(GamescopeComposite.wanted(DisplaySession.STEAM_GAME_MODE) { if (it == "FUSE_COMPOSITE") "on" else null })
    }

    @Test
    fun theRootPropertyIsSetAndClearedOnARealXServer() {
        // Only where an X server is at hand (Xvfb in CI's release job, a desktop): the property round trip.
        if (System.getenv("DISPLAY").isNullOrBlank() || java.awt.GraphicsEnvironment.isHeadless()) return
        val p = ProcessBuilder("xprop", "-root", "GAMESCOPE_COMPOSITE_FORCE").redirectErrorStream(true)
        if (runCatching { p.start().waitFor() }.isFailure) return
        GamescopeComposite.enable(DisplaySession.STEAM_GAME_MODE)
        java.awt.Toolkit.getDefaultToolkit().sync()
        val on = p.start().inputStream.bufferedReader().readText()
        GamescopeComposite.hidden()
        java.awt.Toolkit.getDefaultToolkit().sync()
        val off = p.start().inputStream.bufferedReader().readText()
        assertTrue("= 1" in on, on)
        assertTrue("not found" in off || "no such atom" in off, off)
    }
}
