package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.matiyaaa.fuse.model.ArtworkSizing
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.ui.designsystem.media.heroDecodePx
import io.github.matiyaaa.fuse.ui.designsystem.media.heroRequest
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Gets the second screen's art ready before it is asked for: the backdrop and title logo of what
 * is in focus and of its neighbours on either side, so moving along a shelf shows them on the
 * other screen at once instead of after a trip to the server. Backdrops are decoded exactly as
 * the background decodes them (so they are found in memory); logos are fetched to disk, which is
 * quick enough to read when they are drawn. Each picture is asked for once.
 */
internal object MediaArtPrefetch {
    private const val KEEP = 512
    private val asked = object : LinkedHashMap<String, Unit>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > KEEP
    }
    private val gate = Semaphore(3)

    private fun firstTime(key: String): Boolean = synchronized(asked) { asked.put(key, Unit) == null }
    private fun retry(key: String) = synchronized(asked) { asked.remove(key) }

    /** Failed/offline prefetches are not permanently remembered as ready. */
    private fun prefetch(scope: CoroutineScope, key: String, run: suspend () -> Boolean) {
        if (!firstTime(key)) return
        scope.launch {
            try { gate.withPermit { if (!run()) retry(key) } }
            catch (e: kotlinx.coroutines.CancellationException) { retry(key); throw e }
            catch (_: Throwable) { retry(key) }
        }
    }

    /** Readies [items] around [index]: nearest first, two behind and three ahead. */
    fun around(context: PlatformContext, scope: CoroutineScope, items: List<MediaItem>, index: Int, heroPx: Int) {
        if (items.isEmpty()) return
        val order = listOf(0, 1, -1, 2, 3, -2).mapNotNull { items.getOrNull(index + it) }
        val loader = SingletonImageLoader.get(context)
        for (item in order) {
            val backdrop = (item.backdrop ?: item.thumb ?: item.poster)?.sized(ArtworkSizing.bucket(heroPx))
            val logoPx = ArtworkSizing.bucket((heroPx / 3).coerceAtLeast(256))
            val logo = item.logo?.sized(logoPx)
            if (backdrop != null) prefetch(scope, "b:${backdrop.key}:$heroPx") {
                loader.execute(heroRequest(context, backdrop, heroPx)) is SuccessResult
            }
            if (logo != null) prefetch(scope, "l:${logo.key}:$logoPx") {
                loader.execute(ImageRequest.Builder(context).data(logo).size(logoPx, logoPx).build()) is SuccessResult
            }
        }
    }
}

/** Readies the art around [index] of [items] whenever either changes (see [MediaArtPrefetch]). */
@Composable
internal fun PrefetchMediaArt(items: List<MediaItem>, index: Int) {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val heroPx = Fuse.quality.heroDecodePx
    LaunchedEffect(items, index, heroPx) { MediaArtPrefetch.around(context, scope, items, index.coerceAtLeast(0), heroPx) }
}
