package io.github.matiyaaa.fuse.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.storage.toHex
import io.github.matiyaaa.fuse.ui.shell.store.AppIconModel
import io.github.matiyaaa.fuse.ui.shell.store.ArchiveInfo
import io.github.matiyaaa.fuse.ui.shell.store.DownloadSink
import io.github.matiyaaa.fuse.ui.shell.store.InstalledPackage
import io.github.matiyaaa.fuse.ui.shell.store.PackageBridge
import io.github.matiyaaa.fuse.ui.shell.store.PackageOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The Store's view of Android: installed packages from the PackageManager (watched through the
 * system's package broadcasts), downloads in Fuse's own cache (`cache/store`, never a user folder),
 * APKs read with [PackageManager.getPackageArchiveInfo], and installs and uninstalls through
 * [PackageInstaller], which always shows Android's own confirmation ([InstallResultReceiver] opens
 * it). Nothing is ever installed or removed without the user confirming it in Android.
 */
class AndroidPackageBridge(
    context: Context,
    private val activities: ActivityHolder,
) : PackageBridge {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val dir = File(appContext.cacheDir, "store")

    override val abis: List<String> = Build.SUPPORTED_ABIS.toList()

    override val hasSecondScreen: Boolean
        get() {
            val dm = appContext.getSystemService(DisplayManager::class.java) ?: return false
            // A second screen built into the device shows as a presentation display; recording and
            // casting make private or virtual ones, which don't count.
            return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).any { d ->
                d.displayId != Display.DEFAULT_DISPLAY && (d.flags and Display.FLAG_PRIVATE) == 0 &&
                    !(d.name ?: "").contains("virtual", ignoreCase = true)
            }
        }

    private val changed = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val changes: SharedFlow<String> = changed

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    intent.data?.schemeSpecificPart?.let { changed.tryEmit(it) }
                }
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Downloads a previous run left (Fuse closed mid-download) are not kept.
        dir.listFiles()?.forEach { it.delete() }
    }

    override suspend fun installed(packageName: String): InstalledPackage? = withContext(Dispatchers.IO) {
        val info = PackageSupport.packageInfo(pm, packageName) ?: return@withContext null
        InstalledPackage(info.packageName, info.versionName, info.longVersionCode, PackageSupport.label(pm, info))
    }

    override fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    override fun requestInstallPermission() {
        activities.startFirst(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri()),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
    }

    override fun freeBytes(): Long? = try {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    } catch (e: IllegalArgumentException) {
        null
    }

    override suspend fun newDownload(fileName: String): DownloadSink? = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext null
        val safe = fileName.substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }.trimStart('.').ifEmpty { "app.apk" }
        val file = File(dir, "${System.currentTimeMillis()}-$safe")
        val out = try {
            FileOutputStream(file)
        } catch (e: IOException) {
            return@withContext null
        }
        FileSink(file, out)
    }

    private class FileSink(private val file: File, private val out: FileOutputStream) : DownloadSink {
        private val digest = MessageDigest.getInstance("SHA-256")
        @Volatile private var closed = false
        override val path: String = file.absolutePath

        override suspend fun write(bytes: ByteArray, count: Int) = withContext(Dispatchers.IO) {
            out.write(bytes, 0, count)
            digest.update(bytes, 0, count)
        }

        override suspend fun finish(): String = withContext(Dispatchers.IO) {
            out.fd.sync()
            out.close()
            closed = true
            digest.digest().toHex()
        }

        override suspend fun discard() {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!closed) runCatching { out.close() }
                closed = true
                file.delete()
            }
        }
    }

    override suspend fun inspect(path: String): ArchiveInfo? = withContext(Dispatchers.IO) {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, 0)
            }
        } catch (e: RuntimeException) {
            null
        } ?: return@withContext null
        ArchiveInfo(info.packageName, info.versionName, info.longVersionCode)
    }

    override suspend fun install(path: String, onTurn: () -> Unit): PackageOutcome {
        val file = File(path)
        if (!file.isFile) return PackageOutcome.Failed("The download is gone. Try again.")
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android always asks the user; Fuse never installs silently.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val id = try {
            withContext(Dispatchers.IO) { installer.createSession(params) }
        } catch (e: IOException) {
            return PackageOutcome.Failed("Android couldn't start the installation.")
        } catch (e: RuntimeException) {
            return PackageOutcome.Failed("Android couldn't start the installation.")
        }
        val result = InstallResults.expect(id)
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                    }
                    val callback = Intent(appContext, InstallResultReceiver::class.java).setPackage(appContext.packageName)
                        .putExtra(InstallResultReceiver.EXTRA_STORE, true)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(appContext, id, callback, flags).intentSender)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                abandon(id)
                throw e
            }
            abandon(id)
            InstallResults.forget(id)
            return PackageOutcome.Failed("Android couldn't start the installation.")
        }
        onTurn()
        try {
            while (true) {
                withTimeoutOrNull(SESSION_POLL_MS) { result.await() }?.let { return it }
                // The confirmation was dismissed in a way that sends nothing back: the session is gone.
                if (withContext(Dispatchers.IO) { installer.getSessionInfo(id) } == null) {
                    return withTimeoutOrNull(SESSION_POLL_MS) { result.await() } ?: PackageOutcome.Cancelled
                }
            }
        } catch (e: CancellationException) {
            // The user cancelled in Fuse: the waiting confirmation goes too.
            abandon(id)
            throw e
        } finally {
            InstallResults.forget(id)
        }
    }

    private fun abandon(id: Int) {
        try {
            pm.packageInstaller.abandonSession(id)
        } catch (e: RuntimeException) {
            // Already finished or gone.
        }
    }

    override suspend fun uninstall(packageName: String): PackageOutcome {
        if (PackageSupport.packageInfo(pm, packageName) == null) return PackageOutcome.Done
        val key = InstallResults.uninstallKey(packageName)
        val result = InstallResults.expect(key)
        try {
            val callback = Intent(appContext, InstallResultReceiver::class.java).setPackage(appContext.packageName)
                .putExtra(InstallResultReceiver.EXTRA_STORE, true)
                .putExtra(InstallResultReceiver.EXTRA_UNINSTALL, packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            pm.packageInstaller.uninstall(packageName, PendingIntent.getBroadcast(appContext, key, callback, flags).intentSender)
        } catch (e: RuntimeException) {
            InstallResults.forget(key)
            // Android's own uninstall screen, when the installer route is refused.
            val started = activities.start(Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()))
            if (!started) return PackageOutcome.Failed("Android couldn't open its uninstaller.")
            return waitGone(packageName)
        }
        try {
            val deadline = System.currentTimeMillis() + UNINSTALL_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                withTimeoutOrNull(SESSION_POLL_MS) { result.await() }?.let { return it }
                if (PackageSupport.packageInfo(pm, packageName) == null) return PackageOutcome.Done
            }
            return PackageOutcome.Cancelled
        } finally {
            InstallResults.forget(key)
        }
    }

    /** After Android's own uninstall screen: done when the package is gone, else the user said no. */
    private suspend fun waitGone(packageName: String): PackageOutcome {
        val deadline = System.currentTimeMillis() + UNINSTALL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PackageSupport.packageInfo(pm, packageName) == null) return PackageOutcome.Done
            if (activities.isFuseResumed) {
                delay(SESSION_POLL_MS)
                return if (PackageSupport.packageInfo(pm, packageName) == null) PackageOutcome.Done else PackageOutcome.Cancelled
            }
            delay(SESSION_POLL_MS)
        }
        return PackageOutcome.Cancelled
    }

    override fun launch(packageName: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: pm.getLeanbackLaunchIntentForPackage(packageName) ?: return false
        return activities.start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun iconModel(packageName: String): Any = AppIconModel(packageName)

    private companion object {
        const val SESSION_POLL_MS = 2_000L
        const val UNINSTALL_WAIT_MS = 10L * 60 * 1000
    }
}

