package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * Just the picture of what is playing, with Fuse's subtitles over exactly it: for the other
 * screen, when the menus (and the remote) are on the touch screen.
 */
@Composable
fun PlayerPicture(session: PlayerSession, modifier: Modifier = Modifier) {
    val engine = session.engine
    val state = engine?.state?.collectAsState()?.value ?: EngineState()
    val engineCues = engine?.cues?.collectAsState()?.value.orEmpty()
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth * state.pixelRatio / state.videoHeight else 16f / 9f
        val fitW = minOf(maxWidth, maxHeight * ratio)
        if (session.isVideo) {
            Box(Modifier.size(fitW, fitW / ratio)) {
                engine?.Video(Modifier.fillMaxSize())
                SubtitleLayer(engineCues, session.fileCues, { session.positionMs() }, session.settings)
            }
        } else {
            session.item?.let { item ->
                Box(Modifier.fillMaxSize()) {
                    Artwork(item.backdrop ?: item.artwork, Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.35f })
                    Box(Modifier.align(Alignment.Center).fillMaxSize(0.62f).aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(16.dp))) {
                        Artwork(item.artwork, Modifier.fillMaxSize())
                    }
                }
            }
        }
        val waiting = session.resolving || state.status == EngineStatus.LOADING || state.status == EngineStatus.BUFFERING
        if (waiting && session.error == null) Spinner(size = 44.dp, color = Color.White)
    }
}

private enum class RemoteList { AUDIO, SUBTITLES }

/**
 * A remote for what plays on the other screen: its art, what it is, the time and the timeline,
 * play and pause, skips, previous and next, and the sound and subtitle tracks. Made for touch;
 * with [inputEnabled] the controller drives it too (A plays or pauses, Left and Right skip, LB and
 * RB go to the previous and next, B leaves through [onExit]).
 */
