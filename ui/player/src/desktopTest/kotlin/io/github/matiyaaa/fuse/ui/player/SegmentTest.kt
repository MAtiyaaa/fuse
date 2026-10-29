package io.github.matiyaaa.fuse.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.PlayItem
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.PlaybackEvent
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import io.github.matiyaaa.fuse.playback.Segment
import io.github.matiyaaa.fuse.playback.SegmentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An engine whose picture moves with the real clock while it plays. */
internal class ClockEngine : PlayerEngine {
    override val state = MutableStateFlow(EngineState())
    override val cues = MutableStateFlow<List<Cue>>(emptyList())
    private var base = 0L
    private var since = System.currentTimeMillis()
    val seeks = ArrayList<Long>()
    override fun load(source: PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean) {
        base = startMs
        since = System.currentTimeMillis()
        state.value = EngineState(EngineStatus.READY, playing = true, positionMs = startMs)
    }
    override fun play() {}
    override fun pause() {
        base = positionMs()
        state.value = state.value.copy(playing = false)
    }
    override fun seekTo(ms: Long) {
        seeks += ms
        base = ms
        since = System.currentTimeMillis()
    }
    override fun setSpeed(speed: Float) {}
    override fun setVolume(volume: Float) {}
    override fun selectAudio(order: Int) {}
    override fun selectSubtitle(order: Int?) {}
    override fun positionMs(): Long = if (state.value.playing) base + (System.currentTimeMillis() - since) else base
    override fun capabilities(hardwareDecoding: Boolean) = Capabilities(emptyList(), setOf("aac"), containers = setOf("mkv"))
    override fun stop() {}
    override fun release() {}
    @Composable override fun Video(modifier: Modifier) {}
}

/** Intros and credits the server marks: offered as they play, skipped by themselves where asked, and never ticked for. */
class SegmentTest {
    private class Resolver(val parts: List<Segment>) : PlaybackResolver {
        override suspend fun resolve(item: PlayItem, request: PlayRequest) =
            PlaySource(url = "x", method = PlayMethod.DIRECT_PLAY, durationMs = item.durationMs, startMs = request.startMs)
        override suspend fun report(event: PlaybackEvent) {}
        override suspend fun next(item: PlayItem): PlayItem? = if (item.id == "e1") PlayItem("e2", "Second", durationMs = 60_000) else null
        override suspend fun segments(item: PlayItem) = if (item.id == "e1") parts else emptyList()
    }

    private val intro = Segment(SegmentKind.INTRO, 400, 900)
    private val credits = Segment(SegmentKind.OUTRO, 50_000, 59_000)

    @Test
    fun anIntroIsOfferedWhileItPlaysAndGoneAfter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ClockEngine()
        val session = PlayerSession(scope) { engine }
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), Resolver(listOf(intro, credits)), startMs = 0)
        delay(150)
        assertEquals(listOf(intro, credits), session.segments)
        assertNull(session.segment)
        delay(500)
        assertEquals(intro, session.segment)
        delay(600)
        assertNull(session.segment)
        // Skipping it from the button goes to its end; seeking back in offers it again.
        session.seekTo(500)
        delay(50)
        assertEquals(intro, session.segment)
        session.skipSegment()
        assertEquals(900L, engine.seeks.last())
        delay(50)
        assertNull(session.segment)
        scope.cancel()
    }

    @Test
    fun skippedByItselfOnceThenLeftToPlayWhenSoughtBackInto() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ClockEngine()
        val session = PlayerSession(scope) { engine }
        session.settings = PlayerSettings(skipIntros = SkipMode.AUTO)
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), Resolver(listOf(intro, credits)), startMs = 0)
        delay(650)
        assertEquals(900L, engine.seeks.single())
        assertEquals("Intro skipped", session.notice)
        session.seekTo(450)
        delay(100)
        // Gone back to watch it: it stays, offered rather than skipped again.
        assertEquals(intro, session.segment)
        assertEquals(listOf(900L, 450L), engine.seeks)
        scope.cancel()
    }

    @Test
    fun theCreditsOfAnEpisodeWithAnotherAfterGoOnToIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ClockEngine()
        val session = PlayerSession(scope) { engine }
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), Resolver(listOf(intro, credits)), startMs = 50_500)
        delay(150)
        assertEquals(credits, session.segment)
        assertEquals("e2", session.upNext?.id)
        session.skipSegment()
        delay(150)
        assertEquals("e2", session.item?.id)
        // The next episode has none of its own: nothing is offered.
        assertTrue(session.segments.isEmpty())
        assertNull(session.segment)
        scope.cancel()
    }

    @Test
    fun pausedItWaitsWithoutLooking() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ClockEngine()
        val session = PlayerSession(scope) { engine }
        session.settings = PlayerSettings(skipIntros = SkipMode.AUTO)
        session.start(PlayItem("e1", "Pilot", durationMs = 60_000), Resolver(listOf(intro, credits)), startMs = 0)
        delay(100)
        session.pause()
        delay(700)
        // Paused before the intro: nothing moved, nothing skipped.
        assertTrue(engine.seeks.isEmpty())
        assertNull(session.segment)
        scope.cancel()
    }
}
