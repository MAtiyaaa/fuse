package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fuse sizes itself for the screen: normal on a handheld or a 1080p TV, twice as large on a 4K one. */
class InterfaceSizeTest {
    @Test
    fun automaticSizeFollowsTheScreen() {
        assertEquals(1f, automaticInterfaceScale(1280f, 800f)) // Steam Deck
        assertEquals(1f, automaticInterfaceScale(1920f, 1080f))
        assertEquals(1f, automaticInterfaceScale(1920f, 1200f))
        assertEquals(1.25f, automaticInterfaceScale(2560f, 1440f))
        assertEquals(2f, automaticInterfaceScale(3840f, 2160f)) // a 4K TV, counted in pixels
        assertEquals(1.25f, automaticInterfaceScale(3440f, 1440f)) // ultrawide: by its height
        assertEquals(3f, automaticInterfaceScale(7680f, 4320f))
        assertEquals(1f, automaticInterfaceScale(0f, 0f))
    }

    @Test
    fun artIsSizedForTheScreenFuseIsOnNow() {
        // A Deck started on its own screen, then docked to a 4K TV.
        val deck = CapabilityProfile(8, 16_000, false, 60f, 1280, 800, 96, 1)
        val alone = RenderQuality.of(PerformanceProfile.BALANCED, deck, lowPower = false)
        val docked = RenderQuality.of(PerformanceProfile.BALANCED, deck, lowPower = false, windowPx = 3840)
        assertEquals(1280, alone.heroMaxPx)
        assertTrue(docked.heroMaxPx >= 2560, "${docked.heroMaxPx}")
        // A phone stays at 1080p's size on Balanced.
        val phone = CapabilityProfile(8, 8_000, false, 60f, 2400, 1080, 400, 1)
        assertEquals(1920, RenderQuality.of(PerformanceProfile.BALANCED, phone, lowPower = false).heroMaxPx)
    }
}
