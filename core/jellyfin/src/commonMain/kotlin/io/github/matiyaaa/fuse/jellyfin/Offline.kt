package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.playback.AudioTrack
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The Downloads source name for films and episodes kept for watching offline. */
const val JELLYFIN_SOURCE = "jellyfin"

/** The Downloads source name for moving kept films and episodes to another drive. */
const val OFFLINE_MOVE_SOURCE = "offline-move"

/** A picture on the server, kept as plain fields so it survives in a transfer's note. */
@Serializable
data class OfflineArtRef(val itemId: String, val kind: String, val tag: String, val index: Int = 0) {
    fun toArt(): JellyfinArt? = ArtKind.entries.firstOrNull { it.name == kind }?.let { JellyfinArt(itemId, it, tag, index) }

    companion object {
        fun of(a: JellyfinArt?): OfflineArtRef? = a?.let { OfflineArtRef(it.itemId, it.kind.name, it.tag, it.index) }
    }
}

/** A sound track of a kept file, as the player lists it. */
@Serializable
data class OfflineAudio(val index: Int, val order: Int, val label: String, val language: String? = null, val codec: String? = null, val channels: Int? = null, val isDefault: Boolean = false)

/**
 * A subtitle of a kept file: inside it ([embeddedOrder] among its subtitle tracks), or a file of
 * its own beside it ([file], the name only) for subtitles the server keeps next to the video.
 */
@Serializable
data class OfflineSubtitle(
    val index: Int,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
    val embeddedOrder: Int = -1,
    val file: String = "",
    val ext: String = "",
)

/**
 * What Fuse keeps about a film or episode to show and play it with the server away: its words,
 * its tracks, the names of its pictures beside the file, and where it was left.
 */
@Serializable
data class OfflineMeta(
    val itemId: String,
    val name: String,
    val type: String,
    val overview: String? = null,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val mediaSourceId: String? = null,
    val container: String? = null,
    val sizeBytes: Long = 0,
    val videoFacts: String? = null,
    val audioFacts: String? = null,
    val audio: List<OfflineAudio> = emptyList(),
    val subtitles: List<OfflineSubtitle> = emptyList(),
    /** The pictures' file names beside the video, once fetched. */
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
    val posterArt: OfflineArtRef? = null,
    val backdropArt: OfflineArtRef? = null,
    val logoArt: OfflineArtRef? = null,
    val thumbArt: OfflineArtRef? = null,
    val resumeMs: Long = 0,
    val played: Boolean = false,
    /** A position watched offline that the server hasn't heard yet. */
    val unsentMs: Long? = null,
) {
    val isEpisode: Boolean get() = type == MediaType.EPISODE.name

    val episodeLabel: String? get() = if (season != null && episode != null) "S$season:E$episode" else episode?.let { "E$it" }
}

/** One film or episode on its way to this device: the server it comes from and where it goes. */
@Serializable
data class JellyfinDownloadJob(
    val server: String,
    val itemId: String,
    /** The video's file name inside the transfer's place (its folder). */
    val fileName: String,
    val meta: OfflineMeta,
)

/** A kept film or episode moving to another drive: its row, the folder it leaves, and its files' names. */
@Serializable
data class OfflineMoveJob(val key: String, val from: io.github.matiyaaa.fuse.transfer.TransferPlace, val files: List<String>)

internal val offlineJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

fun JellyfinDownloadJob.encode(): String = offlineJson.encodeToString(JellyfinDownloadJob.serializer(), this)
fun OfflineMoveJob.encode(): String = offlineJson.encodeToString(OfflineMoveJob.serializer(), this)
fun OfflineMeta.encode(): String = offlineJson.encodeToString(OfflineMeta.serializer(), this)
fun decodeOfflineMeta(text: String): OfflineMeta? = runCatching { offlineJson.decodeFromString(OfflineMeta.serializer(), text) }.getOrNull()
fun decodeJellyfinJob(text: String): JellyfinDownloadJob? = runCatching { offlineJson.decodeFromString(JellyfinDownloadJob.serializer(), text) }.getOrNull()
fun decodeMoveJob(text: String): OfflineMoveJob? = runCatching { offlineJson.decodeFromString(OfflineMoveJob.serializer(), text) }.getOrNull()

