package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.model.Achievement
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.effects.ditheredBackground
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.sync.localOffsetMillis
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The second screen while a game runs, made to be read at a glance and touched. The first screenful
 * is the game: its logo centred, Playing and when it began, this session's time with the week's and
 * the game's in all, its achievements a little under them (touch for the whole list), and Quit where
 * Fuse can close it. Scroll down (a finger on this screen) for its Journal: where you are with it,
 * your rating, and your notes, a notebook of as many pages as you like, kept with this game and your
 * profile on every device ([SessionJournal]). Laid out for a two-screen handheld's lower screen (an
 * AYN Thor's is 496 by 432 dp) and for any second screen, wider or taller.
 */
@Composable
internal fun SessionPage(store: FuseStore, game: GameCard, since: Long?, onAchievements: (GameId) -> Unit) {
    var now by remember { mutableLongStateOf(clockNow()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = clockNow()
            delay(15_000)
        }
    }
    val shared = LocalCompanionDetails.current
    val flow = remember(game.id, shared) { shared?.of(game.id) ?: store.library.game(game.id) }
    val detail by flow.collectAsState(initial = null)
    val play by store.achievements.playing.collectAsState()
    val prefs by store.prefs.collectAsState()
    val session = play.takeIf { it.game == game.id }
    // The set as this session has left it, else as the game's page last read it.
    val set = (session?.set ?: detail?.achievements)?.takeIf { it.total > 0 }
    val unlocked = session?.unlocks.orEmpty()
    val sessionSeconds = since?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0L
    val total = detail?.game?.play?.totalSeconds
    val figures = listOfNotNull(
        (playtimeText(sessionSeconds).takeIf { sessionSeconds >= 60 } ?: "Just now") to "This session",
        detail?.secondsThisWeek?.takeIf { it > 0 }?.let { playtimeText(it) to "This week" },
        total?.takeIf { it > 0 }?.let { playtimeText(it) to "In all" },
    )
    val started = since?.let { "Started at " + clockWords(it, prefs.clock24h) }
    val journal = detail?.game?.journal
    val scope = rememberCoroutineScope()
    var quitting by remember(game.id) { mutableStateOf(false) }
    LaunchedEffect(quitting) {
        if (quitting) {
            delay(QUIT_ARMED_MS)
            quitting = false
        }
    }
    val c = Fuse.colors
    val scroll = rememberScrollState()
    // While a note is being written the page holds still: the keys are at its foot.
    val typing = CompanionTyping.active
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val short = viewport < 560.dp
        val reading = minOf(maxWidth - Space.xl * 2, 600.dp)
        Column(
            // Writing, the finger no longer scrolls it, and it stays where it is: the line being
            // written is kept above the keys by the notebook itself.
            Modifier.fillMaxSize()
                .dragScroll(scroll, enabled = !typing)
                .verticalScroll(scroll, enabled = !typing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The game: the first screenful, everything centred on it.
            Box(Modifier.fillMaxWidth().heightIn(min = viewport), contentAlignment = Alignment.Center) {
                // A soft pool of shade behind it, so it reads over any art.
                Box(
                    Modifier.matchParentSize().padding(vertical = CompanionTopBar)
                        .ditheredBackground(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (c.isDark) 0.42f else 0.58f), Color.Transparent))),
                )
                Column(
                    Modifier.width(reading).padding(top = CompanionTopBar, bottom = CompanionDotsBar + Space.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val logoHeight = (viewport * if (set != null) 0.2f else 0.3f).coerceIn(56.dp, 150.dp)
                    Artwork(
                        game.art.logo, Modifier.height(logoHeight).fillMaxWidth(0.84f),
                        contentScale = ContentScale.Fit, fadeIn = true, fallbackDelayMs = 160,
                        fallback = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                FText(game.title, if (short) Fuse.type.title else Fuse.type.display, maxLines = 2, align = TextAlign.Center, fit = true)
                            }
                        },
                    )
                    Spacer(Modifier.height(Space.m))
                    Row(
                        Modifier.clip(PillShape).background(c.ink.copy(alpha = 0.55f)).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        LivePulse()
                        FText(listOfNotNull("Playing", game.platformShort.takeIf { it.isNotBlank() }, started).joinToString("  \u00B7  "), Fuse.type.label, maxLines = 1)
                    }
                    Spacer(Modifier.height(if (short) Space.m else Space.l))
                    SessionFigures(figures, Modifier.fillMaxWidth())
                    if (set != null) {
                        Spacer(Modifier.height(if (short) Space.m else Space.l))
                        SessionAchievements(set, unlocked, next = if (short) 1 else 3, Modifier.fillMaxWidth()) { onAchievements(game.id) }
                    }
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        if (journal != null) {
                            // Its heading comes to rest under the status line, never behind it.
                            val toJournal = with(densityOf()) { (viewport - CompanionTopBar).roundToPx() }
                            SessionPill(FuseIcons.NotebookPen, "Journal") { scope.launch { scroll.animateScrollTo(scroll.maxValue.coerceAtMost(toJournal)) } }
                        }
                        if (store.library.canQuitPlaying) {
                            SessionPill(FuseIcons.Power, if (quitting) "Tap again to quit" else "Quit", warn = quitting) {
                                if (!quitting) quitting = true else {
                                    quitting = false
                                    scope.launch { store.library.quitPlaying() }
                                }
                            }
                        }
                    }
                }
                // Below: the Journal, a finger's scroll away.
                if (journal != null) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = CompanionDotsBar + Space.xs)
                            .graphicsLayer { alpha = (1f - scroll.value / 120f).coerceIn(0f, 1f) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FuseIcon(FuseIcons.ChevronDown, size = 14.dp, tint = c.textMuted)
                        Spacer(Modifier.width(Space.xs))
                        FText("Scroll for your Journal", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    }
                }
            }
            if (journal != null) {
                SessionJournal(store, game, journal, Modifier.width(reading).padding(bottom = CompanionDotsBar + Space.xxl), scroll)
            }
        }
        // A third of the way down, the Journal has the screen: the page dots step aside.
        val readingFrom = with(densityOf()) { (viewport / 3).roundToPx() }
        LaunchedEffect(scroll, readingFrom) { androidx.compose.runtime.snapshotFlow { scroll.value > readingFrom }.collect { CompanionTyping.reading = it } }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { CompanionTyping.reading = false } }
        // Scrolled, the page passes under the status line: a shade keeps the two apart.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(CompanionTopBar + Space.m)
                .graphicsLayer { alpha = (scroll.value / SHADE_AFTER_PX).coerceIn(0f, 1f) }
                .ditheredBackground(Brush.verticalGradient(0f to c.ink, 0.55f to c.ink.copy(alpha = 0.9f), 1f to Color.Transparent)),
        )
        // The keys for writing a note, over the page's foot while one is being written.
        NotebookKeys(Modifier.align(Alignment.BottomCenter))
    }
}

