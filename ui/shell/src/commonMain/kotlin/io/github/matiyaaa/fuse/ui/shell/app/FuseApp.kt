package io.github.matiyaaa.fuse.ui.shell.app

import kotlin.time.TimeSource
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.matiyaaa.fuse.ui.designsystem.components.rememberHintFlash
import io.github.matiyaaa.fuse.ui.designsystem.effects.RevealScope
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.shell.capture.CaptureController
import io.github.matiyaaa.fuse.ui.shell.capture.CaptureOverlay
import io.github.matiyaaa.fuse.ui.shell.capture.rememberRecordingTime
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
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
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.GlyphConfig
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.TileMetrics
import io.github.matiyaaa.fuse.ui.shell.apps.AppsScreen
import io.github.matiyaaa.fuse.ui.shell.cartridge.CartridgeScreen
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileBorders
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.FrameTimeOverlay
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
        state = AppState(store, platform, scope, if (stored.onboardingDone) Route.Root(Destination.HOME) else Route.Onboarding, phoneLink)
        state.safeMode = safeMode
        state
    }
    // Safe mode draws with Fuse's own look and no effects; what is saved never changes.
    val prefs = if (app.safeMode != null) stored.inSafeMode() else stored
    LaunchedEffect(Unit) {
        // The startup animation, once per start of Fuse (a window made again doesn't replay it).
        if (startupIntro && !StartupIntro.played && app.safeMode == null && stored.startupAnimation) app.intro = true
        StartupIntro.played = true
        if (app.safeMode != null) app.showSafeMode()
        delay(StartupGuard.SETTLE_MS)
        onSettled()
    }
    // Back after a long while away (the device slept, the screen was off): the animation again, as
    // though Fuse had just been switched on. Only where this start would have played it.
    LaunchedEffect(Unit) {
        Away.returns.collect { away ->
            val p = app.store.prefs.value
            if (startupIntro && away >= Away.AWAY_INTRO_MS && app.safeMode == null && p.startupAnimation && p.onboardingDone && app.launching == null) app.intro = true
        }
    }
    app.navigator.forgetsTabs = !prefs.rememberPlace
    val homeFeed by store.library.home.collectAsState()
    StandbyWatch(app, router, prefs.standbyMinutes) {
        app.intro || app.launching != null || homeFeed.playtime.currentGame != null || app.navigator.current == Route.Onboarding
    }
    DriveWatch(app) {
        app.intro || app.standby || app.launching != null || homeFeed.playtime.currentGame != null || app.navigator.current == Route.Onboarding
    }
    val spec = prefs.theme
    val quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower)
    val lastSource by router.lastSource.collectAsState()
    val glyphStyle = when {
        !prefs.input.autoGlyphs -> prefs.input.glyphs
        lastSource == InputSource.KEYBOARD -> GlyphStyle.KEYBOARD
        else -> prefs.input.glyphs
    }

    // Input settings, sounds and haptics follow preferences.
    LaunchedEffect(prefs.input) { router.profile = prefs.input }
    // L3 + R3: a screenshot, or held, a recording (where Fuse can capture its screen).
    val capture = app.capture
    DisposableEffect(router, capture, prefs.captureCombo) {
        router.onCaptureCombo = if (capture != null && prefs.captureCombo) capture::onCombo else null
        onDispose { router.onCaptureCombo = null }
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
        motion = prefs.motion,
        quality = quality,
        glyphs = GlyphConfig(glyphStyle, prefs.input.confirmOnRight),
        glass = prefs.glass,
        highContrastFocus = prefs.highContrastFocus,
        animateChanges = true,
        textScale = prefs.textScale,
    ) {
        CompositionLocalProvider(LocalInputRouter provides router, LocalUiSounds provides platform.sounds) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(Fuse.colors.ink)
                    // A touch or the pointer counts as being here, for standby.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                router.touched()
                            }
                        }
                    },
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
                    if (app.intro) StartupIntroOverlay(onDone = { app.intro = false })
                }
                }
                if (prefs.crt.enabled && quality.crtShader) CrtOverlay(prefs.crt)
            }
        }
    }

    // Leaving Fuse (a game started) and coming back.
    LaunchedEffect(Unit) { store.library.onResume() }
    // The Store's news (an app installed, updated or removed), wherever the user is.
    LaunchedEffect(Unit) { store.appStore.notices.collect { app.toasts.show(it) } }
    LaunchedEffect(Unit) { store.cartridge.notices.collect { app.toasts.show(it) } }
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
    AmbientBackground(if (style == BackgroundStyle.HERO) BackgroundStyle.SOLID else style, hero?.accent ?: Fuse.colors.accent, Modifier.fillMaxSize(), ambient = ambient)
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
                    AnimatedVisibility(videoReady, enter = fadeIn(Fuse.motion.fade(Durations.DELIBERATE)), exit = fadeOut(Fuse.motion.fade(Durations.FAST))) {
                        Box(Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (videoReady) 1f else 0f }) {
                        player.Player(src, playing = app.launching == null && !app.overlayOpen, modifier = Modifier.fillMaxSize(), onFirstFrame = { videoReady = true })
                    }
                }
            },
        )
    }
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
    // Tabs follow the order the top line shows them in, which the user may have changed.
    fun place(route: Route): Int {
        val d = (route as? Route.Root)?.destination ?: return 0
        return tabs.indexOf(d).takeIf { it >= 0 } ?: (tabs.size + d.ordinal)
    }
    // When the next page arrives while the last one is still sliding in (a shoulder button tapped
    // again and again), it switches at once: stacking half-composed pages is what made quick runs lag.
    val lastSwitch = remember { arrayOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    AnimatedContent(
        targetState = nav.current,
        transitionSpec = {
            val now = TimeSource.Monotonic.markNow()
            val quick = lastSwitch[0]?.let { (now - it).inWholeMilliseconds < QUICK_SWITCH_MS } == true
            lastSwitch[0] = now
            if (quick) return@AnimatedContent (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = false))
            val dir = when (nav.direction) {
                NavDirection.FORWARD -> 1
                NavDirection.BACK -> -1
                NavDirection.LATERAL -> if (place(targetState) >= place(initialState)) 1 else -1
            }
            val shift = motion.slideFraction
            val enter = fadeIn(tween(motion.ms(Durations.BASE), delayMillis = motion.ms(Durations.INSTANT) / 2, easing = Easings.Fade)) +
                slideInHorizontally(motion.tween(Durations.BASE, Easings.Enter)) { (it * shift * dir).toInt() }
            val exit = fadeOut(motion.tween(Durations.FAST, Easings.Standard)) +
                slideOutHorizontally(motion.tween(Durations.FAST, Easings.Standard)) { (-it * shift * 0.5f * dir).toInt() }
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        contentKey = { it },
        label = "pages",
    ) { route ->
        RevealScope(route) {
            Box(Modifier.fillMaxSize()) {
                when (route) {
                    is Route.Root -> when (route.destination) {
                        Destination.HOME -> HomeScreen(app)
                        Destination.LIBRARY -> LibraryScreen(app, LibraryScope.All)
                        Destination.SYSTEMS -> SystemsScreen(app)
                        Destination.ACHIEVEMENTS -> io.github.matiyaaa.fuse.ui.shell.achievements.AchievementsScreen(app)
                        Destination.APPS -> AppsScreen(app)
                        Destination.CARTRIDGE -> if (app.sections.addons) io.github.matiyaaa.fuse.ui.shell.addons.AddonsScreen(app) else CartridgeScreen(app)
                    }
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
                    Route.PlayTime -> io.github.matiyaaa.fuse.ui.shell.library.PlayTimeScreen(app)
                    Route.Themes -> io.github.matiyaaa.fuse.ui.shell.settings.ThemesScreen(app)
                    Route.Onboarding -> OnboardingScreen(app)
                    is Route.FolderBrowser -> FolderBrowserScreen(app, route.game)
                    is Route.GameContent -> io.github.matiyaaa.fuse.ui.shell.game.GameContentScreen(app, route.game)
                    is Route.PickFile -> io.github.matiyaaa.fuse.ui.shell.files.FilePickerScreen(app, route.purpose, route.locate, route.licence)
                    is Route.StoreApp -> io.github.matiyaaa.fuse.ui.shell.addons.StoreAppScreen(app, route.key)
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
            Route.Search -> return HudButton.SEARCH
            is Route.Settings, is Route.PlatformSettings, Route.Controls, Route.Licenses, Route.Themes, Route.Storage, Route.PhoneLink ->
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
                    HudButton.STATUS -> { app.hudButton = HudButton.SETTINGS; NavResult.MOVED }
                    HudButton.SETTINGS -> { app.hudButton = HudButton.SEARCH; NavResult.MOVED }
                    HudButton.SEARCH -> { app.hudButton = null; NavResult.MOVED }
                    null -> cycle(-1)
                }
                NavAction.RIGHT -> when (button) {
                    HudButton.SEARCH -> { app.hudButton = HudButton.SETTINGS; NavResult.MOVED }
                    // Past Settings: Wi-Fi, battery and the clock, which open the quick menu.
                    HudButton.SETTINGS -> { app.hudButton = HudButton.STATUS; NavResult.MOVED }
                    HudButton.STATUS -> NavResult.BLOCKED
                    null -> if (tabs.lastOrNull() == active) { app.hudButton = HudButton.SEARCH; NavResult.MOVED } else cycle(1)
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
            NavAction.UP -> if (app.navigator.stack.size == 1) { app.focusZone = FocusZone.TABS; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.PREVIOUS_SECTION -> when (page) {
                null -> cycle(-1)
                else -> tabs.lastOrNull()?.let { app.selectTab(it); NavResult.MOVED } ?: NavResult.BLOCKED
            }
            NavAction.NEXT_SECTION -> when (page) {
                null -> cycle(1)
                HudButton.SEARCH -> { app.go(Route.Settings()); NavResult.MOVED }
                HudButton.SETTINGS, HudButton.STATUS -> NavResult.BLOCKED
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
    HudButton.SEARCH -> go(Route.Search)
    HudButton.SETTINGS -> go(Route.Settings())
    HudButton.STATUS -> quickMenuOpen = true
}

/** A short, calm handoff while the emulator starts: the game's art fills the screen and dims away. */
@Composable
private fun LaunchVeilView(app: AppState) {
    val veil = app.launching
    AnimatedVisibility(
        visible = veil != null,
        enter = fadeIn(Fuse.motion.fade(Durations.BASE)),
        exit = fadeOut(Fuse.motion.fade(Durations.SLOW)),
    ) {
        var shown by remember { mutableStateOf(veil) }
        if (veil != null) shown = veil
        val v = shown ?: return@AnimatedVisibility
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
 * The menu music follows its settings and steps aside while a game starts or runs. First-time setup
 * plays its own song and crossfades into the menu song when it finishes.
 *
 * Everything is handed to the player as one [MusicState] ([MenuMusicPlan]), and handed again whenever
 * any part of it changes, so the player can always repair itself from the latest complete picture.
 */
@Composable
private fun MenuMusic(app: AppState, player: MenuMusicPlayer?) {
    player ?: return
    val prefs by app.store.prefs.collectAsState()
    val home by app.store.library.home.collectAsState()
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
    // The startup animation has its own sound; the music waits until it has opened out.
    val quiet = app.launching != null || home.playtime.currentGame != null || app.intro || app.standby
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

/** What is working in the background, for the top line: recordings, art fills, uploads, downloads and updates. */
@Composable
private fun hudActivities(app: AppState): List<HudActivity> {
    val health = io.github.matiyaaa.fuse.ui.shell.settings.rememberHealthIssues(app)
    val update by app.store.updates.state.collectAsState()
    val available by app.store.updates.available.collectAsState()
    val cartridge by app.store.cartridge.status.collectAsState()
    val fill by app.store.media.fillProgress.collectAsState()
    val shop by app.store.appStore.state.collectAsState()
    val recordingTime = rememberRecordingTime(app.capture)
    return buildList {
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
        // Only while bytes are going: once RomM is adding the game, the upload is done for the user.
        cartridge.uploads.firstOrNull { it.state == io.github.matiyaaa.fuse.model.UploadState.UPLOADING || it.state == io.github.matiyaaa.fuse.model.UploadState.WAITING }?.let { u ->
            add(HudActivity(
                "upload", FuseIcons.Upload, "Uploading ${u.title} to RomM",
                progress = u.progress.takeIf { u.state == io.github.matiyaaa.fuse.model.UploadState.UPLOADING },
            ) { app.openCartridge() })
        }
        if (cartridge.installed && (cartridge.activeDownloads > 0 || cartridge.queue.any { it.state == io.github.matiyaaa.fuse.model.QueueState.DOWNLOADING })) {
            val current = cartridge.queue.firstOrNull { it.state == io.github.matiyaaa.fuse.model.QueueState.DOWNLOADING }
            add(HudActivity(
                "cartridge", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks.Cartridge, "Cartridge is downloading ${current?.title ?: cartridge.currentTitle ?: "a game"}",
                progress = current?.progress ?: cartridge.progress,
            ) { app.openCartridge() })
        }
        // Store installs carry on anywhere in Fuse; the top line keeps them in view.
        val installing = shop.jobs.filter { (_, j) -> j.active && j !is io.github.matiyaaa.fuse.ui.shell.store.StoreJob.Uninstalling }
        installing.entries.firstOrNull()?.let { (key, job) ->
            val name = shop.app(key)?.name ?: "an app"
            val label = if (installing.size > 1) "Store: ${installing.size} apps on their way" else "$name  ·  ${io.github.matiyaaa.fuse.ui.shell.addons.jobShort(job)}"
            add(HudActivity("store", FuseIcons.Store, label, progress = io.github.matiyaaa.fuse.ui.shell.addons.jobProgress(job)) { app.openStore(key) })
        }
        when (val u = update) {
            is UpdateState.Downloading -> add(HudActivity("update", FuseIcons.Download, "Downloading ${u.release.name}", progress = u.progress) { app.go(Route.Settings("about")) })
            is UpdateState.Ready -> add(HudActivity("update", FuseIcons.Refresh, "${u.release.name} is ready: restart to update", attention = true) { app.go(Route.Settings("about")) })
            else -> if (available != null) add(HudActivity("update", FuseIcons.Download, "${available?.name} is available", attention = true) { app.go(Route.Settings("about")) })
        }
    }
}

/** How long a selection must rest before the room fades in its art. */
private const val HERO_SETTLE_MS = 160L

/** A page that arrives sooner than this after the last one switches without a transition. */
private const val QUICK_SWITCH_MS = 300L

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
        io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(w.path, Modifier.fillMaxSize(), focusX = fx, focusY = fy)
        Box(Modifier.fillMaxSize().background(ink.copy(alpha = w.dim.coerceIn(0f, 0.9f))))
    }
}

/** The standby screen; waking it plays the startup animation when that is on. */
@Composable
private fun StandbyHost(app: AppState, clock24h: Boolean, intro: Boolean) {
    StandbyScreen(app, clock24h) {
        app.standby = false
        if (intro) app.intro = true
    }
}
