package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.launch.linux.LinuxEnvironment
import io.github.matiyaaa.fuse.model.EmulatorId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The version of an emulator installed outside Fuse, so the Store can say when it is behind. */
class LinuxVersionsTest {
    @Test
    fun readsFlatpakVersions() {
        val out = "Application ID           Version\norg.azahar_emu.Azahar    2125.0.1\norg.libretro.RetroArch\nnet.pcsx2.PCSX2          v2.2.0\n"
        assertEquals(mapOf("org.azahar_emu.Azahar" to "2125.0.1", "net.pcsx2.PCSX2" to "v2.2.0"), LinuxDetector.parseFlatpakVersions(out))
    }

    @Test
    fun readsTheVersionAnAppImagesNameCarries() {
        assertEquals("2.6", LinuxDetector.versionInName("/home/deck/Applications/Cemu-2.6-x86_64.AppImage"))
        assertEquals("2.2.0", LinuxDetector.versionInName("PCSX2-v2.2.0-linux-appimage-x64-Qt.AppImage"))
        assertEquals("0.0.34", LinuxDetector.versionInName("rpcs3-v0.0.34-17123-abcd_linux64.AppImage"))
        assertNull(LinuxDetector.versionInName("/home/deck/Applications/azahar.AppImage"))
        assertNull(LinuxDetector.versionInName("DuckStation-x64.AppImage"))
    }

    @Test
    fun aFlatpakAzaharIsFoundWithItsVersion() {
        val env = object : LinuxEnvironment {
            override val homeDir = "/home/deck"
            override fun pathDirectories() = emptyList<String>()
            override fun isExecutable(path: String) = false
            override fun exists(path: String) = false
            override fun listFiles(dir: String) = emptyList<String>()
            override fun flatpakApps() = setOf("org.azahar_emu.Azahar")
            override fun flatpakVersions() = mapOf("org.azahar_emu.Azahar" to "2125.0.1")
            override fun readText(path: String): String? = null
        }
        val azahar = LinuxDetector.detect(env).single { it.id == EmulatorId("linux.azahar") }
        assertEquals("org.azahar_emu.Azahar", azahar.appId)
        assertEquals("2125.0.1", azahar.version)
    }
}
