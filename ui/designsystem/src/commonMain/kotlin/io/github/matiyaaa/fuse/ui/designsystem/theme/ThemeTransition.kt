package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.ThemeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** What a theme change crossfades: everything that changes how the interface looks. */
internal data class ThemeLookKey(val spec: ThemeSpec, val glass: GlassSettings?, val highContrastFocus: Boolean)

/**
 * The state of one theme crossfade: a picture of the interface just before the change, laid over
 * the new look and faded out.
 */
@Stable
private class ThemeCrossfade {
    /** The next frame records itself so it can be pictured. */
    var capturing by mutableStateOf(false)

    /** The old look, while it fades out. */
    var picture by mutableStateOf<ImageBitmap?>(null)

    val fade = Animatable(1f)

    var drawn: CompletableDeferred<Unit>? = null
}

/**
 * Shows [content] in the look [requested], crossfading from the old look over [Durations.THEME]
 * whenever it changes, instead of snapping.
 *
 * It costs one recomposition per change, never one per frame: just before switching, the current
 * frame is recorded once into a picture; then the new look is composed and the picture is drawn
 * over it, fading out in the draw phase alone. Under Reduced motion and in Low Power Mode the look
 * switches at once. If the platform cannot take the picture, the look switches at once as well.
 *
 * [content] receives the look to show, which trails [requested] by the one frame the picture takes.
 */
@Composable
internal fun ThemeTransition(
    requested: ThemeLookKey,
    animate: Boolean,
    content: @Composable (ThemeLookKey) -> Unit,
) {
    var shown by remember { mutableStateOf(requested) }
    val layer = rememberGraphicsLayer()
    val crossfade = remember { ThemeCrossfade() }
    val enabled by rememberUpdatedState(animate)
    LaunchedEffect(requested) {
        if (requested == shown) return@LaunchedEffect
        if (!enabled) {
            crossfade.picture = null
            shown = requested
            return@LaunchedEffect
        }
        val picture = try {
            capture(crossfade, layer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            crossfade.capturing = false
        }
        shown = requested
        if (picture == null) {
            crossfade.picture = null
            return@LaunchedEffect
        }
        crossfade.picture = picture
        crossfade.fade.snapTo(1f)
        try {
            crossfade.fade.animateTo(0f, tween(Durations.THEME, easing = Easings.Fade))
        } finally {
            crossfade.picture = null
        }
    }
    Box(
        Modifier.drawWithContent {
            val picture = crossfade.picture
            if (crossfade.capturing || picture != null) {
                // Recorded with the fading picture in it, so a change during a crossfade starts
                // from exactly what is on screen.
                layer.record {
                    this@drawWithContent.drawContent()
                    if (picture != null) drawImage(picture, alpha = crossfade.fade.value)
                }
                drawLayer(layer)
                if (crossfade.capturing) crossfade.drawn?.complete(Unit)
            } else {
                drawContent()
            }
        },
    ) {
        content(shown)
    }
}

/**
 * Records the next frame into [layer] and returns it as a picture, or null when no frame is drawn
 * in time (a hidden window): the look then switches without a crossfade.
 */
private suspend fun capture(crossfade: ThemeCrossfade, layer: GraphicsLayer): ImageBitmap? {
    val drawn = CompletableDeferred<Unit>()
    crossfade.drawn = drawn
    crossfade.capturing = true
    withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { drawn.await() } ?: return null
    return layer.toImageBitmap()
}

/** A frame is due within a few milliseconds; a window that draws none for this long is hidden. */
private const val CAPTURE_TIMEOUT_MS = 250L
