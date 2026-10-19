package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.launch.patches.IniText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Azahar's keys per value, read and written as its Qt config does. */
class AzaharConfigTest {
    @Test
    fun everyGamesValueIsWhatTheKeyHoldsUnlessMarkedTheDefault() {
        val file = IniText("[Renderer]\nresolution_factor\\default=false\nresolution_factor=3\nuse_vsync\\default=true\nuse_vsync=false\ntexture_filter=2\n")
        assertEquals("3", AzaharConfig.value(file, "Renderer", "resolution_factor", game = false, default = "1"))
        assertNull(AzaharConfig.value(file, "Renderer", "use_vsync", game = false, default = "true"), "marked the default")
        // Without `\default`, Azahar reads the key (its default for `\default` is false).
        assertEquals("2", AzaharConfig.value(file, "Renderer", "texture_filter", game = false, default = "0"))
        assertNull(AzaharConfig.value(file, "Renderer", "missing", game = false, default = "1"))
    }

    @Test
    fun aGamesOwnValueIsThereOnlyWhenItDoesntUseEveryGames() {
        val file = IniText("[Renderer]\nresolution_factor\\use_global=false\nresolution_factor=4\nuse_vsync\\use_global=true\nuse_vsync=false\n")
        assertEquals("4", AzaharConfig.value(file, "Renderer", "resolution_factor", game = true, default = "1"))
        assertNull(AzaharConfig.value(file, "Renderer", "use_vsync", game = true, default = "true"))
        assertNull(AzaharConfig.value(file, "Renderer", "missing", game = true, default = "1"))
    }

    @Test
    fun aChangeWritesTheKeysAzaharWritesAndPutsBackExactly() {
        assertEquals(
            listOf(Triple("Core", "cpu_clock_percentage\\use_global", "false"), Triple("Core", "cpu_clock_percentage", "150")),
            AzaharConfig.writes("Core", "cpu_clock_percentage", "150", game = true, default = "100"),
        )
        assertEquals(
            listOf(Triple("Core", "cpu_clock_percentage\\default", "true"), Triple("Core", "cpu_clock_percentage", "100")),
            AzaharConfig.writes("Core", "cpu_clock_percentage", "100", game = false, default = "100"),
        )
        val before = "[Core]\ncpu_clock_percentage\\default=true\ncpu_clock_percentage=100\n"
        val file = IniText(before, spaced = false)
        var records = emptySet<String>()
        for ((s, k, v) in AzaharConfig.writes("Core", "cpu_clock_percentage", "200", game = false, default = "100")) records = SettingsEdit.change(file, records, s, k, v)
        assertEquals("[Core]\ncpu_clock_percentage\\default=false\ncpu_clock_percentage=200\n", file.toString())
        SettingsEdit.restore(file, records)
        assertEquals(before, file.toString())
        assertEquals("/c/custom/0004000000055D00.ini", AzaharConfig.gameFile("/c", "0004000000055d00"))
    }
}
