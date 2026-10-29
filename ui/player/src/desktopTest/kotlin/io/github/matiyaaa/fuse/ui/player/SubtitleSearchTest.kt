package io.github.matiyaaa.fuse.ui.player

import io.github.matiyaaa.fuse.playback.PlayItem
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import io.github.matiyaaa.fuse.playback.RemoteSubtitle
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.playback.SubtitleFormat
import io.github.matiyaaa.fuse.playback.SubtitleTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Subtitles found elsewhere: looked for, brought in, and shown from the same moment. */
class SubtitleSearchTest {
    private class Resolver(val finds: Boolean = true) : PlaybackResolver {
        var fetched = false
        val arrive = CompletableDeferred<Boolean>()
        override val findsSubtitles: Boolean get() = finds
        override suspend fun resolve(item: PlayItem, request: PlayRequest) = PlaySource(
            url = "x", method = PlayMethod.DIRECT_PLAY, durationMs = item.durationMs, startMs = request.startMs,
            subtitleTracks = buildList {
                add(SubtitleTrack("3", 3, "English", "eng", "subrip", delivery = SubtitleDelivery.Embedded(0)))
                if (fetched) add(SubtitleTrack("7", 7, "English (OpenSubtitles)", "eng", "srt", delivery = SubtitleDelivery.External("http://server/7.srt", SubtitleFormat.SRT)))
            },
        )
        override suspend fun findSubtitles(item: PlayItem) = listOf(RemoteSubtitle("os1", "Pilot.1080p.srt", "eng", "Open Subtitles", "srt", 1_200, hashMatch = true))
        override suspend fun fetchSubtitle(item: PlayItem, subtitle: RemoteSubtitle): Boolean {
            val ok = arrive.await()
            fetched = ok
            return ok
        }
        override suspend fun subtitleText(url: String) = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
    }

    @Test
    fun aSubtitleFoundElsewhereIsBroughtInAndShown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = PlayerSession(scope) { ClockEngine() }
        val r = Resolver()
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), r, startMs = 0)
        delay(50)
        assertTrue(session.findsSubtitles)
        session.findSubtitles()
        delay(50)
        val found = assertIs<SubtitleSearch.Found>(session.subtitleSearch)
        session.fetchSubtitle(found.subtitles.single())
        assertIs<SubtitleSearch.Fetching>(session.subtitleSearch)
        // The sheet closing while it comes leaves it coming.
        session.leaveSubtitleSearch()
        assertIs<SubtitleSearch.Fetching>(session.subtitleSearch)
        r.arrive.complete(true)
        delay(150)
        assertNull(session.subtitleSearch)
        assertEquals("7", session.source?.subtitle)
        assertEquals("Subtitles added", session.notice)
        scope.cancel()
    }

    @Test
    fun aSubtitleThatNeverCameSaysSoAndAProviderWithoutSearchOffersNone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = PlayerSession(scope) { ClockEngine() }
        val r = Resolver()
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), r, startMs = 0)
        delay(50)
        session.findSubtitles()
        delay(50)
        session.fetchSubtitle((session.subtitleSearch as SubtitleSearch.Found).subtitles.single())
        r.arrive.complete(false)
        delay(100)
        assertIs<SubtitleSearch.Failed>(session.subtitleSearch)
        assertNull(session.source?.subtitle)
        // Looking stops with its sheet.
        session.leaveSubtitleSearch()
        assertNull(session.subtitleSearch)

        session.start(PlayItem("e2", "Second", durationMs = 60_000), Resolver(finds = false), startMs = 0)
        delay(50)
        assertFalse(session.findsSubtitles)
        scope.cancel()
    }
}
