package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.automaticMotion
import io.github.matiyaaa.fuse.model.recommendedMotion
import kotlin.test.Test
import kotlin.test.assertEquals

/** Motion on Automatic follows the effects setup recommends for the device, and the Performance choice. */
class MotionRecommendationTest {
    private fun device(cores: Int, ramMb: Long) = CapabilityProfile(cores, ramMb, false, 60f, 1920, 1080, 160, 1)

    @Test
    fun theDevicesRecommendationSetsTheMotion() {
        val low = device(4, 2_048)
        val mid = device(6, 6_000)
        val high = device(8, 16_000)
        assertEquals(MotionProfile.MINIMAL, recommendedMotion(PerformanceProfile.AUTOMATIC, low, lowPower = false))
        assertEquals(MotionProfile.STANDARD, recommendedMotion(PerformanceProfile.AUTOMATIC, mid, lowPower = false))
        assertEquals(MotionProfile.ENHANCED, recommendedMotion(PerformanceProfile.AUTOMATIC, high, lowPower = false))
        // The person's Performance choice, and Low Power Mode, decide over the device.
        assertEquals(MotionProfile.ENHANCED, recommendedMotion(PerformanceProfile.HIGH_QUALITY, low, lowPower = false))
        assertEquals(MotionProfile.MINIMAL, recommendedMotion(PerformanceProfile.AUTOMATIC, high, lowPower = true))
        assertEquals(MotionProfile.MINIMAL, recommendedMotion(PerformanceProfile.LOW_POWER, high, lowPower = false))
    }

    @Test
    fun aCalmThemeStaysCalmAndTheRestFollowTheDevice() {
        assertEquals(MotionProfile.ENHANCED, automaticMotion(MotionProfile.STANDARD, MotionProfile.ENHANCED))
        assertEquals(MotionProfile.MINIMAL, automaticMotion(MotionProfile.ENHANCED, MotionProfile.MINIMAL))
        assertEquals(MotionProfile.MINIMAL, automaticMotion(MotionProfile.MINIMAL, MotionProfile.ENHANCED))
    }
}
