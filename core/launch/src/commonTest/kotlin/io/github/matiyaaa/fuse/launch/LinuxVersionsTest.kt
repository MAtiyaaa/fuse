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

    /** A home with [files] (path to text), [programs] runnable, and what each prints for its version flag. */
    private fun home(files: Map<String, String> = emptyMap(), programs: Set<String> = emptySet(), printed: Map<String, String> = emptyMap(), listed: Set<String> = emptySet()) = object : LinuxEnvironment {
        override val homeDir = "/home/deck"
        override fun pathDirectories() = listOf("/usr/bin")
        override fun isExecutable(path: String) = path in programs
        override fun exists(path: String) = path in programs || files.keys.any { it == path || it.startsWith("$path/") }
        override fun listFiles(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct()
        override fun flatpakApps() = listed
        override fun readText(path: String): String? = files[path]
        override fun versionOutput(path: String, flag: String): String? = printed["$path $flag"]
    }

    private val dolphinInfo = """
        <component type="desktop-application">
          <id>org.DolphinEmu.dolphin-emu</id>
          <releases>
            <release version="2609a" date="2026-09-20"/>
            <release version="2609" date="2026-09-01"/>
          </releases>
        </component>
    """.trimIndent()

    @Test
    fun aFlatpakIsFoundByItsFolderWithTheVersionItsOwnAppStreamFileGives() {
        // No flatpak command to ask: the deployment in use, and its AppStream file.
        val root = "/var/lib/flatpak/app/org.DolphinEmu.dolphin-emu/current/active"
        val env = home(files = mapOf("$root/metadata" to "[Application]", "$root/files/share/metainfo/org.DolphinEmu.dolphin-emu.metainfo.xml" to dolphinInfo))
        assertEquals(setOf("org.DolphinEmu.dolphin-emu"), LinuxDetector.flatpakFolders(env))
        val dolphin = LinuxDetector.detect(env).single { it.id == EmulatorId("linux.dolphin") }
        assertEquals("Flatpak" to "2609a", dolphin.detectedVia to dolphin.version, "the newest release, listed first")
    }

    @Test
    fun aPackagedProgramsVersionIsInTheAppStreamFileItsPackagePutBesideIt() {
        val env = home(files = mapOf("/usr/share/metainfo/org.DolphinEmu.dolphin-emu.metainfo.xml" to dolphinInfo), programs = setOf("/usr/bin/dolphin-emu"))
        val dolphin = LinuxDetector.detect(env).single { it.id == EmulatorId("linux.dolphin") }
        assertEquals("PATH" to "2609a", dolphin.detectedVia to dolphin.version)
    }

    @Test
    fun elseTheProgramSaysItsVersionWhereItPrintsItAndExits() {
        val env = home(
            programs = setOf("/usr/bin/retroarch", "/usr/bin/dolphin-emu"),
            printed = mapOf("/usr/bin/retroarch --version" to "RetroArch - libretro frontend\nVersion: 1.22.2 (Git 1a2b3c4)"),
        )
        val found = LinuxDetector.detect(env)
        assertEquals("1.22.2", found.single { it.id == EmulatorId("linux.retroarch") }.version)
        // Dolphin starts its window toolkit before it reads its flags, so it is never run to ask.
        assertNull(found.single { it.id == EmulatorId("linux.dolphin") }.version)
        assertEquals("1.20.4", LinuxDetector.versionInOutput("v1.20.4-12-gabcdef"))
        assertNull(LinuxDetector.metainfoVersion("<component><release version=\"1.0\"/></component>"), "a release outside <releases> isn't one")
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
