package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.size.Dimension
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

actual fun ComponentRegistry.Builder.addMovingPictures() {
    add(SkiaMovingDecoder.Factory())
}

/**
 * Decodes a GIF or an animated WebP into all its frames once, each composited as the file says and
 * scaled to the size it is drawn at (smaller still where every frame would not fit [BUDGET_BYTES]),
 * for [MovingSkiaImage] to play. A file with one frame comes back as a plain picture.
 */
internal class SkiaMovingDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.use { it.source().readByteArray() }
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        try {
            val w = codec.width
            val h = codec.height
            val count = codec.frameCount.coerceIn(1, MAX_FRAMES)
            val dstW = (options.size.width as? Dimension.Pixels)?.px ?: w
            val dstH = (options.size.height as? Dimension.Pixels)?.px ?: h
            val fit = DecodeUtils.computeSizeMultiplier(w, h, dstW, dstH, options.scale).toFloat()
            val budget = sqrt(BUDGET_BYTES.toDouble() / (w.toDouble() * h * 4 * count)).toFloat()
            val scale = minOf(1f, fit, budget)
            val outW = max(1, (w * scale).roundToInt())
            val outH = max(1, (h * scale).roundToInt())

            val work = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
            val info = codec.framesInfo
            val frames = ArrayList<Image>(count)
            val durations = IntArray(count)
            for (i in 0 until count) {
                val required = info.getOrNull(i)?.requiredFrame ?: -1
                val prior = i - 1
                // The frame before is still in [work]: build on it, as the file allows, else let Skia
                // decode the frames this one needs.
                val reuse = required >= 0 && prior >= required && info[prior].disposalMethod != AnimationDisposalMode.RESTORE_PREVIOUS
                val ok = runCatching {
                    if (reuse) {
                        codec.readPixels(work, i, prior)
                    } else {
                        work.erase(0)
                        codec.readPixels(work, i)
                    }
                }.isSuccess
                // A file cut short plays the frames it has.
                if (!ok) break
                frames += snapshot(work, outW, outH)
                // As browsers do, a frame asking for no time at all is shown for a tenth of a second.
                durations[i] = (info.getOrNull(i)?.duration ?: 0).let { if (it <= 10) 100 else it }
            }
            work.close()
            check(frames.isNotEmpty()) { "No frames in the picture" }
            if (frames.size == 1) {
                val still = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(outW, outH)) }
                frames[0].readPixels(still)
                frames[0].close()
                return DecodeResult(still.apply { setImmutable() }.asImage(), isSampled = scale < 1f)
            }
            return DecodeResult(MovingSkiaImage(frames, durations.copyOf(frames.size), codec.repetitionCount), isSampled = scale < 1f)
        } finally {
            codec.close()
        }
    }

    /** A copy of [work] at [w] by [h] that stays as it is while [work] moves on. */
    private fun snapshot(work: Bitmap, w: Int, h: Int): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            Image.makeFromBitmap(work).use { frame ->
                surface.canvas.drawImageRect(frame, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
            }
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    /** Takes GIFs and animated WebPs, unless only their first frame is asked for ([MovingPictures.Still]). */
    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (options.getExtra(MovingPictures.Still)) return null
            val peek = result.source.source().peek()
            val header = if (peek.request(HEADER.toLong())) peek.readByteArray(HEADER.toLong()) else peek.readByteArray()
            return if (MovingPictures.isMoving(header)) SkiaMovingDecoder(result.source, options) else null
        }
    }

    private companion object {
        const val HEADER = 32
        /** All the frames of one picture together, decoded: a long GIF is drawn smaller to stay inside it. */
        const val BUDGET_BYTES = 96L * 1024 * 1024
        const val MAX_FRAMES = 1_000
    }
}

/**
 * A moving picture's frames, played in turn with each one's own time and looping as the file asks.
 * One place plays it at a time ([play] waits its turn), however many show it, so it never runs fast.
 */
internal class MovingSkiaImage(
    private val frames: List<Image>,
    private val durations: IntArray,
    /** How many times it plays again after the first (-1: for ever). */
    private val repeats: Int,
) : coil3.Image, MovingPicture {
    private var index by mutableIntStateOf(0)
    private val turn = Mutex()
    private val paint = Paint()

    override val width: Int = frames[0].width
    override val height: Int = frames[0].height
    override val size: Long = frames.sumOf { it.width.toLong() * it.height * 4 }
    override val shareable: Boolean = true

    /** How many frames it has, how long each shows and which is shown now. */
    internal val frameCount: Int get() = frames.size
    internal val frameMillis: List<Int> get() = durations.toList()
    internal val shown: Int get() = index

    override fun draw(canvas: Canvas) {
        // Reading [index] here is what redraws the art as the frames change.
        val frame = frames[index]
        canvas.drawImageRect(frame, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.LINEAR, paint, true)
    }

    override suspend fun play() = turn.withLock {
        var played = 0
        while (true) {
            delay(durations[index].toLong())
            // On the next frame drawn: nothing moves while the window is hidden.
            withFrameMillis { }
            val next = index + 1
            if (next < frames.size) {
                index = next
            } else {
                if (repeats >= 0 && ++played > repeats) return@withLock
                index = 0
            }
        }
    }
}
