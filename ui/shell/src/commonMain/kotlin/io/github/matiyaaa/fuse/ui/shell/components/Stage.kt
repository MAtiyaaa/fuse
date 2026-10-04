package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Enter
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInVertically
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/**
 * A state worth more than a word in the meta line ("Update", "2 DLC", "File missing"): shown on the
 * stage as a small tag with the same [icon] its tile's mark uses. [warning] tints it as a problem.
 */
@Immutable
data class StageTag(val text: String, val icon: ImageVector? = null, val warning: Boolean = false)

/** What the stage says about the selected thing. */
@Immutable
data class StageInfo(
    val key: Any,
    val title: String,
    val eyebrow: String? = null,
    val logo: Any? = null,
    val meta: List<String> = emptyList(),
    val accent: Long = 0xFFFF6A3D,
    /** States shown as tags after the meta line, in the icons the tiles' marks use. */
    val tags: List<StageTag> = emptyList(),
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
        addAll(extra)
    },
    accent = accent,
    // The tile's marks, told in words.
    tags = marks().filter { it.onStage }.map { StageTag(it.words, it.icon, warning = it.tint == MarkTint.WARNING) },
)

fun PlatformCard.stage(): StageInfo = StageInfo(
    key = platform.id,
    title = platform.name,
    eyebrow = platform.manufacturer?.uppercase(),
    logo = art.logo,
    meta = buildList {
        add(gamesText(gameCount))
        add(emulatorName ?: "No emulator found")
        platform.releaseYear?.let { add(it.toString()) }
    },
    accent = platform.accent,
)

/**
 * The selected item's name, told big: its logo art when it has one, otherwise its title set in the
 * display face, with a quiet meta line underneath and any [StageInfo.tags] after it. An eyebrow (the
 * system, with a dot in its colour) sits above. Swaps with a short fade and lift (a fade only under
 * Reduced motion). [fold] (0..1) tucks the meta line away, for a page that is giving its height to
 * the grid. With [inlineEyebrow] (short screens) the eyebrow leads the meta line instead of taking a
 * line of its own above the title.
 */
@Composable
fun Stage(
    info: StageInfo?,
    modifier: Modifier = Modifier,
    showLogo: Boolean = true,
    logoHeight: Dp = 104.dp,
    titleStyle: TextStyle = Fuse.type.hero,
    fold: Float = 0f,
    inlineEyebrow: Boolean = false,
) {
    val motion = Fuse.motion
    val c = Fuse.colors
    Swap(
        targetState = info,
        modifier = modifier,
        contentKey = { it?.key },
        transitionSpec = {
            (fadeIn(motion.fade(Durations.BASE)) + slideInVertically(motion.tween(Durations.SLOW, Curves.Enter)) { if (motion.reduced) 0 else it / 10 }) togetherWith
                fadeOut(motion.fade(Durations.INSTANT))
        },
        contentAlignment = Alignment.BottomStart,
        label = "stage",
    ) { s ->
        if (s == null) {
            Spacer(Modifier.height(logoHeight))
            return@Swap
        }
        Column {
            if (s.eyebrow != null && !inlineEyebrow) {
                Row(Modifier.padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.size(Size.dot).background(s.accent.toColor(), CircleShape))
                    FText(s.eyebrow.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                }
            }
            val title: @Composable () -> Unit = {
                FText(s.title, titleStyle, color = c.text, maxLines = if (fold > 0f) 1 else 2, modifier = Modifier.widthIn(max = TITLE_MAX))
            }
            if (showLogo && s.logo != null) {
                Artwork(
                    model = s.logo,
                    modifier = Modifier.heightIn(max = logoHeight).height(logoHeight).fillMaxWidth(LOGO_WIDTH),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 1f,
                    fallback = title,
                )
            } else {
                title()
            }
            val meta = if (inlineEyebrow && s.eyebrow != null) s.copy(meta = listOf(s.eyebrow) + s.meta) else s
            if ((meta.meta.isNotEmpty() || meta.tags.isNotEmpty()) && fold < 1f) {
                MetaLine(meta, Fuse.type.body, Modifier.foldDown(fold).padding(top = Space.s), wrap = true)
            }
        }
    }
}

/**
 * The stage on one line, for layouts that give their height to the art (the Cover grid): the title
 * in the title face, then the meta and tags quietly after it. On a narrow screen the meta and tags
 * take a line of their own under the title, so neither is cut short. Swaps with a quick fade.
 */
