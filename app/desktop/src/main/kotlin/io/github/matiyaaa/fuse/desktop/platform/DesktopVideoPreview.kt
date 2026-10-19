package io.github.matiyaaa.fuse.desktop.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.ui.player.EngineStatus
import io.github.matiyaaa.fuse.ui.player.ffmpeg.FfmpegEngine
import io.github.matiyaaa.fuse.ui.shell.platform.VideoPreview
import kotlin.math.max

/**
 * Muted, looping gameplay previews behind the room on a computer, played by Fuse Player's own
 * engine (FFmpeg), as Android plays them with Media3: silent, starting over at the end, filling the
 * screen and cropping what overflows (Android's "zoom"). The engine is let go as the preview leaves.
 * Fuse decides when one plays at all (Settings, Art and details, Video previews, and never while
 * drawing without a graphics card, whose quality turns background video off).
 */
class DesktopVideoPreview : VideoPreview {
    @Composable
    override fun Player(source: String, playing: Boolean, modifier: Modifier, onFirstFrame: () -> Unit) {
        val engine = remember(source) { FfmpegEngine() }
        val firstFrame by rememberUpdatedState(onFirstFrame)
        DisposableEffect(engine) {
            engine.setVolume(0f)
            engine.load(PlaySource(url = source, method = PlayMethod.DIRECT_PLAY), startMs = 0, audioOrder = null, subtitleOrder = null, play = playing)
            onDispose { engine.release() }
        }
        LaunchedEffect(engine, playing) { if (playing) engine.play() else engine.pause() }
        val state by engine.state.collectAsState()
        LaunchedEffect(engine) {
            var told = false
            engine.state.collect { s ->
                if (!told && s.status == EngineStatus.READY && s.videoWidth > 0) {
                    told = true
                    firstFrame()
                }
                // A preview loops, as Android's does.
                if (s.status == EngineStatus.ENDED) {
                    engine.seekTo(0)
                    engine.play()
                }
            }
        }
        var box by remember { mutableStateOf(IntSize.Zero) }
        Box(modifier.clipToBounds().onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
            val w = state.videoWidth * state.pixelRatio
            val h = state.videoHeight.toFloat()
            if (w > 0f && h > 0f && box.width > 0 && box.height > 0) {
                val scale = max(box.width / w, box.height / h)
                with(LocalDensity.current) {
                    engine.Video(Modifier.requiredSize((w * scale).toDp(), (h * scale).toDp()))
                }
            }
        }
    }
}
