package io.github.matiyaaa.fuse.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A choice not saved yet survives settings that came in meanwhile; nothing else of theirs is touched. */
class UnsavedChoicesTest {
    @Test
    fun onlyWhatThePersonChangedIsCarriedOverWhatCameIn() {
        val saved = AppSettings()
        val shown = saved.copy(appearance = saved.appearance.copy(themeId = "fusi"))
        // Meanwhile a sync round brought another setting (and Fuse One wrote its own).
        val cameIn = saved.copy(
            appearance = saved.appearance.copy(textScale = 1.2f),
            sync = saved.sync.copy(activeProfile = "someone"),
        )
        val result = assertNotNull(UnsavedChoices.carry(cameIn, saved, shown))
        assertEquals("fusi", result.appearance.themeId)
        assertEquals(1.2f, result.appearance.textScale, "what came in stays")
        assertEquals("someone", result.sync.activeProfile, "Fuse One's own settings are never carried")
    }

    @Test
    fun anEntryTakenOutIsTakenOutOfWhatCameInToo() {
        val saved = AppSettings().let { s -> s.copy(home = s.home.copy(continueDismissed = mapOf("1" to 10L, "2" to 20L))) }
        // Game 1 is back on Continue Playing here; meanwhile game 3 was taken off elsewhere.
        val shown = saved.copy(home = saved.home.copy(continueDismissed = mapOf("2" to 20L)))
        val cameIn = saved.copy(home = saved.home.copy(continueDismissed = saved.home.continueDismissed + ("3" to 30L)))
        val result = assertNotNull(UnsavedChoices.carry(cameIn, saved, shown))
        assertEquals(mapOf("2" to 20L, "3" to 30L), result.home.continueDismissed)
    }

    @Test
    fun whatSomeoneElseChangedSinceStaysUnlessThePersonChangedItToo() {
        val read = AppSettings().let { it.copy(appearance = it.appearance.copy(themeId = "fuse", textScale = 1f)) }
        // Since the interface read its settings, another Fuse chose Twin and a larger text size.
        val now = read.copy(appearance = read.appearance.copy(themeId = "twin", textScale = 1.2f))
        // Here the person turned video previews off and chose a larger text size still.
        val before = read
        val after = read.copy(videoPreview = read.videoPreview.copy(enabled = false), appearance = read.appearance.copy(textScale = 1.4f))
        // What the interface writes: everything it shows, its theme the one it last read.
        val written = now.copy(videoPreview = after.videoPreview, appearance = now.appearance.copy(themeId = "fuse", textScale = 1.4f))
        val result = assertNotNull(UnsavedChoices.keepOthers(written, read, now, before, after))
        assertEquals("twin", result.appearance.themeId, "the other Fuse's theme isn't put back")
        assertEquals(false, result.videoPreview.enabled, "the person's change is written")
        assertEquals(1.4f, result.appearance.textScale, "changed in both: the person's here is the newest")
        // Nothing changed in storage since: nothing to keep.
        assertNull(UnsavedChoices.keepOthers(written, now, now, before, after))
    }

    @Test
    fun nothingChangedCarriesNothing() {
        val saved = AppSettings()
        assertNull(UnsavedChoices.carry(saved, saved, saved))
        // A difference only in Fuse One's settings isn't the person's choice to carry.
        assertNull(UnsavedChoices.carry(saved, saved, saved.copy(sync = saved.sync.copy(enabled = true))))
    }
}
