package io.github.matiyaaa.fuse.desktop.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Skia's picture cache fits the screen, and the drawing settings come from the environment. */
class RenderTuningTest {
    private val none: (String) -> String? = { null }

    @Test
    fun theCacheGrowsWithTheScreenWithinItsBounds() {
        assertEquals(256L, RenderTuning.cacheMegabytes(1280L * 800))
        assertEquals(256L, RenderTuning.cacheMegabytes(1920L * 1080))
        // A 4K TV: room for two dozen full-screen layers, not the 96 MB that holds three.
        assertEquals(760L, RenderTuning.cacheMegabytes(3840L * 2160))
        assertEquals(1_024L, RenderTuning.cacheMegabytes(7680L * 4320))
        assertEquals(256L, RenderTuning.cacheMegabytes(0))
    }

    @Test
    fun withNothingAskedOnlyTheCacheIsSet() {
        val choice = RenderTuning.choose(3840L * 2160, none, none)
        assertEquals(mapOf("skiko.gpu.resourceCacheLimit" to "760M"), choice.properties)
        assertFalse(choice.keepCursor)
    }

    @Test
    fun theEnvironmentAsksForEachSetting() {
        val env = mapOf("FUSE_GPU_CACHE_MB" to "512", "FUSE_VSYNC" to "off", "FUSE_BUFFERING" to "Triple", "FUSE_WAIT_VSYNC" to "0", "FUSE_CURSOR" to "keep")
        val choice = RenderTuning.choose(3840L * 2160, env::get, none)
        assertEquals("512M", choice.properties["skiko.gpu.resourceCacheLimit"])
        assertEquals("false", choice.properties["skiko.vsync.enabled"])
        assertEquals("TRIPLE", choice.properties["skiko.buffering"])
        assertEquals("false", choice.properties["skiko.rendering.linux.waitForFrameVsyncOnRedrawImmediately"])
        assertTrue(choice.keepCursor)
    }

    @Test
    fun nonsenseIsIgnoredAndJavasOwnPropertiesWin() {
        val env = mapOf("FUSE_GPU_CACHE_MB" to "lots", "FUSE_VSYNC" to "maybe", "FUSE_BUFFERING" to "9")
        val given = mapOf("skiko.gpu.resourceCacheLimit" to "2G")
        val choice = RenderTuning.choose(1920L * 1080, env::get, given::get)
        assertTrue(choice.properties.isEmpty(), "${choice.properties}")
        assertTrue(choice.notes.any { "given to Java" in it })
    }

    @Test
    fun underGamescopeSkiaLeavesTheWaitToIt() {
        for (session in listOf(DisplaySession.STEAM_GAME_MODE, DisplaySession.GAMESCOPE_NESTED)) {
            assertEquals("false", RenderTuning.choose(3840L * 2160, none, none, session).properties["skiko.vsync.enabled"], "$session")
        }
        for (session in listOf(DisplaySession.X11, DisplaySession.WAYLAND, DisplaySession.UNKNOWN)) {
            assertEquals(null, RenderTuning.choose(1920L * 1080, none, none, session).properties["skiko.vsync.enabled"], "$session")
        }
        // Asked for, the wait comes back in Game Mode too.
        val on = mapOf("FUSE_VSYNC" to "on")
        assertEquals("true", RenderTuning.choose(3840L * 2160, on::get, none, DisplaySession.STEAM_GAME_MODE).properties["skiko.vsync.enabled"])
    }
}
