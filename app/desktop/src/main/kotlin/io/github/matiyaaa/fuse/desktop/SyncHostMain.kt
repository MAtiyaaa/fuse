package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.HeadlessHost
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `Fuse --sync-host`: the Fuse Sync host with no window, as the background service runs it. Reads
 * the host's name and port once, then serves until the system stops it. When Fuse itself is still
 * hosting, it exits and the system tries again shortly (Fuse hands over to it first).
 */
object SyncHostMain {
    fun run(dirs: FuseDirs): Int {
        // No window and no Dock icon: this is a background process.
        System.setProperty("java.awt.headless", "true")
        System.setProperty("apple.awt.UIElement", "true")
        val sync = runCatching {
            val driver = DesktopDatabase.openDriver(dirs.database)
            try {
                runBlocking { SettingsStore(FuseDatabase(driver)).current().sync }
            } finally {
                driver.close()
            }
        }.getOrElse {
            Log.info("Fuse Sync host: couldn't read the settings")
            return 2
        }
        if (sync.role != "HOST") {
            Log.info("Fuse Sync host: this computer isn't set up as the host")
            return 3
        }
        val name = sync.hostName.ifBlank { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("Fuse") }
        val folder = sync.hostDataDir.ifBlank { null }?.let(::File) ?: File(dirs.data, "sync/host")
        val host = HeadlessHost.serve(folder, sync.hostPort, name, BuildInfo.VERSION)
        if (host == null) {
            Log.info("Fuse Sync host: port ${sync.hostPort} is in use (Fuse is hosting); trying again later")
            return 4
        }
        Log.info("Fuse Sync host serving on port ${sync.hostPort}")
        Runtime.getRuntime().addShutdownHook(Thread { host.close() })
        Thread.currentThread().join()
        return 0
    }
}
