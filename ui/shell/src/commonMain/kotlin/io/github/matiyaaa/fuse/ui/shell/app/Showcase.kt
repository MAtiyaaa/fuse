package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.ui.designsystem.components.PageDots
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusCluster
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroBackdrop
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.slideOutHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlinx.coroutines.delay

/**
 * True when the menus are on the other screen and this one's pages can leave out what the
 * [ShowcaseApp] above already shows large (the selected game's stage).
 */
val LocalShowcaseElsewhere = compositionLocalOf { false }

/**
 * The main screen in flipped mode, the menus on the touch screen below it, as a 3DS's top screen
 * shows the title chosen below. It is what Fuse has in focus, shown large and with what it means:
 * a game's art over the whole screen, its logo, what it is (system, year, genre, players), a few
 * lines about it, and what you have done with it (time played, last played, achievements). The
 * game being played comes first, with how long this session has run. With nothing in focus it is
 * the clock over the room, with the games played last.
 */
@Composable
fun ShowcaseApp(store: FuseStore, platform: PlatformUi) {
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
        val media = io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaFocus.get(focus.key)
        val target: Any? = playing ?: media?.takeIf { prefs.jellyfin.browsingCompanion != "OFF" } ?: focus.key.takeIf { media == null }
        val direction = if (playing != null) 0 else focus.direction
        val hero = companionHero(store, systems, target)
        Box(Modifier.fillMaxSize().background(Fuse.colors.ink).veiledWhileOpening()) {
            AmbientBackground(
                if (spec.background == io.github.matiyaaa.fuse.model.BackgroundStyle.HERO) io.github.matiyaaa.fuse.model.BackgroundStyle.SOLID else spec.background,
                hero?.accent ?: Fuse.colors.accent,
                Modifier.fillMaxSize(),
                ambient = spec.ambient,
            )
            spec.wallpaper?.let { WallpaperLayer(it, Modifier.fillMaxSize()) }
            HeroBackdrop(hero, Modifier.fillMaxSize(), dim = 0.1f, gradient = 0.85f, settleMs = 60)
            // A shade from the lower left, where the words sit, so they read over any art.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(Fuse.colors.ink.copy(alpha = 0.82f), Fuse.colors.ink.copy(alpha = 0.35f), Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f),
                    ),
                ),
            )
            // Three pages, as on the lower screen: what is chosen (or playing), the device's
            // status, and its controls. A swipe or the dots turn them.
            val pager = rememberPagerState(initialPage = 0) { 3 }
            val pageScope = rememberCoroutineScope()
            LaunchedEffect(pager) { ShowcasePage.current.collect { p -> if (p != null && p != pager.settledPage) pager.animateScrollToPage(p) } }
            // A shade deepens behind Status and Controls so their cards read over any art.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }.background(Fuse.colors.ink.copy(alpha = 0.6f)))
            val player = if (io.github.matiyaaa.fuse.ui.player.FusePlayer.available) io.github.matiyaaa.fuse.ui.player.FusePlayer.session else null
            val playingId = player?.item?.id
            LaunchedEffect(playingId) { if (playingId != null && pager.currentPage != 0) pager.animateScrollToPage(0) }
            // A film here takes every touch (its timeline drags sideways), so the pages hold still.
            val filmShown = player?.item != null && !io.github.matiyaaa.fuse.ui.player.PlayerPlacement.withMenus && pager.currentPage == 0
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !filmShown) { page ->
                when (page) {
                    0 -> Box(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.gutter, vertical = Space.l)) {
                            val compact = maxHeight < 380.dp
                            val motion = Fuse.motion
                            Swap(
                                targetState = ShowcaseContent(target, direction),
                                modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight, bottom = SHOWCASE_DOTS),
                                transitionSpec = {
                                    val dir = targetState.direction
                                    val enter = fadeIn(motion.fade(Durations.SLOW))
                                    val exit = fadeOut(motion.fade(Durations.FAST))
                                    if (dir == 0 || motion.reduced) {
                                        enter togetherWith exit
                                    } else {
                                        (slideInHorizontally(motion.tween(Durations.SLOW, Curves.Enter)) { (it * 0.06f * dir).toInt() } + enter) togetherWith
                                            (slideOutHorizontally(motion.tween(Durations.BASE, Curves.Exit)) { (-it * 0.04f * dir).toInt() } + exit)
                                    }
                                },
                                contentKey = { (it.target as? GameCard)?.id ?: (it.target as? io.github.matiyaaa.fuse.jellyfin.MediaItem)?.id ?: it.target },
                                label = "showcase",
                            ) { content ->
                                when (val t = content.target) {
                                    is GameCard -> ShowcaseGame(store, t.id, playingSince = home.playtime.currentSince, compact = compact)
                                    is GameId -> ShowcaseGame(store, t, playingSince = null, compact = compact)
                                    is PlatformId -> systems.firstOrNull { it.platform.id == t }?.let { ShowcaseSystem(it, compact) }
                                    is CollectionId -> ShowcaseCollection(store, t, compact)
                                    is io.github.matiyaaa.fuse.jellyfin.MediaItem -> ShowcaseMedia(t, compact, minimal = prefs.jellyfin.browsingCompanion == "MINIMAL")
                                    else -> ShowcaseIdle(time, home.recentlyPlayed.ifEmpty { home.continuePlaying }, compact)
                                }
                            }
                        }
                        // Fuse Player: its picture here while the menus and its remote are on the touch
                        // screen; played on the touch screen instead, what is playing, large.
                        if (player?.item != null) {
                            if (!io.github.matiyaaa.fuse.ui.player.PlayerPlacement.withMenus) {
                                // The controller drives the menus below: here the film answers to touch alone.
                                val placement = io.github.matiyaaa.fuse.ui.player.PlayerPlacement
                                io.github.matiyaaa.fuse.ui.player.PlayerTouchPicture(
                                    player, Modifier.fillMaxSize(),
                                    onSwap = if (placement.canSwap) ({ placement.swap() }) else null,
                                    swapLabel = "Play on the touch screen",
                                )
                            } else {
                                io.github.matiyaaa.fuse.ui.player.PlayerNowShowing(player, Modifier.fillMaxSize(), where = "Playing on the touch screen")
                            }
                        }
                    }
                    1 -> Box(Modifier.fillMaxSize().padding(top = Size.hudHeight - CompanionTopBar + Space.s)) { StatusPage(store, platform, status) }
                    else -> Box(Modifier.fillMaxSize().padding(top = Size.hudHeight - CompanionTopBar + Space.s)) { ControlsPage(store, platform) }
                }
            }
            // The top line over every page (but not over a film filling the screen).
            val filmHere = player?.item != null && !io.github.matiyaaa.fuse.ui.player.PlayerPlacement.withMenus && pager.currentPage == 0
            if (!filmHere) {
                // The mark at the start, the page's name in the middle of the screen (cross-fading
                // with the swipe), the status at the end.
                Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l).height(Size.hudHeight - Space.l)) {
                    FuseMark(Modifier.align(Alignment.CenterStart).size(28.dp))
                    listOf("Fuse", "Status", "Controls").forEachIndexed { i, title ->
                        FText(
                            title, Fuse.type.titleSmall, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).graphicsLayer {
                                alpha = (1f - kotlin.math.abs(pager.currentPage + pager.currentPageOffsetFraction - i)).coerceIn(0f, 1f)
                            },
                        )
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) { StatusCluster(status, time, showWifi = prefs.showWifi, showBluetooth = prefs.showBluetooth) }
                }
                PageDots(
                    count = 3,
                    current = pager.currentPage,
                    onSelect = { pageScope.launch { pager.animateScrollToPage(it) } },
                    labels = listOf("Now", "Status", "Controls"),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s),
                )
            }
        }
    }
}

