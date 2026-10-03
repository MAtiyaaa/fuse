package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.graphics.PathMeasure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The brand art in code is the brand files' art: the same boxes, and the fuse line ending in the spark. */
class BrandArtTest {
    private fun near(a: Float, b: Float, what: String, tolerance: Float = 0.5f) =
        assertTrue(abs(a - b) <= tolerance, "$what is $a, expected $b")

    @Test
    fun wordmarkFillsItsBox() {
        val b = BrandArt.wordmark.getBounds()
        near(b.left, 0f, "left")
        near(b.top, 0f, "top")
        near(b.right, BrandArt.WORD_W, "right")
        near(b.bottom, BrandArt.WORD_H, "bottom")
    }

    @Test
    fun frameIsTheSquircle() {
        val b = BrandArt.frame.getBounds()
        near(b.left, 5f, "left")
        near(b.top, 5f, "top")
        near(b.width, 90f, "width")
        near(b.height, 90f, "height")
    }

    @Test
    fun fuseLineRunsIntoTheSpark() {
        val m = PathMeasure().apply { setPath(BrandArt.fuse, false) }
        val start = m.getPosition(0f)
        val end = m.getPosition(m.length)
        near(start.x, 28f, "start x")
        near(start.y, 70f, "start y")
        near(end.x, BrandArt.SPARK.x, "end x")
        near(end.y, BrandArt.SPARK.y, "end y")
    }

    @Test
    fun lockupIsTheBrandsProportions() {
        near(BrandArt.LOCKUP_W, 1.3f + 0.46f + 2.7771f, "lockup width per cap", 0.001f)
    }
}
