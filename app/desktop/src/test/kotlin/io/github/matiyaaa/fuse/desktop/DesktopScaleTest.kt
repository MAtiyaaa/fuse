package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.system.DesktopScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopScaleTest {
    @Test
    fun fractionalDesktopScalesAreDrawnByFuseWholeOnesByJava() {
        assertEquals(1.25f, DesktopScale.extra(120.0, 1.0))
        assertEquals(1.5f, DesktopScale.extra(144.0, 1.0))
        // GNOME at 200%: Java already draws 2x, nothing on top.
        assertEquals(1f, DesktopScale.extra(192.0, 2.0))
        assertEquals(1f, DesktopScale.extra(96.0, 1.0))
        assertEquals(1f, DesktopScale.extra(null, 1.0))
        assertEquals(1f, DesktopScale.extra(98.0, 1.0), "rounding is not a request")
        assertEquals(1.25f, DesktopScale.extra(96.0, 1.0, override = "1.25"))
    }

    @Test
    fun readsXftDpiFromXrdb() {
        assertEquals(120.0, DesktopScale.parseXrdb("Xcursor.size:\t24\nXft.dpi:\t120\nXft.antialias:\t1\n"))
        assertNull(DesktopScale.parseXrdb("Xcursor.size:\t24\n"))
    }
}
