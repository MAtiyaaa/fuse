package io.github.matiyaaa.fuse.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEstimatorTest {
    private val minute = 60_000L

    /** One percent every [every] minutes from [from], unplugged, screen on. Returns the last time. */
    private fun BatteryEstimator.drain(from: Int, drops: Int, every: Long, start: Long = 0L): Long {
        onScreen(true, start)
        onLevel(start, from, plugged = false)
        var t = start
        for (i in 1..drops) {
            t += every * minute
            onLevel(t, from - i, plugged = false)
        }
        return t
    }

    @Test
    fun nothingUntilTheLevelHasReallyMoved() {
        val e = BatteryEstimator()
        e.onScreen(true, 0)
        e.onLevel(0, 80, plugged = false)
        assertNull(e.estimate(minute))
        e.onLevel(3 * minute, 79, plugged = false)
        // One change only marks where the clock starts.
        assertNull(e.estimate(4 * minute))
    }

    @Test
    fun steadyDrainGivesLevelOverRate() {
        val e = BatteryEstimator()
        // 1% every 3 minutes, down to 77%: about 231 minutes left.
        val t = e.drain(from = 80, drops = 3, every = 3)
        val m = e.estimate(t)
        assertNotNull(m)
        assertEquals(231.0, m!!.toDouble(), 231 * 0.1)
    }

    @Test
    fun timeWithTheScreenOffDoesNotCount() {
        val e = BatteryEstimator()
        e.onScreen(true, 0)
        e.onLevel(0, 80, plugged = false)
        e.onLevel(3 * minute, 79, plugged = false)
        // Eight hours asleep, one more percent gone.
        e.onScreen(false, 4 * minute)
        e.onScreen(true, 4 * minute + 8 * 60 * minute)
        e.onLevel(4 * minute + 8 * 60 * minute + 2 * minute, 78, plugged = false)
        e.onLevel(4 * minute + 8 * 60 * minute + 5 * minute, 77, plugged = false)
        val m = e.estimate(4 * minute + 8 * 60 * minute + 5 * minute)!!
        // Two percent over six minutes on: about 3 min per percent, not two days.
        assertTrue("was $m", m in 200..260)
    }

    @Test
    fun anOverdueDropSlowsTheEstimate() {
        val e = BatteryEstimator()
        val t = e.drain(from = 80, drops = 4, every = 2)
        val now = e.estimate(t)!!
        // Twenty minutes without another drop: the battery is clearly lasting longer.
        val later = e.estimate(t + 20 * minute)!!
        assertTrue("$later should be well above $now", later > now * 2)
    }

    @Test
    fun pluggingInStartsOver() {
        val e = BatteryEstimator()
        val t = e.drain(from = 80, drops = 3, every = 3)
        e.onLevel(t + minute, 77, plugged = true)
        assertNull(e.estimate(t + 2 * minute))
    }

    @Test
    fun androidsOwnPredictionWins() {
        val e = BatteryEstimator()
        val t = e.drain(from = 80, drops = 3, every = 3)
        assertEquals(500, e.estimate(t, systemDischargeMinutes = 500))
        // Out of bounds, it is ignored.
        assertNotNull(e.estimate(t, systemDischargeMinutes = 100_000))
        assertTrue(e.estimate(t, systemDischargeMinutes = 100_000) != 100_000)
    }

    @Test
    fun theCurrentGivesAnEarlyEstimateInAnyUnitAndSign() {
        // 50% of a 6000 mAh battery (3000 mAh) at 1 A drawn: 180 minutes.
        val readings = listOf(
            Triple(3_000_000, -1_000_000, null), // microamp hours, microamps, negative while draining
            Triple(3_000_000, 1_000_000, null), // positive while draining
            Triple(3_000, -1_000, null), // milliamp hours and milliamps
            Triple(3_000_000, null, -1_000_000), // only the average
        )
        for ((counter, now, average) in readings) {
            val e = BatteryEstimator()
            e.onScreen(true, 0)
            e.onLevel(0, 50, plugged = false)
            e.onReadings(counter, now, average)
            assertEquals("$counter $now $average", 180, e.estimate(minute))
        }
    }

    @Test
    fun implausibleReadingsAreIgnored() {
        val e = BatteryEstimator()
        e.onScreen(true, 0)
        e.onLevel(0, 50, plugged = false)
        // A 6 Wh "counter" and a 3 microamp current: nonsense, so nothing.
        e.onReadings(30, 3, null)
        assertNull(e.estimate(minute))
        e.onReadings(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        assertNull(e.estimate(minute))
    }

    @Test
    fun theObservedRateTakesOverFromTheCurrent() {
        val e = BatteryEstimator()
        // The current says about 180 minutes; the drops say 73 (1% a minute at 73%).
        e.onReadings(3_000_000, -1_000_000, null)
        val t = e.drain(from = 80, drops = 7, every = 1)
        e.onReadings(3_000_000, -1_000_000, null)
        val m = e.estimate(t)!!
        assertTrue("was $m", m in 60..90)
    }

    @Test
    fun chargingSlowsAboveEightyPercent() {
        val e = BatteryEstimator()
        e.onLevel(0, 47, plugged = true)
        // 1% a minute from 48% to 50%.
        e.onLevel(minute, 48, plugged = true)
        e.onLevel(3 * minute, 50, plugged = true)
        e.onLevel(5 * minute, 52, plugged = true)
        val m = e.estimate(5 * minute)!!
        // 28 minutes to 80%, then 20% at half speed (40 minutes): 68.
        assertEquals(68.0, m.toDouble(), 3.0)
        assertEquals(42, e.estimate(5 * minute, systemChargeMinutes = 42))
    }

    @Test
    fun fullOrUnknownGivesNothing() {
        val e = BatteryEstimator()
        assertNull(e.estimate(0))
        e.onLevel(0, 100, plugged = true)
        assertNull(e.estimate(minute, systemChargeMinutes = 10))
    }

    @Test
    fun aJumpInLevelStartsOver() {
        val e = BatteryEstimator()
        val t = e.drain(from = 80, drops = 3, every = 3)
        // A recalibration from 77% to 60%.
        e.onLevel(t + minute, 60, plugged = false)
        assertNull(e.estimate(t + 2 * minute))
    }
}
