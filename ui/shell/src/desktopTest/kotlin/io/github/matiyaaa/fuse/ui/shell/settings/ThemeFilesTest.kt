package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The theme files in docs/themes: every built-in theme as a file to start from (kept identical to
 * what Fuse itself would write; set FUSE_WRITE_THEMES=1 to write them again), and the examples,
 * which must load without a single repair.
 */
class ThemeFilesTest {
    private val docs = File("../../docs/themes").canonicalFile

    @Test
    fun presetFilesMatchTheBuiltInThemes() {
        val write = System.getenv("FUSE_WRITE_THEMES") == "1"
        for (preset in ThemePresets.all) {
            val file = File(docs, "presets/${preset.id}.json")
            val expected = ThemeCodec.encode(preset)
            if (write) file.apply { parentFile.mkdirs() }.writeText(expected)
            assertTrue(file.isFile, "Missing ${file.path}: run with FUSE_WRITE_THEMES=1")
            assertEquals(expected, file.readText(), "${file.name} is out of date: run with FUSE_WRITE_THEMES=1")
        }
    }

    @Test
    fun examplesLoadCleanly() {
        val examples = docs.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
        assertTrue(examples.isNotEmpty())
        for (f in examples) {
            val r = assertIs<ThemeCodec.Imported>(ThemeCodec.parse(f.readText(), ThemePresets::find, ThemePresets.Fuse), f.name)
            assertTrue(r.notes.isEmpty(), "${f.name}: ${r.notes}")
        }
    }
}
