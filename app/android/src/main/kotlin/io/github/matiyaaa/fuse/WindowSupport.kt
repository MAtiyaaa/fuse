package io.github.matiyaaa.fuse

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.matiyaaa.fuse.model.DeviceTier
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import kotlin.math.abs

/** Fuse's ink background, also the splash colour (see values/colors.xml). */
internal const val INK_ARGB: Long = 0xFF0B0D12

/** Hides status and navigation bars; a swipe shows them briefly (console-style immersive mode). */
internal fun Activity.enterImmersive() = window.hideSystemBars()

/** The same for any window (the second screen's Presentation). */
internal fun Window.hideSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    WindowInsetsControllerCompat(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** The display this activity is on. */
internal fun Activity.displayIdCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.displayId ?: Display.DEFAULT_DISPLAY
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.displayId
    }

/** How the display this activity is on is turned now (a [android.view.Surface] rotation). */
internal fun Activity.displayRotationCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }

/** The refresh rate Fuse's window asks for, from the performance profile. */
internal enum class RefreshPreference {
    /** The display's fastest mode. */
    HIGHEST,

    /** The mode closest to 60 Hz. */
    STANDARD,

    /** 60 Hz, or the slowest mode of at least 45 Hz when there is no 60 Hz mode. */
    LOW_POWER,
    ;

    companion object {
        /** Picks the profile the way [RenderQuality.of] does: Low Power Mode, then the profile, then the device tier. */
        fun of(profile: PerformanceProfile, tier: DeviceTier, lowPower: Boolean): RefreshPreference {
            val effective = when {
                lowPower -> PerformanceProfile.LOW_POWER
                profile != PerformanceProfile.AUTOMATIC -> profile
                tier == DeviceTier.HIGH -> PerformanceProfile.HIGH_QUALITY
                tier == DeviceTier.LOW -> PerformanceProfile.LOW_POWER
                else -> PerformanceProfile.BALANCED
            }
            return when (effective) {
                PerformanceProfile.HIGH_QUALITY -> HIGHEST
                PerformanceProfile.LOW_POWER -> LOW_POWER
                PerformanceProfile.BALANCED, PerformanceProfile.AUTOMATIC -> STANDARD
            }
        }

        /** The index in [rates] (the refresh rates of the modes at one resolution) to ask for; null leaves it to the system. */
        fun pick(rates: List<Float>, preference: RefreshPreference): Int? {
            val closestTo60 = rates.indices.minByOrNull { abs(rates[it] - 60f) } ?: return null
            return when (preference) {
                HIGHEST -> rates.indices.maxByOrNull { rates[it] }
                STANDARD -> closestTo60
                LOW_POWER -> closestTo60.takeIf { abs(rates[it] - 60f) < 1f }
                    ?: rates.indices.filter { rates[it] >= 45f }.minByOrNull { rates[it] }
            }
        }
    }
}

/** Asks for the display mode at the current resolution that matches [preference]. */
internal fun Activity.preferRefreshRate(preference: RefreshPreference) {
    val display: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display ?: return
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
    val current = display.mode
    val modes = display.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
    val modeId = RefreshPreference.pick(modes.map { it.refreshRate }, preference)?.let { modes[it].modeId } ?: 0
    val attributes = window.attributes
    if (attributes.preferredDisplayModeId != modeId) {
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }
}