@Composable
fun PlayerRemote(session: PlayerSession, modifier: Modifier = Modifier, inputEnabled: Boolean = false, onExit: (() -> Unit)? = null) {
    val item = session.item ?: return
    val state = session.engine?.state?.collectAsState()?.value ?: EngineState()
    val settings = session.settings
    val src = session.source
    var list by remember { mutableStateOf<RemoteList?>(null) }
    val step = settings.seekSeconds * 1000L
    val hasPrevious = session.queue.size > 1 && session.queueIndex > 0 || item.season != null
    val hasNext = session.upNext != null

    if (inputEnabled) {
        InputLayer(priority = LayerPriority.SCREEN + 5, modal = true, repeats = setOf(NavAction.LEFT, NavAction.RIGHT)) { e ->
            when (e.action) {
                NavAction.SELECT -> { session.toggle(); NavResult.ACTIVATED }
                NavAction.LEFT -> { session.seekBy(-step); NavResult.MOVED }
                NavAction.RIGHT -> { session.seekBy(step); NavResult.MOVED }
                NavAction.PREVIOUS_SECTION -> { session.previous(); NavResult.ACTIVATED }
                NavAction.NEXT_SECTION -> { session.next(); NavResult.ACTIVATED }
                NavAction.BACK -> { if (list != null) list = null else onExit?.invoke(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF0B0C10))) {
        Artwork(item.backdrop ?: item.artwork, Modifier.fillMaxSize().blur(56.dp).graphicsLayer { alpha = 0.3f })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.75f)))))
        BoxWithConstraints(Modifier.fillMaxSize().padding(Space.l)) {
            val wide = maxWidth > maxHeight * 1.25f
            val art = if (wide) minOf(maxHeight * 0.62f, maxWidth * 0.32f) else minOf(maxWidth * 0.42f, maxHeight * 0.3f)
            val cover = @Composable {
                Box(Modifier.width(art).aspectRatio(if (session.isVideo) 2f / 3f else 1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
                    Artwork(item.artwork, Modifier.fillMaxSize())
                }
            }
            val controls = @Composable {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    FText(if (session.isVideo) "Playing on the other screen" else "Now playing", Fuse.type.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                    FText(item.title, Fuse.type.titleSmall, color = Color.White, maxLines = 2)
                    (item.subtitle ?: listOfNotNull(item.artist, item.album).joinToString("  ·  ").ifEmpty { null })?.let {
                        FText(it, Fuse.type.label, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                    }
                    Spacer(Modifier.height(Space.xs))
                    PlayerTimeline(
                        position = { session.positionMs() },
                        durationMs = session.durationMs(),
                        bufferedMs = { (src?.offsetMs ?: 0) + (session.engine?.state?.value?.bufferedMs ?: 0) },
                        chapters = item.chapters,
                        focused = false,
                        onSeek = { session.seekTo(it) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeText({ session.positionMs() })
                        Spacer(Modifier.weight(1f))
                        session.durationMs()?.let { d -> TimeText({ -(d - session.positionMs()).coerceAtLeast(0) }) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        if (hasPrevious) RoundButton(FuseIcons.SkipBack, "Previous", selected = false, size = 48.dp) { session.previous() }
                        RoundButton(FuseIcons.RotateCcw, "Back ${settings.seekSeconds} seconds", selected = false, size = 48.dp, badge = settings.seekSeconds.toString()) { session.seekBy(-step) }
                        RoundButton(if (state.playing) FuseIcons.Pause else FuseIcons.Play, if (state.playing) "Pause" else "Play", selected = false, size = 64.dp, filled = true) { session.toggle() }
                        RoundButton(FuseIcons.RotateCw, "Forward ${settings.seekSeconds} seconds", selected = false, size = 48.dp, badge = settings.seekSeconds.toString()) { session.seekBy(step) }
                        if (hasNext) RoundButton(FuseIcons.SkipForward, "Next", selected = false, size = 48.dp) { session.next() }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally)) {
                        if ((src?.audioTracks?.size ?: 0) > 1) RemoteChip(FuseIcons.AudioLines, "Audio", list == RemoteList.AUDIO) { list = if (list == RemoteList.AUDIO) null else RemoteList.AUDIO }
                        if (src?.subtitleTracks?.isNotEmpty() == true) RemoteChip(if (src.subtitle == null) FuseIcons.CaptionsOff else FuseIcons.Captions, "Subtitles", list == RemoteList.SUBTITLES) { list = if (list == RemoteList.SUBTITLES) null else RemoteList.SUBTITLES }
                        onExit?.let { RemoteChip(FuseIcons.Close, "Stop", false, onClick = it) }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    cover()
                    Box(Modifier.weight(1f)) { controls() }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterVertically)) {
                    cover()
                    Box(Modifier.widthIn(max = 520.dp)) { controls() }
                }
            }
            // The tracks, over the remote's lower part, chosen by touch.
            list?.let { which ->
                TrackList(session, which, Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight * 0.6f)) { list = null }
            }
        }
    }
}

@Composable
private fun RemoteChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (on) Color(0xFF101114) else Color.White
        FuseIcon(icon, size = 18.dp, tint = fg)
        Spacer(Modifier.width(Space.s))
        FText(label, Fuse.type.label, color = fg, maxLines = 1)
    }
}

@Composable
private fun TrackList(session: PlayerSession, which: RemoteList, modifier: Modifier, onDone: () -> Unit) {
    val src = session.source ?: return
    val rows: List<Triple<String, String?, Boolean>> = when (which) {
        RemoteList.AUDIO -> src.audioTracks.map { Triple(it.label, it.codec?.uppercase(), src.audio == it.id) }
        RemoteList.SUBTITLES -> listOf(Triple("Off", null, src.subtitle == null)) + src.subtitleTracks.map { Triple(it.label, if (it.forced) "Forced" else it.codec?.uppercase(), src.subtitle == it.id) }
    }
    LazyColumn(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xF0181A20)).padding(vertical = Space.s),
    ) {
        itemsIndexed(rows) { i, (label, detail, on) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    when (which) {
                        RemoteList.AUDIO -> session.chooseAudio(src.audioTracks[i])
                        RemoteList.SUBTITLES -> session.chooseSubtitle(if (i == 0) null else src.subtitleTracks[i - 1])
                    }
                    onDone()
                }.padding(horizontal = Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    FText(label, Fuse.type.bodyStrong, color = Color.White, maxLines = 1)
                    if (detail != null) FText(detail, Fuse.type.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                }
                if (on) FuseIcon(FuseIcons.Check, size = 20.dp, tint = Fuse.colors.accent)
            }
        }
    }
}
