package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.effects.LocalGlassBackdrop
import io.github.matiyaaa.fuse.ui.designsystem.effects.liquidGlass
import io.github.matiyaaa.fuse.ui.designsystem.effects.liquidGlassOn
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * A surface that holds content: menus, sheets, cards. Panels are one family of objects with the
 * tiles: a fill a step above the room, a soft shadow and a lit top edge. [raised] lifts a panel that
 * sits on another panel; inside an [Overlay] a panel takes the overlay level by itself (a lighter
 * fill and a deeper shadow), so dialogs and menus float above the screen they cover.
 *
 * With Glass on, the fill turns translucent so the (blurred) room shows through, the top edge and a
 * faint sheen get brighter, and the shadow goes (under frosted glass it reads as a stain). A panel
 * floating over the interface (a menu, a sheet, a dialog, a notice) is liquid glass: the screen
 * behind it frosted, bent along its rim and lit around its edge (see [liquidGlass]), under a lighter
 * tint. [shadow] false keeps a panel flat, for panels that sit inside scrolling lists.
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
    val liquid = liquidGlassOn(glass)
    val fill = when {
        // A bright theme's glass is frosted white: thinner, the art behind turns the white muddy.
        liquid && !c.isDark -> base.copy(alpha = maxOf(glass.surfaceOpacity, LIQUID_BRIGHT_MIN))
        liquid -> base.copy(alpha = (glass.surfaceOpacity - LIQUID_CLEARER).coerceIn(LIQUID_MIN, 1f))
        glass.enabled -> base.copy(alpha = glass.surfaceOpacity)
        else -> base
    }
    // One elevation family: the shadow and the strength of the lit edge come from the level the
    // panel is at (Elevation.panel, raised or overlay), the same as every other lifted surface.
    val level = when {
        overlay -> Elevation.overlay
        raised -> Elevation.raised
        else -> Elevation.panel
    }
    // Glass floats on its own light in a dark room; on a bright one it keeps a soft shadow to lift it.
    val elevation = if (!shadow || (glass.enabled && c.isDark)) 0.dp else level.shadow
    // Glass keeps a brighter edge: it is what makes a frosted panel read as glass.
    val edge = if (glass.enabled && c.isDark) GLASS_EDGE else level.edgeAlpha(c.isDark)
    val sheen = when {
        !c.isDark -> 0f
        glass.enabled -> 0.07f
        else -> 0.03f
    }
    val shadowColor = c.shadow
    val hairline = c.hairline
    Box(
        modifier
            .graphicsLayer {
                this.shape = shape
                clip = true
                if (elevation > 0.dp) {
                    shadowElevation = elevation.toPx()
                    spotShadowColor = shadowColor
                    ambientShadowColor = shadowColor.copy(alpha = shadowColor.alpha * 0.5f)
                }
            }
            .then(if (liquid) Modifier.liquidGlass(shape, glass) else Modifier)
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
        // What is inside sits on this pane, not on the screen behind it.
        CompositionLocalProvider(LocalOverlaySurface provides false, LocalPanelFill provides fill, LocalGlassBackdrop provides null) { scope.content() }
    }
}

/**
 * The fill of the [Panel] around a composable, or null outside one. Lists on a panel fade their
 * edges into this colour with a plain gradient, which costs nothing like an offscreen layer that
 * erases the content would.
 */
val LocalPanelFill = staticCompositionLocalOf<Color?> { null }

/** How much clearer liquid glass's tint is than flat glass's: the frosted screen does the rest. */
private const val LIQUID_CLEARER = 0.2f

/** The least white a bright theme's liquid glass keeps. */
private const val LIQUID_BRIGHT_MIN = 0.84f

/** The least tint liquid glass keeps, so words on it always read. */
private const val LIQUID_MIN = 0.36f

/** The lit edge of a glass panel in a dark theme. */
private const val GLASS_EDGE = 0.22f
