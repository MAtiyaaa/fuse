package io.github.matiyaaa.fuse

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.matiyaaa.fuse.ui.shell.app.ShowcaseApp
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

    /** This window's menus for the second screen in flipped mode (see [CompanionScreens.menus]). */
    private val ownMenus: @Composable () -> Unit = { FlippedMenus() }

    private val folderSlot = ResultSlot<Uri?>(null)
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderSlot.complete(it) }
    private val imageSlot = ResultSlot<Uri?>(null)
    private val imageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { imageSlot.complete(it) }
    private val audioSlot = ResultSlot<Uri?>(null)
    private val audioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { audioSlot.complete(it) }
    private val roleSlot = ResultSlot(false)
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleSlot.complete(it.resultCode == RESULT_OK)
    }
    private val permissionSlot = ResultSlot(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionSlot.complete(it) }
    private val createSlot = ResultSlot<Uri?>(null)
    private var createType = "application/octet-stream"
    private val createLauncher = registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: android.content.Context, input: String): Intent = super.createIntent(context, input).setType(createType)
    }) { createSlot.complete(it) }
    private val openSlot = ResultSlot<Uri?>(null)
    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { openSlot.complete(it) }
    private val captureSlot = ResultSlot<ActivityResult?>(null)
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        captureSlot.complete(it.takeIf { r -> r.resultCode == RESULT_OK && r.data != null })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        io.github.matiyaaa.fuse.services.UpdateRelaunch.clearReopenNotice(this)
        androidx.core.content.ContextCompat.registerReceiver(
            this, dreamWatch,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Which way round, before anything shows: a handheld stays landscape by its sensor.
        followRotation(io.github.matiyaaa.fuse.model.ScreenRotation.AUTO)
        app.beginInterface(askedSafe = intent?.getStringExtra(EXTRA_SAFE_MODE) == "true" || intent?.getBooleanExtra(EXTRA_SAFE_MODE, false) == true)
        enterImmersive()
        preferRefreshRate(RefreshPreference.of(PerformanceProfile.AUTOMATIC, app.platformUi.device.tier, lowPower = false))
        app.platformUi.quick.applyTo(window)

        // Back always goes to the interface, as the Escape button, so button mapping and the
        // controller test see it too. Fuse never finishes itself on Back.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back pressed on the second screen (its navigation bar or gesture) is not Fuse's.
                    if (SecondScreenTouch.justTouched()) return
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
                        store?.let { companions.onMainResumed(this, it.prefs.value.display.mode, it.prefs.value.display.flipped) }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        gamepad.releaseAll()
                        store?.library?.onPause()
                        pausedWhileAway = true
                    }
                    Lifecycle.Event.ON_START -> app.platformUi.music.setForeground(true)
                    // A game or another app is in front: the sound is theirs, and the companion stays
                    // beside them on the second screen (see CompanionScreens).
                    Lifecycle.Event.ON_STOP -> {
                        // Leaving for a game or another app is a start that went fine.
                        if (store != null) app.settled()
                        stoppedAt = android.os.SystemClock.uptimeMillis()
                        companions.onMainStopped()
                        app.platformUi.music.setForeground(false)
                        // Only Fuse is ever recorded: a recording ends when Fuse leaves the screen.
                        app.platformUi.capture.onFuseStopped()
                    }
                    else -> Unit
                }
            },
        )

        lifecycleScope.launch {
            val store = app.awaitStore() ?: return@launch
            repeatOnLifecycle(Lifecycle.State.STARTED) { followPrefs(store) }
        }

        // Flipped mode: the second screen shows these menus, while this window keeps the controller.
        companions.menus = ownMenus
        setContent { Content() }
    }

    /** Fuse's menus on the second screen (flipped mode), fed by this window's controller input. */
    @Composable
    private fun FlippedMenus() {
        val startup by app.startup.collectAsState()
        val s = startup as? Startup.Ready ?: return
        Box(Modifier.fillMaxSize().background(Color(INK_ARGB))) {
            FuseApp(s.store, app.platformUi, router, s.phoneLink, safeMode = s.safeMode, onSettled = app::settled, startupIntro = true, showcaseElsewhere = true, keepPlace = true)
        }
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
                store.prefs.map { it.display.rotation }.distinctUntilChanged().collect { followRotation(it) }
            }
            launch {
                kotlinx.coroutines.flow.combine(
                    store.prefs.map { it.display.mode to it.display.flipped }.distinctUntilChanged(),
                    app.platformUi.displays,
                ) { wish, _ -> wish }.collect { (mode, flipped) -> companions.update(this@MainActivity, mode, flipped) }
            }
        }
    }

    /**
     * Turns with the device as [rotation] asks. The sensor orientations are used even while
     * Android's rotation lock is on, so a handheld turned over by accident comes back the right
     * way when it is turned back, instead of staying where the lock caught it.
     */
    private fun followRotation(rotation: io.github.matiyaaa.fuse.model.ScreenRotation) {
        val wanted = when (rotation) {
            io.github.matiyaaa.fuse.model.ScreenRotation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            io.github.matiyaaa.fuse.model.ScreenRotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            io.github.matiyaaa.fuse.model.ScreenRotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            io.github.matiyaaa.fuse.model.ScreenRotation.ANY -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            io.github.matiyaaa.fuse.model.ScreenRotation.AUTO ->
                if (naturallyLandscape() || app.platformUi.features.secondScreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    /** True when the built-in screen is wider than tall held the way it was made (a handheld, a TV). */
    private fun naturallyLandscape(): Boolean {
        val turned = when (displayRotationCompat()) {
            android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> true
            else -> false
        }
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return wide != turned
    }

    @Composable
    private fun Content() {
        val startup by app.startup.collectAsState()
        Box(Modifier.fillMaxSize().background(Color(INK_ARGB))) {
            when (val s = startup) {
                // A plain ink screen, the same colour as the system splash, so nothing flashes.
                Startup.Loading -> Unit
                is Startup.Failed -> androidx.compose.foundation.layout.Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(
                        "Fuse could not start",
                        style = TextStyle(color = Color(0xFFE6E8EF), fontSize = 22.sp, textAlign = TextAlign.Center),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
                    BasicText(
                        s.message,
                        style = TextStyle(color = Color(0xB3E6E8EF), fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center),
                    )
                    // As the Home app, the way out must never depend on Fuse working.
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 24.dp))
                    BasicText(
                        "Open Android settings",
                        style = TextStyle(color = Color(0xFFFF6A3D), fontSize = 18.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.clickable { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }.padding(12.dp),
                    )
                    BasicText(
                        "Choose another Home app",
                        style = TextStyle(color = Color(0xFFFF6A3D), fontSize = 18.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.clickable { runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) } }.padding(12.dp),
                    )
                }
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
                        // Flipped: the menus are on the second screen, and this one shows what they chose.
                        val menusBelow by companions.flipped.collectAsState()
                        if (menusBelow) {
                            ShowcaseApp(s.store, app.platformUi)
                        } else {
                            FuseApp(s.store, app.platformUi, router, s.phoneLink, safeMode = s.safeMode, onSettled = app::settled, startupIntro = true, keepPlace = true)
                        }
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
    }

    /** When Fuse last left the screen (uptime), to tell a Home press in Fuse from a return from a game. */
    private var stoppedAt = 0L

    /** Fuse lost focus to a game or app (on this screen or the other one) and was not paused since. */
    private var lostFocus = false
    private var pausedWhileAway = false

    /**
     * A game or app on the other screen leaves Fuse resumed but takes the focus (Android 10 and
     * later). When Fuse gets the focus back without having been paused, that game's play session
     * ends here, the way it does on resume after a game on this screen.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) {
            lostFocus = true
            pausedWhileAway = false
            return
        }
        if (lostFocus && !pausedWhileAway) (app.startup.value as? Startup.Ready)?.store?.library?.onResume()
        lostFocus = false
    }

    /**
     * A screensaver (Android's dream, which handhelds like the AYN Thor use to spare their OLED
     * screens) is showing. Controller input then belongs to waking it, never to Fuse underneath.
     */
    @Volatile private var dreaming = false

    private val dreamWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            dreaming = intent.action == Intent.ACTION_DREAMING_STARTED
            if (dreaming) gamepad.releaseAll()
        }
    }

    @SuppressLint("RestrictedApi") // Lint false positive: Activity.dispatchKeyEvent is public API.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dreaming && (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK))) return true
        // The second screen's own Back key (its navigation bar) reaches this window too; it is not Fuse's.
        val controller = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !controller && SecondScreenTouch.justTouched()) return true
        return gamepad.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (dreaming) true else gamepad.onMotion(event) || super.dispatchGenericMotionEvent(event)

    /** Controller input that reached the companion screen by mistake is handled as if it came here. */
    fun forwardKey(event: KeyEvent): Boolean = gamepad.onKey(event)

    fun forwardMotion(event: MotionEvent): Boolean = gamepad.onMotion(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive() else gamepad.releaseAll()
    }

    /**
     * Home pressed while Fuse is Home (handed over by [HomeActivity]). When Fuse was on screen it goes
     * to its Home page; when it comes back from a game it stays where the player left it. The
     * activity is never recreated.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isHome = intent.getBooleanExtra(EXTRA_HOME, false) ||
            (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME))
        // Still started, or stopped only a moment ago by the Home press itself.
        val wasVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            android.os.SystemClock.uptimeMillis() - stoppedAt < HOME_PRESS_MS
        if (isHome && wasVisible && app.startup.value is Startup.Ready) {
            router.dispatch(NavAction.HOME, router.lastSource.value)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dreamWatch) }
        folderSlot.cancel()
        imageSlot.cancel()
        audioSlot.cancel()
        roleSlot.cancel()
        permissionSlot.cancel()
        captureSlot.cancel()
        if (isFinishing) companions.stop() else companions.onMainDestroyed()
        if (companions.menus === ownMenus) companions.menus = null
        super.onDestroy()
    }

    // ActivityRequests

    override suspend fun pickFolder(): Uri? = folderSlot.request { folderLauncher.launch(null) }

    override suspend fun pickImage(): Uri? = imageSlot.request {
        imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override suspend fun pickAudio(): Uri? = audioSlot.request { audioLauncher.launch(arrayOf("audio/*")) }

    override suspend fun createDocument(name: String, mimeType: String): Uri? = createSlot.request {
        createType = mimeType
        createLauncher.launch(name)
    }

    override suspend fun openDocument(mimeTypes: Array<String>): Uri? = openSlot.request { openLauncher.launch(mimeTypes) }

    override suspend fun requestRole(intent: Intent): Boolean = roleSlot.request { roleLauncher.launch(intent) }

    override suspend fun requestPermission(permission: String): Boolean = permissionSlot.request { permissionLauncher.launch(permission) }

    override suspend fun requestScreenCapture(intent: Intent): ActivityResult? = captureSlot.request { captureLauncher.launch(intent) }

    companion object {
        /** Set by [HomeActivity]: this start is a press of Home. */
        const val EXTRA_HOME = "io.github.matiyaaa.fuse.HOME"

        /** Set by the launcher shortcut "Start in safe mode" (res/xml/shortcuts.xml). */
        const val EXTRA_SAFE_MODE = "io.github.matiyaaa.fuse.SAFE_MODE"

        /** How soon after leaving the screen a Home press still counts as made inside Fuse. */
        private const val HOME_PRESS_MS = 1_000L
    }
}
