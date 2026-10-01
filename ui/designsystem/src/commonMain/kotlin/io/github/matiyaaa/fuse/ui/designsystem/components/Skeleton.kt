package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * A loading placeholder in the shape of what is coming: a quiet block with a calm band of light
 * passing over it. Every skeleton on screen shares one band (it is placed by window position and a
 * shared clock), so a page of them shimmers as one surface rather than flickering separately. The
 * band moves at 30 frames per second at most, and stops (leaving a still block) under Reduced
 * motion and in Low Power Mode.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Radius.s)) {
    val c = Fuse.colors
    val animate = !Fuse.motion.reduced && Fuse.quality.animatedBackground
    val clock = if (animate) rememberShimmerClock() else null
    val fill = c.text.copy(alpha = if (c.isDark) 0.07f else 0.07f)
    val light = Color.White.copy(alpha = if (c.isDark) 0.06f else 0.5f)
    // Where this block sits in the window, read at draw time (no recomposition when it moves).
    val origin = remember { FloatArray(1) }
    Box(
        modifier
            .onGloballyPositioned { origin[0] = it.positionInRoot().x }
            .clip(shape)
            .drawWithCache {
                val band = SHIMMER_BAND.toPx()
                val brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to light,
                    1f to Color.Transparent,
                    startX = 0f,
                    endX = band,
                )
                val travel = SHIMMER_TRAVEL.toPx()
                onDrawBehind {
                    drawRect(fill)
                    if (clock != null) {
                        val phase = (clock.value % SHIMMER_PERIOD_MS).toFloat() / SHIMMER_PERIOD_MS
                        // The band crosses the window in the first part of each period, then rests.
                        val sweep = (phase / 0.7f).coerceAtMost(1f)
                        val x = -band + (travel + band) * sweep - origin[0]
                        if (x < size.width && x + band > 0f) {
                            translate(x, 0f) { drawRect(brush, Offset.Zero, size.copy(width = band)) }
                        }
                    }
                }
            },
    )
}

/**
 * Placeholder lines for text in [style]: [lines] bars at the style's line height, the last one
 * shorter, so a loading paragraph has the same rhythm as the text that replaces it.
 */
@Composable
fun SkeletonText(
    modifier: Modifier = Modifier,
    lines: Int = 2,
    style: TextStyle = Fuse.type.body,
    lastLineFraction: Float = 0.6f,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val bar = with(density) { (style.fontSize * 0.72f).toDp() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(lineHeight - bar)) {
        repeat(lines.coerceAtLeast(1)) { i ->
            val last = i == lines - 1 && lines > 1
            Skeleton(
                Modifier.fillMaxWidth(if (last) lastLineFraction else 1f).height(bar),
                shape = RoundedCornerShape(bar / 2),
            )
        }
    }
}

/**
 * A placeholder shaped like a [MenuRow] (or a list row in general): an icon well, a title line and
 * a shorter detail line, at the row height, so a list can hold its layout while it loads.
 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, icon: Boolean = true, detail: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Size.row).padding(start = 3.dp + Space.m, end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Skeleton(Modifier.size(32.dp), shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f))
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Skeleton(Modifier.fillMaxWidth(0.46f).height(12.dp), shape = RoundedCornerShape(6.dp))
            if (detail) Skeleton(Modifier.fillMaxWidth(0.72f).height(9.dp), shape = RoundedCornerShape(4.5.dp))
        }
    }
}

/** A shared clock for every skeleton's band: frame time, advanced at most 30 times a second. */
@Composable
private fun rememberShimmerClock(): State<Long> = produceState(0L) {
    var last = 0L
    while (true) {
        withFrameMillis { now ->
            if (now - last >= 33L) {
                last = now
                value = now
            }
        }
    }
}

private val SHIMMER_BAND = 220.dp
private val SHIMMER_TRAVEL = 2200.dp
private const val SHIMMER_PERIOD_MS = 2200L
