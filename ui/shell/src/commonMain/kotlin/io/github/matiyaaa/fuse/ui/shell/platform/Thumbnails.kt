package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.BitmapImage
import coil3.decode.DataSource
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import io.github.matiyaaa.fuse.model.ArtworkSizing
import io.github.matiyaaa.fuse.ui.designsystem.media.MovingPictures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Fuseline 4.2: small copies of art, made once and kept on disk. The first time a cover, wide art or
 * a logo is shown at tile size it is decoded from its full-size file, as before, and the small copy
 * Coil just made is written beside Fuse's image cache. From then on, at that size, the small file is
 * read instead: a fling through a library after an update (or a cold start) decodes a few kilobytes
 * per tile, not a full picture, so the art keeps up with the scroll.
 *
 * Only local files are copied (art Fuse keeps on disk). A copy is keyed by the file's path, when it
 * last changed and the size bucket, so new art is copied again; the folder is trimmed to [MAX_BYTES].
 * A moving picture (a GIF, an animated WebP) is never copied: a small copy would be its first frame.
 */
class ThumbnailInterceptor(cacheDir: String) : Interceptor {
    private val dir = "${cacheDir.trimEnd('/')}/thumbs".toPath()
    private val fs = FileSystem.SYSTEM
    private val writes = Semaphore(2)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val path = (request.data as? String)?.takeIf(::isLocalPath) ?: return chain.proceed()
        val w = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val h = (chain.size.height as? Dimension.Pixels)?.px ?: return chain.proceed()
        val longest = maxOf(w, h)
        if (longest > MAX_EDGE) return chain.proceed()
        val changed = runCatching { fs.metadataOrNull(path.toPath())?.lastModifiedAtMillis }.getOrNull() ?: return chain.proceed()
        val alpha = path.endsWith(".png", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true) || path.endsWith(".svg", ignoreCase = true)
        if (path.endsWith(".svg", ignoreCase = true)) return chain.proceed()
        // A GIF is read whole, also past a still copy an earlier Fuse made of it.
        if (path.endsWith(".gif", ignoreCase = true)) return chain.proceed()
        val name = "$path|$changed|${ArtworkSizing.bucket(longest)}".encodeUtf8().sha1().hex() + if (alpha) ".png" else ".jpg"
        val thumb = dir / name
        if (runCatching { fs.exists(thumb) }.getOrDefault(false)) {
            val small = chain.withRequest(request.newBuilder().data(thumb.toString()).build()).proceed()
            if (small is SuccessResult) return small
        }
        val result = chain.proceed()
        // Only a still picture, decoded as it moves (not a first frame asked for alone), is copied.
        val still = result is SuccessResult && result.image is BitmapImage && !request.getExtra(MovingPictures.Still)
        if (still && result.dataSource != DataSource.MEMORY_CACHE && result.dataSource != DataSource.MEMORY) {
            val image = (result as SuccessResult).image
            scope.launch {
                writes.withPermit {
                    runCatching {
                        fs.createDirectories(dir)
                        val part = dir / "$name.part"
                        if (saveThumbnail(image, part.toString(), alpha)) fs.atomicMove(part, thumb)
                        trimIfNeeded()
                    }
                }
            }
        }
        return result
    }

    private var written = 0

    /** Keeps the folder under [MAX_BYTES], oldest first; looked at every few hundred copies. */
    private fun trimIfNeeded() {
        if (++written % TRIM_EVERY != 0) return
        val files = fs.listOrNull(dir).orEmpty().mapNotNull { p -> fs.metadataOrNull(p)?.let { p to it } }
        var total = files.sumOf { it.second.size ?: 0L }
        if (total <= MAX_BYTES) return
        for ((p, m) in files.sortedBy { it.second.lastModifiedAtMillis ?: 0L }) {
            if (total <= MAX_BYTES * 3 / 4) break
            runCatching { fs.delete(p) }
            total -= m.size ?: 0L
        }
    }

    private companion object {
        /** Art drawn larger than this (a backdrop, a game's page) is always read from its own file. */
        const val MAX_EDGE = 768
        const val MAX_BYTES = 256L * 1024 * 1024
        const val TRIM_EVERY = 300
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun isLocalPath(s: String) = s.startsWith("/") || (s.length > 2 && s[1] == ':' && (s[2] == '\\' || s[2] == '/'))
    }
}

/** Writes [image] (already decoded at its small size) to [target] as PNG ([alpha]) or JPEG. */
internal expect fun saveThumbnail(image: coil3.Image, target: String, alpha: Boolean): Boolean