/** Asks the screen above to turn to a page (for tests and screenshots); null leaves it alone. */
internal object ShowcasePage {
    val current = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
}

/** Room kept at the bottom of the first page for the page dots. */
private val SHOWCASE_DOTS = 28.dp

private data class ShowcaseContent(val target: Any?, val direction: Int)

/**
 * A film, show or album from Jellyfin, cinematic: its logo (or name), the facts, how far in it is
 * and a few lines about it, low on the left over its backdrop.
 */
@Composable
private fun ShowcaseMedia(item: io.github.matiyaaa.fuse.jellyfin.MediaItem, compact: Boolean, minimal: Boolean) {
    val c = Fuse.colors
    val title = if (item.type == io.github.matiyaaa.fuse.jellyfin.MediaType.EPISODE) item.seriesName ?: item.name else item.name
    Column(Modifier.fillMaxSize().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom)) {
        val name: @Composable () -> Unit = { FText(title, if (compact) Fuse.type.display else Fuse.type.hero, maxLines = 2) }
        val logo = item.logo?.sized(800)
        if (logo != null) {
            Artwork(logo, Modifier.widthIn(max = 560.dp).fillMaxWidth(0.5f).height(if (compact) 90.dp else 150.dp), contentScale = ContentScale.Fit, focusX = 0f, fallback = name)
        } else {
            name()
        }
        if (minimal) return@Column
        if (item.type == io.github.matiyaaa.fuse.jellyfin.MediaType.EPISODE) {
            FText(listOfNotNull(item.episodeLabel, item.name).joinToString("  ·  "), Fuse.type.bodyStrong, maxLines = 1)
        }
        Fact(io.github.matiyaaa.fuse.ui.shell.jellyfin.mediaFacts(item))
        item.progress?.let { p ->
            Box(Modifier.width(220.dp).height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(p).height(4.dp).background(c.accent))
            }
        }
        item.overview?.let { FText(it, Fuse.type.body, color = c.textMuted, maxLines = if (compact) 2 else 4, modifier = Modifier.widthIn(max = 640.dp)) }
    }
}

