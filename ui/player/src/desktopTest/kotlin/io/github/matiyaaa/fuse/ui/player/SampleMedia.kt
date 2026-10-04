package io.github.matiyaaa.fuse.ui.player

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_SUBRIP
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_SUBTITLE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.Pointer
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Makes small test clips with FFmpeg itself, so the engine's tests need no files checked in: a
 * picture whose brightness steps every frame, a 440 Hz tone, and SubRip cues, in Matroska.
 */
internal object SampleMedia {
    class Cue(val startMs: Long, val endMs: Long, val text: String)

    fun write(file: File, seconds: Int = 3, cues: List<Cue> = emptyList(), audioTracks: Int = 1) {
        val fmt = AVFormatContext(null as Pointer?)
        check(avformat_alloc_output_context2(fmt, null, "matroska", file.absolutePath) >= 0) { "no muxer" }
        val fps = 25
        val vCodec = avcodec_find_encoder_by_name("libx264") ?: avcodec_find_encoder(AV_CODEC_ID_MPEG4)
        val vst = avformat_new_stream(fmt, null)
        val vc = avcodec_alloc_context3(vCodec)
        vc.width(320).height(240).time_base(av_make_q(1, fps)).framerate(av_make_q(fps, 1)).pix_fmt(AV_PIX_FMT_YUV420P).gop_size(fps)
        if ((fmt.oformat().flags() and AVFMT_GLOBALHEADER) != 0) vc.flags(vc.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
        check(avcodec_open2(vc, vCodec, null as AVDictionary?) >= 0) { "video encoder" }
        avcodec_parameters_from_context(vst.codecpar(), vc)
        vst.time_base(vc.time_base())

        val aCodec = avcodec_find_encoder(AV_CODEC_ID_AAC)
        val audio = List(audioTracks) {
            val st = avformat_new_stream(fmt, null)
            val ac = avcodec_alloc_context3(aCodec)
            ac.sample_fmt(AV_SAMPLE_FMT_FLTP).sample_rate(48_000).bit_rate(96_000).time_base(av_make_q(1, 48_000))
            av_channel_layout_default(ac.ch_layout(), 2)
            if ((fmt.oformat().flags() and AVFMT_GLOBALHEADER) != 0) ac.flags(ac.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
            check(avcodec_open2(ac, aCodec, null as AVDictionary?) >= 0) { "audio encoder" }
            avcodec_parameters_from_context(st.codecpar(), ac)
            st.time_base(ac.time_base())
            st to ac
        }

        val sst: AVStream? = if (cues.isNotEmpty()) {
            avformat_new_stream(fmt, null).also {
                it.codecpar().codec_type(AVMEDIA_TYPE_SUBTITLE).codec_id(AV_CODEC_ID_SUBRIP)
                it.time_base(av_make_q(1, 1000))
            }
        } else {
            null
        }

        val pb = AVIOContext(null as Pointer?)
        check(avio_open(pb, file.absolutePath, AVIO_FLAG_WRITE) >= 0) { "open output" }
        fmt.pb(pb)
        check(avformat_write_header(fmt, null as AVDictionary?) >= 0) { "header" }

        val frame = av_frame_alloc()
        frame.format(AV_PIX_FMT_YUV420P).width(320).height(240)
        av_frame_get_buffer(frame, 0)
        for (i in 0 until seconds * fps) {
            av_frame_make_writable(frame)
            val y = frame.data(0)
            val luma = (16 + (i * 8) % 220).toByte()
            for (row in 0 until 240) for (x in 0 until 320) y.put((row * frame.linesize(0) + x).toLong(), luma)
            for (p in 1..2) {
                val plane = frame.data(p)
                for (row in 0 until 120) for (x in 0 until 160) plane.put((row * frame.linesize(p) + x).toLong(), 128.toByte())
            }
            frame.pts(i.toLong())
            encode(fmt, vc, vst, frame)
        }
        encode(fmt, vc, vst, null)
        av_frame_free(frame)

        for ((st, ac) in audio) {
            val af = av_frame_alloc()
            val size = ac.frame_size().takeIf { it > 0 } ?: 1024
            af.format(AV_SAMPLE_FMT_FLTP).sample_rate(48_000).nb_samples(size)
            av_channel_layout_default(af.ch_layout(), 2)
            av_frame_get_buffer(af, 0)
            var t = 0L
            while (t < seconds * 48_000L) {
                av_frame_make_writable(af)
                for (c in 0..1) {
                    val data = FloatPointer(af.data(c))
                    for (s in 0 until size) data.put(s.toLong(), (0.2 * sin(2 * PI * 440 * (t + s) / 48_000.0)).toFloat())
                }
                af.pts(t)
                encode(fmt, ac, st, af)
                t += size
            }
            encode(fmt, ac, st, null)
            av_frame_free(af)
        }

        sst?.let { st ->
            for (c in cues) {
                val bytes = c.text.toByteArray()
                val pkt = av_packet_alloc()
                av_new_packet(pkt, bytes.size)
                pkt.data().put(bytes, 0, bytes.size)
                pkt.pts(c.startMs).dts(c.startMs).duration(c.endMs - c.startMs).stream_index(st.index())
                av_interleaved_write_frame(fmt, pkt)
                av_packet_free(pkt)
            }
        }

        av_write_trailer(fmt)
        avcodec_free_context(vc)
        audio.forEach { avcodec_free_context(it.second) }
        avio_closep(fmt.pb())
        avformat_free_context(fmt)
    }

    private fun encode(fmt: AVFormatContext, ctx: AVCodecContext, st: AVStream, frame: AVFrame?) {
        avcodec_send_frame(ctx, frame)
        val pkt = av_packet_alloc()
        while (avcodec_receive_packet(ctx, pkt) >= 0) {
            av_packet_rescale_ts(pkt, ctx.time_base(), st.time_base())
            pkt.stream_index(st.index())
            av_interleaved_write_frame(fmt, pkt)
            av_packet_unref(pkt)
        }
        av_packet_free(pkt)
    }
}
