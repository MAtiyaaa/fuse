package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.HeadlessHost
import kotlinx.coroutines.runBlocking
import io.github.matiyaaa.fuse.integrations.getOrNull
import io.github.matiyaaa.fuse.integrations.github.GitHubReleases
import java.io.File

/**
 * `Fuse --sync-host`: the Fuse Sync host with no window, as the background service runs it. Reads
 * the host's name and port once, then serves until the system stops it. When Fuse itself is still
 * hosting, it exits and the system tries again shortly (Fuse hands over to it first). It keeps itself
 * on the newest Fuse ([HostUpkeep]): it stops to start again once Fuse was updated here, and updates
 * itself when the household's devices run a newer Fuse.
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
            Log.info("Fuse One host: couldn't read the settings")
            return 2
        }
        if (sync.role != "HOST") {
            Log.info("Fuse One host: this computer isn't set up as the host")
            return 3
        }
        val name = sync.hostName.ifBlank { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("Fuse") }
        val folder = sync.hostDataDir.ifBlank { null }?.let(::File) ?: File(dirs.data, "sync/host")
        val backups = sync.householdBackupDir.ifBlank { null }?.let(::File) ?: File(dirs.data, "sync/household-backups")
        val host = HeadlessHost.serve(folder, sync.hostPort, name, BuildInfo.VERSION, backupsDir = backups)
        if (host == null) {
            Log.info("Fuse One host: port ${sync.hostPort} is in use (Fuse is hosting); trying again later")
            return 4
        }
        Log.info("Fuse One host serving on port ${sync.hostPort}")
        Runtime.getRuntime().addShutdownHook(Thread { host.close() })
        val program = io.github.matiyaaa.fuse.desktop.platform.Autostart.command()?.let(::File)
        // Fuse updates itself in place only from an AppImage; elsewhere the system's installer does.
        val inPlace = io.github.matiyaaa.fuse.desktop.system.DesktopOs.current == io.github.matiyaaa.fuse.desktop.system.DesktopOs.LINUX &&
            System.getenv("APPIMAGE") != null
        val upkeep = HostUpkeep(program, host::newerFuse, if (inPlace) ({ selfUpdate(dirs) }) else null, log = Log::info)
        runBlocking {
            while (true) {
                kotlinx.coroutines.delay(HostUpkeep.EVERY_MS)
                if (runCatching { upkeep.check() }.getOrDefault(false)) break
            }
        }
        // Stopped on purpose: the service starts the host again, on the Fuse that is there now.
        return UPDATED
    }

    /** Exit code when the host stops to start again on a newer Fuse. */
    const val UPDATED = 75

    /** Fetches the newest Fuse and puts it in place of the AppImage this host runs from. */
    private suspend fun selfUpdate(dirs: FuseDirs): Result<Unit> {
        val http = io.github.matiyaaa.fuse.integrations.FuseHttp.client(
            io.ktor.client.engine.cio.CIO.create(),
            io.github.matiyaaa.fuse.integrations.FuseHttpConfig(appVersion = BuildInfo.VERSION),
        )
        return try {
            val release = GitHubReleases(http).latest(GitHubReleases.FUSE_REPO).getOrNull()
                ?: return Result.failure(IllegalStateException("GitHub didn't answer"))
            if (!io.github.matiyaaa.fuse.integrations.github.SemVer.isNewer(release.tag, BuildInfo.VERSION)) {
                return Result.failure(IllegalStateException("Fuse ${release.tag} is the newest released"))
            }
            val asset = GitHubReleases.pickAsset(release, io.github.matiyaaa.fuse.integrations.github.ReleasePlatform.LINUX_X86_64)
                ?: return Result.failure(IllegalStateException("Fuse ${release.tag} has no AppImage yet"))
            io.github.matiyaaa.fuse.desktop.services.DesktopReleaseInstaller(dirs, http).install(asset)
        } finally {
            http.close()
        }
    }
}
