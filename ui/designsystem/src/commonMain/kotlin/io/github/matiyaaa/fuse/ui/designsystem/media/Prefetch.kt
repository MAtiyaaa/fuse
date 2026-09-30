package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * Downloads the art of the items around [index] in [models] before they scroll into view, as far
 * as the performance profile allows ([io.github.matiyaaa.fuse.model.RenderQuality.prefetchDepth]:
 * fewer in Low Power). Only the disk cache is warmed, so nothing extra is held in memory.
 */
@Composable
fun PrefetchArt(models: List<Any?>, index: Int) {
    val depth = Fuse.quality.prefetchDepth
    val context = LocalPlatformContext.current
    LaunchedEffect(index, models, depth) {
        if (depth <= 0 || index < 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        for (i in (index - depth)..(index + depth)) {
            if (i == index) continue
            val model = models.getOrNull(i) ?: continue
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(model)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build(),
            )
        }
    }
}