/** A game, large: logo, facts, a few lines about it, then its numbers, with its cover at the right. */
@Composable
private fun ShowcaseGame(store: FuseStore, id: GameId, playingSince: Long?, compact: Boolean) {
    val flow = remember(id) { store.library.game(id) }
    val detail by flow.collectAsState(initial = null)
    val d = detail ?: return
    val c = Fuse.colors
    val meta = d.game.metadata
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(playingSince) {
        while (playingSince != null) {
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }
    val glow = Color(d.platform.accent)
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            // The system's colour, glowing softly behind the cover.
            val at = androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.55f)
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.28f), Color.Transparent), center = at, radius = size.width * 0.45f), radius = size.width * 0.45f, center = at)
        },
    ) {
        val cover = d.art.boxart ?: d.art.grid
        val coverH = (maxHeight * 0.78f).coerceAtMost(420.dp)
        // Without box art, Fuse's own drawn cover for it, so the right side is never empty.
        val showCover = maxWidth >= 640.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m)) {
                if (playingSince != null) {
                    Row(
                        Modifier.clip(PillShape).background(c.accent).padding(horizontal = Space.m, vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c.onAccent))
                        FText("Now playing  ·  ${playtimeText(((now - playingSince) / 1000).coerceAtLeast(0))}", Fuse.type.label, color = c.onAccent, maxLines = 1)
                    }
                }
                val title: @Composable () -> Unit = {
                    FText(d.game.displayTitle, if (compact) Fuse.type.display else Fuse.type.hero, maxLines = 2, modifier = Modifier.widthIn(max = 760.dp))
                }
                if (d.art.logo != null) {
                    Artwork(
                        d.art.logo,
                        Modifier.widthIn(max = 520.dp).fillMaxWidth(0.55f).height(if (compact) 96.dp else 150.dp),
                        contentScale = ContentScale.Fit,
                        focusX = 0f,
                        fadeIn = true,
                        fallback = title,
                    )
                } else {
                    title()
                }
                // What it is.
                val facts = listOfNotNull(
                    d.platform.name,
                    meta.releaseYear?.toString(),
                    meta.genres.firstOrNull(),
                    meta.players?.let { p -> if (p == "1") "1 player" else "$p players" },
                    meta.developer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    facts.take(4).forEachIndexed { i, f -> Fact(f, dot = if (i == 0) Color(d.platform.accent) else null) }
                }
                meta.description?.takeIf { it.isNotBlank() && !compact }?.let {
                    FText(it, Fuse.type.body, color = c.text.copy(alpha = 0.82f), maxLines = 3, modifier = Modifier.widthIn(max = 720.dp))
                }
                // What you have done with it.
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
                    val play = d.game.play
                    Numbers(FuseIcons.Clock, if (play.totalSeconds > 0) playtimeText(play.totalSeconds) else "Not played yet", "played")
                    play.lastPlayedAt?.let { Numbers(FuseIcons.History, agoText(it), "last played") }
                    d.achievements?.takeIf { it.total > 0 }?.let { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Ring(a.earned / a.total.toFloat(), 34.dp)
                            Column {
                                FText("${a.earned} of ${a.total}", Fuse.type.bodyStrong, maxLines = 1)
                                FText("achievements", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (showCover) {
                Spacer(Modifier.width(Space.xl))
                Artwork(
                    cover,
                    Modifier
                        .height(coverH)
                        .aspectRatio(0.72f)
                        .shadow(24.dp, RoundedCornerShape(Fuse.geometry.panel))
                        .clip(RoundedCornerShape(Fuse.geometry.panel))
                        .border(Size.stroke, c.text.copy(alpha = 0.12f), RoundedCornerShape(Fuse.geometry.panel)),
                    contentScale = ContentScale.Crop,
                    fadeIn = true,
                    fallback = { GeneratedArt(d.game.displayTitle, Color(d.platform.accent), slot = ArtSlot.BOX) },
                )
            }
        }
    }
}

