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
import io.github.matiyaaa.fuse.ui.shell.components.PerformanceOverlay
import io.github.matiyaaa.fuse.ui.shell.components.TileBorders
import io.github.matiyaaa.fuse.ui.shell.game.FolderBrowserScreen
import io.github.matiyaaa.fuse.ui.shell.game.GameScreen
import io.github.matiyaaa.fuse.ui.shell.home.HomeScreen
import io.github.matiyaaa.fuse.ui.shell.library.LibraryScope
import io.github.matiyaaa.fuse.ui.shell.library.LibraryScreen
import io.github.matiyaaa.fuse.ui.shell.media.MediaScreen
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
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
fun FuseApp(store: FuseStore, platform: PlatformUi, router: InputRouter, phoneLink: PhoneLinkControl? = null) {
    val base = rememberCoroutineScope()
    val prefs by store.prefs.collectAsState()
    val app = remember {
        lateinit var state: AppState
        // Everything screens start runs here. A failure shows a message; it never closes Fuse.
        val scope = CoroutineScope(
            base.coroutineContext + SupervisorJob(base.coroutineContext[Job]) + CoroutineExceptionHandler { _, t ->
                state.toasts.show("Something went wrong (${t::class.simpleName ?: "error"}). Fuse kept running.", ToastKind.ERROR)
            },
        )
        state = AppState(store, platform, scope, if (prefs.onboardingDone) Route.Root(Destination.HOME) else Route.Onboarding, phoneLink)
        state
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
    ) {
        CompositionLocalProvider(LocalInputRouter provides router, LocalUiSounds provides platform.sounds) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Fuse.colors.ink)) {
                val metrics = remember(maxWidth, maxHeight) { TileMetrics.forHeight(maxHeight, maxWidth) }
                val borders = rememberTileBorders(store)
                CompositionLocalProvider(LocalTileMetrics provides metrics, LocalTileBorders provides borders, LocalGameArt provides prefs.gameArt) {
                    Room(app, prefs.showHero, spec.background, prefs.heroDim, prefs.glass, prefs.videoPreview, prefs.videoDelaySeconds, spec.ambient)
                    ArtWarmup(app)
                    ShellInput(app)
                    Pages(app, visibleTabs(app, prefs))
                    val route = app.navigator.current
                    if (route != Route.Onboarding) {
                        val status by platform.status.collectAsState()
                        // Settings or Search open is where you are, not the tab underneath them.
                        val page = hudPage(app.navigator.stack)
                        HudScrim(art = prefs.showHero && app.hero != null)
                        Hud(
                            destinations = visibleTabs(app, prefs),
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
                    if (prefs.crt.enabled && quality.crtShader) CrtOverlay(prefs.crt)
                }
            }
        }
    }

    // Leaving Fuse (a game started) and coming back.
    LaunchedEffect(Unit) { store.library.onResume() }
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
                        Destination.CARTRIDGE -> CartridgeScreen(app)
                    }
                    is Route.PlatformGames -> LibraryScreen(app, LibraryScope.OfPlatform(route.platform))
                    is Route.CollectionGames -> LibraryScreen(app, LibraryScope.OfCollection(route.collection, route.name))
                    Route.Collections -> io.github.matiyaaa.fuse.ui.shell.collections.CollectionsScreen(app)
                    Route.Storage -> io.github.matiyaaa.fuse.ui.shell.settings.StorageScreen(app)
                    Route.PhoneLink -> io.github.matiyaaa.fuse.ui.shell.settings.PhoneLinkScreen(app)
                    is Route.GameInfo -> GameScreen(app, route.game)
                    is Route.Media -> MediaScreen(app, route.owner, route.title, route.identify)
                    is Route.Settings -> SettingsScreen(app, route.section)
                    is Route.PlatformSettings -> PlatformSettingsScreen(app, route.platform)
                    Route.Search -> SearchScreen(app)
                    Route.Controls -> io.github.matiyaaa.fuse.ui.shell.settings.ControlsScreen(app)
                    Route.Licenses -> io.github.matiyaaa.fuse.ui.shell.settings.LicensesScreen(app)
                    Route.Themes -> io.github.matiyaaa.fuse.ui.shell.settings.ThemesScreen(app)
                    Route.Onboarding -> OnboardingScreen(app)
                    is Route.FolderBrowser -> FolderBrowserScreen(app, route.game)
                    is Route.PickFile -> io.github.matiyaaa.fuse.ui.shell.files.FilePickerScreen(app, route.purpose, route.locate)
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
    val tabs = visibleTabs(app, prefs)
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
                    HudButton.SETTINGS -> { app.hudButton = HudButton.SEARCH; NavResult.MOVED }
                    HudButton.SEARCH -> { app.hudButton = null; NavResult.MOVED }
                    null -> cycle(-1)
                }
                NavAction.RIGHT -> when (button) {
                    HudButton.SEARCH -> { app.hudButton = HudButton.SETTINGS; NavResult.MOVED }
                    HudButton.SETTINGS -> NavResult.BLOCKED
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
        when (e.action) {
            NavAction.UP -> if (app.navigator.stack.size == 1) { app.focusZone = FocusZone.TABS; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.PREVIOUS_SECTION -> cycle(-1)
            NavAction.NEXT_SECTION -> cycle(1)
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

/** The tabs shown in the top line: Home first, then the user's order. Cartridge needs Cartridge, Apps an app list. */
@Composable
private fun visibleTabs(app: AppState, prefs: io.github.matiyaaa.fuse.ui.shell.store.UiPrefs): List<Destination> {
    val cartridge by app.store.cartridge.status.collectAsState()
    return (listOf(Destination.HOME) + prefs.destinations.filter { it != Destination.HOME })
        .filter { app.offers(it) && (it != Destination.CARTRIDGE || cartridge.installed) }
}

private fun AppState.runHudButton(button: HudButton) = when (button) {
    HudButton.SEARCH -> go(Route.Search)
    HudButton.SETTINGS -> go(Route.Settings())
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
 */
@Composable
private fun MenuMusic(app: AppState, player: MenuMusicPlayer?) {
    player ?: return
    val prefs by app.store.prefs.collectAsState()
    val home by app.store.library.home.collectAsState()
    val music = prefs.music
    val setup = app.navigator.current == Route.Onboarding
    val track = when {
        !music.enabled -> null
        setup -> BundledMusic.ONBOARDING
        else -> music.track
    }
    // The previous song keeps playing until the next one is ready, so the player can crossfade.
    var song by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track, music.songPath) {
        song = when (track) {
            null -> null
            BundledMusic.OWN_SONG -> music.songPath
            else -> app.store.bundledTrack(track)
        }
    }
    LaunchedEffect(song) { player.setSong(song) }
    LaunchedEffect(music.volume) { player.setVolume(music.volume) }
    val quiet = app.launching != null || home.playtime.currentGame != null
    LaunchedEffect(quiet) { player.setPlaying(!quiet) }
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
        val needs = f.needsYou.size.takeIf { it > 0 }?.let { " $it need you in Settings, Media and Scraping." } ?: ""
        val lead = if (f.automatic) "Found art for your games" else "Fill finished"
        app.toasts.show("$lead. ${io.github.matiyaaa.fuse.ui.shell.settings.fillSummary(f)}.$needs")
    }
}

/** What is working in the background, for the top line: recordings, art fills, uploads, downloads and updates. */
@Composable
private fun hudActivities(app: AppState): List<HudActivity> {
    val update by app.store.updates.state.collectAsState()
    val available by app.store.updates.available.collectAsState()
    val cartridge by app.store.cartridge.status.collectAsState()
    val fill by app.store.media.fillProgress.collectAsState()
    val recordingTime = rememberRecordingTime(app.capture)
    return buildList {
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
        cartridge.uploads.firstOrNull { it.active }?.let { u ->
            add(HudActivity(
                "upload", FuseIcons.Upload, "Uploading ${u.title} to RomM",
                progress = u.progress.takeIf { u.state == io.github.matiyaaa.fuse.model.UploadState.UPLOADING },
            ) { app.selectTab(io.github.matiyaaa.fuse.model.Destination.CARTRIDGE) })
        }
        if (cartridge.installed && (cartridge.activeDownloads > 0 || cartridge.queue.any { it.state == io.github.matiyaaa.fuse.model.QueueState.DOWNLOADING })) {
            val current = cartridge.queue.firstOrNull { it.state == io.github.matiyaaa.fuse.model.QueueState.DOWNLOADING }
            add(HudActivity(
                "cartridge", FuseIcons.CloudDownload, "Cartridge is downloading ${current?.title ?: cartridge.currentTitle ?: "a game"}",
                progress = current?.progress ?: cartridge.progress,
            ) { app.selectTab(io.github.matiyaaa.fuse.model.Destination.CARTRIDGE) })
        }
        when (val u = update) {
            is UpdateState.Downloading -> add(HudActivity("update", FuseIcons.Download, "Downloading ${u.release.name}", progress = u.progress) { app.go(Route.Settings("updates")) })
            is UpdateState.Ready -> add(HudActivity("update", FuseIcons.Refresh, "${u.release.name} is ready: restart to update", attention = true) { app.go(Route.Settings("updates")) })
            else -> if (available != null) add(HudActivity("update", FuseIcons.Download, "${available?.name} is available", attention = true) { app.go(Route.Settings("updates")) })
        }
    }
}

/** How long a selection must rest before the room fades in its art. */
private const val HERO_SETTLE_MS = 90L

/** A page that arrives sooner than this after the last one switches without a transition. */
private const val QUICK_SWITCH_MS = 300L
