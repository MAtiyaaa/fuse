package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSupported
import io.github.matiyaaa.fuse.model.CrtSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A CRT treatment drawn over the whole interface, built from what a tube actually shows:
 *
 * - **Scanlines** two dp apart on whole pixels, so they stay crisp at any density. Most of their
 *   depth goes into dark and middle tones and leaves bright ones nearly untouched, the way a bright
 *   beam blooms over the gap: white text keeps its contrast while art takes the texture.
 * - **An aperture grille**: fine red, green and blue phosphor stripes, multiplied in so they tint
 *   rather than lighten.
 * - **A gentle vignette** shaped to the screen (corners darker than edges), **rounded tube corners**
 *   for the curvature, and a faint **glow** in the middle for the bloom.
 *
 * Everything is one static layer: two tiny tiles repeated by a shader and a few gradients, built
 * once per size and never animated. Every strength is capped so text stays readable, and callers
 * skip it in Low Power Mode.
 */
@Composable
fun CrtOverlay(settings: CrtSettings, modifier: Modifier = Modifier) {
    if (!settings.enabled) return
    val scan = settings.scanlines.coerceIn(0f, 1f)
    val bloom = settings.bloom.coerceIn(0f, 1f)
    val chromatic = settings.chromatic.coerceIn(0f, 1f)
    val vignette = settings.vignette.coerceIn(0f, 1f)
    val curvature = settings.curvature.coerceIn(0f, 1f)
    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                // Brightness-aware scanlines need soft light; where a platform lacks it, the plain
                // multiply takes a little more of the depth instead.
                val soft = BlendMode.Softlight.isSupported()
                val softDepth = if (soft) scan * 0.75f else 0f
                val hardDepth = scan * (if (soft) 0.2f else 0.36f) * (1f - 0.5f * bloom)
                val maskDepth = (chromatic * 0.3f + scan * 0.06f).coerceAtMost(0.2f)
                val period = max(2, (2f * density).roundToInt())
                val stripe = max(1, (density * 0.67f).roundToInt())

                val multiply = ShaderBrush(
                    ImageShader(phosphorTile(period, stripe, hardDepth, maskDepth), TileMode.Repeated, TileMode.Repeated),
                )
                val softScan = if (softDepth > 0f) ShaderBrush(ImageShader(scanTile(period, softDepth), TileMode.Repeated, TileMode.Repeated)) else null

                // Elliptical vignette: drawn as a circle in a square space, then stretched to the screen,
                // so the long edges and the corners darken in proportion.
                val dark = (vignette * 0.5f + curvature * 0.25f).coerceAtMost(0.55f)
                val radius = h / 2f * sqrt(2f)
                val vignetteBrush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.48f to Color.Transparent,
                    0.6f to Color.Black.copy(alpha = dark * 0.1f),
                    0.71f to Color.Black.copy(alpha = dark * 0.4f),
                    0.86f to Color.Black.copy(alpha = dark * 0.75f),
                    1f to Color.Black.copy(alpha = dark),
                    center = Offset(h / 2f, h / 2f),
                    radius = radius,
                )

                // Rounded tube corners: each corner fades to dark outside a quarter circle.
                val corner = curvature * min(w, h) * 0.5f
                val cornerDark = min(1f, curvature * 4f) * 0.7f
                val corners = if (corner >= 2f && cornerDark > 0f) {
                    listOf(
                        Offset(corner, corner) to Rect(0f, 0f, corner, corner),
                        Offset(w - corner, corner) to Rect(w - corner, 0f, w, corner),
                        Offset(corner, h - corner) to Rect(0f, h - corner, corner, h),
                        Offset(w - corner, h - corner) to Rect(w - corner, h - corner, w, h),
                    ).map { (center, area) ->
                        area to Brush.radialGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = cornerDark),
                            center = center,
                            radius = corner,
                            tileMode = TileMode.Clamp,
                        )
                    }
                } else {
                    emptyList()
                }

                // A faint warm glow in the middle: the bloom of a lit tube.
                val glow = bloom * 0.1f
                val glowBrush = Brush.radialGradient(
                    0f to Color(0xFFFFF1DC).copy(alpha = glow),
                    1f to Color.Transparent,
                    center = Offset(w / 2f, h / 2f),
                    radius = max(w, h) * 0.55f,
                )

                onDrawBehind {
                    drawRect(multiply, blendMode = BlendMode.Modulate)
                    if (softScan != null) drawRect(softScan, blendMode = BlendMode.Softlight)
                    if (glow > 0f) drawRect(glowBrush, blendMode = BlendMode.Screen)
                    if (dark > 0f) {
                        scale(scaleX = w / h, scaleY = 1f, pivot = Offset.Zero) {
                            drawRect(vignetteBrush, size = androidx.compose.ui.geometry.Size(h, h))
                        }
                    }
                    for (i in corners.indices) {
                        val (area, brush) = corners[i]
                        clipRect(area.left, area.top, area.right, area.bottom) { drawRect(brush) }
                    }
                }
            },
    )
}

/**
 * Darkness across one scanline period of [period] pixels: full on the gap row, falling off within
 * about a quarter period either side, nothing through the beam.
 */
private fun gap(y: Int, period: Int): Float {
    val t = y.toFloat() / period
    val fromGap = min(t, 1f - t) * 2f
    return (1f - fromGap / 0.55f).coerceIn(0f, 1f).pow(1.5f)
}

/**
 * The multiply tile: phosphor stripes ([stripe] pixels each, red, green then blue) times the
 * scanline profile, opaque so modulating by it is a true multiply on every platform.
 */
private fun phosphorTile(period: Int, stripe: Int, scanDepth: Float, maskDepth: Float): ImageBitmap {
    val width = stripe * 3
    return tile(width, period) { x, y ->
        val beam = 1f - scanDepth * gap(y, period)
        val lit = x / stripe
        val off = 1f - maskDepth
        Color(
            red = beam * (if (lit == 0) 1f else off),
            green = beam * (if (lit == 1) 1f else off),
            blue = beam * (if (lit == 2) 1f else off),
        )
    }
}

/** The soft light tile: black whose strength follows the scanline profile. */
private fun scanTile(period: Int, depth: Float): ImageBitmap =
    tile(1, period) { _, y -> Color.Black.copy(alpha = (depth * gap(y, period)).coerceIn(0f, 1f)) }

private fun tile(width: Int, height: Int, pixel: (x: Int, y: Int) -> Color): ImageBitmap {
    val image = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
    val canvas = Canvas(image)
    val paint = Paint().apply { isAntiAlias = false }
    for (y in 0 until height) {
        for (x in 0 until width) {
            paint.color = pixel(x, y)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
        }
    }
    return image
}
