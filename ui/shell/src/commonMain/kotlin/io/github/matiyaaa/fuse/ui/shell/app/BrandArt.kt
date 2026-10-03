package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Fuse's brand art as the brand files draw it, so the app writes "Fuse" the way the website and the
 * README do. The path data is copied from docs/assets/brand/wordmark-dark.svg and mark.svg (made by
 * docs/assets/brand/source/build.py); copy it again if those change.
 *
 * Everything is in the files' own units: the mark in a 100 box, the wordmark with its capitals 100
 * tall (cap top at 0, baseline at 100; round letters dip a little below).
 */
internal object BrandArt {
    /** The wordmark's box: its width, and its height including the overshoot of s, u and e. */
    const val WORD_W = 277.71f
    const val WORD_H = 101.57f
    const val CAP = 100f

    const val MARK = 100f
    const val MARK_STROKE = 10f
    val SPARK = Offset(70f, 30f)
    const val GLOW_R = 22f
    const val CORE_R = 7f

    /** The horizontal lockup, per unit of cap height: the mark at 1.3 (so its frame matches the letters' strokes), a gap, the word. */
    const val LOCKUP_MARK = 1.3f
    const val LOCKUP_GAP = 0.46f
    const val LOCKUP_W = LOCKUP_MARK + LOCKUP_GAP + WORD_W / CAP

    private const val WORDMARK_D = "M277.71 67.37V55.49q0-14.53-9.26-22.88-4.74-4.26-11.5-6.42-6.44-2.05-14.38-2.05-8.04 0-14.56 2.25-6.79 2.33-11.52 6.93-9.06 8.82-9.06 24.17v7.65q0 16.63 8.8 26.19 4.69 5.08 11.5 7.69 6.64 2.55 14.84 2.55 6.46 0 10.69-.46 5.77-.62 10.08-2.3 10.05-3.91 14.74-14.59l-14.16-4.6q-1.41 3.2-3.25 5.03-1.49 1.49-3.58 2.3-2.22.87-5.72 1.24-3.29.36-8.8.36-9.57 0-14.47-5.31-5.01-5.44-5.48-15.87ZM63.43 25.71v40.58q0 15.97 8 25.19 4.33 4.98 10.68 7.56 6.22 2.53 13.89 2.53 7.98 0 14.45-2.46 2.49-.95 4.69-2.24V100h15.15V25.71h-15.15v40.58q0 12.8-6.32 18.24-4.68 4.02-12.82 4.02-8.21 0-12.43-4.87-5-5.75-5-17.39V25.71ZM15.14 100V60.51h40.29V47.49H15.14V17.94q0-2.9 1.23-4.13.79-.79 2.63-.79h42.43V0H19q-4.19 0-7.76 1.32-3.72 1.37-6.35 4Q0 10.21 0 17.94V100Zm154.07-62.83q4.68 0 7.79 1.06 2.42.82 3.67 2.25l12.12-7.8q-3.81-4.38-10.22-6.56-5.8-1.98-13.36-1.98-13.33 0-21.19 5.26-4.34 2.91-6.57 7.14-2.16 4.09-2.16 9.07 0 5.17 2.07 9.4 2.18 4.44 6.45 7.51 7.75 5.58 20.83 5.7v.01q8.49 0 12.48 2.92 3.45 2.54 3.45 7.81 0 4.59-3.26 6.82-4.03 2.77-12.67 2.77-9.71 0-12.7-4.14l-12.86 6.89q3.79 5.23 10.76 7.87 6.34 2.4 14.8 2.4 13.93 0 22.11-5.6 4.46-3.06 6.76-7.51 2.2-4.28 2.2-9.5 0-5.42-2.13-9.84-2.24-4.65-6.63-7.88-8.07-5.91-21.74-6.03v-.01q-7.98 0-11.68-2.66-3.1-2.24-3.1-6.93 0-3.99 2.89-5.93 3.76-2.51 11.89-2.51m73.36 0q10 0 14.93 4.44 4.76 4.28 5.05 12.74H222.7q1.48-17.18 19.87-17.18"

    /** The squircle frame, starting at the top centre and running clockwise. */
    private const val FRAME_D = "M49.8 5h.4c15.68 0 23.52 0 29.51 3.05a28 28 0 0 1 12.24 12.24C95 26.28 95 34.12 95 49.8v.4c0 15.68 0 23.52-3.05 29.51a28 28 0 0 1-12.24 12.24C73.72 95 65.88 95 50.2 95h-.4c-15.68 0-23.52 0-29.51-3.05A28 28 0 0 1 8.05 79.71C5 73.72 5 65.88 5 50.2v-.4c0-15.68 0-23.52 3.05-29.51A28 28 0 0 1 20.29 8.05C26.28 5 34.12 5 49.8 5Z"

    /** The fuse line, from its free end into the spark. */
    private const val FUSE_D = "M28 70c14 0 16-36 36-36 3.2 0 5.2-1.5 6-4"

    val wordmark: Path by lazy { parse(WORDMARK_D) }
    val frame: Path by lazy { parse(FRAME_D) }
    val fuse: Path by lazy { parse(FUSE_D) }

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

    /** The spark's hot core, from near white to the accent, as the brand file shades it. */
    fun core(accent: Color): Brush = Brush.radialGradient(
        0f to mix(accent, Color.White, 0.92f),
        0.42f to mix(accent, Color.White, 0.45f),
        0.78f to accent,
        center = SPARK,
        radius = CORE_R,
    )

    /** The spark's glow. */
    fun glow(accent: Color, strength: Float = 1f): Brush = Brush.radialGradient(
        0f to accent.copy(alpha = strength),
        0.3f to accent.copy(alpha = 0.55f * strength),
        0.6f to accent.copy(alpha = 0.18f * strength),
        1f to accent.copy(alpha = 0f),
        center = SPARK,
        radius = GLOW_R,
    )
}

/** Fuse's mark, [size] wide with its box at [topLeft]: the frame and fuse line in [ink], the spark in [accent]. */
internal fun DrawScope.drawBrandMark(topLeft: Offset, size: Float, ink: Color, accent: Color) {
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(size / BrandArt.MARK, size / BrandArt.MARK, pivot = Offset.Zero)
    }) {
        drawPath(BrandArt.frame, ink, style = Stroke(BrandArt.MARK_STROKE))
        drawPath(BrandArt.fuse, ink, style = Stroke(BrandArt.MARK_STROKE, cap = StrokeCap.Round))
        drawCircle(BrandArt.glow(accent), radius = BrandArt.GLOW_R, center = BrandArt.SPARK)
        drawCircle(BrandArt.core(accent), radius = BrandArt.CORE_R, center = BrandArt.SPARK)
    }
}

/** Fuse's wordmark with its capitals [cap] tall and its cap top left corner at [topLeft]. */
internal fun DrawScope.drawWordmark(topLeft: Offset, cap: Float, color: Color) {
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(cap / BrandArt.CAP, cap / BrandArt.CAP, pivot = Offset.Zero)
    }) {
        drawPath(BrandArt.wordmark, color)
    }
}

internal fun mix(a: Color, b: Color, f: Float): Color = Color(
    a.red + (b.red - a.red) * f,
    a.green + (b.green - a.green) * f,
    a.blue + (b.blue - a.blue) * f,
    a.alpha + (b.alpha - a.alpha) * f,
)
