package io.github.matiyaaa.fuse.ui.designsystem.theme

import io.github.matiyaaa.fuse.model.AmbientSpec
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
 * Built-in themes. All are original designs; DESIGN_SYSTEM.md notes the console eras that
 * inspired some of them. Names are Fuse's own and never use console trademarks. Each one is also a
 * starting point for community themes (docs/THEMES.md), which name it in `extends`.
 */
object ThemePresets {
    /** The brand: a dark room lit by the game you're on, with an ember accent. */
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

    /** Fuse in a bright room: warm paper, ink outlines, the same ember. */
    val Daylight = ThemeSpec(
        id = "daylight",
        name = "Daylight",
        tagline = "Fuse in a bright room",
        palette = ThemePalette(
            dark = false,
            background = 0xFFF4F2EE, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF9F7F3,
            accent = 0xFFC9431F, accentSoft = 0x26C9431F, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF15171C, textSecondary = 0xFF565C68, focusRing = 0xFF15171C,
            success = 0xFF16935A, warning = 0xFFB86E00, danger = 0xFFD12D3E,
        ),
        background = BackgroundStyle.HERO,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        ambient = AmbientSpec(secondary = 0xFFFFC08A),
    )

    /** Frosted panels over your art, in cold blue and violet light. */
    val Glass = ThemeSpec(
        id = "glass",
        name = "Glass",
        tagline = "Frosted panels over your art",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0A0D13, surface = 0xFF151A24, surfaceRaised = 0xFF1E2532,
            accent = 0xFF8CCBFF, accentSoft = 0x338CCBFF, onAccent = 0xFF04121F,
            textPrimary = 0xFFF1F5FA, textSecondary = 0xFFA6B3C5, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.HERO,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        glass = GlassSettings(enabled = true, blur = 28f, surfaceOpacity = 0.64f),
        ambient = AmbientSpec(secondary = 0xFFB79CFF),
    )

    /** Deep navy, a ribbon of light in two blues, items crossing sections. */
    val Crossbar = ThemeSpec(
        id = "crossbar",
        name = "Crossbar",
        tagline = "Sections across, items down",
        palette = ThemePalette(
            dark = true,
            background = 0xFF071027, surface = 0xFF0F1B3A, surfaceRaised = 0xFF17284D,
            accent = 0xFF9FC3FF, accentSoft = 0x339FC3FF, onAccent = 0xFF061022,
            textPrimary = 0xFFF2F6FF, textSecondary = 0xFFA9B8D6, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.WAVE,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.BAR,
        motion = MotionProfile.STANDARD,
        navigation = NavigationStyle.CROSSBAR,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(intensity = 1.15f, secondary = 0xFFE6F2FF),
    )

    /** Quiet light circling in the dark, over a far field of stars. */
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
        ambient = AmbientSpec(secondary = 0xFF4FD1FF),
    )

    /** Plum dusk, a coral accent and gold ribbons drifting across. */
    val Wave = ThemeSpec(
        id = "wave",
        name = "Wave",
        tagline = "A slow ribbon of light at dusk",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0E0B12, surface = 0xFF19141F, surfaceRaised = 0xFF241D2C,
            accent = 0xFFFF8F7A, accentSoft = 0x33FF8F7A, onAccent = 0xFF220A06,
            textPrimary = 0xFFF7F2F5, textSecondary = 0xFFB2A7B5, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.WAVE,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFFFFC46B),
    )

    /** Bold panels, sharp edges and green northern light. */
    val Blades = ThemeSpec(
        id = "blades",
        name = "Blades",
        tagline = "Bold panels, sharp edges, green light",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0A0F0B, surface = 0xFF131B15, surfaceRaised = 0xFF1C281F,
            accent = 0xFF86DC5C, accentSoft = 0x3386DC5C, onAccent = 0xFF08140A,
            textPrimary = 0xFFF1F6F0, textSecondary = 0xFFA2B0A0, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.AURORA,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CLICK,
        ambient = AmbientSpec(intensity = 1.1f, secondary = 0xFF2BD9A5),
    )

    /** Bright tiles on soft pinstripes, a clear sky blue. */
    val Channels = ThemeSpec(
        id = "channels",
        name = "Channels",
        tagline = "Bright tiles you arrange yourself",
        palette = ThemePalette(
            dark = false,
            background = 0xFFEDF1F5, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF5F8FA,
            accent = 0xFF1779BC, accentSoft = 0x261779BC, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF1A202B, textSecondary = 0xFF56606F, focusRing = 0xFF1471AA,
            success = 0xFF17955C, warning = 0xFFB86E00, danger = 0xFFD12D3E,
        ),
        background = BackgroundStyle.STRIPES,
        geometry = CornerFamily.PILL,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.SOFT,
    )

    /** A glowing tube: scanlines, phosphor green text, an amber spark. */
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
        ambient = AmbientSpec(intensity = 0.8f),
    )

    /** Night sky: drifting stars and a violet nebula. */
    val Starlight = ThemeSpec(
        id = "starlight",
        name = "Starlight",
        tagline = "A slow drift of stars",
        palette = ThemePalette(
            dark = true,
            background = 0xFF05050C, surface = 0xFF0F0F1C, surfaceRaised = 0xFF181828,
            accent = 0xFFB79CFF, accentSoft = 0x33B79CFF, onAccent = 0xFF120A26,
            textPrimary = 0xFFF1EFFA, textSecondary = 0xFFA5A2BD, focusRing = 0xFFFFFFFF,
        ),
        background = BackgroundStyle.STARS,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFF5BC8FF),
    )

    val all: List<ThemeSpec> = listOf(Fuse, Glass, Starlight, Crossbar, Orbital, Wave, Blades, Channels, Crt, Daylight)

    fun byId(id: String?): ThemeSpec = all.firstOrNull { it.id == id } ?: Fuse

    /** The built-in theme with [id], or null. */
    fun find(id: String?): ThemeSpec? = all.firstOrNull { it.id == id }
}
