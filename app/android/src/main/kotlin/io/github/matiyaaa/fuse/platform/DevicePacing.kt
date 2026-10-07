package io.github.matiyaaa.fuse.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import io.github.matiyaaa.fuse.ui.fuseline.DevicePressure
import io.github.matiyaaa.fuse.ui.fuseline.FramePacing

/**
 * Tells Fuseline what the device says about itself: its thermal status (Android 10 and later) and
 * whether battery saver is on. Under pressure Fuseline updates decoration less often (an ambient
 * room, a slow drift), each update at its true time; what the person is doing keeps every frame.
 */
internal object DevicePacing {
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermal(power.currentThermalStatus)
            power.addThermalStatusListener(app.mainExecutor) { thermal(it) }
        }
        FramePacing.powerSaving = power.isPowerSaveMode
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    FramePacing.powerSaving = power.isPowerSaveMode
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )
    }

    private fun thermal(status: Int) {
        FramePacing.devicePressure = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> DevicePressure.THROTTLED
            status >= PowerManager.THERMAL_STATUS_MODERATE -> DevicePressure.HOT
            status >= PowerManager.THERMAL_STATUS_LIGHT -> DevicePressure.WARM
            else -> DevicePressure.NONE
        }
    }
}
