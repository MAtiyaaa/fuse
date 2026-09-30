package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.background.CrtOverlay
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastHost
import io.github.matiyaaa.fuse.ui.designsystem.input.InputFeedback
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroBackdrop
import io.github.matiyaaa.fuse.ui.designsystem.sound.LocalUiSounds
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.GlyphConfig
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.designsystem.theme.TileMetrics
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import io.github.matiyaaa.fuse.ui.shell.apps.AppsScreen
import io.github.matiyaaa.fuse.ui.shell.cartridge.CartridgeScreen
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.game.FolderBrowserScreen
import io.github.matiyaaa.fuse.ui.shell.game.GameScreen
import io.github.matiyaaa.fuse.ui.shell.home.HomeScreen
import io.github.matiyaaa.fuse.ui.shell.library.LibraryScope
import io.github.matiyaaa.fuse.ui.shell.library.LibraryScreen
import io.github.matiyaaa.fuse.ui.shell.media.MediaScreen
import io.github.matiyaaa.fuse.ui.shell.onboarding.OnboardingScreen
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.quick.QuickMenu
import io.github.matiyaaa.fuse.ui.shell.search.SearchScreen
import io.github.matiyaaa.fuse.ui.shell.settings.PlatformSettingsScreen
import io.github.matiyaaa.fuse.ui.shell.settings.SettingsScreen
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.systems.SystemsScreen
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import io.github.matiyaaa.fuse.ui.shell.components.TileBorders
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileBorders
import io.github.matiyaaa.fuse.ui.shell.components.PerformanceOverlay
import io.github.matiyaaa.fuse.model.ScopedSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.input.TextInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

/**
 * The whole Fuse interface for one window. [router] is created by the host (Android activity or
 * desktop window) because that is where raw input arrives.
 */
