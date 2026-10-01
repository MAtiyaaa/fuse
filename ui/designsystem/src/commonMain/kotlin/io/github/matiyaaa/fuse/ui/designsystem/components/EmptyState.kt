package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

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
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, motion.tween(Durations.SLOW, Easings.Enter)) }
    val rise = if (motion.reduced) 0f else 12f
    val disc = if (compact) 64.dp else 88.dp
    val wash = tint.copy(alpha = if (c.isDark) 0.1f else 0.08f)
    val edge = Color.White.copy(alpha = if (c.isDark) 0.14f else 0.6f)
    val halo = tint.copy(alpha = if (c.isDark) 0.06f else 0.05f)
    Column(
        modifier.graphicsLayer {
            alpha = arrive.value
            translationY = (1f - arrive.value) * rise * density
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
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
        Spacer(Modifier.height(if (compact) Space.m else Space.l))
        FText(
            title,
            if (compact) Fuse.type.titleSmall else Fuse.type.title,
            align = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.widthIn(max = 520.dp).semantics { heading() },
        )
        if (message != null) {
            Spacer(Modifier.height(Space.s))
            FText(
                message,
                Fuse.type.body,
                color = c.textMuted,
                align = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.widthIn(max = if (compact) 320.dp else 440.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.xl))
            FuseButton(actionLabel, selected = actionSelected, onClick = onAction, kind = ButtonKind.PRIMARY, icon = actionIcon)
        }
    }
}
