package io.github.matiyaaa.fuse.ui.player.ffmpeg

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.ui.player.EngineState
import io.github.matiyaaa.fuse.ui.player.EngineStatus
import io.github.matiyaaa.fuse.ui.player.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * The desktop's player engine, on FFmpeg (bytedeco's build, through JavaCPP): files, HTTP and HLS
 * streams, hardware decoding where the system has it, sound through `javax.sound` as the clock,
 * and subtitles inside the stream decoded to cues. Pictures are converted to BGRA once, at most
 * 1080p, and drawn by Compose. It never claims HDR: HDR video is tone mapped by the server.
 */
class FfmpegEngine : PlayerEngine {
    private val audio = AudioOut()
    private val stateFlow = MutableStateFlow(EngineState())
    private val cueFlow = MutableStateFlow<List<Cue>>(emptyList())
    override val state: StateFlow<EngineState> = stateFlow
    override val cues: StateFlow<List<Cue>> = cueFlow

    @Volatile private var playback: FfmpegPlayback? = null
    @Volatile private var playing = false
    private var speed = 1f
    private var volume = 1f
    @Volatile private var released = false

    /** Counts loads, so a stream's late news is told from the current one's. */
    @Volatile private var loads = 0

    /** The stream's position count once the last seek is done; it can't have ended before. */
    @Volatile private var seekSerial = 0

    init {
        Thread({
            while (!released) {
                runCatching { tick() }
                Thread.sleep(TICK_MS)
            }
        }, "fuse-player-clock").apply {
            isDaemon = true
            start()
        }
    }

    override fun load(source: PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean) {
        closePlayback()
        audio.open()
        audio.pause()
        audio.reset(startMs)
        audio.speed = speed
        playing = play
        stateFlow.value = EngineState(status = EngineStatus.LOADING, positionMs = startMs)
        cueFlow.value = emptyList()
        // A stream still winding down after a quick switch must never speak for the new one.
        val load = ++loads
        seekSerial = 0
        val listener = object : PlaybackListener {
            override fun opened(durationMs: Long?, width: Int, height: Int, pixelRatio: Float, hardware: Boolean, decoder: String?) {
                if (load != loads) return
                stateFlow.update {
                    it.copy(status = EngineStatus.BUFFERING, durationMs = durationMs, videoWidth = width, videoHeight = height, pixelRatio = pixelRatio, hardware = hardware, decoder = decoder)
                }
                playback?.let { p -> audio.silent = !p.hasAudio }
                if (playing) audio.start()
            }

            override fun failed(message: String) {
                if (load != loads) return
                stateFlow.update { it.copy(status = EngineStatus.ERROR, error = message, playing = false) }
            }

            override fun firstFrame() {
                if (load != loads) return
                stateFlow.update { if (it.status == EngineStatus.LOADING || it.status == EngineStatus.BUFFERING) it.copy(status = EngineStatus.READY) else it }
            }
        }
        val p = FfmpegPlayback(source, startMs, audioOrder, subtitleOrder, hardware = hardwareDecoding, audio = audio, listener = listener)
        p.paused = !play
        p.speed = speed
        p.volume = volume
        playback = p
    }

    /** Set by [capabilities]: what the next load decodes with. */
    @Volatile private var hardwareDecoding = true

    override fun play() {
        val p = playback ?: return
        if (stateFlow.value.status == EngineStatus.ENDED) return
        playing = true
        p.paused = false
        audio.start()
        stateFlow.update { it.copy(playing = true) }
    }

    override fun pause() {
        playing = false
        playback?.paused = true
        audio.pause()
        stateFlow.update { it.copy(playing = false) }
    }

    override fun seekTo(ms: Long) {
        val p = playback ?: return
        val target = ms.coerceAtLeast(0)
        // The stream moves on its own thread; until it has, its end is the old position's.
        seekSerial = p.currentSerial + 1
        p.seek(target)
        audio.reset(target)
        stateFlow.update { it.copy(positionMs = target, status = if (it.status == EngineStatus.ENDED) EngineStatus.BUFFERING else it.status) }
        if (playing) audio.start()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        audio.speed = speed
        playback?.speed = speed
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        playback?.volume = this.volume
    }

    override fun selectAudio(order: Int) {
        playback?.selectAudio(order)
    }

    override fun selectSubtitle(order: Int?) {
        playback?.selectSubtitle(order)
        cueFlow.value = emptyList()
        // Picture subtitles only arrive when they start: going back a moment catches the one showing.
        if (order != null) seekTo(positionMs())
    }

    override fun positionMs(): Long {
        val d = stateFlow.value.durationMs
        val now = audio.clockMs().coerceAtLeast(0)
        return if (d != null) now.coerceAtMost(d) else now
    }

    override fun capabilities(hardwareDecoding: Boolean): Capabilities {
        this.hardwareDecoding = hardwareDecoding
        return FfmpegCapabilities.detect(hardwareDecoding)
    }

