package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * A theme changes more than colour: background renderer, geometry, focus treatment, motion and
 * sound. Presets are original designs; DESIGN_SYSTEM.md notes what inspired each.
 */
@Serializable
data class ThemeSpec(
    val id: String,
    val name: String,
    /** One line shown in the theme picker. */
    val tagline: String,
    val palette: ThemePalette,
    val background: BackgroundStyle,
    val geometry: CornerFamily,
    val focus: FocusStyle,
    val motion: MotionProfile,
    val navigation: NavigationStyle = NavigationStyle.TABS,
    val sound: SoundProfile = SoundProfile.SOFT,
    val glass: GlassSettings = GlassSettings(),
    val crt: CrtSettings = CrtSettings(),
    /** Who made it, for themes added from the community. */
    val author: String? = null,
    /** How strongly and how fast the background glows and moves. */
    val ambient: AmbientSpec = AmbientSpec(),
)

/**
 * The background's character: [intensity] scales its light (0 to 1.5), [speed] its movement
 * (0 holds it still, up to 2), and [secondary] is a second colour some backgrounds blend with the
 * accent (ARGB, null for the background's own).
 */
@Serializable
data class AmbientSpec(
    val intensity: Float = 1f,
    val speed: Float = 1f,
    val secondary: Long? = null,
)

@Serializable
data class ThemePalette(
    val dark: Boolean = true,
    /** ARGB */
    val background: Long,
    val surface: Long,
    val surfaceRaised: Long,
    val accent: Long,
    val accentSoft: Long,
    val onAccent: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val focusRing: Long,
    /** Status colours; null keeps Fuse's own green, amber and red. */
    val success: Long? = null,
    val warning: Long? = null,
    val danger: Long? = null,
)

@Serializable
enum class BackgroundStyle {
    /** The focused game's hero art, softened. */
    HERO,
    /** Slow flowing ribbon of light (original, inspired by console wave menus). */
    WAVE,
    /** Soft moving light fields. */
    AURORA,
    /** Concentric orbit lines. */
    ORBITAL,
    /** Fine grid with a vanishing horizon. */
    GRID,
    /** Clean flat colour. */
    SOLID,
    /** Soft pinstripes under a top light, for bright themes. */
    STRIPES,
    /** A slow field of stars. */
    STARS,
}

@Serializable
enum class CornerFamily { SOFT, ROUND, SHARP, PILL }

@Serializable
enum class FocusStyle {
    /** Lift and scale with a crisp outline ring. */
    RING,
    /** Lift with an under-glow in the accent colour. */
    GLOW,
    /** Scale only with a bar underneath (no colour needed to read it). */
    BAR,
}

@Serializable
enum class NavigationStyle {
    /** Section tabs across the top. */
    TABS,
    /** A cross of sections (horizontal) and items (vertical). */
    CROSSBAR,
}

@Serializable
enum class MotionProfile { REDUCED, MINIMAL, STANDARD, ENHANCED }

@Serializable
enum class SoundProfile { OFF, SOFT, CLICK, CHIME }

@Serializable
data class GlassSettings(
    val enabled: Boolean = false,
    /** Blur radius in dp for glass surfaces. */
    val blur: Float = 24f,
    /** 0..1 */
    val surfaceOpacity: Float = 0.72f,
    val backgroundOpacity: Float = 1f,
    val heroBrightness: Float = 0.85f,
    val heroBlur: Float = 0f,
    val overlayDarkness: Float = 0.55f,
    val gradientStrength: Float = 0.8f,
)

@Serializable
data class CrtSettings(
    val enabled: Boolean = false,
    /** 0..1 for each. Defaults keep text fully readable. */
    val scanlines: Float = 0.35f,
    val bloom: Float = 0.15f,
    val curvature: Float = 0.08f,
    val chromatic: Float = 0.1f,
    val vignette: Float = 0.3f,
)
