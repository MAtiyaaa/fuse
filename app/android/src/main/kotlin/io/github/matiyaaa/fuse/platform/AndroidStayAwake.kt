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
 * Keeps Android awake while Fuse works ([PlatformUi.stayAwake]): the processor and Wi-Fi stay up
 * so art, downloads and uploads carry on with the screen off, and Fuse's window keeps the screen on
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
        if (wifi?.isHeld != true) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
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
