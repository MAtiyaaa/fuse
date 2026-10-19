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
    fun theRootTablesKeysAreTheSectionWithNoName() {
        // melonDS.toml as toml11 writes it: the root table's keys, then its tables.
        val melon = "LimitFPS = true\nTargetFPS = 60.0\n\n[3D.GL]\nScaleFactor = 1\n"
        val t = TomlText(melon)
        assertEquals(listOf("true"), t.values("", "LimitFPS"))
        assertEquals(emptyList(), t.values("", "ScaleFactor"), "a table's key isn't the root's")
        t.set("", "TargetFPS", "120.0")
        t.set("", "AudioSync", "true")
        t.set("3D.GL", "ScaleFactor", "4")
        assertEquals("LimitFPS = true\nTargetFPS = 120.0\nAudioSync = true\n\n[3D.GL]\nScaleFactor = 4\n", t.toString())
        assertTrue(t.removeKey("", "AudioSync"))
        assertFalse(t.removeEmptySection("", true), "the root table stays")
        // A file with no root keys yet gets its first one at the top, a blank line before the first table.
        val tables = TomlText("[Emu]\nDirectBoot = true\n")
        tables.set("", "LimitFPS", "false")
        assertEquals("LimitFPS = false\n\n[Emu]\nDirectBoot = true\n", tables.toString())
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
