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
 * starting point for community themes (docs/THEMES.md), which name it in `extends`, so an id never
 * changes once a theme has shipped.
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
            background = 0xFF080B12, surface = 0xFF121826, surfaceRaised = 0xFF1B2335,
            accent = 0xFF8FD0FF, accentSoft = 0x338FD0FF, onAccent = 0xFF04131F,
            textPrimary = 0xFFF2F6FB, textSecondary = 0xFFAFBBCD, focusRing = 0xFFFFFFFF,
            success = 0xFF5FE3B5, warning = 0xFFFFC56B, danger = 0xFFFF7A8A,
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
            background = 0xFF061033, surface = 0xFF0D1C45, surfaceRaised = 0xFF15295C,
            accent = 0xFFA9CBFF, accentSoft = 0x33A9CBFF, onAccent = 0xFF07122A,
            textPrimary = 0xFFF3F7FF, textSecondary = 0xFFB0C1E2, focusRing = 0xFFFFFFFF,
            success = 0xFF6BE3B0, warning = 0xFFFFCB6B, danger = 0xFFFF7D8C,
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
            background = 0xFF03040A, surface = 0xFF0C0F1C, surfaceRaised = 0xFF151A2C,
            accent = 0xFF8B9BFF, accentSoft = 0x338B9BFF, onAccent = 0xFF080B22,
            textPrimary = 0xFFEEF0FC, textSecondary = 0xFFA8AFCA, focusRing = 0xFFFFFFFF,
            success = 0xFF62E0C0, warning = 0xFFFFC46E, danger = 0xFFFF7A90,
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
            background = 0xFF100B14, surface = 0xFF1B1422, surfaceRaised = 0xFF261D30,
            accent = 0xFFFF957F, accentSoft = 0x33FF957F, onAccent = 0xFF250B06,
            textPrimary = 0xFFF9F2F5, textSecondary = 0xFFBFB2C2, focusRing = 0xFFFFFFFF,
            success = 0xFF7FE0A8, warning = 0xFFFFC46B, danger = 0xFFFF6F86,
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
            background = 0xFF080E0A, surface = 0xFF111A14, surfaceRaised = 0xFF1A271E,
            accent = 0xFF8FE36A, accentSoft = 0x338FE36A, onAccent = 0xFF07140A,
            textPrimary = 0xFFF1F7EF, textSecondary = 0xFFABBBA8, focusRing = 0xFFFFFFFF,
            success = 0xFF4FE0B6, warning = 0xFFFFC85E, danger = 0xFFFF6B6B,
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
            background = 0xFFEAF0F6, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF4F7FB,
            accent = 0xFF1270B5, accentSoft = 0x261270B5, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF141B26, textSecondary = 0xFF4A5666, focusRing = 0xFF0F66A3,
            success = 0xFF12804F, warning = 0xFF9A5B00, danger = 0xFFC62837,
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
            background = 0xFF040705, surface = 0xFF0B120D, surfaceRaised = 0xFF132017,
            accent = 0xFFFFB534, accentSoft = 0x33FFB534, onAccent = 0xFF1A1002,
            textPrimary = 0xFFE6F6E8, textSecondary = 0xFFA4BEA9, focusRing = 0xFFFFE3A8,
            success = 0xFF6EF0A0, warning = 0xFFFFD166, danger = 0xFFFF6E5E,
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
            background = 0xFF05050D, surface = 0xFF0E0E1E, surfaceRaised = 0xFF18182C,
            accent = 0xFFC0A6FF, accentSoft = 0x33C0A6FF, onAccent = 0xFF140B2B,
            textPrimary = 0xFFF2F0FB, textSecondary = 0xFFAFACC8, focusRing = 0xFFFFFFFF,
            success = 0xFF6DE3C4, warning = 0xFFFFCB7A, danger = 0xFFFF7FA0,
        ),
        background = BackgroundStyle.STARS,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFF5BC8FF),
    )

    /** True black for screens that light each pixel: nothing moves, nothing glows, panels barely lift. */
    val Pitch = ThemeSpec(
        id = "pitch",
        name = "Pitch",
        tagline = "True black, kind to the battery",
        palette = ThemePalette(
            dark = true,
            background = 0xFF000000, surface = 0xFF0C0C0E, surfaceRaised = 0xFF161619,
            accent = 0xFFF2F2F2, accentSoft = 0x26F2F2F2, onAccent = 0xFF0A0A0A,
            textPrimary = 0xFFEDEDEF, textSecondary = 0xFF9E9EA6, focusRing = 0xFFFFFFFF,
            success = 0xFF5FD49A, warning = 0xFFE8B45A, danger = 0xFFF0717E,
        ),
        background = BackgroundStyle.SOLID,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.RING,
        motion = MotionProfile.MINIMAL,
        sound = SoundProfile.SOFT,
        ambient = AmbientSpec(intensity = 0f, speed = 0f),
    )

    /** Twilight indigo, pink petals drifting down and a peach glow where the sun went. */
    val Blossom = ThemeSpec(
        id = "blossom",
        name = "Blossom",
        tagline = "Petals drifting at dusk",
        palette = ThemePalette(
            dark = true,
            background = 0xFF110D1F, surface = 0xFF1B162C, surfaceRaised = 0xFF26203A,
            accent = 0xFFFFA3C7, accentSoft = 0x33FFA3C7, onAccent = 0xFF2B0A1A,
            textPrimary = 0xFFFBF2F7, textSecondary = 0xFFC3B6CE, focusRing = 0xFFFFE3EE,
            success = 0xFF7FE3B8, warning = 0xFFFFCB80, danger = 0xFFFF7C93,
        ),
        background = BackgroundStyle.PETALS,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.ENHANCED,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFFFFB892),
    )

    /** Deep teal water, light rippling across it and rays slanting down from the surface. */
    val Lagoon = ThemeSpec(
        id = "lagoon",
        name = "Lagoon",
        tagline = "Light rippling through clear water",
        palette = ThemePalette(
            dark = true,
            background = 0xFF02131A, surface = 0xFF09212A, surfaceRaised = 0xFF102E39,
            accent = 0xFF4BE8D7, accentSoft = 0x334BE8D7, onAccent = 0xFF02201D,
            textPrimary = 0xFFEDFBFA, textSecondary = 0xFFA2C6C5, focusRing = 0xFFFFFFFF,
            success = 0xFF6FE6A2, warning = 0xFFFFCA6B, danger = 0xFFFF7B85,
        ),
        background = BackgroundStyle.CAUSTICS,
        geometry = CornerFamily.PILL,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFF7FC8FF),
    )

    /** A dark wood after sunset, mist between the trunks and fireflies blinking. */
    val Canopy = ThemeSpec(
        id = "canopy",
        name = "Canopy",
        tagline = "Fireflies in a quiet wood",
        palette = ThemePalette(
            dark = true,
            background = 0xFF050C09, surface = 0xFF0D1813, surfaceRaised = 0xFF16241D,
            accent = 0xFFE6F07E, accentSoft = 0x33E6F07E, onAccent = 0xFF1A1E04,
            textPrimary = 0xFFEFF6ED, textSecondary = 0xFFA9BDB0, focusRing = 0xFFF4FFC9,
            success = 0xFF6FE0A2, warning = 0xFFFFC36B, danger = 0xFFFF7A70,
        ),
        background = BackgroundStyle.FIREFLIES,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.SOFT,
        ambient = AmbientSpec(secondary = 0xFF5FCFA0),
    )

    /** Magenta and cyan: a neon sun going down behind a grid that runs to the horizon. */
    val Sundown = ThemeSpec(
        id = "sundown",
        name = "Sundown",
        tagline = "A neon sun over an endless grid",
        palette = ThemePalette(
            dark = true,
            background = 0xFF0B0716, surface = 0xFF160F26, surfaceRaised = 0xFF211736,
            accent = 0xFFFF4FA3, accentSoft = 0x33FF4FA3, onAccent = 0xFF2A0216,
            textPrimary = 0xFFFBF2FF, textSecondary = 0xFFB9A8CF, focusRing = 0xFF7DF3FF,
        ),
        background = BackgroundStyle.HORIZON,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.RING,
        motion = MotionProfile.ENHANCED,
        sound = SoundProfile.CLICK,
        ambient = AmbientSpec(secondary = 0xFF3EE6FF),
    )

    /** A bright desk: soft pinstripes on paper, ink text and a cool mint accent. */
    val PaperMint = ThemeSpec(
        id = "paper-mint",
        name = "Paper Mint",
        tagline = "A bright desk with a cool green accent",
        palette = ThemePalette(
            dark = false,
            background = 0xFFF3F5F1, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF7F9F5,
            accent = 0xFF11795A, accentSoft = 0x2611795A, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF16201B, textSecondary = 0xFF505C56, focusRing = 0xFF16201B,
            success = 0xFF0F7A4A, warning = 0xFF8F5A00, danger = 0xFFC0303C,
        ),
        background = BackgroundStyle.STRIPES,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.SOFT,
        ambient = AmbientSpec(secondary = 0xFF9ED9C2),
    )

    /** Pearl white, pastel light that blends and shifts, a deep iris accent. */
    val Opal = ThemeSpec(
        id = "opal",
        name = "Opal",
        tagline = "Pearl light that shifts as it settles",
        palette = ThemePalette(
            dark = false,
            background = 0xFFF0EEF6, surface = 0xFFFFFFFF, surfaceRaised = 0xFFF8F6FC,
            accent = 0xFF6246D8, accentSoft = 0x266246D8, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF1B1924, textSecondary = 0xFF524E62, focusRing = 0xFF4B33BF,
            success = 0xFF127A4C, warning = 0xFF975800, danger = 0xFFBE2539,
        ),
        background = BackgroundStyle.MESH,
        geometry = CornerFamily.PILL,
        focus = FocusStyle.RING,
        motion = MotionProfile.ENHANCED,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFF4FD8C4),
    )

    /** Warm sand under a high sun, a deep turquoise accent like a stone in the desert. */
    val Noon = ThemeSpec(
        id = "noon",
        name = "Noon",
        tagline = "Warm dunes under a high sun",
        palette = ThemePalette(
            dark = false,
            background = 0xFFF3E7D3, surface = 0xFFFFFBF4, surfaceRaised = 0xFFFAF3E7,
            accent = 0xFF0D7672, accentSoft = 0x260D7672, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF2B2016, textSecondary = 0xFF63523F, focusRing = 0xFF2B2016,
            success = 0xFF2B7F4A, warning = 0xFF9C5F00, danger = 0xFFC2362B,
        ),
        background = BackgroundStyle.DUNES,
        geometry = CornerFamily.ROUND,
        focus = FocusStyle.RING,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.SOFT,
        ambient = AmbientSpec(secondary = 0xFFE2A46A),
    )

    /** A survey map in graphite on paper: contour lines, every fifth one heavier, and ink for the spark. */
    val Ridge = ThemeSpec(
        id = "ridge",
        name = "Ridge",
        tagline = "Contour lines on a quiet map",
        palette = ThemePalette(
            dark = false,
            background = 0xFFEAEAE4, surface = 0xFFFAFAF6, surfaceRaised = 0xFFF2F2ED,
            accent = 0xFF262A2F, accentSoft = 0x1F262A2F, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF151618, textSecondary = 0xFF505359, focusRing = 0xFF151618,
            success = 0xFF1A7448, warning = 0xFF8A5800, danger = 0xFFB3262E,
        ),
        background = BackgroundStyle.CONTOURS,
        geometry = CornerFamily.SOFT,
        focus = FocusStyle.BAR,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CLICK,
    )

    /** Four shades of olive on a dot-matrix screen: pixel hills, stepping clouds, no glow at all. */
    val Olive = ThemeSpec(
        id = "olive",
        name = "Olive",
        tagline = "Four shades on a dot-matrix screen",
        palette = ThemePalette(
            dark = false,
            background = 0xFFD3DAAA, surface = 0xFFE3E9C4, surfaceRaised = 0xFFEBF0D3,
            accent = 0xFF3A4B22, accentSoft = 0x293A4B22, onAccent = 0xFFE8EDCB,
            textPrimary = 0xFF1D2712, textSecondary = 0xFF44512D, focusRing = 0xFF1D2712,
            success = 0xFF2F6B1F, warning = 0xFF7A5200, danger = 0xFFA3291E,
        ),
        background = BackgroundStyle.LCD,
        geometry = CornerFamily.SHARP,
        focus = FocusStyle.BAR,
        motion = MotionProfile.STANDARD,
        sound = SoundProfile.CLICK,
        ambient = AmbientSpec(secondary = 0xFF8E9E5E),
    )

    /**
     * Fusi's room, pink and cute like a cartoon from the early 2000s: polka dots drifting over a
     * pink sky, puffy clouds, sparkles, and Fusi the white pixel Maltese playing on a mint hill.
     * Plum text and a berry accent keep every word clear on the pink (11:1 and 5:1 at least).
     */
    val Fusi = ThemeSpec(
        id = "fusi",
        name = "Fusi",
        tagline = "Pink, cute, and Fusi the Maltese at play",
        palette = ThemePalette(
            dark = false,
            background = 0xFFFFE4F1, surface = 0xFFFFF6FA, surfaceRaised = 0xFFFFFFFF,
            accent = 0xFFC72370, accentSoft = 0x29C72370, onAccent = 0xFFFFFFFF,
            textPrimary = 0xFF4A1F3D, textSecondary = 0xFF80466C, focusRing = 0xFFC72370,
            success = 0xFF1E8A57, warning = 0xFFA45C00, danger = 0xFFC0263F,
        ),
        background = BackgroundStyle.FUSI,
        geometry = CornerFamily.PILL,
        focus = FocusStyle.GLOW,
        motion = MotionProfile.ENHANCED,
        sound = SoundProfile.CHIME,
        ambient = AmbientSpec(secondary = 0xFFB9E9FF),
    )

    /**
     * Every built-in theme in the order the gallery shows them: Fuse first (the default), then the
     * dark rooms from the plainest to the most decorated, then the bright ones.
     */
    val all: List<ThemeSpec> = listOf(
        Fuse, Glass, Pitch, Starlight, Orbital, Crossbar, Wave, Blossom, Lagoon, Canopy, Blades, Sundown, Crt,
        Daylight, PaperMint, Channels, Opal, Noon, Ridge, Olive, Fusi,
    )

    /** The dark rooms, in gallery order, for a gallery that shows them as a group. */
    val dark: List<ThemeSpec> = all.filter { it.palette.dark }

    /** The bright themes, in gallery order. */
    val bright: List<ThemeSpec> = all.filter { !it.palette.dark }

    fun byId(id: String?): ThemeSpec = all.firstOrNull { it.id == id } ?: Fuse

    /** The built-in theme with [id], or null. */
    fun find(id: String?): ThemeSpec? = all.firstOrNull { it.id == id }
}
