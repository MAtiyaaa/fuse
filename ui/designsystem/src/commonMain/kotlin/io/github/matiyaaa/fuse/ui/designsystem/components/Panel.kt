package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * A surface that holds content: menus, sheets, cards. Panels are one family of objects with the
 * tiles: a fill a step above the room, a soft shadow and a lit top edge. [raised] lifts a panel that
 * sits on another panel; inside an [Overlay] a panel takes the overlay level by itself (a lighter
 * fill and a deeper shadow), so dialogs and menus float above the screen they cover.
 *
 * With Glass on, the fill turns translucent so the (blurred) room shows through, the top edge and a
 * faint sheen get brighter, and the shadow goes (under frosted glass it reads as a stain). [shadow]
 * false keeps a panel flat, for panels that sit inside scrolling lists.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Fuse.geometry.panel),
    raised: Boolean = false,
    tint: Color? = null,
    shadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Fuse.colors
    val glass = Fuse.look.glass
    val overlay = LocalOverlaySurface.current && !raised && tint == null
    val base = when {
        tint != null -> c.tinted(tint)
        raised -> c.surfaceRaised
        overlay -> c.overlaySurface()
        else -> c.surface
    }
    val fill = if (glass.enabled) base.copy(alpha = glass.surfaceOpacity) else base
    val elevation = when {
        !shadow || glass.enabled -> 0f
        overlay -> 24f
        raised -> 8f
        else -> 4f
    }
    // White only shows on dark fills; light themes get a firmer white so the edge still catches.
    val edge = when {
        !c.isDark -> 0.7f
        glass.enabled -> 0.22f
        overlay -> 0.15f
        raised -> 0.13f
        else -> 0.11f
    }
    val sheen = when {
        !c.isDark -> 0f
        glass.enabled -> 0.07f
        else -> 0.03f
    }
    val shadowColor = if (c.isDark) Color.Black else c.text.copy(alpha = 0.55f)
    val hairline = c.hairline
    Box(
        modifier
            .graphicsLayer {
                this.shape = shape
                clip = true
                if (elevation > 0f) {
                    shadowElevation = elevation * density
                    spotShadowColor = shadowColor
                    ambientShadowColor = shadowColor.copy(alpha = shadowColor.alpha * 0.5f)
                }
            }
            .background(fill)
            .drawWithCache {
                // A faint sheen over the top of the surface, gone within the first 160 dp.
                val reach = (160.dp.toPx() / size.height.coerceAtLeast(1f)).coerceIn(0.1f, 1f)
                val brush = Brush.verticalGradient(0f to Color.White.copy(alpha = sheen), reach to Color.Transparent)
                onDrawBehind { if (sheen > 0f) drawRect(brush) }
            }
            .litEdge(shape, top = Color.White.copy(alpha = edge), rest = hairline),
    ) {
        val scope = this
        CompositionLocalProvider(LocalOverlaySurface provides false) { scope.content() }
    }
}
