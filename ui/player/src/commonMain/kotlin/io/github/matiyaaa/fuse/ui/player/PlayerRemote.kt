package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.scaleIn
import io.github.matiyaaa.fuse.ui.fuseline.scaleOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInVertically
import io.github.matiyaaa.fuse.ui.fuseline.slideOutVertically
import io.github.matiyaaa.fuse.ui.fuseline.snap
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * What plays on the other screen, large, for a screen that only shows it: the backdrop, the poster,
 * what it is and how far in, the time left. Nothing on it needs a touch.
 */
@Composable
fun PlayerNowShowing(session: PlayerSession, modifier: Modifier = Modifier, where: String? = null) {
    val item = session.item ?: return
    val video = session.isVideo
    val coverArt = if (video) item.poster ?: item.artwork else item.artwork
    val state = session.engine?.state?.collectAsState()?.value ?: EngineState()
    Box(modifier.fillMaxSize().background(Color(0xFF07080B))) {
        Artwork(item.backdrop ?: coverArt, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f })
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
        BoxWithConstraints(Modifier.fillMaxSize().padding(Space.xl)) {
            val art = minOf(maxHeight * 0.62f / (if (video) 1.5f else 1f), maxWidth * 0.26f)
            val logoHeight = minOf(96.dp, maxHeight * 0.18f)
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                Box(
                    Modifier.width(art).aspectRatio(if (video) 2f / 3f else 1f)
                        .graphicsLayer { shadowElevation = 24.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true }
                        .background(Color.White.copy(alpha = 0.08f)),
                ) { Artwork(coverArt, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FuseIcon(if (state.playing) FuseIcons.Play else FuseIcons.Pause, size = 14.dp, tint = Fuse.colors.accent)
                        Spacer(Modifier.width(Space.xs))
                        FText((where ?: "Now playing").uppercase(), Fuse.type.overline, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                    }
                    // The logo where there is one, set at the left like a title; else the name.
                    if (item.logo != null) {
                        Artwork(item.logo, Modifier.fillMaxWidth(0.7f).height(logoHeight), contentScale = androidx.compose.ui.layout.ContentScale.Fit, focusX = 0f, backdrop = false)
                    } else {
                        FText(item.title, Fuse.type.display, color = Color.White, maxLines = 2)
                    }
                    (item.subtitle ?: listOfNotNull(item.artist, item.album).joinToString("  ·  ").ifEmpty { null })?.let {
                        FText(it, Fuse.type.body, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                    }
                    session.durationMs()?.takeIf { it > 0 }?.let { d ->
                        Spacer(Modifier.height(Space.xs))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer {
                                    val f = (session.positionMs().toFloat() / d).coerceIn(0f, 1f)
                                    scaleX = f
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                }.background(Fuse.colors.accent),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TimeText({ session.positionMs() })
                            Spacer(Modifier.weight(1f))
                            TimeText({ -(d - session.positionMs()).coerceAtLeast(0) })
                        }
                    }
                }
            }
        }
    }
}

private enum class RemoteList { AUDIO, SUBTITLES }

/**
 * A remote for what plays on the other screen. Over the film's own backdrop: where it plays (with a
 * live dot while it plays), its logo or its name, what it is; then a glass deck with the timeline,
 * the times, and the transport (previous, skip back, play or pause in a ring that fills as it
 * plays, skip forward, next); and under it the tools as one even bar (sound, subtitles, play here,
 * stop). Every control answers a touch with a soft press. With [inputEnabled] the controller drives
 * it too (A plays or pauses, Left and Right skip, LB and RB go to the previous and next, Y swaps the
 * screens, X stops, B goes back to browsing through [onBrowse], or stops through [onExit] without it).
 *
 * The backdrop reaches every edge; [topInset] keeps the content clear of a status line drawn over
 * it. [where] says where the picture is ("On the main screen").
 */