/**
 * Where kept films and episodes go and what they are called: tidy, readable folders that make
 * sense opened in a file manager (Films/Title (Year), Shows/Show/Season 01), names safe on every
 * file system, and never a server's own path.
 */
object OfflinePlan {
    /** The folder under the download root for [item]. */
    fun folder(item: MediaItem): String = when (item.type) {
        MediaType.EPISODE -> {
            val show = safe(item.seriesName ?: "Show")
            val season = item.season?.let { "Season ${it.toString().padStart(2, '0')}" } ?: safe(item.seasonName ?: "Specials")
            "Shows/$show/$season"
        }
        MediaType.MOVIE -> "Films/${safe(titleWithYear(item))}"
        else -> "Videos"
    }

    /** The same folder from what was kept, for moving a kept item to another drive. */
    fun folderOf(m: OfflineMeta): String = when (m.type) {
        MediaType.EPISODE.name -> {
            val season = m.season?.let { "Season ${it.toString().padStart(2, '0')}" } ?: safe(m.seasonName ?: "Specials")
            "Shows/${safe(m.seriesName ?: "Show")}/$season"
        }
        MediaType.MOVIE.name -> "Films/${safe(if (m.year != null) "${m.name} (${m.year})" else m.name)}"
        else -> "Videos"
    }

    /** The video's file name: the item's own name and the server file's extension. */
    fun fileName(item: MediaItem, container: String?, serverPath: String?): String {
        val ext = serverPath?.substringAfterLast('/')?.substringAfterLast('\\')?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
            ?: container?.substringBefore(',')?.takeIf { it.isNotBlank() } ?: "mkv"
        val base = when (item.type) {
            MediaType.EPISODE -> {
                val code = buildString {
                    item.season?.let { append("S").append(it.toString().padStart(2, '0')) }
                    item.episode?.let { append("E").append(it.toString().padStart(2, '0')) }
                }
                listOf(item.seriesName ?: "Show", code, item.name).filter { it.isNotBlank() }.joinToString(" - ")
            }
            MediaType.MOVIE -> titleWithYear(item)
            else -> item.name
        }
        return "${safe(base)}.${ext.lowercase()}"
    }

    /** The name of an external subtitle's file beside [video]. */
    fun subtitleName(video: String, s: OfflineSubtitle): String {
        val stem = video.substringBeforeLast('.')
        val tags = listOfNotNull(s.language?.lowercase()?.takeIf { it.isNotBlank() }, "forced".takeIf { s.forced }, s.index.toString())
        return "$stem.${tags.joinToString(".")}.${s.ext}"
    }

    /** A picture's file name beside [video] ("poster", "backdrop"). */
    fun artName(video: String, kind: String): String = ".${video.substringBeforeLast('.')}.$kind.jpg"

    private fun titleWithYear(item: MediaItem) = if (item.year != null) "${item.name} (${item.year})" else item.name

