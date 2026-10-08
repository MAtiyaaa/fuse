package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Skia software pixels, not a motion-engine proxy or a claim of GPU/display coverage. */
class StaticRenderingTest {
    @AfterTest fun reset() { Drawing.cpu = false }

    @Test fun cachedPixelsInvalidateForDensityAndClearBeforeRepaint() {
        Drawing.cpu = true
        val flat = FlatLayer(null)
        fun draw(density: Float, key: Int, paint: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap {
            val image = ImageBitmap(16, 16)
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(16f, 16f)) {
                flat.draw(this, key, paint)
            }
            return image
        }
        var recorded = 0
        val first = draw(1f, 1) { recorded++; drawRect(Color.Red) }
        val same = draw(1f, 1) { error("unchanged software picture should be reused") }
        assertEquals(first.toPixelMap()[8, 8], same.toPixelMap()[8, 8])
        val changed = draw(2f, 1) { recorded++; drawRect(Color.Blue, size = Size(4f, 4f)) }
        assertEquals(2, recorded)
        assertEquals(Color.Blue, changed.toPixelMap()[1, 1])
        assertEquals(0f, changed.toPixelMap()[8, 8].alpha, "old full-size pixels must be cleared")
    }

    @Test fun largeSoftwareSurfaceNeverAllocatesAReplacementRasterOnResize() {
        Drawing.cpu = true
        UiRenderTrace.enabled = true
        UiRenderTrace.reset()
        try {
            val flat = FlatLayer(null)
            val image = ImageBitmap(2049, 1024)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(2049f, 1024f)) {
                flat.draw(this, 1) { drawRect(Color.Red) }
            }
            assertTrue(UiRenderTrace.report().contains("Software raster generations not sampled"))
            assertEquals(Color.Red, image.toPixelMap()[2048, 1023])
        } finally { UiRenderTrace.enabled = false }
    }

    @Test fun grainHasNoOld128PixelPeriodAtAnyRepresentativeCanvasSize() {
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)) {
            val image = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                drawRect(Color(.25f, .25f, .25f))
                drawGrain()
            }
            val pixels = image.toPixelMap()
            var different = 0
            for (y in 0 until 256 step 3) for (x in 0 until 512 step 3) {
                if (pixels[x, y] != pixels[x + 128, y]) different++
            }
            assertTrue(different > 1000, "$w x $h must not repeat the old128px grain field")
        }
    }
}