@Composable
fun PlayerRemote(
    session: PlayerSession,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = false,
    onExit: (() -> Unit)? = null,
    onSwap: (() -> Unit)? = null,
    onBrowse: (() -> Unit)? = null,
    where: String? = null,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Kept clear at the bottom for what is drawn over it (a pager's dots). */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
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
                NavAction.SEARCH -> { onSwap?.invoke(); NavResult.ACTIVATED }
                NavAction.CONTEXT -> { onExit?.invoke(); NavResult.ACTIVATED }
                NavAction.BACK -> { if (list != null) list = null else (onBrowse ?: onExit)?.invoke(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }

    val video = session.isVideo
    // A film or show is shown by its poster (an episode by its show's); music by its cover.
    val coverArt = if (video) item.poster ?: item.artwork else item.artwork
    val accent = Fuse.colors.accent
    // How far in, read every frame for the ring around Play (only its drawing follows it).
    val progress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(session) {
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            val d = session.durationMs() ?: 0L
            progress.floatValue = if (d > 0) (session.positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
        }
    }
    Box(modifier.fillMaxSize().background(REMOTE_INK)) {
        // The film's backdrop, softened, with a cinema's vignette: dark at the edges and the foot.
        Artwork(item.backdrop ?: coverArt, Modifier.fillMaxSize().blur(18.dp).graphicsLayer { alpha = 0.5f })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.88f)))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), radius = 1600f)))
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset).padding(horizontal = Space.l, vertical = Space.m)) {
            val wide = maxWidth > maxHeight * 1.15f
            val compact = maxHeight < 380.dp || (!wide && maxHeight < 560.dp)
            val tall = if (video) 1.5f else 1f
            // Upright, the poster takes what the heading, the deck and the tools leave, and steps
            // aside when that is too little to read as a poster.
            val stack = if (compact) 360.dp else 430.dp
            val art = if (wide) minOf(maxHeight * 0.74f / tall, maxWidth * 0.28f) else minOf(maxWidth * 0.36f, (maxHeight - stack) / tall)
            val showCover = wide || art >= 72.dp
            val cover = @Composable {
                Box(
                    Modifier.width(art).aspectRatio(if (video) 2f / 3f else 1f)
                        .graphicsLayer { shadowElevation = 28.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true }
                        .background(Color.White.copy(alpha = 0.06f)),
                ) {
                    Artwork(coverArt, Modifier.fillMaxSize())
                    // A thin light edge, as a print catches the light.
                    Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)))
                }
            }
            val heading = @Composable { centred: Boolean ->
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    WherePill(where ?: if (video) "On the other screen" else "Now playing", state.playing, video)
                    Spacer(Modifier.height(Space.xxs))
                    if (item.logo != null && video) {
                        Artwork(
                            item.logo, Modifier.fillMaxWidth(if (centred) 0.72f else 0.8f).height(if (compact) 52.dp else 72.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit, focusX = if (centred) 0.5f else 0f, backdrop = false,
                            fallback = { FText(item.title, if (compact) Fuse.type.titleSmall else Fuse.type.title, color = Color.White, maxLines = 2) },
                        )
                    } else {
                        FText(item.title, if (compact) Fuse.type.titleSmall else Fuse.type.title, color = Color.White, maxLines = 2,
                            align = if (centred) androidx.compose.ui.text.style.TextAlign.Center else null)
                    }
                    (item.subtitle ?: listOfNotNull(item.artist, item.album).joinToString("  ·  ").ifEmpty { null })?.let {
                        FText(it, Fuse.type.label, color = Color.White.copy(alpha = 0.72f), maxLines = 1,
                            align = if (centred) androidx.compose.ui.text.style.TextAlign.Center else null)
                    }
                }
            }
            val deck = @Composable {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DECK_RADIUS))
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(DECK_RADIUS))
                        .padding(horizontal = if (compact) Space.m else Space.l, vertical = if (compact) Space.s else Space.m),
                    verticalArrangement = Arrangement.spacedBy(if (compact) Space.xxs else Space.xs),
                ) {
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
                    val side = if (compact) 44.dp else 52.dp
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else Space.xs),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportButton(FuseIcons.SkipBack, "Previous", side, enabled = hasPrevious) { session.previous() }
                        TransportButton(FuseIcons.RotateCcw, "Back ${settings.seekSeconds} seconds", side, badge = settings.seekSeconds.toString()) { session.seekBy(-step) }
                        PlayRing(state.playing, if (compact) 64.dp else 76.dp, accent, { progress.floatValue }) { session.toggle() }
                        TransportButton(FuseIcons.RotateCw, "Forward ${settings.seekSeconds} seconds", side, badge = settings.seekSeconds.toString()) { session.seekBy(step) }
                        TransportButton(FuseIcons.SkipForward, "Next", side, enabled = hasNext) { session.next() }
                    }
                }
            }
            val tools = buildList {
                if ((src?.audioTracks?.size ?: 0) > 1) add(RemoteTool(FuseIcons.AudioLines, "Sound", list == RemoteList.AUDIO) { list = if (list == RemoteList.AUDIO) null else RemoteList.AUDIO })
                if (src?.subtitleTracks?.isNotEmpty() == true) {
                    add(RemoteTool(if (src.subtitle == null) FuseIcons.CaptionsOff else FuseIcons.Captions, "Subtitles", list == RemoteList.SUBTITLES) { list = if (list == RemoteList.SUBTITLES) null else RemoteList.SUBTITLES })
                }
                if (onSwap != null && video) add(RemoteTool(FuseIcons.Swap, "Play here", false, onSwap))
                onExit?.let { add(RemoteTool(FuseIcons.Square, "Stop", false, it)) }
            }
            val toolBar = @Composable { if (tools.isNotEmpty()) ToolBar(tools, compact) }
            if (wide) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    cover()
                    Column(Modifier.weight(1f).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m)) {
                        heading(false)
                        deck()
                        toolBar()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m, Alignment.CenterVertically),
                ) {
                    if (showCover) cover()
                    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m)) {
                        heading(true)
                        deck()
                        toolBar()
                    }
                }
            }
            // The tracks rise over the remote's lower part, chosen by touch.
            val shown = list
            Appear(shown != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
                var kept by remember { mutableStateOf(shown) }
                if (shown != null) kept = shown
                kept?.let { which ->
                    TrackList(session, which, Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * 0.62f)) { list = null }
                }
            }
        }
    }
}

