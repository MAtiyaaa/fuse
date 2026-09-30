package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/** On/off switch. The knob position and a check mark carry the state, not only colour. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Fuse.colors
    val t by animateFloatAsState(if (on) 1f else 0f, Fuse.motion.focusSpring(), label = "toggle")
    val track by animateColorAsState(if (on) c.accent else c.text.copy(alpha = 0.16f), Fuse.motion.tween(Durations.FAST), label = "track")
    Box(
        modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(PillShape)
            .background(if (enabled) track else track.copy(alpha = 0.3f))
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = 18.dp * t)
                .size(22.dp)
                .background(if (on) c.onAccent.takeIf { it.luminanceApprox() > 0.5f } ?: Color.White else c.text.copy(alpha = 0.85f), CircleShape),
        )
    }
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** A thin progress bar. [value] null shows an indeterminate shimmer. */
@Composable
fun ProgressBar(value: Float?, modifier: Modifier = Modifier, color: Color = Fuse.colors.accent, height: Dp = 4.dp) {
    val c = Fuse.colors
    Box(modifier.height(height).clip(PillShape).background(c.text.copy(alpha = 0.12f))) {
        if (value != null) {
            val v by animateFloatAsState(value.coerceIn(0f, 1f), Fuse.motion.tween(Durations.SLOW), label = "progress")
            Box(Modifier.fillMaxHeight().fillMaxWidth(v).clip(PillShape).background(color))
        } else {
            val phase by rememberInfiniteTransition(label = "indet").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase",
            )
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val w = size.width * 0.3f
                val x = -w + (size.width + w) * phase
                drawLine(color, Offset(x, size.height / 2), Offset(x + w, size.height / 2), size.height, StrokeCap.Round)
            }
        }
    }
}

/** Circular progress (achievement completion, downloads). */
@Composable
fun ProgressRing(
    value: Float,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    stroke: Dp = 4.dp,
    color: Color = Fuse.colors.accent,
) {
    val c = Fuse.colors
    val v by animateFloatAsState(value.coerceIn(0f, 1f), Fuse.motion.tween(Durations.DELIBERATE), label = "ring")
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        drawArc(c.text.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(s), topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s))
        drawArc(color, -90f, 360f * v, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s))
    }
}

/** Fuse's loading indicator: an arc chasing around a faint track. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = Fuse.colors.text) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    val sweep by t.animateFloat(40f, 200f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "s")
    Canvas(modifier.size(size)) {
        val s = 2.5.dp.toPx()
        val box = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
        drawArc(color.copy(alpha = 0.14f), 0f, 360f, false, Offset(s / 2, s / 2), box, style = Stroke(s))
        drawArc(color, angle, sweep, false, Offset(s / 2, s / 2), box, style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** Horizontal value slider, stepped by the controller (left/right) or dragged by touch. */
@Composable
fun SliderBar(value: Float, selected: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val v by animateFloatAsState(value.coerceIn(0f, 1f), Fuse.motion.focusSpring(), label = "slider")
    Box(modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(PillShape).background(c.text.copy(alpha = 0.14f)))
        Box(Modifier.fillMaxWidth(v).height(4.dp).clip(PillShape).background(c.accent))
        Box(Modifier.fillMaxWidth(v), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .size(if (selected) 20.dp else 16.dp)
                    .background(Color.White, CircleShape)
                    .then(if (selected) Modifier.border(3.dp, c.accent.copy(alpha = 0.5f), CircleShape) else Modifier),
            )
        }
    }
}

/** Small rounded label: platform names, counts, states. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Fuse.colors.text,
    background: Color = color.copy(alpha = 0.12f),
    selected: Boolean = false,
) {
    val c = Fuse.colors
    Row(
        modifier
            .clip(PillShape)
            .background(if (selected) c.text else background)
            .padding(horizontal = Space.m, vertical = Space.xs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            FuseIcon(icon, size = 14.dp, tint = if (selected) c.ink else color)
            Box(Modifier.width(Space.xs + 2.dp))
        }
        FText(text, Fuse.type.label, color = if (selected) c.ink else color, maxLines = 1)
    }
}

/** A status dot + label, for connection and readiness states (shape differs per state too). */
@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val color = when (ok) { true -> c.success; false -> c.danger; null -> c.textFaint }
    Canvas(modifier.size(10.dp)) {
        when (ok) {
            true -> drawCircle(color)
            false -> drawCircle(color, style = Stroke(2.dp.toPx()))
            null -> drawCircle(color, radius = size.minDimension / 3)
        }
    }
}