/** A system: its logo in white, how many games, and the emulator that plays them. */
@Composable
private fun ShowcaseSystem(card: PlatformCard, compact: Boolean) {
    val c = Fuse.colors
    Column(Modifier.fillMaxSize().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom)) {
        val name: @Composable () -> Unit = { FText(card.platform.name, if (compact) Fuse.type.display else Fuse.type.hero, maxLines = 2) }
        if (card.art.logo != null) {
            Artwork(card.art.logo, Modifier.widthIn(max = 520.dp).fillMaxWidth(0.5f).height(if (compact) 90.dp else 140.dp), contentScale = ContentScale.Fit, focusX = 0f, tint = Color.White, fallback = name)
        } else {
            name()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Fact(if (card.gameCount == 1) "1 game" else "${card.gameCount} games", dot = Color(card.platform.accent))
            card.emulatorName?.let { Fact(if (card.emulatorInstalled) "Plays in $it" else "$it is not installed") }
        }
        FText(card.platform.name, Fuse.type.caption, color = c.textFaint, maxLines = 1)
    }
}

/** A collection: its name and how many games, over its first game's room. */
@Composable
private fun ShowcaseCollection(store: FuseStore, id: CollectionId, compact: Boolean) {
    val collections by store.collections.collections.collectAsState()
    val collection = collections.firstOrNull { it.id == id } ?: return
    val flow = remember(id) { store.library.games(GameQuery(collection = id)) }
    val games by flow.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom)) {
        Fact(if (collection.kind == io.github.matiyaaa.fuse.model.CollectionKind.SERIES) "Series" else "Collection")
        FText(collection.name, if (compact) Fuse.type.display else Fuse.type.hero, maxLines = 2)
        Covers(games.take(6), if (compact) 96.dp else 150.dp)
    }
}

/** Nothing in focus: the time over the room, and the games played last. */
@Composable
private fun ShowcaseIdle(time: String, recent: List<GameCard>, compact: Boolean) {
    val c = Fuse.colors
    Column(Modifier.fillMaxSize().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.Bottom)) {
        FText(time, if (compact) Fuse.type.display else Fuse.type.hero, maxLines = 1)
        FText(formatDate(), Fuse.type.title, color = c.textMuted, maxLines = 1)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            FText("PLAYED LAST", Fuse.type.overline, color = c.textMuted, maxLines = 1)
            Covers(recent.take(6), if (compact) 96.dp else 150.dp)
        }
    }
}

@Composable
private fun Covers(games: List<GameCard>, height: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        for (g in games) {
            Artwork(
                g.art.boxart ?: g.art.tile,
                Modifier.height(height).aspectRatio(0.72f).clip(RoundedCornerShape(Fuse.geometry.control)).background(Fuse.colors.text.copy(alpha = 0.08f)),
                contentScale = ContentScale.Crop,
                fallback = { GeneratedArt(g.title, Color(g.accent), slot = ArtSlot.BOX, showText = height >= 120.dp) },
            )
        }
    }
}

@Composable
private fun Fact(text: String, dot: Color? = null) {
    val c = Fuse.colors
    Row(
        Modifier.clip(PillShape).background(c.ink.copy(alpha = 0.45f)).border(Size.stroke, c.text.copy(alpha = 0.14f), PillShape).padding(horizontal = Space.m, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
        FText(text, Fuse.type.label, maxLines = 1)
    }
}

@Composable
private fun Numbers(icon: ImageVector, value: String, label: String) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FuseIcon(icon, size = Size.iconL, tint = c.accent)
        Column {
            FText(value, Fuse.type.bodyStrong, maxLines = 1)
            FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** How much of a set is done, as a ring. */
@Composable
private fun Ring(fraction: Float, size: Dp) {
    val c = Fuse.colors
    Canvas(Modifier.size(size)) {
        val w = size.toPx() * 0.12f
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        drawArc(c.text.copy(alpha = 0.15f), 0f, 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), arcSize, style = Stroke(w))
        drawArc(c.accent, -90f, 360f * fraction.coerceIn(0f, 1f), false, androidx.compose.ui.geometry.Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}
