package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * Loads artwork with Coil (memory + disk cache, decoded at the size it is drawn, cancelled when the
 * composable leaves). Until the image is ready, and if it fails or is missing, [fallback] is shown
 * instead of an empty box, so a library with no scraped art still looks designed.
 *
 * [focusX]/[focusY] (0..1) choose which part of the image stays visible when it is cropped.
 * [tint] recolours the image (single-colour logos, such as system logos, drawn in white).
 */
@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    focusX: Float = 0.5f,
    focusY: Float = 0.5f,
    zoom: Float = 1f,
    fadeIn: Boolean = true,
    tint: Color? = null,
    fallback: @Composable () -> Unit = {},
) {
    if (model == null) {
        Box(modifier) { fallback() }
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(model, context) {
        ImageRequest.Builder(context).data(model).crossfade(false).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = contentScale)
    val state by painter.state.collectAsStateCompat()
    val loaded = state is AsyncImagePainter.State.Success
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = if (fadeIn) Fuse.motion.fade(Durations.FAST) else Fuse.motion.fade(0),
        label = "art",
    )
    Box(modifier) {
        if (!loaded || alpha < 1f) fallback()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (zoom != 1f) {
                        scaleX = zoom
                        scaleY = zoom
                    }
                }
                .alpha(alpha)
                .paint(
                    painter,
                    contentScale = contentScale,
                    alignment = BiasAlignment(focusX * 2 - 1, focusY * 2 - 1),
                    colorFilter = tint?.let { ColorFilter.tint(it) },
                ),
        )
    }
}

/** Alignment helper for callers positioning cropped art. */
fun focusAlignment(x: Float, y: Float): Alignment = BiasAlignment(x * 2 - 1, y * 2 - 1)
