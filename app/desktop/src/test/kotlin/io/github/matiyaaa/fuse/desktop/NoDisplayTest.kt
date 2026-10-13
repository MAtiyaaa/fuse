package io.github.matiyaaa.fuse.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoDisplayTest {
    @Test
    fun linuxWithoutAnyDisplayIsSaidPlainly() {
        val why = NoDisplay.why(env = { null }, headless = { false }, os = "Linux")
        assertNotNull(why)
        assertTrue("screen" in NoDisplay.message(why))
    }

    @Test
    fun headlessJavaIsNoDisplay() {
        assertNotNull(NoDisplay.why(env = { if (it == "DISPLAY") ":0" else null }, headless = { true }, os = "Linux"))
        assertNotNull(NoDisplay.why(env = { null }, headless = { true }, os = "Windows 11"))
    }

    @Test
    fun theToolkitsErrorsAreRecognised() {
        assertTrue(NoDisplay.isDisplayError(java.awt.AWTError("Can't connect to X11 window server using ':9' as the value of the DISPLAY variable.")))
        assertTrue(NoDisplay.isDisplayError(RuntimeException("wrapped", java.awt.HeadlessException())))
        assertFalse(NoDisplay.isDisplayError(IllegalStateException("something else")))
    }

    @Test
    fun aWaylandSessionIsADisplay() {
        val why = NoDisplay.why(env = { if (it == "WAYLAND_DISPLAY") "wayland-0" else null }, headless = { false }, os = "Linux")
        assertFalse(why?.startsWith("no display") == true)
    }
}
