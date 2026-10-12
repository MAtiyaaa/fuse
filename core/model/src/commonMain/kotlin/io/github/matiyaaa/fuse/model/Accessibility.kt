package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * Which colours Fuse's states use, for the ways people see colour: [TYPICAL] keeps the theme's own
 * green, amber and red; [RED_GREEN] (protanopia and deuteranopia, the commonest) and [BLUE_YELLOW]
 * (tritanopia) swap them for colours those eyes tell apart, after Okabe and Ito's palette, each also
 * apart in lightness. Every state also says itself in words or a symbol, never by colour alone.
 */
@Serializable
enum class ColorVision(val title: String, val detail: String) {
    TYPICAL("As the theme has them", "Green, amber and red"),
    RED_GREEN("Red and green apart", "Blue, yellow and vermilion, for protanopia and deuteranopia"),
    BLUE_YELLOW("Blue and yellow apart", "Teal, pink and red, for tritanopia"),
}

/**
 * Which way Fuse lays itself out: [AUTOMATIC] follows the system's language (right to left for
 * Arabic, Hebrew, Persian and Urdu), or always [LEFT_TO_RIGHT] or [RIGHT_TO_LEFT]. Mirrored, the
 * D-pad and shoulder buttons move the way the screen reads.
 */
@Serializable
enum class LayoutDirectionChoice(val title: String) {
    AUTOMATIC("Automatic"),
    LEFT_TO_RIGHT("Left to right"),
    RIGHT_TO_LEFT("Right to left"),
}
