package io.github.matiyaaa.fuse

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.matiyaaa.fuse.input.GamepadInput
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.handleKeyEvent
import io.github.matiyaaa.fuse.ui.shell.app.FuseApp
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fuse's only window on the main screen. It owns the [InputRouter] (raw controller and keyboard
 * input arrives here), keeps the interface immersive at the refresh rate the performance profile
 * asks for, bridges pickers and system dialogs for the platform layer, and hands Back to the
 * interface instead of closing: as the Home app there is nothing to go back to.
 */
class MainActivity : ComponentActivity(), ActivityRequests {
    private val app: FuseApplication get() = application as FuseApplication
    private val router: InputRouter by lazy { InputRouter(lifecycleScope) }
    private val gamepad: GamepadInput by lazy { GamepadInput(router) }
    private val companions: CompanionScreens get() = app.companions
    private var resumedOnce = false

    private val folderSlot = ResultSlot<Uri?>(null)
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderSlot.complete(it) }
    private val imageSlot = ResultSlot<Uri?>(null)
    private val imageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { imageSlot.complete(it) }
    private val roleSlot = ResultSlot(false)
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleSlot.complete(it.resultCode == RESULT_OK)
    }
    private val permissionSlot = ResultSlot(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionSlot.complete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        enterImmersive()
        preferRefreshRate(RefreshPreference.of(PerformanceProfile.AUTOMATIC, app.platformUi.device.tier, lowPower = false))
        app.platformUi.quick.applyTo(window)

        // Back always goes to the interface, as the Escape button, so button mapping and the
        // controller test see it too. Fuse never finishes itself on Back.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val source = router.lastSource.value
                    router.press(PadButton.KEY_ESCAPE, source)
                    router.release(PadButton.KEY_ESCAPE, source)
                }
            },
        )

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                val store = (app.startup.value as? Startup.Ready)?.store
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        // The first resume is covered by FuseApp, which calls onResume when it starts.
                        if (resumedOnce) store?.library?.onResume()
                        resumedOnce = true
                        // Back from a game or another app: the companion goes in front on its screen again.
                        store?.let { companions.onMainResumed(this, it.prefs.value.display.mode) }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        gamepad.releaseAll()
                        store?.library?.onPause()
                    }
                    // A game or another app is in front: the second screen is theirs.
                    Lifecycle.Event.ON_STOP -> companions.onMainStopped()
                    else -> Unit
                }
            },
        )

        lifecycleScope.launch {
            val store = app.awaitStore() ?: return@launch
            repeatOnLifecycle(Lifecycle.State.STARTED) { followPrefs(store) }
        }

        setContent { Content() }
    }

    private suspend fun followPrefs(store: FuseStore) {
        kotlinx.coroutines.coroutineScope {
            launch {
                store.prefs.map { it.input }.distinctUntilChanged().collect { input ->
                    app.platformUi.haptics.intensity = input.vibration
                    app.platformUi.sounds.setNavigation(input.soundsEnabled, input.navigationSoundVolume)
                }
            }
            launch {
                val tier = app.platformUi.device.tier
                store.prefs.map { RefreshPreference.of(it.performance, tier, it.lowPower) }
                    .distinctUntilChanged()
                    .collect { preferRefreshRate(it) }
            }
            launch {
                kotlinx.coroutines.flow.combine(
                    store.prefs.map { it.display.mode }.distinctUntilChanged(),
                    app.platformUi.displays,
                ) { mode, _ -> mode }.collect { mode -> companions.update(this@MainActivity, mode) }
            }
        }
    }

    @Composable
    private fun Content() {
        val startup by app.startup.collectAsState()
        Box(Modifier.fillMaxSize().background(Color(INK_ARGB))) {
            when (val s = startup) {
                // A plain ink screen, the same colour as the system splash, so nothing flashes.
                Startup.Loading -> Unit
                is Startup.Failed -> BasicText(
                    s.message,
                    style = TextStyle(color = Color(0xFFE6E8EF), fontSize = 18.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is Startup.Ready -> {
                    val focus = remember { FocusRequester() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            // Keys Compose did not use (letters on a keyboard) still reach navigation.
                            .onKeyEvent { router.handleKeyEvent(it) }
                            .focusRequester(focus)
                            .focusable(),
                    ) {
                        FuseApp(s.store, app.platformUi, router)
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi") // Lint false positive: Activity.dispatchKeyEvent is public API.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepad.onKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotion(event) || super.dispatchGenericMotionEvent(event)

    /** Controller input that reached the companion screen by mistake is handled as if it came here. */
    fun forwardKey(event: KeyEvent): Boolean = gamepad.onKey(event)

    fun forwardMotion(event: MotionEvent): Boolean = gamepad.onMotion(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive() else gamepad.releaseAll()
    }

    /**
     * Home pressed while Fuse is Home. When Fuse was on screen it goes to its Home page; when it comes
     * back from a game it stays where the player left it. The activity is never recreated.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        val wasVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isHome && wasVisible && app.startup.value is Startup.Ready) {
            router.dispatch(NavAction.HOME, router.lastSource.value)
        }
    }

    override fun onDestroy() {
        folderSlot.cancel()
        imageSlot.cancel()
        roleSlot.cancel()
        permissionSlot.cancel()
        if (isFinishing) companions.stop()
        super.onDestroy()
    }

    // ActivityRequests

    override suspend fun pickFolder(): Uri? = folderSlot.request { folderLauncher.launch(null) }

    override suspend fun pickImage(): Uri? = imageSlot.request {
        imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override suspend fun requestRole(intent: Intent): Boolean = roleSlot.request { roleLauncher.launch(intent) }

    override suspend fun requestPermission(permission: String): Boolean = permissionSlot.request { permissionLauncher.launch(permission) }
}
