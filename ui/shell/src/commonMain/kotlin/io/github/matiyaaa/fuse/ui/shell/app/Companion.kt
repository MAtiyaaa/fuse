package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.PageDots
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusCluster
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroBackdrop
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.components.CoverCollage
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.systems.SystemShowcase
import io.github.matiyaaa.fuse.ui.shell.systems.panelFade
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the main screen has in focus, shared with the companion screen. Keys are [GameId] or
 * [PlatformId]; null when nothing specific is focused. [Focus.direction] says which way the main
 * screen moved to get there (1 right or down, -1 left or up, 0 a jump or a tap), so the companion
 * can slide the same way.
 */
object Spotlight {
    data class Focus(val key: Any?, val direction: Int = 0)

    private val state = MutableStateFlow(Focus(null))
    val focused: StateFlow<Focus> = state
    private var pending = 0
    private var pendingAt: TimeSource.Monotonic.ValueTimeMark? = null

    /** The main screen moved the selection; the next [set] (if it comes soon) slides this way. */
    fun moved(direction: Int) {
        pending = direction
        pendingAt = TimeSource.Monotonic.markNow()
    }

    fun set(key: Any?) {
        if (state.value.key == key) return
        val fresh = pendingAt?.let { it.elapsedNow().inWholeMilliseconds < 800 } == true
        state.value = Focus(key, if (fresh) pending else 0)
        pending = 0
        pendingAt = null
    }
}

/** What the companion shows, with the direction it arrived from. */
private data class CompanionContent(val target: Any?, val direction: Int)

/** The companion's pages, in order. */
private val companionPages = listOf("Now", "Status", "Controls")

/**
 * The page the second screen is on, shared by every host it shows in (the companion can be
 * recreated). Setting it turns the page.
 */
internal object CompanionPage {
    val current = MutableStateFlow<Int?>(null)
}

/**
 * Content for a second screen (dual-screen handhelds, an external display). The Android app shows
 * it in its own window on the other display; it is touch only and never takes controller input,
 * so the main screen keeps focus.
 *
 * Three pages, swiped or picked with the dots at the bottom, with the status line on every one:
 * what the main screen has in focus (a game's logo over its room, a system, a collection, the game
 * being played), the device's status, and controls for brightness and sound. The backdrop
 * crossfades behind everything; on the first page the details slide the way the main screen moved.
 */
@Composable
fun CompanionApp(store: FuseStore, platform: PlatformUi, mode: DualScreenMode) {
    val prefs by store.prefs.collectAsState()
    val spec = ThemePresets.byId(prefs.themeId)
    FuseTheme(
        spec = spec,
        motion = prefs.motion,
        quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower),
        glass = prefs.glass,
        highContrastFocus = prefs.highContrastFocus,
    ) {
        val home by store.library.home.collectAsState()
        val status by platform.status.collectAsState()
        val focus by Spotlight.focused.collectAsState()
        val systems by store.library.platforms.collectAsState()
        val time = rememberClockText(prefs.clock24h)
        val playing = home.playtime.currentGame
        val scope = rememberCoroutineScope()
        val pager = rememberPagerState(initialPage = (CompanionPage.current.value ?: prefs.display.companionPage).coerceIn(0, companionPages.lastIndex)) { companionPages.size }
        LaunchedEffect(pager) {
            CompanionPage.current.collect { page -> if (page != null && page != pager.settledPage && !pager.isScrollInProgress) pager.animateScrollToPage(page) }
        }
        // The page is kept while Fuse runs and saved for the next start.
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { page ->
                CompanionPage.current.value = page
                if (store.prefs.value.display.companionPage != page) store.updatePrefs { it.copy(display = it.display.copy(companionPage = page)) }
            }
        }
        Box(Modifier.fillMaxSize().background(Fuse.colors.ink)) {
            val content = when {
                mode == DualScreenMode.GAME_COMPANION && playing != null -> CompanionContent(playing, 0)
                mode == DualScreenMode.LIBRARY_COMPANION -> CompanionContent(focus.key, focus.direction)
                playing != null -> CompanionContent(playing, 0)
                else -> CompanionContent(null, 0)
            }
            val hero = companionHero(store, systems, content.target)
            HeroBackdrop(hero, Modifier.fillMaxSize(), dim = 0.25f, gradient = 0.75f, settleMs = 60)
            // Status and controls sit on a deeper shade, so their cards read over any art.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }
                    .background(Fuse.colors.ink.copy(alpha = 0.55f)),
            )
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> SpotlightPage(store, content, home.playtime.currentSince, systems, time)
                    1 -> StatusPage(store, platform, status)
                    else -> ControlsPage(store, platform)
                }
            }
            if (prefs.display.companionShowsPerformance && pager.currentPage == 0) {
                val metrics by platform.performance.collectAsState()
                io.github.matiyaaa.fuse.ui.shell.components.PerformanceOverlay(
                    metrics,
                    Modifier.align(Alignment.BottomEnd).padding(Space.l).padding(bottom = Space.xl),
                )
            }
            StatusCluster(
                status,
                time,
                Modifier.align(Alignment.TopEnd).padding(horizontal = Space.l, vertical = Space.m),
                showWifi = prefs.showWifi,
                showBluetooth = prefs.showBluetooth,
            )
            PageDots(
                count = companionPages.size,
                current = pager.currentPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                labels = companionPages,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s),
            )
        }
    }
}

