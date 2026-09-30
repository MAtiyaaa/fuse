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
 * controls and video previews. One instance lives as long as the process; things that need an
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

    private val statusMonitor = SystemStatusMonitor(appContext).also { it.start() }
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
            )
        }

    /** Re-reads everything that can change while Fuse is in the background. */
    fun onFuseResumed() {
        homeRole.refresh()
        storage.refresh()
        quick.refresh()
        displayMonitor.refresh()
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

    override fun lastCrashReport(): String? = crashLog.read()

    override fun clearCrashReport() = crashLog.clear()

    /** Leaves Fuse. Ignored while Fuse is Home, where there is nothing to exit to. */
    override fun exit() {
        if (homeRole.isHome.value) return
        activities.companion?.finish()
        activities.main?.finishAffinity()
    }
}
