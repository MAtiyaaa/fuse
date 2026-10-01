package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * A surface that holds content: menus, sheets, cards. With Glass on it becomes translucent so the
 * room shows through; otherwise it is solid, which stays readable over any art.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Fuse.geometry.panel),
    raised: Boolean = false,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Fuse.colors
    val glass = Fuse.look.glass
    val base = when {
        tint != null -> c.tinted(tint)
        raised -> c.surfaceRaised
        else -> c.surface
    }
    val fill = if (glass.enabled) base.copy(alpha = glass.surfaceOpacity) else base
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (c.isDark) 0.035f else 0f), Color.Transparent),
                ),
            )
            // The top edge catches the light, like a tile's, so every surface reads as one family.
            .border(
                1.dp(),
                Brush.verticalGradient(0f to Color.White.copy(alpha = if (c.isDark) 0.13f else 0.5f), 0.3f to c.hairline, 1f to c.hairline),
                shape,
            ),
        content = content,
    )
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
