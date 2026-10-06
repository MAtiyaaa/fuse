package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.background.CrtOverlay
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastHost
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.rememberHintFlash
import io.github.matiyaaa.fuse.ui.designsystem.effects.RevealScope
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.icons.remappedHints
import io.github.matiyaaa.fuse.ui.designsystem.input.InputFeedback
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.TextInput
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroBackdrop
import io.github.matiyaaa.fuse.ui.designsystem.sound.LocalUiSounds
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.GlyphConfig
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.TileMetrics
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Enter
import io.github.matiyaaa.fuse.ui.fuseline.Exit
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.LocalPageActive
import io.github.matiyaaa.fuse.ui.fuseline.SizeTransform
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.slideOutHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.apps.AppsScreen
import io.github.matiyaaa.fuse.ui.shell.capture.CaptureController
import io.github.matiyaaa.fuse.ui.shell.capture.CaptureOverlay
import io.github.matiyaaa.fuse.ui.shell.capture.rememberRecordingTime
import io.github.matiyaaa.fuse.ui.shell.cartridge.CartridgeScreen
import io.github.matiyaaa.fuse.ui.shell.components.FrameTimeOverlay
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileBorders
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.PerformanceOverlay
import io.github.matiyaaa.fuse.ui.shell.components.TileBorders
import io.github.matiyaaa.fuse.ui.shell.game.FolderBrowserScreen
import io.github.matiyaaa.fuse.ui.shell.game.GameScreen
import io.github.matiyaaa.fuse.ui.shell.home.HomeScreen
import io.github.matiyaaa.fuse.ui.shell.library.LibraryScope
import io.github.matiyaaa.fuse.ui.shell.library.LibraryScreen
import io.github.matiyaaa.fuse.ui.shell.media.MediaScreen
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.music.MenuMusicPlan
import io.github.matiyaaa.fuse.ui.shell.onboarding.OnboardingScreen
import io.github.matiyaaa.fuse.ui.shell.onboarding.SetupOpening
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.quick.QuickMenu
import io.github.matiyaaa.fuse.ui.shell.search.SearchScreen
import io.github.matiyaaa.fuse.ui.shell.settings.PlatformSettingsScreen
import io.github.matiyaaa.fuse.ui.shell.settings.SettingsScreen
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import io.github.matiyaaa.fuse.ui.shell.systems.SystemsScreen
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The whole Fuse interface for one window. [router] is created by the host (Android activity or
 * desktop window) because that is where raw input arrives.
 */
@Composable
fun FuseApp(
    store: FuseStore,
    platform: PlatformUi,
    router: InputRouter,
    phoneLink: PhoneLinkControl? = null,
    /** Start in safe mode (see [SafeMode]); null starts normally. */
    safeMode: SafeMode? = null,
    /** Called once this start has run long enough to count as settled ([StartupGuard.settle]). */
    onSettled: () -> Unit = {},
    /** Plays the startup animation on this start when it is on in Settings (the apps do; tests and renders don't). */
    startupIntro: Boolean = false,
    /** The menus are on the lower screen and [ShowcaseApp] shows the chosen game on the one above. */
    showcaseElsewhere: Boolean = false,
    /**
     * Keeps where the menus are ([KeptPlace]) while Fuse runs, so a window made again (the menus
     * moved to the other screen, the device turned) opens on the page it was on. The apps do.
     */
    keepPlace: Boolean = false,
) {
    CompositionLocalProvider(LocalShowcaseElsewhere provides showcaseElsewhere) {
        FuseAppContent(store, platform, router, phoneLink, safeMode, onSettled, startupIntro, keepPlace)
    }
}

