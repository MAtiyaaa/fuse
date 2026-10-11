package io.github.matiyaaa.fuse.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The likely reason an emulator stopped as it started, only from what it wrote. */
class LaunchDiagnosisTest {
    private fun cause(output: String, exit: Int? = 1, command: List<String> = listOf("/usr/bin/emu", "/home/mo/roms/game.iso")) =
        LaunchDiagnosis.cause(LaunchDiagnostic(command, exit, output))

    @Test
    fun whatTheEmulatorWroteNamesTheCause() {
        assertEquals(LaunchCause.CORE_MISSING, cause("[ERROR] Failed to load libretro core \"snes9x_libretro.so\"."))
        assertEquals(LaunchCause.FIRMWARE, cause("Error: No BIOS found for region NTSC-U (SCPH1001.BIN)"))
        assertEquals(LaunchCause.FIRMWARE, cause("prod.keys could not be found"))
        assertEquals(LaunchCause.GRAPHICS, cause("vkCreateInstance failed: VK_ERROR_INCOMPATIBLE_DRIVER"))
        assertEquals(LaunchCause.GRAPHICS, cause("Failed to create OpenGL context"))
        assertEquals(LaunchCause.LIBRARY, cause("emu: error while loading shared libraries: libQt6Core.so.6: cannot open shared object file"))
        assertEquals(LaunchCause.LIBRARY, cause("/lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_2.38' not found"))
        assertEquals(LaunchCause.PERMISSION, cause("Could not read /home/mo/roms: Permission denied"))
        assertEquals(LaunchCause.GAME_FILE, cause("Failed to open /home/mo/roms/game.iso: No such file or directory"))
        assertEquals(LaunchCause.CRASH, cause("Segmentation fault (core dumped)", 139))
        assertEquals(LaunchCause.CRASH, cause("", 139))
        assertEquals(LaunchCause.UNKNOWN, cause("Goodbye", 1), "nothing it wrote says why: no guess")
    }

    @Test
    fun theReportNamesTheCommandWithoutTheHomeFolder() {
        val d = LaunchDiagnostic(listOf("/home/mo/Applications/Emu.AppImage", "/home/mo/roms/My Game.iso"), 1, "line 1\nline 2\n")
        val report = LaunchDiagnosis.report(d, "Emu", "1.2", LaunchCause.UNKNOWN, "/home/mo")
        assertTrue("Command: ~/Applications/Emu.AppImage \"~/roms/My Game.iso\"" in report, report)
        assertTrue("Exit code: 1" in report)
        assertTrue("line 2" in report)
        assertFalse("/home/mo" in report)
        assertTrue(LaunchDiagnosis.explain(LaunchCause.GRAPHICS, "PCSX2").contains("renderer"))
    }
}
