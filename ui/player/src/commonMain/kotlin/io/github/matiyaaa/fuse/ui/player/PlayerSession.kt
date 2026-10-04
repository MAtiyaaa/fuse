package io.github.matiyaaa.fuse.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.playback.AudioTrack
import io.github.matiyaaa.fuse.playback.CueTimeline
import io.github.matiyaaa.fuse.playback.MediaKind
import io.github.matiyaaa.fuse.playback.PlayItem
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.PlaybackEvent
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.playback.SubtitleParser
import io.github.matiyaaa.fuse.playback.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How the player behaves, from the user's settings (the shell maps its preferences onto this).
 * Subtitle size is a scale on the cue's own size; position lifts subtitles from the bottom as a
 * fraction of the picture; delay moves them later (positive) or earlier.
 */
data class PlayerSettings(
    val subtitleScale: Float = 1f,
    val subtitleLift: Float = 0f,
    val subtitleDelayMs: Long = 0,
    /** Subtitles with a dark box behind them instead of an outline. */
    val subtitleBackground: Boolean = false,
    val seekSeconds: Int = 10,
    val controlsTimeoutMs: Long = 4_000,
    val autoplayNext: Boolean = true,
    val rememberSpeed: Boolean = false,
    val hardwareDecoding: Boolean = true,
    /** The most the connection should carry, in bits per second; null for no limit. */
    val maxBitrate: Long? = null,
)

/**
 * The one player of the process: what is playing, from where, with which tracks, and everything
 * around it (the queue, subtitles from files, reports to the provider, getting back up after a
 * failed stream). Any window may show it: the main screen, a second screen with only the picture,
 * or a remote. Its state is Compose state, so screens follow it without wiring.
 */