/** The first page: what the main screen has in focus, sliding the way it moved. */
@Composable
private fun SpotlightPage(store: FuseStore, content: CompanionContent, since: Long?, systems: List<PlatformCard>, time: String) {
    val motion = Fuse.motion
    AnimatedContent(
        targetState = content,
        transitionSpec = {
            val dir = targetState.direction
            val enter = fadeIn(motion.fade(Durations.SLOW)) + scaleIn(motion.tween(Durations.SLOW, Easings.Enter), initialScale = if (motion.reduced) 1f else 0.97f)
            val exit = fadeOut(motion.fade(Durations.FAST))
            if (dir == 0 || motion.reduced) {
                enter togetherWith exit
            } else {
                val shift = (motion.slideFraction * 2.5f).coerceAtMost(0.2f)
                (slideInHorizontally(motion.tween(Durations.SLOW, Easings.Enter)) { (it * shift * dir).toInt() } + enter) togetherWith
                    (slideOutHorizontally(motion.tween(Durations.BASE, Easings.Exit)) { (-it * shift * dir).toInt() } + exit)
            }
        },
        contentKey = { (it.target as? GameCard)?.id ?: it.target },
        label = "companion",
    ) { c ->
        when (val target = c.target) {
            is GameCard -> NowPlaying(target, since)
            is GameId -> FocusedGame(store, target)
            is PlatformId -> FocusedPlatform(systems.firstOrNull { it.platform.id == target })
            is CollectionId -> FocusedCollection(store, target)
            else -> Idle(time)
        }
    }
}

/** The backdrop for what the companion shows: the game's background art, or the system's. */
@Composable
private fun companionHero(store: FuseStore, systems: List<PlatformCard>, target: Any?): HeroSource? = when (target) {
    is GameCard -> target.room(systems.firstOrNull { it.platform.id == target.platformId })
    is GameId -> {
        val flow = remember(target) { store.library.game(target) }
        val detail by flow.collectAsState(initial = null)
        detail?.let { d -> gameRoom(target, d.art, d.platform.accent, systems.firstOrNull { it.platform.id == d.platform.id }) }
    }
    is PlatformId -> systems.firstOrNull { it.platform.id == target }?.let { HeroSource(target, it.art.hero, it.platform.accent.toColor()) }
    // A collection's room is its first game's.
    is CollectionId -> {
        val flow = remember(target) { store.library.games(GameQuery(collection = target)) }
        val games by flow.collectAsState(initial = emptyList())
        games.firstOrNull()?.let { g -> gameRoom(target, g.art, g.accent, systems.firstOrNull { it.platform.id == g.platformId }) }
    }
    else -> null
}

@Composable
private fun Idle(time: String) {
    // Opaque, so the last game's art never lingers behind the clock.
    Column(Modifier.fillMaxSize().background(Fuse.colors.ink), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        FuseMark(Modifier.size(56.dp))
        Spacer(Modifier.height(Space.l))
        FText(time, Fuse.type.numericLarge)
        FText(formatDate(), Fuse.type.body, color = Fuse.colors.textMuted)
    }
}

/**
 * A game: its logo large and centred over its room, nothing else, so the second screen reads as
 * the game's poster. Without a logo the title stands in, in display type.
 */
@Composable
private fun FocusedGame(store: FuseStore, id: GameId) {
    val flow = remember(id) { store.library.game(id) }
    val detail by flow.collectAsState(initial = null)
    val d = detail ?: return
    GameLogo(d.art.logo, d.game.displayTitle)
}

/** A game's logo (or its title) in the middle of the page, with a soft shade behind it for contrast. */
@Composable
private fun GameLogo(logo: Any?, title: String, below: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.x3)) {
        val maxW = maxWidth
        val maxH = maxHeight
        // A soft pool of shade behind the logo, so white and dark logos both read over any art.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent))),
        )
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val name: @Composable () -> Unit = {
                FText(title, Fuse.type.display, maxLines = 3, align = TextAlign.Center, modifier = Modifier.widthIn(max = maxW))
            }
            if (logo != null) {
                Artwork(
                    logo,
                    Modifier.width(maxW * 0.86f).height((maxH * 0.42f).coerceAtMost(maxW * 0.6f)),
                    contentScale = ContentScale.Fit,
                    fadeIn = true,
                    fallback = name,
                )
            } else {
                name()
            }
            below()
        }
    }
}

