package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetroArchCoreDirTest {
    @Test
    fun theCoreFolderRetroArchsOwnSettingsName() {
        assertEquals("~/Emulation/cores", LinuxDetector.libretroDirectory("video_driver = \"gl\"\nlibretro_directory = \"~/Emulation/cores\"\n"))
        assertNull(LinuxDetector.libretroDirectory("libretro_directory = \"default\"\n"))
        assertNull(LinuxDetector.libretroDirectory("libretro_info_path = \"~/x\"\n"))
    }
}
