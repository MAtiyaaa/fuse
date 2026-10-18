package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.ui.shell.platform.StayAwake

/**
 * Keeps Android awake while Fuse works ([PlatformUi.stayAwake]): the processor (and Wi-Fi, before
 * Android 14) stays up so art, downloads and uploads carry on with the screen off, and Fuse's window keeps the screen on
 * while its standby screen shows over that work. Everything is let go when the work is done.
 */
internal class AndroidStayAwake(context: Context, private val activities: ActivityHolder) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var screen = false

    @Synchronized
    fun apply(awake: StayAwake) {
        if (awake.cpu) hold() else release()
        DeviceAwake.hold("work", awake.cpu)
        if (awake.screen != screen) {
            screen = awake.screen
            val on = awake.screen
            main.post {
                val window = activities.main?.window ?: return@post
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun hold() {
        if (cpu?.isHeld != true) {
            cpu = appContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fuse:background-work")
                ?.apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
        }
        // Wi-Fi at full speed with the screen off, where Android still gives that (before 14). The
        // low-latency lock asked for before works only with Fuse on screen, where it switched off
        // Wi-Fi's power saving for the whole download and only warmed the device: a busy download
        // keeps the radio awake by itself. From Android 14 every Wi-Fi lock is that one, so none is taken.
        if (wifi?.isHeld != true && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(mode, "fuse:background-work")
                ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        }
    }

    private fun release() {
        cpu?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wifi?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        cpu = null
        wifi = null
    }

    private companion object {
        /** A lock never outlives a stuck job by more than this; Fuse asks again while work goes on. */
        const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
