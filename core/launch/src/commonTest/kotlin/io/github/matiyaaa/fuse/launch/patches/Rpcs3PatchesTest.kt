package io.github.matiyaaa.fuse.launch.patches

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Rpcs3PatchesTest {
    private val patchYml = """
        Version: 1.2

        Anchors:
          blur: &blur
            - [ be32, 0x00100000, 0x60000000 ]

        PPU-b8c34f774adb367761706a7f685d4f8d9d355426:
          "Disable Blur":
            Games:
              "Demon's Souls":
                BLES00932: [ 01.00, 01.04 ]
                BLUS30443: [ 01.00 ]
            Author: "Someone"
            Notes: Turns the blur off
            Patch Version: 1.0
            Patch:
              - [ load, *blur ]
          "60 FPS":
            Games:
              "Demon's Souls":
                BLUS30443: [ All ]
            Patch Version: 1.0
            Patch:
              - [ be32, 0x00200000, 0x60000000 ]
        PPU-0000000000000000000000000000000000000000:
          "Disable Blur":
            Games:
              "Demon's Souls":
                BLUS30443: [ 01.00 ]
            Patch Version: 1.0
            Patch:
              - [ load, *blur ]
          Other:
            Games:
              "Another Game":
                BLUS99999: [ 01.00 ]
            Patch Version: 1.0
            Patch: []
    """.trimIndent()

    @Test
    fun `the patches for a game are every hash that lists its serial and version`() {
        val found = Rpcs3Patches.forGame(patchYml, "BLUS30443", "01.00")
        assertEquals(listOf("Disable Blur", "60 FPS", "Disable Blur"), found.map { it.description })
        assertEquals(listOf("01.00", "All", "01.00"), found.map { it.version })
        assertEquals("Turns the blur off", found.first().notes)
        // A version it isn't listed for: only the "All" one.
        assertEquals(listOf("60 FPS"), Rpcs3Patches.forGame(patchYml, "BLUS30443", "01.02").map { it.description })
        // App versions stay text.
        assertEquals(listOf("01.00", "01.04"), Rpcs3Patches.forGame(patchYml, "BLES00932", null).map { it.version })
        // Another engine version is skipped whole, as RPCS3 does.
        assertTrue(Rpcs3Patches.forGame(patchYml.replace("Version: 1.2", "Version: 1.1"), "BLUS30443", "01.00").isEmpty())
    }

    @Test
    fun `turning a patch on and off leaves everything else in the config as it was`() {
        val existing = """
            PPU-aaaa:
              "Someone's patch":
                "Game: The Sequel":
                  BLUS00001:
                    01.00:
                      Enabled: true
                      Configurable Values:
                        Speed: 2
        """.trimIndent()
        val config = Rpcs3PatchConfig(existing)
        val entries = Rpcs3Patches.forGame(patchYml, "BLUS30443", "01.00").filter { it.description == "Disable Blur" }
        entries.forEach { assertFalse(config.enabled(it)) }
        entries.forEach { config.set(it, true) }
        val written = config.toString()
        val reread = Rpcs3PatchConfig(written)
        entries.forEach { assertTrue(reread.enabled(it)) }
        val theirs = Rpcs3PatchEntry("PPU-aaaa", "Someone's patch", "Game: The Sequel", "BLUS00001", "01.00")
        assertTrue(reread.enabled(theirs))
        assertTrue("Speed: 2" in written)
        entries.forEach { reread.set(it, false) }
        val back = reread.toString()
        assertFalse("Disable Blur" in back)
        assertEquals(Rpcs3PatchConfig(existing).toString(), back)
    }

    @Test
    fun `the older bare form is read as on`() {
        val e = Rpcs3PatchEntry("PPU-1", "P", "T", "BLUS00001", "01.00")
        assertTrue(Rpcs3PatchConfig("PPU-1:\n  P:\n    T:\n      BLUS00001:\n        01.00: true\n").enabled(e))
        assertEquals("{}\n", Rpcs3PatchConfig(null).toString())
    }
}
