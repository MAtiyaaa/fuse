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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A weak connection: stalls while playing make Fuse Player ask for a lighter stream, from the same moment. */
class StallTest {
    private class Engine : PlayerEngine {
        override val state = MutableStateFlow(EngineState())
        override val cues = MutableStateFlow<List<Cue>>(emptyList())
        var loads = 0
        override fun load(source: PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean) {
            loads++
            state.value = EngineState(EngineStatus.READY, playing = true, positionMs = startMs)
        }
        override fun play() {}
        override fun pause() {}
        override fun seekTo(ms: Long) {}
        override fun setSpeed(speed: Float) {}
        override fun setVolume(volume: Float) {}
        override fun selectAudio(order: Int) {}
        override fun selectSubtitle(order: Int?) {}
        override fun positionMs(): Long = state.value.positionMs
        override fun capabilities(hardwareDecoding: Boolean) = Capabilities(emptyList(), setOf("aac"), containers = setOf("mkv"))
        override fun stop() {}
        override fun release() {}
        @Composable override fun Video(modifier: Modifier) {}
    }

    private class Resolver : PlaybackResolver {
        val asked = ArrayList<Long?>()
        override suspend fun resolve(item: PlayItem, request: PlayRequest): PlaySource {
            asked += request.maxBitrate
            return PlaySource(url = "x", method = PlayMethod.TRANSCODE, isHls = true, durationMs = 3_600_000, bitrate = 8_000_000, startMs = request.startMs)
        }
        override suspend fun report(event: PlaybackEvent) {}
        override suspend fun next(item: PlayItem): PlayItem? = null
    }

    @Test
    fun threeStallsLowerTheQualityFromTheSamePlace() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = Engine()
        val resolver = Resolver()
        val session = PlayerSession(scope) { engine }
        session.start(PlayItem("e1", "Episode", durationMs = 3_600_000), resolver, startMs = 0)
        kotlinx.coroutines.delay(50)
        assertNull(session.qualityCap)
        // Past the moment after starting, where waiting is expected.
        kotlinx.coroutines.delay(PlayerSession.SEEK_GRACE_MS + 100)
        repeat(3) {
            engine.state.value = engine.state.value.copy(status = EngineStatus.BUFFERING, positionMs = 600_000)
            engine.state.value = engine.state.value.copy(status = EngineStatus.READY)
        }
        kotlinx.coroutines.delay(50)
        assertEquals(4_000_000L, session.qualityCap)
        assertNotNull(session.notice)
        assertEquals(listOf(null, 4_000_000L), resolver.asked)
        assertEquals(2, engine.loads)
        scope.cancel()
    }
}
