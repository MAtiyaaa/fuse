package io.github.matiyaaa.fuse.ui.player.ffmpeg

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.Loader

/**
 * FFmpeg's native libraries, loaded once on a thread of their own. The first time, JavaCPP unpacks
 * them under a file lock; a thread interrupted while it waits there (a stream closed by a quick
 * switch) leaves that library's class broken until Fuse restarts, and every video after it has no
 * sound and never ends. Nothing interrupts this thread, so the loading always finishes; a stream
 * waiting for it can be interrupted safely.
 */
internal object FfmpegNatives {
    private val loader = Thread({
        runCatching {
            Loader.load(avutil::class.java)
            Loader.load(avcodec::class.java)
            Loader.load(avformat::class.java)
            Loader.load(avfilter::class.java)
            Loader.load(swscale::class.java)
        }
    }, "fuse-ffmpeg-natives").apply {
        isDaemon = true
        start()
    }

    /** Returns once the libraries are loaded; throws InterruptedException if the caller is interrupted first. */
    fun await() = loader.join()
}
