package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.launch.patches.IniText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Eden's two and three keys per value, read and written as its config.cpp does. */
class EdenConfigTest {
    @Test
    fun everyGamesValueCountsOnlyWhenItIsntMarkedTheDefault() {
        val file = IniText("[Renderer]\nresolution_setup\\default=false\nresolution_setup=6\nuse_vsync_mode\\default=true\nuse_vsync_mode=0\n")
        assertEquals("6", EdenConfig.value(file, "Renderer", "resolution_setup", game = false, default = "3"))
        // Marked the default: Eden uses its default, whatever the key says.
        assertNull(EdenConfig.value(file, "Renderer", "use_vsync_mode", game = false, default = "2"))
        assertNull(EdenConfig.value(file, "Renderer", "missing", game = false, default = "1"))
    }

    @Test
    fun aGamesValueIsItsOwnOnlyWhenItDoesntUseEveryGames() {
        val file = IniText(
            "[Renderer]\n" +
                "resolution_setup\\use_global=false\nresolution_setup\\default=false\nresolution_setup=7\n" +
                "use_vsync_mode\\use_global=true\nuse_vsync_mode\\default=false\nuse_vsync_mode=0\n" +
                "use_disk_shader_cache\\use_global=false\nuse_disk_shader_cache\\default=true\nuse_disk_shader_cache=false\n",
        )
        assertEquals("7", EdenConfig.value(file, "Renderer", "resolution_setup", game = true, default = "3"))
        assertNull(EdenConfig.value(file, "Renderer", "use_vsync_mode", game = true, default = "2"), "it follows every game's")
        assertEquals("true", EdenConfig.value(file, "Renderer", "use_disk_shader_cache", game = true, default = "true"), "its own, and Eden's default")
        // SimpleIni's GetBoolValue: no, off and 0 are false too.
        assertEquals("7", EdenConfig.value(IniText("[R]\nk\\use_global=no\nk\\default=off\nk=7\n"), "R", "k", game = true, default = "3"))
    }

    @Test
    fun aChangeWritesEachKeyEdenReads() {
        assertEquals(
            listOf(Triple("Renderer", "resolution_setup\\use_global", "false"), Triple("Renderer", "resolution_setup\\default", "false"), Triple("Renderer", "resolution_setup", "6")),
            EdenConfig.writes("Renderer", "resolution_setup", "6", game = true, default = "3"),
        )
        assertEquals(
            listOf(Triple("Renderer", "resolution_setup\\default", "true"), Triple("Renderer", "resolution_setup", "3")),
            EdenConfig.writes("Renderer", "resolution_setup", "3", game = false, default = "3"),
        )
        assertEquals(listOf<String?>(null, null, null), EdenConfig.writes("Renderer", "k", null, game = true, default = "3").map { it.third })
    }

    @Test
    fun aChangeAndItsPutBackLeaveTheFileAsItWas() {
        val before = "[Renderer]\nresolution_setup\\use_global=true\n\n[Core]\nuse_multi_core\\use_global=true\n"
        val file = IniText(before, spaced = false)
        var records = emptySet<String>()
        for ((section, key, value) in EdenConfig.writes("Renderer", "resolution_setup", "6", game = true, default = "3")) records = SettingsEdit.change(file, records, section, key, value)
        assertEquals(
            "[Renderer]\nresolution_setup\\use_global=false\nresolution_setup\\default=false\nresolution_setup=6\n\n[Core]\nuse_multi_core\\use_global=true\n",
            file.toString(),
        )
        assertEquals("6", EdenConfig.value(file, "Renderer", "resolution_setup", game = true, default = "3"))
        SettingsEdit.restore(file, records)
        assertEquals(before, file.toString())
        // A new file is written as Eden writes its own: no spaces around `=`.
        val made = IniText("", spaced = false)
        for ((section, key, value) in EdenConfig.writes("Cpu", "cpu_accuracy", "1", game = true, default = "0")) SettingsEdit.change(made, emptySet(), section, key, value)
        assertEquals("[Cpu]\ncpu_accuracy\\use_global=false\ncpu_accuracy\\default=false\ncpu_accuracy=1\n", made.toString())
    }

    @Test
    fun aGamesFileIsNamedByItsTitleIdInUpperCase() {
        assertEquals("/c/eden/custom/0100F2C0115B6000.ini", EdenConfig.gameFile("/c/eden", "0100f2c0115b6000"))
        assertEquals("/c/eden/qt-config.ini", EdenConfig.globalFile("/c/eden"))
    }
}
