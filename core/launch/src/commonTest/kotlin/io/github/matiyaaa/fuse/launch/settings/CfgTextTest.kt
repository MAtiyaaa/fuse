package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RetroArch's config files read and changed as RetroArch v1.22.2's own reader reads them (`config_file.c`). */
class CfgTextTest {
    private val sample = """
        # A comment RetroArch leaves alone
        #include "/home/ana/.config/retroarch/extra.cfg"
        video_driver = "vulkan"
        cheevos_enable = "false"
        savefile_directory = "~/saves" # where saves go
        menu_driver=ozone
        cheevos_enable = "true"
        audio_latency = "64"

    """.trimIndent()

    @Test
    fun anUntouchedFileIsTheSameByteForByteWhateverItsLineEndings() {
        assertEquals(sample, CfgText(sample).toString())
        val crlf = sample.replace("\n", "\r\n")
        assertEquals(crlf, CfgText(crlf).toString())
        val noBreak = sample.trimEnd()
        assertEquals(noBreak, CfgText(noBreak).toString())
    }

    @Test
    fun valuesAreReadAsRetroArchReadsThem() {
        val cfg = CfgText(sample)
        assertEquals(listOf("vulkan"), cfg.values("", "video_driver"))
        // The first time a key is written is the one RetroArch keeps.
        assertEquals(listOf("false"), cfg.values("", "cheevos_enable"))
        // A comment after a quoted value is no part of it.
        assertEquals(listOf("~/saves"), cfg.values("", "savefile_directory"))
        // RetroArch reads a key up to the first space, so `menu_driver=ozone` is all key and no `=`:
        // it skips the line, and so does Fuse.
        assertEquals(emptyList(), cfg.values("", "menu_driver"))
        assertEquals(emptyList(), cfg.values("", "menu_driver=ozone"))
        assertEquals(listOf("64"), CfgText("audio_latency = 64 ms").values("", "audio_latency"))
        // An #include line is no key, and nothing is in a section.
        assertEquals(emptyList(), cfg.values("", "#include"))
        assertEquals(emptyList(), cfg.values("Core", "video_driver"))
        assertTrue(cfg.hasEntries())
    }

    @Test
    fun aChangeTouchesOnlyItsOwnLineAndNewKeysGoAtTheEnd() {
        val cfg = CfgText(sample)
        cfg.set("", "cheevos_enable", "true")
        cfg.set("", "cheevos_username", "Ana")
        // Only the first cheevos_enable changed, written as RetroArch writes it; the later one is as it was.
        assertEquals(
            """
            # A comment RetroArch leaves alone
            #include "/home/ana/.config/retroarch/extra.cfg"
            video_driver = "vulkan"
            cheevos_enable = "true"
            savefile_directory = "~/saves" # where saves go
            menu_driver=ozone
            cheevos_enable = "true"
            audio_latency = "64"
            cheevos_username = "Ana"

            """.trimIndent(),
            cfg.toString(),
        )
        assertEquals(listOf("Ana"), CfgText(cfg.toString()).values("", "cheevos_username"))
    }

    @Test
    fun removingAKeyTakesOutTheLineRetroArchReadsAndOnlyIt() {
        val cfg = CfgText(sample)
        assertTrue(cfg.removeKey("", "video_driver"))
        assertFalse(cfg.removeKey("", "video_driver"))
        assertEquals(sample.lines().filterNot { it.startsWith("video_driver") }.joinToString("\n"), cfg.toString())
    }

    @Test
    fun aNewFileIsWrittenAsRetroArchWritesOne() {
        val cfg = CfgText("")
        assertFalse(cfg.hasEntries())
        cfg.set("", "cheevos_token", "AbCdEf0123456789")
        assertEquals("cheevos_token = \"AbCdEf0123456789\"\n", cfg.toString())
    }

    @Test
    fun aValueRetroArchCantReadBackIsRefused() {
        assertFailsWith<IllegalArgumentException> { CfgText("").set("", "name", "say \"hi\"") }
        assertFailsWith<IllegalArgumentException> { CfgText("").set("", "name", "two\nlines") }
        assertFailsWith<IllegalArgumentException> { CfgText("").set("Core", "name", "x") }
    }
}
