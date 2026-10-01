package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
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
 * Brightness applies to Fuse's own window, unless "Modify system settings" was allowed for Fuse:
 * then the main screen's brightness itself changes, so it holds in games too. The second screen's
 * brightness is that of Fuse's window there. Volume is the media stream, followed live. Wi-Fi and
 * Bluetooth open the system panels, since apps can't toggle them on current Android.
 */
class AndroidQuickControls(
    context: Context,
    private val activities: ActivityHolder,
) : QuickControls {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    /** Brightness chosen in Fuse, or null to follow the system. Re-applied when the window is recreated. */
    private var override: Float? = null

    /** The second screen's brightness chosen in Fuse, and Fuse's windows there. */
    private var secondOverride: Float? = null
    private val secondWindows = ArrayList<java.lang.ref.WeakReference<android.view.Window>>()

    private val _brightness = MutableStateFlow(readBrightness())
    override val brightness: StateFlow<Float?> = _brightness.asStateFlow()
    private val _volume = MutableStateFlow(readVolume())
    override val volume: StateFlow<Float?> = _volume.asStateFlow()
    private val _second = MutableStateFlow<Float?>(null)
    override val secondBrightness: StateFlow<Float?> = _second.asStateFlow()
    private val _system = MutableStateFlow(canWriteSystem())
    override val systemBrightness: StateFlow<Boolean> = _system.asStateFlow()
    override val canAskSystemBrightness: Boolean get() = true

    init {
        // Volume changed with the device's buttons or another app shows at once.
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        _volume.value = readVolume()
                        if (_system.value) _brightness.value = readBrightness()
                    }
                },
            )
        }
    }

    fun refresh() {
        _system.value = canWriteSystem()
        _brightness.value = readBrightness()
        _volume.value = readVolume()
    }

    /** Applies the chosen brightness to a new window of Fuse. */
    fun applyTo(window: android.view.Window) {
        val value = override ?: return
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    /** A window of Fuse on the second screen; its brightness follows [setSecondBrightness]. */
    fun attachSecond(window: android.view.Window) {
        secondWindows.removeAll { it.get() == null || it.get() === window }
        secondWindows += java.lang.ref.WeakReference(window)
        secondOverride?.let { v -> window.attributes = window.attributes.apply { screenBrightness = v } }
        if (_second.value == null) _second.value = secondOverride ?: readBrightness() ?: 0.5f
    }

    fun detachSecond(window: android.view.Window) {
        secondWindows.removeAll { it.get() == null || it.get() === window }
        if (secondWindows.isEmpty()) _second.value = null
    }

    override fun setSecondBrightness(value: Float) {
        val v = value.coerceIn(0.02f, 1f)
        secondOverride = v
        secondWindows.mapNotNull { it.get() }.forEach { w -> w.attributes = w.attributes.apply { screenBrightness = v } }
        _second.value = v
    }

    override fun askSystemBrightness() {
        val page = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${appContext.packageName}"))
        activities.startFirst(page, Intent(Settings.ACTION_DISPLAY_SETTINGS))
    }

    private fun canWriteSystem(): Boolean = runCatching { Settings.System.canWrite(appContext) }.getOrDefault(false)

    override fun setBrightness(value: Float) {
        val v = value.coerceIn(0.02f, 1f)
        if (canWriteSystem()) {
            // The screen itself: manual mode, then the level, and Fuse's window follows it again.
            val written = runCatching {
                val resolver = appContext.contentResolver
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (v * 255).roundToInt().coerceIn(1, 255))
            }.isSuccess
            if (written) {
                override = null
                activities.main?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
                _system.value = true
                _brightness.value = v
                return
            }
        }
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