    /** Letters every file system takes, without the characters Windows or FAT refuse. */
    fun safe(s: String): String {
        val cleaned = s.map { c -> if (c in "<>:\"/\\|?*" || c.code < 32) ' ' else c }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120)
        return cleaned.ifBlank { "Untitled" }
    }

    /** What Fuse keeps about [item], given the server's file. */
    internal fun meta(item: MediaItem, ms: MediaSourceDto?): OfflineMeta {
        val streams = ms?.mediaStreams.orEmpty().sortedBy { it.index }
        val audio = streams.filter { it.type == "Audio" && !it.isExternal }.mapIndexed { order, s ->
            OfflineAudio(s.index, order, s.displayTitle ?: s.title ?: s.language ?: "Track ${order + 1}", s.language, s.codec, s.channels, s.isDefault)
        }
        val embedded = streams.filter { it.type == "Subtitle" && !it.isExternal }
        val subs = streams.filter { it.type == "Subtitle" }.mapNotNull { s ->
            val label = s.displayTitle ?: s.title ?: s.language ?: "Subtitles"
            if (s.isExternal) {
                // A file the server keeps beside the video: only text ones can come along.
                val format = SubtitleParser.formatOf(s.codec) ?: return@mapNotNull null
                val ext = when (format) {
                    io.github.matiyaaa.fuse.playback.SubtitleFormat.SRT -> "srt"
                    io.github.matiyaaa.fuse.playback.SubtitleFormat.VTT -> "vtt"
                    io.github.matiyaaa.fuse.playback.SubtitleFormat.ASS -> "ass"
                }
                OfflineSubtitle(s.index, label, s.language, s.codec, s.isForced, s.isDefault, ext = ext)
            } else {
                OfflineSubtitle(s.index, label, s.language, s.codec, s.isForced, s.isDefault, embeddedOrder = embedded.indexOf(s))
            }
        }
        return OfflineMeta(
            itemId = item.id, name = item.name, type = item.type.name, overview = item.overview, year = item.year,
            runtimeMs = item.runtimeMs ?: ms?.runTimeTicks?.let { it / 10_000 }, officialRating = item.officialRating, genres = item.genres,
            seriesId = item.seriesId, seriesName = item.seriesName, seasonId = item.seasonId, seasonName = item.seasonName,
            season = item.season, episode = item.episode, mediaSourceId = ms?.id, container = ms?.container, sizeBytes = ms?.size ?: 0,
            videoFacts = item.videoFacts, audioFacts = item.audioFacts, audio = audio, subtitles = subs,
            posterArt = OfflineArtRef.of(if (item.type == MediaType.EPISODE) item.seriesPoster ?: item.poster else item.poster),
            backdropArt = OfflineArtRef.of(item.backdrop), logoArt = OfflineArtRef.of(item.logo),
            thumbArt = OfflineArtRef.of(if (item.type == MediaType.EPISODE) item.thumb ?: item.poster else item.thumb),
            resumeMs = item.resumeMs, played = item.played,
        )
    }
}

/** A kept film or episode, as the offline player and the Storage page read it. */
data class OfflineEntry(
    val key: String,
    val server: String,
    val itemId: String,
    /** The video file where it is now, or null when its drive isn't connected. */
    val path: String?,
    /** The folder it was kept in when last seen, for words when its drive is away. */
    val lastPath: String,
    val driveLabel: String?,
    val meta: OfflineMeta,
    val addedAt: Long,
) {
    val here: Boolean get() = path != null
    val folder: String get() = (path ?: lastPath).substringBeforeLast('/')

    fun file(name: String): String = "$folder/$name"

    /** For Fuse Player, with the kept pictures. */
    fun toPlayItem(): PlayItem {
        val m = meta
        val episode = m.isEpisode
        fun local(name: String?) = name?.let { file(it) }
        return PlayItem(
            id = m.itemId,
            title = if (episode) m.seriesName ?: m.name else m.name,
            subtitle = if (episode) listOfNotNull(m.episodeLabel, m.name).joinToString("  ·  ") else m.year?.toString(),
            kind = MediaKind.VIDEO,
            durationMs = m.runtimeMs,
            artwork = local(if (episode) m.thumb ?: m.poster else m.poster),
            backdrop = local(m.backdrop),
            logo = local(m.logo),
            poster = local(m.poster),
            resumeMs = m.unsentMs ?: m.resumeMs,
            seriesId = m.seriesId,
            seasonId = m.seasonId,
            season = m.season,
            episode = m.episode,
        )
    }
}

/**
 * Plays kept films and episodes from this device: the file as it is, its own sound tracks, and its
 * subtitles from inside it or from the files beside it. Where it was left is kept here and sent to
 * the server once it can be reached, so the resume point follows the person back online.
 */
