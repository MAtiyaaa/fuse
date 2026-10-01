package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlinx.coroutines.launch

/**
 * Loads the art of the items around [index] in [models] before they scroll into view, as far as the
 * performance profile allows ([io.github.matiyaaa.fuse.model.RenderQuality.prefetchDepth]: fewer in
 * Low Power). With a [size] (the size the art is drawn at) the images are decoded into the memory
 * cache, so moving onto them shows them at once; without one only the disk cache is warmed.
 *
 * Loads run while the list is on screen (moving on doesn't cancel them) and each image is asked for
 * once.
 */
@Composable
fun PrefetchArt(
    models: List<Any?>,
    index: Int,
    size: Dp? = null,
    /** At most this many on each side, for large art such as backgrounds. */
    limit: Int = Int.MAX_VALUE,
    /** The art fills (crops to) its space, like a background, instead of fitting in it. */
    fill: Boolean = false,
) {
    val depth = Fuse.quality.prefetchDepth.coerceAtMost(limit)
    val context = LocalPlatformContext.current
    val px = size?.let { with(LocalDensity.current) { it.roundToPx() } }
    val scope = rememberCoroutineScope()
    val asked = remember(px) { HashSet<Any>() }
    LaunchedEffect(index, models, depth, px) {
        if (depth <= 0 || index < 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        if (asked.size > MAX_REMEMBERED) asked.clear()
        // Nearest first, so the next item is ready before the ones further away.
        val order = (1..depth).flatMap { listOf(index + it, index - it) }
        for (i in order) {
            val model = models.getOrNull(i) ?: continue
            if (!asked.add(model)) continue
            val request = ImageRequest.Builder(context).data(model)
            if (px != null) {
                request.size(px, px).precision(Precision.INEXACT).scale(if (fill) Scale.FILL else Scale.FIT)
            } else {
                request.memoryCachePolicy(CachePolicy.DISABLED)
            }
            scope.launch { runCatching { loader.execute(request.build()) } }
        }
    }
}

private const val MAX_REMEMBERED = 512