/** Where the picture is, in a quiet pill; a live dot breathes in the accent while it plays. */
@Composable
private fun WherePill(text: String, playing: Boolean, video: Boolean) {
    val accent = Fuse.colors.accent
    val clock = rememberLoopClock("remote live")
    val pulse by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), "pulse")
    val still = Fuse.motion.reduced || !playing
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = Space.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (playing) {
                Box(Modifier.size(14.dp).graphicsLayer {
                    val p = if (still) 0f else pulse
                    scaleX = 0.5f + p
                    scaleY = 0.5f + p
                    alpha = if (still) 0f else (1f - p) * 0.6f
                }.clip(androidx.compose.foundation.shape.CircleShape).background(accent))
                Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent))
            } else {
                FuseIcon(FuseIcons.Pause, size = 12.dp, tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.width(Space.xs + 2.dp))
        FuseIcon(if (video) FuseIcons.MonitorPlay else FuseIcons.Music, size = 13.dp, tint = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.width(Space.xs))
        FText(text, Fuse.type.caption, color = Color.White.copy(alpha = 0.86f), maxLines = 1)
    }
}

/** How much a pressed control gives under a finger, sprung back with Fuseline. */
@Composable
private fun pressed(interaction: MutableInteractionSource): Float {
    val down by interaction.collectIsPressedAsState()
    val v by fuselineFloat(if (down) 1f else 0f, if (Fuse.motion.reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    return v
}

/** A transport control: an icon on nothing, a soft disc of light under it while pressed. */
@Composable
private fun TransportButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val p = pressed(interaction)
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f - 0.1f * p
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.28f
            }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White.copy(alpha = 0.04f + 0.14f * p))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = size * 0.46f, tint = Color.White)
        if (badge != null) {
            FText(badge, Fuse.type.caption.copy(fontSize = Fuse.type.caption.fontSize * 0.7f), color = Color.White, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/**
 * Play or pause: a white disc with a dark icon, in a ring that fills in the accent as the film
 * plays, so how far in shows at a glance.
 */
@Composable
private fun PlayRing(playing: Boolean, size: androidx.compose.ui.unit.Dp, accent: Color, progress: () -> Float, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val p = pressed(interaction)
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f - 0.07f * p
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2
                drawCircle(Color.White.copy(alpha = 0.16f), radius = this.size.minDimension / 2 - inset, style = Stroke(stroke))
                drawArc(
                    accent, startAngle = -90f, sweepAngle = 360f * progress(), useCenter = false,
                    topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            .padding(7.dp)
            .graphicsLayer { shadowElevation = 14.dp.toPx(); shape = androidx.compose.foundation.shape.CircleShape; clip = true }
            .background(Color.White)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Swap(playing, contentAlignment = Alignment.Center, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) }, label = "play") { on ->
            FuseIcon(if (on) FuseIcons.Pause else FuseIcons.Play, size = size * 0.36f, tint = Color(0xFF101114))
        }
    }
}

