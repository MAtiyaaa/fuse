package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.platform.DesktopSteam
import io.github.matiyaaa.fuse.desktop.services.DesktopReleaseInstaller
import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.library.steam.SteamShortcut
import io.github.matiyaaa.fuse.library.steam.SteamShortcuts
import io.ktor.client.HttpClient
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Linux update replaces the AppImage Fuse runs from at the same path (shortcuts and Steam keep
 * working, one step back is kept, old copies go), and Add Fuse to Steam gives Steam Fuse's art.
 */
class UpdateAndSteamTest {
    private val root: File = Files.createTempDirectory("fuse-update").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun anUpdateReplacesTheAppImageFuseRunsFromAndClearsOldCopies() {
        val apps = File(root, "Applications").apply { mkdirs() }
        val running = File(apps, "Fuse-0.3.6.3-x86_64.AppImage").apply { writeText("old"); setExecutable(true) }
        val older = File(apps, "Fuse-0.3.6.2-x86_64.AppImage").apply { writeText("older") }
        val numbered = File(apps, "Fuse-0.3.6.3-x86_64-2.AppImage").apply { writeText("again") }
        val cartridge = File(apps, "Cartridge-0.9.10-x86_64.AppImage").apply { writeText("cartridge") }
        val mine = File(apps, "notes.txt").apply { writeText("mine") }
        val dirs = FuseDirs(root.path, File(root, "cache").path, File(root, "data").path, File(root, "config").path, File(root, "xdg-config").path, File(root, "xdg-data").path)
        val part = File(root, "download.part").apply { writeText("new") }

        val placed = DesktopReleaseInstaller(dirs, HttpClient(), running = { running.path }).replaceFuse(part)

        assertEquals(running.canonicalPath, placed.canonicalPath, "the same path, so shortcuts and Steam keep working")
        assertEquals("new", running.readText())
        assertTrue(running.canExecute())
        assertEquals("old", File(apps, ".${running.name}.previous").readText(), "one step back is kept")
        assertFalse(older.exists())
        assertFalse(numbered.exists())
        assertTrue(cartridge.exists() && mine.exists(), "nothing that isn't an old Fuse copy is touched")
        assertFalse(File(apps, ".${running.name}.part").exists())
    }

    @Test
    fun addingFuseToSteamGivesItsArtAndKeepsThePersonsOwn() {
        val grid = File(root, "userdata/123/config/grid")
        val shortcut = SteamShortcut(name = "Fuse", exe = "\"/home/deck/Applications/Fuse.AppImage\"", startDir = "\"/home/deck/Applications\"")
        val id = SteamShortcuts.appId(shortcut.exe, shortcut.name).toLong() and 0xFFFFFFFFL
        // Someone already chose their own hero for it.
        grid.mkdirs()
        File(grid, "${id}_hero.jpg").writeText("theirs")

        val icon = DesktopSteam(KnownFolders(root.path)).placeArt(grid, shortcut)

        for ((name, w, h) in listOf(Triple("${id}p", 600, 900), Triple("$id", 920, 430), Triple("${id}_logo", 1200, 344), Triple("${id}_icon", 256, 256))) {
            val image = ImageIO.read(File(grid, "$name.png"))
            assertEquals(w to h, image.width to image.height, name)
        }
        assertFalse(File(grid, "${id}_hero.png").exists(), "their own hero stays")
        assertEquals("theirs", File(grid, "${id}_hero.jpg").readText())
        assertEquals(File(grid, "${id}_icon.png").absolutePath, icon)
    }
}