    override fun stop() {
        loads++
        closePlayback()
        playing = false
        stateFlow.value = EngineState()
        cueFlow.value = emptyList()
    }

    override fun release() {
        stop()
        released = true
        audio.close()
    }

    private fun closePlayback() {
        val p = playback ?: return
        playback = null
        p.close()
        audio.pause()
        // The old stream winds down on its own threads; nothing waits for it.
        Thread({ p.join(3_000) }, "fuse-player-close").apply {
            isDaemon = true
            start()
        }
    }

    /** A few times a second: the position, buffering, the end, and the subtitles showing. */
    private fun tick() {
        val p = playback ?: return
        val first = stateFlow.value.status
        if (first == EngineStatus.ERROR || first == EngineStatus.IDLE) return
        val now = positionMs()
        val ended = p.currentSerial >= seekSerial && p.eof && p.videoDone && p.audioDone && audio.drained() && p.ready.isEmpty()
        val starving = playing && !p.eof && (if (p.hasAudio) audio.starving() else p.ready.isEmpty())
        var endedNow = false
        // One atomic change: the stream's own threads update the state too (opened, its first
        // picture, a failure), and a plain read then write here would put back what they changed.
        stateFlow.update { s ->
            if (s.status == EngineStatus.ERROR || s.status == EngineStatus.IDLE) return@update s
            val status = when {
                ended && s.status != EngineStatus.LOADING -> EngineStatus.ENDED
                s.status == EngineStatus.LOADING -> EngineStatus.LOADING
                starving && s.status != EngineStatus.READY -> EngineStatus.BUFFERING
                starving -> s.status
                else -> if (s.status == EngineStatus.BUFFERING && (p.hasVideo && p.ready.isEmpty())) EngineStatus.BUFFERING else EngineStatus.READY
            }
            endedNow = status == EngineStatus.ENDED && s.status != EngineStatus.ENDED
            s.copy(status = status, positionMs = now, playing = playing && status != EngineStatus.ENDED, bufferedMs = now)
        }
        if (endedNow) {
            playing = false
            audio.pause()
        }
        val c = p.cuesAt(now)
        if (c != cueFlow.value) cueFlow.value = c
    }

    // Presentation --------------------------------------------------------------------------------

    private var shownSerial = -1

    /** The picture due now, if a new one is: later ones wait, late ones are skipped. */
    private fun takeFrame(): VideoFrame? {
        val p = playback ?: return null
        val clock = positionMs()
        var chosen: VideoFrame? = null
        while (true) {
            val head = p.ready.peek() ?: break
            if (head.serial != p.currentSerial) {
                p.ready.poll()
                p.recycle(head)
                continue
            }
            // The first picture after a load or a seek shows at once, so a paused seek shows where it landed.
            val due = head.ptsMs <= clock + PRESENT_EARLY_MS || (chosen == null && shownSerial != head.serial)
            if (!due) break
            p.ready.poll()
            chosen?.let { p.recycle(it) }
            chosen = head
            shownSerial = head.serial
            if (!playing) break
        }
        return chosen
    }

    /** For tests: the size of the last picture taken. */
    internal var lastFrameBytes = 0
        private set

    /** For tests: takes the picture due now as the screen would, and gives it straight back. */
    internal fun takeFrameForTest(): VideoFrame? {
        val f = takeFrame() ?: return null
        lastFrameBytes = f.bytes.size
        val copy = VideoFrame(f.width, f.height, ByteArray(0), f.ptsMs, f.serial)
        playback?.recycle(f)
        return copy
    }

    /** For tests: everything the end of a stream waits on, to say why it hasn't come. */
    internal fun endConditionsForTest(): String {
        val p = playback ?: return "no stream"
        return "eof=${p.eof} videoDone=${p.videoDone} audioDone=${p.audioDone} ready=${p.ready.size} " +
            "drained=${audio.drained()} card=${audio.hasCard} silent=${audio.silent} serial=${p.currentSerial}/$seekSerial playing=$playing"
    }

    /** For tests: the stream takes [ms] longer to act on each seek, as on a slow machine. */
    internal fun slowSeeksForTest(ms: Long) {
        playback?.seekDelayForTestMs = ms
    }

    @Composable
    override fun Video(modifier: Modifier) {
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        val held = remember { arrayOfNulls<Image>(1) }
        LaunchedEffect(this) {
            while (true) {
                withFrameNanos { }
                val frame = takeFrame() ?: continue
                val info = ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                val next = Image.makeRaster(info, frame.bytes, frame.width * 4)
                playback?.recycle(frame)
                held[0]?.close()
                held[0] = next
                image = next.toComposeImageBitmap()
            }
        }
        DisposableEffect(this) {
            onDispose {
                held[0]?.close()
                held[0] = null
            }
        }
        Canvas(modifier) {
            val img = image ?: return@Canvas
            drawImage(
                img,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }

    companion object {
        private const val TICK_MS = 100L
        private const val PRESENT_EARLY_MS = 8L
    }
}
