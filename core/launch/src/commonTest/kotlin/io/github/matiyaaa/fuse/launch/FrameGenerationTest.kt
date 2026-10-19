package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FrameGenerationTest {
    private val mgba = LaunchPlan.Command(EmulatorId("linux.mgba"), listOf("/usr/bin/mgba-qt", "-f", "/roms/a.gba"), env = mapOf("QT_QPA_PLATFORM" to "xcb"), target = LaunchTarget.File("/roms/a.gba"))

    @Test
    fun aCommandGetsLsfgVksOwnVariablesBesideItsOwn() {
        val on = FrameGeneration.apply(mgba, 3) as LaunchPlan.Command
        assertEquals(mapOf("QT_QPA_PLATFORM" to "xcb", "LSFGVK_ENV" to "1", "LSFGVK_MULTIPLIER" to "3"), on.env)
        assertEquals(mgba.argv, on.argv)
    }

    @Test
    fun offOrAnUnofferedValueLeavesThePlanAsItIs() {
        assertSame(mgba, FrameGeneration.apply(mgba, 0))
        assertSame(mgba, FrameGeneration.apply(mgba, 7))
        val app = LaunchPlan.OpenAppOnly(EmulatorId("linux.x"), "x", "no")
        assertSame(app, FrameGeneration.apply(app, 2))
    }

    @Test
    fun itsConfigurationIsWhereLsfgVkSays() {
        assertEquals("/home/deck/.config/lsfg-vk/conf.toml", FrameGeneration.configPath("/home/deck", null))
        assertEquals("/home/deck/.cfg/lsfg-vk/conf.toml", FrameGeneration.configPath("/home/deck/", "/home/deck/.cfg/"))
        // A relative XDG_CONFIG_HOME is ignored, as the XDG specification says.
        assertEquals("/home/deck/.config/lsfg-vk/conf.toml", FrameGeneration.configPath("/home/deck", "relative"))
    }
}
