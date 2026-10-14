package io.github.matiyaaa.fuse.desktop.system

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Settings says when a TV is sent reduced colour, and only then. */
class HdmiColourTest {
    private fun note(connector: String?, w: Int, h: Int, hz: Double, driver: String? = "AMD Radeon Graphics (radeonsi)", linux: Boolean = true) =
        HdmiColour.note(connector, w, h, hz, driver, linux)

    @Test
    fun fourKAt120OverHdmiOnLinux() {
        assertNotNull(note("HDMI-A-1", 3840, 2160, 120.0))
        assertNotNull(note("HDMI-A-1", 4096, 2160, 100.0))
    }

    @Test
    fun notAt60NorBelow4KNorOverDisplayPort() {
        assertNull(note("HDMI-A-1", 3840, 2160, 60.0))
        assertNull(note("HDMI-A-1", 2560, 1440, 144.0))
        assertNull(note("DP-1", 3840, 2160, 120.0))
        assertNull(note(null, 3840, 2160, 120.0))
    }

    @Test
    fun notWithNvidiasDriverNorOffLinux() {
        assertNull(note("HDMI-A-1", 3840, 2160, 120.0, driver = "NVIDIA GeForce RTX 4070"))
        assertNull(note("HDMI-A-1", 3840, 2160, 120.0, linux = false))
    }
}
