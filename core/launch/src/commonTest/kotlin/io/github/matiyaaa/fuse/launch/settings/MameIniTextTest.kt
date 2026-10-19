package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** MAME's ini files as core_options::output_ini writes them, changed in place and put back. */
class MameIniTextTest {
    private val ini = "#\n# CORE CONFIGURATION OPTIONS\n#\nreadconfig                1\nwriteconfig               0\n\n#\n# CORE SEARCH PATH OPTIONS\n#\nrompath                   \"/home/p/My Roms\"\nvideo                     auto\n"

    @Test
    fun untouchedItIsTheSameBytesAndValuesReadAsWritten() {
        val m = MameIniText(ini)
        assertEquals(ini, m.toString())
        assertEquals(listOf("1"), m.values("", "readconfig"))
        assertEquals(listOf("/home/p/My Roms"), m.values("", "rompath"), "quotes taken off")
        assertEquals(emptyList(), m.values("", "bgfx_backend"))
    }

    @Test
    fun aValueIsChangedWhereItIsAndANewOneGoesLastAsMameLaysItOut() {
        val m = MameIniText(ini)
        m.set("", "video", "opengl")
        m.set("", "artwork_crop", "1")
        m.set("", "bgfx_screen_chains", "crt geom")
        assertEquals(ini.replace("video                     auto", "video                     opengl") + "artwork_crop              1\nbgfx_screen_chains        \"crt geom\"\n", m.toString())
    }

    @Test
    fun putBackLeavesTheFileAsItWasAndASystemsFileFromNothing() {
        val m = MameIniText(ini)
        var records = SettingsEdit.change(m, emptySet(), "", "writeconfig", "1")
        records = SettingsEdit.change(m, records, "", "waitvsync", "1")
        SettingsEdit.restore(m, records)
        assertEquals(ini, m.toString())
        val own = MameIniText("")
        own.set("", "waitvsync", "1")
        assertEquals("waitvsync                 1\n", own.toString())
        own.removeKey("", "waitvsync")
        assertFalse(own.hasEntries())
    }
}
