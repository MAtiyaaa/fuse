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
import org.junit.Assume.assumeTrue

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
        home = home.invariantSeparatorsPath, env = emptyMap(), which = { null },
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
        assertEquals(zeldaDir.invariantSeparatorsPath, zelda.folder, "portable: beside the program")
        assertEquals(zeldaDir.invariantSeparatorsPath, zelda.cwd, "started in its own folder, where its assets are")
        assertEquals("1.2.2", zelda.version)
        val banjo = assertNotNull(found["banjorecomp"])
        assertEquals("Flatpak", banjo.how)
        assertEquals(listOf("flatpak", "run", "io.github.banjorecomp.banjorecomp"), banjo.argv)
        assertEquals("${home.invariantSeparatorsPath}/.config/BanjoRecompiled", banjo.folder)
        assertEquals("1.0.2", banjo.version)
        val starfox = assertNotNull(found["starfox64recomp"])
        assertEquals("AppImage", starfox.how)
        assertEquals("${home.invariantSeparatorsPath}/.config/Starfox64Recompiled", starfox.folder)
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
        val mods = "${home.invariantSeparatorsPath}/.config/Zelda64Recompiled/mods"
        val single = File(home, "Download/Rando.nrm").apply { parentFile.mkdirs(); writeText("nrm") }
        assertEquals(listOf("Rando.nrm"), f.addMods(single.path, mods).getOrThrow())
        val pack = zip("pack.zip", mapOf("MMRecompRando/mods/extra.nrm" to "a", "textures/HD.rtz" to "b", "README.txt" to "c", "../escape.nrm" to "d", "lib/native.so" to "e"))
        assertEquals(setOf("extra.nrm", "HD.rtz", "escape.nrm"), f.addMods(pack.path, mods).getOrThrow().toSet())
        assertEquals(setOf("Rando.nrm", "extra.nrm", "HD.rtz", "escape.nrm"), f.list(mods)!!.map { it.first }.toSet(), "only mods, each at the top of its mods folder")
        assertFalse(File(home, ".config/Zelda64Recompiled/escape.nrm").exists())
        assertTrue(File(mods).listFiles()!!.none { it.name.endsWith(".part") }, "nothing half-written is left")

        // Only a recomp's own folders, Fuse's ports and its profiles.
        assertTrue(f.addMods(single.path, "${home.invariantSeparatorsPath}/Documents/mods").isFailure)
        assertFalse(f.writeText("${home.invariantSeparatorsPath}/.bashrc", "no"))
        assertFalse(f.remove(single.path), "the person's own file stays")
        assertTrue(single.isFile)
        assertNull(f.readText("${home.invariantSeparatorsPath}/.ssh/id_ed25519"))
        assertTrue(f.writeText("${home.invariantSeparatorsPath}/.config/Zelda64Recompiled/graphics.json", "{}"))
        assertTrue(f.writeText("${f.profiles}/zelda64recomp/Deck.json", "{}"))
        assertTrue(f.copy("${f.profiles}/zelda64recomp/Deck.json", "${home.invariantSeparatorsPath}/.config/Zelda64Recompiled/controls.json"))
        assertTrue(f.remove("$mods/Rando.nrm"))
        assertFalse(File(mods, "Rando.nrm").exists())
    }

    /** A symbolic link at [at] to [to]; Windows makes one only in Developer Mode or as an administrator. */
    private fun link(at: File, to: File) {
        at.parentFile.mkdirs()
        val made = runCatching { Files.createSymbolicLink(at.toPath(), to.toPath()) }.isSuccess
        assumeTrue("This system doesn't let the tests make symbolic links", made)
    }

    @Test
    fun aLinkInARecompsFolderNeverReachesPastIt(): Unit = runBlocking {
        val f = files()
        val zelda = File(home, ".config/Zelda64Recompiled").apply { mkdirs() }
        val outside = File(home, "private").apply { mkdirs() }
        val secret = File(outside, "id_ed25519").apply { writeText("secret") }

        // A file in the recomp's folder that is a link to one outside it.
        link(File(zelda, "graphics.json"), secret)
        assertNull(f.readText(File(zelda, "graphics.json").path), "a linked file outside isn't read")
        assertFalse(f.writeText(File(zelda, "graphics.json").path, "{}"), "nor written")
        assertEquals("secret", secret.readText())
        assertFalse(f.copy(File(zelda, "graphics.json").path, "${f.profiles}/zelda64recomp/Deck.json"), "nor copied out")

        // A folder in it that is a link to one outside: nothing is listed, made or taken away through it.
        link(File(zelda, "mods"), outside)
        assertNull(f.list(File(zelda, "mods").path))
        assertFalse(f.writeText(File(zelda, "mods/new.json").path, "{}"))
        assertFalse(File(outside, "new.json").exists())
        assertFalse(f.remove(File(zelda, "mods/id_ed25519").path))
        assertTrue(secret.isFile)
        val mod = File(home, "Download/Rando.nrm").apply { parentFile.mkdirs(); writeText("nrm") }
        assertTrue(f.addMods(mod.path, File(zelda, "mods").path).isFailure)
        assertFalse(File(outside, "Rando.nrm").exists())

        // A link that leads nowhere is refused, not followed.
        link(File(zelda, "gone"), File(home, "nowhere/at/all"))
        assertFalse(f.writeText(File(zelda, "gone/file.json").path, "{}"))
    }

    @Test
    fun aRecompsFolderThatIsItselfALinkStillCounts(): Unit = runBlocking {
        // ~/.config kept elsewhere and linked in, as dotfiles managers do.
        val real = File(home, "dotfiles/config").apply { mkdirs() }
        link(File(home, ".config"), real)
        val f = files()
        assertTrue(f.writeText("${home.invariantSeparatorsPath}/.config/Zelda64Recompiled/graphics.json", "{}"))
        assertEquals("{}", File(real, "Zelda64Recompiled/graphics.json").readText())
        assertEquals("{}", f.readText("${home.invariantSeparatorsPath}/.config/Zelda64Recompiled/graphics.json"))
        assertFalse(f.writeText("${home.invariantSeparatorsPath}/.config/other/x.json", "{}"), "only the recomp's own folder in it")
    }

    @Test
    fun aFlatpakIsUpdatedAsThePersonInstalledIt(): Unit = runBlocking {
        val calls = ArrayList<List<String>>()
        val f = DesktopRecompFiles(
            DesktopOs.LINUX, File(data, "ports"), File(data, "recomp-profiles"),
            home = home.invariantSeparatorsPath, env = emptyMap(), which = { if (it == "flatpak") "/usr/bin/flatpak" else null },
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
        assertEquals(listOf("${home.invariantSeparatorsPath}/Games/spartanport-x86_64.AppImage", "--play"), spartan.argv)
        assertEquals("${home.invariantSeparatorsPath}/.local/share/spartanport/user/savedata", spartan.saves)
        val p = assertNotNull(found["pt-pc"])
        assertEquals(listOf("${pt.invariantSeparatorsPath}/pt"), p.argv)
        assertEquals("${pt.invariantSeparatorsPath}/data", p.saves)
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
