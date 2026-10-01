package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** What the stage says about the selected thing. */
@Immutable
data class StageInfo(
    val key: Any,
    val title: String,
    val eyebrow: String? = null,
    val logo: Any? = null,
    val meta: List<String> = emptyList(),
    val accent: Long = 0xFFFF6A3D,
)

fun GameCard.stage(extra: List<String> = emptyList()): StageInfo = StageInfo(
    key = id,
    title = title,
    eyebrow = platformShort,
    logo = art.logo,
    meta = buildList {
        year?.let { add(it.toString()) }
        if (playSeconds > 0) add(playtimeText(playSeconds))
        lastPlayedAt?.let { add("Played ${agoText(it)}") }
        if (updates > 0) add(if (updates == 1) "Update" else "$updates updates")
        if (dlc > 0) add(if (dlc == 1) "1 DLC" else "$dlc DLC")
        if (discs > 1) add("$discs discs")
        addAll(extra)
    },
    accent = accent,
)

fun PlatformCard.stage(): StageInfo = StageInfo(
    key = platform.id,
    title = platform.name,
    eyebrow = platform.manufacturer?.uppercase(),
    logo = art.logo,
    meta = buildList {
        add("$gameCount ${if (gameCount == 1) "game" else "games"}")
        add(emulatorName ?: "No emulator found")
        platform.releaseYear?.let { add(it.toString()) }
    },
    accent = platform.accent,
)

/**
 * The selected item's name, told big: its logo art when it has one, otherwise its title set in the
 * display face, with a quiet meta line underneath. Swaps with a short fade and lift.
 */
@Composable
fun Stage(
    info: StageInfo?,
    modifier: Modifier = Modifier,
    showLogo: Boolean = true,
    logoHeight: Dp = 104.dp,
    titleStyle: TextStyle = Fuse.type.hero,
) {
    val motion = Fuse.motion
    val c = Fuse.colors
    AnimatedContent(
        targetState = info,
        modifier = modifier,
        contentKey = { it?.key },
        transitionSpec = {
            (fadeIn(motion.fade(Durations.BASE)) + slideInVertically(motion.tween(Durations.SLOW, Easings.Enter)) { if (motion.reduced) 0 else it / 10 }) togetherWith
                fadeOut(motion.fade(Durations.INSTANT))
        },
        contentAlignment = Alignment.BottomStart,
        label = "stage",
    ) { s ->
        if (s == null) {
            Spacer(Modifier.height(logoHeight))
            return@AnimatedContent
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (s.eyebrow != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.size(7.dp).background(s.accent.toColor(), CircleShape))
                    FText(s.eyebrow.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                }
            }
            val title: @Composable () -> Unit = {
                FText(s.title, titleStyle, color = c.text, maxLines = 2, modifier = Modifier.widthIn(max = 720.dp))
            }
            if (showLogo && s.logo != null) {
                Artwork(
                    model = s.logo,
                    modifier = Modifier.heightIn(max = logoHeight).height(logoHeight).fillMaxWidth(0.42f),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 1f,
                    fallback = title,
                )
            } else {
                title()
            }
            if (s.meta.isNotEmpty()) {
                FText(s.meta.joinToString("  ·  "), Fuse.type.body, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

fun playtimeText(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "Under a minute"
        minutes < 60 -> "$minutes min"
        minutes < 600 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "${minutes / 60} h"
    }
}

fun agoText(epochMs: Long, now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): String {
    val minutes = (now - epochMs) / 60_000
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        minutes < 60 * 48 -> "yesterday"
        minutes < 60 * 24 * 14 -> "${minutes / (60 * 24)} days ago"
        minutes < 60 * 24 * 60 -> "${minutes / (60 * 24 * 7)} weeks ago"
        else -> "${minutes / (60 * 24 * 30)} months ago"
    }
}
