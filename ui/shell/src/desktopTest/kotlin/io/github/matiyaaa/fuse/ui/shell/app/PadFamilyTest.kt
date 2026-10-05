package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.PadFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PadFamilyTest {
    @Test
    fun namesSayWhichFamily() {
        // Linux device names, SDL controller types and Android device names.
        assertEquals(GlyphStyle.PLAYSTATION, PadFamily.of("Sony Interactive Entertainment DualSense Wireless Controller"))
        assertEquals(GlyphStyle.PLAYSTATION, PadFamily.of("PS4 Controller"))
        assertEquals(GlyphStyle.PLAYSTATION, PadFamily.of("PS5 Controller"))
        assertEquals(GlyphStyle.NINTENDO, PadFamily.of("Nintendo Switch Pro Controller"))
        assertEquals(GlyphStyle.NINTENDO, PadFamily.of("Joy-Con (L/R)"))
        assertEquals(GlyphStyle.XBOX, PadFamily.of("Xbox Wireless Controller"))
        assertEquals(GlyphStyle.XBOX, PadFamily.of("Microsoft X-Box 360 pad"))
        // A handheld's own pad or a third-party one doesn't say: the setting decides.
        assertNull(PadFamily.of("Retroid Pocket Controller"))
        assertNull(PadFamily.of("Generic X-Input Gamepad Wireless"))
        assertNull(PadFamily.of(null))
    }

    @Test
    fun vendorsSayWhichFamily() {
        assertEquals(GlyphStyle.PLAYSTATION, PadFamily.of("Wireless Controller", 0x054C))
        assertEquals(GlyphStyle.NINTENDO, PadFamily.of("Pro Controller", 0x057E))
        assertEquals(GlyphStyle.XBOX, PadFamily.of("Controller", 0x045E))
    }

    @Test
    fun glyphsFollowThePadInHand() {
        assertEquals(GlyphStyle.PLAYSTATION, padGlyphs(GlyphStyle.XBOX, GlyphStyle.PLAYSTATION))
        assertEquals(GlyphStyle.XBOX, padGlyphs(GlyphStyle.PLAYSTATION, GlyphStyle.XBOX))
        // Nintendo stays with the setting, which also says which button confirms.
        assertEquals(GlyphStyle.XBOX, padGlyphs(GlyphStyle.XBOX, GlyphStyle.NINTENDO))
        assertEquals(GlyphStyle.NINTENDO, padGlyphs(GlyphStyle.NINTENDO, GlyphStyle.XBOX))
        assertEquals(GlyphStyle.PLAYSTATION, padGlyphs(GlyphStyle.PLAYSTATION, null))
    }
}
