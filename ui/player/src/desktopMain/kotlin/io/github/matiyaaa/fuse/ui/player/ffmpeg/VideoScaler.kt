package io.github.matiyaaa.fuse.ui.player.ffmpeg

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_MPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_UNSPECIFIED
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE170M
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_UNSPECIFIED
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_FLAG_RGB
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_pix_fmt_desc_get
import org.bytedeco.ffmpeg.global.swscale.SWS_ACCURATE_RND
import org.bytedeco.ffmpeg.global.swscale.SWS_BICUBIC
import org.bytedeco.ffmpeg.global.swscale.SWS_FULL_CHR_H_INT
import org.bytedeco.ffmpeg.global.swscale.sws_alloc_context
import org.bytedeco.ffmpeg.global.swscale.sws_frame_setup
import org.bytedeco.ffmpeg.global.swscale.sws_free_context
import org.bytedeco.ffmpeg.global.swscale.sws_scale_frame
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * A decoded picture to a Skia image the screen draws as it is, on the decoding thread.
 *
 * FFmpeg converts it to BGRA with every core it has (one alone couldn't keep up with 4K), with a
 * bicubic filter, full-resolution colour and exact rounding, so gradients in a film stay smooth.
 * The colours follow what the stream says it is; a stream that says nothing is taken as HD (BT.709)
 * from 720 lines up and as SD (BT.601) below, in TV range, the way players and TVs read it: read as
 * SD, an HD film's reds went orange and its greens dull.
 *
 * The picture is copied once, straight into the image's own memory, so the interface thread only
 * hands it to the graphics card (it used to copy it twice there for every picture).
 */
internal class VideoScaler : AutoCloseable {
    private var sws: SwsContext? = null
    private val dst: AVFrame = av_frame_alloc()
    private var key = ""

    /** Converts [src] to a [width] by [height] image; null when FFmpeg can't. */
    fun scale(src: AVFrame, width: Int, height: Int): Image? {
        describeColours(src)
        val k = "${src.width()}x${src.height()}:${src.format()}:${src.colorspace()}:${src.color_range()}>${width}x$height"
        if (k != key && !setUp(src, width, height, k)) return null
        if (sws_scale_frame(sws, dst, src) < 0) return null
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val bitmap = Bitmap()
        try {
            if (!bitmap.allocPixels(info)) return null
            val to = bitmap.peekPixels()?.addr ?: return null
            val toStride = bitmap.rowBytes.toLong()
            val from = dst.data(0).address()
            val fromStride = dst.linesize(0).toLong()
            val row = width * 4L
            if (toStride == fromStride) {
                Pointer.memcpy(At(to), At(from), toStride * height)
            } else {
                for (y in 0 until height) Pointer.memcpy(At(to + y * toStride), At(from + y * fromStride), row)
            }
            // Immutable, its memory becomes the image's own without another copy.
            bitmap.setImmutable()
            return Image.makeFromBitmap(bitmap)
        } finally {
            bitmap.close()
        }
    }

    private fun setUp(src: AVFrame, width: Int, height: Int, k: String): Boolean {
        sws?.let { sws_free_context(it) }
        av_frame_unref(dst)
        key = ""
        dst.width(width).height(height).format(AV_PIX_FMT_BGRA).color_range(AVCOL_RANGE_JPEG)
        if (av_frame_get_buffer(dst, 64) < 0) return false
        val ctx = sws_alloc_context() ?: return false
        sws = ctx
        ctx.flags(SWS_BICUBIC or SWS_FULL_CHR_H_INT or SWS_ACCURATE_RND)
        ctx.threads(THREADS)
        if (sws_frame_setup(ctx, dst, src) < 0) return false
        key = k
        return true
    }

    override fun close() {
        sws?.let { sws_free_context(it) }
        sws = null
        av_frame_free(dst)
    }

    /** Pointer at a raw address, for copying between FFmpeg's memory and Skia's. */
    private class At(address: Long) : Pointer() {
        init {
            this.address = address
        }
    }

    companion object {
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

        /** Fills in what [frame] leaves unsaid about its colours (see the class). Pictures in RGB are left as they are. */
        internal fun describeColours(frame: AVFrame) {
            val rgb = av_pix_fmt_desc_get(frame.format())?.let { (it.flags() and AV_PIX_FMT_FLAG_RGB.toLong()) != 0L } ?: false
            if (rgb) return
            if (frame.colorspace() == AVCOL_SPC_UNSPECIFIED) {
                frame.colorspace(if (frame.height() >= 720 || frame.width() >= 1280) AVCOL_SPC_BT709 else AVCOL_SPC_SMPTE170M)
            }
            if (frame.color_range() == AVCOL_RANGE_UNSPECIFIED) frame.color_range(AVCOL_RANGE_MPEG)
        }
    }
}
