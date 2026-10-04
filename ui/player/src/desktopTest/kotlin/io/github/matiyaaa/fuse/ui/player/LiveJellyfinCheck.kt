package io.github.matiyaaa.fuse.ui.player

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.jellyfin.ConnectionMode
import io.github.matiyaaa.fuse.jellyfin.DeviceInfo
import io.github.matiyaaa.fuse.jellyfin.JellyfinClient
import io.github.matiyaaa.fuse.jellyfin.JellyfinConnection
import io.github.matiyaaa.fuse.jellyfin.JellyfinQuality
import io.github.matiyaaa.fuse.jellyfin.JellyfinResolver
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.github.matiyaaa.fuse.jellyfin.LibraryKind
import io.github.matiyaaa.fuse.jellyfin.MediaFilter
import io.github.matiyaaa.fuse.jellyfin.MediaSort
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaybackEvent
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.playback.SubtitleParser
import io.github.matiyaaa.fuse.ui.player.ffmpeg.FfmpegEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A check against a real Jellyfin server, run by hand: FUSE_JF_URL, FUSE_JF_USER and FUSE_JF_PASS
 * in the environment (never in a file). Without them it does nothing. It prints only counts and
 * yes/no answers, never names, addresses or credentials, keeps the token in memory, signs out at
 * the end, and puts back anything it changes (a favourite, a watched mark, a resume point).
 */
class LiveJellyfinCheck {
    private class Memory : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private fun say(line: String) = println("live: $line")

    @Test
    fun againstARealServer() = runBlocking {
        val url = System.getenv("FUSE_JF_URL").orEmpty()
        val user = System.getenv("FUSE_JF_USER").orEmpty()
        val pass = System.getenv("FUSE_JF_PASS").orEmpty()
        if (url.isBlank() || user.isBlank() || pass.isBlank()) return@runBlocking
        val http = HttpClient(CIO) {
            install(HttpTimeout)
            // Through the machine's proxy where there is one.
            System.getenv("HTTPS_PROXY")?.takeIf { it.startsWith("http://") }?.let { p -> engine { proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(p)) } }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = JellyfinService(JellyfinClient(http, DeviceInfo("Fuse check", "fuse-live-check", "0.2.8")), Memory(), scope)
        try {
            service.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = url))
            val tested = service.test(url)
            say("server answers: ${tested.isSuccess}, version ${tested.getOrNull()?.second?.version}")
            assertTrue(tested.isSuccess)
            service.signIn(user, pass).getOrThrow()
            say("signed in: true, route ${service.state.value.route}")

            val shelves = service.home()
            say("home shelves: ${shelves.size} (${shelves.joinToString { "${it.kind}:${it.items.size}" }})")
            val libraries = service.libraries()
            say("libraries: ${libraries.size} (${libraries.groupingBy { it.library }.eachCount()})")
            val films = libraries.firstOrNull { it.library == LibraryKind.MOVIES } ?: libraries.firstOrNull { it.library == LibraryKind.SHOWS }
            if (films != null) {
                val p1 = service.page(films.id, if (films.library == LibraryKind.MOVIES) "Movie" else "Series", MediaSort.NAME, MediaFilter.ALL, 0, 20)
                val p2 = service.page(films.id, if (films.library == LibraryKind.MOVIES) "Movie" else "Series", MediaSort.NAME, MediaFilter.ALL, 20, 20)
                say("paging: ${p1.items.size} then ${p2.items.size} of ${p1.total}, no overlap ${p1.items.map { it.id }.intersect(p2.items.map { it.id }.toSet()).isEmpty()}")
                p1.items.firstOrNull()?.let { first ->
                    val detail = service.item(first.id)
                    say("item page: overview ${detail.overview != null}, cast ${detail.cast.size}, backdrop ${detail.backdrop != null}, logo ${detail.logo != null}")
                    if (detail.type == MediaType.SERIES) {
                        val seasons = service.seasons(detail.id)
                        val eps = seasons.firstOrNull()?.let { service.episodes(detail.id, it.id) }.orEmpty()
                        say("series: ${seasons.size} seasons, first has ${eps.size} episodes")
                    }
                }
            }
            val hits = service.search("the")
            say("search: ${hits.values.sumOf { it.size }} results in ${hits.keys.size} kinds")