@Composable
private fun FuseAppContent(
    store: FuseStore,
    platform: PlatformUi,
    router: InputRouter,
    phoneLink: PhoneLinkControl?,
    safeMode: SafeMode?,
    onSettled: () -> Unit,
    startupIntro: Boolean,
    keepPlace: Boolean,
) {
    val base = rememberCoroutineScope()
    val stored by store.prefs.collectAsState()
    val app = remember {
        lateinit var state: AppState
        // Everything screens start runs here. A failure shows a message; it never closes Fuse.
        val scope = CoroutineScope(
            base.coroutineContext + SupervisorJob(base.coroutineContext[Job]) + CoroutineExceptionHandler { _, t ->
                state.toasts.show("Something went wrong (${t::class.simpleName ?: "error"}). Fuse kept running.", ToastKind.ERROR)
            },
        )
        val start = if (stored.onboardingDone) Route.Root(Destination.HOME) else Route.Onboarding
        val kept = if (keepPlace) KeptPlaces.current ?: KeptPlace(start).also { KeptPlaces.current = it } else KeptPlace(start)
        state = AppState(store, platform, scope, start, phoneLink, kept)
        state.safeMode = safeMode
        state
    }
    // Safe mode draws with Fuse's own look and no effects; what is saved never changes.
    val prefs = if (app.safeMode != null) stored.inSafeMode() else stored
    app.menusOnSecondScreen = LocalShowcaseElsewhere.current
    PlayerScreens(app, prefs)
    LaunchedEffect(Unit) {
        // The startup animation, once per start of Fuse (a window made again doesn't replay it).
        // The very first start opens setup with its own, longer opening instead.
        if (startupIntro && !StartupIntro.played && app.safeMode == null && stored.startupAnimation) {
            if (stored.onboardingDone) app.intro = true else app.setupOpening = true
        }
        StartupIntro.played = true
        if (app.safeMode != null) app.showSafeMode()
        delay(StartupGuard.SETTLE_MS)
        onSettled()
    }
    // Back after a long while away (the device slept, the screen was off): the animation again, as
    // though Fuse had just been switched on. Only where this start would have played it.
    LaunchedEffect(Unit) {
        Away.returns.collect { away ->
            // Coming back is activity: Standby starts counting again, and never waits under the
            // animation (opening a lid used to show the animation, then Standby, then the animation).
            router.touched()
            app.standby = false
            val p = app.store.prefs.value
            if (startupIntro && away >= Away.AWAY_INTRO_MS && app.safeMode == null && p.startupAnimation && p.onboardingDone && app.launching == null) app.intro = true
        }
    }
    app.navigator.forgetsTabs = !prefs.rememberPlace
    val homeFeed by store.homeFeed.collectAsState()
    StandbyWatch(app, router, prefs.standbyMinutes) {
        app.intro || app.launching != null || homeFeed.playtime.currentGame != null || app.navigator.current == Route.Onboarding
    }
    DriveWatch(app) {
        app.intro || app.standby || app.launching != null || homeFeed.playtime.currentGame != null || app.navigator.current == Route.Onboarding
    }
    val spec = prefs.theme
    // Drawn without the graphics card, every moving frame is costly: lighter effects and calmer
    // motion (short fades, no sliding pages) keep it smooth instead of stuttering.
    val drawing by platform.drawing.collectAsState()
    val cpuDrawing = drawing?.gpu == false
    val quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower || cpuDrawing)
    val motionProfile = (prefs.motion ?: spec.motion).let { m ->
        if (cpuDrawing && m.ordinal > io.github.matiyaaa.fuse.model.MotionProfile.MINIMAL.ordinal) io.github.matiyaaa.fuse.model.MotionProfile.MINIMAL else prefs.motion
    }
    val lastSource by router.lastSource.collectAsState()
    val padFamily by router.padFamily.collectAsState()
    // A phone used as a controller is labelled like the controller in hand.
    LaunchedEffect(padFamily) { RemoteInput.padInUse(padFamily) }
    val glyphStyle = when {
        !prefs.input.autoGlyphs -> prefs.input.glyphs
        lastSource == InputSource.KEYBOARD -> GlyphStyle.KEYBOARD
        else -> padGlyphs(prefs.input.glyphs, padFamily)
    }

    // Input settings, sounds and haptics follow preferences.
    LaunchedEffect(prefs.input) { router.profile = prefs.input }
    // L3 + R3: a screenshot, or held, a recording (where Fuse can capture its screen).
    val capture = app.capture
    DisposableEffect(router, capture, prefs.captureCombo) {
        router.onCaptureCombo = if (capture != null && prefs.captureCombo) capture::onCombo else null
        onDispose { router.onCaptureCombo = null }
    }
    // Fuse Sync: who is playing here, and at startup, who should be.
    io.github.matiyaaa.fuse.ui.shell.sync.SyncProfiles(app)
    app.store.syncthing?.let { st ->
        val state by st.state.collectAsState()
        LaunchedEffect(state) { app.syncthingActive = state !is io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.Off }
    }
    // A hardware keyboard types into whichever text field is open.
    val keyboardTarget = app.keyboardTarget
    DisposableEffect(router, keyboardTarget) {
        router.textInput = keyboardTarget?.let { target ->
            object : TextInput {
                override fun type(text: String) = target.field.insert(text)
                override fun backspace() = target.field.backspace()
                override fun deleteWordBack() = target.field.deleteWordBack()
                override fun submit() = target.submit()
                override fun paste() = app.pasteInto(target.field)
                override fun deleteForward() = target.field.deleteForward()
                override fun home() = target.field.setCaret(0)
                override fun end() = target.field.setCaret(target.field.text.length)
            }
        }
        onDispose { router.textInput = null }
    }
    // Phone Link: a phone types into whichever field is open, and its text follows the field here.
    LaunchedEffect(keyboardTarget) {
        val target = keyboardTarget ?: return@LaunchedEffect
        val id = RemoteInput.opened(target.title, target.field.text, target.secret, target.placeholder, target.doneLabel, target.cancel != null)
        try {
            snapshotFlow { target.field.text }.collect { RemoteInput.changed(id, it) }
        } finally {
            RemoteInput.closed(id)
        }
    }
    // What phones ask for: text for the field open, and buttons pressed on a phone used as a controller.
    LaunchedEffect(router) {
        RemoteInput.commands.collect { c ->
            val target = app.keyboardTarget
            val current = RemoteInput.field.value?.id
            when (c) {
                is RemoteCommand.SetText -> if (target != null && c.id == current) target.field.replaceAll(c.text)
                is RemoteCommand.Submit -> if (target != null && c.id == current) target.submit()
                is RemoteCommand.Cancel -> if (target != null && c.id == current) target.cancel?.invoke()
                is RemoteCommand.Pad -> if (store.prefs.value.phoneLinkController) {
                    if (c.down) router.press(c.button, InputSource.REMOTE) else router.release(c.button, InputSource.REMOTE)
                }
            }
        }
    }
    // The companion screen (second display) follows what the main screen has in focus.
    LaunchedEffect(app.hero?.id) { Spotlight.set(app.hero?.id) }
    LaunchedEffect(prefs.sound, prefs.soundVolume) {
        platform.sounds.setProfile(prefs.sound)
        platform.sounds.setVolume(prefs.soundVolume)
    }
    // The hint line answers the hand: a pressed button's glyph flashes when it did something.
    val hintFlash = rememberHintFlash()
    MenuMusic(app, platform.music)
    FillFinishedToast(app)
    io.github.matiyaaa.fuse.ui.shell.cartridge.UploadFinishedToasts(app)
    DisposableEffect(router) {
        router.feedback = InputFeedback { event, result ->
            when (result) {
                NavResult.MOVED -> {
                    platform.sounds.play(
                        when (event.action) {
                            NavAction.UP -> SoundCue.MOVE_UP
                            NavAction.DOWN -> SoundCue.MOVE_DOWN
                            else -> SoundCue.MOVE
                        },
                    )
                    if (!event.isRepeat) platform.haptics.tick()
                    // The second screen slides the same way when what it shows changes.
                    when (event.action) {
                        NavAction.LEFT, NavAction.UP, NavAction.PAGE_UP -> Spotlight.moved(-1)
                        NavAction.RIGHT, NavAction.DOWN, NavAction.PAGE_DOWN -> Spotlight.moved(1)
                        else -> Unit
                    }
                }
                NavResult.ACTIVATED -> { platform.sounds.play(SoundCue.SELECT); platform.haptics.confirm() }
                NavResult.BLOCKED -> if (!event.isRepeat) { platform.sounds.play(SoundCue.BUMP); platform.haptics.reject() }
                NavResult.CONSUMED -> if (event.action == NavAction.BACK) platform.sounds.play(SoundCue.BACK)
                NavResult.IGNORED -> Unit
            }
            if (!event.isRepeat && (result == NavResult.ACTIVATED || result == NavResult.CONSUMED)) {
                when (event.action) {
                    NavAction.SELECT -> HintButton.CONFIRM
                    NavAction.BACK -> HintButton.BACK
                    NavAction.CONTEXT -> HintButton.OPTIONS
                    NavAction.SEARCH -> HintButton.SEARCH
                    else -> null
                }?.let(hintFlash::flash)
            }
        }
        onDispose { router.feedback = InputFeedback { _, _ -> } }
    }

    FuseTheme(
        spec = spec,
        motion = motionProfile,
        quality = quality,
        glyphs = GlyphConfig(glyphStyle, hintConfirmOnRight(prefs.input, glyphStyle, padFamily), prefs.input.swapShoulders, remappedHints(prefs.input)),
        glass = prefs.glass,
        highContrastFocus = prefs.highContrastFocus,
        animateChanges = true,
        textScale = prefs.textScale,
    ) {
        CompositionLocalProvider(LocalInputRouter provides router, io.github.matiyaaa.fuse.ui.designsystem.input.LocalPointerRouter provides router, LocalUiSounds provides platform.sounds) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(Fuse.colors.ink)
                    // A touch or the pointer counts as being here, for standby.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                router.touched()
                                // Whether it is a mouse, and whether it really moved, for hovering and clicking.
                                e.changes.firstOrNull()?.let { ch ->
                                    router.pointer(
                                        mouse = ch.type == androidx.compose.ui.input.pointer.PointerType.Mouse,
                                        x = ch.position.x, y = ch.position.y,
                                        pressed = e.type == androidx.compose.ui.input.pointer.PointerEventType.Press,
                                    )
                                }
                            }
                        }
                    }
                    // A swipe in from either side goes back, on computers' touch screens.
                    .edgeSwipeBack(
                        enabled = platform.host != io.github.matiyaaa.fuse.model.Host.ANDROID,
                        onTick = { platform.haptics.tick() },
                        onBack = { router.dispatch(NavAction.BACK, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.TOUCH) },
                    ),
            ) {
                // The room fills the whole screen; everything on it keeps clear of edges a TV cuts off.
                Room(app, prefs.showHero, spec.background, prefs.heroDim, prefs.glass, prefs.videoPreview, prefs.videoDelaySeconds, spec.ambient, spec.wallpaper)
                val margin = prefs.screenMargin.coerceIn(0, 10) / 100f
                // On an ultrawide screen (wider than about 21:9) the interface keeps a 21:9-like frame
                // in the middle, so the top line, the pages and the hints stay together; the room
                // still fills the whole screen around it.
                val ultrawide = ((maxWidth - maxHeight * MAX_ASPECT) / 2).coerceAtLeast(0.dp)
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = maxWidth * margin + ultrawide, vertical = maxHeight * margin)) {
                val metrics = remember(maxWidth, maxHeight) { TileMetrics.forHeight(maxHeight, maxWidth) }
                val borders = rememberTileBorders(store)
                CompositionLocalProvider(LocalTileMetrics provides metrics, LocalTileBorders provides borders, LocalGameArt provides prefs.gameArt) {
                    ArtWarmup(app)
                    ShellInput(app)
                    val tabs = rememberTabs(app, prefs)
                    // A tab that went away while open (Addons with everything in it turned off, a
                    // hidden section) leaves its pages for Home.
                    LaunchedEffect(tabs) {
                        val root = app.navigator.root?.destination
                        if (root != null && root !in tabs && app.navigator.stack.size == 1) app.selectTab(Destination.HOME)
                    }
                    Pages(app, tabs)
                    val route = app.navigator.current
                    if (route != Route.Onboarding) {
                        val status by platform.status.collectAsState()
                        // Settings or Search open is where you are, not the tab underneath them.
                        val page = hudPage(app.navigator.stack)
                        HudScrim(art = prefs.showHero && app.hero != null)
                        Hud(
                            destinations = tabs,
                            sections = app.sections,
                            active = if (page == null) app.navigator.root?.destination else null,
                            activeButton = page,
                            tabsFocused = app.focusZone == FocusZone.TABS,
                            focusedButton = app.hudButton,
                            onButton = { app.focusZone = FocusZone.CONTENT; app.hudButton = null; app.runHudButton(it) },
                            status = status,
                            clock24h = prefs.clock24h,
                            showWifi = prefs.showWifi,
                            showBluetooth = prefs.showBluetooth,
                            onSelect = { app.focusZone = FocusZone.CONTENT; app.selectTab(it) },
                            onStatusClick = { app.quickMenuOpen = true },
                            activities = hudActivities(app),
                            profile = hudProfile(app),
                            downloads = rememberHudDownloads(app),
                        )
                    }
                    if (prefs.performanceOverlay) {
                        val metrics by platform.performance.collectAsState()
                        // Under the status it extends, where it covers the least of any page.
                        PerformanceOverlay(metrics, Modifier.align(Alignment.TopEnd).padding(end = Space.gutter, top = Size.hudHeight + Space.xs))
                    }
                    if (app.dev.frameGraph) {
                        FrameTimeOverlay(Modifier.align(Alignment.TopStart).padding(start = Space.gutter, top = Size.hudHeight + Space.xs))
                    }
                    app.gallery?.let { g -> io.github.matiyaaa.fuse.ui.shell.game.PictureViewer(g.pictures, g.start, g.onIndex, g.onClose) }
                    // Content fades out under the hint line, so hints never sit on top of tiles.
                    if (app.hints.isNotEmpty()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(Size.hintHeight + Space.xxl)
                                .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Fuse.colors.ink.copy(alpha = 0.78f), 1f to Fuse.colors.ink.copy(alpha = 0.94f))),
                        )
                    }
                    HintBar(app.hints, Modifier.align(Alignment.BottomEnd).padding(horizontal = Space.gutter, vertical = Space.s), flash = hintFlash)
                    QuickMenu(app)
                    OverlayHost(app)
                    ToastHost(app.toasts)
                    app.capture?.let { CaptureOverlay(it) }
                    LaunchVeilView(app)
                    if (app.standby) {
                        StandbyHost(app, prefs.clock24h, prefs.startupAnimation && startupIntro)
                    }
                    // The other screen stays dark while an opening plays here.
                    val opening = app.intro || app.setupOpening
                    androidx.compose.runtime.DisposableEffect(opening) {
                        if (opening) OpeningVeil.showing.value = true
                        onDispose { if (opening) OpeningVeil.showing.value = false }
                    }
                    if (app.intro) StartupIntroOverlay(onDone = { app.intro = false; StartupIntro.lastPlayedAt = kotlin.time.Clock.System.now().toEpochMilliseconds() })
                    if (app.setupOpening) SetupOpening(onDone = { app.setupOpening = false })
                }
                }
                // Fuse Player takes the whole screen, outside the margins and the ultrawide frame.
                io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaPlayerHost(app)
                if (prefs.crt.enabled && quality.crtShader) CrtOverlay(prefs.crt)
            }
        }
    }

    // Leaving Fuse (a game started) and coming back.
    LaunchedEffect(Unit) { store.library.onResume() }
    // The Store's news (an app installed, updated or removed), wherever the user is.
    LaunchedEffect(Unit) { store.appStore.notices.collect { app.toasts.show(it) } }
    LaunchedEffect(Unit) { store.cartridge.notices.collect { app.toasts.show(it) } }
    LaunchedEffect(Unit) { store.romm.notices.collect { app.toasts.show(it, durationMs = 5200) } }
    LaunchedEffect(Unit) { (store.offlineMedia as? io.github.matiyaaa.fuse.ui.shell.store.impl.DefaultOfflineMedia)?.notices?.collect { app.toasts.show(it) } }
}