private class RemoteTool(val icon: ImageVector, val label: String, val on: Boolean, val onClick: () -> Unit)

/** The tools in one glass bar, each an icon over its name, the chosen one lit. */
@Composable
private fun ToolBar(tools: List<RemoteTool>, compact: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DECK_RADIUS))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(DECK_RADIUS))
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        for (t in tools) {
            val interaction = remember(t.label) { MutableInteractionSource() }
            val p = pressed(interaction)
            val lit by fuselineFloat(if (t.on) 1f else 0f, Fuse.motion.focusSpring(), label = "tool")
            Column(
                Modifier
                    .weight(1f)
                    .height(if (compact) 52.dp else 60.dp)
                    .graphicsLayer {
                        val s = 1f - 0.05f * p
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(DECK_RADIUS - Space.xs))
                    .background(lerp(Color.White.copy(alpha = 0.1f * p), Color.White, lit))
                    .clickable(interactionSource = interaction, indication = null, onClickLabel = t.label, onClick = t.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val fg = lerp(Color.White, Color(0xFF101114), lit)
                FuseIcon(t.icon, size = 20.dp, tint = fg)
                Spacer(Modifier.height(3.dp))
                FText(t.label, Fuse.type.caption, color = fg.copy(alpha = if (t.on) 1f else 0.82f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TrackList(session: PlayerSession, which: RemoteList, modifier: Modifier, onDone: () -> Unit) {
    val src = session.source ?: return
    val rows: List<Triple<String, String?, Boolean>> = when (which) {
        RemoteList.AUDIO -> src.audioTracks.map { Triple(it.label, it.codec?.uppercase(), src.audio == it.id) }
        RemoteList.SUBTITLES -> listOf(Triple("Off", null, src.subtitle == null)) + src.subtitleTracks.map { Triple(it.label, if (it.forced) "Forced" else it.codec?.uppercase(), src.subtitle == it.id) }
    }
    Column(
        modifier
            .graphicsLayer { shadowElevation = 30.dp.toPx(); shape = RoundedCornerShape(DECK_RADIUS); clip = true }
            .background(Color(0xF216181E))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(DECK_RADIUS)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.s, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            FText(if (which == RemoteList.AUDIO) "Sound" else "Subtitles", Fuse.type.bodyStrong, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            TransportButton(FuseIcons.Close, "Close", 40.dp, onClick = onDone)
        }
        LazyColumn(Modifier.padding(bottom = Space.s)) {
            itemsIndexed(rows) { i, (label, detail, on) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable {
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
}

/** The remote's room, under its backdrop. */
private val REMOTE_INK = Color(0xFF08090C)

/** Corner of the deck, the tool bar and the track list. */
private val DECK_RADIUS = 22.dp
