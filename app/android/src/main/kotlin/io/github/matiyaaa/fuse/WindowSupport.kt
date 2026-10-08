package io.github.matiyaaa.fuse

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

/** Requests only native-resolution modes of this window's own display. */
internal fun android.view.Window.preferRefreshRate(display: Display, maximum: Int = 0): Float? {
    val current = display.mode
    val modes = display.supportedModes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }
    val selected = io.github.matiyaaa.fuse.model.RefreshRates.pick(modes.map { it.refreshRate }, maximum)
        ?.let { modes[it] }
    val attributes = attributes
    val modeId = selected?.modeId ?: 0
    if (attributes.preferredDisplayModeId != modeId) {
        attributes.preferredDisplayModeId = modeId
        this.attributes = attributes
    }
    return selected?.refreshRate
}