/** The background: theme renderer, then the selected item's art with video after it rests. */
@Composable
private fun Room(
    app: AppState,
    showHero: Boolean,
    style: BackgroundStyle,
    dim: Float,
    glass: io.github.matiyaaa.fuse.model.GlassSettings,
    videoOn: Boolean,
    videoDelay: Int,
    ambient: io.github.matiyaaa.fuse.model.AmbientSpec,
    wallpaper: io.github.matiyaaa.fuse.model.Wallpaper? = null,
) {
    val quality = Fuse.quality
    // While the selection runs (a held direction, a quick run of presses), the room waits for it to
    // rest before it decodes and fades in the next art: every step would otherwise start a crossfade.
    val hero by produceState(app.hero) {
        snapshotFlow { app.hero }.collectLatest { next ->
            if (next?.id != value?.id) delay(HERO_SETTLE_MS)
            value = next
        }
    }
    // The theme's own room is always underneath, so art fading in or out never shows a bare screen.
    // Fusi's room is the main screen's half of the room she shares with a screen below.
    androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.designsystem.background.LocalFusiScreen provides io.github.matiyaaa.fuse.ui.designsystem.background.FusiScreen.TOP) {
        AmbientBackground(if (style == BackgroundStyle.HERO) BackgroundStyle.SOLID else style, hero?.accent ?: Fuse.colors.accent, Modifier.fillMaxSize(), ambient = ambient)
    }
    // The theme's own picture, when it has one, over the drawn room and under any game's art.
    wallpaper?.let { WallpaperLayer(it, Modifier.fillMaxSize()) }
    if (showHero) {
        var videoReady by remember(hero?.id) { mutableStateOf(false) }
        var playVideo by remember(hero?.id) { mutableStateOf(false) }
        val player = app.platform.video
        LaunchedEffect(hero?.id, videoOn, quality.backgroundVideo) {
            playVideo = false
            if (hero?.video == null || !videoOn || !quality.backgroundVideo || player == null) return@LaunchedEffect
            delay(videoDelay * 1000L)
            playVideo = true
        }
        // Glass panels frost the art behind them, when the performance profile allows blur.
        val blur = if (glass.enabled && quality.blur) maxOf(glass.heroBlur, glass.blur * 0.5f) else 0f
        HeroBackdrop(
            source = hero,
            // The selection has already rested (above); the room changes at once.
            settleMs = 0,
            modifier = Modifier.fillMaxSize()
                .then(if (blur > 0f) Modifier.blur(blur.dp) else Modifier)
                .graphicsLayer { alpha = if (glass.enabled) glass.backgroundOpacity else 1f },
            dim = if (glass.enabled) glass.overlayDarkness * 0.6f else dim,
            gradient = if (glass.enabled) glass.gradientStrength else 0.9f,
            brightness = if (glass.enabled) glass.heroBrightness else 1f,
            overlay = {
                val src = hero?.video
                if (player != null && src != null && playVideo) {
                    Appear(videoReady, enter = fadeIn(Fuse.motion.fade(Durations.DELIBERATE)), exit = fadeOut(Fuse.motion.fade(Durations.FAST))) {
                        Box(Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (videoReady) 1f else 0f }) {
                        player.Player(src, playing = app.launching == null && !app.overlayOpen, modifier = Modifier.fillMaxSize(), onFirstFrame = { videoReady = true })
                    }
                }
            },
        )
    }
    // Fusi plays in front of any art, so she is always in view.
    if (style == BackgroundStyle.FUSI) io.github.matiyaaa.fuse.ui.designsystem.background.FusiPet(io.github.matiyaaa.fuse.ui.designsystem.background.FusiScreen.TOP)
}

