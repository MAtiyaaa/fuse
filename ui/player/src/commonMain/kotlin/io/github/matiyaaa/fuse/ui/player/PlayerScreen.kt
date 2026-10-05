package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.playback.MediaKind
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuHeader
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import kotlinx.coroutines.delay

/** The player's sheets. */
private enum class PlayerSheet { AUDIO, SUBTITLES, SETTINGS, QUEUE }

/**
 * Fuse Player, full screen: the picture with Fuse's subtitles over it, and controls that come and
 * go. Controller first: A plays and pauses (or presses the control the focus is on), Left and
 * Right seek (held, further and faster), Up and Down show the controls and move between the
 * timeline and the buttons, LB and RB go to the previous and next episode (or chapter), LT and RT
 * skip, X opens the settings, B hides the controls and then leaves. Touch: a tap shows or hides
 * them, a double tap on either side skips, the timeline drags. A mouse shows them as it moves.
 *
 * [onSettings] hears changes made here (speed, subtitle size), so the app can keep them.
 * [fullscreen] toggles the window where there is one. [onSwap] moves the picture to the other
 * screen on a device with two, leaving this one as its remote.
 */
@Composable
fun PlayerScreen(
    session: PlayerSession,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
    onSettings: (PlayerSettings) -> Unit = {},
    fullscreen: (() -> Unit)? = null,
    onSwap: (() -> Unit)? = null,
) {
    val engine = session.engine
    val state = engine?.state?.collectAsState()?.value ?: EngineState()
    val engineCues = engine?.cues?.collectAsState()?.value.orEmpty()
    val item = session.item
    var visible by remember { mutableStateOf(true) }
    var row by remember { mutableIntStateOf(BUTTONS) }
    var focus by remember { mutableIntStateOf(-1) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var touched by remember { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    // How tall the controls at the bottom are, so subtitles can rise above them.
    var controlsHeight by remember { mutableIntStateOf(0) }
    val settings = session.settings
    fun poke() {
        visible = true
        touched++
    }
    fun change(s: PlayerSettings) {
        session.settings = s
        onSettings(s)
    }

    val playing = state.playing
    val waiting = session.resolving || state.status == EngineStatus.LOADING || state.status == EngineStatus.BUFFERING
    // Controls fade once nothing has happened for a while, unless paused, waiting or a sheet is up.
    LaunchedEffect(visible, touched, playing, sheet, waiting) {
        // Music keeps its controls: there is no picture for them to cover.
        if (visible && playing && sheet == null && !waiting && session.error == null && session.isVideo) {
            delay(settings.controlsTimeoutMs)
            visible = false
            row = BUTTONS
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(FLASH_MS)
            flash = null
        }
    }

    val duration = session.durationMs()
    val hasPrevious = session.queue.size > 1 && session.queueIndex > 0 || item?.season != null
    val hasNext = session.upNext != null
    val buttons = buildList {
        if (hasPrevious || item?.chapters?.isNotEmpty() == true) add(PlayerButton("prev", FuseIcons.SkipBack, "Previous") { session.previous() })
        add(PlayerButton("back", FuseIcons.RotateCcw, "Back ${settings.seekSeconds} seconds", badge = settings.seekSeconds.toString()) { session.seekBy(-settings.seekSeconds * 1000L) })
        add(PlayerButton("play", if (playing) FuseIcons.Pause else FuseIcons.Play, if (playing) "Pause" else "Play", big = true) { session.toggle() })
        add(PlayerButton("fwd", FuseIcons.RotateCw, "Forward ${settings.seekSeconds} seconds", badge = settings.seekSeconds.toString()) { session.seekBy(settings.seekSeconds * 1000L) })
        if (hasNext || item?.chapters?.isNotEmpty() == true) add(PlayerButton("next", FuseIcons.SkipForward, "Next") { session.next() })
    }
    val tools = buildList {
        val src = session.source
        if (!session.isVideo) {
            if (session.queue.size > 1) add(PlayerButton("queue", FuseIcons.ListMusic, "Queue") { sheet = PlayerSheet.QUEUE })
            add(PlayerButton(
                "repeat",
                if (session.repeat == RepeatMode.ONE) FuseIcons.Repeat1 else FuseIcons.Repeat,
                when (session.repeat) { RepeatMode.OFF -> "Repeat"; RepeatMode.ALL -> "Repeating all"; RepeatMode.ONE -> "Repeating this song" },
                active = session.repeat != RepeatMode.OFF,
            ) { session.repeat = RepeatMode.entries[(session.repeat.ordinal + 1) % RepeatMode.entries.size] })
        }
        if ((src?.audioTracks?.size ?: 0) > 1) add(PlayerButton("audio", FuseIcons.AudioLines, "Audio") { sheet = PlayerSheet.AUDIO })
        if (src?.subtitleTracks?.isNotEmpty() == true) add(PlayerButton("subs", if (src.subtitle == null) FuseIcons.CaptionsOff else FuseIcons.Captions, "Subtitles") { sheet = PlayerSheet.SUBTITLES })
        add(PlayerButton("settings", FuseIcons.Settings2, "Settings") { sheet = PlayerSheet.SETTINGS })
        if (session.isVideo) onSwap?.let { f -> add(PlayerButton("swap", FuseIcons.Swap, "Play on the other screen") { f() }) }
        fullscreen?.let { f -> add(PlayerButton("full", FuseIcons.Maximize, "Full screen") { f() }) }
    }
    val all = buttons + tools
    val playIndex = all.indexOfFirst { it.id == "play" }
    if (focus !in all.indices) focus = playIndex

    fun seekStep(repeat: Int): Long = when {
        repeat < 4 -> settings.seekSeconds * 1000L
        repeat < 12 -> 30_000L
        else -> 60_000L
    }
    fun chapterJump(forward: Boolean) {
        val chapters = item?.chapters.orEmpty()
        val now = session.positionMs()
        val target = if (forward) chapters.firstOrNull { it.startMs > now + 1_000 } else chapters.lastOrNull { it.startMs < now - 3_000 }
        target?.let { session.seekTo(it.startMs) }
    }

    if (inputEnabled && sheet == null) {
        InputLayer(priority = LayerPriority.SCREEN + 5, modal = true, repeats = setOf(NavAction.LEFT, NavAction.RIGHT)) { e ->
            val shown = visible
            poke()
            when (e.action) {
                NavAction.BACK -> {
                    if (shown && playing) visible = false else onExit()
                    NavResult.CONSUMED
                }
                NavAction.SELECT -> {
                    if (shown && row == BUTTONS) all.getOrNull(focus)?.onClick?.invoke() else session.toggle()
                    NavResult.ACTIVATED
                }
                NavAction.LEFT, NavAction.RIGHT -> {
                    val forward = e.action == NavAction.RIGHT
                    if (shown && row == BUTTONS) {
                        val next = focus + if (forward) 1 else -1
                        if (next in all.indices) focus = next
                        NavResult.MOVED
                    } else {
                        val step = seekStep(e.repeat)
                        session.seekBy(if (forward) step else -step)
                        flash = Flash(if (forward) "+${step / 1000} s" else "-${step / 1000} s", forward)
                        if (!shown) row = TIMELINE
                        NavResult.MOVED
                    }
                }
                NavAction.UP -> {
                    if (shown) row = TIMELINE
                    NavResult.MOVED
                }
                NavAction.DOWN -> {
                    if (shown) row = BUTTONS
                    NavResult.MOVED
                }
                NavAction.NEXT_SECTION -> {
                    if (hasNext) session.next() else chapterJump(true)
                    NavResult.ACTIVATED
                }
                NavAction.PREVIOUS_SECTION -> {
                    if (hasPrevious) session.previous() else chapterJump(false)
                    NavResult.ACTIVATED
                }
                NavAction.PAGE_UP -> {
                    session.seekBy(-settings.seekSeconds * 1000L)
                    flash = Flash("-${settings.seekSeconds} s", false)
                    NavResult.ACTIVATED
                }
                NavAction.PAGE_DOWN -> {
                    session.seekBy(settings.seekSeconds * 1000L)
                    flash = Flash("+${settings.seekSeconds} s", true)
                    NavResult.ACTIVATED
                }
                NavAction.CONTEXT -> {
                    sheet = PlayerSheet.SETTINGS
                    NavResult.ACTIVATED
                }
                else -> NavResult.CONSUMED
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // A mouse moving over the picture brings the controls back.
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Move) poke()
                    }
                }
            },
    ) {
        val narrow = maxWidth < NARROW
        val short = maxHeight < SHORT
        // The picture, as large as fits, with its own shape; subtitles over exactly it.
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth * state.pixelRatio / state.videoHeight else 16f / 9f
        val fitW = minOf(maxWidth, maxHeight * ratio)
        val fitH = fitW / ratio
        val gap = (maxHeight - fitH) / 2
        if (session.isVideo) {
            Box(Modifier.size(fitW, fitH).align(Alignment.Center)) {
                engine?.Video(Modifier.fillMaxSize())
                // While the controls show, subtitles rise to sit just above them.
                val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { gap.toPx() }
                val fitPx = with(androidx.compose.ui.platform.LocalDensity.current) { fitH.toPx() }
                val above = if (visible && controlsHeight > 0) ((controlsHeight - gapPx) / fitPx - SUBTITLE_MARGIN).coerceAtLeast(0f) else 0f
                SubtitleLayer(engineCues, session.fileCues, { session.positionMs() }, settings.copy(subtitleLift = maxOf(settings.subtitleLift, above)))
            }
        } else {
            NowPlaying(session, narrow, short, if (visible) CONTROLS_HEIGHT else 0.dp)
        }

        // Taps: one shows or hides the controls; two on either side skip.
        Box(
            Modifier.fillMaxSize().pointerInput(settings.seekSeconds) {
                detectTapGestures(
                    onTap = {
                        if (visible) visible = false else poke()
                    },
                    onDoubleTap = { o ->
                        val forward = o.x > size.width / 2
                        session.seekBy(if (forward) settings.seekSeconds * 1000L else -settings.seekSeconds * 1000L)
                        flash = Flash(if (forward) "+${settings.seekSeconds} s" else "-${settings.seekSeconds} s", forward)
                    },
                )
            },
        )

        if (waiting && session.error == null) {
            Spinner(Modifier.align(Alignment.Center), size = 44.dp, color = Color.White)
        }

        flash?.let { f ->
            SeekFlash(f, Modifier.align(if (f.forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = maxWidth * 0.12f))
        }

        Appear(visible && session.error == null && !session.finished, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                session = session,
                state = state,
                duration = duration,
                buttons = buttons,
                tools = tools,
                focus = focus,
                row = row,
                narrow = narrow,
                short = short,
                onFocusButton = { focus = it; row = BUTTONS },
                onBottomHeight = { controlsHeight = it },
                onSeek = { ms -> session.seekTo(ms); poke() },
                onExit = onExit,
            )
        }

        UpNextCard(session, visible, Modifier.align(Alignment.BottomEnd).padding(end = Space.xl, bottom = if (visible) CONTROLS_HEIGHT else Space.xl))

        session.error?.let { message ->
            Problem(message, onRetry = { session.retry() }, onExit = onExit, Modifier.align(Alignment.Center))
        }
        if (session.finished) {
            Finished(session, onExit, Modifier.align(Alignment.Center))
        }

        sheet?.let { s ->
            PlayerSheetPanel(s, session, inputEnabled, onSettings = ::change, onClose = { sheet = null; poke() }, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

private const val TIMELINE = 0
private const val BUTTONS = 1

private class PlayerButton(val id: String, val icon: ImageVector, val label: String, val big: Boolean = false, val badge: String? = null, val active: Boolean = false, val onClick: () -> Unit)

private class Flash(val text: String, val forward: Boolean)

@Composable
private fun Controls(
    session: PlayerSession,
    state: EngineState,
    duration: Long?,
    buttons: List<PlayerButton>,
    tools: List<PlayerButton>,
    focus: Int,
    row: Int,
    narrow: Boolean,
    short: Boolean,
    onFocusButton: (Int) -> Unit,
    onBottomHeight: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onExit: () -> Unit,
) {
    val item = session.item
    val pad = if (narrow) Space.l else Space.xxl
    Box(Modifier.fillMaxSize()) {
        // Shade at the top and bottom so white controls read over any picture.
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(if (short) 170.dp else 240.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))))

        // Title, what it is, and how it plays.
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = pad, vertical = if (short) Space.m else Space.xl), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(FuseIcons.ArrowLeft, "Back", selected = false, size = 44.dp, onClick = onExit)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                // Music names the song below; the top says where it comes from.
                val title = if (session.isVideo) item?.title.orEmpty() else item?.album ?: "Music"
                FText(title, (if (narrow) Fuse.type.titleSmall else Fuse.type.title), color = Color.White, maxLines = 1)
                val sub = if (session.isVideo) item?.subtitle else if (session.queue.size > 1) "Song ${session.queueIndex + 1} of ${session.queue.size}" else item?.artist
                if (sub != null) FText(sub, Fuse.type.label, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
            }
            if (!narrow) {
                session.source?.let { src ->
                    Spacer(Modifier.width(Space.m))
                    Pill(src.method.label)
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .androidxOnSize { onBottomHeight(it) }
                .padding(horizontal = pad, vertical = if (short) Space.m else Space.xl),
        ) {
            // Narrow, the tools and how it plays get a row of their own, so the title keeps the top.
            if (narrow) {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    session.source?.let { Pill(it.method.label) }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for ((i, b) in tools.withIndex()) {
                            RoundButton(b.icon, b.label, selected = row == BUTTONS && focus == buttons.size + i, size = 40.dp, active = b.active) {
                                onFocusButton(buttons.size + i)
                                b.onClick()
                            }
                        }
                    }
                }
            }
            PlayerTimeline(
                position = { session.positionMs() },
                durationMs = duration,
                bufferedMs = { (session.source?.offsetMs ?: 0) + (session.engine?.state?.value?.bufferedMs ?: 0) },
                chapters = item?.chapters.orEmpty(),
                focused = row == TIMELINE,
                onSeek = onSeek,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeText({ session.positionMs() })
                Spacer(Modifier.weight(1f))
                if (duration != null) TimeText({ -(duration - session.positionMs()).coerceAtLeast(0) })
            }
            Spacer(Modifier.height(if (short) Space.xs else Space.m))
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (narrow) Space.s else Space.l), verticalAlignment = Alignment.CenterVertically) {
                    for ((i, b) in buttons.withIndex()) {
                        RoundButton(b.icon, b.label, selected = row == BUTTONS && focus == i, size = if (b.big) 64.dp else 48.dp, badge = b.badge, filled = b.big) {
                            onFocusButton(i)
                            b.onClick()
                        }
                    }
                }
                if (!narrow) {
                    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for ((i, b) in tools.withIndex()) {
                            RoundButton(b.icon, b.label, selected = row == BUTTONS && focus == buttons.size + i, size = 44.dp, active = b.active) {
                                onFocusButton(buttons.size + i)
                                b.onClick()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A time that follows the clock: elapsed, or (negative) what is left. */
@Composable
internal fun TimeText(ms: () -> Long) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val v = ms()
            text = if (v < 0) "-" + clock(-v) else clock(v)
            delay(200)
        }
    }
    FText(text, Fuse.type.label.tabular(), color = Color.White.copy(alpha = 0.86f), maxLines = 1)
}

