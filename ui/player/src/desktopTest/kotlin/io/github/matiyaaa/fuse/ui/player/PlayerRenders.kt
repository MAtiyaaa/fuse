package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.playback.AudioTrack
import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.Chapter
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.PlayItem
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import io.github.matiyaaa.fuse.playback.Span
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.playback.SubtitleTrack
import io.github.matiyaaa.fuse.playback.TextCue
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** An engine that "plays" a painted scene, for looking at the player without a stream. */
internal class StillEngine : PlayerEngine {
    override val state = MutableStateFlow(EngineState(EngineStatus.READY, playing = true, positionMs = 1_234_000, durationMs = 2_640_000, bufferedMs = 1_400_000, videoWidth = 1920, videoHeight = 1080, decoder = "hevc", hardware = true))
    override val cues = MutableStateFlow<List<Cue>>(listOf(TextCue(0, Long.MAX_VALUE, listOf(listOf(Span("We should have left ")), listOf(Span("before the "), Span("storm", italic = true), Span(" came in."))))))
    override fun load(source: PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean) {}
    override fun play() { state.value = state.value.copy(playing = true) }
    override fun pause() { state.value = state.value.copy(playing = false) }
    override fun seekTo(ms: Long) {}
    override fun setSpeed(speed: Float) {}
    override fun setVolume(volume: Float) {}
    override fun selectAudio(order: Int) {}
    override fun selectSubtitle(order: Int?) {}
    override fun positionMs(): Long = state.value.positionMs
    override fun capabilities(hardwareDecoding: Boolean) = Capabilities(emptyList(), setOf("aac"), containers = setOf("mkv"))
    override fun stop() {}
    override fun release() {}

    @Composable
    override fun Video(modifier: Modifier) {
        // Dusk over water: a sky gradient, a sun, a horizon.
        Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2A4A), Color(0xFFB4566A), Color(0xFFF2A65A))))
            drawCircle(Color(0xFFFFD58A), size.minDimension * 0.12f, Offset(size.width * 0.62f, size.height * 0.58f))
            drawRect(Brush.verticalGradient(listOf(Color(0xFF26324F), Color(0xFF0B1020)), startY = size.height * 0.62f), topLeft = Offset(0f, size.height * 0.62f))
        }
    }
}

internal object StillResolver : PlaybackResolver {
    override suspend fun resolve(item: PlayItem, request: PlayRequest) = PlaySource(
        url = "still", method = PlayMethod.DIRECT_PLAY, durationMs = 2_640_000,
        audioTracks = listOf(
            AudioTrack("1", 1, 0, "English  ·  5.1", "en", "eac3", 6, true),
            AudioTrack("2", 2, 1, "Japanese  ·  Stereo", "ja", "aac", 2),
        ),
        subtitleTracks = listOf(
            SubtitleTrack("3", 3, "English", "en", "ass", delivery = SubtitleDelivery.Embedded(0)),
            SubtitleTrack("4", 4, "English (SDH)", "en", "pgssub", delivery = SubtitleDelivery.BurnIn),
        ),
        audio = "1", subtitle = "3", description = "1080p HEVC, 5.1 EAC3",
    )

    override suspend fun next(item: PlayItem) = PlayItem("e4", "The Long Way Round", "Season 1, Episode 4", season = 1, episode = 4)
}

/**
 * Renders the player for looking at: `-Pfuse.player.renders=<dir>` writes PNGs there; without it
 * nothing runs. Phone, handheld and TV sizes; controls, a sheet, paused.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerRenders {
    private val dir = System.getProperty("fuse.player.renders").orEmpty()

    @Test
    fun render() {
        if (dir.isBlank()) return
        val sizes = listOf(Triple("H", 1920 to 1080, 2.25f), Triple("M", 1920 to 1080, 1.5f), Triple("V", 1080 to 2400, 2.75f))
        for ((name, px, density) in sizes) {
            for (state in listOf("controls", "settings", "subtitles")) {
                runDesktopComposeUiTest(px.first, px.second) {
                    // The player's clocks run for ever; time moves only when told to.
                    mainClock.autoAdvance = false
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val router = InputRouter(scope)
                    val session = PlayerSession(scope) { StillEngine() }
                    session.start(
                        PlayItem("e3", "The Harbour at Night", "Season 1, Episode 3  ·  Undertow", chapters = listOf(Chapter(0, "Intro"), Chapter(600_000, "Act 1"), Chapter(1_500_000, "Act 2")), season = 1, episode = 3, durationMs = 2_640_000),
                        StillResolver,
                    )
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density), LocalInputRouter provides router) {
                            FuseTheme { PlayerScreen(session, onExit = {}) }
                        }
                    }
                    mainClock.advanceTimeBy(600)
                    when (state) {
                        "settings" -> router.dispatch(NavAction.CONTEXT, InputSource.GAMEPAD)
                        "subtitles" -> {
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.SELECT, InputSource.GAMEPAD)
                        }
                    }
                    mainClock.advanceTimeBy(700)
                    val out = File(dir, "$name-$state.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
                }
            }
        }
    }
}
