package io.github.matiyaaa.fuse.ui.player.ffmpeg

import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.PlaySource
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVSubtitle
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_decode_subtitle2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_hw_config
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avcodec.avsubtitle_free
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_seek_file
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_SUBTITLE
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_buffer_ref
import org.bytedeco.ffmpeg.global.avutil.av_buffer_unref
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_find_type_by_name
import org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One decoded picture, BGRA, [width] by [height], shown at [ptsMs]. Pooled: never kept by the screen. */
internal class VideoFrame(var width: Int = 0, var height: Int = 0, var bytes: ByteArray = ByteArray(0), var ptsMs: Long = 0, var serial: Int = 0)

/** What one load of the engine reports back. */
internal interface PlaybackListener {
    fun opened(durationMs: Long?, width: Int, height: Int, pixelRatio: Float, hardware: Boolean, decoder: String?)
    fun failed(message: String)
    fun firstFrame()
}

/**
 * One stream being played: a thread reading packets, one decoding the picture, one the sound;
 * subtitles are decoded as they are read. Seeks bump a serial, so everything queued before one is
 * dropped wherever it is. Times are milliseconds from the stream's own start.
 */
internal class FfmpegPlayback(
    private val source: PlaySource,
    startMs: Long,
    audioOrder: Int?,
    subtitleOrder: Int?,
    private val hardware: Boolean,
    private val audio: AudioOut,
    private val listener: PlaybackListener,
) {
    @Volatile var closing = false
        private set

    private val serial = AtomicInteger(0)

    /** Where the last seek is going; frames and sound before it are dropped. */
    @Volatile private var seekTarget = startMs
    @Volatile private var seekRequest: Long? = if (startMs > 0) startMs else null

    @Volatile var paused = false
    @Volatile var speed = 1f
    @Volatile var volume = 1f

    @Volatile var eof = false
        private set
    @Volatile var videoDone = false
        private set
    @Volatile var audioDone = false
        private set

    @Volatile var hasVideo = false
        private set
    @Volatile var hasAudio = false
        private set

    @Volatile private var wantedAudio = audioOrder
    @Volatile private var wantedSubtitle = subtitleOrder

    private val videoPackets = LinkedBlockingDeque<Queued>()
    private val audioPackets = LinkedBlockingDeque<Queued>()
    private val free = ArrayBlockingQueue<VideoFrame>(FRAME_POOL)
    val ready = ArrayBlockingQueue<VideoFrame>(FRAME_POOL)

    /** Subtitle cues decoded from the stream, kept a little while for the clock to reach them. */
    private val subtitleCues = ArrayList<Cue>()
    private val cueLock = Object()

    private var startOffsetMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private val threads = ArrayList<Thread>()

    private class Queued(val packet: AVPacket?, val serial: Int, val flush: Boolean = false, val end: Boolean = false)

    init {
        repeat(FRAME_POOL) { free.add(VideoFrame()) }
        threads += thread("fuse-player-read") { readLoop() }
    }

    val currentSerial: Int get() = serial.get()

    fun seek(ms: Long) {
        seekRequest = ms.coerceAtLeast(0)
    }

    fun selectAudio(order: Int) {
        wantedAudio = order
    }

    fun selectSubtitle(order: Int?) {
        wantedSubtitle = order
        synchronized(cueLock) { subtitleCues.clear() }
    }

    /** The cues showing at [ms]. */
    fun cuesAt(ms: Long): List<Cue> = synchronized(cueLock) {
        subtitleCues.removeAll { it.endMs < ms - CUE_KEEP_MS }
        subtitleCues.filter { it.startMs <= ms && it.endMs > ms }
    }

    /** Gives a shown frame back to the pool. */
    fun recycle(frame: VideoFrame) {
        free.offer(frame)
    }

    fun close() {
        closing = true
        videoPackets.offer(Queued(null, -1, end = true))
        audioPackets.offer(Queued(null, -1, end = true))
        // Unblocks a decoder waiting for a free frame.
        ready.clear()
        repeat(FRAME_POOL) { free.offer(VideoFrame()) }
        threads.forEach { it.interrupt() }
    }

    fun join(ms: Long) = threads.forEach { it.join(ms) }

    // Reading -------------------------------------------------------------------------------------

    private fun readLoop() {
        val fmt = avformat_alloc_context()
        // Blocking network reads give up as soon as the player closes.
        val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
            override fun call(p: Pointer?): Int = if (closing) 1 else 0
        }
        fmt.interrupt_callback().callback(interrupt)
        val options = AVDictionary(null as Pointer?)
        if (source.headers.isNotEmpty()) av_dict_set(options, "headers", source.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }, 0)
        av_dict_set(options, "user_agent", "Fuse", 0)
        av_dict_set(options, "reconnect", "1", 0)
        av_dict_set(options, "reconnect_streamed", "1", 0)
        av_dict_set(options, "reconnect_delay_max", "4", 0)
        av_dict_set(options, "rw_timeout", "20000000", 0)
        // Opens quicker: a stream's layout is read from its first two megabytes and three seconds
        // (FFmpeg reads five of each), and one connection is kept for every request it makes.
        av_dict_set(options, "probesize", PROBE_BYTES, 0)
        av_dict_set(options, "analyzeduration", PROBE_MICROS, 0)
        av_dict_set(options, "multiple_requests", "1", 0)
        // The system's proxy, as browsers use it (FFmpeg honours no_proxy itself).
        systemProxy()?.let { av_dict_set(options, "http_proxy", it, 0) }
        val opened = avformat_open_input(fmt, source.url, null, options)
        av_dict_free(options)
        if (opened < 0) {
            if (!closing) listener.failed("Couldn't open the stream (${errorText(opened)})")
            return
        }
        var video: AVCodecContext? = null
        var hwDevice: AVBufferRef? = null
        try {
            if (avformat_find_stream_info(fmt, null as PointerPointer<*>?) < 0) {
                listener.failed("The stream couldn't be read")
                return
            }
            startOffsetMs = fmt.start_time().takeIf { it != AV_NOPTS_VALUE }?.div(1000) ?: 0
            val audioStreams = ArrayList<Int>()
            val subtitleStreams = ArrayList<Int>()
            var videoStream = -1
            for (i in 0 until fmt.nb_streams()) {
                when (fmt.streams(i).codecpar().codec_type()) {
                    AVMEDIA_TYPE_VIDEO -> if (videoStream < 0 && (fmt.streams(i).disposition() and ATTACHED_PIC) == 0) videoStream = i
                    AVMEDIA_TYPE_AUDIO -> audioStreams += i
                    AVMEDIA_TYPE_SUBTITLE -> subtitleStreams += i
                }
            }
            var hw = false
            var decoderName: String? = null
            var width = 0
            var height = 0
            var ratio = 1f
            if (videoStream >= 0) {
                val opened = openVideo(fmt, videoStream)
                video = opened?.first
                hwDevice = opened?.second
                hw = hwDevice != null
                video?.let {
                    width = it.width()
                    height = it.height()
                    val sar = it.sample_aspect_ratio()
                    if (sar.num() > 0 && sar.den() > 0) ratio = sar.num().toFloat() / sar.den()
                    decoderName = avcodec_get_name(it.codec_id()).string + if (hw) " (${hwName})" else ""
                }
            }
            hasVideo = video != null
            videoWidth = width
            videoHeight = height
            val duration = fmt.duration().takeIf { it != AV_NOPTS_VALUE && it > 0 }?.div(1000)
            listener.opened(duration, width, height, ratio, hw, decoderName)

            val vStream = videoStream
            val vCtx = video
            if (vCtx != null) threads += thread("fuse-player-video") { videoLoop(fmt, vStream, vCtx) }
            hasAudio = audioStreams.isNotEmpty()
            if (hasAudio) threads += thread("fuse-player-audio") { audioLoop(fmt, audioStreams) } else audioDone = true
            if (vCtx == null) videoDone = true

            var subtitleCtx: AVCodecContext? = null
            var subtitleStream = -1
            val packet = av_packet_alloc()
            val subtitle = AVSubtitle()
            try {
                while (!closing) {
                    seekRequest?.let { target ->
                        seekRequest = null
                        val ts = (target + startOffsetMs) * 1000
                        avformat_seek_file(fmt, -1, Long.MIN_VALUE, ts, ts, 0)
                        seekTarget = target
                        val s = serial.incrementAndGet()
                        eof = false
                        videoDone = vCtx == null
                        audioDone = !hasAudio
                        drop(videoPackets)
                        drop(audioPackets)
                        videoPackets.offer(Queued(null, s, flush = true))
                        audioPackets.offer(Queued(null, s, flush = true))
                        synchronized(cueLock) { subtitleCues.clear() }
                        subtitleCtx?.let { avcodec_flush_buffers(it) }
                    }
                    // The subtitle stream wanted (embedded subtitles change without a seek).
                    val wantSub = wantedSubtitle?.let { subtitleStreams.getOrNull(it) } ?: -1
                    if (wantSub != subtitleStream) {
                        subtitleCtx?.let { avcodec_free_context(it) }
                        subtitleCtx = if (wantSub >= 0) openDecoder(fmt, wantSub) else null
                        subtitleStream = if (subtitleCtx != null) wantSub else -1
                    }
                    if (eof || videoPackets.size > MAX_VIDEO_PACKETS || audioPackets.size > MAX_AUDIO_PACKETS) {
                        Thread.sleep(8)
                        continue
                    }
                    val r = av_read_frame(fmt, packet)
                    if (r < 0) {
                        if (r == AVERROR_EOF || closing) {
                            eof = true
                            val s = serial.get()
                            videoPackets.offer(Queued(null, s, end = true))
                            audioPackets.offer(Queued(null, s, end = true))
                            continue
                        }
                        // A network hiccup the reconnect options couldn't mend: try again shortly.
                        Thread.sleep(20)
                        continue
                    }
                    val index = packet.stream_index()
                    when {
                        index == vStream -> videoPackets.offer(Queued(clone(packet), serial.get()))
                        index in audioStreams -> audioPackets.offer(Queued(clone(packet), serial.get()))
                        index == subtitleStream && subtitleCtx != null -> decodeSubtitle(fmt, index, subtitleCtx, packet, subtitle)
                    }
                    av_packet_unref(packet)
                }
            } finally {
                av_packet_free(packet)
                subtitleCtx?.let { avcodec_free_context(it) }
            }
        } catch (e: InterruptedException) {
            // Closing.
        } catch (t: Throwable) {
            if (!closing) listener.failed(t.message ?: "Playback stopped")
        } finally {
            closing = true
            threads.filter { it !== Thread.currentThread() }.forEach { it.join(2_000) }
            video?.let { avcodec_free_context(it) }
            hwDevice?.let { av_buffer_unref(it) }
            drop(videoPackets)
            drop(audioPackets)
            avformat_close_input(fmt)
        }
    }

    private fun clone(packet: AVPacket): AVPacket {
        val copy = av_packet_alloc()
        org.bytedeco.ffmpeg.global.avcodec.av_packet_move_ref(copy, packet)
        return copy
    }

    private fun drop(queue: LinkedBlockingDeque<Queued>) {
        while (true) {
            val q = queue.poll() ?: break
            q.packet?.let { av_packet_free(it) }
        }
    }

    private fun openDecoder(fmt: AVFormatContext, stream: Int): AVCodecContext? {
        val par = fmt.streams(stream).codecpar()
        val codec: AVCodec = avcodec_find_decoder(par.codec_id()) ?: return null
        val ctx = avcodec_alloc_context3(codec) ?: return null
        if (avcodec_parameters_to_context(ctx, par) < 0) {
            avcodec_free_context(ctx)
            return null
        }
        ctx.pkt_timebase(fmt.streams(stream).time_base())
        if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            return null
        }
        return ctx
    }

    private var hwFormat = -1
    private var hwName = ""

    /** The picture's decoder: in hardware where the system has a way (VAAPI, D3D11VA, VideoToolbox), else in software. */
    private fun openVideo(fmt: AVFormatContext, stream: Int): Pair<AVCodecContext, AVBufferRef?>? {
        val par = fmt.streams(stream).codecpar()
        val codec = avcodec_find_decoder(par.codec_id()) ?: return null
        if (hardware) {
            for (name in hardwareTypes()) {
                val type = av_hwdevice_find_type_by_name(name)
                if (type < 0) continue
                var format = -1
                var i = 0
                while (true) {
                    val config = avcodec_get_hw_config(codec, i++) ?: break
                    if ((config.methods() and AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0 && config.device_type() == type) {
                        format = config.pix_fmt()
                        break
                    }
                }
                if (format < 0) continue
                val device = AVBufferRef()
                if (av_hwdevice_ctx_create(device, type, null as String?, null as AVDictionary?, 0) < 0) continue
                val ctx = avcodec_alloc_context3(codec)
                avcodec_parameters_to_context(ctx, par)
                ctx.pkt_timebase(fmt.streams(stream).time_base())
                ctx.hw_device_ctx(av_buffer_ref(device))
                if (avcodec_open2(ctx, codec, null as AVDictionary?) >= 0) {
                    hwFormat = format
                    hwName = name.uppercase()
                    return ctx to device
                }
                avcodec_free_context(ctx)
                av_buffer_unref(device)
            }
        }
        val ctx = avcodec_alloc_context3(codec) ?: return null
        avcodec_parameters_to_context(ctx, par)
        ctx.pkt_timebase(fmt.streams(stream).time_base())
        ctx.thread_count(0)
        if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            return null
        }
        return ctx to null
    }

    private fun hardwareTypes(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "win" in os -> listOf("d3d11va", "dxva2")
            "mac" in os -> listOf("videotoolbox")
            else -> listOf("vaapi", "vdpau")
        }
    }

    // The picture ---------------------------------------------------------------------------------

    private fun videoLoop(fmt: AVFormatContext, stream: Int, ctx: AVCodecContext) {
        val frame = av_frame_alloc()
        val software = av_frame_alloc()
        val timeBase = av_q2d(fmt.streams(stream).time_base())
        var sws: SwsContext? = null
        var out: BytePointer? = null
        var outSize = 0L
        // The scaler reads four planes' pointers and strides; BGRA uses the first.
        val planes = PointerPointer<BytePointer>(4L)
        val strides = IntPointer(4L)
        var first = true
        var current = serial.get()
        try {
            while (!closing) {
                val q = videoPackets.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (q.serial != serial.get() && !q.flush) {
                    q.packet?.let { av_packet_free(it) }
                    continue
                }
                if (q.flush) {
                    avcodec_flush_buffers(ctx)
                    current = q.serial
                    // Pictures decoded before the seek are of no use now.
                    while (true) free.offer(ready.poll() ?: break)
                    continue
                }
                if (q.end && q.serial >= 0) {
                    avcodec_send_packet(ctx, null as AVPacket?)
                } else if (q.packet != null) {
                    avcodec_send_packet(ctx, q.packet)
                    av_packet_free(q.packet)
                } else {
                    continue
                }
                while (!closing) {
                    val r = avcodec_receive_frame(ctx, frame)
                    if (r < 0) break
                    val decoded = if (frame.format() == hwFormat && hwFormat >= 0) {
                        if (av_hwframe_transfer_data(software, frame, 0) < 0) {
                            av_frame_unref(frame)
                            continue
                        }
                        software.best_effort_timestamp(frame.best_effort_timestamp())
                        software
                    } else {
                        frame
                    }
                    val pts = decoded.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: frame.best_effort_timestamp()
                    val ptsMs = if (pts == AV_NOPTS_VALUE) 0 else (pts * timeBase * 1000).toLong() - startOffsetMs
                    if (current != serial.get() || ptsMs < seekTarget - FRAME_SLACK_MS) {
                        av_frame_unref(frame)
                        av_frame_unref(software)
                        continue
                    }
                    // Scaled once, to at most the size a screen shows, with the pixel shape baked in.
                    val (w, h) = outputSize(decoded.width(), decoded.height(), ctx)
                    sws = sws_getCachedContext(sws, decoded.width(), decoded.height(), decoded.format(), w, h, AV_PIX_FMT_BGRA, SWS_BILINEAR, null, null, null as org.bytedeco.javacpp.DoublePointer?)
                    val need = w.toLong() * h * 4
                    if (out == null || outSize != need) {
                        out?.deallocate()
                        out = BytePointer(need)
                        outSize = need
                        planes.put(0L, out)
                        for (p in 1L..3L) planes.put(p, null as Pointer?)
                        strides.put(0L, w * 4).put(1L, 0).put(2L, 0).put(3L, 0)
                    }
                    val target = free.take()
                    sws_scale(sws, decoded.data(), decoded.linesize(), 0, decoded.height(), planes, strides)
                    if (target.bytes.size != need.toInt()) target.bytes = ByteArray(need.toInt())
                    out!!.position(0).get(target.bytes, 0, need.toInt())
                    target.width = w
                    target.height = h
                    target.ptsMs = ptsMs
                    target.serial = current
                    av_frame_unref(frame)
                    av_frame_unref(software)
                    while (!closing && !ready.offer(target, 50, TimeUnit.MILLISECONDS)) {
                        if (current != serial.get()) break
                    }
                    if (first) {
                        first = false
                        listener.firstFrame()
                    }
                }
                if (q.end) videoDone = true
            }
        } catch (_: InterruptedException) {
        } finally {
            av_frame_free(frame)
            av_frame_free(software)
            sws?.let { sws_freeContext(it) }
            out?.deallocate()
            planes.deallocate()
            strides.deallocate()
        }
    }

    private fun outputSize(width: Int, height: Int, ctx: AVCodecContext): Pair<Int, Int> {
        val sar = ctx.sample_aspect_ratio()
        var w = width.toDouble()
        val h = height.toDouble()
        if (sar.num() > 0 && sar.den() > 0) w = w * sar.num() / sar.den()
        val scale = minOf(1.0, MAX_OUT_WIDTH / w, MAX_OUT_HEIGHT / h)
        // Even sizes keep the scaler on its fast paths.
        return ((w * scale).toInt() and 1.inv()).coerceAtLeast(2) to ((h * scale).toInt() and 1.inv()).coerceAtLeast(2)
    }

    // The sound -------------------------------------------------------------------------------------

    private fun audioLoop(fmt: AVFormatContext, streams: List<Int>) {
        val frame = av_frame_alloc()
        val filter = AudioFilter()
        var ctx: AVCodecContext? = null
        var stream = -1
        var current = serial.get()
        var timeBase = 0.0
        /** Stream time of the next sample handed to the card. */
        var nextMs = -1L
        var buffer = ByteArray(0)
        try {
            while (!closing) {
                // The audio track wanted: a change opens its decoder and refills from where we are.
                val want = streams.getOrNull(wantedAudio ?: 0) ?: streams.first()
                if (want != stream) {
                    ctx?.let { avcodec_free_context(it) }
                    ctx = openDecoder(fmt, want)
                    stream = want
                    timeBase = av_q2d(fmt.streams(want).time_base())
                    filter.reset()
                    if (current == serial.get() && nextMs >= 0) seekRequest = audio.clockMs()
                }
                // Paused, the sound waits; a seek's flush still goes through, so the clock moves to it.
                if (paused && audioPackets.peekFirst()?.flush != true) {
                    Thread.sleep(10)
                    continue
                }
                val q = audioPackets.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val c = ctx ?: run { q.packet?.let { av_packet_free(it) }; continue }
                if (q.flush) {
                    avcodec_flush_buffers(c)
                    filter.reset()
                    current = q.serial
                    nextMs = -1
                    audio.reset(seekTarget)
                    continue
                }
                if (q.serial != serial.get() || (q.packet != null && q.packet.stream_index() != stream)) {
                    q.packet?.let { av_packet_free(it) }
                    continue
                }
                if (q.end) {
                    avcodec_send_packet(c, null as AVPacket?)
                } else {
                    avcodec_send_packet(c, q.packet)
                    av_packet_free(q.packet)
                }
                while (!closing) {
                    if (avcodec_receive_frame(c, frame) < 0) break
                    val pts = frame.best_effort_timestamp()
                    val ptsMs = if (pts == AV_NOPTS_VALUE) nextMs.coerceAtLeast(0) else (pts * timeBase * 1000).toLong() - startOffsetMs
                    val frameMs = frame.nb_samples() * 1000L / frame.sample_rate().coerceAtLeast(1)
                    // Sound from before the seek's target is skipped.
                    if (ptsMs + frameMs < seekTarget) {
                        av_frame_unref(frame)
                        continue
                    }
                    if (nextMs < 0) nextMs = ptsMs
                    val s = speed
                    if (filter.prepare(frame, s)) {
                        filter.push(frame) { data, length ->
                            if (buffer.size < length) buffer = ByteArray(length)
                            data.position(0).get(buffer, 0, length)
                            applyVolume(buffer, length, volume)
                            val at = nextMs
                            nextMs += (length / AudioOut.FRAME_BYTES * 1000L / AudioOut.RATE * s).toLong()
                            if (current == serial.get() && !closing) audio.write(buffer, length, at)
                        }
                    }
                    av_frame_unref(frame)
                }
                if (q.end) audioDone = true
            }
        } catch (_: InterruptedException) {
        } finally {
            av_frame_free(frame)
            filter.release()
            ctx?.let { avcodec_free_context(it) }
        }
    }

    private fun applyVolume(bytes: ByteArray, length: Int, v: Float) {
        if (v >= 0.999f) return
        var i = 0
        while (i + 1 < length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            val scaled = (sample * v).toInt()
            bytes[i] = scaled.toByte()
            bytes[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }

    // Subtitles -----------------------------------------------------------------------------------------

    private fun decodeSubtitle(fmt: AVFormatContext, stream: Int, ctx: AVCodecContext, packet: AVPacket, sub: AVSubtitle) {
        val got = IntArray(1)
        if (avcodec_decode_subtitle2(ctx, sub, got, packet) < 0 || got[0] == 0) return
        try {
            val cues = SubtitleConverter.convert(sub, ctx, packet, av_q2d(fmt.streams(stream).time_base()), startOffsetMs, videoWidth, videoHeight)
            synchronized(cueLock) {
                // A picture subtitle with nothing in it ends the one showing (PGS works that way).
                val start = cues.startMs
                if (cues.close) {
                    for (i in subtitleCues.indices) {
                        val c = subtitleCues[i]
                        if (c.endMs == Long.MAX_VALUE && c.startMs <= start) subtitleCues[i] = SubtitleConverter.ended(c, start)
                    }
                }
                subtitleCues += cues.cues
            }
        } finally {
            avsubtitle_free(sub)
        }
    }

    private fun systemProxy(): String? =
        listOf("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")
            .firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.startsWith("http://") } }

    private fun thread(name: String, body: () -> Unit): Thread = Thread(body, name).apply {
        isDaemon = true
        start()
    }

    private fun errorText(code: Int): String {
        val buf = ByteArray(256)
        av_strerror(code, buf, buf.size.toLong())
        return String(buf).trimEnd('\u0000')
    }

    companion object {
        private const val FRAME_POOL = 4
        // About half a minute read ahead at 24 frames a second, so a patchy connection is ridden out.
        private const val MAX_VIDEO_PACKETS = 720
        private const val MAX_AUDIO_PACKETS = 1_440
        private const val PROBE_BYTES = "2000000"
        private const val PROBE_MICROS = "3000000"
        private const val FRAME_SLACK_MS = 20L
        private const val CUE_KEEP_MS = 30_000L
        private const val MAX_OUT_WIDTH = 1920.0
        private const val MAX_OUT_HEIGHT = 1080.0
        private const val ATTACHED_PIC = 0x0400
    }
}
