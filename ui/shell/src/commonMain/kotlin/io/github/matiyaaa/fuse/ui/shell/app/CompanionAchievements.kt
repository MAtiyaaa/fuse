package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.Achievement
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import kotlin.math.roundToInt

/**
 * A game's achievements in one slim bar: a trophy (a badge once mastered), how many are unlocked and
 * the points, and the progress. Tapping it opens the list.
 */
@Composable
internal fun AchievementBar(state: AchievementState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val tint = if (state.mastered) c.warning else c.accent
    val shape = RoundedCornerShape(Radius.l)
    Row(
        modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.ink.copy(alpha = 0.6f))
            .border(1.dp, c.text.copy(alpha = 0.08f), shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onOpen)
            .padding(start = 10.dp, end = Space.m, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            FuseIcon(if (state.mastered) FuseIcons.BadgeCheck else FuseIcons.Trophy, size = 17.dp, tint = tint)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(
                    if (state.mastered) "Mastered" else "${state.earned} of ${state.total}",
                    Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f),
                )
                FText(
                    if (state.source.hasPoints) "${state.pointsEarned} / ${state.points} pts" else state.source.label,
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            ProgressBar(state.progress, Modifier.fillMaxWidth(), color = tint, height = 4.dp)
        }
        Spacer(Modifier.width(Space.s))
        FuseIcon(FuseIcons.ChevronRight, size = 16.dp, tint = c.textMuted)
    }
}

/**
 * Every achievement of a game, over the second screen's pages: a summary with the progress ring,
 * then each achievement with its badge, earned ones lit and first (newest first), the rest in the
 * set's order. The top line shows a close button, since Back is never the second screen's.
 */
@Composable
internal fun AchievementsSheet(store: FuseStore, id: GameId) {
    val flow = remember(id) { store.library.game(id) }
    val detail by flow.collectAsState(initial = null)
    val c = Fuse.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.ink)
            .background(Brush.verticalGradient(listOf(c.surfaceRaised.copy(alpha = 0.55f), Color.Transparent), endY = 600f))
            // Touches land here, never on the page beneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val state = detail?.achievements ?: return@Box
        val list = remember(state) {
            val (earned, locked) = state.achievements.partition { it.earned }
            earned.sortedByDescending { it.earnedHardcoreAt ?: it.earnedAt ?: 0 } + locked.sortedBy { it.displayOrder }
        }
        Column(Modifier.fillMaxSize().padding(top = CompanionTopBar)) {
            AchievementSummary(state, detail?.game?.displayTitle ?: state.title, Modifier.padding(horizontal = Space.l).widthIn(max = 720.dp).fillMaxWidth())
            Spacer(Modifier.height(Space.m))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).fadingEdges(top = 12.dp, bottom = 24.dp),
                contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.xs, bottom = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                items(list, key = { it.id }) { AchievementRow(it, Modifier.widthIn(max = 720.dp)) }
                if (list.isEmpty()) {
                    item {
                        FText("This game's achievements haven't been listed yet.", Fuse.type.body, color = c.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementSummary(state: AchievementState, title: String, modifier: Modifier) {
    val c = Fuse.colors
    val tint = if (state.mastered) c.warning else c.accent
    CompanionCard(modifier, padding = Space.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                ProgressRing(state.progress, size = 54.dp, stroke = 5.dp, color = tint)
                FText("${(state.progress * 100).roundToInt()}%", Fuse.type.label.copy(fontSize = 13.sp), maxLines = 1)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(title, Fuse.type.bodyStrong, maxLines = 1)
                FText(
                    "${state.earned} of ${state.total} unlocked" + if (state.source.hasPoints) "  ·  ${state.pointsEarned} of ${state.points} points" else "  ·  ${state.source.label}",
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
                if (state.matchedByName) FText("Matched by name: unlocking needs a supported ROM version", Fuse.type.caption, color = c.textFaint, maxLines = 1)
            }
            if (state.mastered) {
                Spacer(Modifier.width(Space.s))
                Row(
                    Modifier.clip(PillShape).background(c.warning.copy(alpha = 0.16f)).padding(horizontal = Space.s, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    FuseIcon(FuseIcons.BadgeCheck, size = 13.dp, tint = c.warning)
                    FText("Mastered", Fuse.type.caption, color = c.warning, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AchievementRow(a: Achievement, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (a.earned) c.accent.copy(alpha = 0.09f) else c.text.copy(alpha = 0.04f))
            .padding(horizontal = Space.s, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(SquircleShape.fraction(0.22f)).background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Artwork(
                if (a.earned) a.badgeUrl else a.badgeLockedUrl,
                Modifier.fillMaxSize().alpha(if (a.earned) 1f else 0.6f),
                contentScale = ContentScale.Crop,
                fallback = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FuseIcon(FuseIcons.Trophy, size = 18.dp, tint = if (a.earned) c.accent else c.textMuted)
                    }
                },
            )
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(a.title, Fuse.type.bodyStrong, color = if (a.earned) c.text else c.text.copy(alpha = 0.8f), maxLines = 1)
            FText(a.description, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.s))
        Column(horizontalAlignment = Alignment.End) {
            if (a.earned) FuseIcon(FuseIcons.Check, size = 14.dp, tint = c.accent)
            if (a.points > 0) FText("${a.points}", Fuse.type.label, color = if (a.earned) c.accent else c.textMuted, maxLines = 1)
        }
    }
}