class OfflineResolver(
    private val find: suspend (itemId: String) -> OfflineEntry?,
    private val readText: suspend (path: String) -> String?,
    private val saveResume: suspend (entry: OfflineEntry, positionMs: Long, finished: Boolean) -> Unit,
    private val nextOf: suspend (entry: OfflineEntry, step: Int) -> OfflineEntry?,
) : PlaybackResolver {
    override suspend fun resolve(item: PlayItem, request: PlayRequest): PlaySource {
        val e = find(item.id) ?: throw JellyfinException("This download isn't on this device any more.", JellyfinException.Kind.NOT_FOUND)
        val path = e.path ?: throw JellyfinException(
            "${e.driveLabel ?: "The drive"} with this download isn't connected. Connect it, then play again.",
            JellyfinException.Kind.NOT_FOUND,
        )
        val m = e.meta
        val audio = m.audio.map { a -> AudioTrack(a.index.toString(), a.index, a.order, a.label, a.language, a.codec, a.channels, a.isDefault) }
        val subs = m.subtitles.mapNotNull { s ->
            val delivery = when {
                s.embeddedOrder >= 0 -> SubtitleDelivery.Embedded(s.embeddedOrder)
                s.file.isNotEmpty() -> SubtitleParser.formatOf(s.ext)?.let { SubtitleDelivery.External(e.file(s.file), it) } ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            SubtitleTrack(s.index.toString(), s.index, s.label, s.language, s.codec, s.forced, s.isDefault, delivery)
        }
        val chosenSub = when (val r = request.subtitleStreamIndex) {
            -1 -> null
            null -> m.subtitles.firstOrNull { it.forced && (it.embeddedOrder >= 0 || it.file.isNotEmpty()) }?.index
            else -> r
        }
        return PlaySource(
            url = path,
            method = PlayMethod.DIRECT_PLAY,
            container = m.container,
            startMs = request.startMs,
            durationMs = m.runtimeMs,
            audioTracks = audio,
            subtitleTracks = subs,
            audio = (request.audioStreamIndex ?: m.audio.firstOrNull { it.isDefault }?.index ?: m.audio.firstOrNull()?.index)?.toString(),
            subtitle = chosenSub?.toString(),
            description = listOfNotNull(m.videoFacts, m.audioFacts, "Downloaded").joinToString(", "),
        )
    }

    override suspend fun report(event: PlaybackEvent) {
        if (event is PlaybackEvent.Started) return
        val e = find(event.item.id) ?: return
        val duration = e.meta.runtimeMs ?: event.item.durationMs
        val finished = duration != null && duration > 0 && event.positionMs >= duration * WATCHED_SHARE
        // Progress every so often (a closed lid keeps the place), and always at the end.
        saveResume(e, event.positionMs, finished)
    }

    override suspend fun next(item: PlayItem): PlayItem? = find(item.id)?.let { nextOf(it, 1) }?.toPlayItem()

    override suspend fun previous(item: PlayItem): PlayItem? = find(item.id)?.let { nextOf(it, -1) }?.toPlayItem()

    override suspend fun subtitleText(url: String): String? = readText(url)

    companion object {
        /** Watched this far, it counts as watched (as Jellyfin's own default). */
        const val WATCHED_SHARE = 0.9
    }
}

/** What the offline transfers need from the app around them. */
interface OfflineHost {
    /** The server's address and the account, on whichever route works now. */
    suspend fun session(server: String): Pair<String, Account>

    fun authorization(token: String): String

    /** A film or episode is whole in its folder: remember it, with what was fetched beside it. */
    suspend fun landed(job: JellyfinDownloadJob, item: io.github.matiyaaa.fuse.transfer.TransferItem, video: String, meta: OfflineMeta)

    /** A kept one is now in [folder] (on [place]'s drive). */
    suspend fun moved(job: OfflineMoveJob, folder: String, place: io.github.matiyaaa.fuse.transfer.TransferPlace)
}

/** The handlers for keeping films and episodes, and for moving them between drives. */
expect fun offlineHandlers(http: io.ktor.client.HttpClient, host: OfflineHost): List<io.github.matiyaaa.fuse.transfer.TransferHandler>
