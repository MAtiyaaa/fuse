package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.ui.designsystem.media.initialsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The foundation's promises: readable roles, ordered surfaces, motion that respects the profile. */
class FoundationTest {
    private fun contrast(fg: Color, bg: Color): Float {
        val a = fg.compositeOver(bg).luminance()
        val b = bg.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    @Test
    fun faintTextStillReadsOnEverySurface() {
        for (spec in ThemePresets.all) {
            val c = FuseColors.from(spec.palette)
            for (bg in listOf(c.ink, c.surface, c.surfaceRaised)) {
                val ratio = contrast(c.textFaint, bg)
                assertTrue(ratio >= 4.5f, "${spec.name}: faint text is $ratio:1")
            }
            // Still a step quieter than muted text.
            assertTrue(c.textFaint.alpha < 1f, "${spec.name}: faint text is as strong as muted")
        }
    }

    @Test
    fun surfacesStepUpInDarkThemes() {
        for (spec in ThemePresets.all.filter { it.palette.dark }) {
            val c = FuseColors.from(spec.palette)
            val steps = listOf(c.ink, c.surfaceDim, c.surface, c.surfaceRaised, c.surfaceOverlay).map { it.luminance() }
            assertEquals(steps.sorted(), steps, "${spec.name}: surfaces out of order")
        }
    }

    @Test
    fun interactionOverlaysAreQuietAndPressedIsStronger() {
        for (spec in ThemePresets.all) {
            val c = FuseColors.from(spec.palette)
            assertTrue(c.hover.alpha in 0.01f..0.12f, "${spec.name}: hover ${c.hover.alpha}")
            assertTrue(c.pressed.alpha > c.hover.alpha, "${spec.name}: pressed not stronger than hover")
            assertTrue(c.hairlineStrong.alpha > c.hairline.alpha, "${spec.name}: strong hairline not stronger")
        }
    }

    @Test
    fun reducedMotionKeepsOnlyFades() {
        val m = FuseMotion(MotionProfile.REDUCED)
        assertEquals(0f, m.revealRise.value)
        assertEquals(0, m.stagger(5))
        assertEquals(1f, m.pressScale)
        assertEquals(1f, m.overlayScale)
        assertFalse(m.drift)
        assertTrue(m.ms(Durations.SLOW) <= 72)
    }

    @Test
    fun staggerIsCappedSoLongListsNeverQueue() {
        val m = FuseMotion(MotionProfile.STANDARD)
        assertEquals(0, m.stagger(0))
        assertEquals(Durations.STAGGER * 3, m.stagger(3))
        assertEquals(m.stagger(FuseMotion.STAGGER_MAX), m.stagger(40))
        assertTrue(FuseMotion(MotionProfile.ENHANCED).drift)
        assertFalse(m.drift)
    }

    @Test
    fun elevationClimbsFromRoomToOverlay() {
        val levels = listOf(Elevation.room, Elevation.tile, Elevation.panel, Elevation.raised, Elevation.overlay)
        val shadows = levels.map { it.shadow.value }
        assertEquals(shadows.sorted(), shadows)
        assertTrue(Elevation.tileFocused.shadow > Elevation.tile.shadow)
        assertTrue(Elevation.tileFocused.edge > Elevation.tile.edge)
    }

    @Test
    fun initialsSkipSmallWords() {
        assertEquals("LZ", initialsOf("The Legend of Zelda"))
        assertEquals("TE", initialsOf("Tetris"))
        assertEquals("FZ", initialsOf("F-Zero X"))
    }
}