/** A small dot that breathes while the game runs: the session is live. Still under Reduced motion. */
@Composable
private fun LivePulse() {
    val c = Fuse.colors
    val moving = !Fuse.motion.reduced && Fuse.quality.animatedBackground
    val beat = if (moving) io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock(label = "live").animateFloat(
        0.45f, 1f, io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable(io.github.matiyaaa.fuse.ui.fuseline.tween(1400, easing = io.github.matiyaaa.fuse.ui.fuseline.Curves.Standard), io.github.matiyaaa.fuse.ui.fuseline.RepeatMode.Reverse), label = "livePulse",
    ) else null
    Box(Modifier.size(7.dp).graphicsLayer { alpha = beat?.value ?: 1f }.clip(CircleShape).background(c.accent))
}

@Composable
private fun densityOf() = androidx.compose.ui.platform.LocalDensity.current

/** A round-ended touch button on the session page, red while it waits to be sure. */
@Composable
private fun SessionPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, warn: Boolean = false, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by io.github.matiyaaa.fuse.ui.fuseline.fuselineColor(
        if (warn) c.danger else c.ink.copy(alpha = 0.55f),
        Fuse.motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST), label = "sessionPill",
    )
    val fg = if (warn) Color.White else c.text
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(PillShape)
            .background({ bg })
            .border(1.dp, c.text.copy(alpha = if (warn) 0f else 0.12f), PillShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 18.dp, tint = fg)
        Spacer(Modifier.width(Space.s))
        FText(label, Fuse.type.label, color = fg, maxLines = 1)
    }
}

/** The session's figures in a row of even columns, a hairline between: a value over what it counts. */
@Composable
private fun SessionFigures(figures: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        figures.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.padding(horizontal = Space.m).width(1.dp).fillMaxHeight().background(c.text.copy(alpha = 0.14f)))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // Whole, always: set smaller rather than cut short in a narrow column.
                FText(value, Fuse.type.titleSmall, maxLines = 1, align = TextAlign.Center, fit = true, fitMin = 0.7f)
                FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center, fit = true, fitMin = 0.8f)
            }
        }
    }
}

