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
    /**
     * Before 0.0.2 this swapped A/B by keycode. Android handhelds with Nintendo labels already send
     * the Nintendo keycodes, so that swapped them twice. It is now only read to migrate old settings
     * (see [migrated]); [glyphs] says how the buttons are labelled and [swapConfirmBack] swaps keys.
     */
    val nintendoLayout: Boolean = false,
    /** How the face buttons are labelled on the pad, which decides the hint glyphs. */
    val glyphs: GlyphStyle = GlyphStyle.XBOX,
    /**
     * Swap A/B and X/Y behaviour. Only needed when the pad reports its buttons by position rather
     * than by label; "Detect my buttons" sets it from the button the user presses to confirm.
     */
    val swapConfirmBack: Boolean = false,
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
) {
    /** Confirm is the right face button: Nintendo labels without a swap, or other labels with one. */
    val confirmOnRight: Boolean get() = (glyphs == GlyphStyle.NINTENDO) != swapConfirmBack

    /** Settings saved by 0.0.1 with the old Nintendo toggle become Nintendo labels without a swap. */
    fun migrated(): InputProfile =
        if (nintendoLayout) copy(nintendoLayout = false, glyphs = GlyphStyle.NINTENDO) else this
}
