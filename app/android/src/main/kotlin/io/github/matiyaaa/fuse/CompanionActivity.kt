package io.github.matiyaaa.fuse

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The companion as an activity of its own on the second screen, for while a game runs (with Fuse in
 * front, [CompanionPresentation] shows it instead). Touch only: its window is not focusable, so
 * controller input stays with the main screen, and any key that still arrives is forwarded there.
 * [CompanionScreens] starts and stops it; it also closes itself when its display has been gone or
 * off for [CompanionScreens.GRACE_MS], rather than covering the main screen.
 */
open class CompanionActivity : ComponentActivity() {
    private val app: FuseApplication get() = application as FuseApplication

    /** True for [CompanionHomeActivity], which Android starts as the second screen's Home and which never closes itself. */
    open val isDisplayHome: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (leaveMainScreen()) return
        val role = if (isDisplayHome) "Home companion" else "Companion"
        SecondScreenLog.add("$role running on display ${displayIdCompat()}")
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        app.platformUi.quick.attachSecond(window)
        enterImmersive()
        // A back swipe on the second screen must not close it; the setting controls it.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        if (!isDisplayHome) {
            lifecycleScope.launch {
                app.platformUi.displays
                    .map { displays -> displays.any { it.id == displayIdCompat() && it.isOn } }
                    .distinctUntilChanged()
                    .collectLatest { present ->
                        if (present) return@collectLatest
                        delay(CompanionScreens.GRACE_MS)
                        SecondScreenLog.add("Companion closed: display ${displayIdCompat()} was gone or off")
                        finish()
                    }
            }
        }

        setContent { CompanionContent(app) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leaveMainScreen()
    }

    override fun onResume() {
        super.onResume()
        // A removed display moves its activities to the main screen: never stay there.
        leaveMainScreen()
    }

    /**
     * Finishes when this window is on the main screen, and tells [CompanionScreens] which display
     * it was meant for so that display is not tried again. Returns true when it finished.
     */
    private fun leaveMainScreen(): Boolean {
        if (isDisplayHome || isFinishing || displayIdCompat() != Display.DEFAULT_DISPLAY) return false
        app.companions.onLandedOnMainScreen(intent?.getIntExtra(EXTRA_TARGET_DISPLAY, -1) ?: -1)
        finish()
        return true
    }

    override fun onDestroy() {
        app.platformUi.quick.detachSecond(window)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        SecondScreenTouch.touched()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Controller keys go to the main screen. Back from this screen itself (its navigation bar or
     * gesture, not a controller) does nothing: the second screen never moves Fuse back.
     */
    @SuppressLint("RestrictedApi") // Lint false positive: Activity.dispatchKeyEvent is public API.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controller = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !controller) return true
        return app.activities.main?.forwardKey(event) == true || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        app.activities.main?.forwardMotion(event) == true || super.dispatchGenericMotionEvent(event)

    companion object {
        /** The display [CompanionScreens] asked for, to know which one refused when Android ignores it. */
        const val EXTRA_TARGET_DISPLAY = "io.github.matiyaaa.fuse.extra.TARGET_DISPLAY"
    }
}

/**
 * The same companion, declared with a SECONDARY_HOME filter: while Fuse is the Home app, Android
 * starts it on every secondary display that shows a Home. It is that display's Home, so it never
 * closes itself (Android would only start it again), and it shows the clock when the second screen
 * setting is off. A separate class because Android refuses a single-instance activity as a
 * secondary Home, while Fuse's own companion must stay single-instance.
 */
class CompanionHomeActivity : CompanionActivity() {
    override val isDisplayHome: Boolean get() = true
}
