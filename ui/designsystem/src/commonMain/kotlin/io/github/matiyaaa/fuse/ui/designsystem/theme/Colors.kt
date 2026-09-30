package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.matiyaaa.fuse.model.ThemePalette

/**
 * Fuse's colour roles. Screens never use raw colours; they pick a role, so a theme can restyle the
 * whole interface consistently.
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
) {
    /** A surface tinted slightly toward [tint], for cards that belong to a platform or game. */
    fun tinted(tint: Color, amount: Float = 0.14f): Color = lerp(surface, tint, amount)

    companion object {
        fun from(p: ThemePalette): FuseColors {
            val accent = Color(p.accent)
            return FuseColors(
                ink = Color(p.background),
                surface = Color(p.surface),
                surfaceRaised = Color(p.surfaceRaised),
                hairline = Color(p.textPrimary).copy(alpha = if (p.dark) 0.09f else 0.12f),
                text = Color(p.textPrimary),
                textMuted = Color(p.textSecondary),
                textFaint = Color(p.textSecondary).copy(alpha = 0.62f),
                accent = accent,
                accentSoft = Color(p.accentSoft),
                onAccent = Color(p.onAccent),
                focus = Color(p.focusRing),
                success = Color(0xFF3DD68C),
                warning = Color(0xFFFFB547),
                danger = Color(0xFFFF5D6C),
                scrim = Color(p.background).copy(alpha = 0.72f),
                isDark = p.dark,
            )
        }
    }
}

/** Parses an ARGB Long from the model into a Compose colour. */
fun Long.toColor(): Color = Color(this)
