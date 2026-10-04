package io.github.matiyaaa.fuse.capture

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Records the screen into an MP4: the screen through a virtual display into an H.264 encoder, and,
 * when [audioRecord] is given, the sound Fuse plays (Android's playback capture) into an AAC encoder.
 * One muxer writes both, started once each encoder has said what it makes.
 *
 * The encoder is configured before the virtual display is made, so a size the encoder refuses fails
 * here, before the projection's one virtual display is used up.
 */
internal class ScreenRecorder(
    projection: MediaProjection,
    width: Int,
    height: Int,
    densityDpi: Int,
    output: FileDescriptor,
    private val audioRecord: AudioRecord?,
) {
    private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val video: MediaCodec
    private val surface: Surface
    private val display: VirtualDisplay
    private val audio: MediaCodec?

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var wroteVideo = false

    @Volatile
    private var stopping = false
    private val threads = mutableListOf<Thread>()

    init {
        val format = MediaFormat.createVideoFormat(VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, CaptureFiles.bitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Frames only come when the screen changes; a still screen still records at full rate.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / FPS)
        }
        var v: MediaCodec? = null
        var s: Surface? = null
        var a: MediaCodec? = null
        try {
            v = MediaCodec.createEncoderByType(VIDEO)
            v.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            s = v.createInputSurface()
            a = audioRecord?.let {
                val f = MediaFormat.createAudioFormat(AUDIO, SAMPLE_RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                }
                MediaCodec.createEncoderByType(AUDIO).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            }
            v.start()
            a?.start()
            display = projection.createVirtualDisplay(
                "Fuse recording", width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, null,
            ) ?: error("No virtual display")
        } catch (e: Exception) {
            a?.let { runCatching { it.stop() }; it.release() }
            v?.let { runCatching { it.stop() }; it.release() }
            s?.release()
            runCatching { muxer.release() }
            throw e
        }
        video = v
        surface = s
        audio = a
    }

    fun start() {
        threads += thread(name = "Fuse recording video") { drain(video, isVideo = true) }
        val record = audioRecord
        val codec = audio
        if (record != null && codec != null) {
            record.startRecording()
            threads += thread(name = "Fuse recording sound in") { feed(record, codec) }
            threads += thread(name = "Fuse recording sound") { drain(codec, isVideo = false) }
        }
    }

    /** Stops, finishes the file and frees everything. False when nothing could be saved. */
    fun stop(): Boolean {
        stopping = true
        runCatching { video.signalEndOfInputStream() }
        synchronized(lock) { lock.notifyAll() }
        threads.forEach { it.join(JOIN_MS) }
        audioRecord?.let { runCatching { it.stop() }; it.release() }
        runCatching { display.release() }
        runCatching { video.stop() }
        video.release()
        surface.release()
        audio?.let { runCatching { it.stop() }; it.release() }
        val saved = synchronized(lock) { muxing && wroteVideo }
        return try {
            if (muxing) muxer.stop()
            saved
        } catch (e: IllegalStateException) {
            false
        } finally {
            runCatching { muxer.release() }
        }
    }

    /** Reads the captured sound and hands it to the encoder with timestamps on the same clock as the frames. */
    private fun feed(record: AudioRecord, codec: MediaCodec) {
        val bytes = ByteArray(8 * 1024)
        val startUs = System.nanoTime() / 1_000
        var frames = 0L
        try {
            while (true) {
                val index = codec.dequeueInputBuffer(WAIT_US)
                if (index < 0) continue
                val pts = startUs + frames * 1_000_000L / SAMPLE_RATE
                if (stopping) {
                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
                val input: ByteBuffer = codec.getInputBuffer(index) ?: continue
                // Android hands over no sound at all while nothing is playing (music at volume 0, a
                // pause), so a count of what was read falls behind the clock and the video would play
                // slowed against it. The gap is filled with silence, keeping sound and picture together.
                val behind = (System.nanoTime() / 1_000 - startUs) * SAMPLE_RATE / 1_000_000L - frames
                if (behind > SAMPLE_RATE / 2) {
                    val silent = minOf(behind - SAMPLE_RATE / 4, (minOf(bytes.size, input.capacity()) / BYTES_PER_FRAME).toLong()).toInt()
                    input.clear()
                    input.put(ByteArray(silent * BYTES_PER_FRAME))
                    codec.queueInputBuffer(index, 0, silent * BYTES_PER_FRAME, pts, 0)
                    frames += silent
                    continue
                }
                // Never blocks, so a silent stretch is noticed above instead of stalling here.
                val read = record.read(bytes, 0, minOf(bytes.size, input.capacity()), AudioRecord.READ_NON_BLOCKING)
                if (read <= 0) {
                    codec.queueInputBuffer(index, 0, 0, pts, 0)
                    Thread.sleep(IDLE_MS)
                    continue
                }
                input.clear()
                input.put(bytes, 0, read)
                codec.queueInputBuffer(index, 0, read, pts, 0)
                frames += read / BYTES_PER_FRAME
            }
        } catch (e: IllegalStateException) {
            // The encoder was stopped under it; the recording ends here.
        } catch (e: InterruptedException) {
            // Fuse is stopping the recording.
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, WAIT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(codec.outputFormat, isVideo)
                    index >= 0 -> {
                        val data = codec.getOutputBuffer(index)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && data != null) write(isVideo, data, info)
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // The encoder was stopped under it; the recording ends here.
        }
    }

    private fun addTrack(format: MediaFormat, isVideo: Boolean) = synchronized(lock) {
        if (isVideo) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
        if (videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
            muxer.start()
            muxing = true
            lock.notifyAll()
        }
    }

    /** Writes a sample once the muxer runs; until then (the other track not ready yet) it waits. */
    private fun write(isVideo: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        while (!muxing && !stopping) lock.wait(50)
        if (!muxing) return@synchronized
        muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, data, info)
        if (isVideo) wroteVideo = true
    }

    companion object {
        private const val VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val FPS = 30
        const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_FRAME = 4
        private const val WAIT_US = 10_000L
        private const val JOIN_MS = 3_000L

        /** How long the sound reader rests when nothing new was captured. */
        private const val IDLE_MS = 10L

        /**
         * Fuse's own sound as Android lets it be captured: media and game audio (the menu music),
         * never button sounds. Null when the permission or the system says no.
         */
        @SuppressLint("MissingPermission")
        @RequiresApi(Build.VERSION_CODES.Q)
        fun playbackCapture(projection: MediaProjection): AudioRecord? = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min * 2, 32 * 1024))
                .setAudioPlaybackCaptureConfig(config)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
    }
}
