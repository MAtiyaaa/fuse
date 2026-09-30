package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.desktop.BuildInfo
import io.github.matiyaaa.fuse.desktop.FuseDirs
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TimeZone

/**
 * [FuseServices] for Linux. Built once per process, off the UI thread (it opens the database).
 * Everything Fuse writes goes below [FuseDirs]; user folders are only read.
 */
class DesktopFuseServices private constructor(
    val dirs: FuseDirs,
    scope: CoroutineScope,
) : FuseServices, AutoCloseable {
    override val host: Host = Host.LINUX
    override val appVersion: String = BuildInfo.VERSION

    private val driver = DesktopDatabase.openDriver(dirs.database)
    override val data: FuseData = FuseData(io.github.matiyaaa.fuse.data.db.FuseDatabase(driver))
    override val fs: FuseFileSystem = NioFileSystem()
    override val secrets: SecretStore = DesktopSecretStore(dirs.data)

    private val engine = CIO.create()
    override val http: HttpClient = FuseHttp.client(engine, FuseHttpConfig(appVersion = appVersion))
    override val cacheDir: String = dirs.cache

    private val env = SystemLinuxEnvironment(dirs.home)
    private val folders = KnownFolders(dirs.home)
    private val desktopFiles = DesktopFileIndex(dirs)
    /** Hooks the window sets (bring Fuse back to the front when a game ends). */
    val launcherHooks = LauncherHooks()
    private val desktopLauncher = DesktopLauncher(desktopFiles, launcherHooks)

    override val emulators: EmulatorDetector = DesktopEmulatorDetector(env, folders)
    override val launcher: GameLauncher = desktopLauncher
    override val cartridge: CartridgeBridge = DesktopCartridgeBridge(dirs)
    override val installer: ReleaseInstaller = DesktopReleaseInstaller(dirs, http)
    private val appsProvider = DesktopAppsProvider(desktopFiles, desktopLauncher, scope)
    override val apps: AppsProvider = appsProvider
    override val locations: DeviceLocations = DesktopLocations(folders)

    override fun writeCacheFile(relativePath: String, content: String): String? = writeBelow(cacheDir, relativePath, content)

    override suspend fun cacheFile(relativePath: String, content: suspend () -> ByteArray): String? = withContext(Dispatchers.IO) {
        val rel = relativePath.trim().trimStart('/')
        val existing = File(cacheDir, rel).absoluteFile
        if (FsPath.isWithin(existing.path, File(cacheDir).absolutePath) && existing.isFile && existing.length() > 0) return@withContext existing.path
        val bytes = try {
            content()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        writeBelow(cacheDir, relativePath) { bytes }
    }

    override fun utcOffsetMillis(): Long = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

    override fun close() {
        appsProvider.close()
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
         * Absolute paths are taken as relative; `.`/`..` segments and NUL are refused, so nothing is
         * ever written outside [rootDir].
         */
        internal fun writeBelow(rootDir: String, relativePath: String, content: String): String? =
            writeBelow(rootDir, relativePath) { content.toByteArray(Charsets.UTF_8) }

        internal fun writeBelow(rootDir: String, relativePath: String, content: () -> ByteArray): String? {
            val rel = relativePath.trim().trimStart('/')
            if (rel.isEmpty() || rel.split('/').any { it == ".." || it == "." } || '\u0000' in rel) return null
            val root = File(rootDir).absoluteFile
            val target = File(root, rel).absoluteFile
            if (!FsPath.isWithin(target.path, root.path) || target.path == root.path) return null
            return try {
                target.parentFile.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.tmp")
                tmp.writeBytes(content())
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                target.path
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
