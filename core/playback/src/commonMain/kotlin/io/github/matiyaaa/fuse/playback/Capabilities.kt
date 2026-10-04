package io.github.matiyaaa.fuse.playback

/** Kinds of high dynamic range video. */
enum class HdrFormat { HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

/**
 * One video codec a player decodes, and how far: the largest picture, the deepest colour, the
 * profiles and level, and which HDR it shows as HDR (anything else needs tone mapping by the server).
 * Codec names are the short lower-case ones servers use: h264, hevc, av1, vp9, mpeg2video.
 */
data class VideoCodec(
    val codec: String,
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val maxBitDepth: Int = 8,
    val profiles: Set<String> = emptySet(),
    val maxLevel: Int? = null,
    val hdr: Set<HdrFormat> = emptySet(),
)

/**
 * What a player can play by itself, measured on the device (its decoders, its screen): a provider
 * builds its device profile from this, so the server converts only what the player can't play.
 */
data class Capabilities(
    val videoCodecs: List<VideoCodec>,
    /** Audio codecs by short name: aac, mp3, ac3, eac3, dts, truehd, flac, opus, vorbis, alac, pcm. */
    val audioCodecs: Set<String>,
    val maxAudioChannels: Int = 2,
    /** Containers it opens: mp4, mkv, webm, mov, ts, avi, m4a, mp3, flac, ogg. */
    val containers: Set<String>,
    /** Subtitle formats it decodes inside a stream (bitmap ones like pgssub and dvdsub included). */
    val embeddedSubtitles: Set<String> = emptySet(),
    val hls: Boolean = true,
    /** Decoding in hardware first; off asks for software decoders where the platform has them. */
    val hardwareDecoding: Boolean = true,
) {
    fun video(codec: String): VideoCodec? = videoCodecs.firstOrNull { it.codec.equals(codec, ignoreCase = true) }

    fun playsAudio(codec: String?): Boolean = codec != null && audioCodecs.any { it.equals(codec, ignoreCase = true) }

    companion object {
        /** Text subtitles Fuse draws itself from a file of their own, on every platform. */
        val TEXT_SUBTITLES = setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")

        /** Bitmap subtitles, which come as pictures and can't be turned into text. */
        val BITMAP_SUBTITLES = setOf("pgssub", "pgs", "dvdsub", "dvd_subtitle", "vobsub", "dvbsub")
    }
}
