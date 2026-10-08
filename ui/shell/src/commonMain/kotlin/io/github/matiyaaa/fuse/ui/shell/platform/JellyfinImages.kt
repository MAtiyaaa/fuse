package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.ImageLoader
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.Options
import coil3.toUri
import coil3.size.Dimension
import io.github.matiyaaa.fuse.model.ArtworkSizing
import io.github.matiyaaa.fuse.jellyfin.JellyfinArt
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.ktor.client.HttpClient

/**
 * Jellyfin's pictures for Coil. A [JellyfinArt] is fetched from whichever route is in use (home or
 * outside) but cached, in memory and on disk, by the picture itself, so a change of route never
 * fetches anything again and pictures already seen show offline.
 */
object JellyfinImages {
    /** The service whose route pictures come from; set when the store starts. */
    @Volatile
    var service: JellyfinService? = null

    /** The legacy width is only a fallback for requests without a measured layout. */
    internal fun sized(data: JellyfinArt, options: Options): JellyfinArt {
        val width = (options.size.width as? Dimension.Pixels)?.px
        return data.sized(ArtworkSizing.bucket(width ?: data.width.takeIf { it > 0 } ?: 1024))
    }

    class Key : Keyer<JellyfinArt> {
        override fun key(data: JellyfinArt, options: Options): String = sized(data, options).key
    }

    class Fetch(http: HttpClient) : Fetcher.Factory<JellyfinArt> {
        private val network = KtorNetworkFetcherFactory(httpClient = { http })

        override fun create(data: JellyfinArt, options: Options, imageLoader: ImageLoader): Fetcher? {
            val sized = sized(data, options)
            val url = service?.imageUrl(sized) ?: return null
            return network.create(url.toUri(), options.copy(diskCacheKey = sized.key), imageLoader)
        }
    }
}
