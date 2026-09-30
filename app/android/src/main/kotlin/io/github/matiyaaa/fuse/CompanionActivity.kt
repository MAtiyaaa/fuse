package io.github.matiyaaa.fuse

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import io.github.matiyaaa.fuse.ui.shell.app.CompanionApp
import kotlinx.coroutines.launch

/**
 * Fuse's second-screen window (dual-screen handhelds, external displays). Touch only: its window is
 * not focusable, so controller input stays with the main screen, and any key that still arrives is
 * forwarded there. It closes itself when its display goes away rather than covering the main screen.
 */
class CompanionActivity : ComponentActivity() {
    private val app: FuseApplication get() = application as FuseApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (displayIdCompat() == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        enterImmersive()
        // A back swipe on the second screen must not close it; the setting controls it.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        lifecycleScope.launch {
            app.platformUi.displays.collect { displays ->
                val id = displayIdCompat()
                if (id == Display.DEFAULT_DISPLAY || displays.none { it.id == id }) finish()
            }
        }

        setContent {
            val startup by app.startup.collectAsState()
            when (val s = startup) {
                is Startup.Ready -> {
                    val prefs by s.store.prefs.collectAsState()
                    CompanionApp(s.store, app.platformUi, prefs.display.mode)
                }
                else -> Box(Modifier.fillMaxSize().background(Color(INK_ARGB)))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A removed display moves its activities to the main screen: never stay there.
        if (displayIdCompat() == Display.DEFAULT_DISPLAY) finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        app.activities.main?.forwardKey(event) == true || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        app.activities.main?.forwardMotion(event) == true || super.dispatchGenericMotionEvent(event)
}
