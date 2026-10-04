package io.github.matiyaaa.fuse.ui.player.ffmpeg

import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.VideoCodec
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name

/**
 * What the desktop engine plays, from the decoders this FFmpeg build really has. Pictures up to
 * 4K are decoded (and shown at up to 1080p); 10-bit is fine; HDR is left to the server to tone
 * map, since the desktop shows SDR. Surround sound is folded to stereo here, so any channel count
 * plays as it is.
 */
internal object FfmpegCapabilities {
    @Volatile private var cached: Capabilities? = null

    fun detect(hardware: Boolean): Capabilities {
        val base = cached ?: build().also { cached = it }
        return base.copy(hardwareDecoding = hardware)
    }

    private fun has(name: String): Boolean = runCatching { avcodec_find_decoder_by_name(name) != null }.getOrDefault(false)

    private fun build(): Capabilities {
        val video = buildList {
            if (has("h264")) add(VideoCodec("h264", 3840, 2160, 8, profiles = setOf("high", "main", "baseline", "constrained baseline"), maxLevel = 52))
            if (has("hevc")) add(VideoCodec("hevc", 3840, 2160, 10, profiles = setOf("main", "main 10")))
            if (has("av1") || has("libdav1d")) add(VideoCodec("av1", 3840, 2160, 10))
            if (has("vp9")) add(VideoCodec("vp9", 3840, 2160, 10))
            if (has("vp8")) add(VideoCodec("vp8", 1920, 1080, 8))
            if (has("mpeg2video")) add(VideoCodec("mpeg2video", 1920, 1080, 8))
            if (has("mpeg4")) add(VideoCodec("mpeg4", 1920, 1080, 8))
            if (has("vc1")) add(VideoCodec("vc1", 1920, 1080, 8))
        }
        val audio = listOf("aac", "mp3", "ac3", "eac3", "dca", "truehd", "flac", "opus", "vorbis", "alac", "mp2", "pcm_s16le", "pcm_s24le")
            .filter { has(it) }
            .map { if (it == "dca") "dts" else it }
            .toMutableSet()
        if (audio.any { it.startsWith("pcm") }) audio += "pcm"
        val subtitles = listOf("pgssub", "dvdsub", "subrip", "ass", "ssa", "webvtt", "mov_text", "dvbsub")
            .filter { has(it) || (it == "ssa" && has("ass")) }
            .toSet()
        return Capabilities(
            videoCodecs = video,
            audioCodecs = audio,
            maxAudioChannels = 8,
            containers = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "mpegts", "avi", "m4a", "mp3", "flac", "ogg", "wav", "aac"),
            embeddedSubtitles = subtitles,
            hls = true,
        )
    }
}
