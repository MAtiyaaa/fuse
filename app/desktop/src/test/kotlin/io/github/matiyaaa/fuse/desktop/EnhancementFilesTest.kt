package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopEmulatorFiles
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.launch.patches.PlannedFile
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.SourceForm
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A person's own files put in an emulator's folders, all or nothing, with a restoration point to put everything back. */
class EnhancementFilesTest {
    private val home = Files.createTempDirectory("fuse-enh").toFile()
    private val backups = File(home, "fuse/emulator-backups")
    private val data = File(home, ".config/PCSX2").apply { File(this, "inis").mkdirs() }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun p(f: File) = f.absolutePath.replace('\\', '/')

    private suspend fun files(): DesktopEmulatorFiles {
        File(data, "inis/PCSX2.ini").writeText("")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        // Finding PCSX2 is what lets Fuse write inside its folder.
        files.pcsx2(InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH"))!!
        return files
    }

    private fun zip(name: String, entries: Map<String, String>): File {
        val f = File(home, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((k, v) in entries) {
                z.putNextEntry(ZipEntry(k))
                z.write(v.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun foldersAndArchivesAreListedWithoutTheirLinks() = runBlocking {
        val files = files()
        val pack = File(home, "pack").apply { File(this, "replacements/hud").mkdirs() }
        File(pack, "replacements/hud/a.png").writeText("png")
        File(pack, "readme.txt").writeText("hi")
        runCatching { Files.createSymbolicLink(File(pack, "replacements/link.png").toPath(), File(home, ".config").toPath()) }
        val listed = files.inspect(p(pack))!!
        assertEquals(SourceForm.FOLDER, listed.form)
        assertEquals(listOf("readme.txt", "replacements/hud/a.png"), listed.entries)
        val archive = files.inspect(p(zip("p.zip", mapOf("SLUS-20062/replacements/b.png" to "b", "../evil.png" to "x"))))!!
        assertEquals(SourceForm.ARCHIVE, archive.form)
        assertEquals(listOf("SLUS-20062/replacements/b.png", "../evil.png"), archive.entries)
        assertEquals("hi", files.sourceText(p(pack), "readme.txt"))
        assertNull(files.sourceText(p(pack), "../pack/readme.txt"), "never above the chosen folder")
        assertNull(files.inspect(p(File(home, "missing"))))
    }

    @Test
    fun installKeepsWhatItReplacesAndUndoPutsItBack() = runBlocking {
        val files = files()
        val target = File(data, "textures/SLUS-20062/replacements")
        File(target, "old").mkdirs()
        File(target, "old/b.png").writeText("theirs")
        val src = zip("p.zip", mapOf("r/a.png" to "A", "r/old/b.png" to "B"))
        val settings = File(data, "gamesettings/SLUS-20062_ABCD1234.ini")
        val ok = files.install(
            p(src),
            listOf(PlannedFile("r/a.png", p(File(target, "deep/a.png"))), PlannedFile("r/old/b.png", p(File(target, "old/b.png")))),
            mapOf(p(settings) to "[EmuCore/GS]\nLoadTextureReplacements = true\n"),
            "point-1",
        )
        assertTrue(ok)
        assertEquals("A", File(target, "deep/a.png").readText())
        assertEquals("B", File(target, "old/b.png").readText())
        assertTrue(settings.isFile)
        // The settings file is put back by Fuse itself (its own records), so it is kept here.
        assertTrue(files.undo("point-1", keep = setOf(p(settings))))
        assertFalse(File(target, "deep/a.png").exists())
        assertFalse(File(target, "deep").exists(), "the folder Fuse made goes once empty")
        assertEquals("theirs", File(target, "old/b.png").readText())
        assertTrue(settings.isFile)
        assertFalse(File(home, "fuse/restore-points/point-1").exists())
        assertFalse(files.undo("point-1"), "nothing left to undo")
    }

    @Test
    fun nothingIsWrittenOutsideTheEmulatorsFolders() = runBlocking {
        val files = files()
        val src = zip("p.zip", mapOf("a.png" to "A"))
        val outside = File(home, "elsewhere/a.png")
        assertFalse(files.install(p(src), listOf(PlannedFile("a.png", p(outside))), emptyMap(), "point-2"))
        assertFalse(outside.exists())
        val sneaky = p(File(data, "textures")) + "/../../../elsewhere/b.png"
        assertFalse(files.install(p(src), listOf(PlannedFile("a.png", sneaky)), emptyMap(), "point-3"))
        assertFalse(File(home, "elsewhere/b.png").exists())
        assertFalse(files.install(p(src), listOf(PlannedFile("a.png", p(File(data, "x.png")))), emptyMap(), "../point"), "restoration point names are Fuse's own")
    }

    @Test
    fun aFailedCopyPutsBackWhatWasDone() = runBlocking {
        val files = files()
        val first = File(data, "cheats/a.pnach").apply { parentFile.mkdirs(); writeText("mine") }
        val src = zip("p.zip", mapOf("a.pnach" to "new"))
        val ok = files.install(
            p(src),
            listOf(PlannedFile("a.pnach", p(first)), PlannedFile("missing.pnach", p(File(data, "cheats/b.pnach")))),
            emptyMap(),
            "point-4",
        )
        assertFalse(ok)
        assertEquals("mine", first.readText())
        assertFalse(File(data, "cheats/b.pnach").exists())
        assertFalse(File(home, "fuse/restore-points/point-4").exists())
    }

    @Test
    fun aSingleFileIsCopiedAsItIs() = runBlocking {
        val files = files()
        val cheat = File(home, "My Cheats.pnach").apply { writeText("[x]\npatch=1,EE,0,word,0\n") }
        val listed = files.inspect(p(cheat))!!
        assertEquals(SourceForm.FILE, listed.form)
        assertEquals(listOf("My Cheats.pnach"), listed.entries)
        assertEquals(cheat.readText(), files.sourceText(p(cheat), "My Cheats.pnach"))
        val dest = File(data, "cheats/SLUS-20062_ABCD1234_My Cheats.pnach")
        assertTrue(files.install(p(cheat), listOf(PlannedFile("", p(dest))), emptyMap(), "point-5"))
        assertEquals(cheat.readText(), dest.readText())
        assertTrue(files.undo("point-5"))
        assertFalse(dest.exists())
        assertTrue(cheat.isFile, "the person's own file is never touched")
    }

    @Test
    fun onlySecureAddressesAreFetched() = runBlocking {
        val files = files()
        assertNull(files.fetch("http://example.com/x.zip", "x.zip", null, null, 1_000))
        assertNull(files.fetch("file:///etc/passwd", "x.zip", null, null, 1_000))
        assertFalse(File(home, "fuse/fetched-mods").listFiles().orEmpty().any { it.listFiles().orEmpty().isNotEmpty() })
    }

    @Test
    fun aShadPs4ModGoesBesideTheGameAndComesOutAfterARestart(): Unit = runBlocking {
        val game = File(home, "games/ps4/CUSA00001").apply { File(this, "sce_sys").mkdirs() }
        File(game, "eboot.bin").writeText("game")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val (mods, top) = files.shadPs4Mods(p(File(game, "eboot.bin")))!!
        assertEquals(p(File(home, "games/ps4/CUSA00001-mods")), mods)
        assertEquals(setOf("eboot.bin", "sce_sys"), top)
        val mod = zip("mod.zip", mapOf("sce_sys/icon0.png" to "icon"))
        assertTrue(files.install(mod.absolutePath, listOf(PlannedFile("sce_sys/icon0.png", "$mods/sce_sys/icon0.png")), emptyMap(), "rp-shad"))
        assertEquals("icon", File(mods, "sce_sys/icon0.png").readText())
        assertEquals("game", File(game, "eboot.bin").readText(), "the game's own files stay as they are")
        // Fuse restarted: it hasn't looked for the game's folder again, and taking the mod out still works.
        val later = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        assertTrue(later.undo("rp-shad"))
        assertFalse(File(mods, "sce_sys/icon0.png").exists())
        assertFalse(File(mods).exists(), "the folders Fuse made go once empty")
        // A folder that isn't a game is no place for mods.
        assertNull(files.shadPs4Mods(p(File(home, "games/ps4"))))
    }

    @Test
    fun rpcs3ModsReplaceTheGamesOwnFilesKeepingThemToPutBack(): Unit = runBlocking {
        val usr = File(home, "games/ps3/Demon/PS3_GAME/USRDIR").apply { mkdirs() }
        File(usr, "EBOOT.BIN").writeText("boot")
        File(usr, "data.pak").writeText("original")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = backups)
        val rpcs3 = InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH")
        val target = files.rpcs3GameFiles(rpcs3, p(File(usr, "EBOOT.BIN")), "BLUS30443")!!
        assertEquals(p(usr), target)
        val mod = zip("ps3mod.zip", mapOf("PS3_GAME/USRDIR/data.pak" to "modded"))
        assertTrue(files.install(mod.absolutePath, listOf(PlannedFile("PS3_GAME/USRDIR/data.pak", "$target/data.pak")), emptyMap(), "rp-ps3"))
        assertEquals("modded", File(usr, "data.pak").readText())
        assertTrue(files.undo("rp-ps3"))
        assertEquals("original", File(usr, "data.pak").readText())
    }
}
