package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * Loads the art of the items around [index] in [models] before they scroll into view, as far as the
 * performance profile allows ([io.github.matiyaaa.fuse.model.RenderQuality.prefetchDepth]: fewer in
 * Low Power). With a [size] (the size the art is drawn at) the images are decoded into the memory
 * cache, so moving onto them shows them at once; without one only the disk cache is warmed.
 */
@Composable
fun PrefetchArt(models: List<Any?>, index: Int, size: Dp? = null) {
    val depth = Fuse.quality.prefetchDepth
    val context = LocalPlatformContext.current
    val px = size?.let { with(LocalDensity.current) { it.roundToPx() } }
    LaunchedEffect(index, models, depth, px) {
        if (depth <= 0 || index < 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        // Nearest first, so the next item is ready before the ones further away.
        val order = (1..depth).flatMap { listOf(index + it, index - it) }
        for (i in order) {
            val model = models.getOrNull(i) ?: continue
            val request = ImageRequest.Builder(context).data(model)
            if (px != null) {
                request.size(px, px).precision(Precision.INEXACT)
            } else {
                request.memoryCachePolicy(CachePolicy.DISABLED)
            }
            loader.enqueue(request.build())
        }
    }
}
