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

    /** graphics.json as an N64 recompilation writes it (nlohmann's `setw(4)`): keys alone, at the top. */
    private val flat = """
        {
            "res_option": "Auto",
            "msaa_option": "MSAA2X",
            "rr_manual_value": 60,
            "developer_mode": false
        }

    """.trimIndent()

    @Test
    fun aFileOfKeysAloneIsReadAndChangedAtTheTop() {
        val j = JsonText(flat)
        assertEquals(listOf("Auto"), j.values("", "res_option"))
        assertEquals(listOf("60"), j.values("", "rr_manual_value"))
        assertTrue(j.hasSection(""))
        j.set("", "res_option", "Original2x")
        j.set("", "rr_manual_value", "120")
        j.set("", "ds_option", "2")
        assertEquals(
            """
            {
                "res_option": "Original2x",
                "msaa_option": "MSAA2X",
                "rr_manual_value": 120,
                "developer_mode": false,
                "ds_option": 2
            }

            """.trimIndent(),
            j.toString(),
            "changed in place, a new key last at the file's own indent",
        )
        assertTrue(j.removeKey("", "ds_option"))
        assertEquals(flat.replace("\"Auto\"", "\"Original2x\"").replace(": 60", ": 120"), j.toString())
        assertFalse(j.removeKey("", "ds_option"))
    }

    @Test
    fun aNewFileOfKeysAloneIsMadeWithTheKeyAtTheTop() {
        val j = JsonText("")
        j.set("", "main_volume", "80")
        assertEquals("{\n  \"main_volume\": 80\n}\n", j.toString())
        assertEquals(listOf("80"), j.values("", "main_volume"))
    }

    @Test
    fun aKeyInsideObjectsInsideObjectsIsFoundAndTheObjectsMadeWhenMissing() {
        val j = JsonText(
            """
            {
                "CVars": {
                    "gSettings": {
                        "Volume": 1.0
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("1.0"), j.values("CVars/gSettings", "Volume"))
        assertTrue(j.hasSection("CVars/gSettings"))
        assertFalse(j.hasSection("CVars/gEnhancements"))
        j.set("CVars/gEnhancements", "Widescreen", "1")
        assertEquals(listOf("1"), j.values("CVars/gEnhancements", "Widescreen"))
        assertEquals(
            """
            {
                "CVars": {
                    "gSettings": {
                        "Volume": 1.0
                    },
                    "gEnhancements": {
                        "Widescreen": 1
                    }
                }
            }
            """.trimIndent(),
            j.toString(),
        )
        assertTrue(j.removeKey("CVars/gEnhancements", "Widescreen"))
        assertTrue(j.removeEmptySection("CVars/gEnhancements", blankBefore = false))
        assertFalse(j.hasSection("CVars/gEnhancements"))
        assertEquals(listOf("1.0"), j.values("CVars/gSettings", "Volume"))
    }
}
