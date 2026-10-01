package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.matiyaaa.fuse.model.ThemePalette

/**
 * Fuse's colour roles. Screens never use raw colours; they pick a role, so a theme can restyle the
 * whole interface consistently.
 *
 * The surfaces form one elevation family, darkest to lightest in a dark theme: [ink] (the room),
 * [surfaceDim] (wells and tracks), [surface] (panels), [surfaceRaised] (panels on panels) and
 * [surfaceOverlay] (dialogs and menus over everything). Pair each with its [Elevation] level.
 */
@Immutable
data class FuseColors(
    /** The room: the darkest layer everything sits in. */
    val ink: Color,
    /** Panels, menus, sheets. */
    val surface: Color,
    /** Panels that sit on panels (selected rows, cards on sheets). */
    val surfaceRaised: Color,
    /** 1dp edges and dividers. */
    val hairline: Color,
    val text: Color,
    val textMuted: Color,
    /** Tertiary text: hints, counts, captions under captions. Still at least 4.5:1 on every surface. */
    val textFaint: Color,
    /** The spark: used sparingly for the primary action, progress and the focus bar. */
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    /** Focus outline and focus bar (colour-independent cues accompany it everywhere). */
    val focus: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Dims content under overlays. */
    val scrim: Color,
    val isDark: Boolean,
    /** A step below [surface]: wells, slider and progress tracks, text fields, sunken areas. */
    val surfaceDim: Color = surface,
    /** The top surface level: dialogs, context menus, the quick menu and other overlays. */
    val surfaceOverlay: Color = surfaceRaised,
    /** Dividers that must hold on bright surfaces too: around inputs, between list sections. */
    val hairlineStrong: Color = hairline,
    /** Laid over an element under the mouse pointer (desktop hover). */
    val hover: Color = Color.Transparent,
    /** Laid over an element while it is pressed. */
    val pressed: Color = Color.Transparent,
    /** Fill of loading placeholders (skeletons). */
    val skeleton: Color = surfaceRaised,
    /** The calm highlight that passes over a skeleton while it loads. */
    val shimmer: Color = Color.Transparent,
    /** Shadow colour of lifted surfaces: black in dark themes, a soft ink in light ones. */
    val shadow: Color = Color.Black,
    /** Text and icons laid directly on artwork (art is always darkened under them). */
    val onArt: Color = Color.White,
    /** Secondary text on artwork. */
    val onArtMuted: Color = Color.White.copy(alpha = 0.78f),
    /** The dark floor laid under text on artwork, at its strongest point. */
    val artScrim: Color = Color.Black.copy(alpha = 0.72f),
) {
    /** A surface tinted slightly toward [tint], for cards that belong to a platform or game. */
    fun tinted(tint: Color, amount: Float = 0.14f): Color = lerp(surface, tint, amount)

    companion object {
        fun from(p: ThemePalette): FuseColors {
            val accent = Color(p.accent)
            val ink = Color(p.background)
            val surface = Color(p.surface)
            val raised = Color(p.surfaceRaised)
            val text = Color(p.textPrimary)
            val muted = Color(p.textSecondary)
            val dark = p.dark
            return FuseColors(
                ink = ink,
                surface = surface,
                surfaceRaised = raised,
                // Bright themes need a touch more: a 12% line all but vanishes on white.
                hairline = text.copy(alpha = if (dark) 0.09f else 0.14f),
                text = text,
                textMuted = muted,
                textFaint = faint(muted, listOf(ink, surface, raised)),
                accent = accent,
                accentSoft = Color(p.accentSoft),
                onAccent = Color(p.onAccent),
                focus = Color(p.focusRing),
                success = Color(p.success ?: 0xFF3DD68C),
                warning = Color(p.warning ?: 0xFFFFB547),
                danger = Color(p.danger ?: 0xFFFF5D6C),
                scrim = ink.copy(alpha = 0.72f),
                isDark = dark,
                surfaceDim = if (dark) lerp(ink, surface, 0.5f) else lerp(ink, text, 0.04f),
                surfaceOverlay = if (dark) lerp(raised, text, 0.035f) else surface,
                hairlineStrong = text.copy(alpha = if (dark) 0.16f else 0.24f),
                hover = text.copy(alpha = if (dark) 0.06f else 0.05f),
                pressed = text.copy(alpha = if (dark) 0.10f else 0.08f),
                skeleton = text.copy(alpha = if (dark) 0.07f else 0.07f),
                shimmer = Color.White.copy(alpha = if (dark) 0.07f else 0.55f),
                shadow = if (dark) Color.Black else lerp(text, Color.Black, 0.4f).copy(alpha = 0.6f),
            )
        }

        /**
         * Tertiary text: the secondary colour faded as far as it can go while still reading at 4.5:1
         * on the room and both panel levels, but never fainter than 62% (the old fixed value) or
         * stronger than 92%, so it stays visibly quieter than muted text.
         */
        private fun faint(muted: Color, on: List<Color>): Color {
            var alpha = 0.62f
            while (alpha < 0.92f) {
                val fg = muted.copy(alpha = alpha)
                if (on.all { contrast(fg.compositeOver(it), it) >= 4.5f }) break
                alpha += 0.02f
            }
            return muted.copy(alpha = alpha.coerceAtMost(0.92f))
        }

        private fun contrast(a: Color, b: Color): Float {
            val la = a.luminance()
            val lb = b.luminance()
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }
    }
}

/** Parses an ARGB Long from the model into a Compose colour. */
fun Long.toColor(): Color = Color(this)
