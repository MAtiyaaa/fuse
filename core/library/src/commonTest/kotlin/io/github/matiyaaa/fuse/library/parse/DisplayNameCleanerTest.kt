package io.github.matiyaaa.fuse.library.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayNameCleanerTest {
    private fun clean(s: String) = DisplayNameCleaner.clean(s)

    @Test
    fun removesRegionRevisionAndDumpFlags() {
        val preview = DisplayNameCleaner.preview("Metroid Fusion (USA) (Rev 1) [!]")
        assertEquals("Metroid Fusion", preview.cleaned)
        assertEquals(CleanupConfidence.SAFE, preview.confidence)
        assertEquals(listOf("(USA)", "(Rev 1)", "[!]"), preview.removed)
    }

    @Test
    fun invertsTrailingArticleBeforeSubtitle() {
        assertEquals(
            "The Legend of Zelda - A Link to the Past",
            clean("Legend of Zelda, The - A Link to the Past (USA)"),
        )
    }

    @Test
    fun invertsTrailingArticleAtEnd() {
        assertEquals("The Adventures of Lolo", clean("Adventures of Lolo, The (USA) (Rev A)"))
        assertEquals("A Tale of Two Cities", clean("Tale of Two Cities, A (Europe)"))
        assertEquals("An American Tail", clean("American Tail, An (USA)"))
        assertEquals("L'Amerzone", clean("Amerzone, L' (France)"))
        assertEquals("Die Siedler", clean("Siedler, Die (Germany)"))
    }

    @Test
    fun doesNotInvertCommasThatAreNotArticles() {
        assertEquals("Rock, Paper, Scissors", clean("Rock, Paper, Scissors (USA)"))
    }

    @Test
    fun removesDiscAndLanguageTags() {
        assertEquals("Final Fantasy VII", clean("Final Fantasy VII (Disc 2 of 3) (Europe) (En,Fr,De)"))
        assertEquals("Game", clean("Game - Disc 2"))
    }

    @Test
    fun removesTranslationTags() {
        assertEquals("Pokemon - Emerald Version", clean("Pokemon - Emerald Version (U) [T+Fre]"))
    }

    @Test
    fun keepsUnknownParentheticals() {
        val preview = DisplayNameCleaner.preview("Tetris (Tengen) (USA)")
        assertEquals("Tetris (Tengen)", preview.cleaned)
        assertEquals(CleanupConfidence.SAFE, preview.confidence)
        assertEquals("Game (Bonus Disc)", clean("Game (Bonus Disc) (Japan)"))
    }

    @Test
    fun keepsReleaseVariants() {
        assertEquals("Star Fox 2 (Beta)", clean("Star Fox 2 (Japan) (Beta)"))
        assertEquals("Game (Proto 2)", clean("Game (USA) (Proto 2)"))
        assertEquals("Game (Demo)", clean("Game (Europe) (Demo)"))
        assertEquals("Action 52", clean("Action 52 (USA) (Unl)"))
    }

    @Test
    fun unknownSquareTagsAreRemovedButFlaggedForReview() {
        val preview = DisplayNameCleaner.preview("Game [Some Scene Group]")
        assertEquals("Game", preview.cleaned)
        assertEquals(CleanupConfidence.REVIEW, preview.confidence)
        assertTrue(preview.warnings.single().contains("[Some Scene Group]"))
    }

    @Test
    fun removesSerialsAndSwitchIds() {
        assertEquals("Gran Turismo 5", clean("Gran Turismo 5 [BCES00569]"))
        assertEquals("Zelda BOTW", clean("Zelda BOTW [01007EF00011E000][v0]"))
        assertEquals("Metal Gear Solid 4", clean("BLUS30001-[Metal Gear Solid 4]"))
    }

    @Test
    fun removesTosecMetadata() {
        assertEquals("Game", clean("Game (1990)(Publisher)(Disk 1 of 2)[cr]"))
    }

    @Test
    fun replacesUnderscoresOnlyWhenThereAreNoSpaces() {
        assertEquals("Super Mario World", clean("Super_Mario_World_(USA)"))
        assertEquals("Mega_Man X", clean("Mega_Man X (USA)"))
    }

    @Test
    fun leavesCleanTitlesAlone() {
        val preview = DisplayNameCleaner.preview("Super Mario World")
        assertFalse(preview.changed)
        assertEquals(CleanupConfidence.UNCHANGED, preview.confidence)
        assertEquals("Dr. Mario", clean("Dr. Mario (World)"))
        assertEquals("BLUS30001", clean("BLUS30001"))
    }

    @Test
    fun neverReturnsAnEmptyTitle() {
        val preview = DisplayNameCleaner.preview("(USA)")
        assertEquals("(USA)", preview.cleaned)
        assertEquals(CleanupConfidence.REVIEW, preview.confidence)
    }

    @Test
    fun warnsAboutUnbalancedBrackets() {
        val preview = DisplayNameCleaner.preview("Game (USA")
        assertEquals("Game (USA", preview.cleaned)
        assertEquals(CleanupConfidence.REVIEW, preview.confidence)
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("Street Fighter II - Turbo", clean("  Street   Fighter II  -  Turbo  (Japan) "))
    }

    @Test
    fun previewAllKeepsOrderAndFlagsChanges() {
        val previews = DisplayNameCleaner.previewAll(listOf("Sonic (USA)", "Sonic", "Legend of Zelda, The"))
        assertEquals(listOf("Sonic", "Sonic", "The Legend of Zelda"), previews.map { it.cleaned })
        assertEquals(listOf(true, false, true), previews.map { it.changed })
        assertEquals(listOf("Sonic (USA)", "Sonic", "Legend of Zelda, The"), previews.map { it.original })
    }
}
