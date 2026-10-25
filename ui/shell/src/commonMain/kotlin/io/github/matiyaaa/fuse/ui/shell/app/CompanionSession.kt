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
import androidx.compose.runtime.remember
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
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.sync.localOffsetMillis
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The second screen while a game runs: its logo, how long this session has run and when it began,
 * its time this week and in all; and, for a game with achievements, how far through the set you
 * are, what this session unlocked and the next few to go for. Touching the achievements opens the
 * whole list. Side by side on a screen wider than tall, one above the other otherwise.
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
    val flow = remember(game.id) { store.library.game(game.id) }
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
        playtimeText(sessionSeconds).takeIf { sessionSeconds >= 60 }.orEmpty().ifEmpty { "Just now" } to "this session",
        detail?.secondsThisWeek?.takeIf { it > 0 }?.let { playtimeText(it) to "this week" },
        total?.takeIf { it > 0 }?.let { playtimeText(it) to "in all" },
    )
    val started = since?.let { "Started at " + clockWords(it, prefs.clock24h) }
    val c = Fuse.colors
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = Space.xl, end = Space.xl, top = CompanionTopBar, bottom = CompanionDotsBar + Space.s)) {
        // A soft pool of shade behind it all, so it reads over any art.
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().fillMaxHeight(0.8f)
                .ditheredBackground(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (c.isDark) 0.4f else 0.55f), Color.Transparent))),
        )
        val wide = maxWidth > maxHeight * 1.1f && set != null
        val short = maxHeight < 560.dp
        val logoHeight = if (wide) minOf(maxHeight * 0.26f, 120.dp) else minOf(maxHeight * (if (set != null) 0.16f else 0.3f), 150.dp)
        val heading: @Composable (Alignment.Horizontal) -> Unit = { align ->
            Column(horizontalAlignment = align) {
                Artwork(
                    game.art.logo, Modifier.height(logoHeight).fillMaxWidth(if (wide) 1f else 0.82f),
                    contentScale = ContentScale.Fit, fadeIn = true,
                    focusX = if (align == Alignment.Start) 0f else 0.5f,
                    fallback = {
                        Box(Modifier.fillMaxSize(), contentAlignment = if (align == Alignment.Start) Alignment.CenterStart else Alignment.Center) {
                            FText(game.title, Fuse.type.display, maxLines = 2, align = if (align == Alignment.Start) TextAlign.Start else TextAlign.Center)
                        }
                    },
                )
                Spacer(Modifier.height(Space.m))
                Row(
                    Modifier.clip(PillShape).background(c.ink.copy(alpha = 0.55f)).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                    FText(listOfNotNull("Playing", game.platformShort.takeIf { it.isNotBlank() }).joinToString("  ·  "), Fuse.type.label, maxLines = 1)
                }
                if (started != null) {
                    Spacer(Modifier.height(Space.xs))
                    FText(started, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
                Spacer(Modifier.height(Space.l))
                // Beside the achievements there is room for two: this session's and the game's in all.
                val shown = if (wide && figures.size > 2) listOf(figures.first(), figures.last()) else figures
                SessionFigures(shown, Modifier.widthIn(max = 520.dp).fillMaxWidth())
            }
        }
        if (wide) {
            Row(Modifier.align(Alignment.Center).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { heading(Alignment.Start) }
                Spacer(Modifier.width(Space.xl))
                SessionAchievements(set!!, unlocked, next = 2, Modifier.weight(1.1f), narrow = true) { onAchievements(game.id) }
            }
        } else {
            Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                heading(Alignment.CenterHorizontally)
                if (set != null) {
                    Spacer(Modifier.height(Space.l))
                    SessionAchievements(set, unlocked, next = if (short) 2 else 3, Modifier.fillMaxWidth()) { onAchievements(game.id) }
                }
            }
        }
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
                FText(value, Fuse.type.titleSmall, maxLines = 1, align = TextAlign.Center)
                FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
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