/**
 * A round control on glass. Selected (the controller's focus) it turns white with a dark icon and
 * lifts, like Fuse's quiet buttons; [filled] is the main one (play), always solid. [badge] puts a
 * small number in the icon (the seconds a skip button skips).
 */
@Composable
internal fun RoundButton(icon: ImageVector, label: String, selected: Boolean, size: Dp, badge: String? = null, filled: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val lift by fuselineFloat(if (selected) 1f else 0f, Fuse.motion.focusSpring(), label = "pb")
    val solid = selected || filled
    // A switched-on tool (repeat) reads brighter than the rest without looking selected.
    val bg = if (solid) Color.White else Color.White.copy(alpha = if (active) 0.32f else 0.12f)
    val fg = if (solid) Color(0xFF101114) else Color.White
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + 0.08f * lift
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (selected) drawCircle(Fuse.colorsAccentRing, radius = this.size.minDimension / 2 + 3.dp.toPx() * lift, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = size * 0.44f, tint = fg)
        if (badge != null) {
            FText(badge, Fuse.type.caption.copy(fontSize = Fuse.type.caption.fontSize * 0.72f), color = fg, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** The accent ring around a focused control; white on dark video reads in any theme. */
private val Fuse.colorsAccentRing: Color get() = Color.White.copy(alpha = 0.55f)

@Composable
private fun Pill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = Space.m, vertical = Space.xs)) {
        FText(text, Fuse.type.caption, color = Color.White, maxLines = 1)
    }
}

