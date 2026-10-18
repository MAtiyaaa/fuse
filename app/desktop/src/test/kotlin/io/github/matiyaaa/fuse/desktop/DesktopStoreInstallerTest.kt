package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopStoreInstaller
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The version an update replaces, kept out of the way in Fuse's own folder and put back on request. */
class DesktopStoreInstallerTest {
    private val home = Files.createTempDirectory("fuse-store-home").toFile()
    private val kept = File(home, ".local/share/fuse/previous-versions")
    private val installer = DesktopStoreInstaller(DesktopOs.LINUX, home, File(home, ".cache/fuse/store-downloads"), kept)
    private val apps = File(home, "Applications")

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun download(text: String) = File(home, "dl-${System.nanoTime()}").apply { writeText(text) }.absolutePath

    @Test
    fun anAppImageUpdateKeepsTheOneBeforeAndGoesBackToIt(): Unit = runBlocking {
        val first = installer.install("Emu", download("one"), "Emu-1.0.AppImage", DesktopAssetKind.APPIMAGE, null)
        assertEquals(File(apps, "Emu-1.0.AppImage").absoluteFile, File(first).absoluteFile)

        val keptAt = installer.keepPrevious("Emu", first)!!
        assertFalse(File(first).exists(), "out of the programs folder, where it would be found as a second copy")
        assertTrue(keptAt.startsWith(kept.absolutePath))
        assertEquals("one", File(keptAt).readText())

        val second = installer.install("Emu", download("two"), "Emu-1.1.AppImage", DesktopAssetKind.APPIMAGE, null)
        val back = installer.restorePrevious(keptAt, second)
        assertEquals(File(first).absoluteFile, File(back).absoluteFile)
        assertEquals("one", File(back).readText())
        assertFalse(File(second).exists())
        assertTrue(File(back).canExecute())
    }

    @Test
    fun aProgramInItsOwnFolderIsKeptWholeAndOnlyWhatFusePutInPlaceIs(): Unit = runBlocking {
        val folder = File(apps, "Emu").apply { File(this, "bin").mkdirs() }
        File(folder, "bin/emu").writeText("one")
        File(folder, "data.txt").writeText("data")
        val keptAt = installer.keepPrevious("Emu", File(folder, "bin/emu").absolutePath)!!
        assertTrue(keptAt.replace('\\', '/').endsWith("Emu/bin/emu"), keptAt)
        assertEquals("data", File(File(keptAt).parentFile.parentFile, "data.txt").readText())
        val back = installer.restorePrevious(keptAt, null)
        assertEquals(File(folder, "bin/emu").absoluteFile, File(back).absoluteFile)
        assertEquals("one", File(back).readText())

        // Something outside the programs folder is never moved.
        val elsewhere = File(home, "Downloads/emu").apply { parentFile.mkdirs(); writeText("x") }
        assertNull(installer.keepPrevious("Emu", elsewhere.absolutePath))
        assertTrue(elsewhere.exists())
    }

    @Test
    fun everyReplacedVersionIsKeptOnItsOwnShelfAndAnyOfThemComesBack(): Unit = runBlocking {
        // Three updates in a row: each version they replaced is kept, none over another.
        var current = installer.install("Emu", download("1.0"), "Emu-1.0.AppImage", DesktopAssetKind.APPIMAGE, null)
        val shelves = ArrayList<String>()
        for (v in listOf("1.1", "1.2", "1.3")) {
            shelves += installer.keepPrevious("Emu", current)!!
            current = installer.install("Emu", download(v), "Emu-$v.AppImage", DesktopAssetKind.APPIMAGE, null)
        }
        assertEquals(listOf("1.0", "1.1", "1.2"), shelves.map { File(it).readText() })
        assertEquals(3, shelves.map { File(it).parentFile }.toSet().size, "a shelf each")
        assertEquals(listOf(current), apps.listFiles()!!.map { it.absolutePath }, "only the version in use is where programs are found")

        // Back to the oldest, skipping the two between: what was in place is kept first (the Store does that).
        val nowKept = installer.keepPrevious("Emu", current)!!
        val back = installer.restorePrevious(shelves[0], null)
        assertEquals("1.0", File(back).readText())
        assertEquals(File(apps, "Emu-1.0.AppImage").absoluteFile, File(back).absoluteFile)
        assertFalse(File(shelves[0]).exists())
        assertEquals("1.3", File(nowKept).readText())
        assertEquals("1.1", File(shelves[1]).readText())

        // Letting versions go: each shelf leaves, and the program's folder with the last of them.
        for (k in listOf(shelves[1], shelves[2], nowKept)) assertTrue(installer.forgetKept(k))
        assertFalse(File(kept, "Emu").exists())
        assertFalse(installer.forgetKept(nowKept), "a version already let go")
        assertEquals("1.0", File(back).readText())
    }

    @Test
    fun aVersionKeptBeforeShelvesHadTimesStillComesBack(): Unit = runBlocking {
        // 0.4.3 kept one version as <kept>/<name>/<program>.
        val old = File(kept, "Emu/Emu-0.9.AppImage").apply { parentFile.mkdirs(); writeText("0.9") }
        val current = installer.install("Emu", download("1.0"), "Emu-1.0.AppImage", DesktopAssetKind.APPIMAGE, null)
        val back = installer.restorePrevious(old.absolutePath, current)
        assertEquals("0.9", File(back).readText())
        assertFalse(File(current).exists())
        assertFalse(File(kept, "Emu").exists())
    }
}
