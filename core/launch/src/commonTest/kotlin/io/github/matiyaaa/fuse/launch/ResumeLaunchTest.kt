package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.launch.android.AndroidIntentAdapter
import io.github.matiyaaa.fuse.launch.linux.LinuxCatalog
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Continue: each emulator that can start a game from its save state is asked to in its own way,
 * just before the game's file, and only when the person continues.
 */
class ResumeLaunchTest {
    private fun linux(id: String) = LinuxCatalog.adapters.first { it.id == EmulatorId(id) }

    private fun argv(id: String, platform: String, rom: String, app: String, resume: Resume?, home: String? = null) =
        assertIs<LaunchPlan.Command>(linux(id).plan(LaunchRequest(game(platform, rom), linuxEmu(id, app), LaunchTarget.File(rom), LaunchOptions(homeDir = home, resume = resume)))).argv

    @Test
    fun duckStationLoadsItsOwnResumeState() {
        val rom = "/g/psx/a.chd"
        assertEquals(listOf("/usr/bin/duckstation-qt", "-batch", "-resume", rom), argv("linux.duckstation", "psx", rom, "/usr/bin/duckstation-qt", Resume("/s/SLUS-00001_resume.sav")))
        assertEquals(listOf("/usr/bin/duckstation-qt", "-batch", rom), argv("linux.duckstation", "psx", rom, "/usr/bin/duckstation-qt", null))
    }

    @Test
    fun pcsx2IsGivenTheStateFile() {
        val rom = "/g/ps2/a.chd"
        val state = "/home/u/.config/PCSX2/sstates/SLUS-20946 (2C8A0D9B).resume.p2s"
        assertEquals(listOf("/usr/bin/pcsx2-qt", "-batch", "-statefile", state, rom), argv("linux.pcsx2", "ps2", rom, "/usr/bin/pcsx2-qt", Resume(state)))
    }

    @Test
    fun retroArchLaysFusesSettingsOverThePersons() {
        val rom = "/g/gba/a.gba"
        val args = argv("linux.retroarch", "gba", rom, "/usr/bin/retroarch", Resume("/s/a.state.auto", "/cache/resume/retroarch.cfg"), home = "/home/u")
        assertEquals(listOf("/usr/bin/retroarch", "-L", "/home/u/.config/retroarch/cores/mgba_libretro.so", "--appendconfig", "/cache/resume/retroarch.cfg", rom), args)
        // Without the settings file there is nothing to load it with: the game starts as always.
        val bare = argv("linux.retroarch", "gba", rom, "/usr/bin/retroarch", Resume("/s/a.state.auto"), home = "/home/u")
        assertEquals(listOf("/usr/bin/retroarch", "-L", "/home/u/.config/retroarch/cores/mgba_libretro.so", rom), bare)
    }

    @Test
    fun windowsAndMacOsKeepTheWay() {
        val windows = io.github.matiyaaa.fuse.launch.desktop.WindowsCatalog.adapters.first { it.id.value.endsWith(".duckstation") }
        assertEquals(ResumeWay.OWN_RESUME, windows.resumeWay)
        val mac = io.github.matiyaaa.fuse.launch.desktop.MacCatalog.adapters.first { it.id.value.endsWith(".pcsx2") }
        assertEquals(ResumeWay.STATE_FILE, mac.resumeWay)
    }

    @Test
    fun androidDuckStationTurnsItsOwnFlagOn() {
        val adapter = AndroidEmulatorCatalog.adapters.first { it.id == EmulatorId("duckstation") } as AndroidIntentAdapter
        val rom = "$ROMS/psx/a.chd"
        fun bools(resume: Resume?) = assertNotNull(
            adapter.planAndroid(LaunchRequest(game("psx", rom), androidEmu("duckstation", "com.github.stenzek.duckstation"), LaunchTarget.File(rom), LaunchOptions(resume = resume))).intent,
        ).intent.boolExtras
        assertEquals(mapOf("resumeState" to true), bools(Resume("/s/x_resume.sav")))
        assertEquals(mapOf("resumeState" to false), bools(null))
    }

    @Test
    fun emulatorsWithoutAWayAreNeverAsked() {
        assertNull(linux("linux.dolphin").resumeWay)
        val resolver = LinuxCatalog.adapters.filter { it.resumeWay != null }.map { it.id.value }.toSet()
        assertEquals(setOf("linux.retroarch", "linux.pcsx2", "linux.duckstation"), resolver)
    }
}
