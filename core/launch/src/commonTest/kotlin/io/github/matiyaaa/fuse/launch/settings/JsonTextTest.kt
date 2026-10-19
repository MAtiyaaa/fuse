package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JSON settings files as shadPS4 writes them (nlohmann's `dump(2)`), changed in place and put back. */
class JsonTextTest {
    private val config = """
        {
          "GPU": {
            "readbacks_mode": 0,
            "vblank_frequency": 60,
            "fsr_enabled": true
          },
          "General": {
            "trophy_notification_side": "right",
            "install_dirs": [
              {
                "enabled": true,
                "path": "/games/ps4"
              }
            ]
          },
          "Network": {}
        }

    """.trimIndent()

    @Test
    fun untouchedItIsTheSameBytesAndValuesReadAsWritten() {
        val j = JsonText(config)
        assertEquals(config, j.toString())
        assertEquals(listOf("0"), j.values("GPU", "readbacks_mode"))
        assertEquals(listOf("true"), j.values("GPU", "fsr_enabled"))
        assertEquals(listOf("right"), j.values("General", "trophy_notification_side"), "a string's text")
        assertEquals(emptyList(), j.values("General", "install_dirs"), "a list isn't a value Fuse reads")
        assertEquals(emptyList(), j.values("Vulkan", "validation"))
        assertTrue(j.hasSection("Network"))
        assertFalse(j.hasSection("Vulkan"))
    }

    @Test
    fun aValueIsChangedWhereItIsAndAStringStaysOne() {
        val j = JsonText(config)
        j.set("GPU", "vblank_frequency", "120")
        j.set("General", "trophy_notification_side", "left")
        assertEquals(config.replace("\"vblank_frequency\": 60", "\"vblank_frequency\": 120").replace("\"right\"", "\"left\""), j.toString())
    }

    @Test
    fun aNewKeyGoesLastInItsGroupAndANewGroupLastOfAll() {
        val j = JsonText(config) { _, key -> key == "shadnet_server" }
        j.set("GPU", "copy_gpu_buffers", "true")
        assertTrue("    \"fsr_enabled\": true,\n    \"copy_gpu_buffers\": true\n  }," in j.toString(), j.toString())
        // An empty group gets it on a line of its own; a text setting is quoted even when it looks like a number.
        j.set("Network", "shadnet_server", "31313")
        assertTrue("  \"Network\": {\n    \"shadnet_server\": \"31313\"\n  }" in j.toString(), j.toString())
        j.set("Vulkan", "validation", "false")
        assertTrue("  },\n  \"Vulkan\": {\n    \"validation\": false\n  }\n}" in j.toString(), j.toString())
        assertEquals(listOf("false"), JsonText(j.toString()).values("Vulkan", "validation"))
    }

    @Test
    fun putBackLeavesTheFileAsItWas() {
        val j = JsonText(config)
        var records = SettingsEdit.change(j, emptySet(), "GPU", "readbacks_mode", "2")
        records = SettingsEdit.change(j, records, "GPU", "copy_gpu_buffers", "true")
        records = SettingsEdit.change(j, records, "Vulkan", "validation", "true")
        records = SettingsEdit.change(j, records, "Network", "enable_upnp", "false")
        SettingsEdit.restore(j, records)
        assertEquals(config, j.toString())
    }

    @Test
    fun aGamesFileMadeFromNothingIsAsShadPs4LaysOneOut() {
        val j = JsonText("")
        assertFalse(j.hasEntries())
        j.set("GPU", "readbacks_mode", "1")
        j.set("GPU", "fsr_enabled", "false")
        assertEquals("{\n  \"GPU\": {\n    \"readbacks_mode\": 1,\n    \"fsr_enabled\": false\n  }\n}\n", j.toString())
        assertTrue(j.hasEntries())
        j.removeKey("GPU", "readbacks_mode")
        j.removeKey("GPU", "fsr_enabled")
        assertFalse(j.hasEntries(), "only an empty group left")
        assertTrue(j.removeEmptySection("GPU", blankBefore = false))
        assertEquals("{}\n", j.toString())
    }

    @Test
    fun textWithQuotesAndEscapesIsReadAndWrittenBack() {
        val j = JsonText("{\"General\": {\"name\": \"a \\\"b\\\" \\\\ c\\u0041\"}}")
        assertEquals(listOf("a \"b\" \\ cA"), j.values("General", "name"))
        j.set("General", "name", "x \"y\"")
        assertEquals("{\"General\": {\"name\": \"x \\\"y\\\"\"}}", j.toString())
    }
}