/**
 * The game's achievements while it runs: the ring and the counts, the badges this session
 * unlocked, and the [next] ones still to unlock in the set's order. Touching it opens the list.
 */
@Composable
internal fun SessionAchievements(
    set: AchievementState,
    unlocked: List<RecentAchievement>,
    next: Int,
    modifier: Modifier = Modifier,
    /** Beside the session's figures: the counts in fewer words. */
    narrow: Boolean = false,
    onOpen: () -> Unit,
) {
    val c = Fuse.colors
    val tint = if (set.mastered || set.earned >= set.total) c.warning else c.accent
    val ahead = remember(set) { set.achievements.filter { !it.earned }.sortedBy { it.displayOrder }.take(next) }
    CompanionCard(modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onOpen), padding = Space.m) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    ProgressRing(set.progress, size = 52.dp, stroke = 5.dp, color = tint)
                    FText("${(set.progress * 100).roundToInt()}%", Fuse.type.label, maxLines = 1)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText("${set.earned} of ${set.total}" + if (narrow) "" else " ${set.source.noun.lowercase()}", Fuse.type.bodyStrong, maxLines = 1)
                    FText(
                        when {
                            set.source.hasPoints && set.points > 0 -> "${set.pointsEarned} of ${set.points} points"
                            narrow -> set.source.noun
                            else -> set.source.label
                        },
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                }
                FuseIcon(FuseIcons.ChevronRight, size = 16.dp, tint = c.textMuted)
            }
            if (unlocked.isNotEmpty()) {
                Spacer(Modifier.height(Space.m))
                FText(if (unlocked.size == 1) "UNLOCKED THIS SESSION" else "${unlocked.size} UNLOCKED THIS SESSION", Fuse.type.overline, color = tint, maxLines = 1)
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    unlocked.takeLast(SHOWN_BADGES).reversed().forEach { Badge(it.achievement.badgeUrl, 40.dp, earned = true, tint = tint) }
                    if (unlocked.size > SHOWN_BADGES) FText("+${unlocked.size - SHOWN_BADGES}", Fuse.type.label, color = c.textMuted, maxLines = 1)
                }
            }
            if (ahead.isNotEmpty()) {
                Spacer(Modifier.height(Space.m))
                FText("NEXT UP", Fuse.type.overline, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    ahead.forEach { NextRow(it) }
                }
            }
        }
    }
}

/** One achievement still to unlock: its badge, dimmed, its name and what it asks, its points. */
@Composable
private fun NextRow(a: Achievement) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Badge(a.badgeLockedUrl.ifBlank { a.badgeUrl }, 36.dp, earned = false, tint = c.textMuted)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(a.title, Fuse.type.label, maxLines = 1)
            if (a.description.isNotBlank()) FText(a.description, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        if (a.points > 0) {
            Spacer(Modifier.width(Space.s))
            FText("${a.points}", Fuse.type.label, color = c.textMuted, maxLines = 1)
        }
    }
}

/** An achievement's badge on a soft tile, a trophy where it has none; dimmed while still locked. */
@Composable
private fun Badge(url: String, size: Dp, earned: Boolean, tint: Color) {
    val c = Fuse.colors
    val shape = SquircleShape.fraction(0.24f)
    Box(
        Modifier.size(size).clip(shape).background(c.text.copy(alpha = 0.06f))
            .then(if (earned) Modifier.border(1.5.dp, tint.copy(alpha = 0.8f), shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Artwork(
            url.ifBlank { null }, Modifier.fillMaxSize().alpha(if (earned) 1f else 0.55f), contentScale = ContentScale.Crop, fadeIn = false,
            fallback = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FuseIcon(FuseIcons.Trophy, size = size * 0.42f, tint = if (earned) tint else c.textMuted) } },
        )
    }
}

/** The time of [at] here, as the clock is set to show it. */
private fun clockWords(at: Long, clock24h: Boolean): String {
    val local = at + localOffsetMillis(at)
    val minutes = local.mod(io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords.DAY_MS) / 60_000
    val h = (minutes / 60).toInt()
    val m = (minutes % 60).toInt().toString().padStart(2, '0')
    return if (clock24h) "$h:$m" else io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords.clock(local)
}

private fun clockNow() = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** The most badges this session's row shows before "+N". */
private const val SHOWN_BADGES = 6

/** How long Quit waits for its second tap before it goes back to asking. */
private const val QUIT_ARMED_MS = 4_000L

/** How far the page scrolls before the shade under the status line is whole. */
private const val SHADE_AFTER_PX = 80f
