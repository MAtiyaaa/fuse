package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopReleaseInstaller
import io.github.matiyaaa.fuse.desktop.services.UpdateHandoff
import io.github.matiyaaa.fuse.desktop.services.UpdateTrial
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Going back from a Fuse update: the version it replaced is kept with what it is, offered to go back
 * to (and forward again), and put back by itself when the new one doesn't come up.
 */
class UpdateRecoveryTest {
    private val root: File = Files.createTempDirectory("fuse-recovery").toFile()
    private val apps = File(root, "Applications").apply { mkdirs() }
    private val dirs = FuseDirs(root.path, File(root, "cache").path, File(root, "data").path, File(root, "config").path, File(root, "xdg-config").path, File(root, "xdg-data").path)
    private val appImage = File(apps, "Fuse.AppImage").apply { writeText("0.4.2"); setExecutable(true) }
    private val trialFile = File(dirs.data, UpdateTrial.FILE)

    @AfterTest
    fun cleanUp() {
        UpdateHandoff.installedFuseAppImage = null
        root.deleteRecursively()
    }

    /** The installer as the given version sees it, running from [appImage]. */
    private fun installer(version: String, schema: Long) =
        DesktopReleaseInstaller(dirs, HttpClient(), running = { appImage.path }, version = version, schema = schema, now = { 1_000L })

    private fun update(to: String) {
        val part = File(root, "download.part").apply { writeText(to) }
        installer("0.4.2", schema = 5).replaceFuse(part, newVersion = to)
    }

    @Test
    fun anUpdateKeepsTheVersionItReplacedToGoBackToAndForwardAgain() = runBlocking {
        update("0.4.3")
        assertEquals("0.4.3", appImage.readText())
        // The new version (reading a newer library) is offered the one before, which brings back its library.
        val newer = installer("0.4.3", schema = 6)
        val previous = newer.previous()!!
        assertEquals("0.4.2", previous.version)
        assertTrue(previous.libraryGoesBack)
        assertTrue(newer.goBack().getOrThrow(), "restart into it")
        assertEquals("0.4.2", appImage.readText())
        assertEquals(appImage.path, UpdateHandoff.installedFuseAppImage)
        assertFalse(trialFile.exists(), "going back ends the trial")
        // From the version gone back to, the newer one is offered again, and nothing is lost on the way.
        val older = installer("0.4.2", schema = 5)
        assertEquals("0.4.3", older.previous()!!.version)
        assertFalse(older.previous()!!.libraryGoesBack)
        older.goBack().getOrThrow()
        assertEquals("0.4.3", appImage.readText())
        assertEquals("0.4.2", File(apps, ".Fuse.AppImage.previous").readText())
    }

    @Test
    fun anUpdateThatDoesntComeUpGoesBackByItself() {
        update("0.4.3")
        val trial = UpdateTrial(trialFile)
        // Starts of the version it replaced, or from elsewhere, don't count.
        assertEquals(UpdateTrial.Start.Normal, trial.onStart(appImage.path, "0.4.2"))
        assertEquals(UpdateTrial.Start.Normal, trial.onStart("/elsewhere/Fuse.AppImage", "0.4.3"))
        assertEquals(UpdateTrial.Start.OnTrial(1), trial.onStart(appImage.path, "0.4.3"))
        assertEquals(UpdateTrial.Start.OnTrial(2), trial.onStart(appImage.path, "0.4.3"))
        val back = assertIs<UpdateTrial.Start.WentBack>(trial.onStart(appImage.path, "0.4.3"))
        assertEquals(appImage.path, back.target)
        assertEquals("0.4.2", back.from)
        assertEquals("0.4.2", appImage.readText(), "the version before is back in its place")
        assertEquals("0.4.3", File(apps, ".Fuse.AppImage.failed").readText(), "the one that failed is kept aside")
        assertFalse(trialFile.exists())
        assertNull(installer("0.4.2", schema = 5).previous(), "nothing is offered to go forward to a version that failed")
    }

    @Test
    fun anUpdateThatComesUpEndsItsTrial() {
        update("0.4.3")
        val trial = UpdateTrial(trialFile)
        assertEquals(UpdateTrial.Start.OnTrial(1), trial.onStart(appImage.path, "0.4.3"))
        trial.healthy()
        assertEquals(UpdateTrial.Start.Normal, trial.onStart(appImage.path, "0.4.3"))
        assertEquals("0.4.3", appImage.readText())
    }

    @Test
    fun aFirstInstallHasNothingToGoBackTo() {
        // Fuse ran from elsewhere (a development run): the update goes to ~/Applications with nothing before it.
        File(apps, ".Fuse.AppImage.previous").writeText("stale")
        appImage.delete()
        installer("0.4.2", schema = 5).replaceFuse(File(root, "download.part").apply { writeText("0.4.3") }, newVersion = "0.4.3")
        assertFalse(File(apps, ".Fuse.AppImage.previous").exists())
        assertNull(installer("0.4.3", schema = 6).previous())
        assertFalse(trialFile.exists())
    }
}
