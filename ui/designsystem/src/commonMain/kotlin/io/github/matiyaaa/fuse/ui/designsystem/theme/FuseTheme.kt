package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ThemeSpec

/** Corner geometry for the active theme. */
@Immutable
data class FuseGeometry(
    val family: CornerFamily,
    /** Tile corner as a fraction of the tile's short side (continuous corners). */
    val tileCornerFraction: Float,
    val panel: Dp,
    val control: Dp,
) {
    companion object {
        fun of(family: CornerFamily) = when (family) {
            CornerFamily.SOFT -> FuseGeometry(family, 0.2f, Radius.l, Radius.m)
            CornerFamily.ROUND -> FuseGeometry(family, 0.26f, Radius.xl, Radius.l)
            CornerFamily.SHARP -> FuseGeometry(family, 0.06f, 6.dp, 4.dp)
            CornerFamily.PILL -> FuseGeometry(family, 0.3f, Radius.xl, Radius.pill)
        }
    }
}

/** How controller buttons are drawn in hints, and whether confirm is on the right (Nintendo layout). */
@Immutable
data class GlyphConfig(val style: GlyphStyle, val confirmOnRight: Boolean)

/** Everything a Fuse composable may read about the current look. */
@Immutable
data class FuseLook(
    val spec: ThemeSpec,
    val colors: FuseColors,
    val geometry: FuseGeometry,
    val focusStyle: FocusStyle,
    val glass: GlassSettings,
    /** High contrast focus: adds an outline ring to every focus style. */
    val highContrastFocus: Boolean,
)

val LocalFuseLook = staticCompositionLocalOf<FuseLook> { error("FuseTheme missing") }
val LocalFuseType = staticCompositionLocalOf<FuseTypography> { error("FuseTheme missing") }
val LocalFuseMotion = staticCompositionLocalOf { FuseMotion(MotionProfile.STANDARD) }
val LocalRenderQuality = staticCompositionLocalOf {
    RenderQuality.of(PerformanceProfile.BALANCED, null, lowPower = false)
}
val LocalGlyphs = staticCompositionLocalOf { GlyphConfig(GlyphStyle.XBOX, confirmOnRight = false) }

/** Shorthand accessors, like MaterialTheme.colorScheme. */
object Fuse {
    val look: FuseLook @Composable @ReadOnlyComposable get() = LocalFuseLook.current
    val colors: FuseColors @Composable @ReadOnlyComposable get() = LocalFuseLook.current.colors
    val type: FuseTypography @Composable @ReadOnlyComposable get() = LocalFuseType.current
    val motion: FuseMotion @Composable @ReadOnlyComposable get() = LocalFuseMotion.current
    val quality: RenderQuality @Composable @ReadOnlyComposable get() = LocalRenderQuality.current
    val geometry: FuseGeometry @Composable @ReadOnlyComposable get() = LocalFuseLook.current.geometry
    val glyphs: GlyphConfig @Composable @ReadOnlyComposable get() = LocalGlyphs.current
}

/**
 * Root of every Fuse surface (main window, second screen, onboarding). [motion] overrides the
 * theme's own motion profile when the user picked one in Accessibility.
 *
 * With [animateChanges] (the app's root windows), changing the theme, glass or High contrast focus
 * crossfades the whole surface from the old look to the new one over about 300 ms instead of
 * snapping, at the cost of a single recomposition (see [ThemeTransition]). It stays off for theme
 * previews and anything else that is drawn in a fixed theme.
 */
@Composable
fun FuseTheme(
    spec: ThemeSpec = ThemePresets.Fuse,
    motion: MotionProfile? = null,
    quality: RenderQuality = RenderQuality.of(PerformanceProfile.BALANCED, null, lowPower = false),
    glyphs: GlyphConfig = GlyphConfig(GlyphStyle.XBOX, confirmOnRight = false),
    glass: GlassSettings? = null,
    highContrastFocus: Boolean = false,
    animateChanges: Boolean = false,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    if (!animateChanges) {
        FuseThemeLocals(spec, motion, quality, glyphs, glass, highContrastFocus, textScale, content)
        return
    }
    val reduced = (motion ?: spec.motion) == MotionProfile.REDUCED
    ThemeTransition(
        requested = ThemeLookKey(spec, glass, highContrastFocus),
        animate = !reduced && quality.animatedBackground,
    ) { shown ->
        FuseThemeLocals(shown.spec, motion, quality, glyphs, shown.glass, shown.highContrastFocus, textScale, content)
    }
}

@Composable
private fun FuseThemeLocals(
    spec: ThemeSpec,
    motion: MotionProfile?,
    quality: RenderQuality,
    glyphs: GlyphConfig,
    glass: GlassSettings?,
    highContrastFocus: Boolean,
    textScale: Float,
    content: @Composable () -> Unit,
) {
    val colors = remember(spec) { FuseColors.from(spec.palette) }
    val look = remember(spec, glass, highContrastFocus) {
        FuseLook(
            spec = spec,
            colors = colors,
            geometry = FuseGeometry.of(spec.geometry),
            focusStyle = spec.focus,
            glass = glass ?: spec.glass,
            highContrastFocus = highContrastFocus,
        )
    }
    val type = rememberFuseTypography(textScale)
    val motionSpec = remember(motion, spec.motion) { FuseMotion(motion ?: spec.motion) }
    val selection = remember(colors) { TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)) }
    CompositionLocalProvider(
        LocalFuseLook provides look,
        LocalFuseType provides type,
        LocalFuseMotion provides motionSpec,
        LocalRenderQuality provides quality,
        LocalGlyphs provides glyphs,
        LocalTextSelectionColors provides selection,
        // Fuse draws its own focus and press feedback; no ripples.
        LocalIndication provides NoIndication,
        content = content,
    )
}
