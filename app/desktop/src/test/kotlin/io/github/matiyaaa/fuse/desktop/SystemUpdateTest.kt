package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.SystemReleaseInstaller
import io.github.matiyaaa.fuse.desktop.services.SystemReleaseInstaller.Layout
import io.github.matiyaaa.fuse.desktop.services.UpdateTrial
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fuse updating itself on Windows and macOS: how it tells where it is installed, and what it does there. */
class SystemUpdateTest {
    private val root: File = Files.createTempDirectory("fuse-system-update").toFile()
    private val dirs = FuseDirs(root.path, File(root, "cache").path, File(root, "data").path, File(root, "config").path, File(root, "xdg-config").path, File(root, "xdg-data").path)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(path: String, text: String = "x") = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun windowsTellsTheInstalledFuseFromThePortableOne() {
        val local = File(root, "AppData/Local")
        val installed = file("AppData/Local/Programs/Fuse/Fuse.exe")
        val (layout, why) = SystemReleaseInstaller.detectLayout(DesktopOs.WINDOWS, installed.path, mapOf("LOCALAPPDATA" to local.path))
        assertIs<Layout.WindowsInstalled>(layout)
        assertNull(why)

        val portable = file("USB/Fuse/Fuse.exe")
        File(portable.parentFile, "FuseData").mkdirs()
        assertIs<Layout.WindowsPortable>(SystemReleaseInstaller.detectLayout(DesktopOs.WINDOWS, portable.path, mapOf("LOCALAPPDATA" to local.path)).first)

        // Anywhere else, or without its launcher, it says why and the release page opens instead.
        val elsewhere = SystemReleaseInstaller.detectLayout(DesktopOs.WINDOWS, file("Elsewhere/Fuse.exe").path, mapOf("LOCALAPPDATA" to local.path))
        assertNull(elsewhere.first)
        assertNotNull(elsewhere.second)
        assertNotNull(SystemReleaseInstaller.detectLayout(DesktopOs.WINDOWS, null, emptyMap()).second)
    }

    @Test
    fun aMacUpdatesOnlyFromAnAppItCanWriteBeside() {
        val launcher = file("Applications/Fuse.app/Contents/MacOS/Fuse")
        val (layout, _) = SystemReleaseInstaller.detectLayout(DesktopOs.MACOS, launcher.path, emptyMap())
        assertEquals(File(root, "Applications/Fuse.app").absoluteFile, (layout as Layout.MacBundle).bundle)

        val translocated = file("private/var/folders/x/AppTranslocation/ABC/d/Fuse.app/Contents/MacOS/Fuse")
        val t = SystemReleaseInstaller.detectLayout(DesktopOs.MACOS, translocated.path, emptyMap())
        assertNull(t.first)
        assertTrue("Applications" in t.second!!)
    }

    @Test
    fun thePortableFuseTakesItsZipAndTheInstalledOneItsInstaller() {
        val local = File(root, "AppData/Local")
        val exe = file("USB/Fuse/Fuse.exe")
        File(exe.parentFile, "FuseData").mkdirs()
        val portable = SystemReleaseInstaller(dirs, HttpClient(), DesktopOs.WINDOWS, appPath = { exe.path }, env = mapOf("LOCALAPPDATA" to local.path))
        assertEquals(ReleasePlatform.WINDOWS_X64_PORTABLE, portable.platform)
        assertTrue(portable.inPlace)
        val installedExe = file("AppData/Local/Programs/Fuse/Fuse.exe")
        val installed = SystemReleaseInstaller(dirs, HttpClient(), DesktopOs.WINDOWS, appPath = { installedExe.path }, env = mapOf("LOCALAPPDATA" to local.path))
        assertEquals(ReleasePlatform.WINDOWS_X64, installed.platform)
        val dev = SystemReleaseInstaller(dirs, HttpClient(), DesktopOs.WINDOWS, appPath = { null })
        assertFalse(dev.inPlace)
        assertNotNull(dev.manualReason)
    }

    @Test
    fun theWindowsHelperWaitsInstallsAndStartsFuseAgain() {
        val exe = File("C:/Users/O'Neil/AppData/Local/Programs/Fuse/Fuse.exe")
        val msi = File("C:/Users/O'Neil/AppData/Local/Fuse/cache/updates/Fuse-0.4.2.11-windows-x64.msi")
        val script = SystemReleaseInstaller.windowsHelper(Layout.WindowsInstalled(exe, exe.parentFile), msi, File("C:/note"), File("C:/log"), pid = 4242)
        assertTrue("Wait-Process -Id 4242" in script)
        assertTrue("'/passive'" in script && "'/norestart'" in script)
        // Quotes in paths are doubled, so a name can't break out of its string.
        assertTrue("O''Neil" in script)
        assertTrue(script.trimEnd().endsWith("Start-Process -FilePath ${io.github.matiyaaa.fuse.desktop.system.PowerShell.literal(exe.path)}"))
        assertFalse("\u2014" in script)

        val portable = SystemReleaseInstaller.windowsHelper(Layout.WindowsPortable(exe, exe.parentFile), msi, File("C:/note"), File("C:/log"), pid = 1)
        // The person's FuseData is never touched.
        assertTrue("-eq 'FuseData'" in portable)
        assertTrue("Expand-Archive" in portable)
    }

    @Test
    fun aMacSwapKeepsTheVersionBeforeAndPutsTheNewOneOnTrial() = runBlocking {
        val bundle = File(root, "Applications/Fuse.app").apply { File(this, "Contents/MacOS").mkdirs(); File(this, "Contents/MacOS/Fuse").writeText("0.4.2.10") }
        val staging = File(root, "Applications/.Fuse.app.part").apply { File(this, "Contents/MacOS").mkdirs(); File(this, "Contents/MacOS/Fuse").writeText("0.4.2.11") }
        val launcher = File(bundle, "Contents/MacOS/Fuse")
        val installer = SystemReleaseInstaller(dirs, HttpClient(), DesktopOs.MACOS, appPath = { launcher.path }, version = "0.4.2.10", schema = 6, now = { 5L })
        installer.swapIn(bundle, staging, "0.4.2.11")
        assertEquals("0.4.2.11", launcher.readText())
        assertEquals("0.4.2.10", File(UpdateTrial.previousOf(bundle), "Contents/MacOS/Fuse").readText())
        assertFalse(staging.exists())
        assertEquals("0.4.2.11", UpdateTrial(File(dirs.data, UpdateTrial.FILE)).read()?.version)
        assertEquals("0.4.2.10", installer.previous()?.version)
        // Going back swaps them again.
        assertTrue(installer.goBack().getOrThrow())
        assertEquals("0.4.2.10", launcher.readText())

        // A version that never comes up is put back by the trial, folder and all.
        installer.swapIn(bundle, File(root, "Applications/.Fuse.app.part").apply { File(this, "Contents/MacOS").mkdirs(); File(this, "Contents/MacOS/Fuse").writeText("0.4.2.12") }, "0.4.2.12")
        val trial = UpdateTrial(File(dirs.data, UpdateTrial.FILE))
        repeat(UpdateTrial.MAX_STARTS) { assertIs<UpdateTrial.Start.OnTrial>(trial.onStart(bundle.path, "0.4.2.12")) }
        assertIs<UpdateTrial.Start.WentBack>(trial.onStart(bundle.path, "0.4.2.12"))
        assertEquals("0.4.2.10", launcher.readText())
    }
}