/** "+10 s" where the thumb would be, after a skip. */
@Composable
private fun SeekFlash(f: Flash, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(if (f.forward) FuseIcons.RotateCw else FuseIcons.RotateCcw, size = 18.dp, tint = Color.White)
        Spacer(Modifier.width(Space.s))
        FText(f.text, Fuse.type.bodyStrong.tabular(), color = Color.White, maxLines = 1)
    }
}

/** The next episode, in the last moments: what it is, a bar running down to it, and RB to go now. */
@Composable
private fun UpNextCard(session: PlayerSession, controls: Boolean, modifier: Modifier) {
    val next = session.upNext ?: return
    if (!session.isVideo) return
    var left by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(next.id) {
        while (true) {
            val d = session.durationMs()
            left = if (d == null) Long.MAX_VALUE else d - session.positionMs()
            delay(250)
        }
    }
    val shown = left in 0..UP_NEXT_MS && session.error == null
    Appear(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Panel(Modifier.width(320.dp).clickable { session.next() }) {
            Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) {
                    Artwork(next.artwork ?: next.backdrop, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FText(if (session.settings.autoplayNext) "Up next in ${(left / 1000).coerceAtLeast(0)} s" else "Up next", Fuse.type.caption, color = Fuse.colors.accent, maxLines = 1)
                    FText(next.title, Fuse.type.bodyStrong, maxLines = 1)
                    next.subtitle?.let { FText(it, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ButtonGlyph(HintButton.NEXT, size = 18.dp)
                        Spacer(Modifier.width(Space.xs))
                        FText("Play now", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                    }
                }
            }
            // The time left before it starts, as a bar along the bottom.
            val fraction = (left.toFloat() / UP_NEXT_MS).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(1f - fraction).height(3.dp).background(Fuse.colors.accent))
        }
    }
}

