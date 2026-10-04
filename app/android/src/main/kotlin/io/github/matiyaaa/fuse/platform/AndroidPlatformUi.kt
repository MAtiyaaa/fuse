package io.github.matiyaaa.fuse.platform

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.BuildConfig
import io.github.matiyaaa.fuse.CompanionScreens
import io.github.matiyaaa.fuse.CrashLog
import io.github.matiyaaa.fuse.SecondScreenLog
import io.github.matiyaaa.fuse.capture.AndroidScreenCapture
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.storage.StorageVolumes
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformFeatures
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess
import io.github.matiyaaa.fuse.ui.shell.platform.VideoPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The Android side of the interface: status, displays, sounds, haptics, Home role, storage, quick
 * controls, video previews and screen capture. One instance lives as long as the process; things that need an
 * activity go through [ActivityHolder].
 */
class AndroidPlatformUi(
    context: Context,
    private val activities: ActivityHolder,
    scope: CoroutineScope,
    volumes: StorageVolumes,
    private val crashLog: CrashLog,
) : PlatformUi {
    private val appContext = context.applicationContext

    private val statusMonitor = SystemStatusMonitor(appContext, scope).also { it.start() }
    val displayMonitor = DisplayMonitor(appContext)
    private val performanceMonitor = PerformanceMonitor(appContext, activities, statusMonitor, scope)

    override val host: Host = Host.ANDROID
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val device: CapabilityProfile = DeviceProfile.measure(appContext)
    override val status: StateFlow<SystemStatus> = statusMonitor.status
    override val displays: StateFlow<List<DisplayInfo>> = displayMonitor.displays
    override val performance: StateFlow<List<PerformanceMetric>> = performanceMonitor.metrics
    override val sounds: AndroidUiSounds = AndroidUiSounds().also { it.preload() }
    override val haptics: AndroidHaptics = AndroidHaptics(appContext)
    override val homeRole: AndroidHomeRole = AndroidHomeRole(appContext, activities, scope)
    override val storage: AndroidStorageAccess = AndroidStorageAccess(appContext, activities, volumes, scope)
    override val quick: AndroidQuickControls = AndroidQuickControls(appContext, activities)
    override val video: VideoPreview = AndroidVideoPreview()
    override val music: AndroidMenuMusic = AndroidMenuMusic()
    override val capture: AndroidScreenCapture = AndroidScreenCapture(appContext, activities, scope)
    override val secondScreenLog: StateFlow<List<String>> = SecondScreenLog.entries

    private val hasBluetooth = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    /** Read on every access: the second screen and the Home role change while Fuse runs. */
    override val features: PlatformFeatures
        get() {
            val hasSecond = displayMonitor.secondary() != null
            // The companion is a Presentation while Fuse is in front, which every supported Android version shows.
            val companion = displayMonitor.presentationTarget() != null
            return PlatformFeatures(
                homeRole = homeRole.available,
                androidApps = true,
                secondScreen = companion,
                launchOnOtherDisplay = hasSecond,
                // No overlay over other apps: Fuse does not request SYSTEM_ALERT_WINDOW.
                overlay = false,
                videoPreview = true,
                brightness = true,
                volume = true,
                wifiSettings = true,
                bluetoothSettings = hasBluetooth,
                canExit = !homeRole.isHome.value,
                windowModes = false,
                rotation = true,
            )
        }

    /** Re-reads everything that can change while Fuse is in the background. */
    fun onFuseResumed() {
        homeRole.refresh()
        storage.refresh()
        quick.refresh()
        displayMonitor.refresh()
    }

    override suspend fun saveFile(name: String, mimeType: String, bytes: ByteArray): String? {
        val requests = activities.main ?: return null
        val uri = requests.createDocument(name, mimeType) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return@withContext null
                displayName(uri)?.first ?: name
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun openFile(mimeTypes: List<String>, extensions: List<String>, maxBytes: Long): io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile? {
        val requests = activities.main ?: return null
        val uri = requests.openDocument(mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val (name, size) = displayName(uri) ?: ("file" to 0L)
                if (size > maxBytes) return@withContext null
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { input -> input.readNBytesCompat(maxBytes + 1) } ?: return@withContext null
                if (bytes.size > maxBytes) null else io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile(name, bytes)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** A document's name and size as its provider reports them. */
    private fun displayName(uri: android.net.Uri): Pair<String, Long>? = try {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0) ?: return@use null
            name to (if (c.isNull(1)) 0L else c.getLong(1))
        }
    } catch (e: Exception) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    override fun openUrl(url: String) {
        val uri = url.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return
        activities.start(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** Starts Fuse again in a fresh process (after a restore or a setting that needs it). */
    override fun restart() {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return
        val component = launch.component ?: return
        val intent = Intent.makeRestartActivityTask(component)
        try {
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return
        }
        Runtime.getRuntime().exit(0)
    }

    /** Reads on the main thread, while Fuse has focus: Android 10+ hides the clipboard from apps in the background. */
    override suspend fun readClipboardText(): String? = withContext(Dispatchers.Main) {
        try {
            val clip = appContext.getSystemService(ClipboardManager::class.java)?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                null
            } else {
                clip.getItemAt(0).coerceToText(activities.context)?.toString()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    override suspend fun writeClipboardText(text: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return@withContext false
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Fuse theme", text))
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    override fun lastCrashReport(): String? = crashLog.read()

    override fun clearCrashReport() = crashLog.clear()

    /** Leaves Fuse. Ignored while Fuse is Home, where there is nothing to exit to. */
    override fun exit() {
        if (homeRole.isHome.value) return
        activities.companion?.finish()
        activities.main?.finishAffinity()
    }
}