/**
 * Where [InstallResultReceiver] hands the Store's install and uninstall results, by session id (or
 * an uninstall's key), to the [AndroidPackageBridge] call waiting for them.
 */
object InstallResults {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<PackageOutcome>>()

    fun expect(id: Int): CompletableDeferred<PackageOutcome> = CompletableDeferred<PackageOutcome>().also { waiting[id] = it }

    fun complete(id: Int, outcome: PackageOutcome) {
        waiting.remove(id)?.complete(outcome)
    }

    fun forget(id: Int) {
        waiting.remove(id)
    }

    /** A request code for uninstalling [packageName], apart from session ids (which are positive). */
    fun uninstallKey(packageName: String): Int = -(packageName.hashCode() and 0x3FFFFFFF) - 1

    /** What Android's [status] means, for the Store. */
    fun outcome(status: Int, message: String?): PackageOutcome = when (status) {
        PackageInstaller.STATUS_SUCCESS -> PackageOutcome.Done
        PackageInstaller.STATUS_FAILURE_ABORTED -> PackageOutcome.Cancelled
        PackageInstaller.STATUS_FAILURE_CONFLICT -> PackageOutcome.Failed("It conflicts with the installed app, which is signed with a different key.", conflict = true)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> PackageOutcome.Failed("Android says the app isn't compatible with this device.")
        PackageInstaller.STATUS_FAILURE_STORAGE -> PackageOutcome.Failed("There isn't enough storage to install it.")
        PackageInstaller.STATUS_FAILURE_INVALID -> PackageOutcome.Failed("Android says the download isn't a valid app.")
        PackageInstaller.STATUS_FAILURE_BLOCKED -> PackageOutcome.Failed("The device blocked the installation.")
        else -> PackageOutcome.Failed("Android couldn't finish" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."))
    }
}
