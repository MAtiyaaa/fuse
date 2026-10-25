package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.WeekRecap

/** How this week stands beside the last at the same point, in words, and which way it went (null: about level). */
internal data class WeekChange(val words: String, val up: Boolean?)

/**
 * [w] beside last week up to the same moment; null before anything was played in either. [short]
 * says it in fewer words, for a small card.
 */
internal fun weekChange(w: WeekRecap, short: Boolean = false): WeekChange? {
    if (w.seconds <= 0 && w.lastWeekSoFar <= 0) return null
    val diff = w.seconds - w.lastWeekSoFar
    return when {
        kotlin.math.abs(diff) < LEVEL_SECONDS -> WeekChange(if (short) "Level with last week" else "About the same as this time last week", null)
        diff > 0 -> WeekChange("${playtimeText(diff)} " + if (short) "up on last week" else "more than this time last week", true)
        else -> WeekChange("${playtimeText(-diff)} " + if (short) "down on last week" else "less than this time last week", false)
    }
}

/** The week's change, on one line: a trend mark in its colour and the words. */
@Composable
internal fun WeekChangeLine(change: WeekChange, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val tint = when (change.up) {
        true -> c.success
        false -> c.textMuted
        null -> c.textMuted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(
            when (change.up) {
                true -> FuseIcons.TrendingUp
                false -> FuseIcons.TrendingDown
                null -> FuseIcons.Minus
            },
            size = 14.dp, tint = tint,
        )
        Spacer(Modifier.width(Space.xs))
        FText(change.words, Fuse.type.caption, color = if (change.up == true) c.text else c.textMuted, maxLines = 1)
    }
}

/**
 * The week so far as a card: its time in large figures, how it stands beside last week at the same
 * point, its sessions and achievements, the last seven days as bars, and its most played games,
 * each opening on a touch ([onGame]). [compact] keeps to the figures and the bars.
 */
@Composable
internal fun YourWeekPanel(
    week: WeekRecap,
    days: List<Long>,
    achievements: Int,
    onGame: (GameCard) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = Fuse.colors
    val change = weekChange(week)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(FuseIcons.CalendarClock, size = 14.dp, tint = c.accent)
            Spacer(Modifier.width(Space.xs))
            FText("YOUR WEEK", Fuse.type.overline, color = c.accent, maxLines = 1)
        }
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FText(if (week.seconds > 0) playtimeText(week.seconds) else "Nothing yet", Fuse.type.display.tabular(), maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                if (change != null) WeekChangeLine(change) else FText("Play something and your week fills in here", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                val facts = listOfNotNull(
                    week.sessions.takeIf { it > 0 }?.let { if (it == 1) "1 session" else "$it sessions" },
                    achievements.takeIf { it > 0 }?.let { if (it == 1) "1 achievement" else "$it achievements" },
                )
                if (facts.isNotEmpty()) {
                    Spacer(Modifier.height(Space.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        facts.forEach { Fact(it) }
                    }
                }
            }
            Spacer(Modifier.width(Space.l))
            WeekBars(days.ifEmpty { List(7) { 0L } }, Modifier.width(if (compact) 140.dp else 196.dp).height(if (compact) 64.dp else 92.dp), letters = true)
        }
        if (!compact && week.games.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            FText("MOST PLAYED THIS WEEK", Fuse.type.overline, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                week.games.forEach { (g, s) -> WeekGame(g, s, week.seconds, Modifier.weight(1f)) { onGame(g) } }
                repeat(3 - week.games.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A short fact about the week on a soft pill. */
@Composable
private fun Fact(text: String) {
    val c = Fuse.colors
    FText(
        text, Fuse.type.label, color = c.text, maxLines = 1,
        modifier = Modifier.clip(PillShape).background(c.text.copy(alpha = 0.07f)).padding(horizontal = Space.s + 2.dp, vertical = 3.dp),
    )
}

/** One of the week's games: its art, its name, its time and its share of the week, opening it on a touch. */
@Composable
private fun WeekGame(g: GameCard, seconds: Long, week: Long, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Row(
        modifier.clip(shape).background(c.text.copy(alpha = 0.04f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(g, 44.dp)
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            FText(g.title, Fuse.type.label, maxLines = 1)
            FText(
                playtimeText(seconds) + if (week > 0) "  ·  ${(seconds * 100 / week).coerceIn(1, 100)}%" else "",
                Fuse.type.caption, color = c.textMuted, maxLines = 1,
            )
        }
    }
}

@Composable
private fun Art(g: GameCard, size: Dp) {
    val shape = RoundedCornerShape(Fuse.geometry.control * 0.7f)
    Box(Modifier.size(size).clip(shape)) {
        SquareGameArt(g.art, Modifier.size(size), fallback = { GeneratedArt(g.title, Color(g.accent), Modifier.size(size)) })
    }
}

/** Closer than this to last week counts as about the same. */
private const val LEVEL_SECONDS = 10 * 60L
