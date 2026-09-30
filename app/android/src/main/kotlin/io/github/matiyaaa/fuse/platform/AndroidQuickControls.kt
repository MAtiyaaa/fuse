package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.ui.shell.platform.QuickControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Brightness applies to Fuse's own window only (changing the system brightness needs WRITE_SETTINGS,
 * which Fuse does not ask for). Volume is the media stream. Wi-Fi and Bluetooth open the system
 * panels, since apps can't toggle them on current Android.
 */
class AndroidQuickControls(
    context: Context,
    private val activities: ActivityHolder,
) : QuickControls {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    /** Brightness chosen in Fuse, or null to follow the system. Re-applied when the window is recreated. */
    private var override: Float? = null

    private val _brightness = MutableStateFlow(readBrightness())
    override val brightness: StateFlow<Float?> = _brightness.asStateFlow()
    private val _volume = MutableStateFlow(readVolume())
    override val volume: StateFlow<Float?> = _volume.asStateFlow()

    fun refresh() {
        _brightness.value = readBrightness()
        _volume.value = readVolume()
    }

    /** Applies the chosen brightness to a new window of Fuse. */
    fun applyTo(window: android.view.Window) {
        val value = override ?: return
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun setBrightness(value: Float) {
        val v = value.coerceIn(0.02f, 1f)
        override = v
        activities.main?.window?.let { applyTo(it) }
        _brightness.value = v
    }

    override fun setVolume(value: Float) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).roundToInt(), 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse volume changes.
        }
        _volume.value = readVolume()
    }

    override fun openWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activities.startFirst(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
        } else {
            activities.startFirst(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    override fun openBluetooth() {
        activities.startFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    override fun openDisplaySettings() {
        activities.startFirst(Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    override fun openSoundSettings() {
        activities.startFirst(Intent(Settings.ACTION_SOUND_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    override fun openSystemSettings() {
        activities.startFirst(Intent(Settings.ACTION_SETTINGS))
    }

    /** Controllers pair over Bluetooth; physical keyboard settings are the fallback. */
    override fun openControllerSettings() {
        activities.startFirst(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    private fun readBrightness(): Float? {
        override?.let { return it }
        val window = activities.main?.window
        val own = window?.attributes?.screenBrightness
        if (own != null && own >= 0f && own != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return own
        return try {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun readVolume(): Float? {
        val am = audio ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
}
