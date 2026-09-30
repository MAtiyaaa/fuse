package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Button layout, the confirm/back swap, detection and the test mode that swallows every press. */
class ButtonLayoutTest {
    private fun router(profile: InputProfile = InputProfile()) =
        InputRouter(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), profile)

    @Test
    fun nintendoLabelsDoNotSwapKeys() {
        // An AYN in Nintendo mode sends BUTTON_A for the button labelled A: it must confirm.
        val r = router(InputProfile(glyphs = GlyphStyle.NINTENDO))
        assertEquals(NavAction.SELECT, r.actionFor(PadButton.A))
        assertEquals(NavAction.BACK, r.actionFor(PadButton.B))
        assertTrue(r.profile.confirmOnRight)
    }

    @Test
    fun swapExchangesConfirmAndBack() {
        val r = router(InputProfile(swapConfirmBack = true))
        assertEquals(NavAction.BACK, r.actionFor(PadButton.A))
        assertEquals(NavAction.SELECT, r.actionFor(PadButton.B))
        assertEquals(NavAction.SEARCH, r.actionFor(PadButton.X))
        assertTrue(r.profile.confirmOnRight)
        assertFalse(InputProfile(glyphs = GlyphStyle.NINTENDO, swapConfirmBack = true).confirmOnRight)
    }

    @Test
    fun oldNintendoToggleMigratesToLabelsWithoutSwap() {
        val old = InputProfile(nintendoLayout = true)
        val now = old.migrated()
        assertFalse(now.nintendoLayout)
        assertEquals(GlyphStyle.NINTENDO, now.glyphs)
        assertFalse(now.swapConfirmBack)
        assertEquals(InputProfile(), InputProfile().migrated())
    }

    @Test
    fun detectionReadsTheRightButtonAndTheConfirmButton() {
        // Nintendo keycodes: right button reports A, and A confirms.
        val nintendo = detectedProfile(InputProfile(), rightButton = PadButton.A, confirmButton = PadButton.A)
        assertEquals(GlyphStyle.NINTENDO, nintendo.glyphs)
        assertFalse(nintendo.swapConfirmBack)
        assertTrue(nintendo.confirmOnRight)
        // Xbox keycodes, but the user confirms with the right button.
        val swapped = detectedProfile(InputProfile(), rightButton = PadButton.B, confirmButton = PadButton.B)
        assertEquals(GlyphStyle.XBOX, swapped.glyphs)
        assertTrue(swapped.swapConfirmBack)
        assertTrue(swapped.confirmOnRight)
        // A PlayStation pad keeps its shapes.
        val ps = detectedProfile(InputProfile(glyphs = GlyphStyle.PLAYSTATION), PadButton.B, PadButton.A)
        assertEquals(GlyphStyle.PLAYSTATION, ps.glyphs)
    }

    @Test
    fun exclusiveModeSwallowsEveryPressIncludingBack() {
        val r = router()
        val actions = mutableListOf<NavAction>()
        r.register(priority = 0) { e -> actions += e.action; NavResult.CONSUMED }
        val seen = mutableListOf<Pair<PadButton, Boolean>>()
        r.exclusive = { b, down -> seen += b to down }
        r.press(PadButton.B, InputSource.GAMEPAD)
        r.release(PadButton.B, InputSource.GAMEPAD)
        r.press(PadButton.KEY_ESCAPE, InputSource.GAMEPAD)
        r.release(PadButton.KEY_ESCAPE, InputSource.GAMEPAD)
        assertTrue(actions.isEmpty(), "no action while testing")
        assertEquals(listOf(PadButton.B to true, PadButton.B to false, PadButton.KEY_ESCAPE to true, PadButton.KEY_ESCAPE to false), seen)
        r.exclusive = null
        r.press(PadButton.B, InputSource.GAMEPAD)
        assertEquals(listOf(NavAction.BACK), actions)
    }
}
