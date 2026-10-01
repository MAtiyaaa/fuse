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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
fun SliderBar(
    value: Float,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Makes the bar touchable: a tap or a drag along it sets the value (0..1). */
    onChange: ((Float) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = Fuse.colors
    var dragging by remember { mutableStateOf(false) }
    // Under a finger the knob follows exactly; otherwise it eases to the new value.
    val eased by animateFloatAsState(value.coerceIn(0f, 1f), Fuse.motion.focusSpring(), label = "slider")
    val v = if (dragging) value.coerceIn(0f, 1f) else eased
    val change by rememberUpdatedState(onChange)
    Box(
        modifier
            .heightIn(min = 24.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (onChange == null || !enabled) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        dragging = true
                        change?.invoke((down.position.x / width).coerceIn(0f, 1f))
                        drag(down.id) { moved ->
                            moved.consume()
                            change?.invoke((moved.position.x / width).coerceIn(0f, 1f))
                        }
                        dragging = false
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
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

/**
 * Small rounded label: platform names, counts, states, and tabs or filters. [selected] fills it;
 * [focused] adds the focus ring so controller focus is visible even on the selected chip.
 * [onClick] makes it tappable.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Fuse.colors.text,
    background: Color = color.copy(alpha = 0.12f),
    selected: Boolean = false,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Fuse.colors
    val ring by animateFloatAsState(if (focused) 1f else 0f, Fuse.motion.focusSpring(), label = "chipFocus")
    Row(
        modifier
            .graphicsLayer { val s = 1f + 0.06f * ring; scaleX = s; scaleY = s }
            .drawBehind {
                if (ring > 0.01f) {
                    // The ring sits just outside the chip, so focusing never changes the layout.
                    val gap = 3.dp.toPx()
                    drawRoundRect(
                        c.focus.copy(alpha = ring),
                        topLeft = Offset(-gap, -gap),
                        size = androidx.compose.ui.geometry.Size(size.width + gap * 2, size.height + gap * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + gap),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .clip(PillShape)
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick).semantics { this.selected = focused } else Modifier)
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
