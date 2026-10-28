package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.launch.patches.IniText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuse's hotkeys written into an emulator's own file beside the person's bindings, as the emulator
 * writes its own, and taken out again leaving the file exactly as it was.
 */
class HotkeysTest {
    @Test
    fun duckStationGetsEachChordBesideThePersonsOwnBinding() {
        val text = "[Main]\nSettingsVersion = 3\n\n[Hotkeys]\nOpenPauseMenu = Keyboard/Escape\nSaveSelectedSaveState = Keyboard/F2\n"
        val ini = IniText(text)
        val applied = Hotkeys.apply("duckstation", ini, emptySet(), emptySet())
        assertTrue(applied.changed)
        assertEquals(listOf("Keyboard/Escape", "SDL-0/Back & SDL-0/Y"), ini.values("Hotkeys", "OpenPauseMenu"))
        assertEquals(listOf("Keyboard/F2", "SDL-0/Back & SDL-0/RightShoulder"), ini.values("Hotkeys", "SaveSelectedSaveState"))
        assertEquals(listOf("SDL-0/Back & SDL-0/Start"), ini.values("Hotkeys", "PowerOff"))
        assertEquals(listOf("SDL-0/Back & SDL-0/LeftShoulder"), ini.values("Hotkeys", "LoadSelectedSaveState"))
        assertEquals(4, applied.added.size)
        // Again: nothing more, nothing noted twice.
        val again = Hotkeys.apply("duckstation", ini, applied.added, applied.records)
        assertFalse(again.changed)
        assertEquals(applied.added, again.added)
        // Taken out: the file is as it was, byte for byte.
        Hotkeys.restore(ini, again.added, again.records)
        assertEquals(text, ini.toString())
    }

    @Test
    fun aSectionFuseMadeGoesAgainAndAChordThatWasThereStays() {
        val bare = "[EmuCore]\nSaveStateOnShutdown = false\n"
        val ini = IniText(bare)
        val applied = Hotkeys.apply("pcsx2", ini, emptySet(), emptySet())
        assertEquals(listOf("SDL-0/Back & SDL-0/Start"), ini.values("Hotkeys", "ShutdownVM"))
        Hotkeys.restore(ini, applied.added, applied.records)
        assertEquals(bare, ini.toString())
        // Someone who bound Select + Start themselves keeps it when Fuse's are taken out.
        val own = "[Hotkeys]\nShutdownVM = SDL-0/Back & SDL-0/Start\n"
        val mine = IniText(own)
        val kept = Hotkeys.apply("pcsx2", mine, emptySet(), emptySet())
        assertTrue(kept.added.none { it.contains("ShutdownVM") })
        Hotkeys.restore(mine, kept.added, kept.records)
        assertEquals(own, mine.toString())
    }

    @Test
    fun retroArchsQuitComboIsGivenOnlyWhereNoneIsSet() {
        val none = CfgText("input_quit_gamepad_combo = \"0\"\nvideo_fullscreen = \"true\"\n")
        val applied = Hotkeys.apply("retroarch", none, emptySet(), emptySet())
        assertEquals("4", none.values("", "input_quit_gamepad_combo").single())
        assertTrue(applied.kept.isEmpty())
        Hotkeys.restore(none, applied.added, applied.records)
        assertEquals("input_quit_gamepad_combo = \"0\"\nvideo_fullscreen = \"true\"\n", none.toString())
        // L1 + R1 chosen by the person stays theirs.
        val theirs = CfgText("input_quit_gamepad_combo = \"6\"\n")
        val left = Hotkeys.apply("retroarch", theirs, emptySet(), emptySet())
        assertEquals(setOf(Hotkeys.Action.QUIT), left.kept)
        assertFalse(left.changed)
        assertEquals("6", theirs.values("", "input_quit_gamepad_combo").single())
    }

    @Test
    fun anEmulatorWithoutFusesLayoutGetsNothing() {
        val ini = IniText("[General]\nA = 1\n")
        assertFalse(Hotkeys.apply("dolphin", ini, emptySet(), emptySet()).changed)
        assertEquals(emptyList(), Hotkeys.actionsFor("ppsspp"))
        assertEquals(Hotkeys.Action.entries.toList(), Hotkeys.actionsFor("duckstation"))
    }
}
