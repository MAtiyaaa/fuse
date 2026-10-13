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
            quietLogs()
        }
    }, "fuse-ffmpeg-natives").apply {
        isDaemon = true
        start()
    }

    /**
     * FFmpeg says only what went wrong, and never a sign-in: at its usual level it printed every
     * address it opened, Jellyfin's access token in each one ("ApiKey=..."), into Fuse's log.
     */
    private fun quietLogs() {
        avutil.av_log_set_level(avutil.AV_LOG_WARNING)
        avutil.setLogCallback(logCallback)
    }

    /** Kept here for as long as FFmpeg may call it. */
    private val logCallback = object : org.bytedeco.ffmpeg.avutil.LogCallback() {
        override fun call(level: Int, msg: org.bytedeco.javacpp.BytePointer?) {
            if (level > avutil.av_log_get_level()) return
            val text = runCatching { msg?.string }.getOrNull() ?: return
            System.err.print("[ffmpeg] " + redact(text))
        }
    }

    private val secrets = Regex("(?i)((?:api_?key|access_?token|token|x-emby-token|x-mediabrowser-token|password|pass|auth)=)[^&\\s'\"]+")

    /** [text] with the values of sign-in parameters (ApiKey, access_token, ...) replaced. */
    internal fun redact(text: String): String = secrets.replace(text) { it.groupValues[1] + "<hidden>" }

    /** Returns once the libraries are loaded; throws InterruptedException if the caller is interrupted first. */
    fun await() = loader.join()
}
