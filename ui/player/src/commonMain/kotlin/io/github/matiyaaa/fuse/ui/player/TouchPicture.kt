package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import kotlinx.coroutines.delay

/**
 * Fuse Player's picture with controls for touch alone, for a screen whose controller belongs to
 * the other screen (the screen above, in Flipped mode, while the menus are on the touch screen
 * below; or the second screen a film was put on). The controls start hidden so the picture is
 * whole. A tap shows them: Back to the other screen, Stop, skip back, play or pause, skip forward
 * and the timeline to drag; they fade a few seconds after the last touch while it plays. A double
 * tap on either side skips, as on a phone. Nothing here listens to the controller.
 */
@Composable
fun PlayerTouchPicture(
    session: PlayerSession,
    modifier: Modifier = Modifier,
    /** Puts the picture back with the menus, or null when it can't move. */
    onSwap: (() -> Unit)? = null,
    swapLabel: String = "Play on the other screen",
) {
    var shown by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(0L) }
    val engine = session.engine
    val state = engine?.state?.collectAsState()?.value ?: EngineState()
    val playing = state.playing
    fun poke() {
        shown = true
        touched++
    }
    // Fades once nothing has been touched for a while, unless paused.
    LaunchedEffect(shown, touched, playing) {
        if (shown && playing) {
            delay(HIDE_MS)
            shown = false
        }
    }
    val reveal by fuselineFloat(if (shown) 1f else 0f, Fuse.motion.fade(io.github.matiyaaa.fuse.ui.fuseline.Durations.BASE), label = "touch controls")
    val seek = session.settings.seekSeconds
    BoxWithConstraints(
        modifier.fillMaxSize().pointerInput(seek) {
            detectTapGestures(
                onTap = { if (shown) shown = false else poke() },
                onDoubleTap = { o ->
                    val forward = o.x > size.width / 2
                    session.seekBy(if (forward) seek * 1000L else -seek * 1000L)
                    poke()
                },
            )
        },
    ) {
        val narrow = maxWidth < 520.dp
        PlayerPicture(session, Modifier.fillMaxSize())
        if (reveal <= 0.01f) return@BoxWithConstraints
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
            // Shade top and bottom, so white controls read over any picture.
            Box(Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
            // What is playing, and where it can go.
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = if (narrow) Space.m else Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    FText(session.item?.title.orEmpty(), if (narrow) Fuse.type.bodyStrong else Fuse.type.titleSmall, color = Color.White, maxLines = 1)
                    session.item?.subtitle?.let { FText(it, Fuse.type.caption, color = Color.White.copy(alpha = 0.72f), maxLines = 1) }
                }
                onSwap?.let { swap ->
                    Spacer(Modifier.width(Space.s))
                    RoundButton(FuseIcons.Swap, swapLabel, selected = false, size = 40.dp) { poke(); swap() }
                }
                Spacer(Modifier.width(Space.s))
                RoundButton(FuseIcons.Close, "Stop", selected = false, size = 40.dp) { session.stop() }
            }
            // Skip back, play or pause, skip forward, in the middle of the picture.
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) Space.l else Space.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(FuseIcons.RotateCcw, "Back $seek seconds", selected = false, size = 48.dp, badge = seek.toString()) { poke(); session.seekBy(-seek * 1000L) }
                RoundButton(if (playing) FuseIcons.Pause else FuseIcons.Play, if (playing) "Pause" else "Play", selected = false, size = 64.dp, filled = true) { poke(); session.toggle() }
                RoundButton(FuseIcons.RotateCw, "Forward $seek seconds", selected = false, size = 48.dp, badge = seek.toString()) { poke(); session.seekBy(seek * 1000L) }
            }
            // The timeline, with the time played and the time left.
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = if (narrow) Space.m else Space.l, vertical = Space.s)) {
                PlayerTimeline(
                    position = { session.positionMs() },
                    durationMs = session.durationMs(),
                    bufferedMs = { (session.source?.offsetMs ?: 0) + (session.engine?.state?.value?.bufferedMs ?: 0) },
                    chapters = session.item?.chapters.orEmpty(),
                    focused = false,
                    onSeek = { poke(); session.seekTo(it) },
                )
                Row(Modifier.fillMaxWidth()) {
                    TimeText { session.positionMs() }
                    Spacer(Modifier.weight(1f))
                    TimeText { -((session.durationMs() ?: 0L) - session.positionMs()).coerceAtLeast(0L) }
                }
            }
        }
    }
}

/** How long the touch controls stay after the last touch, while it plays. */
private const val HIDE_MS = 3_500L
