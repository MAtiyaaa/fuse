package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.system.DisplaySession
import io.github.matiyaaa.fuse.desktop.system.EffectsGovernor
import io.github.matiyaaa.fuse.desktop.system.FramePacing
import io.github.matiyaaa.fuse.desktop.system.FrameStats
import io.github.matiyaaa.fuse.desktop.system.Renderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramePacingTest {
    @Test
    fun openGlOnLlvmpipeIsTheProcessor() {
        val soft = Renderer.classify("OPENGL", "GraphicsApi: OPENGL, OS: Linux\nVendor: Mesa\nModel: llvmpipe (LLVM 17.0.6, 256 bits)")
        assertFalse(soft.gpu)
        assertTrue("llvmpipe" in soft.name && soft.why != null)
        val real = Renderer.classify("OPENGL", "Vendor: AMD\nModel: AMD Custom GPU 0405 (radeonsi, vangogh)")
        assertTrue(real.gpu)
        assertNull(real.why)
        assertFalse(Renderer.classify("SOFTWARE_FAST", null).gpu)
        assertTrue(Renderer.classify("OPENGL", null).gpu, "no info: the API's word is all there is")
    }

    @Test
    fun sessionsAreToldApart() {
        fun env(vararg kv: Pair<String, String>) = mapOf(*kv)::get
        assertEquals(DisplaySession.STEAM_GAME_MODE, FramePacing.session(env("XDG_CURRENT_DESKTOP" to "gamescope", "SteamDeck" to "1")))
        assertEquals(DisplaySession.GAMESCOPE_NESTED, FramePacing.session(env("GAMESCOPE_WAYLAND_DISPLAY" to "gamescope-0", "WAYLAND_DISPLAY" to "wayland-0")))
        assertEquals(DisplaySession.WAYLAND, FramePacing.session(env("WAYLAND_DISPLAY" to "wayland-0", "DISPLAY" to ":0")))
        assertEquals(DisplaySession.X11, FramePacing.session(env("DISPLAY" to ":0")))
    }

    @Test
    fun aHandheldSizedWindowOnATvIsUpscaledAndTheBudgetFollowsTheDisplay() {
        val tv = FramePacing.profile(DisplaySession.STEAM_GAME_MODE, 1280, 800, 3840, 2160, 60.0, gpu = true)
        assertTrue(tv.upscaled && tv.largeOutput)
        assertEquals(16_666_666L, tv.budgetNanos)
        val deck = FramePacing.profile(DisplaySession.STEAM_GAME_MODE, 1280, 800, 1280, 800, 90.0, gpu = true)
        assertFalse(deck.upscaled || deck.largeOutput)
        assertEquals(11_111_111L, deck.budgetNanos)
        for (hz in listOf(60.0, 90.0, 120.0)) assertEquals((1e9 / hz).toLong(), FramePacing.profile(DisplaySession.X11, 1920, 1080, 1920, 1080, hz, true).budgetNanos)
    }

    @Test
    fun steamsFrameLimitIsReadFromSteadyFrames() {
        val at40 = List(60) { 25_000_000L }
        assertEquals(40.0, FramePacing.profile(DisplaySession.STEAM_GAME_MODE, 1280, 800, 1280, 800, 90.0, true, at40).refreshHz)
        // Uneven slow frames are slowness, not a limit.
        val uneven = List(60) { i -> if (i % 2 == 0) 11_000_000L else 40_000_000L }
        assertEquals(90.0, FramePacing.profile(DisplaySession.STEAM_GAME_MODE, 1280, 800, 1280, 800, 90.0, true, uneven).refreshHz)
    }

    @Test
    fun decorationDropsOnlyUnderSustainedPressureAndComesBack() {
        val budget = 16_666_666L
        val slow = FrameStats(20_000_000, 30_000_000, 40_000_000, 10, 60)
        val easy = FrameStats(8_000_000, 12_000_000, 14_000_000, 0, 60)
        val g = EffectsGovernor()
        assertFalse(g.window(slow, budget))
        assertFalse(g.window(easy, budget), "one slow window is not pressure")
        repeat(3) { g.window(slow, budget) }
        assertTrue(g.reduced)
        repeat(5) { assertTrue(g.window(easy, budget)) }
        assertFalse(g.window(easy, budget), "back after six good windows")
        val stats = FramePacing.stats(List(99) { 10L } + 100L, 20L)
        assertEquals(10L, stats.p50)
        assertEquals(1, stats.late)
    }
}
