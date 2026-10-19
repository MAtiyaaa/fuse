package io.github.matiyaaa.fuse.library.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Steam's own files, written here by hand in the shapes Steam and Proton write them. */
class SteamProtonTest {
    private val config = """
        "InstallConfigStore"
        {
            "Software"
            {
                "Valve"
                {
                    "Steam"
                    {
                        "CompatToolMapping"
                        {
                            "0"
                            {
                                "name"		"GE-Proton9-20"
                                "config"		""
                                "priority"		"75"
                            }
                            "1091500"
                            {
                                "name"		"proton_experimental"
                            }
                        }
                    }
                }
            }
        }
    """.trimIndent()

    @Test
    fun theToolForEveryOtherTitleIsThePersonsChoice() {
        assertEquals("GE-Proton9-20", SteamProton.defaultTool(config))
        assertNull(SteamProton.defaultTool("\"InstallConfigStore\" { \"Software\" { } }"))
        assertNull(SteamProton.defaultTool(""))
    }

    @Test
    fun addedToolsAreNamedByTheirOwnFileAndValvesByTheirFolder() {
        // As ValveSoftware/Proton's compatibilitytool.vdf.template writes it.
        val vdf = """
            "compatibilitytools"
            {
              "compat_tools"
              {
                "GE-Proton9-20"
                {
                  "install_path" "."
                  "display_name" "GE-Proton9-20"
                  "from_oslist"  "windows"
                  "to_oslist"    "linux"
                }
              }
            }
        """.trimIndent()
        val ge = SteamProton.declared(vdf, "/s/compatibilitytools.d/GE-Proton9-20").single()
        assertEquals("/s/compatibilitytools.d/GE-Proton9-20" to listOf(9, 20), ge.folder to ge.version)
        assertEquals(ProtonTool("proton_9", "/c/Proton 9.0 (Beta)", listOf(9, 0)), SteamProton.valve("Proton 9.0 (Beta)", "/c/Proton 9.0 (Beta)"))
        assertEquals("proton_63", SteamProton.valve("Proton 6.3", "/c/Proton 6.3")?.name)
        assertEquals("proton_experimental", SteamProton.valve("Proton - Experimental", "/c/x")?.name)
        assertEquals("proton_hotfix", SteamProton.valve("Proton Hotfix", "/c/x")?.name)
        assertNull(SteamProton.valve("Steam Linux Runtime 3.0 (sniper)", "/c/x"))
    }

    @Test
    fun thePersonsChoiceElseTheNewestWithAVersion() {
        val valve9 = ProtonTool("proton_9", "/c/Proton 9.0", listOf(9, 0))
        val valve8 = ProtonTool("proton_8", "/c/Proton 8.0", listOf(8, 0))
        val ge = ProtonTool("ge-proton9-20", "/s/GE-Proton9-20", listOf(9, 20))
        val experimental = ProtonTool("proton_experimental", "/c/Proton - Experimental", emptyList())
        val all = listOf(valve8, experimental, valve9, ge)
        assertEquals(valve8, SteamProton.choose(all, "proton_8"))
        assertEquals(ge, SteamProton.choose(all, "GE-Proton9-20"), "names compared as Steam's are, letter case aside")
        assertEquals(experimental, SteamProton.choose(all, "proton_experimental"))
        assertEquals(ge, SteamProton.choose(all, null), "the newest, 9.20 over 9.0")
        assertEquals(ge, SteamProton.choose(all, "proton_10"), "a choice no longer installed: the newest")
        assertNull(SteamProton.choose(listOf(experimental), null))
    }
}
