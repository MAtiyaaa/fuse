package io.github.matiyaaa.fuse.ui.designsystem.media

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options

/** Android plays GIFs and animated WebPs itself (AnimatedImageDrawable), unless only a first frame is asked for. */
actual fun ComponentRegistry.Builder.addMovingPictures() {
    add(StillAware(AnimatedImageDecoder.Factory()))
}

/** [moving], except for a picture asked for as its first frame only ([MovingPictures.Still]). */
private class StillAware(private val moving: Decoder.Factory) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
        if (options.getExtra(MovingPictures.Still)) null else moving.create(result, options, imageLoader)
}
