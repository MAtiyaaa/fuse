package io.github.matiyaaa.fuse.services

import android.content.Context
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.BuildConfig
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.storage.AndroidFileSystem
import io.github.matiyaaa.fuse.storage.SourceRoot
import io.github.matiyaaa.fuse.storage.StoragePaths
import io.github.matiyaaa.fuse.storage.StorageVolumes
import io.github.matiyaaa.fuse.ui.shell.store.AppsProvider
import io.github.matiyaaa.fuse.ui.shell.store.PackageBridge
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.github.matiyaaa.fuse.ui.shell.store.VolumeMonitor
import io.ktor.client.HttpClient
import java.io.File
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android implementation of everything the shared store needs from the system. */
class AndroidFuseServices(
    context: Context,
    override val data: FuseData,
    override val http: HttpClient,
    scope: CoroutineScope,
    activities: ActivityHolder,
    dualScreen: DualScreenHandoff? = null,
) : FuseServices {
    private val appContext = context.applicationContext
    val storageVolumes = StorageVolumes(appContext)

    override val host: Host = Host.ANDROID
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val fs: FuseFileSystem = AndroidFileSystem(storageVolumes::mounted, appContext.packageName, storageVolumes::hasFullAccess)
    override val secrets: SecretStore = KeystoreSecretStore(appContext)
    override val cacheDir: String = appContext.cacheDir.absolutePath

    override val emulators: EmulatorDetector = AndroidEmulatorDetector(appContext, storageVolumes)
    override val launcher: GameLauncher = AndroidGameLauncher(
        appContext,
        activities,
        storageVolumes,
        sources = { data.sources.all().filter { it.enabled }.map { SourceRoot(it.path, it.kind) } },
        platformAt = { path -> data.games.idByPath(path)?.let { data.games.summary(it) }?.platformId?.value },
        dualScreen = dualScreen,
    )
    override val cartridge: CartridgeBridge = AndroidCartridgeBridge(appContext, activities)
    private val releaseInstaller = AndroidReleaseInstaller(appContext, http, activities)
    override val installer: ReleaseInstaller = releaseInstaller
    override val packages: PackageBridge = AndroidPackageBridge(appContext, activities)
    override val apps: AppsProvider = AndroidAppsProvider(appContext, scope, activities, dualScreen, releaseInstaller::installLocal)
    override val locations: DeviceLocations = AndroidDeviceLocations(storageVolumes)
    override val volumes: VolumeMonitor = io.github.matiyaaa.fuse.storage.AndroidVolumes(appContext)

    override fun writeCacheFile(relativePath: String, content: String): String? =
        writeCacheBytes(relativePath, content.toByteArray(Charsets.UTF_8))

    override suspend fun cacheFile(relativePath: String, content: suspend () -> ByteArray): String? = withContext(Dispatchers.IO) {
        cacheTarget(relativePath)?.takeIf { it.isFile && it.length() > 0 }?.let { return@withContext it.absolutePath }
        val bytes = try {
            content()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        writeCacheBytes(relativePath, bytes)
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
        if (!StoragePaths.isSafeRelative(relativePath)) return@withContext null
        try {
            val root = appContext.filesDir.canonicalFile
            val file = File(root, relativePath).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) } ?: return@withContext null
            val parent = file.parentFile ?: return@withContext null
            if (!parent.isDirectory && !parent.mkdirs()) return@withContext null
            val temp = File(parent, ".${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.delete()
                return@withContext null
            }
            file.absolutePath
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** The file for [relativePath] inside the cache, or null when the path would leave it. */
    private fun cacheTarget(relativePath: String): File? {
        if (!StoragePaths.isSafeRelative(relativePath)) return null
        return try {
            val root = File(cacheDir).canonicalFile
            File(root, relativePath).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
        } catch (e: IOException) {
            null
        }
    }

    private fun writeCacheBytes(relativePath: String, content: ByteArray): String? {
        val file = cacheTarget(relativePath) ?: return null
        return try {
            val parent = file.parentFile ?: return null
            if (!parent.isDirectory && !parent.mkdirs()) return null
            val temp = File(parent, ".${file.name}.tmp")
            temp.writeBytes(content)
            if (!temp.renameTo(file)) {
                temp.delete()
                return null
            }
            file.absolutePath
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override fun utcOffsetMillis(): Long = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
}
