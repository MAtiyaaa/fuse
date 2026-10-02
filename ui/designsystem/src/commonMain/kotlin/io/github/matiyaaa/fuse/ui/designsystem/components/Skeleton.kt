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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.effects.skeleton
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * A loading placeholder in the shape of what is coming: a quiet block with a calm band of light
 * passing over it now and then. It is [Modifier.skeleton] as a block of its own, so every
 * placeholder on screen, whichever way it was made, shares one band of light that moves in window
 * coordinates and reads as one surface. Still (the block alone) under Reduced and Minimal motion
 * and in Low Power Mode.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Radius.s)) {
    Box(modifier.skeleton(shape))
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
