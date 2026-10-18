package io.github.matiyaaa.fuse.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device is awake for Fuse's own rounds ([io.github.matiyaaa.fuse.ui.shell.store.FuseServices.awake]):
 * its screen on, or Fuse holding it awake for work it was asked to do (a transfer, art being found).
 *
 * Fuse is a Home app and stays in memory under every game, so whatever it repeats goes on while the
 * device sleeps. The wait for Fuse One's news answered every 25 seconds, the household was looked at
 * every 30 and Jellyfin every 30, and each answer woke the processor and the Wi-Fi again: a handheld
 * left asleep, a game open on it, lost a large share of its battery overnight. Asleep, those rounds
 * now wait for the screen to come back and catch up at once.
 */
internal object DeviceAwake {
    private val _screenOn = MutableStateFlow(true)
    private val _awake = MutableStateFlow(true)
    private val holds = HashSet<String>()
    private var started = false

    /** The screen is on (the device is interactive). */
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    /** The screen is on, or Fuse holds the device awake for work. */
    val awake: StateFlow<Boolean> = _awake.asStateFlow()

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        _screenOn.value = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        publish()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                _screenOn.value = intent.action != Intent.ACTION_SCREEN_OFF
                publish()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Fuse keeps the device awake for [reason] while [on] (transfers, art being found). */
    fun hold(reason: String, on: Boolean) {
        synchronized(holds) { if (on) holds += reason else holds -= reason }
        publish()
    }

    private fun publish() {
        val held = synchronized(holds) { holds.isNotEmpty() }
        _awake.value = _screenOn.value || held
    }
}
