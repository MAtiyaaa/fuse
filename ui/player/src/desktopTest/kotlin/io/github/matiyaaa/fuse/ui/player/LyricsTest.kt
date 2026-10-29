package io.github.matiyaaa.fuse.ui.player

import io.github.matiyaaa.fuse.playback.LyricLine
import io.github.matiyaaa.fuse.playback.Lyrics
import io.github.matiyaaa.fuse.playback.MediaKind
import io.github.matiyaaa.fuse.playback.PlayItem
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A song's words: the line sung found by time, followed as the song plays, and none for video. */
class LyricsTest {
    private val timed = Lyrics(listOf(LyricLine("One", 300), LyricLine("Two", 700), LyricLine("", 1_100), LyricLine("Four", 1_400)))

    private class Resolver(val words: Lyrics?) : PlaybackResolver {
        var asked = 0
        override suspend fun resolve(item: PlayItem, request: PlayRequest) =
            PlaySource(url = "x", method = PlayMethod.DIRECT_PLAY, durationMs = item.durationMs, startMs = request.startMs)
        override suspend fun lyrics(item: PlayItem): Lyrics? {
            asked++
            return words
        }
    }

    @Test
    fun theLineSungIsTheLastBegun() {
        assertEquals(-1, timed.lineAt(0))
        assertEquals(0, timed.lineAt(300))
        assertEquals(0, timed.lineAt(699))
        assertEquals(2, timed.lineAt(1_200))
        assertEquals(3, timed.lineAt(60_000))
        // Words without times are never followed by time.
        val plain = Lyrics(listOf(LyricLine("One"), LyricLine("Two")))
        assertFalse(plain.synced)
        assertEquals(-1, plain.lineAt(10_000))
        assertFalse(Lyrics(emptyList()).synced)
    }

    @Test
    fun theLineFollowsTheSongWhileItPlaysAndWaitsWhilePaused() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ClockEngine()
        val session = PlayerSession(scope) { engine }
        session.start(PlayItem("s1", "Night Ferry", kind = MediaKind.AUDIO, durationMs = 60_000), Resolver(timed), startMs = 0)
        delay(100)
        assertEquals(timed, session.lyrics)
        assertEquals(-1, session.lyricLine)
        delay(350)
        assertEquals(0, session.lyricLine)
        delay(400)
        assertEquals(1, session.lyricLine)
        session.pause()
        delay(800)
        // Paused in the second line: it stays there.
        assertEquals(1, session.lyricLine)
        // Moved, it shows the line there at once.
        session.seekTo(1_450)
        assertEquals(3, session.lyricLine)
        scope.cancel()
    }

    @Test
    fun videoIsNeverAskedForWords() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = PlayerSession(scope) { ClockEngine() }
        val resolver = Resolver(timed)
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), resolver, startMs = 0)
        delay(100)
        assertEquals(0, resolver.asked)
        assertNull(session.lyrics)
        // A song after it is.
        session.start(PlayItem("s2", "Harbour Wall", kind = MediaKind.AUDIO, durationMs = 60_000), resolver, startMs = 0)
        delay(100)
        assertEquals(1, resolver.asked)
        assertTrue(session.lyrics?.synced == true)
        scope.cancel()
    }
}
