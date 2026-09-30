package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlinx.coroutines.delay

/**
 * Loads artwork with Coil (memory + disk cache, decoded at the size it is drawn, cancelled when the
 * composable leaves). If the image is missing or fails, [fallback] is shown instead of an empty
 * box, so a library with no scraped art still looks designed.
 *
 * While the image loads, nothing is drawn for [fallbackDelayMs] and the fallback only appears if
 * loading takes longer, so a title never flashes up before a logo that was just a moment away. Art
 * that comes straight from the memory cache appears at once, without the fade.
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
    fallbackDelayMs: Long = 400,
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
    val success = state as? AsyncImagePainter.State.Success
    val failed = state is AsyncImagePainter.State.Error
    val fromMemory = success?.result?.dataSource == DataSource.MEMORY_CACHE
    val alpha = remember(model) { Animatable(0f) }
    val fade = Fuse.motion.fade<Float>(Durations.FAST)
    LaunchedEffect(success != null, fromMemory) {
        when {
            success == null -> alpha.snapTo(0f)
            !fadeIn || fromMemory -> alpha.snapTo(1f)
            else -> alpha.animateTo(1f, fade)
        }
    }
    // Quick loads (memory or disk) never show the fallback; slow ones (network) do after a moment.
    var slow by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        delay(fallbackDelayMs)
        slow = true
    }
    Box(modifier) {
        if (failed || (slow && alpha.value < 1f)) fallback()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (zoom != 1f) {
                        scaleX = zoom
                        scaleY = zoom
                    }
                    this.alpha = alpha.value
                }
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
