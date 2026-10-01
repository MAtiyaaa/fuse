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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Art that isn't a background (box art or a cover): drawn blurred and darker so it reads as a
     * room, not as a picture.
     */
    val blurred: Boolean = false,
    /** Shown while [model] is still loading (usually the system's background, already in memory). */
    val placeholder: Any? = null,
)

/** The size backgrounds are decoded at: they sit dimmed behind the interface, so a little under the screen's. */
val RenderQuality.heroDecodePx: Int get() = (heroMaxPx * HERO_DECODE_SHARE).roundToInt()

private const val HERO_DECODE_SHARE = 0.7f

/** How long a new background may take before the room shows its placeholder instead of the old one. */
private const val PLACEHOLDER_MS = 120L

private class HeroLayer(val source: HeroSource) {
    val alpha = Animatable(0f)
    var ready = false
    /** Fading out because the screen has no background. */
    var leaving = false
}

/**
 * The room the interface lives in. The focused item's hero art fills the screen behind everything,
 * softened by scrims so text stays readable.
 *
 * Transitions never flash and never show the wrong art: new art fades and settles in over the old
 * once it has decoded, and art that takes longer than a moment first shows a placeholder (the
 * system's background, or a glow in the item's colour) so the previous item's art never lingers.
 * A screen without a background ([source] null) fades the room back to the theme's own. While the
 * user is moving quickly the backdrop waits for the selection to rest ([settleMs]) so fast scrolling
 * never queues dozens of decodes.
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
            .distinctUntilChanged { a, b -> a?.id == b?.id && a?.model == b?.model && a?.blurred == b?.blurred }
            .debounce { if (layers.none { !it.leaving }) 0L else settleMs }
            .collect { next ->
                if (next == null) {
                    // No background here: the room fades back to the theme's own.
                    val fading = layers.filterNot { it.leaving }
                    fading.forEach { it.leaving = true }
                    launch {
                        coroutineScope { fading.forEach { l -> launch { l.alpha.animateTo(0f, motion.tween(Durations.BASE, Easings.Fade)) } } }
                        layers.removeAll(fading)
                    }
                    return@collect
                }
                // Another item with the same art (games on their system's background) keeps the layer.
                val top = layers.lastOrNull { !it.leaving }
                if (top?.source?.let { it.model == next.model && it.blurred == next.blurred && (it.id == next.id || next.model != null) } == true) return@collect
                // Drop layers that never finished loading: they belong to items already left behind.
                layers.removeAll { !it.ready && !it.leaving }
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
        val accent = layers.lastOrNull { !it.leaving }?.source?.accent ?: colors.accent
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

/** A glow in [accent]: the room of an item without art, and the placeholder while art loads. */
private fun DrawScope.glow(accent: Color) {
    drawRect(
        Brush.radialGradient(
            listOf(lerp(Color.Black, accent, 0.55f), Color.Black),
            center = Offset(size.width * 0.72f, size.height * 0.3f),
            radius = size.maxDimension * 0.8f,
        ),
    )
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
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = layer.alpha.value }) { glow(source.accent) }
        return
    }
    val context = LocalPlatformContext.current
    // Decoded a little under the screen's size and no larger than the performance profile allows.
    val px = Fuse.quality.heroDecodePx
    val canBlur = Fuse.quality.blur
    val request = remember(source.model, context, px) { heroRequest(context, source.model, px) }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    val state by painter.state.collectAsStateCompat()
    val imageAlpha = remember { Animatable(0f) }
    var placeholder by remember { mutableStateOf(false) }
    LaunchedEffect(layer) {
        val finished = { state is AsyncImagePainter.State.Success || state is AsyncImagePainter.State.Error }
        val quick = withTimeoutOrNull(PLACEHOLDER_MS) { snapshotFlow { finished() }.first { it } } != null
        if (quick) {
            imageAlpha.snapTo(1f)
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
        } else {
            // Slow art: the placeholder replaces the old room now, and the art fades in over it.
            placeholder = true
            settle.snapTo(1f)
            onReady()
            layer.alpha.animateTo(1f, motion.tween(Durations.BASE, Easings.Fade))
            onShown()
            snapshotFlow { finished() }.first { it }
            imageAlpha.animateTo(1f, motion.tween(Durations.HERO, Easings.Fade))
        }
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
        if (placeholder || failed) {
            Canvas(Modifier.fillMaxSize()) { glow(source.accent) }
            val ph = source.placeholder
            if (ph != null && ph != source.model && !failed) {
                val phRequest = remember(ph, context, px) { heroRequest(context, ph, px) }
                Box(Modifier.fillMaxSize().paint(rememberAsyncImagePainter(phRequest, contentScale = ContentScale.Crop), contentScale = ContentScale.Crop))
            }
        }
        if (!failed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha.value }
                    .then(if (source.blurred && canBlur) Modifier.blur(48.dp, BlurredEdgeTreatment.Rectangle) else Modifier)
                    .paint(
                        painter,
                        contentScale = ContentScale.Crop,
                        alignment = focusAlignment(source.focusX, source.focusY),
                    )
                    .drawWithContent {
                        drawContent()
                        // Box art behind the interface is a colour field, not a picture: darker, and more so unblurred.
                        if (source.blurred) drawRect(Color.Black.copy(alpha = if (canBlur) 0.35f else 0.6f))
                    },
            )
        }
    }
}

/** One background decode, the same everywhere, so art warmed ahead of time is found in memory. */
fun heroRequest(context: PlatformContext, model: Any?, px: Int): ImageRequest =
    ImageRequest.Builder(context).data(model).size(px, px).precision(Precision.INEXACT).scale(Scale.FIT).build()
