package io.github.matiyaaa.fuse.model

import kotlin.math.ceil
import kotlin.math.sqrt

/** Physical pixels, after density and interface scaling, not a fixed handheld request size. */
data class ArtworkPixels(val width: Int, val height: Int)

/**
 * Stable buckets avoid a new server image/cache entry for every animated resize pixel. The budget
 * is per decode, never permission to upscale a smaller source. Source limits, when known, are
 * applied last. A modest focus reserve keeps selected cards sharp without requesting TV art for
 * every handheld tile.
 */
object ArtworkSizing {
    private val buckets = intArrayOf(128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 2560, 3072, 4096)

    fun bucket(pixels: Int): Int = buckets.firstOrNull { it >= pixels.coerceAtLeast(1) } ?: buckets.last()

    fun decode(
        widthPx: Int,
        heightPx: Int,
        enlargement: Float = 1.15f,
        maxPixels: Long = 8_388_608,
        sourceWidth: Int? = null,
        sourceHeight: Int? = null,
    ): ArtworkPixels {
        require(maxPixels > 0)
        val reserve = enlargement.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 1f
        var w = bucket(ceil(widthPx.coerceAtLeast(1) * reserve).toInt())
        var h = bucket(ceil(heightPx.coerceAtLeast(1) * reserve).toInt())
        val area = w.toLong() * h
        if (area > maxPixels) {
            val scale = sqrt(maxPixels.toDouble() / area)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
            // A very narrow layout can round one dimension up to one after scaling. Keep
            // the promised area bound even for callers with a tiny decode budget.
            if (w.toLong() * h > maxPixels) {
                if (w >= h) w = (maxPixels / h).toInt().coerceAtLeast(1)
                else h = (maxPixels / w).toInt().coerceAtLeast(1)
            }
        }
        sourceWidth?.takeIf { it > 0 }?.let { w = minOf(w, it) }
        sourceHeight?.takeIf { it > 0 }?.let { h = minOf(h, it) }
        return ArtworkPixels(w, h)
    }
}