@Composable
fun FuseApp(store: FuseStore, platform: PlatformUi, router: InputRouter) {
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
        state = AppState(store, platform, scope, if (prefs.onboardingDone) Route.Root(Destination.HOME) else Route.Onboarding)
        state
    }
    val spec = ThemePresets.byId(prefs.themeId)
    val quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower)
    val lastSource by router.lastSource.collectAsState()
    val glyphStyle = when {
        !prefs.input.autoGlyphs -> prefs.input.glyphs
        lastSource == InputSource.KEYBOARD -> GlyphStyle.KEYBOARD
        else -> prefs.input.glyphs
    }

    // Input settings, sounds and haptics follow preferences.
    LaunchedEffect(prefs.input) { router.profile = prefs.input }
    // A hardware keyboard types into whichever text field is open.
    val keyboardTarget = app.keyboardTarget
    DisposableEffect(router, keyboardTarget) {
        router.textInput = keyboardTarget?.let { target ->
            object : TextInput {
                override fun type(text: String) = target.set(target.get() + text)
                override fun backspace() = target.set(target.get().dropLast(1))
                override fun submit() = target.submit()
                override fun paste() = app.pasteInto(target.get, target.set)
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
    MenuMusic(app, platform.music)
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
                }
                NavResult.ACTIVATED -> { platform.sounds.play(SoundCue.SELECT); platform.haptics.confirm() }
                NavResult.BLOCKED -> if (!event.isRepeat) { platform.sounds.play(SoundCue.BUMP); platform.haptics.reject() }
                NavResult.CONSUMED -> if (event.action == NavAction.BACK) platform.sounds.play(SoundCue.BACK)
                NavResult.IGNORED -> Unit
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
    ) {
        CompositionLocalProvider(LocalInputRouter provides router, LocalUiSounds provides platform.sounds) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Fuse.colors.ink)) {
                val metrics = remember(maxWidth, maxHeight) { TileMetrics.forHeight(maxHeight, maxWidth) }
                val borders = rememberTileBorders(store)
                CompositionLocalProvider(LocalTileMetrics provides metrics, LocalTileBorders provides borders) {
                    Room(app, prefs.showHero, spec.background, prefs.heroDim, prefs.glass, prefs.videoPreview, prefs.videoDelaySeconds)
                    ShellInput(app)
                    Pages(app)
                    val route = app.navigator.current
                    if (route != Route.Onboarding) {
                        val status by platform.status.collectAsState()
                        Hud(
                            destinations = visibleTabs(app, prefs),
                            active = app.navigator.root?.destination,
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
                        PerformanceOverlay(metrics, Modifier.align(Alignment.TopStart).padding(start = Space.gutter, top = Size.hudHeight + Space.s))
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
                    HintBar(app.hints, Modifier.align(Alignment.BottomEnd).padding(horizontal = Space.gutter, vertical = Space.s))
                    QuickMenu(app)
                    OverlayHost(app)
                    ToastHost(app.toasts)
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
) {
    val quality = Fuse.quality
    val hero = app.hero
    if (style != BackgroundStyle.HERO || hero == null || !showHero) {
        AmbientBackground(if (style == BackgroundStyle.HERO) BackgroundStyle.SOLID else style, hero?.accent ?: Fuse.colors.accent, Modifier.fillMaxSize())
    }
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

/** Page content with transitions that follow the direction you moved. */
@Composable
private fun Pages(app: AppState) {
    val motion = Fuse.motion
    val nav = app.navigator
    val destinations = Destination.entries
    AnimatedContent(
        targetState = nav.current,
        transitionSpec = {
            val dir = when (nav.direction) {
                NavDirection.FORWARD -> 1
                NavDirection.BACK -> -1
                NavDirection.LATERAL -> {
                    val from = (initialState as? Route.Root)?.destination?.let(destinations::indexOf) ?: 0
                    val to = (targetState as? Route.Root)?.destination?.let(destinations::indexOf) ?: 0
                    if (to >= from) 1 else -1
                }
            }
            val shift = motion.slideFraction
            (fadeIn(motion.fade(Durations.BASE)) + slideInHorizontally(motion.tween(Durations.SLOW, Easings.Enter)) { (it * shift * dir).toInt() }) togetherWith
                (fadeOut(motion.fade(Durations.FAST)) + slideOutHorizontally(motion.tween(Durations.BASE, Easings.Exit)) { (-it * shift * 0.6f * dir).toInt() })
        },
        contentKey = { it },
        label = "pages",
    ) { route ->
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
                is Route.GameInfo -> GameScreen(app, route.game)
                is Route.Media -> MediaScreen(app, route.owner, route.title)
                is Route.Settings -> SettingsScreen(app, route.section)
                is Route.PlatformSettings -> PlatformSettingsScreen(app, route.platform)
                Route.Search -> SearchScreen(app)
                Route.Controls -> io.github.matiyaaa.fuse.ui.shell.settings.ControlsScreen(app)
                Route.Licenses -> io.github.matiyaaa.fuse.ui.shell.settings.LicensesScreen(app)
                Route.Onboarding -> OnboardingScreen(app)
                is Route.FolderBrowser -> FolderBrowserScreen(app, route.game)
            }
        }
    }
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

/** The tabs shown in the top line: Home first, then the user's order. Cartridge needs Cartridge. */
@Composable
private fun visibleTabs(app: AppState, prefs: io.github.matiyaaa.fuse.ui.shell.store.UiPrefs): List<Destination> {
    val cartridge by app.store.cartridge.status.collectAsState()
    return (listOf(Destination.HOME) + prefs.destinations.filter { it != Destination.HOME })
        .filter { it != Destination.CARTRIDGE || cartridge.installed }
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

/** The menu music follows its settings and steps aside while a game starts or runs. */
@Composable
private fun MenuMusic(app: AppState, player: MenuMusicPlayer?) {
    player ?: return
    val prefs by app.store.prefs.collectAsState()
    val home by app.store.library.home.collectAsState()
    val music = prefs.music
    LaunchedEffect(music.enabled, music.songPath) { player.setSong(music.songPath.takeIf { music.enabled }) }
    LaunchedEffect(music.volume) { player.setVolume(music.volume) }
    val quiet = app.launching != null || home.playtime.currentGame != null
    LaunchedEffect(quiet) { player.setPlaying(!quiet) }
}

/** What is working in the background, for the top line: Cartridge downloads and Fuse updates. */
@Composable
private fun hudActivities(app: AppState): List<HudActivity> {
    val update by app.store.updates.state.collectAsState()
    val available by app.store.updates.available.collectAsState()
    val cartridge by app.store.cartridge.status.collectAsState()
    return buildList {
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