/**
 * Page content, moving the way you went, like rooms side by side: the next tab along slides in from
 * its side, a page you open comes in from the right and Back brings the last one in from the left.
 * The page you leave fades at once while drifting half as far the other way, and the new one fades
 * in a beat later as it settles, so two pages never sit on top of each other. Under Reduced motion
 * it is a short crossfade, without travel.
 *
 * Every entry into a page gets a fresh [RevealScope], so a page's own `Modifier.reveal` items rise
 * in as it arrives, once. Input belongs to the new page from its first frame; nothing here holds it.
 */
@Composable
private fun Pages(app: AppState, tabs: List<Destination>) {
    val motion = Fuse.motion
    val nav = app.navigator
    val current = nav.current
    // Tabs follow the order the top line shows them in, which the user may have changed.
    fun place(d: Destination): Int = tabs.indexOf(d).takeIf { it >= 0 } ?: (tabs.size + d.ordinal)
    Box(Modifier.fillMaxSize()) {
        RootPages(app, current, nav.direction, ::place, tabs)
        PushedPages(app, current, nav.direction, motion)
    }
}

/**
 * The tabs' own pages, kept ready once visited (the [KEPT_PAGES] most recent), so going back to one
 * is instant: it is shown again as it was left, with nothing to build. The tabs beside Home are
 * built and laid out ahead, while Home sits idle, so even the first visit to Systems or the Library
 * builds nothing. Only the page shown is drawn; the others hear no input, run no loops and keep
 * their effects waiting ([LocalPageActive], [PageEffect]) until shown again.
 *
 * A tab changes in the very frame it is chosen: the page is simply there, with a short nudge from
 * the side it came from (a moving layer, nothing faded or drawn twice).
 */
@Composable
private fun RootPages(app: AppState, current: Route, direction: NavDirection, place: (Destination) -> Int, tabs: List<Destination>) {
    val motion = Fuse.motion
    // Drawn without the graphics card: tabs change without the nudge.
    val cpuDrawing = app.platform.drawing.collectAsState().value?.gpu == false
    val root = (current as? Route.Root)?.destination
    // Most recent last. Updated as composition runs: a new tab joins in the same frame it is chosen.
    val kept = remember { ArrayList<Destination>() }
    if (root != null && kept.lastOrNull() != root) {
        kept.remove(root)
        kept.add(root)
        while (kept.size > KEPT_PAGES) kept.removeAt(0)
    }
    val shownRoot = kept.lastOrNull() ?: return
    val onRoot = current is Route.Root
    // The page coming in as a share of its nudge, and whether tab pages show at all (a pushed page covers them).
    val incoming = remember { FuselineValue(1f) }
    val visible = remember { FuselineValue(if (onRoot) 1f else 0f) }
    val dir = remember { intArrayOf(1) }
    val last = remember { arrayOf<Destination?>(shownRoot) }
    var nudging by remember { mutableStateOf(false) }
    if (last[0] != shownRoot) {
        val before = last[0]
        last[0] = shownRoot
        nudging = before != null && !motion.reduced && !cpuDrawing
        if (before != null) dir[0] = if (place(shownRoot) >= place(before)) 1 else -1
    }
    // Remember where you were, off: a tab left behind is let go, so it opens at its start next time.
    val forgets = app.navigator.forgetsTabs
    if (forgets) kept.retainAll { it == shownRoot }
    // Systems and the Library, built ahead once Home has had its first moments to itself.
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(WARM_AFTER_MS)
        warm = true
    }
    val ahead = if (warm && !forgets) WARM_PAGES.filter { it != shownRoot && it !in kept && it in tabs } else emptyList()
    val pages = ahead + kept
    LaunchedEffect(shownRoot) {
        if (!nudging) {
            incoming.snapTo(1f)
            return@LaunchedEffect
        }
        incoming.snapTo(0f)
        incoming.animateTo(1f, motion.tween(Durations.FAST, Curves.Enter))
        nudging = false
    }
    LaunchedEffect(onRoot) {
        dir[0] = if (direction == NavDirection.BACK) -1 else 1
        if (motion.reduced || cpuDrawing) visible.snapTo(if (onRoot) 1f else 0f)
        else visible.animateTo(if (onRoot) 1f else 0f, motion.tween(if (onRoot) Durations.BASE else Durations.FAST, if (onRoot) Curves.Enter else Curves.Standard))
    }
    val shift = motion.slideFraction
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            for (d in pages) key(d) {
                val active = d == shownRoot && onRoot
                CompositionLocalProvider(LocalPageActive provides active) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (d != shownRoot) return@graphicsLayer
                            val w = size.width
                            val v = visible.value
                            // A pushed page leaving fades this one back in; a new tab is there at
                            // once, nudged in from its side.
                            alpha = v
                            val nudge = if (nudging) (1f - incoming.value) * TAB_NUDGE.toPx() * dir[0] else 0f
                            translationX = nudge + (1f - v) * -shift * 0.5f * w * dir[0]
                        },
                    ) {
                        RevealScope(Route.Root(d)) {
                            Box(Modifier.fillMaxSize().testTag("page.${d.name}")) { RootPage(app, d) }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // Every page is measured (a page already laid out costs nothing to measure again, and one
        // built ahead is then ready to show), but only the one in front is placed, so only it is drawn.
        val inFront = visible.value > 0f
        val measured = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (inFront) measured.getOrNull(pages.indexOf(shownRoot))?.place(0, 0)
        }
    }
}

