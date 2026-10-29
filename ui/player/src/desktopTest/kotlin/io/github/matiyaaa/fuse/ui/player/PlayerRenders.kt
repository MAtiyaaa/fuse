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
import io.github.matiyaaa.fuse.playback.MediaKind
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

/** An engine that "plays" a painted scene, for looking at the player without a stream; stood [at] that point of [length]. */
internal class StillEngine(at: Long = 1_234_000, length: Long = 2_640_000) : PlayerEngine {
    override val state = MutableStateFlow(EngineState(EngineStatus.READY, playing = true, positionMs = at, durationMs = length, bufferedMs = minOf(length, at + 166_000), videoWidth = 1920, videoHeight = 1080, decoder = "hevc", hardware = true))
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

/** The same episode with an intro marked where the still stands, as Intro Skipper marks one. */
internal object SkipResolver : PlaybackResolver by StillResolver {
    override suspend fun segments(item: PlayItem) = listOf(io.github.matiyaaa.fuse.playback.Segment(io.github.matiyaaa.fuse.playback.SegmentKind.INTRO, 1_200_000, 1_300_000))
}

/** The same episode with trickplay pictures, ten by ten on each sheet, every ten seconds. */
internal class ScrubResolver(private val sheet: String) : PlaybackResolver by StillResolver {
    override suspend fun previews(item: PlayItem) = io.github.matiyaaa.fuse.playback.ScrubPreviews(10_000, 320, 180, 10, 10, 264, List(3) { sheet })
}

/** The same episode on a server with a subtitle plugin, which finds these for it. */
internal object FindResolver : PlaybackResolver by StillResolver {
    override val findsSubtitles: Boolean get() = true
    override suspend fun findSubtitles(item: PlayItem) = listOf(
        io.github.matiyaaa.fuse.playback.RemoteSubtitle("1", "The.Harbour.at.Night.S01E03.1080p.WEB", "eng", "Open Subtitles", "srt", 48_210, hashMatch = true),
        io.github.matiyaaa.fuse.playback.RemoteSubtitle("2", "The Harbour at Night 1x03 Undertow", "eng", "Open Subtitles", "srt", 12_904),
        io.github.matiyaaa.fuse.playback.RemoteSubtitle("3", "The.Harbour.at.Night.S01E03.HI", "eng", "Podnapisi", "srt", 3_116, hearingImpaired = true),
        io.github.matiyaaa.fuse.playback.RemoteSubtitle("4", "Harbour at Night E03", "eng", "Open Subtitles", "ass", 640, machineTranslated = true),
    )
}

internal object MusicResolver : PlaybackResolver {
    override suspend fun resolve(item: PlayItem, request: PlayRequest) =
        PlaySource(url = "song", method = PlayMethod.DIRECT_PLAY, durationMs = item.durationMs, description = "FLAC, Stereo")
}

/** The same songs with their words timed to them, as a lyrics file beside the song (or LrcLib) gives them. */
internal object LyricsResolver : PlaybackResolver by MusicResolver {
    override suspend fun lyrics(item: PlayItem) = io.github.matiyaaa.fuse.playback.Lyrics(
        listOf(
            "Lamps along the harbour wall", "count the boats that never call", "",
            "I bought a ticket for the night", "a paper star, a borrowed light", "and every wave that turns to go", "remembers something I don't know",
            "Hold on, the ferry's leaving", "hold on, the water's breathing", "the city folds its arms behind us", "and the dark is kind to us", "",
            "Out past the last light on the buoy", "the engine hums a song for two",
        ).mapIndexed { i, t -> io.github.matiyaaa.fuse.playback.LyricLine(t, 12_000L + i * 5_500L) },
    )
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
            val sheet = previewSheet()
            for (state in listOf("controls", "settings", "subtitles", "skip", "scrub", "find")) {
                runDesktopComposeUiTest(px.first, px.second) {
                    // The player's clocks run for ever; time moves only when told to.
                    mainClock.autoAdvance = false
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val router = InputRouter(scope)
                    val session = PlayerSession(scope) { StillEngine() }
                    session.start(
                        PlayItem("e3", "The Harbour at Night", "Season 1, Episode 3  ·  Undertow", chapters = listOf(Chapter(0, "Intro"), Chapter(600_000, "Act 1"), Chapter(1_500_000, "Act 2")), season = 1, episode = 3, durationMs = 2_640_000),
                        when (state) {
                            "skip" -> SkipResolver
                            "scrub" -> ScrubResolver(sheet.path)
                            "find" -> FindResolver
                            else -> StillResolver
                        },
                    )
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density), LocalInputRouter provides router) {
                            FuseTheme { PlayerScreen(session, onExit = {}) }
                        }
                    }
                    mainClock.advanceTimeBy(600)
                    when (state) {
                        // Playing in the intro with the controls gone: the skip on offer, and A beside it.
                        "skip" -> {
                            session.play()
                            mainClock.advanceTimeBy(4_600)
                        }
                        // Up to the timeline and two steps on: the picture where it lands, over the time.
                        "scrub" -> {
                            router.dispatch(NavAction.UP, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                        }
                        "settings" -> router.dispatch(NavAction.CONTEXT, InputSource.GAMEPAD)
                        "subtitles", "find" -> {
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD)
                            router.dispatch(NavAction.SELECT, InputSource.GAMEPAD)
                            // Down past the tracks to Find subtitles online: what the server's plugins found.
                            if (state == "find") {
                                mainClock.advanceTimeBy(400)
                                repeat(3) { router.dispatch(NavAction.DOWN, InputSource.GAMEPAD) }
                                router.dispatch(NavAction.SELECT, InputSource.GAMEPAD)
                            }
                        }
                    }
                    mainClock.advanceTimeBy(700)
                    val out = File(dir, "$name-$state.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
                }
            }
            // Music: the album large with what plays next, the queue, and the song's words following it.
            val cover = albumCover()
            for (state in listOf("music", "queue", "lyrics")) {
                runDesktopComposeUiTest(px.first, px.second) {
                    mainClock.autoAdvance = false
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val router = InputRouter(scope)
                    val songs = listOf("Departure Board", "Harbour Wall", "Night Ferry", "Signal Lamp", "Undertow", "Port Light", "Last Crossing").mapIndexed { i, t ->
                        PlayItem("t$i", t, "Marlowe Vane", kind = MediaKind.AUDIO, durationMs = 180_000L + i * 23_000L, artwork = cover.path, album = "Night Ferry", artist = "Marlowe Vane")
                    }
                    // A minute into the song: in its chorus, for the words.
                    val session = PlayerSession(scope) { StillEngine(at = 52_000, length = songs[2].durationMs!!) }
                    session.start(songs[2], if (state == "lyrics") LyricsResolver else MusicResolver, 0, songs)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density), LocalInputRouter provides router) {
                            FuseTheme { PlayerScreen(session, onExit = {}) }
                        }
                    }
                    mainClock.advanceTimeBy(600)
                    if (state == "queue") {
                        // Right from play: next, the forward skip... to the queue button.
                        repeat(3) { router.dispatch(NavAction.RIGHT, InputSource.GAMEPAD) }
                        router.dispatch(NavAction.SELECT, InputSource.GAMEPAD)
                    }
                    mainClock.advanceTimeBy(900)
                    val out = File(dir, "$name-$state.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
                }
            }
        }
    }

    /** A sheet of trickplay frames drawn for the render: ten by ten skies, each a little later in the evening. */
    private fun previewSheet(): File {
        val f = File(dir, "trickplay.png")
        if (f.exists()) return f
        val img = java.awt.image.BufferedImage(3200, 1800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        for (i in 0 until 100) {
            val x = (i % 10) * 320
            val y = (i / 10) * 180
            val t = i / 99f
            g.paint = java.awt.GradientPaint(x.toFloat(), y.toFloat(), java.awt.Color(0x1B, 0x2A, 0x4A), x.toFloat(), y + 180f, java.awt.Color((0xB4 + 0x3E * t).toInt().coerceAtMost(255), (0x56 + 0x50 * t).toInt(), 0x6A))
            g.fillRect(x, y, 320, 180)
            g.color = java.awt.Color(0xFF, 0xD5, 0x8A)
            g.fillOval(x + 190, y + 60 + (60 * t).toInt(), 44, 44)
            g.color = java.awt.Color(0x0B, 0x10, 0x20)
            g.fillRect(x, y + 120, 320, 60)
        }
        g.dispose()
        f.parentFile.mkdirs()
        ImageIO.write(img, "png", f)
        return f
    }

    /** A square album cover drawn for the render: a night sea under a moon. */
    private fun albumCover(): File {
        val f = File(dir, "cover.png")
        if (f.exists()) return f
        val img = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x24, 0x33, 0x5C), 0f, 600f, java.awt.Color(0x0B, 0x10, 0x20))
        g.fillRect(0, 0, 600, 600)
        g.color = java.awt.Color(0xF2, 0xE3, 0xC0)
        g.fillOval(380, 90, 110, 110)
        g.color = java.awt.Color(0x13, 0x1B, 0x33)
        g.fillRect(0, 400, 600, 200)
        g.color = java.awt.Color(0xF2, 0xE3, 0xC0, 90)
        for (i in 0 until 8) g.fillRect(400 + (i % 3) * 8, 410 + i * 18, 70 - i * 6, 3)
        g.dispose()
        f.parentFile.mkdirs()
        ImageIO.write(img, "png", f)
        return f
    }
}
