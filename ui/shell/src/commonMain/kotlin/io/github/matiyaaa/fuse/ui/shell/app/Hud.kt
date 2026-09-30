package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusCluster
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun Destination.label(): String = when (this) {
    Destination.HOME -> "Home"
    Destination.LIBRARY -> "Library"
    Destination.SYSTEMS -> "Systems"
    Destination.APPS -> "Apps"
    Destination.CARTRIDGE -> "Cartridge"
}

fun Destination.icon(): ImageVector = when (this) {
    Destination.HOME -> FuseIcons.Home
    Destination.LIBRARY -> FuseIcons.Library
    Destination.SYSTEMS -> FuseIcons.Chip
    Destination.APPS -> FuseIcons.Smartphone
    Destination.CARTRIDGE -> FuseIcons.CloudDownload
}

/**
 * The top line: Fuse's mark and the section tabs on the left, status on the right. The active tab
 * shows its name; the others are icons only, so the line stays calm. When controller focus moves up
 * into the tabs the active one gets an outline, and LB/RB switch sections from anywhere.
 */
@Composable
fun Hud(
    destinations: List<Destination>,
    active: Destination?,
    tabsFocused: Boolean,
    status: SystemStatus,
    clock24h: Boolean,
    showWifi: Boolean,
    showBluetooth: Boolean,
    onSelect: (Destination) -> Unit,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = Space.gutter,
) {
    val time = rememberClockText(clock24h)
    Row(
        modifier.fillMaxWidth().height(Size.hudHeight).padding(horizontal = gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseMark(Modifier.size(22.dp))
        Spacer(Modifier.width(Space.xl))
        if (tabsFocused) {
            ButtonGlyph(HintButton.PREV, size = 18.dp, color = Fuse.colors.textFaint)
            Spacer(Modifier.width(Space.s))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            for (d in destinations) {
                Tab(d, selected = d == active, focused = tabsFocused && d == active, onClick = { onSelect(d) })
            }
        }
        if (tabsFocused) {
            Spacer(Modifier.width(Space.s))
            ButtonGlyph(HintButton.NEXT, size = 18.dp, color = Fuse.colors.textFaint)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(PillShape)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onStatusClick)
                .padding(horizontal = Space.s, vertical = Space.xs),
        ) {
            StatusCluster(status, time, showWifi = showWifi, showBluetooth = showBluetooth)
        }
    }
}

@Composable
private fun Tab(destination: Destination, selected: Boolean, focused: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val tint by animateColorAsState(if (selected) c.text else c.text.copy(alpha = 0.5f), motion.tween(Durations.FAST), label = "tab")
    val bar by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "bar")
    val bg by animateColorAsState(if (focused) c.text.copy(alpha = 0.12f) else Color.Transparent, motion.tween(Durations.FAST), label = "tabbg")
    Row(
        Modifier
            .clip(PillShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .drawBehind {
                if (bar > 0.01f) {
                    val w = 18.dp.toPx() * bar
                    val h = 2.5.dp.toPx()
                    drawRoundRect(
                        c.accent.copy(alpha = bar),
                        topLeft = Offset((size.width - w) / 2, size.height - h - 3.dp.toPx()),
                        size = GSize(w, h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                }
                if (focused) {
                    drawRoundRect(
                        c.focus.copy(alpha = 0.8f),
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = Space.m, vertical = Space.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(destination.icon(), size = Size.iconM, tint = tint)
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(motion.tween(Durations.BASE)) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.tween(Durations.FAST)) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s))
                FText(destination.label(), Fuse.type.bodyStrong, color = tint, maxLines = 1)
            }
        }
    }
}

/**
 * Fuse's mark: a rounded frame with a lit fuse line running into it and a spark at the end.
 * Original artwork, drawn in code.
 */
@Composable
fun FuseMark(modifier: Modifier = Modifier, color: Color = Fuse.colors.text, spark: Color = Fuse.colors.accent) {
    Canvas(modifier) {
        val w = size.width
        val stroke = w * 0.1f
        drawRoundRect(
            color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = GSize(w - stroke, w - stroke),
            cornerRadius = CornerRadius(w * 0.28f),
            style = Stroke(stroke),
        )
        val fuse = Path().apply {
            moveTo(w * 0.28f, w * 0.7f)
            cubicTo(w * 0.42f, w * 0.7f, w * 0.44f, w * 0.34f, w * 0.64f, w * 0.34f)
        }
        drawPath(fuse, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(
            Brush.radialGradient(listOf(spark, spark.copy(alpha = 0f)), center = Offset(w * 0.7f, w * 0.3f), radius = w * 0.22f),
            radius = w * 0.22f,
            center = Offset(w * 0.7f, w * 0.3f),
        )
        drawCircle(spark, radius = w * 0.07f, center = Offset(w * 0.7f, w * 0.3f))
    }
}

/** Current local time, updated on the minute (not every second) to keep the idle home screen idle. */
@Composable
fun rememberClockText(clock24h: Boolean): String {
    var text by remember { mutableStateOf(formatTime(clock24h)) }
    LaunchedEffect(clock24h) {
        while (true) {
            text = formatTime(clock24h)
            val now = Clock.System.now().toEpochMilliseconds()
            delay(60_000 - now % 60_000 + 50)
        }
    }
    return text
}

fun formatTime(clock24h: Boolean): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return if (clock24h) {
        "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    } else {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        "$h:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "AM" else "PM"}"
    }
}

fun formatDate(): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val day = t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$day, $month ${t.day}"
}
