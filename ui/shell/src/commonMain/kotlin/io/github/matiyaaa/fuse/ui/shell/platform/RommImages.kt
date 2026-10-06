package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.matiyaaa.fuse.ui.shell.store.impl.DefaultRommOps
import io.github.matiyaaa.fuse.ui.shell.store.impl.RommArt
import okio.Path.Companion.toPath

/**
 * Fuse RomM's pictures for Coil. A [RommArt] is fetched once, with Fuse's sign-in, into Fuse's cache
 * and drawn from there: the same picture is never fetched twice (whichever route is in use), and
 * pictures already seen show while the server is away.
 */
object RommImages {
    class Key : Keyer<RommArt> {
        override fun key(data: RommArt, options: Options): String = data.key
    }

    class Fetch : Fetcher.Factory<RommArt> {
        override fun create(data: RommArt, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
            val cache = DefaultRommOps.rommArtCache ?: error("Fuse RomM isn't running")
            val file = cache.file(data.server, data.path) ?: error("RomM's picture couldn't be had")
            SourceFetchResult(ImageSource(file.toPath(), options.fileSystem), null, DataSource.DISK) as FetchResult
        }
    }

    /** A transfer row's picture, kept as text with the transfer. */
    fun model(text: String?): Any? = text?.let { DefaultRommOps.rommArtCache?.parseKey(it) ?: it }
}
