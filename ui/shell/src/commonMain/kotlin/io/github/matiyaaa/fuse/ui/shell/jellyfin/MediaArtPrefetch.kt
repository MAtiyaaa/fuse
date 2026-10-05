package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
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

    /** Readies [items] around [index]: nearest first, two behind and three ahead. */
    fun around(context: PlatformContext, scope: CoroutineScope, items: List<MediaItem>, index: Int, heroPx: Int) {
        if (items.isEmpty()) return
        val order = listOf(0, 1, -1, 2, 3, -2).mapNotNull { items.getOrNull(index + it) }
        val loader = SingletonImageLoader.get(context)
        for (item in order) {
            val backdrop = (item.backdrop ?: item.thumb ?: item.poster)?.sized(BACKDROP_WIDTH)
            val logo = item.logo?.sized(WIDE_WIDTH)
            if (backdrop != null && firstTime("b:${item.id}:$heroPx")) {
                scope.launch { gate.withPermit { loader.execute(heroRequest(context, backdrop, heroPx)) } }
            }
            if (logo != null && firstTime("l:${item.id}")) {
                scope.launch { gate.withPermit { loader.execute(ImageRequest.Builder(context).data(logo).build()) } }
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
