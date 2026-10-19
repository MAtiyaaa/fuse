package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TOML settings files as Xenia writes them (config.cc SaveConfig), changed in place and put back. */
class TomlTextTest {
    private val config = "[APU]\n" +
        "apu = \"any\"                                       \t# Audio system. Use: [any, nop, sdl, xaudio2]\n" +
        "mute = false                                      \t# Mutes all audio output.\n" +
        "\n\n" +
        "[GPU]\n" +
        "framerate_limit = 0                               \t# Maximum frames per second.\n" +
        "                                                  \t# 0 = Unlimited frames.\n" +
        "\n" +
        "vsync = true                                      \t# Enable VSYNC.\n"

    @Test
    fun untouchedItIsTheSameBytesAndValuesReadWithoutTheirComments() {
        val t = TomlText(config)
        assertEquals(config, t.toString())
        assertEquals(listOf("any"), t.values("APU", "apu"))
        assertEquals(listOf("false"), t.values("APU", "mute"))
        assertEquals(listOf("0"), t.values("GPU", "framerate_limit"))
        assertEquals(listOf("true"), t.values("GPU", "vsync"))
        assertEquals(emptyList(), t.values("GPU", "apu"), "keys are their table's")
    }

    @Test
    fun aValueIsChangedWhereItIsAndItsCommentStays() {
        val t = TomlText(config)
        t.set("GPU", "framerate_limit", "60")
        t.set("APU", "apu", "sdl")
        assertEquals(config.replace("framerate_limit = 0", "framerate_limit = 60").replace("apu = \"any\"", "apu = \"sdl\""), t.toString())
    }

    @Test
    fun newKeysAndTablesGoWhereXeniaWouldPutThemAndPutBackLeavesTheFileAsItWas() {
        val t = TomlText(config) { _, key -> key == "gpu" }
        var records = SettingsEdit.change(t, emptySet(), "GPU", "gpu", "vulkan")
        records = SettingsEdit.change(t, records, "CPU", "break_on_debugbreak", "false")
        records = SettingsEdit.change(t, records, "APU", "mute", "true")
        val changed = t.toString()
        assertTrue("vsync = true                                      \t# Enable VSYNC.\ngpu = \"vulkan\"\n" in changed, changed)
        assertTrue(changed.endsWith("\n\n[CPU]\nbreak_on_debugbreak = false\n"), changed)
        SettingsEdit.restore(t, records)
        assertEquals(config, t.toString())
    }

    @Test
    fun aGamesFileFromNothing() {
        val t = TomlText("")
        assertFalse(t.hasEntries())
        t.set("GPU", "vsync", "false")
        assertEquals("[GPU]\nvsync = false\n", t.toString())
        assertTrue(t.removeKey("GPU", "vsync"))
        assertFalse(t.hasEntries())
    }

    @Test
    fun quotesAndAHashInsideAStringAreTheString() {
        val t = TomlText("[Content]\nlicense_mask = \"a # b \\\"c\\\"\"   # not this\n")
        assertEquals(listOf("a # b \"c\""), t.values("Content", "license_mask"))
        t.set("Content", "license_mask", "x")
        assertEquals("[Content]\nlicense_mask = \"x\"   # not this\n", t.toString())
    }
}