@Composable
private fun RootPage(app: AppState, d: Destination) {
    when (d) {
        Destination.HOME -> HomeScreen(app)
        Destination.LIBRARY -> LibraryScreen(app, LibraryScope.All)
        Destination.SYSTEMS -> SystemsScreen(app)
        Destination.ACHIEVEMENTS -> io.github.matiyaaa.fuse.ui.shell.achievements.AchievementsScreen(app)
        Destination.APPS -> AppsScreen(app)
        Destination.CARTRIDGE -> if (app.sections.addons) io.github.matiyaaa.fuse.ui.shell.addons.AddonsScreen(app) else CartridgeScreen(app)
    }
}

/** Pages opened on top of a tab (a game, Settings, Search...), sliding in and out over it. */
@Composable
private fun PushedPages(app: AppState, current: Route, direction: NavDirection, motion: io.github.matiyaaa.fuse.ui.fuseline.FuselineMotion) {
    val target: Route? = current.takeIf { it !is Route.Root }
    // Drawn without the graphics card: pages change at once, as a sliding page would stutter.
    val instant = app.platform.drawing.collectAsState().value?.gpu == false
    Swap(
        targetState = target,
        transitionSpec = {
            if (instant) return@Swap (Enter.None togetherWith Exit.None).using(SizeTransform(clip = false))
            val dir = if (direction == NavDirection.BACK) -1 else 1
            val shift = motion.slideFraction
            val enter = fadeIn(tween(motion.ms(Durations.BASE), delayMillis = motion.ms(Durations.INSTANT) / 2, easing = Curves.Fade)) +
                slideInHorizontally(motion.tween(Durations.BASE, Curves.Enter)) { (it * shift * dir).toInt() }
            val exit = fadeOut(motion.tween(Durations.FAST, Curves.Standard)) +
                slideOutHorizontally(motion.tween(Durations.FAST, Curves.Standard)) { (-it * shift * 0.5f * dir).toInt() }
            when {
                // Back to a tab: the page leaves, and the tab's page comes in under it (RootPages).
                targetState == null -> (Enter.None togetherWith exit).using(SizeTransform(clip = false))
                // Opened from a tab: only this page moves here.
                initialState == null -> (enter togetherWith Exit.None).using(SizeTransform(clip = false))
                else -> (enter togetherWith exit).using(SizeTransform(clip = false))
            }
        },
        contentKey = { it },
        label = "pages",
    ) { route ->
        if (route == null) return@Swap
        RevealScope(route) {
            Box(Modifier.fillMaxSize()) {
                when (route) {
            is Route.PlatformGames -> LibraryScreen(app, LibraryScope.OfPlatform(route.platform))
            is Route.CollectionGames -> LibraryScreen(app, LibraryScope.OfCollection(route.collection, route.name))
            Route.Collections -> io.github.matiyaaa.fuse.ui.shell.collections.CollectionsScreen(app)
            Route.Storage -> io.github.matiyaaa.fuse.ui.shell.settings.StorageScreen(app)
            Route.PhoneLink -> io.github.matiyaaa.fuse.ui.shell.settings.PhoneLinkScreen(app)
            is Route.GameInfo -> GameScreen(app, route.game)
            is Route.Media -> MediaScreen(app, route.owner, route.title, route.identify)
            is Route.Settings -> SettingsScreen(app, route.section, route.row)
            is Route.PlatformSettings -> PlatformSettingsScreen(app, route.platform)
            Route.Search -> SearchScreen(app)
            Route.Controls -> io.github.matiyaaa.fuse.ui.shell.settings.ControlsScreen(app)
            Route.Licenses -> io.github.matiyaaa.fuse.ui.shell.settings.LicensesScreen(app)
            is Route.ReleaseNotes -> io.github.matiyaaa.fuse.ui.shell.notes.ReleaseNotesScreen(app, route)
            Route.PlayTime -> io.github.matiyaaa.fuse.ui.shell.library.PlayTimeScreen(app)
            Route.Themes -> io.github.matiyaaa.fuse.ui.shell.settings.ThemesScreen(app)
            Route.Onboarding -> OnboardingScreen(app)
            is Route.FolderBrowser -> FolderBrowserScreen(app, route.game)
            is Route.GameContent -> io.github.matiyaaa.fuse.ui.shell.game.GameContentScreen(app, route.game)
            is Route.PickFile -> io.github.matiyaaa.fuse.ui.shell.files.FilePickerScreen(app, route.purpose, route.locate, route.licence)
            is Route.StoreApp -> io.github.matiyaaa.fuse.ui.shell.addons.StoreAppScreen(app, route.key)
            is Route.MediaPage -> io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaItemScreen(app, route.id)
            is Route.MediaLibrary -> io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaLibraryScreen(app, route.id, route.name, route.kind)
            Route.MediaSearch -> io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaSearchScreen(app)
            Route.JellyfinSettings -> io.github.matiyaaa.fuse.ui.shell.jellyfin.JellyfinSettingsScreen(app)
            Route.SyncSettings -> io.github.matiyaaa.fuse.ui.shell.sync.SyncSettingsScreen(app)
            Route.SyncthingSettings -> io.github.matiyaaa.fuse.ui.shell.sync.SyncthingScreen(app)
            Route.SaveFolders -> io.github.matiyaaa.fuse.ui.shell.sync.SaveFoldersScreen(app)
            is Route.SyncSetup -> io.github.matiyaaa.fuse.ui.shell.sync.SyncSetupScreen(app, route.host)
            is Route.SaveHistory -> io.github.matiyaaa.fuse.ui.shell.sync.SaveHistoryScreen(app, route.game, route.title)
            is Route.SyncGame -> io.github.matiyaaa.fuse.ui.shell.sync.SyncGameScreen(app, route.game, route.name)
            Route.Downloads -> io.github.matiyaaa.fuse.ui.shell.downloads.DownloadsScreen(app)
            Route.OfflineMedia -> io.github.matiyaaa.fuse.ui.shell.jellyfin.OfflineMediaScreen(app)
            Route.RommSettings -> io.github.matiyaaa.fuse.ui.shell.romm.RommSettingsScreen(app)
            is Route.RommSetup -> io.github.matiyaaa.fuse.ui.shell.romm.RommSetupScreen(app, route.pairing)
            is Route.RommGames -> io.github.matiyaaa.fuse.ui.shell.romm.RommGamesScreen(app, route.slug, route.name, route.collection)
                    is Route.Root -> Unit
                }
            }
        }
    }
}

/**
 * The top line's own page that is open (Settings or Search, or a page opened from it), or null when
 * a tab's pages are showing. The line shows it as the active place instead of the tab underneath.
 */
internal fun hudPage(stack: List<Route>): HudButton? {
    for (route in stack.asReversed()) {
        when (route) {
            Route.Downloads -> return HudButton.DOWNLOADS
            Route.Search -> return HudButton.SEARCH
            is Route.Settings, is Route.PlatformSettings, Route.Controls, Route.Licenses, is Route.ReleaseNotes, Route.Themes, Route.Storage, Route.PhoneLink, Route.JellyfinSettings, Route.SyncSettings, Route.SyncthingSettings, Route.SaveFolders, is Route.SyncSetup, Route.RommSettings, is Route.RommSetup ->
                return HudButton.SETTINGS
            else -> Unit
        }
    }
    return null
}

