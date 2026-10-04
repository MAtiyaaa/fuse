package io.github.matiyaaa.fuse.playback

/** Video with sound, or sound alone (music). */
enum class MediaKind { VIDEO, AUDIO }

/**
 * How a stream reaches the player: the file as it is, the file repackaged (another container, the
 * same video), or converted by the server. Shown in the player's details as "Direct Play" and so on.
 */
enum class PlayMethod(val label: String) {
    DIRECT_PLAY("Direct Play"),
    DIRECT_STREAM("Direct Stream"),
    TRANSCODE("Transcode"),
}

/** A named point in an item, for the timeline's ticks and skipping by chapter. */
data class Chapter(val startMs: Long, val title: String)

/**
 * Something to play, as Fuse shows it: names, length, chapters and art. [id] is the provider's own;
 * art is whatever the image loader takes (a URL, or a provider's art key).
 */
data class PlayItem(
    val id: String,
    val title: String,
    /** A second line: "Season 1, Episode 3", an artist, a year. */
    val subtitle: String? = null,
    val kind: MediaKind = MediaKind.VIDEO,
    val durationMs: Long? = null,
    val chapters: List<Chapter> = emptyList(),
    val artwork: Any? = null,
    val backdrop: Any? = null,
    val logo: Any? = null,
    /** Where it was left last time, to offer resuming. */
    val resumeMs: Long = 0,
    /** For an episode: its series and season, so the queue can follow the show. */
    val seriesId: String? = null,
    val seasonId: String? = null,
    /** An episode's place, for "S1:E3" labels. */
    val season: Int? = null,
    val episode: Int? = null,
    /** For music: the album and artist lines under the title. */
    val album: String? = null,
    val artist: String? = null,
)

/** One audio track of a source. [streamIndex] is the provider's; [order] its place among the source's audio tracks. */
data class AudioTrack(
    val id: String,
    val streamIndex: Int,
    val order: Int,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
)

/** How a subtitle reaches the screen. */
sealed interface SubtitleDelivery {
    /** A file of its own: Fuse downloads and parses it, the same on every platform. */
    data class External(val url: String, val format: SubtitleFormat) : SubtitleDelivery

    /** Inside the stream: the player decodes it ([order] among the source's subtitle tracks). */
    data class Embedded(val order: Int) : SubtitleDelivery

    /** Drawn into the picture by the server: choosing it asks for a new stream. */
    data object BurnIn : SubtitleDelivery
}

/** Subtitle formats Fuse reads itself. */
enum class SubtitleFormat { SRT, VTT, ASS }

data class SubtitleTrack(
    val id: String,
    val streamIndex: Int,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
    val delivery: SubtitleDelivery,
)

/**
 * A playable stream for an item: where it is (and the headers it needs), how it got there, and its
 * tracks with those chosen. A provider makes these from an item ([PlaybackResolver]); the player
 * never sees a server's own types. [sessionId] is the provider's play session, for reporting.
 */
data class PlaySource(
    val url: String,
    val method: PlayMethod,
    val headers: Map<String, String> = emptyMap(),
    val container: String? = null,
    val isHls: Boolean = false,
    /** Where playback starts; a transcode that starts there itself has this as zero. */
    val startMs: Long = 0,
    /** Where the stream's zero is in the item, for a transcode started part way in. */
    val offsetMs: Long = 0,
    val durationMs: Long? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val audio: String? = null,
    val subtitle: String? = null,
    val sessionId: String? = null,
    /** A short line about the picture and sound, like "1080p HEVC, 5.1 EAC3", for the details sheet. */
    val description: String? = null,
    val bitrate: Long? = null,
    /** Why the server converted it, in plain words ("The player can't decode HEVC 10-bit"). */
    val transcodeReason: String? = null,
)

/** What to ask a provider for: where to start, which tracks, and how much the connection may carry. */
data class PlayRequest(
    val startMs: Long = 0,
    val audioStreamIndex: Int? = null,
    /** -1 turns subtitles off; null leaves the choice to the provider's defaults. */
    val subtitleStreamIndex: Int? = null,
    val maxBitrate: Long? = null,
    val capabilities: Capabilities,
    /** A stream that failed: the provider tries another way (another route, or converting it). */
    val failed: PlaySource? = null,
)

/** What happened while playing, for a provider that keeps track (resume points, watched marks). */
sealed interface PlaybackEvent {
    val item: PlayItem
    val source: PlaySource
    val positionMs: Long

    data class Started(override val item: PlayItem, override val source: PlaySource, override val positionMs: Long) : PlaybackEvent
    data class Progress(override val item: PlayItem, override val source: PlaySource, override val positionMs: Long, val paused: Boolean) : PlaybackEvent
    data class Stopped(override val item: PlayItem, override val source: PlaySource, override val positionMs: Long, val finished: Boolean) : PlaybackEvent
}

/**
 * A provider's side of playback: making a stream for an item, hearing what happened, and what
 * comes before and after it. Jellyfin is one; nothing in the player depends on which.
 */
interface PlaybackResolver {
    suspend fun resolve(item: PlayItem, request: PlayRequest): PlaySource

    suspend fun report(event: PlaybackEvent) {}

    /** The item after [item] (the next episode), or null. */
    suspend fun next(item: PlayItem): PlayItem? = null

    /** The item before [item], or null. */
    suspend fun previous(item: PlayItem): PlayItem? = null

    /** Downloads an external subtitle file's text. */
    suspend fun subtitleText(url: String): String? = null
}
