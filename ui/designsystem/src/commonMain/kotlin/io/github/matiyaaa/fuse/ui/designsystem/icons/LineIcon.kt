package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size

/**
 * Builds a 24 x 24 line icon from path data. The stroke is slightly lighter than Lucide's default (1.8
 * instead of 2) to match Fuse's typography weight at handheld sizes.
 */
internal fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()

/** Draws a [FuseIcons] icon tinted with [tint] (defaults to the current text colour). */
@Composable
fun FuseIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = Size.iconM,
    tint: Color = Fuse.colors.text,
) {
    val painter = rememberVectorPainter(icon)
    Box(
        modifier
            .size(size)
            .paint(painter, colorFilter = ColorFilter.tint(tint), contentScale = ContentScale.Fit),
    )
}
