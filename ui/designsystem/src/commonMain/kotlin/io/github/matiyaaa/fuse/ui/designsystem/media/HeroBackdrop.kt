package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.paint
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the backdrop should show for the focused item. */
data class HeroSource(
    /** Identity of the focused item; changing it is what triggers a transition. */
    val id: Any,
    /** Hero image (path or URL), or null for an art-less item (a lit gradient is used). */
    val model: Any?,
    /** Colour that lights the room: platform or art colour. */
    val accent: Color,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.35f,
    /** Muted gameplay clip that may replace the art after the selection rests (path or URL). */
    val video: String? = null,
)

private class HeroLayer(val source: HeroSource) {
    val alpha = Animatable(0f)
    var ready = false
}

/**
 * The room the interface lives in. The focused item's hero art fills the screen behind everything,
 * softened by scrims so text stays readable.
 *
 * Transitions never flash: the previous art stays fully visible until the next one has decoded, then
 * the new art fades and settles in over it. While the user is moving quickly the backdrop waits for
 * the selection to rest ([settleMs]) so fast scrolling never queues dozens of decodes.
 */
@OptIn(FlowPreview::class)
@Composable
fun HeroBackdrop(
    source: HeroSource?,
    modifier: Modifier = Modifier,
    /** 0..1 darkness laid over the art. */
    dim: Float = 0.35f,
    /** 0..1 strength of the legibility gradients (left and bottom). */
    gradient: Float = 0.9f,
    brightness: Float = 1f,
    settleMs: Long = 110,
    /** Vertical parallax offset in px (content scroll); the art moves a fraction of it. */
    parallax: () -> Float = { 0f },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val colors = Fuse.colors
    val motion = Fuse.motion
    val layers = remember { mutableStateListOf<HeroLayer>() }
    val latest by rememberUpdatedState(source)

    LaunchedEffect(Unit) {
        snapshotFlow { latest }
            .distinctUntilChanged { a, b -> a?.id == b?.id && a?.model == b?.model }
            .debounce { if (layers.isEmpty()) 0L else settleMs }
            .collect { next ->
                if (next == null) return@collect
                if (layers.lastOrNull()?.source?.let { it.id == next.id && it.model == next.model } == true) return@collect
                // Drop layers that never finished loading; keep the visible one underneath.
                layers.removeAll { !it.ready && it !== layers.firstOrNull() }
                layers.add(HeroLayer(next))
                if (layers.size > 3) layers.removeAt(0)
            }
    }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (motion.parallax) translationY = -parallax() * 0.12f
                },
        ) {
            for (layer in layers) {
                key(layer) {
                    HeroLayerView(
                        layer = layer,
                        brightness = brightness,
                        onReady = {
                            layer.ready = true
                        },
                        onShown = {
                            // Everything under a fully shown layer is invisible: release it.
                            val index = layers.indexOf(layer)
                            if (index > 0) repeat(index) { layers.removeAt(0) }
                        },
                    )
                }
            }
            overlay()
        }
        val accent = layers.lastOrNull()?.source?.accent ?: colors.accent
        Canvas(Modifier.fillMaxSize()) {
            // Readability: art fades into the room at the left and bottom, softly at the top.
            drawRect(Color.Black.copy(alpha = dim))
            drawRect(
                Brush.horizontalGradient(
                    0f to colors.ink.copy(alpha = 0.92f * gradient),
                    0.38f to colors.ink.copy(alpha = 0.55f * gradient),
                    0.7f to Color.Transparent,
                ),
            )
            drawRect(
                Brush.verticalGradient(
                    0f to colors.ink.copy(alpha = 0.55f * gradient),
                    0.18f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to colors.ink.copy(alpha = 0.96f * gradient),
                ),
            )
            // The room is lit by the game: a low, wide glow in its colour.
            drawRect(
                Brush.radialGradient(
                    listOf(lerp(accent, Color.Transparent, 0.35f).copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 1.05f),
                    radius = size.maxDimension * 0.7f,
                ),
            )
        }
    }
}

@Composable
private fun HeroLayerView(layer: HeroLayer, brightness: Float, onReady: () -> Unit, onShown: () -> Unit) {
    val motion = Fuse.motion
    val source = layer.source
    val settle = remember { Animatable(1.035f) }
    if (source.model == null) {
        LaunchedEffect(layer) {
            onReady()
            layer.alpha.animateTo(1f, motion.tween(Durations.HERO, Easings.Fade))
            onShown()
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = layer.alpha.value }) {
            drawRect(
                Brush.radialGradient(
                    listOf(lerp(Color.Black, source.accent, 0.55f), Color.Black),
                    center = Offset(size.width * 0.72f, size.height * 0.3f),
                    radius = size.maxDimension * 0.8f,
                ),
            )
        }
        return
    }
    val context = LocalPlatformContext.current
    // Decoded no larger than the performance profile allows (smaller in Low Power).
    val maxPx = Fuse.quality.heroMaxPx
    val request = remember(source.model, context, maxPx) { ImageRequest.Builder(context).data(source.model).size(maxPx).build() }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    val state by painter.state.collectAsStateCompat()
    val done = state is AsyncImagePainter.State.Success || state is AsyncImagePainter.State.Error
    LaunchedEffect(done) {
        if (!done) return@LaunchedEffect
        onReady()
        if (!motion.reduced) {
            coroutineScope {
                launch { settle.animateTo(1f, motion.tween(Durations.DELIBERATE + 200, Easings.Enter)) }
                layer.alpha.animateTo(1f, motion.tween(Durations.HERO, Easings.Fade))
            }
        } else {
            layer.alpha.animateTo(1f, motion.tween(Durations.FAST, Easings.Fade))
        }
        onShown()
    }
    val failed = state is AsyncImagePainter.State.Error
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = layer.alpha.value
                scaleX = settle.value
                scaleY = settle.value
            }
            .drawWithContent {
                drawContent()
                if (brightness < 1f) drawRect(Color.Black.copy(alpha = 1f - brightness))
            },
    ) {
        if (failed) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.radialGradient(
                        listOf(lerp(Color.Black, source.accent, 0.55f), Color.Black),
                        center = Offset(size.width * 0.72f, size.height * 0.3f),
                        radius = size.maxDimension * 0.8f,
                    ),
                )
            }
        } else {
            Box(
                Modifier.fillMaxSize().paint(
                    painter,
                    contentScale = ContentScale.Crop,
                    alignment = focusAlignment(source.focusX, source.focusY),
                ),
            )
        }
    }
}
