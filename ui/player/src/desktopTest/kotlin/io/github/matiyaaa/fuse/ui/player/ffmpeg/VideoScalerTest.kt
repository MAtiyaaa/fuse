package io.github.matiyaaa.fuse.ui.player.ffmpeg

import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pictures come out the size asked for, in the colours the stream meant. */
class VideoScalerTest {
    /** A [w] by [h] picture of one colour in TV-range Y, Cb, Cr, saying nothing about its colours. */
    private fun solid(w: Int, h: Int, y: Int, cb: Int, cr: Int) = av_frame_alloc().apply {
        width(w).height(h).format(AV_PIX_FMT_YUV420P)
        check(av_frame_get_buffer(this, 0) >= 0)
        for ((plane, value, rows) in listOf(Triple(0, y, h), Triple(1, cb, h / 2), Triple(2, cr, h / 2))) {
            val bytes = ByteArray(linesize(plane) * rows) { value.toByte() }
            data(plane).put(bytes, 0, bytes.size)
        }
    }

    private fun centre(w: Int, h: Int, y: Int, cb: Int, cr: Int, outW: Int = w, outH: Int = h): Triple<Int, Int, Int> {
        val frame = solid(w, h, y, cb, cr)
        VideoScaler().use { scaler ->
            val image = assertNotNull(scaler.scale(frame, outW, outH))
            assertEquals(outW to outH, image.width to image.height)
            val bitmap = Bitmap.makeFromImage(image)
            val c = bitmap.getColor(outW / 2, outH / 2)
            bitmap.close()
            image.close()
            av_frame_free(frame)
            return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
    }

    @Test
    fun anHdFilmSayingNothingIsReadAsHd() {
        // Pure red in BT.709, TV range. Read as SD (BT.601) it came out a dull 233, 0, 2.
        val (r, g, b) = centre(1280, 720, 63, 102, 240)
        assertTrue(r >= 250 && g <= 8 && b <= 8, "red came out $r, $g, $b")
    }

    @Test
    fun anSdPictureSayingNothingIsReadAsSd() {
        // Pure red in BT.601, TV range.
        val (r, g, b) = centre(640, 480, 81, 90, 240)
        assertTrue(r >= 250 && g <= 8 && b <= 8, "red came out $r, $g, $b")
    }

    @Test
    fun tvRangeBlackAndWhiteReachTheEnds() {
        assertEquals(Triple(0, 0, 0), centre(1280, 720, 16, 128, 128))
        val (r, g, b) = centre(1280, 720, 235, 128, 128)
        assertTrue(minOf(r, g, b) >= 254, "white came out $r, $g, $b")
    }

    @Test
    fun aPictureIsMadeAtTheSizeAsked() {
        centre(3840, 2160, 128, 128, 128, outW = 1920, outH = 1080)
        centre(1920, 1080, 128, 128, 128, outW = 3840, outH = 2160)
    }
}
