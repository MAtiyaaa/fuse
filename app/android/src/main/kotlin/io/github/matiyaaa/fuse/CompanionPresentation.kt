package io.github.matiyaaa.fuse

import android.app.Presentation
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.matiyaaa.fuse.ui.shell.app.CompanionApp

/**
 * Fuse's companion on the second screen while Fuse is in front: a [Presentation] owned by the main
 * activity, the way Cartridge shows its second screen. Android shows a Presentation on a second
 * panel that refuses or hides another app's activity (AYN handhelds), and it lives and dies with
 * the main activity, so nothing is left behind on the second screen.
 *
 * Touch only: the window is not focusable, so the controller stays with the main screen. Back on
 * this screen does nothing (see [SecondScreenTouch]); [CompanionScreens] decides when it shows.
 */
internal class CompanionPresentation(
    private val owner: ComponentActivity,
    display: Display,
    private val content: @Composable () -> Unit,
) : Presentation(owner, display) {

    init {
        setCancelable(false)
    }

    private val app: FuseApplication get() = owner.application as FuseApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = window ?: return
        // Touches next to it (the second screen's own navigation bar) are reported too, so a Back
        // pressed down there is known to come from this screen ([SecondScreenTouch]).
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
        app.platformUi.quick.attachSecond(window)
        window.setBackgroundDrawable(ColorDrawable(INK_ARGB.toInt()))
        // Compose finds its lifecycle, saved state and back handling through the view tree; a
        // Presentation is a Dialog, so they come from the activity that owns it.
        window.decorView.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
        }
        val view = ComposeView(context).apply {
            // Its own id, so its saved state never collides with the main screen's ComposeView.
            id = View.generateViewId()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.hideSystemBars()
    }

    override fun onStop() {
        window?.let { app.platformUi.quick.detachSecond(it) }
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        SecondScreenTouch.touched()
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * When the second screen was last touched. Its Back (a navigation bar button or the back gesture
 * there) reaches the main screen's window, since the companion never takes focus; the main screen
 * ignores a Back that comes right after a touch down here, so only the controller and the main
 * screen's own Back move Fuse back.
 */
internal object SecondScreenTouch {
    @Volatile
    private var lastAt = 0L

    fun touched() {
        lastAt = SystemClock.uptimeMillis()
    }

    /** True when a Back now most likely came from the second screen. */
    fun justTouched(): Boolean = SystemClock.uptimeMillis() - lastAt < WINDOW_MS

    /** A back gesture takes a moment from the first touch to the Back it makes. */
    private const val WINDOW_MS = 1_200L
}

/** What the second screen shows, for the Presentation and the companion activities alike. */
@Composable
internal fun CompanionContent(app: FuseApplication) {
    val startup by app.startup.collectAsState()
    when (val s = startup) {
        is Startup.Ready -> {
            val prefs by s.store.prefs.collectAsState()
            // With the setting off (only the Home instance runs then) this is the clock.
            CompanionApp(s.store, app.platformUi, prefs.display.mode)
        }
        else -> Box(Modifier.fillMaxSize().background(Color(INK_ARGB)))
    }
}
