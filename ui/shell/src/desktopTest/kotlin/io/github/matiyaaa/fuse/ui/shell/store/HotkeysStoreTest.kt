package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.patches.DuckStationHome
import io.github.matiyaaa.fuse.launch.patches.RetroArchHome
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The same hotkeys in every emulator, end to end: written into DuckStation's settings.ini beside the
 * person's own and into RetroArch's quit combo, then taken out again, leaving each file as it was.
 */
class HotkeysStoreTest {
    private lateinit var cache: File
    private lateinit var emu: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-hotkeys-cache").toFile()
        emu = Files.createTempDirectory("fuse-hotkeys-emu").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(cache, emu).forEach { it.deleteRecursively() }
    }

    @Test
    fun hotkeysGoInBesideThePersonsOwnAndComeOutAgain(): Unit = runBlocking {
        val duck = InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH")
        val ra = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", platforms = setOf(PlatformId("snes")), detectedVia = "PATH")
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, installedEmulators = listOf(duck, ra))
        val ds = File(emu, "duckstation").apply { mkdirs() }
        val ini = File(ds, "settings.ini")
        val iniText = "[Main]\nSettingsVersion = 3\n\n[Hotkeys]\nOpenPauseMenu = Keyboard/Escape\n"
        ini.writeText(iniText)
        services.duckstationHome = DuckStationHome(ds.absolutePath, "${ds.absolutePath}/gamesettings", "${ds.absolutePath}/cheats", "${ds.absolutePath}/patches", null, null)
        val raDir = File(emu, "retroarch").apply { mkdirs() }
        val cfg = File(raDir, "retroarch.cfg")
        val cfgText = "input_quit_gamepad_combo = \"0\"\nvideo_fullscreen = \"true\"\n"
        cfg.writeText(cfgText)
        services.retroArchHome = RetroArchHome(cfg.absolutePath, "${raDir.absolutePath}/cheats", raDir.absolutePath)
        val store = createFuseStore(services, scope)

        assertEquals(setOf("DuckStation" to "duckstation", "RetroArch" to "retroarch"), store.library.hotkeysHere().map { it.name to it.family }.toSet())
        val on = store.library.applyHotkeys(true)
        assertEquals(setOf("DuckStation", "RetroArch"), on.map { it.emulator }.toSet())
        assertTrue(on.all { it.kept.isEmpty() })
        val written = ini.readText()
        assertTrue("OpenPauseMenu = Keyboard/Escape\n" in written, written)
        assertTrue("OpenPauseMenu = SDL-0/Back & SDL-0/Y\n" in written, written)
        assertTrue("PowerOff = SDL-0/Back & SDL-0/Start\n" in written, written)
        assertTrue("input_quit_gamepad_combo = \"4\"" in cfg.readText())
        // Again changes nothing.
        store.library.applyHotkeys(true)
        assertEquals(written, ini.readText())

        // Off: both files exactly as they were.
        assertEquals(setOf("DuckStation", "RetroArch"), store.library.applyHotkeys(false).map { it.emulator }.toSet())
        assertEquals(iniText, ini.readText())
        assertEquals(cfgText, cfg.readText())
        assertEquals(emptyList(), store.library.applyHotkeys(false))
    }
}
