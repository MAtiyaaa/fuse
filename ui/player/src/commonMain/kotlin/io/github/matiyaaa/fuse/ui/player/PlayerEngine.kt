package io.github.matiyaaa.fuse.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.PlaySource
import kotlinx.coroutines.flow.StateFlow

/** Where an engine is with what it was given. */
enum class EngineStatus { IDLE, LOADING, BUFFERING, READY, ENDED, ERROR }

/**
 * An engine's state, published as it changes (and the position a few times a second; [PlayerEngine.positionMs]
 * reads it exactly, for a timeline drawn every frame). Positions are on the stream's own clock.
 */
data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedMs: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Width over height of a pixel, for anamorphic video. */
    val pixelRatio: Float = 1f,
    val error: String? = null,
    /** True when the picture is decoded in hardware (for the details sheet). */
    val hardware: Boolean = false,
    /** The decoder's name for the details sheet, like "hevc (VAAPI)" or "c2.android.hevc.decoder". */
    val decoder: String? = null,
)

/**
 * A platform's player: it opens a [PlaySource], decodes and shows the picture, plays the sound,
 * and decodes subtitles inside the stream. Everything you see around the picture (controls,
 * subtitles from files, sheets) is Fuse's own and shared ([PlayerScreen], [SubtitleLayer]).
 *
 * Tracks are chosen by their order among the source's tracks of that kind ([PlaySource.audioTracks]
 * `order`, embedded subtitles' `Embedded.order`), so a provider's numbering never leaks in here.
 * Methods are called on the main thread and return at once; the work happens in the engine.
 */
interface PlayerEngine {
    val state: StateFlow<EngineState>

    /** Subtitles the engine decodes from the stream (embedded ones); empty when none is chosen. */
    val cues: StateFlow<List<Cue>>

    /** Opens [source] at [startMs] (on the stream's clock) with the given tracks; plays at once when [play]. */
    fun load(source: PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean = true)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    fun setSpeed(speed: Float)

    /** 0 to 1. */
    fun setVolume(volume: Float)

    fun selectAudio(order: Int)

    /** An embedded subtitle by its order, or null for none. */
    fun selectSubtitle(order: Int?)

    /** Where playback is now, exactly, in milliseconds. */
    fun positionMs(): Long

    /** What this engine plays by itself on this device. */
    fun capabilities(hardwareDecoding: Boolean): Capabilities

    /** Stops and lets go of the stream; the engine can load again. */
    fun stop()

    /** Lets go of everything for good. */
    fun release()

    /** The picture, fitted to [modifier]'s bounds with its own aspect ratio. */
    @Composable
    fun Video(modifier: Modifier)
}
