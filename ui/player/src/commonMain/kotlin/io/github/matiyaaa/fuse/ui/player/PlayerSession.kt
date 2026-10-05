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

    /** For music: play the queue once, round and round, or the one song again. */
    var repeat by mutableStateOf(RepeatMode.OFF)

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

    /**
     * The most this connection has shown it can carry, learned from stalls this session (null until
     * a stall teaches it). Streams are asked for at no more than this, so a weak Wi-Fi settles on a
     * quality that plays smoothly instead of stopping again and again.
     */
    var qualityCap by mutableStateOf<Long?>(null)
        private set

    /** A short note for the screen (the quality was lowered); the screen clears it once shown. */
    var notice by mutableStateOf<String?>(null)

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

    // Stalls while playing (not the wait after a seek or a start): when, and the watch on the one now.
    private val clock = kotlin.time.TimeSource.Monotonic
    private var lastSeek = clock.markNow()
    private val stalls = ArrayDeque<kotlin.time.TimeSource.Monotonic.ValueTimeMark>()
    private var stallWatch: Job? = null

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
        lastSeek = clock.markNow()
        scope.launch {
            try {
                val e = engine()
                val request = PlayRequest(
                    startMs = startMs,
                    audioStreamIndex = audio,
                    subtitleStreamIndex = subtitle,
                    maxBitrate = listOfNotNull(settings.maxBitrate?.takeIf { it > 0 }, qualityCap).minOrNull(),
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
            EngineStatus.BUFFERING -> if (started && lastSeek.elapsedNow().inWholeMilliseconds > SEEK_GRACE_MS) stalled()
            EngineStatus.READY -> {
                stallWatch?.cancel()
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

    /**
     * Playing stopped to wait for data. Three stalls within a minute and a half, or one that lasts
     * past [LONG_STALL_MS], and the stream is asked for again at about half the bitrate from the
     * same moment: a slow connection gets a picture that keeps moving.
     */
    private fun stalled() {
        if (stallWatch?.isActive == true) return
        val now = clock.markNow()
        while (stalls.isNotEmpty() && stalls.first().elapsedNow().inWholeMilliseconds > STALL_WINDOW_MS) stalls.removeFirst()
        stalls.addLast(now)
        if (stalls.size >= STALLS_TO_LOWER) {
            lowerQuality()
            return
        }
        stallWatch = scope.launch {
            delay(LONG_STALL_MS)
            if (engineOrNull?.state?.value?.status == EngineStatus.BUFFERING) lowerQuality()
        }
    }

    private fun lowerQuality() {
        val src = source ?: return
        if (!isVideo) return
        val current = qualityCap ?: src.bitrate ?: settings.maxBitrate?.takeIf { it > 0 } ?: ASSUMED_BITRATE
        val next = (current / 2).coerceAtLeast(MIN_BITRATE)
        stalls.clear()
        stallWatch?.cancel()
        if (next >= current) return
        qualityCap = next
        notice = "Lowered to about ${(next / 100_000) / 10.0} Mbps to keep playing smoothly"
        reopen(positionMs())
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
        if (i.kind == MediaKind.AUDIO && repeat == RepeatMode.ONE) {
            open(i, 0)
            return
        }
        if (i.kind == MediaKind.AUDIO && repeat == RepeatMode.ALL && queue.size > 1 && queueIndex == queue.lastIndex) {
            queueIndex = 0
            open(queue.first(), 0)
            return
        }
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
        lastSeek = clock.markNow()
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
        qualityCap = null
        notice = null
        stalls.clear()
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

        /** Waiting just after a seek or a start is expected, not a stall. */
        const val SEEK_GRACE_MS = 3_000L

        /** How many stalls within [STALL_WINDOW_MS] lower the quality, or how long one may last. */
        const val STALLS_TO_LOWER = 3
        const val STALL_WINDOW_MS = 90_000L
        const val LONG_STALL_MS = 8_000L

        /** A stream of unknown bitrate is taken to be this; quality never goes below [MIN_BITRATE]. */
        const val ASSUMED_BITRATE = 20_000_000L
        const val MIN_BITRATE = 1_500_000L
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

/**
 * Which screen shows the picture on a device with two: the one with Fuse's menus ([withMenus]),
 * or the other one, while the menus' screen is the remote and stays free to browse. Chosen when
 * playback starts (Settings, Jellyfin, Films play on) and swapped from either screen while it plays.
 */
object PlayerPlacement {
    /** The picture is on the screen with the menus (as on a device with one screen). */
    var withMenus by androidx.compose.runtime.mutableStateOf(true)

    /** There is another screen the picture can go to. */
    var canSwap by androidx.compose.runtime.mutableStateOf(false)

    /** Puts the picture on the other screen. */
    fun swap() {
        if (canSwap || !withMenus) withMenus = !withMenus
    }
}

/** How music repeats: not at all, the whole queue, or the one song. */
enum class RepeatMode { OFF, ALL, ONE }
