package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The blurred room behind the interface is baked at the screen's size, within a cap. */
class FrozenBakeTest {
    @Test
    fun aRoomIsBakedAtTheScreensSize() {
        assertEquals(IntSize(1920, 1080), bakeSize(IntSize(1920, 1080)))
        assertEquals(IntSize(1280, 800), bakeSize(IntSize(1280, 800)))
        // A 4K TV is baked at its own size too: stretched on the card, the dither would smear into bands.
        assertEquals(IntSize(3840, 2160), bakeSize(IntSize(3840, 2160)))
    }

    @Test
    fun aVeryLargeRoomIsBakedNoLargerThanItsCapAndKeepsItsShape() {
        val baked = bakeSize(IntSize(7680, 4320))
        assertTrue(maxOf(baked.width, baked.height) <= 3840, "$baked")
        assertEquals(IntSize(3840, 2160), baked)
    }
}
