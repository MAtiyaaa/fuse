package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.PageDots
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusCluster
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroBackdrop
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Enter
import io.github.matiyaaa.fuse.ui.fuseline.Exit
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.scaleIn
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.slideOutHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.components.CoverCollage
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlin.math.abs
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
fun CompanionApp(store: FuseStore, platform: PlatformUi, mode: DualScreenMode, onHide: (() -> Unit)? = null) {
    val prefs by store.prefs.collectAsState()
    val spec = prefs.theme
    FuseTheme(
        spec = spec,
        motion = prefs.motion,
        quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower),
        glass = prefs.glass,
        highContrastFocus = prefs.highContrastFocus,
    ) {
        val home by store.homeFeed.collectAsState()
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
        Box(Modifier.fillMaxSize().background(Fuse.colors.ink).veiledWhileOpening()) {
            // The game being played comes first in every mode (Fuse is in the background then).
            val content = when {
                playing != null -> CompanionContent(playing, 0)
                mode == DualScreenMode.LIBRARY_COMPANION -> CompanionContent(focus.key, focus.direction)
                else -> CompanionContent(null, 0)
            }
            // A game's achievements open over the pages; they close when the game or the page changes.
            var sheet by remember { mutableStateOf<GameId?>(null) }
            val targetKey = (content.target as? GameCard)?.id ?: content.target
            LaunchedEffect(targetKey) { sheet = null }
            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { if (it != 0) sheet = null } }
            val hero = companionHero(store, systems, content.target)
            // The main screen's own room (its scene, or the picture the theme uses) under everything,
            // so both screens read as one device; art for what is shown fades in over it.
            if (prefs.display.companionFollowsBackground) {
                AmbientBackground(
                    if (spec.background == io.github.matiyaaa.fuse.model.BackgroundStyle.HERO) io.github.matiyaaa.fuse.model.BackgroundStyle.SOLID else spec.background,
                    hero?.accent ?: Fuse.colors.accent,
                    Modifier.fillMaxSize(),
                    ambient = spec.ambient,
                )
                spec.wallpaper?.let { WallpaperLayer(it, Modifier.fillMaxSize()) }
            }
            HeroBackdrop(hero, Modifier.fillMaxSize(), dim = 0.25f, gradient = 0.75f, settleMs = 60)
            // Status and controls sit on a deeper shade, so their cards read over any art.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }
                    .background(Fuse.colors.ink.copy(alpha = 0.6f)),
            )
            val showsNumbers = prefs.display.companionShowsPerformance && sheet == null
            // The performance card sits at the top of the first page; what the page shows moves
            // down by its height, so the card never covers the game's name.
            var numbersHeight by remember { mutableStateOf(0) }
            val numbersRoom = with(androidx.compose.ui.platform.LocalDensity.current) { if (showsNumbers) numbersHeight.toDp() + Space.s else 0.dp }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = sheet == null) { page ->
                when (page) {
                    0 -> Box(Modifier.fillMaxSize().padding(top = numbersRoom)) { SpotlightPage(store, content, home.playtime.currentSince, systems, time) { sheet = it } }
                    1 -> StatusPage(store, platform, status)
                    else -> ControlsPage(store, platform, onHide)
                }
            }
            if (showsNumbers && pager.currentPage == 0) {
                val metrics by platform.performance.collectAsState()
                io.github.matiyaaa.fuse.ui.shell.components.PerformanceOverlay(
                    metrics,
                    Modifier.align(Alignment.TopStart).padding(start = Space.l, top = CompanionTopBar).onSizeChanged { numbersHeight = it.height },
                )
            }
            sheet?.let { AchievementsSheet(store, it) }
            // The top line: the page's title (or a close button over the achievements) and the status.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(CompanionTopBar).padding(start = Space.l, end = Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (sheet != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(end = Space.xs).offset(x = (-6).dp)) { CompanionRoundButton(FuseIcons.Close) { sheet = null } }
                            FText("Achievements", Fuse.type.titleSmall, maxLines = 1)
                        }
                    } else {
                        // Titles cross-fade with the swipe; the first page has none, its art speaks.
                        companionPages.forEachIndexed { i, title ->
                            if (i > 0) {
                                FText(
                                    title, Fuse.type.titleSmall, maxLines = 1,
                                    modifier = Modifier.graphicsLayer { alpha = (1f - abs(pager.currentPage + pager.currentPageOffsetFraction - i)).coerceIn(0f, 1f) },
                                )
                            }
                        }
                    }
                }
                StatusCluster(status, time, showWifi = prefs.showWifi, showBluetooth = prefs.showBluetooth)
            }
            if (sheet == null) {
                PageDots(
                    count = companionPages.size,
                    current = pager.currentPage,
                    onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    labels = companionPages,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s),
                )
            }
            // Screen off: black until touched, saying so for a moment.
            if (CompanionControls.screenOff) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) { detectTapGestures { CompanionControls.screenOff = false } },
                ) {
                    var hint by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(2_500)
                        hint = false
                    }
                    Appear(hint, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut(Fuse.motion.fade(Durations.DELIBERATE))) {
                        FText("Tap to wake", Fuse.type.caption, color = Fuse.colors.textFaint)
                    }
                }
            }
        }
    }
}

