package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.sync.HeadlessHost
import io.github.matiyaaa.fuse.sync.HostAdmin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/** How a computer keeps a Fuse Sync host running without Fuse open, and the host it runs. */
class SyncHostServiceTest {
    @Test
    fun theSystemdUnitRunsFuseAsTheHostAndRestartsIt() {
        val unit = SyncHostService.systemdUnit("/home/mo/Apps/Fuse 0.3.0.AppImage")
        // A path with a space is quoted, as a Desktop Entry's Exec is.
        assertTrue("ExecStart=\"/home/mo/Apps/Fuse 0.3.0.AppImage\" --sync-host" in unit, unit)
        assertTrue("Restart=always" in unit)
        assertTrue("WantedBy=default.target" in unit)
        assertTrue("X-Fuse-Sync-Host=true" in unit, "marked as Fuse's own, so only Fuse's is ever removed")
    }

    @Test
    fun theLaunchAgentKeepsTheHostAlive() {
        val plist = SyncHostService.launchAgent("/Applications/Fuse & Co.app/Contents/MacOS/Fuse", "/tmp/host.log")
        assertTrue("<string>/Applications/Fuse &amp; Co.app/Contents/MacOS/Fuse</string>" in plist, plist)
        assertTrue("<string>--sync-host</string>" in plist)
        assertTrue("<key>KeepAlive</key>" in plist)
        assertTrue("<string>${SyncHostService.LABEL}</string>" in plist)
    }

    @Test
    fun withoutAStableProgramThereIsNoServiceToOffer() {
        val home = Files.createTempDirectory("sync-host-home").toFile()
        try {
            val dirs = FuseDirs.linux(mapOf("HOME" to home.path))
            val service = SyncHostService(dirs, { 47311 }, DesktopOs.LINUX)
            // A development run (no AppImage, no installed launcher) can't start itself later.
            if (System.getenv("APPIMAGE") == null && System.getProperty("jpackage.app-path") == null) {
                assertFalse(service.supported)
                assertFalse(service.state().installed)
                assertTrue(service.install().isFailure)
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun theHeadlessHostServesAndStepsAsideForOneAlreadyRunning() {
        val dir = Files.createTempDirectory("headless-host").toFile()
        val port = ServerSocket(0).use { it.localPort }
        try {
            val host = assertNotNull(HeadlessHost.serve(File(dir, "host"), port, "Gaming PC", "test"))
            try {
                // Fuse finds it and manages it through its admin token, never opening its files.
                assertTrue(File(dir, "host/admin.token").isFile)
                assertNotNull(HostAdmin.of(File(dir, "host"), port))
                // A second one (the service starting while Fuse still hosts) waits its turn.
                assertNull(HeadlessHost.serve(File(dir, "host"), port, "Gaming PC", "test"))
            } finally {
                host.close()
            }
            // Closed, the port is free again for Fuse to host in-process.
            val until = System.currentTimeMillis() + 5_000
            while (!HostAdmin.portFree(port) && System.currentTimeMillis() < until) Thread.sleep(100)
            assertTrue(HostAdmin.portFree(port))
        } finally {
            dir.deleteRecursively()
        }
    }
}