/** A collection: its name, a fan of its games' covers, and how many there are. */
@Composable
private fun FocusedCollection(store: FuseStore, id: CollectionId) {
    val collections by store.collections.collections.collectAsState()
    val collection = collections.firstOrNull { it.id == id } ?: return
    val flow = remember(id) { store.library.games(GameQuery(collection = id)) }
    val games by flow.collectAsState(initial = emptyList())
    val c = Fuse.colors
    // Clear of the status line above and the page dots below.
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = Space.xl, end = Space.xl, top = Space.x3, bottom = Space.x4)) {
        val art = (maxHeight * 0.5f).coerceAtMost(maxWidth * 0.62f)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            if (games.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(art)
                        .clip(SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.5f)),
                ) {
                    CoverCollage(games, art)
                }
                Spacer(Modifier.height(Space.l))
            }
            SectionLabel(if (collection.kind == io.github.matiyaaa.fuse.model.CollectionKind.SERIES) "Series" else "Collection")
            Spacer(Modifier.height(Space.xs))
            FText(collection.name, Fuse.type.display, maxLines = 2)
            Spacer(Modifier.height(Space.xs))
            FText(
                if (collection.gameCount == 0) "No games yet" else "${collection.gameCount} ${if (collection.gameCount == 1) "game" else "games"}",
                Fuse.type.body, color = c.textMuted, maxLines = 1,
            )
        }
    }
}

/**
 * A system: the art pack's panel on the right (unless the system has a background image), its
 * logo in white over the system's colour, then games, emulator and firmware.
 */
@Composable
private fun FocusedPlatform(card: PlatformCard?) {
    card ?: return
    val p = card.platform
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight > maxWidth * 1.15f
        val panel = if (tall) Modifier.align(Alignment.TopEnd).fillMaxWidth(0.9f).fillMaxHeight(0.7f)
        else Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.6f)
        when {
            card.art.hero != null -> Unit
            card.art.boxart != null -> SystemShowcase(card, panel)
            // Until the art pack is downloaded, a panel in the system's colour.
            else -> Box(panel.panelFade()) {
                GeneratedArt(p.shortName, p.accent.toColor(), slot = ArtSlot.SYSTEM, label = p.manufacturer, showText = false)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (tall) 1f else 0.62f).padding(Space.xl)) {
            SectionLabel(listOfNotNull(p.manufacturer, p.releaseYear?.toString()).joinToString("  ·  ").ifEmpty { "System" })
            Spacer(Modifier.height(Space.s))
            val name: @Composable () -> Unit = { FText(p.name, Fuse.type.display, maxLines = 2) }
            if (card.art.logo != null) {
                Artwork(
                    card.art.logo,
                    Modifier.height(72.dp).fillMaxWidth(0.85f),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 1f,
                    tint = Color.White,
                    fadeIn = false,
                    fallback = name,
                )
            } else {
                name()
            }
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Chip("${card.gameCount} ${if (card.gameCount == 1) "game" else "games"}", icon = FuseIcons.Library)
                Chip(card.emulatorName ?: "No emulator", icon = FuseIcons.Gamepad, color = if (card.emulatorInstalled) Fuse.colors.text else Fuse.colors.warning)
            }
            val firmware = when (card.bios.state) {
                BiosState.READY -> Triple("Firmware ready", FuseIcons.Check, Fuse.colors.success)
                BiosState.MISSING -> Triple("Firmware missing", FuseIcons.Warning, Fuse.colors.danger)
                BiosState.PARTIAL -> Triple("Firmware incomplete", FuseIcons.Warning, Fuse.colors.warning)
                else -> null
            }
            if (firmware != null) {
                Spacer(Modifier.height(Space.s))
                Chip(firmware.first, icon = firmware.second, color = firmware.third)
            }
        }
    }
}

/** The game being played: its logo over its room, and how long this session has run. */
@Composable
private fun NowPlaying(game: GameCard, since: Long?) {
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }
    GameLogo(game.art.logo, game.title) {
        Spacer(Modifier.height(Space.l))
        val session = since?.let { "  ·  ${playtimeText(((now - it) / 1000).coerceAtLeast(0))}" }.orEmpty()
        Row(
            Modifier.clip(PillShape).background(Fuse.colors.ink.copy(alpha = 0.55f)).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.colors.accent))
            FText("Playing$session", Fuse.type.label, maxLines = 1)
        }
    }
}
