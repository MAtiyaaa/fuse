package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Enter
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.tween

/**
 * What a screen shows when it has nothing to show, or could not load: an [icon] in a soft lit
 * circle, a [title] that says what happened, a [message] that says what to do, and optionally one
 * action. Use [tint] for the icon's colour (the danger colour for errors, the accent sparingly).
 *
 * Controller focus stays with the screen: pass [actionSelected] when the action is the selected
 * item. It rises into place and fades in once when it first appears (a fade only under Reduced
 * motion). [compact] suits panels and second screens.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    tint: Color = Fuse.colors.text,
    actionLabel: String? = null,
    actionSelected: Boolean = false,
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector? = null,
    compact: Boolean = false,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    // In Low Power Mode it is simply there; under Reduced motion it only fades.
    val animate = Fuse.quality.animatedBackground
    val arrive = remember { FuselineValue(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (arrive.value < 1f) arrive.animateTo(1f, motion.tween(Durations.SLOW, Curves.Enter)) }
    val rise = motion.revealRise.value
    val disc = if (compact) 64.dp else 88.dp
    val wash = tint.copy(alpha = if (c.isDark) 0.1f else 0.08f)
    val edge = Color.White.copy(alpha = if (c.isDark) 0.14f else 0.6f)
    val halo = tint.copy(alpha = if (c.isDark) 0.06f else 0.05f)
    val gapUnderDisc = if (compact) Space.m else Space.l
    Layout(
        modifier = modifier.graphicsLayer {
            alpha = arrive.value
            translationY = (1f - arrive.value) * rise * density
        },
        content = {
        Box(
            Modifier
                .layoutId(PART_DISC)
                .size(disc + Space.l)
                .drawWithCache {
                    // A faint outer ring, then the disc: lit from above like every other object.
                    val r = size.minDimension / 2
                    val inner = (disc / 2).toPx()
                    val fill = Brush.verticalGradient(
                        listOf(wash.copy(alpha = wash.alpha * 1.6f), wash),
                        startY = size.height / 2 - inner,
                        endY = size.height / 2 + inner,
                    )
                    val rim = Brush.verticalGradient(
                        0f to edge,
                        0.35f to edge.copy(alpha = 0f),
                        startY = size.height / 2 - inner,
                        endY = size.height / 2 + inner,
                    )
                    val ringStroke = Stroke(1.dp.toPx())
                    onDrawBehind {
                        drawCircle(halo, r - 0.5.dp.toPx(), style = ringStroke)
                        drawCircle(fill, inner)
                        drawCircle(rim, inner - 0.5.dp.toPx(), style = ringStroke)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(icon, size = if (compact) Size.iconL else Size.iconXL, tint = tint.copy(alpha = if (tint == c.text) 0.86f else 1f))
        }
        FText(
            title,
            if (compact) Fuse.type.titleSmall else Fuse.type.title,
            align = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.layoutId(PART_TITLE).widthIn(max = 520.dp).semantics { heading() },
        )
        if (message != null) {
            FText(
                message,
                Fuse.type.body,
                color = c.textMuted,
                align = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.layoutId(PART_MESSAGE).widthIn(max = if (compact) 320.dp else 440.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Box(Modifier.layoutId(PART_ACTION)) {
                FuseButton(actionLabel, selected = actionSelected, onClick = onAction, kind = ButtonKind.PRIMARY, icon = actionIcon)
            }
        }
        },
    ) { measurables, constraints ->
        // Everything at its own height first; then, where the space is shorter than that, the parts
        // that matter least go (the disc, then the message, then the action), so a squeezed empty
        // state is never drawn half outside its space (a disc cut in two at the foot of a panel).
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val parts = measurables.associate { it.layoutId as String to it.measure(loose) }
        val gaps = mapOf(PART_DISC to gapUnderDisc.roundToPx(), PART_MESSAGE to Space.s.roundToPx(), PART_ACTION to Space.xl.roundToPx())
        fun heightOf(shown: List<String>) = shown.sumOf { parts.getValue(it).height } +
            shown.drop(1).sumOf { id -> if (id == PART_TITLE) gaps.getValue(PART_DISC) else gaps.getValue(id) }
        var shown = PARTS_IN_ORDER.filter { it in parts }
        for (drop in DROPPED_FIRST) {
            if (heightOf(shown) <= constraints.maxHeight) break
            shown = shown - drop
        }
        if (heightOf(shown) > constraints.maxHeight) shown = emptyList()
        val width = (shown.maxOfOrNull { parts.getValue(it).width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = heightOf(shown).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = (height - heightOf(shown)) / 2
            shown.forEachIndexed { i, id ->
                if (i > 0) y += if (id == PART_TITLE) gaps.getValue(PART_DISC) else gaps.getValue(id)
                val p = parts.getValue(id)
                p.place((width - p.width) / 2, y)
                y += p.height
            }
        }
    }
}

private const val PART_DISC = "disc"
private const val PART_TITLE = "title"
private const val PART_MESSAGE = "message"
private const val PART_ACTION = "action"

/** The parts, top to bottom. */
private val PARTS_IN_ORDER = listOf(PART_DISC, PART_TITLE, PART_MESSAGE, PART_ACTION)

/** What goes first when the space is short: the title stays longest. */
private val DROPPED_FIRST = listOf(PART_DISC, PART_MESSAGE, PART_ACTION)
