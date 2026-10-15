package io.github.matiyaaa.fuse.data.settings

import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Settings kept while Fii, Duo or Trio was in use (0.4.2.12 and 0.4.2.13) read as Fuse's own after 0.4.2.14. */
class RetiredThemesTest {
    private val kept = """
        {"version":${AppSettings.CURRENT_VERSION},
         "home":{"layout":{"mode":"CHANNELS","look":"TRIO","beforeFii":"FUSED"}},
         "appearance":{"themeId":"trio-night","unlocked":["fii","trio"],"unlockedNew":["trio"]},
         "sound":{"profile":"TAP","volume":0.4}}
    """.trimIndent()

    @Test
    fun aThemedBoardIsTheFusedBoardWithItsTilesKept() {
        val s = AppSettingsCodec.decode(kept)
        assertEquals(HomeMode.CHANNELS, s.home.layout.mode)
        assertEquals(HomeStyle.FUSED, s.home.layout.style)
    }

    @Test
    fun aRetiredSoundFollowsTheThemeAgainAndTheRestIsKept() {
        val s = AppSettingsCodec.decode(kept)
        assertNull(s.sound.profile)
        assertEquals(0.4f, s.sound.volume)
        // The theme's id is kept as it was; Fuse finds no such theme and shows Fuse (ThemePresets.byId).
        assertEquals("trio-night", s.appearance.themeId)
    }

    @Test
    fun writtenAgainNothingOfTheRetiredThemesIsLeft() {
        val again = AppSettingsCodec.encode(AppSettingsCodec.decode(kept), null)
        for (gone in listOf("\"look\"", "beforeFii", "unlocked", "\"TAP\"")) {
            assertFalse(gone in again, "$gone is still written: $again")
        }
    }
}
