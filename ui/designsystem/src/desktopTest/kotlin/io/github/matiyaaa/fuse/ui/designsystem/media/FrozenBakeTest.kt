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
    }

    @Test
    fun aVeryLargeRoomIsBakedNoLargerThanItsCapAndKeepsItsShape() {
        val baked = bakeSize(IntSize(3840, 2160))
        assertTrue(maxOf(baked.width, baked.height) <= 2560, "$baked")
        assertEquals(IntSize(2560, 1440), baked)
    }
}
