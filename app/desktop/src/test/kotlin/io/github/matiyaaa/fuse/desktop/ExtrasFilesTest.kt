package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopArchives
import io.github.matiyaaa.fuse.desktop.services.DesktopEmulatorFiles
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.launch.patches.PlannedFile
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

/**
 * Archives unpacked whole or not at all, folders an emulator replaces whole (Xenia's patches, a
 * Switch emulator's firmware) put back as they were, and firmware placed where each emulator reads it.
 */
class ExtrasFilesTest {
    private val home = Files.createTempDirectory("fuse-extras").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun zip(name: String, entries: Map<String, String>): File = File(home, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            for ((k, v) in entries) {
                z.putNextEntry(ZipEntry(k))
                z.write(v.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun sevenZip(name: String, entries: Map<String, String>): File = File(home, name).also { f ->
        SevenZOutputFile(f).use { out ->
            for ((k, v) in entries) {
                val src = File(home, "src-${k.replace('/', '_')}").apply { writeText(v) }
                val e = out.createArchiveEntry(src, k)
                out.putArchiveEntry(e)
                out.write(src.readBytes())
                out.closeArchiveEntry()
            }
        }
    }

    @Test
    fun zipAnd7zUnpackWholeIntoTheirFolder() = runBlocking {
        val archives = DesktopArchives(which = { null })
        assertEquals(setOf("zip", "7z"), archives.kinds(), "rar only with a program for it")
        val z = zip("Crash (USA).zip", mapOf("Crash (USA).cue" to "FILE", "Crash (USA).bin" to "DATA"))
        val out = File(home, "Crash (USA)")
        val written = archives.unpack(z.path, out.path).getOrThrow()
        assertEquals(setOf("Crash (USA).cue", "Crash (USA).bin"), written.map { File(it).name }.toSet())
        assertEquals("DATA", File(out, "Crash (USA).bin").readText())
        assertTrue(home.listFiles()!!.none { it.name.contains("fuse-unpacking") }, "nothing half-made is left beside it")
        val s = sevenZip("patches.7z", mapOf("patches/415607D2 - Quake 4.patch.toml" to "title_name = \"Quake 4\""))
        assertEquals(listOf("patches/415607D2 - Quake 4.patch.toml"), archives.list(s.path)!!.map { it.first })
        archives.unpack(s.path, File(home, "unpacked").path).getOrThrow()
        assertTrue(File(home, "unpacked/patches/415607D2 - Quake 4.patch.toml").isFile)
        // A folder already there is never written over.
        assertTrue(archives.unpack(z.path, out.path).isFailure)
    }

    @Test
    fun anArchiveNamingAPlaceOutsideItsFolderLeavesNothing() = runBlocking {
        val archives = DesktopArchives(which = { null })
        val evil = zip("evil.zip", mapOf("ok.txt" to "fine", "../escaped.txt" to "bad"))
        val result = archives.unpack(evil.path, File(home, "evil").path)
        assertTrue(result.isFailure)
        assertFalse(File(home, "escaped.txt").exists())
        assertFalse(File(home, "evil").exists())
        assertTrue(home.listFiles()!!.none { it.name.contains("fuse-unpacking") })
        // A rar with nothing to unpack it says so.
        val rar = File(home, "game.rar").apply { writeText("Rar!") }
        assertTrue(archives.unpack(rar.path, File(home, "game").path).exceptionOrNull()!!.message!!.contains("unrar"))
    }

    private suspend fun xenia(): Pair<DesktopEmulatorFiles, String> {
        val root = File(home, ".local/share/Xenia").apply { mkdirs() }
        File(root, "xenia-canary.config.toml").writeText("[General]\n")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = File(home, "fuse/emulator-backups"))
        val x = InstalledEmulator(EmulatorId("linux.xenia"), "Xenia", Host.LINUX, "/usr/bin/xenia_canary", platforms = setOf(PlatformId("xbox360")), detectedVia = "PATH")
        return files to assertNotNull(files.xeniaRoot(x))
    }

    @Test
    fun aFolderReplacedWholeComesBackAsItWasOnUndo() = runBlocking {
        val (files, root) = xenia()
        val patches = File(root, "patches").apply { mkdirs() }
        File(patches, "old.patch.toml").writeText("old")
        val source = zip("new.zip", mapOf("patches/A.patch.toml" to "a", "patches/B.patch.toml" to "b"))
        val planned = listOf(PlannedFile("patches/A.patch.toml", "${patches.path}/A.patch.toml"), PlannedFile("patches/B.patch.toml", "${patches.path}/B.patch.toml"))
        assertTrue(files.replaceFolder(patches.path, source.path, planned, mapOf("${patches.path}/C.patch.toml" to "c"), "extra-test-1"))
        assertEquals(setOf("A.patch.toml", "B.patch.toml", "C.patch.toml"), patches.list()!!.toSet(), "exactly what was put there; the old file is aside")
        assertEquals(setOf("A.patch.toml", "B.patch.toml", "C.patch.toml"), files.names(patches.path))
        assertTrue(files.undo("extra-test-1"))
        assertEquals(setOf("old.patch.toml"), patches.list()!!.toSet())
        assertEquals("old", File(patches, "old.patch.toml").readText())
    }

    @Test
    fun nothingLandsOutsideTheFolderBeingReplacedOrOutsideTheEmulatorsOwn() = runBlocking {
        val (files, root) = xenia()
        val patches = File(root, "patches").apply { mkdirs() }
        File(patches, "keep.patch.toml").writeText("keep")
        // A destination outside the folder: refused before anything moves.
        assertFalse(files.replaceFolder(patches.path, null, emptyList(), mapOf("$root/xenia-canary.config.toml" to "x"), "extra-test-2"))
        assertEquals("keep", File(patches, "keep.patch.toml").readText())
        // A folder Fuse hasn't found as an emulator's: refused.
        val elsewhere = File(home, "Documents/stuff").apply { mkdirs() }
        assertFalse(files.replaceFolder(elsewhere.path, null, emptyList(), mapOf("${elsewhere.path}/x" to "x"), "extra-test-3"))
        // A copy that fails half way: the old folder is back in place.
        val source = zip("half.zip", mapOf("A.patch.toml" to "a"))
        val planned = listOf(PlannedFile("A.patch.toml", "${patches.path}/A.patch.toml"), PlannedFile("missing.toml", "${patches.path}/missing.toml"))
        assertFalse(files.replaceFolder(patches.path, source.path, planned, emptyMap(), "extra-test-4"))
        assertEquals(setOf("keep.patch.toml"), patches.list()!!.toSet())
    }

    @Test
    fun firmwareGoesToDuckStationsOwnBiosFolder() = runBlocking {
        val data = File(home, ".local/share/duckstation").apply { mkdirs() }
        File(data, "settings.ini").writeText("[BIOS]\nSearchDirectory = firmware\n")
        val files = DesktopEmulatorFiles(DesktopOs.LINUX, env = emptyMap(), home = home.absolutePath, backups = File(home, "fuse/emulator-backups"))
        val ds = InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH")
        val folder = assertNotNull(files.firmwareFolder(ds, "psx"))
        assertTrue(folder.endsWith("/firmware"), folder)
        val bios = File(home, "BIOS/scph5501.bin").apply { parentFile.mkdirs(); writeText("bios") }
        assertTrue(files.install(bios.path, listOf(PlannedFile("", "$folder/scph5501.bin")), emptyMap(), "extra-bios-1"))
        assertEquals("bios", File(folder, "scph5501.bin").readText())
        assertEquals(null, files.firmwareFolder(ds, "ps2"), "DuckStation reads only PlayStation firmware")
    }
}