/** Music: the cover, large, with the title under it, over a blur of itself. */
/**
 * Music: the album art large, with the song, the artist and album, and what plays next. Wide, the
 * art sits beside the words; narrow, above them. The room behind is the art again, blurred.
 */
@Composable
private fun NowPlaying(session: PlayerSession, narrow: Boolean, short: Boolean, controls: Dp) {
    val item = session.item ?: return
    val upcoming = session.queue.drop(session.queueIndex + 1).take(if (short) 2 else 4)
    Box(Modifier.fillMaxSize()) {
        Artwork(item.backdrop ?: item.artwork, Modifier.fillMaxSize().blur(56.dp).graphicsLayer { alpha = 0.32f })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.7f)))))
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = if (short) 72.dp else 104.dp, bottom = maxOf(controls, Space.xl)).padding(horizontal = if (narrow) Space.l else Space.xxl)) {
            val side = !narrow && maxWidth > maxHeight * 1.2f
            val art = if (side) minOf(maxHeight, maxWidth * 0.4f, 440.dp) else minOf(maxWidth * 0.7f, maxHeight * 0.55f, 320.dp)
            val cover = @Composable {
                Box(Modifier.size(art).clip(RoundedCornerShape(art * 0.04f)).background(Color.White.copy(alpha = 0.08f))) {
                    Artwork(item.artwork, Modifier.fillMaxSize())
                }
            }
            val words = @Composable { align: Alignment.Horizontal ->
                Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FText("Now playing", Fuse.type.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                    FText(item.title, if (side) Fuse.type.display else Fuse.type.title, color = Color.White, maxLines = 2, align = if (side) TextAlign.Start else TextAlign.Center)
                    listOfNotNull(item.artist, item.album).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                        FText(it, Fuse.type.body, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                    }
                    if (side && upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(Space.l))
                        FText("Up next", Fuse.type.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                        for ((i, q) in upcoming.withIndex()) {
                            Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                                FText("${session.queueIndex + i + 2}", Fuse.type.label.tabular(), color = Color.White.copy(alpha = 0.45f), maxLines = 1, modifier = Modifier.width(28.dp))
                                Column(Modifier.weight(1f, fill = false)) {
                                    FText(q.title, Fuse.type.label, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                                    q.artist?.takeIf { it != item.artist }?.let { FText(it, Fuse.type.caption, color = Color.White.copy(alpha = 0.55f), maxLines = 1) }
                                }
                                q.durationMs?.let {
                                    Spacer(Modifier.width(Space.m))
                                    FText(clock(it), Fuse.type.caption.tabular(), color = Color.White.copy(alpha = 0.45f), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            if (side) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                    cover()
                    Box(Modifier.widthIn(max = 520.dp)) { words(Alignment.Start) }
                }
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    cover()
                    words(Alignment.CenterHorizontally)
                }
            }
        }
    }
}

@Composable
private fun Problem(message: String, onRetry: () -> Unit, onExit: () -> Unit, modifier: Modifier) {
    var index by remember { mutableIntStateOf(0) }
    InputLayer(priority = LayerPriority.SCREEN + 6, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT -> { index = 0; NavResult.MOVED }
            NavAction.RIGHT -> { index = 1; NavResult.MOVED }
            NavAction.SELECT -> { if (index == 0) onRetry() else onExit(); NavResult.ACTIVATED }
            NavAction.BACK -> { onExit(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }
    Panel(modifier.widthIn(max = 460.dp).padding(Space.l)) {
        Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Fuse.colors.danger.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Warning, size = 22.dp, tint = Fuse.colors.danger)
            }
            FText("This can't play right now", Fuse.type.titleSmall, maxLines = 2, align = TextAlign.Center)
            FText(message.replaceFirstChar { it.uppercase() }.trimEnd('.') + ".", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 4, align = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                FuseButton("Try again", selected = index == 0, onClick = onRetry, icon = FuseIcons.RotateCcw, kind = ButtonKind.PRIMARY)
                FuseButton("Back", selected = index == 1, onClick = onExit)
            }
        }
    }
}

@Composable
private fun Finished(session: PlayerSession, onExit: () -> Unit, modifier: Modifier) {
    var index by remember { mutableIntStateOf(0) }
    InputLayer(priority = LayerPriority.SCREEN + 6, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT -> { index = 0; NavResult.MOVED }
            NavAction.RIGHT -> { index = 1; NavResult.MOVED }
            NavAction.SELECT -> { if (index == 0) session.play() else onExit(); NavResult.ACTIVATED }
            NavAction.BACK -> { onExit(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }
    val item = session.item
    Column(modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        FText("Finished", Fuse.type.caption, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
        FText(item?.title.orEmpty(), Fuse.type.display, color = Color.White, maxLines = 2, align = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            FuseButton("Watch again", selected = index == 0, onClick = { session.play() }, icon = FuseIcons.RotateCcw, kind = ButtonKind.PRIMARY)
            FuseButton("Back", selected = index == 1, onClick = onExit)
        }
    }
}

/** Audio, subtitles or settings, as a side sheet over the picture. */
@Composable
private fun PlayerSheetPanel(sheet: PlayerSheet, session: PlayerSession, inputEnabled: Boolean, onSettings: (PlayerSettings) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val sel = remember(sheet) { LinearSelection(if (sheet == PlayerSheet.QUEUE) session.queueIndex else 0) }
    val src = session.source
    val s = session.settings
    val rows: List<MenuAction> = when (sheet) {
        PlayerSheet.AUDIO -> src?.audioTracks.orEmpty().map { t ->
            MenuAction("a${t.id}", t.label, detail = listOfNotNull(t.codec?.uppercase(), t.channels?.let { channelsText(it) }).joinToString("  ·  ").ifEmpty { null }, trailing = Trailing.Check(src?.audio == t.id)) {
                session.chooseAudio(t)
            }
        }
        PlayerSheet.SUBTITLES -> listOf(MenuAction("off", "Off", trailing = Trailing.Check(src?.subtitle == null)) { session.chooseSubtitle(null) }) +
            src?.subtitleTracks.orEmpty().map { t ->
                val note = listOfNotNull(
                    if (t.forced) "Forced" else null,
                    t.codec?.uppercase(),
                    if (t.delivery == SubtitleDelivery.BurnIn) "Drawn into the picture by the server" else null,
                ).joinToString("  ·  ").ifEmpty { null }
                MenuAction("s${t.id}", t.label, detail = note, trailing = Trailing.Check(src?.subtitle == t.id)) { session.chooseSubtitle(t) }
            }
        PlayerSheet.QUEUE -> session.queue.mapIndexed { i, q ->
            MenuAction("q$i.${q.id}", q.title, detail = listOfNotNull(q.artist, q.durationMs?.let { clock(it) }).joinToString("  ·  ").ifEmpty { null }, trailing = if (i == session.queueIndex) Trailing.Badge("Playing") else Trailing.None) {
                if (i != session.queueIndex) session.playQueueIndex(i)
            }
        }
        PlayerSheet.SETTINGS -> buildList {
            add(MenuAction("speed", "Speed", FuseIcons.Gauge, trailing = Trailing.Value(speedText(session.speed))) {
                session.changeSpeed(next(SPEEDS, session.speed))
                if (s.rememberSpeed) onSettings(s)
            })
            add(MenuAction("size", "Subtitle size", FuseIcons.TextSize, trailing = Trailing.Value("${(s.subtitleScale * 100).toInt()}%")) {
                onSettings(s.copy(subtitleScale = next(SUB_SIZES, s.subtitleScale)))
            })
            add(MenuAction("lift", "Subtitle position", FuseIcons.MoveVertical, trailing = Trailing.Value(liftText(s.subtitleLift))) {
                onSettings(s.copy(subtitleLift = next(SUB_LIFTS, s.subtitleLift)))
            })
            add(MenuAction("delay", "Subtitle timing", FuseIcons.Timer, detail = "Later if they come early, earlier if they come late", trailing = Trailing.Value(delayText(s.subtitleDelayMs))) {
                val i = SUB_DELAYS.indexOf(s.subtitleDelayMs).let { if (it < 0) SUB_DELAYS.indexOf(0L) else it }
                onSettings(s.copy(subtitleDelayMs = SUB_DELAYS[(i + 1) % SUB_DELAYS.size]))
            })
            add(MenuAction("bg", "Subtitle background", FuseIcons.Square, trailing = Trailing.Switch(s.subtitleBackground)) {
                onSettings(s.copy(subtitleBackground = !s.subtitleBackground))
            })
            src?.let { p ->
                val st = session.engine?.state?.value
                add(MenuAction("method", "Playing as", FuseIcons.Info, section = "About this stream", detail = p.transcodeReason ?: p.description, trailing = Trailing.Value(p.method.label)))
                st?.decoder?.let { d -> add(MenuAction("decoder", "Decoder", FuseIcons.Chip, section = "About this stream", trailing = Trailing.Value(d + if (st.hardware) "" else ""))) }
                if ((st?.videoWidth ?: 0) > 0) add(MenuAction("picture", "Picture", FuseIcons.Monitor, section = "About this stream", trailing = Trailing.Value("${st!!.videoWidth} x ${st.videoHeight}")))
            }
        }
    }
    sel.clamp(rows.size)
    if (inputEnabled) {
        InputLayer(priority = LayerPriority.SCREEN + 7, modal = true) { e ->
            when (e.action) {
                NavAction.BACK, NavAction.CONTEXT -> { onClose(); NavResult.CONSUMED }
                else -> handleMenuAction(e, rows, sel)
            }
        }
    }
    val title = when (sheet) {
        PlayerSheet.AUDIO -> "Audio"
        PlayerSheet.SUBTITLES -> "Subtitles"
        PlayerSheet.SETTINGS -> "Settings"
        PlayerSheet.QUEUE -> "Queue"
    }
    Panel(modifier.fillMaxHeight().width(380.dp).padding(Space.l)) {
        Column(Modifier.padding(Space.l)) {
            MenuHeader(title, icon = when (sheet) {
                PlayerSheet.AUDIO -> FuseIcons.AudioLines
                PlayerSheet.SUBTITLES -> FuseIcons.Captions
                PlayerSheet.SETTINGS -> FuseIcons.Settings2
                PlayerSheet.QUEUE -> FuseIcons.ListMusic
            })
            MenuList(rows, sel, modifier = Modifier.weight(1f, fill = false), fill = false)
        }
    }
}

private fun channelsText(n: Int) = when (n) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$n channels"
}

private fun <T> next(values: List<T>, current: T): T = values[(values.indexOf(current) + 1).mod(values.size)]

private fun speedText(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"
private fun liftText(v: Float) = when {
    v <= 0f -> "Bottom"
    v <= 0.06f -> "Raised"
    else -> "Higher"
}
private fun delayText(ms: Long) = when {
    ms == 0L -> "On time"
    ms > 0 -> "+${ms / 1000.0} s"
    else -> "${ms / 1000.0} s"
}

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
private val SUB_SIZES = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
private val SUB_LIFTS = listOf(0f, 0.06f, 0.14f)
private val SUB_DELAYS = listOf(0L, 250L, 500L, 1_000L, 2_000L, -2_000L, -1_000L, -500L, -250L)

/** Up next shows in the last half minute. */
private const val UP_NEXT_MS = 30_000L
private const val FLASH_MS = 700L

/** The subtitle layer's own bottom margin, which the lift above the controls already includes. */
private const val SUBTITLE_MARGIN = 0.04f

private fun Modifier.androidxOnSize(block: (Int) -> Unit): Modifier = this.then(Modifier.onSizeChanged { block(it.height) })
private val CONTROLS_HEIGHT = 190.dp
private val NARROW = 600.dp
private val SHORT = 480.dp
