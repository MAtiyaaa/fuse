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
}