/**
 * Actions no page handled: section switching, Back, Home, Search and the quick menu, plus moving
 * focus up into the section tabs and back down.
 */
@Composable
private fun ShellInput(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val tabs = rememberTabs(app, prefs)
    val onboarding = app.navigator.current == Route.Onboarding
    val downloads = rememberHudDownloads(app) != null
    InputLayer(priority = LayerPriority.SHELL) { e ->
        if (onboarding) return@InputLayer NavResult.IGNORED
        val active = app.navigator.root?.destination
        fun cycle(delta: Int): NavResult {
            val i = tabs.indexOf(active).coerceAtLeast(0)
            val next = tabs.getOrNull(i + delta) ?: return NavResult.BLOCKED
            app.selectTab(next)
            return NavResult.MOVED
        }
        if (app.focusZone == FocusZone.TABS) {
            val button = app.hudButton
            fun leave(): NavResult { app.focusZone = FocusZone.CONTENT; app.hudButton = null; return NavResult.MOVED }
            return@InputLayer when (e.action) {
                // After the last tab the stick moves on to Search and Settings.
                NavAction.LEFT -> when (button) {
                    HudButton.PROFILE -> { app.hudButton = HudButton.STATUS; NavResult.MOVED }
                    HudButton.STATUS -> { app.hudButton = HudButton.SETTINGS; NavResult.MOVED }
                    HudButton.SETTINGS -> { app.hudButton = HudButton.SEARCH; NavResult.MOVED }
                    // Downloads sits between the tabs and Search while anything moves.
                    HudButton.SEARCH -> if (downloads) { app.hudButton = HudButton.DOWNLOADS; NavResult.MOVED } else {
                        app.hudButton = null
                        val last = tabs.lastOrNull()
                        if (hudPage(app.navigator.stack) != null || last != active) last?.let { app.selectTab(it) }
                        NavResult.MOVED
                    }
                    // Left of Search (or Downloads) is the last tab, as the line shows it, whichever
                    // tab Search or Settings was opened from.
                    HudButton.DOWNLOADS -> {
                        app.hudButton = null
                        val last = tabs.lastOrNull()
                        if (hudPage(app.navigator.stack) != null || last != active) last?.let { app.selectTab(it) }
                        NavResult.MOVED
                    }
                    null -> cycle(-1)
                }
                NavAction.RIGHT -> when (button) {
                    HudButton.DOWNLOADS -> { app.hudButton = HudButton.SEARCH; NavResult.MOVED }
                    HudButton.SEARCH -> { app.hudButton = HudButton.SETTINGS; NavResult.MOVED }
                    // Past Settings: Wi-Fi, battery and the clock, which open the quick menu, then
                    // at the far end who is playing.
                    HudButton.SETTINGS -> { app.hudButton = HudButton.STATUS; NavResult.MOVED }
                    HudButton.STATUS -> if (app.hudHasProfile) { app.hudButton = HudButton.PROFILE; NavResult.MOVED } else NavResult.BLOCKED
                    HudButton.PROFILE -> NavResult.BLOCKED
                    null -> if (tabs.lastOrNull() == active) { app.hudButton = if (downloads) HudButton.DOWNLOADS else HudButton.SEARCH; NavResult.MOVED } else cycle(1)
                }
                NavAction.SELECT -> if (button != null) { leave(); app.runHudButton(button); NavResult.ACTIVATED } else leave()
                NavAction.DOWN -> leave()
                NavAction.BACK -> { leave(); NavResult.CONSUMED }
                NavAction.UP -> NavResult.BLOCKED
                NavAction.PREVIOUS_SECTION -> cycle(-1)
                NavAction.NEXT_SECTION -> cycle(1)
                NavAction.QUICK_MENU -> { app.quickMenuOpen = true; NavResult.ACTIVATED }
                NavAction.SEARCH -> { app.go(Route.Search); NavResult.ACTIVATED }
                else -> NavResult.BLOCKED
            }
        }
        // Search and Settings sit after the last tab in the top line, so the shoulder buttons step
        // from them as they look: left to the last tab, and from Search right on to Settings.
        val page = hudPage(app.navigator.stack)
        when (e.action) {
            // Up past the top of any page reaches the top line, a pushed page (a system, Search)
            // included: on Search or Settings their own button is the one chosen.
            NavAction.UP -> {
                app.focusZone = FocusZone.TABS
                app.hudButton = page
                NavResult.MOVED
            }
            NavAction.PREVIOUS_SECTION -> when (page) {
                null -> cycle(-1)
                else -> tabs.lastOrNull()?.let { app.selectTab(it); NavResult.MOVED } ?: NavResult.BLOCKED
            }
            NavAction.NEXT_SECTION -> when (page) {
                null -> cycle(1)
                HudButton.DOWNLOADS -> { app.go(Route.Search); NavResult.MOVED }
                HudButton.SEARCH -> { app.go(Route.Settings()); NavResult.MOVED }
                HudButton.SETTINGS, HudButton.PROFILE, HudButton.STATUS -> NavResult.BLOCKED
            }
            NavAction.QUICK_MENU -> { app.quickMenuOpen = true; NavResult.ACTIVATED }
            NavAction.SEARCH -> { app.go(Route.Search); NavResult.ACTIVATED }
            NavAction.HOME -> { app.focusZone = FocusZone.CONTENT; app.selectTab(Destination.HOME); NavResult.ACTIVATED }
            NavAction.BACK -> when {
                app.back() -> NavResult.CONSUMED
                active != Destination.HOME -> { app.selectTab(Destination.HOME); NavResult.CONSUMED }
                else -> NavResult.BLOCKED
            }
            NavAction.LEFT, NavAction.RIGHT, NavAction.DOWN -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }
}

private fun AppState.runHudButton(button: HudButton) = when (button) {
    // Opening Downloads keeps the page underneath: Back returns to it as it was.
    HudButton.DOWNLOADS -> if (navigator.current != Route.Downloads) go(Route.Downloads) else Unit
    HudButton.SEARCH -> go(Route.Search)
    HudButton.SETTINGS -> go(Route.Settings())
    HudButton.PROFILE -> whoAreYou = io.github.matiyaaa.fuse.ui.shell.sync.WhoMode.SWITCH
    HudButton.STATUS -> quickMenuOpen = true
}

/**
 * Whether the top line shows who is playing: Fuse Sync in use, a profile chosen, and someone else
 * to switch to (with one profile there is no one to pick, so the line keeps its room).
 */
private val AppState.hudHasProfile: Boolean get() = syncProfile != null && syncProfileCount > 1

/** Who is playing here, for the top line. */
@Composable
private fun hudProfile(app: AppState): HudProfile? = app.syncProfile?.takeIf { app.hudHasProfile }?.let { HudProfile(it.name, it.avatar) }

/** A short, calm handoff while the emulator starts: the game's art fills the screen and dims away. */
@Composable
private fun LaunchVeilView(app: AppState) {
    val veil = app.launching
    Appear(
        visible = veil != null,
        enter = fadeIn(Fuse.motion.fade(Durations.BASE)),
        exit = fadeOut(Fuse.motion.fade(Durations.SLOW)),
    ) {
        var shown by remember { mutableStateOf(veil) }
        if (veil != null) shown = veil
        val v = shown ?: return@Appear
        LaunchVeilContent(v)
    }
}

/** Dynamic border style of every platform with games (Global -> Platform), for the tiles. */
@Composable
private fun rememberTileBorders(store: FuseStore): TileBorders {
    val platforms by store.library.platforms.collectAsState()
    val ids = platforms.map { it.platform.id }
    val flow = remember(ids) {
        if (ids.isEmpty()) {
            flowOf(TileBorders())
        } else {
            combine(ids.map { id -> store.settings.observe(ScopedSettings.Border, id, null).map { id to it.value } }) { TileBorders(it.toMap()) }
        }
    }
    return flow.collectAsState(TileBorders()).value
}

/**
 * The menu music follows its settings and steps aside while a game starts or runs, and while
 * Fuse Player plays. First-time setup
 * plays its own song and crossfades into the menu song when it finishes.
 *
 * Everything is handed to the player as one [MusicState] ([MenuMusicPlan]), and handed again whenever
 * any part of it changes, so the player can always repair itself from the latest complete picture.
 */
@Composable
private fun MenuMusic(app: AppState, player: MenuMusicPlayer?) {
    player ?: return
    val prefs by app.store.prefs.collectAsState()
    val home by app.store.homeFeed.collectAsState()
    val music = prefs.music
    // Shuffle: the song it picked and the ones it played lately, so none comes back too soon. A song
    // that ends picks the next; the player reports it from its own thread.
    var shuffled by remember { mutableStateOf<String?>(null) }
    val recent = remember { ArrayDeque<String>() }
    fun shuffleOn() {
        val next = MenuMusicPlan.nextShuffled(shuffled, recent.toList())
        recent.addLast(next)
        while (recent.size > BundledMusic.tracks.size / 2) recent.removeFirst()
        shuffled = next
    }
    LaunchedEffect(music.shuffle) { if (music.shuffle && shuffled == null) shuffleOn() }
    val ended = remember { kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    DisposableEffect(player) {
        player.onSongEnded { ended.trySend(it) }
        onDispose { player.onSongEnded(null) }
    }
    LaunchedEffect(Unit) { for (path in ended) if (app.store.prefs.value.music.shuffle) shuffleOn() }
    val track = MenuMusicPlan.track(music, safeMode = app.safeMode != null, onboarding = app.navigator.current == Route.Onboarding, shuffled = shuffled)
    // The quick menu's Now playing: a skip moves shuffle on (or back through what it played), and
    // without shuffle steps through the songs in album order, keeping the one it lands on.
    val remote = io.github.matiyaaa.fuse.ui.shell.music.MenuMusicRemote
    LaunchedEffect(track) { remote.current = track }
    LaunchedEffect(Unit) {
        for (dir in remote.skips) {
            val now = app.store.prefs.value.music
            if (!now.enabled) continue
            if (now.shuffle) {
                val list = recent.toList()
                val back = list.getOrNull(list.indexOf(shuffled) - 1)
                if (dir < 0 && back != null) shuffled = back else shuffleOn()
            } else {
                val next = io.github.matiyaaa.fuse.ui.shell.music.MenuMusicRemote.neighbour(remote.current, dir)
                app.store.updatePrefs { p -> p.copy(music = p.music.copy(track = next)) }
            }
        }
    }
    // The startup animation has its own sound; the music waits until it has opened out.
    // Fuse Player playing a film or a song (on either screen) has the sound to itself.
    val quiet = app.launching != null || home.playtime.currentGame != null || app.intro || app.standby ||
        app.playerOpen || mediaPlaying() || remote.paused
    // The previous song keeps playing until the next one is ready, so the player can crossfade. The
    // file is looked up again whenever music comes back from a game: a bundled song's unpacked copy
    // lives in the cache, which the system may have cleared meanwhile.
    var song by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track, music.songPath, quiet) {
        if (quiet) return@LaunchedEffect
        song = when (track) {
            null -> null
            BundledMusic.OWN_SONG -> music.songPath
            else -> app.store.bundledTrack(track)
        }
    }
    val state = MenuMusicPlan.state(song, music, quiet)
    LaunchedEffect(state) { player.apply(state) }
}

/** Fuse Player has something playing, on either screen. */
internal fun mediaPlaying(): Boolean =
    io.github.matiyaaa.fuse.ui.player.FusePlayer.available && io.github.matiyaaa.fuse.ui.player.FusePlayer.session.item != null

/**
 * Keeps track of where Fuse Player's picture can go: another screen is there when the menus are on
 * the second screen (the showcase is above) or a companion shows on it. Without one, the picture
 * stays with the menus; and when the picture comes back to the menus' screen, the player opens.
 */
@Composable
private fun PlayerScreens(app: AppState, prefs: io.github.matiyaaa.fuse.ui.shell.store.UiPrefs) {
    if (!io.github.matiyaaa.fuse.ui.player.FusePlayer.available) return
    val d = prefs.display
    val companion = app.platform.features.secondScreen && !d.secondScreenHidden &&
        (d.mode == io.github.matiyaaa.fuse.model.DualScreenMode.LIBRARY_COMPANION || d.mode == io.github.matiyaaa.fuse.model.DualScreenMode.GAME_COMPANION)
    val other = app.menusOnSecondScreen || companion
    val placement = io.github.matiyaaa.fuse.ui.player.PlayerPlacement
    LaunchedEffect(other) {
        placement.canSwap = other
        if (!other) placement.withMenus = true
    }
    LaunchedEffect(app) {
        androidx.compose.runtime.snapshotFlow { placement.withMenus && io.github.matiyaaa.fuse.ui.player.FusePlayer.session.item != null }
            .collect { if (it) app.playerOpen = true }
    }
}

/** Says once when a fill that ran for more than one game finishes, unless its Settings page is open. */
@Composable
private fun FillFinishedToast(app: AppState) {
    val fill by app.store.media.fillProgress.collectAsState()
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(fill) {
        val f = fill ?: return@LaunchedEffect
        if (!f.finished) {
            running = f.total > 1
            return@LaunchedEffect
        }
        if (!running) return@LaunchedEffect
        running = false
        if (f.cancelled || (app.navigator.current as? Route.Settings)?.section == "media") return@LaunchedEffect
        // Fuse's own fills speak up only when they found something.
        if (f.automatic && f.added == 0 && f.details == 0) return@LaunchedEffect
        val needs = f.needsYou.size.takeIf { it > 0 }?.let { " $it need you in Settings, Art and details." } ?: ""
        val lead = if (f.automatic) "Found art for your games" else "Fill finished"
        app.toasts.show("$lead. ${io.github.matiyaaa.fuse.ui.shell.settings.fillSummary(f)}.$needs")
    }
}

/** What is working in the background, for the top line: recordings, art fills and updates (transfers are Downloads'). */
@Composable
private fun hudActivities(app: AppState): List<HudActivity> {
    val health = io.github.matiyaaa.fuse.ui.shell.settings.rememberHealthIssues(app)
    val update by app.store.updates.state.collectAsState()
    val available by app.store.updates.available.collectAsState()
    val fill by app.store.media.fillProgress.collectAsState()
    val recordingTime = rememberRecordingTime(app.capture)
    val playing = if (io.github.matiyaaa.fuse.ui.player.FusePlayer.available) io.github.matiyaaa.fuse.ui.player.FusePlayer.session.item else null
    return buildList {
        // A film playing on the other screen while these menus browse: a press brings its remote back.
        if (playing != null && !app.playerOpen) {
            add(HudActivity("playing", FuseIcons.MonitorPlay, "Playing ${playing.title}. Select for its controls", steady = true) { app.playerOpen = true })
        }
        // Safe mode stays in view, calmly, with its way out a press away.
        if (app.safeMode != null) add(HudActivity("safe", FuseIcons.LifeBuoy, "Safe mode. Select for what it means and how to leave it", steady = true) { app.showSafeMode() })
        // Only something that keeps Fuse from working claims a place in the top line.
        val broken = health.firstOrNull { it.problem.severity == io.github.matiyaaa.fuse.ui.shell.store.Severity.BROKEN }
        if (broken != null) add(HudActivity("health", FuseIcons.BadgeAlert, "${broken.problem.title}. Select to fix it", attention = true) { app.showProblem(broken.problem) })
        // A recording runs: the ring fills toward its 30 minute limit, and a press stops it.
        if (recordingTime != null) {
            val since = (app.capture?.state as? CaptureController.State.Recording)?.since
            val elapsed = since?.let { kotlin.time.Clock.System.now().toEpochMilliseconds() - it } ?: 0L
            add(HudActivity(
                "record", FuseIcons.CircleDot, "Recording $recordingTime. Select to stop",
                progress = (elapsed.toFloat() / CaptureController.MAX_RECORDING_MS).coerceIn(0f, 1f),
                attention = true,
            ) { app.capture?.stopRecording() })
        }
        fill?.takeIf { !it.finished }?.let { f ->
            add(HudActivity(
                "fill", FuseIcons.Wand, "${if (f.automatic) "Finding art" else "Filling art and details"}: ${f.done} of ${f.total}",
                progress = f.fraction.takeIf { f.total > 0 },
            ) { app.go(Route.Settings("media")) })
        }
        // Transfers (Cartridge's downloads and uploads, Store installs, Fuse's own update, Fuse RomM,
        // Jellyfin) all live behind the Downloads button now; only what isn't one stays here.
        when (val u = update) {
            is UpdateState.Downloading -> Unit
            is UpdateState.Ready -> add(HudActivity("update", FuseIcons.Refresh, "${u.release.name} is ready: restart to update", attention = true) { app.go(Route.Settings("about")) })
            else -> if (available != null) add(HudActivity("update", FuseIcons.Download, "${available?.name} is available", attention = true) { app.go(Route.Settings("about")) })
        }
    }
}

/**
 * The Downloads button's state: shown while anything moves or waits, while something failed, and
 * while Downloads is the page that is open (so it can be seen as where you are).
 */
@Composable
internal fun rememberHudDownloads(app: AppState): HudDownloads? {
    val summary by app.store.transfers.summary.collectAsState()
    val onPage = app.navigator.current == Route.Downloads
    if (!summary.any && summary.failed == 0 && !onPage) return null
    return HudDownloads(summary.active, summary.activeUploads > 0, summary.progress, summary.failed, summary.queued + summary.waiting)
}

/** How long a selection must rest before the room fades in its art. */
private const val HERO_SETTLE_MS = 160L

/** A page that arrives sooner than this after the last one switches without a transition. */
/** How far a new tab's page slides in from the side it came from. */
private val TAB_NUDGE = 24.dp

/** The tabs built ahead (beside Home), and how long after starting. */
private val WARM_PAGES = listOf(Destination.SYSTEMS, Destination.LIBRARY)
private const val WARM_AFTER_MS = 1_500L

/** How many tabs' pages are kept ready once visited. */
private const val KEPT_PAGES = 4

/** The widest the interface gets (width over height); wider screens centre it. A little over 21:9. */
private const val MAX_ASPECT = 2.4f

/**
 * A theme's picture: cropped to fill the screen around the part it keeps in view, then darkened (or,
 * in a bright theme, washed out toward the room's colour) by its dim, so text always reads over it.
 */
@Composable
internal fun WallpaperLayer(w: io.github.matiyaaa.fuse.model.Wallpaper, modifier: Modifier = Modifier) {
    val (fx, fy) = when (w.align) {
        io.github.matiyaaa.fuse.model.WallpaperAlign.CENTER -> 0.5f to 0.5f
        io.github.matiyaaa.fuse.model.WallpaperAlign.TOP -> 0.5f to 0f
        io.github.matiyaaa.fuse.model.WallpaperAlign.BOTTOM -> 0.5f to 1f
        io.github.matiyaaa.fuse.model.WallpaperAlign.LEFT -> 0f to 0.5f
        io.github.matiyaaa.fuse.model.WallpaperAlign.RIGHT -> 1f to 0.5f
    }
    val ink = Fuse.colors.ink
    Box(modifier) {
        io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(w.path, Modifier.fillMaxSize(), focusX = fx, focusY = fy, pin = true)
        Box(Modifier.fillMaxSize().background(ink.copy(alpha = w.dim.coerceIn(0f, 0.9f))))
    }
}

/** The standby screen; waking it plays the startup animation when that is on. */
@Composable
private fun StandbyHost(app: AppState, clock24h: Boolean, intro: Boolean) {
    StandbyScreen(clock24h) {
        app.standby = false
        // Not again if it has only just played (Fuse came back from sleep a moment ago).
        val recent = kotlin.time.Clock.System.now().toEpochMilliseconds() - StartupIntro.lastPlayedAt < INTRO_AGAIN_AFTER_MS
        if (intro && !recent) app.intro = true
    }
}

/** Waking from Standby within this long of the animation playing doesn't play it again. */
private const val INTRO_AGAIN_AFTER_MS = 5 * 60_000L

/**
 * The glyphs for the controller in hand: a PlayStation pad shows shapes and an Xbox pad letters,
 * whatever the setting says. Nintendo glyphs are left to the setting (and "Detect my buttons"),
 * since how a Nintendo pad reports its buttons decides which one confirms.
 */
internal fun padGlyphs(setting: GlyphStyle, family: GlyphStyle?): GlyphStyle = when (family) {
    GlyphStyle.PLAYSTATION -> GlyphStyle.PLAYSTATION
    GlyphStyle.XBOX -> if (setting == GlyphStyle.NINTENDO) setting else GlyphStyle.XBOX
    else -> setting
}

/**
 * Whether the hints put Confirm on the right face button for the glyphs shown. A PlayStation or
 * Xbox pad in hand reports its bottom button as A wherever Fuse runs, so Confirm is the bottom one
 * there unless confirm and back are swapped; otherwise the setting (and "Detect my buttons") decides.
 */
internal fun hintConfirmOnRight(input: io.github.matiyaaa.fuse.model.InputProfile, shown: GlyphStyle, family: GlyphStyle?): Boolean = when {
    input.autoGlyphs && shown != GlyphStyle.NINTENDO && (family == GlyphStyle.PLAYSTATION || family == GlyphStyle.XBOX) -> input.swapConfirmBack
    else -> input.confirmOnRight
}
