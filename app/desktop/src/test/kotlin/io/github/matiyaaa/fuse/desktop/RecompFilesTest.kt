package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopRecompFiles
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Recomps the person installed, found where their builds go, and their folders touched only where
 * Fuse may: mods added whole and by their own names, and nothing written outside a recomp's own
 * folder, Fuse's ports or its profiles.
 */
class RecompFilesTest {
    private val home = Files.createTempDirectory("fuse-recomps").toFile()
    private val data = File(home, "fuse-data")

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun files(flatpakVersion: String? = null, calls: MutableList<List<String>> = ArrayList()) = DesktopRecompFiles(
        DesktopOs.LINUX, File(data, "ports"), File(data, "recomp-profiles"),
        home = home.absolutePath, env = emptyMap(), which = { null },
        run = { argv, _ -> calls += argv; Processes.Output(0, flatpakVersion?.let { "Ref: app/x\nVersion: $it\n" }.orEmpty()) },
    )

    @Test
    fun recompsAreFoundWithTheFolderEachKeepsItsFilesIn(): Unit = runBlocking {
        // Zelda unpacked into ~/Applications, set to keep its files beside itself.
        val zeldaDir = File(home, "Applications/Zelda64Recompiled-v1.2.2-Linux-X64").apply { mkdirs() }
        File(zeldaDir, "Zelda64Recompiled").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        File(zeldaDir, "portable.txt").writeText("")
        // Banjo as a Flatpak of the person's own; Star Fox as an AppImage in Games.
        File(home, ".local/share/flatpak/app/io.github.banjorecomp.banjorecomp/current").mkdirs()
        File(home, "Games").mkdirs()
        File(home, "Games/Starfox64Recompiled-x86_64.AppImage").writeText("appimage")
        // Something else of the same kind of name is never one.
        File(home, "Applications/Zelda64Recompiled.txt").writeText("notes")

        val found = files(flatpakVersion = "1.0.2").find().associateBy { it.id }
        val zelda = assertNotNull(found["zelda64recomp"])
        assertEquals("Program", zelda.how)
        assertEquals(zeldaDir.absolutePath, zelda.folder, "portable: beside the program")
        assertEquals(zeldaDir.absolutePath, zelda.cwd, "started in its own folder, where its assets are")
        assertEquals("1.2.2", zelda.version)
        val banjo = assertNotNull(found["banjorecomp"])
        assertEquals("Flatpak", banjo.how)
        assertEquals(listOf("flatpak", "run", "io.github.banjorecomp.banjorecomp"), banjo.argv)
        assertEquals("${home.absolutePath}/.config/BanjoRecompiled", banjo.folder)
        assertEquals("1.0.2", banjo.version)
        val starfox = assertNotNull(found["starfox64recomp"])
        assertEquals("AppImage", starfox.how)
        assertEquals("${home.absolutePath}/.config/Starfox64Recompiled", starfox.folder)
        assertNull(found["goemon64recomp"])
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

    @Test
    fun modsGoInWholeByTheirOwnNamesAndNothingLandsElsewhere(): Unit = runBlocking {
        val f = files()
        val mods = "${home.absolutePath}/.config/Zelda64Recompiled/mods"
        val single = File(home, "Download/Rando.nrm").apply { parentFile.mkdirs(); writeText("nrm") }
        assertEquals(listOf("Rando.nrm"), f.addMods(single.path, mods).getOrThrow())
        val pack = zip("pack.zip", mapOf("MMRecompRando/mods/extra.nrm" to "a", "textures/HD.rtz" to "b", "README.txt" to "c", "../escape.nrm" to "d", "lib/native.so" to "e"))
        assertEquals(setOf("extra.nrm", "HD.rtz", "escape.nrm"), f.addMods(pack.path, mods).getOrThrow().toSet())
        assertEquals(setOf("Rando.nrm", "extra.nrm", "HD.rtz", "escape.nrm"), f.list(mods)!!.map { it.first }.toSet(), "only mods, each at the top of its mods folder")
        assertFalse(File(home, ".config/Zelda64Recompiled/escape.nrm").exists())
        assertTrue(File(mods).listFiles()!!.none { it.name.endsWith(".part") }, "nothing half-written is left")

        // Only a recomp's own folders, Fuse's ports and its profiles.
        assertTrue(f.addMods(single.path, "${home.absolutePath}/Documents/mods").isFailure)
        assertFalse(f.writeText("${home.absolutePath}/.bashrc", "no"))
        assertFalse(f.remove(single.path), "the person's own file stays")
        assertTrue(single.isFile)
        assertNull(f.readText("${home.absolutePath}/.ssh/id_ed25519"))
        assertTrue(f.writeText("${home.absolutePath}/.config/Zelda64Recompiled/graphics.json", "{}"))
        assertTrue(f.writeText("${f.profiles}/zelda64recomp/Deck.json", "{}"))
        assertTrue(f.copy("${f.profiles}/zelda64recomp/Deck.json", "${home.absolutePath}/.config/Zelda64Recompiled/controls.json"))
        assertTrue(f.remove("$mods/Rando.nrm"))
        assertFalse(File(mods, "Rando.nrm").exists())
    }

    @Test
    fun aFlatpakIsUpdatedAsThePersonInstalledIt(): Unit = runBlocking {
        val calls = ArrayList<List<String>>()
        val f = DesktopRecompFiles(
            DesktopOs.LINUX, File(data, "ports"), File(data, "recomp-profiles"),
            home = home.absolutePath, env = emptyMap(), which = { if (it == "flatpak") "/usr/bin/flatpak" else null },
            run = { argv, _ -> calls += argv; Processes.Output(0, "") },
        )
        File(home, ".local/share/flatpak/app/io.github.zelda64recomp.zelda64recomp/current").mkdirs()
        assertNull(f.updateFlatpak("io.github.zelda64recomp.zelda64recomp"))
        assertEquals(listOf("flatpak", "update", "-y", "--noninteractive", "--user", "io.github.zelda64recomp.zelda64recomp"), calls.last())
        assertEquals("Fuse only updates the recomps it knows.", f.updateFlatpak("org.example.Other"))
    }

    @Test
    fun playStationPortsAreFoundByTheFilesTheirReleasesPutThere(): Unit = runBlocking {
        // SpartanPort's AppImage in ~/Games; P.T. installed into ~/Games/PT with its game archives beside it.
        File(home, "Games").mkdirs()
        File(home, "Games/spartanport-x86_64.AppImage").writeText("appimage")
        val pt = File(home, "Games/PT").apply { mkdirs() }
        File(pt, "pt").writeText("elf")
        File(pt, "chunk1.psarc").writeText("archive")
        // A folder with a program called pt but none of P.T.'s archives isn't it.
        File(home, "Applications/other").mkdirs()
        File(home, "Applications/other/pt").writeText("something else")

        val found = files().find().filter { it.id in setOf("spartanport", "pt-pc") }.associateBy { it.id }
        val spartan = assertNotNull(found["spartanport"])
        assertEquals("AppImage", spartan.how)
        assertEquals(listOf("${home.absolutePath}/Games/spartanport-x86_64.AppImage", "--play"), spartan.argv)
        assertEquals("${home.absolutePath}/.local/share/spartanport/user/savedata", spartan.saves)
        val p = assertNotNull(found["pt-pc"])
        assertEquals(listOf("${pt.absolutePath}/pt"), p.argv)
        assertEquals("${pt.absolutePath}/data", p.saves)
        assertEquals(listOf("PT_Save_Data0", "PT_Save_Data1"), p.saveFiles)
        assertEquals(1, files().find().count { it.id == "pt-pc" })
    }

    @Test
    fun aVersionIsReadFromTheNameItCameWith() {
        assertEquals("1.2.2", DesktopRecompFiles.versionIn("Zelda64Recompiled-v1.2.2-Linux-X64"))
        assertEquals("1.0.3", DesktopRecompFiles.versionIn("Starfox64Recompiled-1.0.3.AppImage"))
        assertNull(DesktopRecompFiles.versionIn("Zelda64Recompiled"))
    }
}
