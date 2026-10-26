package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.HeadlessHost
import io.github.matiyaaa.fuse.sync.HostDataCheck
import kotlinx.coroutines.launch
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
            Log.info("Fuse One host: couldn't read the settings: ${it.message ?: it::class.simpleName}")
            Thread.sleep(START_RETRY_MS)
            return 2
        }
        if (sync.role != "HOST") {
            Log.info("Fuse One host: this computer isn't set up as the host")
            return 3
        }
        val name = sync.hostName.ifBlank { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("Fuse") }
        val folder = sync.hostDataDir.ifBlank { null }?.let(::File) ?: File(dirs.data, "sync/host")
        val backups = sync.householdBackupDir.ifBlank { null }?.let(::File) ?: File(dirs.data, "sync/household-backups")
        // A data folder the host can't use (another program changed its owner) is said plainly and
        // waited on, instead of a failed start every few seconds; the host starts once it is given back.
        HeadlessHost.waitForData(folder, sync.hostPort, log = { Log.info("Fuse One host: $it") })
        val host = try {
            HeadlessHost.serve(folder, sync.hostPort, name, BuildInfo.VERSION, backupsDir = backups)
        } catch (e: Exception) {
            // Anything else that stops it: said, and tried again a minute on rather than every few seconds.
            Log.info("Fuse One host couldn't start: ${e.message ?: e::class.simpleName}")
            Thread.sleep(START_RETRY_MS)
            return FAILED
        }
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
        // A device asked the host to update (About, on any device): once the new Fuse is in place,
        // the host stops at once and the system starts it again on it.
        val updated = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        host.onUpdateAsked { remoteUpdate(dirs, inPlace, work) { updated.trySend(Unit) } }
        runBlocking {
            var heldSaidAt = Long.MIN_VALUE / 2
            while (true) {
                val asked = kotlinx.coroutines.withTimeoutOrNull(HostUpkeep.EVERY_MS) { updated.receive() } != null
                if (!asked && !runCatching { upkeep.check() }.getOrDefault(false)) continue
                // The new Fuse opens the same folder from scratch: it starts only once that works. This
                // host read its files when it started and carries on, so a folder another program took
                // over since never turns an update into the host not starting.
                val problem = HostDataCheck.problem(folder)
                if (problem == null) {
                    if (asked) Log.info("Fuse One host: updated as a device asked; starting again on the new Fuse")
                    break
                }
                if (System.currentTimeMillis() - heldSaidAt >= HELD_REMIND_MS) {
                    Log.info("Fuse One host: Fuse was updated, but the host keeps running Fuse ${BuildInfo.VERSION} until it can use its data folder again. $problem")
                    heldSaidAt = System.currentTimeMillis()
                }
            }
        }
        // Stopped on purpose: the service starts the host again, on the Fuse that is there now.
        return UPDATED
    }

    /** Exit code when the host stops to start again on a newer Fuse. */
    const val UPDATED = 75

    /** Exit code when the host couldn't start for a reason it can't name; the service tries again. */
    private const val FAILED = 1

    /** How long a host that couldn't start waits before the service tries again (it restarts 5 s after an exit). */
    private const val START_RETRY_MS = 60_000L

    /** How often a host held on an older Fuse says again why. */
    private const val HELD_REMIND_MS = 60 * 60_000L

    /**
     * A device asked the host to update: answers at once whether it will, and when it will, fetches
     * the newest Fuse in [work] and calls [done] once it is in place. Only a host running from an
     * AppImage replaces itself; an installed package is updated by the system's own installer.
     */
    private suspend fun remoteUpdate(dirs: FuseDirs, inPlace: Boolean, work: kotlinx.coroutines.CoroutineScope, done: () -> Unit): io.github.matiyaaa.fuse.sync.HostUpdate {
        if (!inPlace) {
            return io.github.matiyaaa.fuse.sync.HostUpdate(
                started = false,
                message = "This host runs from an installed package, which its system updates. Update Fuse on the host's computer, and the host starts again on it by itself.",
                version = BuildInfo.VERSION,
            )
        }
        if (updating) return io.github.matiyaaa.fuse.sync.HostUpdate(true, "The host is already updating. It starts again by itself when it is done.")
        val http = io.github.matiyaaa.fuse.integrations.FuseHttp.client(
            io.ktor.client.engine.cio.CIO.create(),
            io.github.matiyaaa.fuse.integrations.FuseHttpConfig(appVersion = BuildInfo.VERSION),
        )
        val release = try {
            GitHubReleases(http).latest(GitHubReleases.FUSE_REPO).getOrNull()
        } catch (e: Exception) {
            null
        }
        if (release == null) {
            http.close()
            return io.github.matiyaaa.fuse.sync.HostUpdate(false, "The host couldn't reach GitHub to find the newest Fuse. Try again in a moment.", BuildInfo.VERSION)
        }
        if (!io.github.matiyaaa.fuse.integrations.github.SemVer.isNewer(release.tag, BuildInfo.VERSION)) {
            http.close()
            return io.github.matiyaaa.fuse.sync.HostUpdate(false, "The host already runs Fuse ${BuildInfo.VERSION}, the newest.", BuildInfo.VERSION)
        }
        val asset = GitHubReleases.pickAsset(release, io.github.matiyaaa.fuse.integrations.github.ReleasePlatform.LINUX_X86_64)
        if (asset == null) {
            http.close()
            return io.github.matiyaaa.fuse.sync.HostUpdate(false, "Fuse ${release.tag} has no AppImage yet. Try again once its release is complete.", BuildInfo.VERSION)
        }
        updating = true
        val target = release.tag.removePrefix("v")
        Log.info("Fuse One host: a device asked for an update; installing Fuse $target")
        work.launch {
            try {
                io.github.matiyaaa.fuse.desktop.services.DesktopReleaseInstaller(dirs, http).install(asset)
                    .onSuccess { done() }
                    .onFailure { Log.info("Fuse One host: the update didn't install: ${it.message ?: it::class.simpleName}") }
            } finally {
                updating = false
                http.close()
            }
        }
        return io.github.matiyaaa.fuse.sync.HostUpdate(true, "The host is updating to Fuse $target. It starts again by itself in a minute or two.", target)
    }

    @Volatile private var updating = false

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