            // Favourite and watched, on something neither: set, check, and put back.
            val plain = service.page(films?.id, "Movie,Episode", MediaSort.NAME, MediaFilter.UNPLAYED, 0, 30).items.firstOrNull { !it.favorite && !it.played && it.resumeMs == 0L }
            if (plain != null) {
                service.setFavorite(plain.id, true)
                val fav = service.item(plain.id).favorite
                service.setFavorite(plain.id, false)
                service.setPlayed(plain.id, true)
                val watched = service.item(plain.id).played
                service.setPlayed(plain.id, false)
                val back = service.item(plain.id)
                say("favourite round trip: $fav, watched round trip: $watched, put back: ${!back.favorite && !back.played}")
                assertTrue(!back.favorite && !back.played)
            }

            // Play something for a few seconds on the desktop engine: one already started, so its
            // resume point can be reported back exactly as it was.
            val resume = shelves.firstOrNull { it.id == "continue" }?.items?.firstOrNull()
            val target = resume ?: films?.let { f -> service.page(f.id, "Movie,Episode", MediaSort.NAME, MediaFilter.ALL, 0, 5).items.firstOrNull { it.isPlayable } }
            if (target != null) {
                val engine = FfmpegEngine()
                val resolver = JellyfinResolver(service) { JellyfinQuality() }
                val item = target.toPlayItem()
                val start = target.resumeMs
                val source = resolver.resolve(item, PlayRequest(startMs = start, capabilities = engine.capabilities(true)))
                say("negotiated: ${source.method}, hls ${source.isHls}, ${source.audioTracks.size} audio, ${source.subtitleTracks.size} subtitles (${source.subtitleTracks.groupingBy { it.delivery::class.simpleName }.eachCount()})")
                engine.load(source, (source.startMs - source.offsetMs).coerceAtLeast(0), source.audioTracks.firstOrNull { it.id == source.audio }?.order, null)
                val t0 = System.currentTimeMillis()
                var frames = 0
                while (System.currentTimeMillis() - t0 < 25_000 && (engine.state.value.status != EngineStatus.READY || frames < 60)) {
                    if (engine.takeFrameForTest() != null) frames++
                    if (engine.state.value.status == EngineStatus.ERROR) break
                    Thread.sleep(10)
                }
                val p0 = engine.positionMs()
                Thread.sleep(3_000)
                while (engine.takeFrameForTest() != null) frames++
                val p1 = engine.positionMs()
                say("playing: status ${engine.state.value.status}, ${engine.state.value.videoWidth}x${engine.state.value.videoHeight}, decoder ${engine.state.value.decoder}, frames $frames, clock moved ${p1 - p0} ms")
                if (resume != null) {
                    resolver.report(PlaybackEvent.Started(item, source, start))
                    resolver.report(PlaybackEvent.Progress(item, source, start, paused = false))
                }
                source.audioTracks.getOrNull(1)?.let { other ->
                    if (source.method == io.github.matiyaaa.fuse.playback.PlayMethod.DIRECT_PLAY) {
                        engine.selectAudio(other.order)
                        Thread.sleep(2_000)
                        say("second audio track: status ${engine.state.value.status}")
                    }
                }
                source.subtitleTracks.firstOrNull { it.delivery is SubtitleDelivery.External }?.let { t ->
                    val d = t.delivery as SubtitleDelivery.External
                    val text = resolver.subtitleText(d.url)
                    val cues = text?.let { SubtitleParser.parse(it, d.format) }
                    say("text subtitles: downloaded ${text != null}, ${cues?.cues?.size ?: 0} cues")
                }
                engine.release()
                if (resume != null) {
                    resolver.report(PlaybackEvent.Stopped(item, source, start, finished = false))
                    val after = service.item(target.id)
                    say("resume point kept: ${kotlin.math.abs(after.resumeMs - start) < 2_000}")
                }
                assertTrue(frames > 0)
            }
        } finally {
            runCatching { service.signOut() }
            scope.cancel()
            http.close()
        }
    }
}
