package io.github.matiyaaa.fuse.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Display
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.matiyaaa.fuse.playback.BitmapCue
import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.HdrFormat
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.Span
import io.github.matiyaaa.fuse.playback.TextCue
import io.github.matiyaaa.fuse.playback.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.media3.common.text.Cue as Media3Cue

/**
 * Android's player engine, on Media3 (ExoPlayer, with HLS): decoding to a SurfaceView, subtitles
 * handed to Fuse's own layer as cues (Media3 draws none), and capabilities measured from the
 * device's decoders and screen, so HDR is only claimed where both really show it. With hardware
 * decoding off, software decoders are chosen first where the device has them.
 */
@OptIn(UnstableApi::class)
class Media3Engine(private val context: Context) : PlayerEngine {
    private val stateFlow = MutableStateFlow(EngineState())
    private val cueFlow = MutableStateFlow<List<Cue>>(emptyList())
    override val state: StateFlow<EngineState> = stateFlow
    override val cues: StateFlow<List<Cue>> = cueFlow

    private val main = Handler(Looper.getMainLooper())
    private var hardware = true
    private var player: ExoPlayer? = null
    private var wantedAudio: Int? = null
    private var wantedSubtitle: Int? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            stateFlow.update { it.copy(positionMs = p.currentPosition.coerceAtLeast(0), bufferedMs = p.bufferedPosition.coerceAtLeast(0)) }
            main.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            stateFlow.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height, pixelRatio = videoSize.pixelWidthHeightRatio) }
        }

        override fun onTracksChanged(tracks: Tracks) = applyTracks()

        override fun onCues(cueGroup: CueGroup) {
            cueFlow.value = cueGroup.cues.mapNotNull { convert(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            stateFlow.update { it.copy(status = EngineStatus.ERROR, error = error.errorCodeName.lowercase().replace('_', ' '), playing = false) }
        }
    }

    private fun player(): ExoPlayer = player ?: build().also { player = it }

    private fun build(): ExoPlayer {
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (hardware) all else all.sortedBy { it.hardwareAccelerated }
        }
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector)
        return ExoPlayer.Builder(context, renderers).build().also { it.addListener(listener) }
    }

    override fun load(source: PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean) {
        val p = player()
        wantedAudio = audioOrder
        wantedSubtitle = subtitleOrder
        cueFlow.value = emptyList()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Fuse")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(source.headers)
        val item = MediaItem.Builder()
            .setUri(source.url)
            .apply { if (source.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item), startMs)
        p.prepare()
        p.playWhenReady = play
        stateFlow.value = EngineState(status = EngineStatus.LOADING, positionMs = startMs)
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun play() {
        player?.playWhenReady = true
    }

    override fun pause() {
        player?.playWhenReady = false
    }

    override fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
    }

    override fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun selectAudio(order: Int) {
        wantedAudio = order
        applyTracks()
    }

    override fun selectSubtitle(order: Int?) {
        wantedSubtitle = order
        cueFlow.value = emptyList()
        applyTracks()
    }

    /** The audio and subtitle tracks wanted, by their order among the stream's tracks of that kind. */
    private fun applyTracks() {
        val p = player ?: return
        val groups = p.currentTracks.groups
        val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val b = p.trackSelectionParameters.buildUpon()
        wantedAudio?.let { order ->
            audio.getOrNull(order)?.let { b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        val sub = wantedSubtitle?.let { text.getOrNull(it) }
        if (sub == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setOverrideForType(TrackSelectionOverride(sub.mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = b.build()
    }

    private fun publish() {
        val p = player ?: return
        val status = when (p.playbackState) {
            Player.STATE_IDLE -> if (stateFlow.value.status == EngineStatus.ERROR) EngineStatus.ERROR else EngineStatus.IDLE
            Player.STATE_BUFFERING -> if (stateFlow.value.status == EngineStatus.LOADING) EngineStatus.LOADING else EngineStatus.BUFFERING
            Player.STATE_READY -> EngineStatus.READY
            Player.STATE_ENDED -> EngineStatus.ENDED
            else -> EngineStatus.IDLE
        }
        val decoder = p.videoFormat?.let { f -> f.codecs ?: f.sampleMimeType }
        stateFlow.update {
            it.copy(
                status = status,
                playing = p.playWhenReady && status != EngineStatus.ENDED,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 },
                decoder = decoder,
                hardware = hardware,
            )
        }
    }

    override fun positionMs(): Long = player?.currentPosition?.coerceAtLeast(0) ?: stateFlow.value.positionMs

    override fun capabilities(hardwareDecoding: Boolean): Capabilities {
        hardware = hardwareDecoding
        return AndroidCapabilities.detect(context, hardwareDecoding)
    }

    override fun stop() {
        main.removeCallbacks(ticker)
        player?.stop()
        player?.clearMediaItems()
        stateFlow.value = EngineState()
        cueFlow.value = emptyList()
    }

    override fun release() {
        stop()
        player?.release()
        player = null
    }

    @Composable
    override fun Video(modifier: Modifier) {
        val p = player()
        AndroidView(
            factory = { ctx -> SurfaceView(ctx) },
            update = { view -> p.setVideoSurfaceView(view) },
            onRelease = { view -> p.clearVideoSurfaceView(view) },
            modifier = modifier,
        )
        DisposableEffect(p) { onDispose { } }
    }

    // Cues -------------------------------------------------------------------------------------------

    private fun convert(c: Media3Cue): Cue? {
        val bitmap = c.bitmap
        if (bitmap != null) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.copy(Bitmap.Config.ARGB_8888, false).getPixels(pixels, 0, w, 0, 0, w, h)
            val left = c.position.takeIf { it != Media3Cue.DIMEN_UNSET } ?: 0f
            val top = c.line.takeIf { it != Media3Cue.DIMEN_UNSET } ?: 0f
            val width = c.size.takeIf { it != Media3Cue.DIMEN_UNSET } ?: 1f
            val height = c.bitmapHeight.takeIf { it != Media3Cue.DIMEN_UNSET } ?: (width * h / w)
            return BitmapCue(0, Long.MAX_VALUE, w, h, pixels, left, top, left + width, top + height)
        }
        val text = c.text ?: return null
        val lines = spans(text)
        if (lines.isEmpty()) return null
        val top = c.lineType == Media3Cue.LINE_TYPE_FRACTION && c.line != Media3Cue.DIMEN_UNSET && c.line < 0.5f
        val horizontal = when (c.textAlignment) {
            android.text.Layout.Alignment.ALIGN_NORMAL -> 0
            android.text.Layout.Alignment.ALIGN_OPPOSITE -> 2
            else -> 1
        }
        return TextCue(0, Long.MAX_VALUE, lines, align = (if (top) 7 else 1) + horizontal)
    }

    /** A cue's styled text as lines of [Span]s: bold, italic, underline and colour kept. */
    private fun spans(text: CharSequence): List<List<Span>> {
        val out = ArrayList<List<Span>>()
        var lineStart = 0
        val s = text.toString()
        while (lineStart <= s.length) {
            val end = s.indexOf('\n', lineStart).let { if (it < 0) s.length else it }
            val line = ArrayList<Span>()
            var i = lineStart
            while (i < end) {
                // Runs of the same styling.
                val next = if (text is Spanned) text.nextSpanTransition(i, end, Any::class.java) else end
                val chunk = s.substring(i, next)
                var bold = false
                var italic = false
                var underline = false
                var color: Long? = null
                if (text is Spanned) {
                    for (span in text.getSpans(i, next, Any::class.java)) {
                        when (span) {
                            is StyleSpan -> {
                                if (span.style and Typeface.BOLD != 0) bold = true
                                if (span.style and Typeface.ITALIC != 0) italic = true
                            }
                            is UnderlineSpan -> underline = true
                            is ForegroundColorSpan -> color = span.foregroundColor.toLong() and 0xFFFFFFFFL
                        }
                    }
                }
                if (chunk.isNotEmpty()) line += Span(chunk, bold, italic, underline, color)
                i = next
            }
            out += line
            lineStart = end + 1
        }
        return out.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    }

    companion object {
        private const val TICK_MS = 250L
    }
}

/** What this Android device plays, from its decoders and its screen. */
internal object AndroidCapabilities {
    @Volatile private var cached: Capabilities? = null

    fun detect(context: Context, hardware: Boolean): Capabilities {
        val base = cached ?: build(context).also { cached = it }
        return base.copy(hardwareDecoding = hardware)
    }

    private fun build(context: Context): Capabilities {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun decoders(mime: String) = list.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val screenHdr = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        fun video(codec: String, mime: String, hdrProfiles: Map<Int, HdrFormat> = emptyMap()): VideoCodec? {
            val infos = decoders(mime)
            if (infos.isEmpty()) return null
            var w = 0
            var h = 0
            var depth = 8
            val hdr = HashSet<HdrFormat>()
            for (info in infos) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                caps.videoCapabilities?.let {
                    w = maxOf(w, it.supportedWidths.upper)
                    h = maxOf(h, it.supportedHeights.upper)
                }
                for (pl in caps.profileLevels) {
                    if (mime == "video/hevc" && pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) depth = 10
                    if (mime == "video/av01" && pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10) depth = 10
                    if (mime == "video/x-vnd.on2.vp9" && pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2) depth = 10
                    hdrProfiles[pl.profile]?.let { f -> if (screenSupports(f, screenHdr)) hdr += f }
                }
            }
            if (hdr.isNotEmpty()) depth = 10
            return VideoCodec(codec, w.coerceAtMost(7680), h.coerceAtMost(4320), depth, hdr = hdr)
        }
        val videoCodecs = listOfNotNull(
            video("h264", "video/avc"),
            video(
                "hevc", "video/hevc",
                mapOf(
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 to HdrFormat.HDR10,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus to HdrFormat.HDR10_PLUS,
                ),
            ),
            video("av1", "video/av01", mapOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 to HdrFormat.HDR10)),
            video("vp9", "video/x-vnd.on2.vp9", mapOf(MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR to HdrFormat.HDR10)),
            video("vp8", "video/x-vnd.on2.vp8"),
            video("mpeg2video", "video/mpeg2"),
            video("mpeg4", "video/mp4v-es"),
        )
        val audio = linkedMapOf(
            "aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "ac3" to "audio/ac3", "eac3" to "audio/eac3",
            "dts" to "audio/vnd.dts", "truehd" to "audio/true-hd", "flac" to "audio/flac", "opus" to "audio/opus",
            "vorbis" to "audio/vorbis", "alac" to "audio/alac", "pcm" to "audio/raw",
        ).filter { (name, mime) -> name == "pcm" || decoders(mime).isNotEmpty() }.keys
        return Capabilities(
            videoCodecs = videoCodecs,
            audioCodecs = audio,
            // The platform mixes surround down where the output has fewer channels.
            maxAudioChannels = 8,
            containers = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "mpegts", "m4a", "mp3", "flac", "ogg", "wav", "aac"),
            embeddedSubtitles = setOf("subrip", "srt", "ass", "ssa", "webvtt", "pgssub", "dvdsub", "dvbsub", "mov_text"),
            hls = true,
        )
    }

    private fun screenSupports(format: HdrFormat, types: Set<Int>): Boolean = when (format) {
        HdrFormat.HDR10 -> Display.HdrCapabilities.HDR_TYPE_HDR10 in types
        HdrFormat.HDR10_PLUS -> Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types
        HdrFormat.HLG -> Display.HdrCapabilities.HDR_TYPE_HLG in types
        HdrFormat.DOLBY_VISION -> Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types
    }
}
