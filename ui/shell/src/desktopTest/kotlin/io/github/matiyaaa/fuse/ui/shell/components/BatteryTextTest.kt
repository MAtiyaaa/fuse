package io.github.matiyaaa.fuse.ui.shell.components

import io.github.matiyaaa.fuse.model.SystemStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryTextTest {
    @Test
    fun durationsReadTheWayPeopleSayThem() {
        assertEquals("1 min", batteryDurationText(0))
        assertEquals("45 min", batteryDurationText(45))
        assertEquals("1 h", batteryDurationText(60))
        assertEquals("1 h 5 min", batteryDurationText(65))
        // Rounded to five minutes: an estimate is never minute-exact.
        assertEquals("3 h 25 min", batteryDurationText(203))
        assertEquals("3 h 20 min", batteryDurationText(201))
        assertEquals("4 h", batteryDurationText(239))
        assertEquals("10 h", batteryDurationText(615))
        assertEquals("11 h", batteryDurationText(640))
    }

    @Test
    fun statusSaysChargedFullInOrLeft() {
        assertEquals("Charged", batteryTimeText(SystemStatus(batteryPercent = 100, charging = true, batteryFull = true)))
        assertEquals("Full in 1 h 5 min", batteryTimeText(SystemStatus(batteryPercent = 60, charging = true, batteryMinutes = 65)))
        assertEquals("3 h 20 min left", batteryTimeText(SystemStatus(batteryPercent = 60, batteryMinutes = 200)))
        assertNull(batteryTimeText(SystemStatus(batteryPercent = 60, charging = true)))
        assertNull(batteryTimeText(SystemStatus(batteryPercent = 60)))
        assertNull(batteryTimeText(SystemStatus(batteryMinutes = 30)))
    }
}
