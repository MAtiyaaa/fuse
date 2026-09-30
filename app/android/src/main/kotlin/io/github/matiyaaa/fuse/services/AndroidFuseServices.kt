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
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.IOException
import java.util.TimeZone

/** The Android implementation of everything the shared store needs from the system. */
class AndroidFuseServices(
    context: Context,
    override val data: FuseData,
    override val http: HttpClient,
    scope: CoroutineScope,
    activities: ActivityHolder,
) : FuseServices {
    private val appContext = context.applicationContext
    val volumes = StorageVolumes(appContext)

    override val host: Host = Host.ANDROID
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val fs: FuseFileSystem = AndroidFileSystem(volumes::mounted, appContext.packageName, volumes::hasFullAccess)
    override val secrets: SecretStore = KeystoreSecretStore(appContext)
    override val cacheDir: String = appContext.cacheDir.absolutePath

    override val emulators: EmulatorDetector = AndroidEmulatorDetector(appContext, volumes)
    override val launcher: GameLauncher = AndroidGameLauncher(appContext, activities, volumes) {
        data.sources.all().filter { it.enabled }.map { SourceRoot(it.path, it.kind) }
    }
    override val cartridge: CartridgeBridge = AndroidCartridgeBridge(appContext, activities)
    override val installer: ReleaseInstaller = AndroidReleaseInstaller(appContext, http, activities)
    override val apps: AppsProvider = AndroidAppsProvider(appContext, scope, activities)
    override val locations: DeviceLocations = AndroidDeviceLocations(volumes)

    override fun writeCacheFile(relativePath: String, content: String): String? {
        if (!StoragePaths.isSafeRelative(relativePath)) return null
        return try {
            val root = File(cacheDir).canonicalFile
            val file = File(root, relativePath).canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) return null
            val parent = file.parentFile ?: return null
            if (!parent.isDirectory && !parent.mkdirs()) return null
            val temp = File(parent, ".${file.name}.tmp")
            temp.writeText(content, Charsets.UTF_8)
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
