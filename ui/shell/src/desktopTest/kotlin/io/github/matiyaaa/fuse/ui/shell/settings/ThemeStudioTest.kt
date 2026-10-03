package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.model.Contrast
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The guided studio: every colour can change, it says what reads well, and saving keeps exactly that. */
class ThemeStudioTest {
    private fun studio(id: String = "fuse") = StudioState.of(ThemePresets.byId(id), custom = false, file = null)

    @Test
    fun anUntouchedThemeIsUnchanged() {
        val s = studio()
        assertFalse(s.changed)
        val base = ThemePresets.Fuse
        val d = s.draft()
        assertEquals(base.palette.copy(success = d.palette.success, warning = d.palette.warning, danger = d.palette.danger), d.palette)
        assertEquals(base.ambient, d.ambient)
    }

    @Test
    fun everyColourCanChangeAndReadsAreChecked() {
        val s = studio()
        for (role in ColorRole.entries) {
            val before = s.color(role)
            val moved = s.nudge(role, EditRow.HUE, 3) || s.nudge(role, EditRow.LIGHTNESS, -1) || s.nudge(role, EditRow.LIGHTNESS, 1)
            assertTrue(moved && s.color(role) != before, "$role didn't change")
        }
        assertTrue(s.changed)
        s.reset()
        assertFalse(s.changed)
    }

    @Test
    fun faintTextIsFlaggedAndFixed() {
        val s = studio()
        repeat(9) { s.nudge(ColorRole.MUTED, EditRow.LIGHTNESS, -1) }
        assertFalse(s.legibility(ColorRole.MUTED).ok)
        assertTrue(s.lines().none { it is StudioLine.Edit })
        s.open(ColorRole.MUTED)
        assertTrue(StudioLine.Edit(EditRow.FIX) in s.lines(), "a fix is offered while it is hard to read")
        s.fix(ColorRole.MUTED)
        assertTrue(s.legibility(ColorRole.MUTED).ok)
    }

    @Test
    fun turningABrightThemeDarkKeepsItReadable() {
        val s = studio("noon")
        s.step(StudioRow.MODE, -1)
        assertTrue(s.dark)
        for (role in listOf(ColorRole.TEXT, ColorRole.MUTED, ColorRole.ACCENT, ColorRole.ON_ACCENT, ColorRole.FOCUS)) {
            assertTrue(s.legibility(role).ok, "$role: ${s.legibility(role)}")
        }
        s.step(StudioRow.MODE, 1)
        assertFalse(s.dark)
        assertTrue(s.legibility(ColorRole.TEXT).ok && s.legibility(ColorRole.MUTED).ok)
    }

    @Test
    fun savingKeepsEveryChangeWithoutRepairs() {
        val s = studio("glass")
        s.set(ColorRole.ACCENT, 0xFFFFB02E)
        s.set(ColorRole.SUCCESS, 0xFF77E0A0)
        s.step(StudioRow.BLUR, 2)
        s.step(StudioRow.CRT, 1)
        s.step(StudioRow.SCANLINES, 2)
        val draft = s.draft()
        val r = ThemeCodec.parse(ThemeCodec.encode(draft.copy(id = ThemeCodec.CUSTOM_PREFIX + "x"), extends = "glass"), ThemePresets::find, ThemePresets.Fuse)
        val kept = (r as ThemeCodec.Imported)
        assertEquals(emptyList(), kept.notes)
        assertEquals(0xFFFFB02E, kept.spec.palette.accent)
        assertEquals(Contrast.bestOn(0xFFFFB02E), kept.spec.palette.onAccent)
        assertEquals(0xFF77E0A0, kept.spec.palette.success)
        assertEquals(draft.glass.blur, kept.spec.glass.blur)
        assertTrue(kept.spec.crt.enabled)
        assertEquals(draft.crt.scanlines, kept.spec.crt.scanlines)
    }

    @Test
    fun stepsGuideOnwardAndColoursOpenInPlace() {
        val s = studio()
        assertEquals(StudioStep.ROOM, s.step)
        assertEquals(StudioLine.Main(StudioRow.NEXT), s.lines().last())
        s.go(StudioStep.EFFECTS)
        assertTrue(StudioLine.Main(StudioRow.BLUR) !in s.lines(), "glass amounts only while glass is on")
        s.step(StudioRow.GLASS, 1)
        assertTrue(StudioLine.Main(StudioRow.BLUR) in s.lines())
        s.go(StudioStep.TEXT)
        s.open(ColorRole.MUTED)
        s.close()
        assertEquals(StudioLine.Main(StudioRow.MUTED), s.line())
        s.go(StudioStep.SAVE)
        assertEquals(listOf<StudioLine>(StudioLine.Main(StudioRow.SAVE)), s.lines())
    }
}
