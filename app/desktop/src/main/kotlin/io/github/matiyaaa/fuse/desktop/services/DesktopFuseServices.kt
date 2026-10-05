package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.desktop.BuildInfo
import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.FuseHttpConfig
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.ui.shell.store.AppsProvider
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.github.matiyaaa.fuse.ui.shell.store.VolumeMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TimeZone

/**
 * [FuseServices] for Linux, Windows and macOS. Built once per process, off the UI thread (it opens
 * the database). Everything Fuse writes goes below [FuseDirs]; user folders are only read. There is
 * no Apps section on any desktop (Fuse is a game launcher there), and Cartridge only on Linux.
 */
class DesktopFuseServices private constructor(
    val dirs: FuseDirs,
    scope: CoroutineScope,
    private val os: DesktopOs = DesktopOs.current,
) : FuseServices, AutoCloseable {
    override val host: Host = os.host
    override val appVersion: String = BuildInfo.VERSION

    private val driver = DesktopDatabase.openDriver(dirs.database)
    override val data: FuseData = FuseData(io.github.matiyaaa.fuse.data.db.FuseDatabase(driver))
    override val fs: FuseFileSystem = NioFileSystem()
    override val secrets: SecretStore = DesktopSecretStore(dirs.data)

    private val engine = CIO.create()
    override val http: HttpClient = FuseHttp.client(engine, FuseHttpConfig(appVersion = appVersion))
    override val cacheDir: String = dirs.cache
    override val deviceName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Fuse"
    override val jellyfinDiscovery = io.github.matiyaaa.fuse.jellyfin.UdpDiscovery()

    private val env = SystemLinuxEnvironment(dirs.home)
    private val folders = KnownFolders(dirs.home, os)
    private val desktopFiles = DesktopFileIndex(dirs)
    /** Hooks the window sets (bring Fuse back to the front when a game ends). */
    val launcherHooks = LauncherHooks()
    private val desktopLauncher = DesktopLauncher(desktopFiles, launcherHooks)

    override val emulators: EmulatorDetector = DesktopEmulatorDetector(env, folders, dirs.config, os)
    override val launcher: GameLauncher = desktopLauncher
    override val cartridge: CartridgeBridge = if (os == DesktopOs.LINUX) DesktopCartridgeBridge(dirs) else NoCartridgeBridge
    override val installer: ReleaseInstaller =
        if (os == DesktopOs.LINUX) DesktopReleaseInstaller(dirs, http) else PageReleaseInstaller(PageReleaseInstaller.platformFor(os))

    /** The Store's programs: fetched from their releases and put where Fuse finds emulators. */
    override val desktopApps: io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller =
        DesktopStoreInstaller(os, File(dirs.home.toString()), File(dirs.cache, "store-downloads"))

    /** No Apps section on a desktop: Fuse manages games there, not programs. */
    override val apps: AppsProvider? = null
    override val locations: DeviceLocations = DesktopLocations(folders)
    override val volumes: VolumeMonitor = io.github.matiyaaa.fuse.desktop.platform.DesktopVolumes(os)
    override val emulatorFiles: io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles =
        DesktopEmulatorFiles(os, backups = java.io.File(dirs.data, "emulator-backups"))

    /** Fuse Sync by Fuse: a computer can be the host, kept running by [io.github.matiyaaa.fuse.desktop.platform.SyncHostService]. */
    override fun syncthingService(scope: CoroutineScope): io.github.matiyaaa.fuse.sync.syncthing.SyncthingService =
        io.github.matiyaaa.fuse.sync.syncthing.JvmSyncthingService(
            platform = object : io.github.matiyaaa.fuse.sync.syncthing.SyncthingPlatform {
                override val host = os.name
                override val install = io.github.matiyaaa.fuse.sync.syncthing.SyncthingInstall(
                    name = "Syncthing",
                    url = "https://syncthing.net/downloads/",
                    note = when (os.name) {
                        "LINUX" -> "Install it from your package manager (or syncthing.net) and start it. Fuse finds it by itself."
                        "MACOS" -> "Install Syncthing for macOS from syncthing.net and start it. Fuse finds it by itself."
                        else -> "Install SyncTrayzor or Syncthing from syncthing.net and start it. Fuse finds it by itself."
                    },
                    canStart = false,
                )
            },
            settings = this.data.settings,
            secrets = secrets,
            scope = scope,
        )

    override fun syncService(data: io.github.matiyaaa.fuse.sync.ProfileDataPort, scope: CoroutineScope): io.github.matiyaaa.fuse.sync.SyncService {
        val port = java.util.concurrent.atomic.AtomicInteger(io.github.matiyaaa.fuse.sync.SyncApi.DEFAULT_PORT)
        val service = io.github.matiyaaa.fuse.sync.JvmSyncService(
            dir = File(dirs.data, "sync"),
            settings = this.data.settings,
            secrets = secrets,
            data = data,
            platform = os.name,
            defaultDeviceName = deviceName,
            fuseVersion = appVersion,
            lifetime = io.github.matiyaaa.fuse.desktop.platform.SyncHostService(dirs, { port.get() }, os),
            scope = scope,
        )
        scope.launch { this@DesktopFuseServices.data.settings.settings.collect { port.set(it.sync.hostPort) } }
        return service
    }

    override fun writeCacheFile(relativePath: String, content: String): String? = writeBelow(cacheDir, relativePath, content)

    override suspend fun cacheFile(relativePath: String, content: suspend () -> ByteArray): String? = withContext(Dispatchers.IO) {
        val rel = relativePath.trim().trimStart('/')
        val existing = File(cacheDir, rel).absoluteFile
        if (FsPath.isWithin(existing.fusePath, File(cacheDir).absoluteFile.fusePath) && existing.isFile && existing.length() > 0) return@withContext existing.fusePath
        val bytes = try {
            content()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        writeBelow(cacheDir, relativePath) { bytes }
    }

    override suspend fun readFile(path: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            File(path).takeIf { it.isFile && it.length() <= maxBytes }?.readBytes()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun keepFile(relativePath: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        writeBelow(dirs.data, relativePath) { bytes }
    }

    override fun utcOffsetMillis(): Long = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

    override fun close() {
        http.close()
        engine.close()
        try {
            driver.close()
        } catch (e: Exception) {
            // Closing on exit; nothing to recover.
        }
    }

    companion object {
        /**
         * Writes [content] to [relativePath] below [rootDir] (atomically) and returns the absolute path.
         * Absolute paths are taken as relative; `.`/`..` segments, NUL, backslashes and drives are
         * refused, so nothing is ever written outside [rootDir].
         */
        internal fun writeBelow(rootDir: String, relativePath: String, content: String): String? =
            writeBelow(rootDir, relativePath) { content.toByteArray(Charsets.UTF_8) }

        internal fun writeBelow(rootDir: String, relativePath: String, content: () -> ByteArray): String? {
            val rel = relativePath.trim().trimStart('/')
            // A drive ("C:/x") or a backslash ("..\\x") could leave the folder on Windows.
            if (rel.isEmpty() || rel.split('/').any { it == ".." || it == "." } || '\u0000' in rel || '\\' in rel || FsPath.drive(rel) != null) return null
            val root = File(rootDir).absoluteFile
            val target = File(root, rel).absoluteFile
            if (!FsPath.isWithin(target.fusePath, root.fusePath) || target.fusePath == root.fusePath) return null
            return try {
                target.parentFile.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.tmp")
                tmp.writeBytes(content())
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                target.fusePath
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

        /** Opens the database and wires every service. Blocking: call from an IO thread. */
        fun create(dirs: FuseDirs, scope: CoroutineScope): DesktopFuseServices {
            dirs.ensure()
            return DesktopFuseServices(dirs, scope)
        }
    }
}
