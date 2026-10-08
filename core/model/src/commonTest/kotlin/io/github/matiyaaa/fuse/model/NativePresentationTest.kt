package io.github.matiyaaa.fuse.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePresentationTest {
    private val linuxHandheld = CapabilityProfile(
        cpuCores = 4,
        totalRamMb = 8_192,
        isLowRamDevice = false,
        maxRefreshRate = 120f,
        screenWidthPx = 1920,
        screenHeightPx = 1080,
        densityDpi = 160,
        displayCount = 1,
    )

    @Test fun linuxFourCoreHandheldKeepsSharpAutomaticArtwork() {
        assertEquals(DeviceTier.LOW, linuxHandheld.tier)
        assertEquals(
            PerformanceProfile.BALANCED,
            NativePresentation.performance(Host.LINUX, PerformanceProfile.AUTOMATIC, linuxHandheld, false),
        )
        assertFalse(NativePresentation.lowMemoryArtwork(Host.LINUX, linuxHandheld))
        val quality = NativePresentation.quality(
            Host.LINUX, PerformanceProfile.AUTOMATIC, linuxHandheld,
            lowPower = false, softwareRenderer = false, windowPx = 1920,
        )
        assertEquals(1f, quality.heroDecodeShare)
        assertEquals(1920, quality.heroMaxPx)
        assertTrue(quality.animatedBackground)
    }

    @Test fun linuxSoftwareFallbackDisablesExpensiveEffectsButPreservesArt() {
        val quality = NativePresentation.quality(
            Host.LINUX, PerformanceProfile.AUTOMATIC, linuxHandheld,
            lowPower = false, softwareRenderer = true, windowPx = 3840,
        )
        assertEquals(1f, quality.heroDecodeShare)
        assertEquals(2560, quality.heroMaxPx)
        assertFalse(quality.blur)
        assertFalse(quality.backgroundVideo)
        assertFalse(quality.animatedBackground)
        assertFalse(quality.crtShader)
        assertTrue(quality.prefetchDepth <= 2)
    }

    @Test fun androidRenderingPolicyAndManualLowPowerRemainUnchanged() {
        for (host in listOf(Host.ANDROID, Host.WINDOWS, Host.MACOS)) {
            val expected = RenderQuality.of(
                PerformanceProfile.AUTOMATIC, linuxHandheld, lowPower = true, windowPx = 3840,
            )
            assertEquals(
                expected,
                NativePresentation.quality(
                    host, PerformanceProfile.AUTOMATIC, linuxHandheld,
                    lowPower = false, softwareRenderer = true, windowPx = 3840,
                ),
            )
            assertTrue(NativePresentation.lowMemoryArtwork(host, linuxHandheld))
        }
        assertEquals(
            PerformanceProfile.LOW_POWER,
            NativePresentation.performance(Host.LINUX, PerformanceProfile.LOW_POWER, linuxHandheld, false),
        )
        assertEquals(
            PerformanceProfile.AUTOMATIC,
            NativePresentation.performance(Host.LINUX, PerformanceProfile.AUTOMATIC, linuxHandheld, true),
        )
    }

    @Test fun genuinelyLowMemoryLinuxStillUsesLowMemoryArtCache() {
        val tiny = linuxHandheld.copy(totalRamMb = 2_048)
        assertTrue(NativePresentation.lowMemoryArtwork(Host.LINUX, tiny))
        assertEquals(
            PerformanceProfile.AUTOMATIC,
            NativePresentation.performance(Host.LINUX, PerformanceProfile.AUTOMATIC, tiny, false),
        )
        assertEquals(
            0.85f,
            RenderQuality.of(PerformanceProfile.BALANCED, tiny, lowPower = false).heroDecodeShare,
        )
    }
}
