package io.github.matiyaaa.fuse.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Find Where It Saves: a test run's changed files say where a game's save really is. */
class SaveProbeTest {
    private lateinit var home: File

    @BeforeTest fun setUp() { home = Files.createTempDirectory("probe").toFile() }

    @AfterTest fun tearDown() { home.deleteRecursively() }

    private fun env(folders: Map<String, String> = emptyMap()): SaveEnvironment =
        WithSaveFolders(FileSaveEnvironment("LINUX", home.path.replace('\\', '/'), variables = { null })) { folders }

    private fun path(f: File) = f.path.replace('\\', '/')

    /** Writes [f] as a change made after the run started. */
    private fun write(f: File, text: String) {
        f.parentFile.mkdirs()
        f.writeText(text)
        f.setLastModified(System.currentTimeMillis() + 10_000)
    }

    private fun gba(): Pair<SaveQuery, File> {
        val roms = File(home, "emulation/gba/roms").apply { mkdirs() }
        File(roms, "ruby.gba").writeText("rom")
        File(roms, "ruby.cfg").writeText("old")
        return SaveQuery(GameKey.of("gba", null, null, "Pokemon Ruby"), "gba", path(File(roms, "ruby.gba")), "gba-emu", title = "Pokemon Ruby") to roms
    }

    @Test
    fun aSaveWhereFuseLooksIsAlreadyKnown() {
        val (q, roms) = gba()
        val e = env()
        val before = SaveProbe.snapshot(SaveProbe.roots(q, e), e)
        write(File(roms, "ruby.sav"), "8 badges")
        val found = assertIs<SaveProbe.Finding.Known>(SaveProbe.conclude(q, e, SaveProbe.changed(before, e)))
        assertEquals(listOf(path(File(roms, "ruby.sav"))), found.paths)
    }

    @Test
    fun aSaveInAnotherFolderTeachesTheEmulatorsSaveFolder() {
        val (q, _) = gba()
        val e = env()
        val roots = SaveProbe.roots(q, e)
        // The game's folder and the one above it (where an emulator's own "saves" folder sits).
        assertTrue(roots.contains(path(File(home, "emulation/gba"))), "$roots")
        val before = SaveProbe.snapshot(roots, e)
        val saves = File(home, "emulation/gba/saves")
        write(File(saves, "ruby.sav"), "8 badges")
        val found = assertIs<SaveProbe.Finding.Folder>(SaveProbe.conclude(q, e, SaveProbe.changed(before, e)))
        assertEquals(path(saves), found.folder)
        // Chosen as the emulator's save folder, Fuse now finds the save there.
        val spot = SaveAdapters.forEmulator(q.emulatorId)!!.locate(q, env(mapOf("gba-emu" to found.folder))).single()
        assertEquals(path(File(saves, "ruby.sav")), spot.files.single().path)
    }

    @Test
    fun aSaveWhoseGameIdCantBeReadTeachesItsOwnFolders() {
        val stick = File(home, "psp/memstick").apply { File(this, "PSP/SAVEDATA/OTHER00000").mkdirs() }
        val e = env(mapOf("ppsspp" to path(stick)))
        val q = SaveQuery(GameKey.of("psp", null, null, "Patapon"), "psp", path(File(home, "psp/roms/patapon.iso")), "ppsspp", title = "Patapon")
        val before = SaveProbe.snapshot(SaveProbe.roots(q, e), e)
        write(File(stick, "PSP/SAVEDATA/UCUS98711DATA/DATA.BIN"), "save")
        write(File(stick, "PSP/SAVEDATA/UCUS98711DATA/PARAM.SFO"), "param")
        val found = assertIs<SaveProbe.Finding.Learned>(SaveProbe.conclude(q, e, SaveProbe.changed(before, e)))
        assertEquals("psp.savedata", found.format)
        assertEquals(listOf("UCUS98711DATA"), found.folders)
    }

    @Test
    fun filesFuseCantUseChangeNothing() {
        val (q, roms) = gba()
        val e = env()
        val before = SaveProbe.snapshot(SaveProbe.roots(q, e), e)
        write(File(roms, "ruby-highscores.bin"), "x")
        val seen = assertIs<SaveProbe.Finding.Unusable>(SaveProbe.conclude(q, e, SaveProbe.changed(before, e)))
        assertEquals(listOf(path(File(roms, "ruby-highscores.bin"))), seen.seen)
    }

    @Test
    fun settingsAndLogsAreNeverTakenForASave() {
        val (q, roms) = gba()
        val e = env()
        val before = SaveProbe.snapshot(SaveProbe.roots(q, e), e)
        write(File(roms, "ruby.cfg"), "new")
        write(File(roms, "emulator.log"), "log")
        assertEquals(SaveProbe.Finding.Nothing, SaveProbe.conclude(q, e, SaveProbe.changed(before, e)))
    }

    @Test
    fun theHomeFolderItselfIsNeverWatched() {
        File(home, "ruby.gba").writeText("rom")
        val q = SaveQuery(GameKey.of("gba", null, null, "Pokemon Ruby"), "gba", path(File(home, "ruby.gba")), "gba-emu")
        val roots = SaveProbe.roots(q, env())
        assertTrue(roots.none { it == path(home) }, "$roots")
    }
}
