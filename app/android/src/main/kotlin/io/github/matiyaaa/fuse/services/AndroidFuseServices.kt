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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Android implementation of everything the shared store needs from the system. */
class AndroidFuseServices(
    context: Context,
    override val data: FuseData,
    override val http: HttpClient,
    scope: CoroutineScope,
    activities: ActivityHolder,
    dualScreen: DualScreenHandoff? = null,
    /** The other screen's display id, as Fuse's display monitor chooses it. */
    secondScreen: () -> Int? = { null },
) : FuseServices {
    private val appContext = context.applicationContext
    val storageVolumes = StorageVolumes(appContext)

    override val host: Host = Host.ANDROID
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val fs: FuseFileSystem = AndroidFileSystem(storageVolumes::mounted, appContext.packageName, storageVolumes::hasFullAccess)
    override val secrets: SecretStore = KeystoreSecretStore(appContext)
    override val cacheDir: String = appContext.cacheDir.absolutePath
    override val dataDir: String = appContext.filesDir.absolutePath

    override val archives: io.github.matiyaaa.fuse.ui.shell.store.Archives = AndroidArchives()

    /** The shared Movies folder when Fuse may write there, so other apps see the films too; else Fuse's own. */
    override val mediaDir: String
        get() = if (storageVolumes.hasFullAccess()) {
            java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "Fuse").absolutePath
        } else {
            (appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: java.io.File(appContext.filesDir, "Offline")).absolutePath
        }

    override suspend fun sendWake(packet: ByteArray, ports: List<Int>, address: String?): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) { io.github.matiyaaa.fuse.stream.AndroidStreaming.sendWake(packet, ports, address) }

    override suspend fun startStream(hostName: String, uniqueId: String?, address: String, app: String, client: String): String? =
        io.github.matiyaaa.fuse.stream.AndroidStreaming.start(appContext, hostName, uniqueId, app, client)

    override fun streamClient(): String? = io.github.matiyaaa.fuse.stream.AndroidStreaming.installed(appContext)

    override fun unmetered(): Boolean {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return runCatching { !cm.isActiveNetworkMetered }.getOrDefault(true)
    }

    override fun keepAliveForTransfers(active: Boolean, text: String, progress: Int?) {
        io.github.matiyaaa.fuse.platform.DeviceAwake.hold("transfers", active)
        if (active) io.github.matiyaaa.fuse.transfer.TransferService.show(appContext, text, progress)
        else io.github.matiyaaa.fuse.transfer.TransferService.stop(appContext)
    }

    override val awake: kotlinx.coroutines.flow.StateFlow<Boolean> get() = io.github.matiyaaa.fuse.platform.DeviceAwake.awake
    override val interactive: kotlinx.coroutines.flow.StateFlow<Boolean> get() = io.github.matiyaaa.fuse.platform.DeviceAwake.screenOn
    override val online: kotlinx.coroutines.flow.StateFlow<Boolean> get() = io.github.matiyaaa.fuse.platform.NetworkWatch.online
    override val deviceName: String =
        android.provider.Settings.Global.getString(appContext.contentResolver, android.provider.Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    /** Broadcast replies only reach an app holding a multicast lock while it listens. */
    override val jellyfinDiscovery = io.github.matiyaaa.fuse.jellyfin.UdpDiscovery {
        val wifi = appContext.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        wifi?.createMulticastLock("fuse-jellyfin")?.apply {
            setReferenceCounted(false)
            acquire()
        }?.let { lock -> AutoCloseable { lock.release() } }
    }

    /**
     * Fuse Sync by Fuse: Android connects to a host (a computer) rather than being one, since it
     * stops background work to save battery and couldn't promise to stay up for other devices.
     */
    override fun syncthingService(scope: CoroutineScope): io.github.matiyaaa.fuse.sync.syncthing.SyncthingService =
        io.github.matiyaaa.fuse.sync.syncthing.JvmSyncthingService(
            platform = AndroidSyncthing(appContext),
            settings = this.data.settings,
            secrets = secrets,
            scope = scope,
            awake = awake,
        )

    override fun rommSaves(
        env: () -> io.github.matiyaaa.fuse.sync.SaveEnvironment?,
        client: suspend () -> io.github.matiyaaa.fuse.romm.RommClient?,
        server: () -> String,
        person: () -> String?,
    ): io.github.matiyaaa.fuse.sync.romm.RommSaveService = io.github.matiyaaa.fuse.sync.romm.JvmRommSaves(
        dir = java.io.File(appContext.filesDir, "romm-saves"),
        env = { env() ?: io.github.matiyaaa.fuse.sync.FileSaveEnvironment("ANDROID") },
        client = client, server = server,
        deviceName = deviceName, platform = "android", clientVersion = appVersion,
        log = { android.util.Log.i("Fuse", it) },
        person = person,
    )

    override fun syncService(data: io.github.matiyaaa.fuse.sync.ProfileDataPort, scope: CoroutineScope): io.github.matiyaaa.fuse.sync.SyncService {
        // The host is reached with Android's own TLS, as Jellyfin is, so an https tunnel answers the same way.
        io.github.matiyaaa.fuse.sync.SyncHttp.factory = { block -> io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp, block) }
        val service = io.github.matiyaaa.fuse.sync.JvmSyncService(
            dir = java.io.File(appContext.filesDir, "sync"),
            settings = this.data.settings,
            secrets = secrets,
            data = data,
            platform = "ANDROID",
            defaultDeviceName = deviceName,
            fuseVersion = appVersion,
            lifetime = io.github.matiyaaa.fuse.sync.NoHostLifetime("Android stops background work to save battery, so it connects to a host instead of being one."),
            scope = scope,
            // Folders the person granted through the picker (Eden's own data folder): read and written through Android.
            trees = { uri -> io.github.matiyaaa.fuse.platform.AndroidDocumentTree.forUri(appContext, uri) },
            // Game files are read for the household only while charging: on battery it ran a handheld hot.
            mayHash = { charging(appContext) },
            discoveryLock = {
                val wifi = appContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                wifi?.createMulticastLock("fuse-sync")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }?.let { lock -> AutoCloseable { lock.release() } }
            },
            awake = awake,
        )
        // While a game it follows is played, Fuse keeps running to send the save as soon as it is written.
        io.github.matiyaaa.fuse.sync.PlaySyncService.onScreenOff = { done ->
            scope.launch { try { service.sendWhilePlaying() } finally { done() } }
        }
        scope.launch {
            service.nowPlaying.collect { title ->
                if (title != null) io.github.matiyaaa.fuse.sync.PlaySyncService.start(appContext, title)
                else io.github.matiyaaa.fuse.sync.PlaySyncService.stop(appContext)
            }
        }
        return service
    }

    // Emulator Settings for the emulators whose files are in shared storage; their other files stay a computer's to change.
    override val settingsFiles: io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles =
        AndroidEmulatorFiles(io.github.matiyaaa.fuse.sync.FileSaveEnvironment("ANDROID"), java.io.File(appContext.filesDir, "emulator-settings-backups"))

    override val emulators: EmulatorDetector = AndroidEmulatorDetector(appContext, storageVolumes)

    // Android keeps other apps private: Intelligence only remembers each emulator's install here,
    // so a launch is verified for the version it ran in.
    override fun intelligence() = io.github.matiyaaa.fuse.launch.intelligence.FileLook() to
        io.github.matiyaaa.fuse.launch.intelligence.FileKnowledgeStore(java.io.File(appContext.filesDir, "intelligence.json"))
    override val launcher: GameLauncher = AndroidGameLauncher(
        appContext,
        activities,
        storageVolumes,
        sources = { data.sources.all().filter { it.enabled }.map { SourceRoot(it.path, it.kind) } },
        platformAt = { path -> data.games.idByPath(path)?.let { data.games.summary(it) }?.platformId?.value },
        dualScreen = dualScreen,
        secondScreen = secondScreen,
    )
    override val cartridge: CartridgeBridge = AndroidCartridgeBridge(appContext, activities)
    private val releaseInstaller = AndroidReleaseInstaller(appContext, http, activities)
    override val installer: ReleaseInstaller = releaseInstaller
    override val packages: PackageBridge = AndroidPackageBridge(appContext, activities)

    /** The lists of Android ports carried inside the app as resources (see app/android/build.gradle.kts, portsLists). */
    override val androidPortLists: io.github.matiyaaa.fuse.ui.shell.store.AndroidPortLists = object : io.github.matiyaaa.fuse.ui.shell.store.AndroidPortLists {
        override fun bundledIndex(): String? = resource("/ports/index.json")

        override fun bundledListing(): String? = resource("/ports/android/openports.json")

        private fun resource(path: String): String? =
            runCatching { AndroidFuseServices::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() } }.getOrNull()
    }
    override val apps: AppsProvider = AndroidAppsProvider(appContext, scope, activities, dualScreen, releaseInstaller::installLocal)
    override val locations: DeviceLocations = AndroidDeviceLocations(storageVolumes)
    override val volumes: VolumeMonitor = io.github.matiyaaa.fuse.storage.AndroidVolumes(appContext)
    override val folderWatch: io.github.matiyaaa.fuse.ui.shell.store.FolderWatch = io.github.matiyaaa.fuse.storage.AndroidFolderWatch()

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
            if (path.startsWith("content://")) {
                // A picture the user chose, or one another app (Cartridge) hands over: read through
                // the provider, never more than asked for.
                appContext.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { input ->
                    val bytes = input.readNBytesCompat(maxBytes + 1)
                    bytes.takeIf { it.size <= maxBytes }
                }
            } else {
                File(path).takeIf { it.isFile && it.length() <= maxBytes }?.readBytes()
            }
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

    override suspend fun writeBeside(beside: String, name: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (name.isBlank() || '/' in name || '\\' in name || name == "." || name == "..") return@withContext null
        try {
            val dir = File(beside).absoluteFile.parentFile ?: return@withContext null
            val file = File(dir, name)
            if (file.exists()) return@withContext null
            val temp = File(dir, ".$name.fuse-part")
            temp.writeBytes(bytes)
            // Never over a file that appeared meanwhile.
            if (file.exists() || !temp.renameTo(file)) {
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

    override suspend fun copyBeside(from: String, name: String, edits: List<io.github.matiyaaa.fuse.library.patch.FileEdit>): String? = withContext(Dispatchers.IO) {
        if (name.isBlank() || '/' in name || '\\' in name || name == "." || name == "..") return@withContext null
        val source = File(from).absoluteFile
        val dir = source.parentFile ?: return@withContext null
        val file = File(dir, name)
        if (!source.isFile || file.exists()) return@withContext null
        val temp = File(dir, ".$name.fuse-part")
        try {
            source.copyTo(temp, overwrite = true)
            java.io.RandomAccessFile(temp, "rw").use { f ->
                for (e in edits) {
                    f.seek(e.offset)
                    f.write(e.bytes)
                }
            }
            // Never over a file that appeared meanwhile.
            if (file.exists() || !temp.renameTo(file)) {
                temp.delete()
                return@withContext null
            }
            file.absolutePath
        } catch (e: IOException) {
            temp.delete()
            null
        } catch (e: SecurityException) {
            temp.delete()
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

/** Up to [limit] bytes of the stream (InputStream.readNBytes is Android 13 and later). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Plugged in (charging, or full on the charger): the battery's last report, which Android keeps. */
private fun charging(context: android.content.Context): Boolean = runCatching {
    val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}.getOrDefault(false)
