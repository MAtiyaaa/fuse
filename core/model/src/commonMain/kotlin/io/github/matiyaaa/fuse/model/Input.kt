package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/** Everything the interface responds to. Hardware buttons map to these through an [InputProfile]. */
@Serializable
enum class NavAction {
    UP, DOWN, LEFT, RIGHT,
    /** Confirm / open (A on Xbox layout). */
    SELECT,
    BACK,
    SEARCH,
    /** Game or tile options. */
    CONTEXT,
    /** Console-style quick settings overlay. */
    QUICK_MENU,
    NEXT_SECTION,
    PREVIOUS_SECTION,
    /** Page jump (triggers). */
    PAGE_UP,
    PAGE_DOWN,
    HOME,
    /** Enter/confirm reorder (long press A by default). */
    REORDER,
}

/** Physical buttons, named by position on an Xbox-style pad. Layout swaps are applied in mapping. */
@Serializable
enum class PadButton {
    A, B, X, Y, L1, R1, L2, R2, L3, R3, START, SELECT, MODE,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    KEY_ENTER, KEY_ESCAPE, KEY_BACKSPACE, KEY_TAB, KEY_SPACE, KEY_SLASH, KEY_F, KEY_Q, KEY_E, KEY_M, KEY_HOME,
    KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_PAGE_UP, KEY_PAGE_DOWN,
}

/** Which face-button family's glyphs to show in hints. */
@Serializable
enum class GlyphStyle { XBOX, NINTENDO, PLAYSTATION, KEYBOARD }

@Serializable
data class InputProfile(
    /** Swap A/B and X/Y behaviour (Nintendo layout: confirm is the right face button). */
    val nintendoLayout: Boolean = false,
    val glyphs: GlyphStyle = GlyphStyle.XBOX,
    val autoGlyphs: Boolean = true,
    /** Delay before a held direction starts repeating. */
    val repeatDelayMs: Int = 280,
    /** Interval between repeats once repeating. Speeds up while held. */
    val repeatIntervalMs: Int = 70,
    val repeatAccelerate: Boolean = true,
    /** Analog stick deadzone, 0..1. */
    val stickDeadzone: Float = 0.25f,
    /** Stick deflection that counts as a direction press, 0..1. */
    val navigationThreshold: Float = 0.55f,
    val longPressMs: Int = 550,
    /** 0..1 */
    val vibration: Float = 0.5f,
    /** 0..1 */
    val navigationSoundVolume: Float = 0.6f,
    val soundsEnabled: Boolean = true,
    /** User remaps: button -> action. Empty means the defaults for the layout. */
    val remap: Map<PadButton, NavAction> = emptyMap(),
)
