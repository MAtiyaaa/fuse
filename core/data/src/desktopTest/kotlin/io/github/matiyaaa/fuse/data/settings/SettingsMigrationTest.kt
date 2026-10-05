package io.github.matiyaaa.fuse.data.settings

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.GlyphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Documents written by 0.0.1 (version 1) read correctly after the 0.0.2 changes. */
class SettingsMigrationTest {
    private fun v1(tabs: String, input: String = "{}", library: String = "{\"cleanDisplayNames\":false}") = """
        {"version":1,"home":{"destinations":$tabs},"input":$input,"library":$library}
    """.trimIndent()

    private val v1Tabs = """[{"destination":"HOME"},{"destination":"LIBRARY"},{"destination":"SYSTEMS"},{"destination":"APPS"},{"destination":"CARTRIDGE"}]"""

    @Test
    fun untouchedTabsTakeTheNewOrder() {
        val s = AppSettingsCodec.decode(v1(v1Tabs))
        assertEquals(AppSettings.CURRENT_VERSION, s.version)
        assertEquals(
            listOf(Destination.HOME, Destination.SYSTEMS, Destination.LIBRARY, Destination.ACHIEVEMENTS, Destination.APPS, Destination.CARTRIDGE),
            s.home.visibleDestinations(),
        )
    }

    @Test
    fun customisedTabsAreKeptAndAchievementsIsAdded() {
        val custom = """[{"destination":"LIBRARY"},{"destination":"HOME"},{"destination":"APPS","visible":false}]"""
        val s = AppSettingsCodec.decode(v1(custom))
        val tabs = s.home.visibleDestinations()
        assertEquals(listOf(Destination.LIBRARY, Destination.HOME), tabs.take(2))
        assertFalse(Destination.APPS in tabs)
        assertTrue(Destination.ACHIEVEMENTS in tabs)
    }

    @Test
    fun cleanNamesBecomeTheDefaultAndRunOnce() {
        val s = AppSettingsCodec.decode(v1(v1Tabs))
        assertTrue(s.library.cleanDisplayNames)
        assertFalse(s.library.cleanedExistingNames)
    }

    @Test
    fun oldNintendoToggleBecomesNintendoLabels() {
        val s = AppSettingsCodec.decode(v1(v1Tabs, input = """{"nintendoLayout":true}"""))
        assertFalse(s.input.nintendoLayout)
        assertFalse(s.input.swapConfirmBack)
        assertEquals(GlyphStyle.NINTENDO, s.input.glyphs)
    }

    @Test
    fun currentDocumentsAreNotMigratedAgain() {
        val saved = AppSettings(library = LibraryPreferences(cleanDisplayNames = false, cleanedExistingNames = true))
        val again = AppSettingsCodec.decode(AppSettingsCodec.encode(saved, null))
        assertFalse(again.library.cleanDisplayNames)
        assertTrue(again.library.cleanedExistingNames)
    }

    @Test
    fun menuMusicShufflesAndFilmsAskWhichScreenFromVersion3() {
        val v2 = """{"version":2,"music":{"track":"menu","shuffle":false},"jellyfin":{"playOn":"MAIN"}}"""
        val s = AppSettingsCodec.decode(v2)
        assertTrue(s.music.shuffle)
        assertEquals("ASK", s.jellyfin.playOn)
        // The person's own song plays on its own, and a screen chosen on purpose stays.
        val own = AppSettingsCodec.decode("""{"version":2,"music":{"track":"file","shuffle":false},"jellyfin":{"playOn":"SECOND"}}""")
        assertFalse(own.music.shuffle)
        assertEquals("SECOND", own.jellyfin.playOn)
        // Written by this version, choices made since stay as they are.
        val now = AppSettingsCodec.decode("""{"version":3,"music":{"shuffle":false},"jellyfin":{"playOn":"MAIN"}}""")
        assertFalse(now.music.shuffle)
        assertEquals("MAIN", now.jellyfin.playOn)
    }
}
