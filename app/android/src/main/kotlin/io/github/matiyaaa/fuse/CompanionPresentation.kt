package io.github.matiyaaa.fuse

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.matiyaaa.fuse.ui.shell.app.CompanionApp

/**
 * Fuse's companion on the second screen: a [Presentation] made by the main activity, the way
 * Cartridge shows its second screen. Android shows a Presentation on a second panel that refuses or
 * hides another app's activity (AYN handhelds).
 *
 * It stays while a game or an app Fuse opened is in front ([away]). It has its own lifecycle for
 * that, since the main activity's stops with it and would freeze what it shows.
 *
 * Touch only: the window is not focusable, so the controller stays with the main screen and the
 * game. Back on this screen does nothing (see [SecondScreenTouch]); [CompanionScreens] decides when
 * it shows.
 */
internal class CompanionPresentation(
    private val owner: ComponentActivity,
    display: Display,
    /** Called when Android shows or hides its window, for the second screen's log. */
    private val onShown: (Boolean) -> Unit = {},
    private val content: @Composable () -> Unit,
) : Presentation(owner, display) {

    private val host = PresentationHost()

    init {
        setCancelable(false)
    }

    /**
     * Fuse went to the background ([away]) or came back. Away, the screen may turn off as usual: a
     * game keeps it on by itself, and an idle device still sleeps.
     */
    fun setAway(away: Boolean) {
        val window = window ?: return
        if (away) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
        // Compose finds its lifecycle, saved state and back handling through the view tree. They
        // are the Presentation's own, so it keeps drawing while the main activity is stopped.
        window.decorView.apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setViewTreeOnBackPressedDispatcherOwner(host)
        }
        val view = ComposeView(context).apply {
            // Its own id, so its saved state never collides with the main screen's ComposeView.
            id = View.generateViewId()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        val root = ShownFrame(context, onShown).apply { addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        host.move(Lifecycle.State.RESUMED)
    }

    override fun onStop() {
        window?.let { app.platformUi.quick.detachSecond(it) }
        host.destroy()
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

/**
 * The lifecycle, saved state and back handling of a [CompanionPresentation]: resumed while it is
 * shown, whatever the main activity is doing, and gone with it.
 */
private class PresentationHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun move(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = state
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/** The Presentation's root: says when Android shows or hides its window. Made in code only. */
@SuppressLint("ViewConstructor")
private class ShownFrame(context: Context, private val onShown: (Boolean) -> Unit) : FrameLayout(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onShown(visibility == VISIBLE)
    }
}

/**
 * What the second screen shows, for the Presentation and the companion activities alike. [onHide]
 * is given only while Fuse is in the background: it frees the second screen until Fuse is back.
 */
@Composable
internal fun CompanionContent(app: FuseApplication, onHide: (() -> Unit)? = null) {
    val startup by app.startup.collectAsState()
    when (val s = startup) {
        is Startup.Ready -> {
            val prefs by s.store.prefs.collectAsState()
            // With the setting off (only the Home instance runs then) this is the clock.
            CompanionApp(s.store, app.platformUi, prefs.display.mode, onHide)
        }
        else -> Box(Modifier.fillMaxSize().background(Color(INK_ARGB)))
    }
}