/** The first page: what the main screen has in focus, sliding the way it moved. */
@Composable
private fun SpotlightPage(
    store: FuseStore,
    content: CompanionContent,
    since: Long?,
    systems: List<PlatformCard>,
    time: String,
    onAchievements: (GameId) -> Unit,
) {
    val motion = Fuse.motion
    Swap(
        targetState = content,
        transitionSpec = {
            val dir = targetState.direction
            val enter = fadeIn(motion.fade(Durations.SLOW)) + scaleIn(motion.tween(Durations.SLOW, Curves.Enter), initialScale = if (motion.reduced) 1f else 0.97f)
            val exit = fadeOut(motion.fade(Durations.FAST))
            if (dir == 0 || motion.reduced) {
                enter togetherWith exit
            } else {
                val shift = (motion.slideFraction * 2.5f).coerceAtMost(0.2f)
                (slideInHorizontally(motion.tween(Durations.SLOW, Curves.Enter)) { (it * shift * dir).toInt() } + enter) togetherWith
                    (slideOutHorizontally(motion.tween(Durations.BASE, Curves.Exit)) { (-it * shift * dir).toInt() } + exit)
            }
        },
        contentKey = { (it.target as? GameCard)?.id ?: it.target },
        label = "companion",
    ) { c ->
        when (val target = c.target) {
            is GameCard -> NowPlaying(store, target, since, onAchievements)
            is GameId -> FocusedGame(store, target, onAchievements)
            is PlatformId -> FocusedPlatform(systems.firstOrNull { it.platform.id == target })
            is CollectionId -> FocusedCollection(store, target)
            else -> Idle(time, room = store.prefs.collectAsState().value.display.companionFollowsBackground)
        }
    }
}

/** The backdrop for what the companion shows: the game's background art, or the system's. */
@Composable
internal fun companionHero(store: FuseStore, systems: List<PlatformCard>, target: Any?): HeroSource? = when (target) {
    is GameCard -> target.room(systems.firstOrNull { it.platform.id == target.platformId })
    is GameId -> {
        val flow = remember(target) { store.library.game(target) }
        val detail by flow.collectAsState(initial = null)
        detail?.let { d -> gameRoom(target, d.art, d.platform.accent, systems.firstOrNull { it.platform.id == d.platform.id }) }
    }
    is PlatformId -> systems.firstOrNull { it.platform.id == target }?.let(::systemRoom)
    // A collection's room is its first game's.
    is CollectionId -> {
        val flow = remember(target) { store.library.games(GameQuery(collection = target)) }
        val games by flow.collectAsState(initial = emptyList())
        games.firstOrNull()?.let { g -> gameRoom(target, g.art, g.accent, systems.firstOrNull { it.platform.id == g.platformId }) }
    }
    else -> null
}

@Composable
private fun Idle(time: String, room: Boolean) {
    // Opaque, so the last game's art never lingers behind the clock; over the main screen's room
    // only a shade, so the room shows through.
    Column(Modifier.fillMaxSize().background(Fuse.colors.ink.copy(alpha = if (room) 0.3f else 1f)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        FuseMark(Modifier.size(56.dp))
        Spacer(Modifier.height(Space.l))
        FText(time, Fuse.type.numericLarge)
        FText(formatDate(), Fuse.type.body, color = Fuse.colors.textMuted)
    }
}

/**
 * A game: its logo large and centred over its room, and its achievements under it when it has a
 * set, so the second screen reads as the game's poster. Without a logo the title stands in.
 */
@Composable
private fun FocusedGame(store: FuseStore, id: GameId, onAchievements: (GameId) -> Unit) {
    val flow = remember(id) { store.library.game(id) }
    val detail by flow.collectAsState(initial = null)
    val d = detail ?: return
    GameLogo(d.art.logo, d.game.displayTitle) {
        d.achievements?.takeIf { it.total > 0 }?.let { a ->
            Spacer(Modifier.height(Space.xl))
            AchievementBar(a, onOpen = { onAchievements(id) })
        }
    }
}

/**
 * A logo (or a title) in the middle of the page, clear of the top line and the dots, with a soft
 * shade behind it for contrast. [tint] draws it in one colour (system logos are white).
 */
@Composable
private fun GameLogo(logo: Any?, title: String, tint: Color? = null, below: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = Space.xl, end = Space.xl, top = CompanionTopBar, bottom = CompanionDotsBar + Space.s)) {
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
                    tint = tint,
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

/** A system: its logo in white, large and centred over its room, exactly like a game's. */
@Composable
private fun FocusedPlatform(card: PlatformCard?) {
    card ?: return
    GameLogo(card.art.logo, card.platform.name, tint = Color.White)
}

/** The game being played: its logo over its room, how long this session has run, and its achievements. */
@Composable
private fun NowPlaying(store: FuseStore, game: GameCard, since: Long?, onAchievements: (GameId) -> Unit) {
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }
    val flow = remember(game.id) { store.library.game(game.id) }
    val detail by flow.collectAsState(initial = null)
    GameLogo(game.art.logo, game.title) {
        // Clear air between the logo and the session pill, so neither crowds the other.
        Spacer(Modifier.height(Space.xxl))
        val session = since?.let { "  ·  ${playtimeText(((now - it) / 1000).coerceAtLeast(0))}" }.orEmpty()
        Row(
            Modifier.clip(PillShape).background(Fuse.colors.ink.copy(alpha = 0.55f)).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.colors.accent))
            FText("Playing$session", Fuse.type.label, maxLines = 1)
        }
        detail?.achievements?.takeIf { it.total > 0 }?.let { a ->
            Spacer(Modifier.height(Space.l))
            AchievementBar(a, onOpen = { onAchievements(game.id) })
        }
    }
}
