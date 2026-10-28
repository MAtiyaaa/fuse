package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * A rating out of ten as five stars in halves: whole stars filled in [tint], a half star filled to
 * its middle, the rest as faint outlines. Not rated (0) is five faint outlines, so a row of stars
 * always says what it is for. [size] is one star's.
 */
@Composable
fun Stars(
    rating: Int,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Fuse.colors.accent,
    empty: Color = Fuse.colors.text.copy(alpha = if (Fuse.colors.isDark) 0.26f else 0.3f),
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(size * STAR_GAP), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until STARS) {
            val filled = rating - i * 2
            Box(Modifier.size(size)) {
                when {
                    filled >= 2 -> FuseIcon(FuseIcons.StarFilled, size = size, tint = tint)
                    filled == 1 -> {
                        FuseIcon(FuseIcons.Star, size = size, tint = empty)
                        FuseIcon(FuseIcons.StarHalfFilled, size = size, tint = tint)
                    }
                    else -> FuseIcon(FuseIcons.Star, size = size, tint = empty)
                }
            }
        }
    }
}

private const val STARS = 5

/** The space between stars, as a share of one star. */
private const val STAR_GAP = 0.18f
