package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.playback.AudioTrack
import io.github.matiyaaa.fuse.playback.Capabilities
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
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments

/** The quality wanted on each route: the most the connection should carry, null for no limit. */
data class JellyfinQuality(val localMaxBitrate: Long? = null, val remoteMaxBitrate: Long? = 20_000_000)

/**
 * Jellyfin behind Fuse Player: asks the server how to play an item for this player
 * (PlaybackInfo, with the device profile built from what the engine measured), then hands the
 * player a [PlaySource] of Fuse's own: the URL, how it plays, and its tracks with how each subtitle
 * arrives. Choosing a track the server must convert asks again from the same moment. A stream that
 * fails is tried the other way in (local or remote), then converted. Reports go back to the server
 * so resume points and watched marks follow you.
 */
class JellyfinResolver(
    private val service: JellyfinService,
    private val quality: () -> JellyfinQuality,
) : PlaybackResolver {
    /** The media source behind each play session, for the reports. */
    private val sources = HashMap<String, String>()

    override suspend fun resolve(item: PlayItem, request: PlayRequest): PlaySource {
        var forceConvert = false
        request.failed?.let { failed ->
            // Another way in first; if there is none (or it was converting already) convert it.
            if (!service.switchRoute() && failed.method != PlayMethod.TRANSCODE) forceConvert = true
        }
        val (base, account) = service.session()
        val route = service.state.value.route
        val q = quality()
        val limit = request.maxBitrate ?: if (route == Route.LOCAL) q.localMaxBitrate else q.remoteMaxBitrate
        val profile = DeviceProfiles.build(request.capabilities, limit)
        val info = service.client.playbackInfo(
            base, account, item.id,
            PlaybackInfoRequestDto(
                userId = account.userId,
                deviceProfile = profile,
                maxStreamingBitrate = limit ?: DeviceProfiles.UNLIMITED,
                startTimeTicks = msToTicks(request.startMs),
                audioStreamIndex = request.audioStreamIndex,
                subtitleStreamIndex = request.subtitleStreamIndex,
                enableDirectPlay = !forceConvert,
                enableDirectStream = !forceConvert,
            ),
        )
        info.errorCode?.let { throw JellyfinException(errorText(it), JellyfinException.Kind.SERVER) }
        val ms = info.mediaSources.firstOrNull() ?: throw JellyfinException("The server has nothing to play for this.", JellyfinException.Kind.SERVER)
        return toSource(base, account, item, ms, info.playSessionId, request, request.capabilities)
    }

    internal fun toSource(base: String, account: Account, item: PlayItem, ms: MediaSourceDto, session: String?, request: PlayRequest, caps: Capabilities): PlaySource {
        val audioItem = item.kind == MediaKind.AUDIO
        val headers = mapOf("Authorization" to service.client.authorization(account.token))
        val (url, method, hls) = when {
            ms.supportsDirectPlay && ms.transcodingUrl == null -> Triple(staticUrl(base, item.id, ms, session, audioItem), PlayMethod.DIRECT_PLAY, false)
            ms.transcodingUrl != null -> Triple(
                base.trimEnd('/') + ms.transcodingUrl,
                if (ms.transcodeReasons.orEmpty().all { it in CONTAINER_ONLY }) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
                ms.transcodingSubProtocol.equals("hls", ignoreCase = true),
            )
            ms.supportsDirectStream -> Triple(staticUrl(base, item.id, ms, session, audioItem), PlayMethod.DIRECT_STREAM, false)
            else -> throw JellyfinException("The server can't stream this to Fuse.", JellyfinException.Kind.SERVER)
        }
        val converting = method != PlayMethod.DIRECT_PLAY
        val streams = ms.mediaStreams.sortedBy { it.index }
        val audioStreams = streams.filter { it.type == "Audio" && !it.isExternal }
        val audio = audioStreams.mapIndexed { order, s ->
            AudioTrack(
                id = s.index.toString(), streamIndex = s.index,
                // A converted stream carries only the track asked for.
                order = if (converting) 0 else order,
                label = s.displayTitle ?: s.title ?: s.language ?: "Track ${order + 1}",
                language = s.language, codec = s.codec, channels = s.channels, isDefault = s.isDefault,
            )
        }
        val embeddedSubs = streams.filter { it.type == "Subtitle" && !it.isExternal }
        val subtitles = streams.filter { it.type == "Subtitle" }.map { s ->
            SubtitleTrack(
                id = s.index.toString(), streamIndex = s.index,
                label = s.displayTitle ?: s.title ?: s.language ?: "Subtitles",
                language = s.language, codec = s.codec, forced = s.isForced, isDefault = s.isDefault,
                delivery = deliveryOf(base, item.id, ms, s, embeddedSubs.indexOf(s), method, caps),
            )
        }
        val chosenAudio = request.audioStreamIndex ?: ms.defaultAudioStreamIndex ?: audioStreams.firstOrNull { it.isDefault }?.index ?: audioStreams.firstOrNull()?.index
        val chosenSub = when (val r = request.subtitleStreamIndex) {
            -1 -> null
            null -> ms.defaultSubtitleStreamIndex?.takeIf { it >= 0 }
            else -> r
        }
        session?.let { sid -> ms.id?.let { sources[sid] = it } }
        val video = streams.firstOrNull { it.type == "Video" }
        val a = audioStreams.firstOrNull { it.index == chosenAudio }
        return PlaySource(
            url = url,
            method = method,
            headers = headers,
            container = ms.container,
            isHls = hls,
            startMs = request.startMs,
            durationMs = ticksToMs(ms.runTimeTicks),
            audioTracks = audio,
            subtitleTracks = subtitles,
            audio = chosenAudio?.toString(),
            subtitle = chosenSub?.toString(),
            sessionId = session,
            description = listOfNotNull(
                video?.let { v -> listOfNotNull(resolutionLabel(v.width, v.height), v.codec?.uppercase(), v.videoRangeType?.takeIf { it != "SDR" }).joinToString(" ") },
                a?.let { listOfNotNull(it.channels?.let { c -> channelLabel(c) }, it.codec?.uppercase()).joinToString(" ") },
            ).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null },
            bitrate = ms.bitrate,
            transcodeReason = if (method == PlayMethod.DIRECT_PLAY) null else reasonText(ms.transcodeReasons.orEmpty()),
        )
    }

    private fun staticUrl(base: String, id: String, ms: MediaSourceDto, session: String?, audio: Boolean): String {
        val b = URLBuilder(base.trimEnd('/'))
        b.appendPathSegments(if (audio) "Audio" else "Videos", id, "stream")
        b.parameters.append("static", "true")
        ms.id?.let { b.parameters.append("mediaSourceId", it) }
        session?.let { b.parameters.append("playSessionId", it) }
        b.parameters.append("deviceId", service.client.device.id)
        return b.buildString()
    }

    /**
     * How one subtitle reaches the screen: as the server says when it chose; otherwise text as a
     * file Fuse draws, pictures in the stream when playing the file as it is and the engine reads
     * them, else drawn in by the server.
     */
    private fun deliveryOf(base: String, id: String, ms: MediaSourceDto, s: MediaStreamDto, embeddedOrder: Int, method: PlayMethod, caps: Capabilities): SubtitleDelivery {
        val codec = s.codec?.lowercase()
        val format = SubtitleParser.formatOf(codec) ?: SubtitleParser.formatOf(s.deliveryUrl?.substringBefore('?'))
        val textUrl = s.deliveryUrl?.let { base.trimEnd('/') + it } ?: format?.let { f ->
            val ext = when (f) {
                io.github.matiyaaa.fuse.playback.SubtitleFormat.SRT -> "srt"
                io.github.matiyaaa.fuse.playback.SubtitleFormat.VTT -> "vtt"
                io.github.matiyaaa.fuse.playback.SubtitleFormat.ASS -> "ass"
            }
            "${base.trimEnd('/')}/Videos/$id/${ms.id}/Subtitles/${s.index}/0/Stream.$ext"
        }
        return when (s.deliveryMethod) {
            "External", "Hls" -> if (textUrl != null && format != null) SubtitleDelivery.External(textUrl, format) else SubtitleDelivery.BurnIn
            "Embed" -> if (embeddedOrder >= 0) SubtitleDelivery.Embedded(embeddedOrder) else SubtitleDelivery.BurnIn
            "Encode" -> SubtitleDelivery.BurnIn
            else -> when {
                (s.isTextSubtitleStream || format != null) && textUrl != null && format != null -> SubtitleDelivery.External(textUrl, format)
                method == PlayMethod.DIRECT_PLAY && embeddedOrder >= 0 && codec in caps.embeddedSubtitles -> SubtitleDelivery.Embedded(embeddedOrder)
                else -> SubtitleDelivery.BurnIn
            }
        }
    }

    override suspend fun report(event: PlaybackEvent) {
        val (base, account) = runCatching { service.session() }.getOrNull() ?: return
        val src = event.source
        val body = PlaybackReportDto(
            itemId = event.item.id,
            mediaSourceId = src.sessionId?.let { sources[it] },
            playSessionId = src.sessionId,
            positionTicks = msToTicks(event.positionMs),
            isPaused = (event as? PlaybackEvent.Progress)?.paused ?: false,
            playMethod = when (src.method) {
                PlayMethod.DIRECT_PLAY -> "DirectPlay"
                PlayMethod.DIRECT_STREAM -> "DirectStream"
                PlayMethod.TRANSCODE -> "Transcode"
            },
            audioStreamIndex = src.audio?.toIntOrNull(),
            subtitleStreamIndex = src.subtitle?.toIntOrNull() ?: -1,
        )
        val path = when (event) {
            is PlaybackEvent.Started -> "Sessions/Playing"
            is PlaybackEvent.Progress -> "Sessions/Playing/Progress"
            is PlaybackEvent.Stopped -> "Sessions/Playing/Stopped"
        }
        runCatching { service.client.report(base, account, path, body) }
        if (event is PlaybackEvent.Stopped) {
            src.sessionId?.let { sources.remove(it) }
            service.changed()
        }
    }

    override suspend fun next(item: PlayItem): PlayItem? {
        val series = item.seriesId ?: return null
        return runCatching { service.call { b, a -> service.client.adjacentEpisodes(b, a, series, item.id).second } }.getOrNull()?.toPlayItem()
    }

    override suspend fun previous(item: PlayItem): PlayItem? {
        val series = item.seriesId ?: return null
        return runCatching { service.call { b, a -> service.client.adjacentEpisodes(b, a, series, item.id).first } }.getOrNull()?.toPlayItem()
    }

    override suspend fun subtitleText(url: String): String? = runCatching { service.call { _, a -> service.client.text(url, a) } }.getOrNull()

    private fun errorText(code: String) = when (code) {
        "NotAllowed" -> "This account isn't allowed to play this."
        "NoCompatibleStream" -> "The server can't make a stream this device plays."
        "RateLimitExceeded" -> "The server is busy. Try again in a moment."
        else -> "The server couldn't start this ($code)."
    }

    /** Why the server converts, in plain words. */
    private fun reasonText(reasons: List<String>): String? {
        if (reasons.isEmpty()) return null
        val words = reasons.mapNotNull {
            when (it) {
                "ContainerNotSupported" -> "repackaged for this player"
                "VideoCodecNotSupported" -> "the picture is converted for this player"
                "AudioCodecNotSupported" -> "the sound is converted for this player"
                "VideoRangeTypeNotSupported" -> "HDR is converted for this screen"
                "SubtitleCodecNotSupported" -> "subtitles are drawn into the picture"
                "ContainerBitrateExceedsLimit", "VideoBitrateNotSupported", "AudioBitrateNotSupported" -> "lowered for your connection"
                "VideoResolutionNotSupported" -> "made smaller for this player"
                "VideoBitDepthNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported" -> "the picture is converted for this player"
                "AudioChannelsNotSupported" -> "the sound is mixed down"
                else -> null
            }
        }.distinct()
        return words.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    }

    companion object {
        /** Reasons that only mean the file is repackaged, not converted. */
        private val CONTAINER_ONLY = setOf("ContainerNotSupported")
    }
}
