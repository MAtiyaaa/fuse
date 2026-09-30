package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import io.github.matiyaaa.fuse.model.CrtSettings

/**
 * A light CRT treatment drawn over the whole interface: scanlines, a phosphor stripe mask, glow at
 * the centre and darkening toward the curved edges. It is a static layer (cached, never animated),
 * and every strength is capped so text stays readable. Callers skip it in Low Power Mode.
 */
@Composable
fun CrtOverlay(settings: CrtSettings, modifier: Modifier = Modifier) {
    if (!settings.enabled) return
    val scan = settings.scanlines.coerceIn(0f, 1f) * 0.32f
    val mask = settings.chromatic.coerceIn(0f, 1f) * 0.06f
    val bloom = settings.bloom.coerceIn(0f, 1f) * 0.12f
    val vignette = (settings.vignette.coerceIn(0f, 1f) + settings.curvature.coerceIn(0f, 1f)).coerceAtMost(1.2f) * 0.55f
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val pitch = (3f * density).coerceAtLeast(3f)
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = scan), Offset(0f, y), Offset(size.width, y), strokeWidth = pitch / 2.6f)
            y += pitch
        }
        if (mask > 0f) {
            val stripe = pitch
            var x = 0f
            var i = 0
            val tints = listOf(Color(0xFFFF3030), Color(0xFF30FF30), Color(0xFF3060FF))
            while (x < size.width) {
                drawLine(tints[i % 3].copy(alpha = mask), Offset(x, 0f), Offset(x, size.height), strokeWidth = stripe / 3f, blendMode = BlendMode.Plus)
                x += stripe / 3f
                i++
            }
        }
        if (bloom > 0f) {
            drawRect(
                Brush.radialGradient(listOf(Color.White.copy(alpha = bloom), Color.Transparent), radius = size.maxDimension * 0.6f),
                blendMode = BlendMode.Plus,
            )
        }
        drawRect(
            Brush.radialGradient(
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = vignette),
                radius = size.maxDimension * 0.72f,
            ),
        )
    }
}
