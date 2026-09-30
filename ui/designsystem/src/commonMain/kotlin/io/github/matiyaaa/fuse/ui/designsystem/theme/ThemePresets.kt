package io.github.matiyaaa.fuse.ui.designsystem.theme

import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.NavigationStyle
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.ThemePalette
import io.github.matiyaaa.fuse.model.ThemeSpec

/**
 * Built-in themes. All are original designs; docs/DESIGN_SYSTEM.md notes the console eras that
 * inspired some of them. Names are Fuse's own and never use console trademarks.
 */
object ThemePresets {
    val Fuse = ThemeSpec(
        id = "fuse",
        name = "Fuse",
        tagline = "Dark room, lit by the game you're on",
        palette = ThemePalette(
            dark = true,
            background = 0xFF07080B, surface = 0xFF111319, surfaceRaised = 0xFF1A1D25,
            accent = 0xFFFF6A3D, accentSoft = 0x33FF6A3D, onAccent = 0xFF1C0A04,
            textPrimary = 0xFFF3F4F6, textSecondary = 0xFFA6ACB8, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.HERO,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.STANDARD,
    )

    val Daylight = ThemeSpec(
        id = "daylight",
        name = "Daylight",
        tagline = "Fuse in a bright room",
        palette = ThemePalette(
            dark = false,
            background = 0xFFF2F1EE, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF7F6F3,
            accent = 0xFFE9522B, accentSoft = 0x26E9522B, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF15171C, textSecondary = 0xFF5B616D, focusRing = 0xFF15171C,
        ),
        background = BackgroundStyle.HERO,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
    )

    val Glass = ThemeSpec(
        id = "glass",
        name = "Glass",
        tagline = "Frosted panels over your art",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0A0D13, surface = 0xFF151A24, surfaceRaised = 0xFF1E2532,
            accent = 0xFF7CC4FF, accentSoft = 0x337CC4FF, onAccent = 0xFF04121F,
            textPrimary = 0xFFF1F5FA, textSecondary = 0xFFA3B0C2, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.HERO,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        glass = GlassSettings(enabled = true),
    )

    val Crossbar = ThemeSpec(
        id = "crossbar",
        name = "Crossbar",
        tagline = "Sections across, items down",
        palette = ThemePalette(
            dark = true,
            background = 0xFF081028, surface = 0xFF101C3C, surfaceRaised = 0xFF18284F,
            accent = 0xFF9FC3FF, accentSoft = 0x339FC3FF, onAccent = 0xFF061022,
            textPrimary = 0xFFF2F6FF, textSecondary = 0xFFA9B8D6, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.WAVE,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.BAR,
        motion = MotionProfile.STANDARD,
        navigation = NavigationStyle.CROSSBAR,
        sound = SoundProfile.CHIME,
    )

    val Orbital = ThemeSpec(
        id = "orbital",
        name = "Orbital",
        tagline = "Quiet light circling in the dark",
        palette = ThemePalette(
            dark = true,
            background = 0xFF04050A, surface = 0xFF0E1019, surfaceRaised = 0xFF171A27,
            accent = 0xFF7C8CFF, accentSoft = 0x337C8CFF, onAccent = 0xFF070A1E,
            textPrimary = 0xFFEEF0FA, textSecondary = 0xFF9EA4BE, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.ORBITAL,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.ENHANCED,
    )

    val Wave = ThemeSpec(
        id = "wave",
        name = "Wave",
        tagline = "A slow ribbon of light",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0B0B11, surface = 0xFF15151E, surfaceRaised = 0xFF1F1F2B,
            accent = 0xFFDDE2EE, accentSoft = 0x26DDE2EE, onAccent = 0xFF0B0B11,
            textPrimary = 0xFFF5F6FA, textSecondary = 0xFFA5A8B6, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.WAVE,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.BAR,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CHIME,
    )

    val Blades = ThemeSpec(
        id = "blades",
        name = "Blades",
        tagline = "Bold panels, sharp edges, green light",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0B100C, surface = 0xFF141C16, surfaceRaised = 0xFF1D2920,
            accent = 0xFF86DC5C, accentSoft = 0x3386DC5C, onAccent = 0xFF08140A,
            textPrimary = 0xFFF1F6F0, textSecondary = 0xFFA2B0A0, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.AURORA,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CLICK,
    )

    val Channels = ThemeSpec(
        id = "channels",
        name = "Channels",
        tagline = "Bright tiles you arrange yourself",
        palette = ThemePalette(
            dark = false,
            background = 0xFFEDF0F3, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF5F7F9,
            accent = 0xFF2B9FDB, accentSoft = 0x262B9FDB, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF1E232D, textSecondary = 0xFF5D6574, focusRing = 0xFF2B9FDB,
        ),
        background = BackgroundStyle.GRID,
        geometry = CornerFamily.PILL,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.SOFT,
    )

    val Crt = ThemeSpec(
        id = "crt",
        name = "CRT",
        tagline = "Scanlines and phosphor glow",
        palette = ThemePalette(
            dark = true,
            background = 0xFF050806, surface = 0xFF0D130F, surfaceRaised = 0xFF142019,
            accent = 0xFFFFB02E, accentSoft = 0x33FFB02E, onAccent = 0xFF1A1002,
            textPrimary = 0xFFEAF5EC, textSecondary = 0xFF9DB3A2, focusRing = 0xFFFFE3A8,
        ),
        background = BackgroundStyle.GRID,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.BAR,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CLICK,
        crt = CrtSettings(enabled = true),
    )

    val all: List<ThemeSpec> = listOf(Fuse, Glass, Crossbar, Orbital, Wave, Blades, Channels, Crt, Daylight)

    fun byId(id: String?): ThemeSpec = all.firstOrNull { it.id == id } ?: Fuse
}
