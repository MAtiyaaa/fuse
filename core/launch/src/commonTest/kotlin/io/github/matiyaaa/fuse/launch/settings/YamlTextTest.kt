package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RPCS3's YAML settings edited in place: only the value asked for changes. */
class YamlTextTest {
    private val p = YamlText.PATH

    /** Written here by hand in the shape RPCS3 writes its config.yml. */
    private val config = """
        Core:
          PPU Decoder: Recompiler (LLVM)
          PPU Threads: 2
          Libraries Control:
            []
          Use LLVM CPU: ""
        Video:
          Renderer: Vulkan
          Resolution Scale: 100
          # kept as it is
          Vulkan:
            Adapter: ""
            Force FIFO present mode: false
          Shader Mode: Async Shader Recompiler

        Input/Output:
          Keyboard: "Null"
    """.trimIndent() + "\n"

    @Test
    fun anUntouchedFileIsWrittenBackByteForByte() {
        assertEquals(config, YamlText(config).toString())
        val crlf = config.replace("\n", "\r\n")
        assertEquals(crlf, YamlText(crlf).toString())
        assertEquals("", YamlText("").toString())
    }

    @Test
    fun valuesAreReadAtTheirPathAsYamlReadsThem() {
        val y = YamlText(config)
        assertEquals(listOf("Recompiler (LLVM)"), y.values("Core", "PPU Decoder"))
        assertEquals(listOf(""), y.values("Core", "Use LLVM CPU"))
        assertEquals(listOf(""), y.values("Video${p}Vulkan", "Adapter"))
        assertEquals(listOf("Null"), y.values("Input/Output", "Keyboard"))
        // A node isn't a value, a key under another node isn't this one's, and keys match exactly.
        assertEquals(emptyList(), y.values("Video", "Vulkan"))
        assertEquals(emptyList(), y.values("Core", "Adapter"))
        assertEquals(emptyList(), y.values("core", "PPU Decoder"))
        assertEquals(listOf("Renderer", "Resolution Scale", "Vulkan", "Shader Mode"), y.keys("Video"))
    }

    @Test
    fun aValueChangesInPlaceAndNothingElseDoes() {
        val y = YamlText(config)
        y.set("Video${p}Vulkan", "Force FIFO present mode", "true")
        y.set("Core", "PPU Decoder", "Interpreter (static)")
        assertEquals(
            config.replace("Force FIFO present mode: false", "Force FIFO present mode: true")
                .replace("PPU Decoder: Recompiler (LLVM)", "PPU Decoder: Interpreter (static)"),
            y.toString(),
        )
    }

    @Test
    fun aNewKeyGoesAtTheEndOfItsNodeAndMissingNodesAreMade() {
        val y = YamlText(config)
        y.set("Video", "Anisotropic Filter Override", "16")
        y.set("Video${p}Performance Overlay", "Enabled", "true")
        y.set("Miscellaneous", "Start games in fullscreen mode", "true")
        val lines = y.toString().lines()
        // After the node's last key, before the blank line that ends it.
        assertEquals("  Anisotropic Filter Override: 16", lines[lines.indexOf("  Shader Mode: Async Shader Recompiler") + 1])
        assertEquals(listOf("  Performance Overlay:", "    Enabled: true"), lines.subList(lines.indexOf("  Performance Overlay:"), lines.indexOf("  Performance Overlay:") + 2))
        assertEquals(listOf("Miscellaneous:", "  Start games in fullscreen mode: true", ""), lines.takeLast(3))
        assertEquals(listOf("true"), YamlText(y.toString()).values("Video${p}Performance Overlay", "Enabled"))
    }

    @Test
    fun textIsQuotedOnlyWhereYamlWouldReadItOtherwise() {
        assertEquals("Vulkan", YamlText.quote("Vulkan"))
        assertEquals("-1", YamlText.quote("-1"))
        assertEquals("Recompiler (LLVM)", YamlText.quote("Recompiler (LLVM)"))
        assertEquals("\"\"", YamlText.quote(""))
        assertEquals("\"a: b\"", YamlText.quote("a: b"))
        assertEquals("\"#1\"", YamlText.quote("#1"))
        assertEquals("\" padded\"", YamlText.quote(" padded"))
        // A quote only counts at the start: inside, YAML reads it as it is.
        assertEquals("say \"hi\"", YamlText.quote("say \"hi\""))
        assertEquals("\"\\\"quoted\\\"\"", YamlText.quote("\"quoted\""))
        for (v in listOf("", "a: b", "#1", " padded", "say \"hi\"", "\"quoted\"", "C:\\Games", "- item", "[x]")) {
            val y = YamlText("Core:\n  Key: 1\n")
            y.set("Core", "Key", v)
            assertEquals(listOf(v), YamlText(y.toString()).values("Core", "Key"), "read back: $v")
        }
    }

    @Test
    fun aKeyIsTakenOutButANodeNeverIs() {
        val y = YamlText(config)
        assertTrue(y.removeKey("Video", "Resolution Scale"))
        assertFalse(y.removeKey("Video", "Vulkan"))
        assertFalse(y.removeKey("Video", "Missing"))
        assertEquals(config.replace("  Resolution Scale: 100\n", ""), y.toString())
        assertTrue(y.hasEntries())
        assertFalse(YamlText("Core:\n  # nothing\n").hasEntries())
    }

    @Test
    fun whatFuseChangedIsPutBackExactly() {
        val y = YamlText(config)
        var records = SettingsEdit.change(y, emptySet(), "Video", "Renderer", "OpenGL")
        records = SettingsEdit.change(y, records, "Video${p}Vulkan", "Adapter", "AMD Radeon")
        records = SettingsEdit.change(y, records, "Video", "Write Color Buffers", "true")
        assertEquals(listOf("AMD Radeon"), y.values("Video${p}Vulkan", "Adapter"))
        assertEquals(0, SettingsEdit.restore(y, records))
        assertEquals(config, y.toString())
    }
}