@Composable
fun StageLine(info: StageInfo?, modifier: Modifier = Modifier) {
    val motion = Fuse.motion
    val c = Fuse.colors
    BoxWithConstraints(modifier) {
        val stacked = maxWidth < STACKED_LINE
        Swap(
            targetState = info,
            contentKey = { it?.key },
            transitionSpec = { fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) },
            contentAlignment = Alignment.CenterStart,
            label = "stage line",
        ) { s ->
            if (s == null) {
                Spacer(Modifier.height(Size.badge))
                return@Swap
            }
            val meta = s.copy(meta = listOfNotNull(s.eyebrow) + s.meta)
            val hasMeta = meta.meta.isNotEmpty() || meta.tags.isNotEmpty()
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FText(s.title, Fuse.type.title, color = c.text, maxLines = 1)
                    if (hasMeta) MetaLine(meta, Fuse.type.label, Modifier, wrap = true)
                }
                return@Swap
            }
            Row(Modifier.heightIn(min = Size.badge), verticalAlignment = Alignment.CenterVertically) {
                FText(s.title, Fuse.type.title, color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (hasMeta) {
                    Spacer(Modifier.width(Space.m))
                    MetaLine(meta, Fuse.type.label, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/**
 * The meta words joined by quiet dots, then the tags. Numbers keep tabular figures. With [wrap] the
 * tags move to a second line rather than cutting the words short.
 */
@Composable
private fun MetaLine(s: StageInfo, style: TextStyle, modifier: Modifier, wrap: Boolean = false) {
    val c = Fuse.colors
    if (wrap) {
        MetaFlow(modifier) {
            if (s.meta.isNotEmpty()) {
                FText(
                    s.meta.joinToString("  ·  "),
                    style.copy(fontFeatureSettings = "tnum"),
                    color = c.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = Space.xs),
                )
            }
            for (tag in s.tags) StageTagPill(tag)
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (s.meta.isNotEmpty()) {
            FText(
                s.meta.joinToString("  ·  "),
                style.copy(fontFeatureSettings = "tnum"),
                color = c.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        for ((i, tag) in s.tags.withIndex()) {
            Spacer(Modifier.width(if (i == 0 && s.meta.isNotEmpty()) Space.m else Space.s))
            StageTagPill(tag)
        }
    }
}

/**
 * Lays its children along a line and starts a new line whenever the next one would not fit. Each is
 * measured at the whole width, so the meta words always come first and whole, and the tags move
 * down rather than squeezing them. Items on a line are centred on each other.
 */
@Composable
private fun MetaFlow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = Space.s.roundToPx()
        val lineGap = Space.xs.roundToPx()
        val max = constraints.maxWidth
        val items = measurables.map { it.measure(Constraints(maxWidth = max)) }
        val lines = mutableListOf<MutableList<Placeable>>()
        var x = 0
        for (p in items) {
            val fresh = lines.isEmpty() || (max != Constraints.Infinity && x > 0 && x + gap + p.width > max)
            if (fresh) {
                lines.add(mutableListOf())
                x = 0
            } else {
                x += gap
            }
            lines.last().add(p)
            x += p.width
        }
        val widths = lines.map { line -> line.sumOf { it.width } + gap * (line.size - 1).coerceAtLeast(0) }
        val heights = lines.map { line -> line.maxOf { it.height } }
        val width = (widths.maxOrNull() ?: 0).coerceIn(constraints.minWidth, max)
        val height = heights.sum() + lineGap * (lines.size - 1).coerceAtLeast(0)
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            for ((i, line) in lines.withIndex()) {
                var lx = 0
                for (p in line) {
                    p.place(lx, y + (heights[i] - p.height) / 2)
                    lx += p.width + gap
                }
                y += heights[i] + lineGap
            }
        }
    }
}

/** A tag on the stage: its mark's icon and a word, in a quiet pill (a warning one for problems). */
@Composable
private fun StageTagPill(tag: StageTag) {
    val c = Fuse.colors
    val tint = if (tag.warning) c.warning else c.text
    Row(
        Modifier
            .height(Size.badge)
            .clip(PillShape)
            .background(if (tag.warning) c.warning.copy(alpha = TAG_FILL_WARNING) else c.text.copy(alpha = TAG_FILL))
            .padding(start = if (tag.icon != null) Space.s else Space.s + Space.xxs, end = Space.s + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs),
    ) {
        if (tag.icon != null) FuseIcon(tag.icon, size = Size.iconXS, tint = tint)
        FText(tag.text, Fuse.type.label.copy(fontFeatureSettings = "tnum"), color = tint, maxLines = 1)
    }
}

/** Gives [fraction] of the content's height back from the bottom, fading it out ahead of the cut. */
private fun Modifier.foldDown(fraction: Float): Modifier = if (fraction <= 0f) this else this
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val f = fraction.coerceIn(0f, 1f)
        val h = (p.height * (1f - f)).toInt()
        layout(p.width, h) { p.placeWithLayer(0, 0) { alpha = (1f - f * 1.6f).coerceIn(0f, 1f) } }
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

/** Below this width the one-line stage puts its meta and tags under the title. */
private val STACKED_LINE = 600.dp

/** A title never runs wider than this, so a long one wraps into a block instead of a banner. */
private val TITLE_MAX = 720.dp

/** A logo takes at most this share of the stage's width. */
private const val LOGO_WIDTH = 0.42f

/** Fill of a stage tag, and of a warning one. */
private const val TAG_FILL = 0.08f
private const val TAG_FILL_WARNING = 0.14f
