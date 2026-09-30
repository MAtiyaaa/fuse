package io.github.matiyaaa.fuse

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Fuse's ink background, also the splash colour (see values/colors.xml). */
internal const val INK_ARGB: Long = 0xFF0B0D12

/** Hides status and navigation bars; a swipe shows them briefly (console-style immersive mode). */
internal fun Activity.enterImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
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

/**
 * Asks for the display's highest refresh rate at the current resolution, or lets the system choose
 * again when [highest] is false (Low Power Mode).
 */
internal fun Activity.preferRefreshRate(highest: Boolean) {
    val display: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display ?: return
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
    val current = display.mode
    val modeId = if (highest) {
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    } else {
        0
    }
    val attributes = window.attributes
    if (attributes.preferredDisplayModeId != modeId) {
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }
}