class PlayerSession(
    private val scope: CoroutineScope,
    private val makeEngine: () -> PlayerEngine,
) {
    var settings by mutableStateOf(PlayerSettings())

    var item by mutableStateOf<PlayItem?>(null)
        private set
    var source by mutableStateOf<PlaySource?>(null)
        private set

    /** The music queue, or the one item; [queueIndex] is the one playing. */
    var queue by mutableStateOf<List<PlayItem>>(emptyList())
        private set
    var queueIndex by mutableStateOf(0)
        private set

    /** What comes after this item (the next episode), once known. */
    var upNext by mutableStateOf<PlayItem?>(null)
        private set

    /** A stream is being asked for: the player shows its spinner. */
    var resolving by mutableStateOf(false)
        private set

    /** Why playing stopped, in plain words; null while all is well. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The item played to its end with nothing after it: the end screen shows. */
    var finished by mutableStateOf(false)
        private set

    var speed by mutableStateOf(1f)
        private set

    /** Cues from a subtitle file of its own (External), on the item's clock. */
    var fileCues by mutableStateOf(CueTimeline.EMPTY)
        private set

    private var engineOrNull: PlayerEngine? = null

    /** The engine, made on first use; null before anything played. */
    val engine: PlayerEngine? get() = engineOrNull

    private var resolver: PlaybackResolver? = null
    private var watcher: Job? = null
    private var reporter: Job? = null
    private var subtitleJob: Job? = null
    private var started = false
    private var recoveries = 0
    private var lastPosition = 0L

    val active: Boolean get() = item != null

    val isVideo: Boolean get() = item?.kind != MediaKind.AUDIO

    private fun engine(): PlayerEngine = engineOrNull ?: makeEngine().also { e ->
        engineOrNull = e
        watcher = scope.launch { e.state.collect { onEngine(it) } }
    }

    /** Plays [item] from [resolver], from [startMs] (its resume point unless told otherwise), with [queue] around it. */
    fun start(item: PlayItem, resolver: PlaybackResolver, startMs: Long = item.resumeMs, queue: List<PlayItem> = listOf(item)) {
        stopCurrent(reportStop = true)
        this.resolver = resolver
        this.queue = queue
        this.queueIndex = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        if (!settings.rememberSpeed) speed = 1f
        open(item, startMs)
    }

    private fun open(item: PlayItem, startMs: Long, audio: Int? = null, subtitle: Int? = null, failed: PlaySource? = null) {
        val r = resolver ?: return
        this.item = item
        finished = false
        error = null
        upNext = null
        started = false
        resolving = true
        lastPosition = startMs
        scope.launch {
            try {
                val e = engine()
                val request = PlayRequest(
                    startMs = startMs,
                    audioStreamIndex = audio,
                    subtitleStreamIndex = subtitle,
                    maxBitrate = settings.maxBitrate,
                    capabilities = e.capabilities(settings.hardwareDecoding),
                    failed = failed,
                )
                val s = r.resolve(item, request)
                if (this@PlayerSession.item?.id != item.id) return@launch
                source = s
                val audioOrder = s.audioTracks.firstOrNull { it.id == s.audio }?.order
                val sub = s.subtitleTracks.firstOrNull { it.id == s.subtitle }
                e.load(s, (s.startMs - s.offsetMs).coerceAtLeast(0), audioOrder, (sub?.delivery as? SubtitleDelivery.Embedded)?.order)
                e.setSpeed(speed)
                loadFileSubtitles(sub)
                upNext = runCatching { if (queue.size > 1) queue.getOrNull(queueIndex + 1) else r.next(item) }.getOrNull()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "This couldn't be played."
            } finally {
                resolving = false
            }
        }
    }

    private fun onEngine(s: EngineState) {
        val i = item ?: return
        val src = source ?: return
        // Remembered for getting back up after a failed stream, which can't ask the engine any more.
        if (s.status == EngineStatus.READY || s.status == EngineStatus.BUFFERING) lastPosition = src.offsetMs + s.positionMs
        when (s.status) {
            EngineStatus.READY -> {
                recoveries = 0
                if (!started) {
                    started = true
                    report(PlaybackEvent.Started(i, src, positionMs()))
                    startReporting()
                }
            }
            EngineStatus.ENDED -> ended()
            EngineStatus.ERROR -> recover(s.error)
            else -> Unit
        }
    }

    /** A stream that failed: ask the provider for another way (another route, or converting it), twice at most. */
    private fun recover(reason: String?) {
        val i = item ?: return
        val failed = source
        if (recoveries >= MAX_RECOVERIES || failed == null) {
            error = reason ?: "Playback stopped."
            return
        }
        recoveries++
        val at = lastPosition
        open(i, at, failed.audioTracks.firstOrNull { it.id == failed.audio }?.streamIndex, failed.subtitleTracks.firstOrNull { it.id == failed.subtitle }?.streamIndex, failed)
    }

    private fun ended() {
        val i = item ?: return
        val src = source ?: return
        report(PlaybackEvent.Stopped(i, src, durationMs() ?: positionMs(), finished = true))
        reporter?.cancel()
        val next = upNext
        if (next != null && (settings.autoplayNext || i.kind == MediaKind.AUDIO)) {
            if (queue.size > 1) queueIndex = (queueIndex + 1).coerceAtMost(queue.lastIndex)
            open(next, next.resumeMs.takeIf { i.kind == MediaKind.VIDEO } ?: 0)
        } else {
            finished = true
        }
    }

    private fun startReporting() {
        reporter?.cancel()
        reporter = scope.launch {
            while (true) {
                delay(REPORT_EVERY_MS)
                val i = item ?: break
                val src = source ?: break
                val p = positionMs()
                lastPosition = p
                report(PlaybackEvent.Progress(i, src, p, paused = engineOrNull?.state?.value?.playing != true))
            }
        }
    }

    private fun report(event: PlaybackEvent) {
        val r = resolver ?: return
        scope.launch { runCatching { r.report(event) } }
    }

    // Controls -----------------------------------------------------------------------------------

    fun play() {
        if (finished) {
            seekTo(0)
            finished = false
        }
        engineOrNull?.play()
        progressNow()
    }

    fun pause() {
        engineOrNull?.pause()
        progressNow()
    }

    fun toggle() {
        if (engineOrNull?.state?.value?.playing == true) pause() else play()
    }

    /** Seeks on the item's clock; a transcode that doesn't reach there is asked for again from that point. */
    fun seekTo(ms: Long) {
        val src = source ?: return
        val target = ms.coerceIn(0, durationMs() ?: Long.MAX_VALUE)
        lastPosition = target
        val local = target - src.offsetMs
        if (local < 0 && src.method == PlayMethod.TRANSCODE) {
            reopen(target)
        } else {
            engineOrNull?.seekTo(local.coerceAtLeast(0))
        }
        progressNow()
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs() + deltaMs)

    /** Where the item is, on its own clock. */
    fun positionMs(): Long {
        val src = source ?: return lastPosition
        val e = engineOrNull ?: return lastPosition
        return src.offsetMs + e.positionMs()
    }

    fun durationMs(): Long? = item?.durationMs ?: source?.durationMs ?: engineOrNull?.state?.value?.durationMs?.let { it + (source?.offsetMs ?: 0) }

    fun changeSpeed(value: Float) {
        speed = value
        engineOrNull?.setSpeed(value)
    }

    fun chooseAudio(track: AudioTrack) {
        val src = source ?: return
        if (src.audio == track.id) return
        if (src.method == PlayMethod.TRANSCODE) {
            // The server mixes the sound: it is asked for this track from here.
            reopen(positionMs(), audio = track.streamIndex)
        } else {
            source = src.copy(audio = track.id)
            engineOrNull?.selectAudio(track.order)
        }
    }

    /** A subtitle track, or null for none. */
    fun chooseSubtitle(track: SubtitleTrack?) {
        val src = source ?: return
        val wasBurnt = src.subtitleTracks.firstOrNull { it.id == src.subtitle }?.delivery == SubtitleDelivery.BurnIn
        when (val d = track?.delivery) {
            SubtitleDelivery.BurnIn -> reopen(positionMs(), subtitle = track.streamIndex)
            else -> {
                if (wasBurnt) {
                    // Off the picture again: a clean stream from here.
                    reopen(positionMs(), subtitle = track?.streamIndex ?: -1)
                    return
                }
                source = src.copy(subtitle = track?.id)
                engineOrNull?.selectSubtitle((d as? SubtitleDelivery.Embedded)?.order)
                loadFileSubtitles(track)
            }
        }
    }

    private fun reopen(at: Long, audio: Int? = null, subtitle: Int? = null) {
        val i = item ?: return
        val src = source
        val a = audio ?: src?.audioTracks?.firstOrNull { it.id == src.audio }?.streamIndex
        val s = subtitle ?: src?.subtitleTracks?.firstOrNull { it.id == src.subtitle }?.streamIndex ?: -1
        val wasPlaying = engineOrNull?.state?.value?.playing != false
        open(i, at, a, s)
        if (!wasPlaying) scope.launch { delay(50); engineOrNull?.pause() }
    }

    private fun loadFileSubtitles(track: SubtitleTrack?) {
        subtitleJob?.cancel()
        fileCues = CueTimeline.EMPTY
        val d = track?.delivery as? SubtitleDelivery.External ?: return
        val r = resolver ?: return
        subtitleJob = scope.launch {
            val text = runCatching { r.subtitleText(d.url) }.getOrNull() ?: return@launch
            fileCues = runCatching { SubtitleParser.parse(text, d.format) }.getOrElse { CueTimeline.EMPTY }
        }
    }

    /** After a failure: the same item again from where it stopped. */
    fun retry() {
        val i = item ?: return
        recoveries = 0
        open(i, lastPosition)
    }

    fun next() {
        val i = item ?: return
        scope.launch {
            val n = if (queue.size > 1) queue.getOrNull(queueIndex + 1) else upNext ?: resolver?.next(i)
            if (n != null) {
                stopCurrent(reportStop = true, keep = true)
                if (queue.size > 1) queueIndex++
                open(n, if (n.kind == MediaKind.VIDEO) n.resumeMs else 0)
            }
        }
    }

    /** Back to the start, or (near the start already) the item before. */
    fun previous() {
        val i = item ?: return
        if (positionMs() > RESTART_WITHIN_MS) {
            seekTo(0)
            return
        }
        scope.launch {
            val p = if (queue.size > 1) queue.getOrNull(queueIndex - 1) else resolver?.previous(i)
            if (p != null) {
                stopCurrent(reportStop = true, keep = true)
                if (queue.size > 1) queueIndex--
                open(p, 0)
            } else {
                seekTo(0)
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val n = queue.getOrNull(index) ?: return
        stopCurrent(reportStop = true, keep = true)
        queueIndex = index
        open(n, 0)
    }

    /** Stops playing and closes the player. */
    fun stop() {
        stopCurrent(reportStop = true)
        item = null
        source = null
        queue = emptyList()
        upNext = null
        finished = false
        error = null
    }

    private fun stopCurrent(reportStop: Boolean, keep: Boolean = false) {
        reporter?.cancel()
        subtitleJob?.cancel()
        val i = item
        val src = source
        if (reportStop && i != null && src != null && started) {
            report(PlaybackEvent.Stopped(i, src, positionMs(), finished = false))
        }
        started = false
        recoveries = 0
        fileCues = CueTimeline.EMPTY
        if (!keep) engineOrNull?.stop()
    }

    private fun progressNow() {
        val i = item ?: return
        val src = source ?: return
        if (!started) return
        report(PlaybackEvent.Progress(i, src, positionMs(), paused = engineOrNull?.state?.value?.playing != true))
    }

    companion object {
        /** Progress reports while playing. */
        const val REPORT_EVERY_MS = 10_000L

        /** Previous restarts the item unless it is this close to its start. */
        const val RESTART_WITHIN_MS = 5_000L

        const val MAX_RECOVERIES = 2
    }
}

/**
 * The process's player. The app sets [engineFactory] at start (Media3 on Android, FFmpeg on the
 * desktop); a build without one has no player, and [available] says so.
 */
object FusePlayer {
    var engineFactory: (() -> PlayerEngine)? = null

    val available: Boolean get() = engineFactory != null

    val session: PlayerSession by lazy {
        PlayerSession(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
            engineFactory?.invoke() ?: error("No player engine on this platform")
        }
    }
}
