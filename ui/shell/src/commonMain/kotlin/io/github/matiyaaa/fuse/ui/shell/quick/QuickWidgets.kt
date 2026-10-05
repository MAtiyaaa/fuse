package io.github.matiyaaa.fuse.ui.shell.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.slideOutHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.components.controlTileShape
import io.github.matiyaaa.fuse.ui.shell.components.controlWellShape
import kotlin.math.roundToInt

/**
 * The frame every quick menu widget sits in: the control tiles' shape and surface, lifted inside an
 * outline ring while [selected] (in the accent while [accent], as while a slider is being set), so
 * a widget answers focus exactly like a tile does.
 */
@Composable
internal fun QuickCard(
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    lit: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = controlTileShape()
    val bg by fuselineColor(
        when {
            lit -> c.accent.copy(alpha = if (c.isDark) 0.13f else 0.1f)
            selected -> c.text.copy(alpha = if (c.isDark) 0.11f else 0.08f)
            else -> c.text.copy(alpha = if (c.isDark) 0.06f else 0.045f)
        },
        motion.tween(Durations.BASE),
        label = "card",
    )
    val lift by fuselineFloat(if (selected) 1f else 0f, motion.focusSpring(), label = "card lift")
    val ring by fuselineColor(if (accent) c.accent else c.focus, motion.tween(Durations.FAST), label = "card ring")
    val edgeRest = Elevation.tile.edgeAlpha(c.isDark)
    val edgeLit = Elevation.tileFocused.edgeAlpha(c.isDark)
    val gap = Size.focusGap
    val ringWidth = Size.focusStroke
    Box(
        modifier
            .graphicsLayer {
                // Wide widgets lift less, so their edges don't swing far.
                val s = if (motion.reduced) 1f else 1f + (6.dp.toPx() / size.width.coerceAtLeast(1f)).coerceAtMost(0.04f) * lift
                scaleX = s
                scaleY = s
            }
            .drawWithCache {
                val g = gap.toPx() + ringWidth.toPx() / 2
                val outer = androidx.compose.ui.geometry.Size(size.width + g * 2, size.height + g * 2)
                val path = Path().apply { addOutline(shape.createOutline(outer, layoutDirection, this@drawWithCache)) }
                val stroke = Stroke(ringWidth.toPx())
                onDrawBehind {
                    if (lift > 0.01f) translate(-g, -g) { drawPath(path, ring, alpha = lift.coerceIn(0f, 1f), style = stroke) }
                }
            }
            .then(if (onClick != null) Modifier.fuseClickable(shape = shape, role = Role.Button, onLongClick = onLongClick, onClick = onClick) else Modifier)
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(bg)
            .lightEdge(shape, { edgeRest + (edgeLit - edgeRest) * lift.coerceIn(0f, 1f) })
            .semantics { this.selected = selected },
        content = content,
    )
}

/** What Now playing shows and does, from the menu music or Fuse Player. */
internal data class NowPlaying(
    val title: String,
    val subtitle: String,
    val playing: Boolean,
    /** Fuse Player's item rather than the menu music. */
    val media: Boolean,
    /** The menu music is off: the widget offers to turn it on. */
    val off: Boolean,
    /** Changes with every new song or item, for the slide between them. */
    val key: Any?,
    /** The way the last skip went: 1 forward, -1 back. */
    val direction: Int,
    /** Counts skips, so a skip to the same song still nudges. */
    val skips: Int,
)

/**
 * Now playing: art that breathes with the music, the song sliding in from the way it was skipped,
 * and skip back, play or pause and skip forward. Three widths: across the menu with everything,
 * two thirds with the buttons under the song, and a tile (a press skips, a hold pauses).
 */
@Composable
internal fun MusicWidget(
    now: NowPlaying,
    span: Int,
    selected: Boolean,
    modifier: Modifier,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val c = Fuse.colors
    QuickCard(selected, modifier, lit = now.playing, onClick = if (span == 1) (if (now.off) onToggle else onNext) else null, onLongClick = if (span == 1) onToggle else null) {
        when (span) {
            3 -> Row(Modifier.fillMaxSize().padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
                MusicArt(now, Modifier.fillMaxHeight().aspectRatio(1f))
                Spacer(Modifier.width(Space.m))
                SongLines(now, Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                SkipButtons(now, big = true, onPrevious, onToggle, onNext)
            }
            2 -> Column(Modifier.fillMaxSize().padding(Space.m), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MusicArt(now, Modifier.size(40.dp))
                    Spacer(Modifier.width(Space.s))
                    SongLines(now, Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SkipButtons(now, big = false, onPrevious, onToggle, onNext) }
            }
            else -> Column(Modifier.fillMaxSize().padding(Space.m), verticalArrangement = Arrangement.SpaceBetween) {
                MusicArt(now, Modifier.size(Size.chip))
                Column {
                    SongTitle(now, Fuse.type.label)
                    FText(
                        when {
                            now.off -> "Turn on"
                            !now.playing -> "Paused"
                            else -> "Tap to skip"
                        },
                        Fuse.type.caption,
                        color = if (now.playing) c.accent else c.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The title, sliding out the way it was skipped and the next one in behind it. */
@Composable
private fun SongTitle(now: NowPlaying, style: androidx.compose.ui.text.TextStyle) {
    val motion = Fuse.motion
    Swap(
        targetState = now.key to now.title,
        transitionSpec = {
            val d = now.direction
            if (motion.reduced) {
                fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.fade(Durations.FAST))
            } else {
                (slideInHorizontally(motion.tween(Durations.BASE, Curves.Enter)) { (it * 0.35f * d).toInt() } + fadeIn(motion.fade(Durations.BASE))) togetherWith
                    (slideOutHorizontally(motion.tween(Durations.FAST, Curves.Exit)) { (-it * 0.35f * d).toInt() } + fadeOut(motion.fade(Durations.FAST)))
            }
        },
        contentKey = { it },
        label = "song",
    ) { (_, title) ->
        FText(title, style, maxLines = 1)
    }
}

@Composable
private fun SongLines(now: NowPlaying, modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier) {
        SongTitle(now, Fuse.type.bodyStrong)
        FText(now.subtitle, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/**
 * The song's art: the accent in a soft wash with the note (or a film), and three bars that dance
 * while it plays and settle low when it stops. A skip turns it over like a record changing.
 */
@Composable
private fun MusicArt(now: NowPlaying, modifier: Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = controlWellShape()
    val turn = remember { FuselineValue(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(now.skips, now.key) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (motion.reduced) return@LaunchedEffect
        turn.snapTo(now.direction.toFloat())
        turn.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 380f))
    }
    val accent = c.accent
    Box(
        modifier
            .graphicsLayer {
                rotationY = turn.value * 28f
                translationX = turn.value * 4.dp.toPx()
                this.shape = shape
                clip = true
            }
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(if (now.media) FuseIcons.Film else FuseIcons.Music, size = Size.iconS, tint = c.onAccent, modifier = Modifier.padding(bottom = 6.dp))
        Equalizer(now.playing, c.onAccent, Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp))
    }
}

/** Three small bars, out of step, rising and falling while [on]; at rest they sit low and even. */
@Composable
private fun Equalizer(on: Boolean, tint: Color, modifier: Modifier) {
    val motion = Fuse.motion
    val rest by fuselineFloat(if (on) 1f else 0f, motion.tween(Durations.BASE), label = "eq")
    val clock = rememberLoopClock("eq")
    val a by clock.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), "eq a")
    val b by clock.animateFloat(1f, 0.3f, infiniteRepeatable(tween(410), RepeatMode.Reverse), "eq b")
    val d by clock.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(610), RepeatMode.Reverse), "eq c")
    Box(
        modifier.size(16.dp, 7.dp).drawBehind {
            val w = 3.dp.toPx()
            val gap = (size.width - w * 3) / 2
            val moving = !motion.reduced
            listOf(a, b, d).forEachIndexed { i, v ->
                val level = 0.28f + (if (moving) v else 0.6f) * 0.72f * rest
                val h = size.height * level.coerceIn(0.2f, 1f)
                drawRoundRect(tint.copy(alpha = 0.9f), Offset(i * (w + gap), size.height - h), androidx.compose.ui.geometry.Size(w, h), CornerRadius(w / 2))
            }
        },
    )
}

/** Skip back, play or pause (filled), skip forward; a skip button kicks the way it skips. */
@Composable
private fun SkipButtons(now: NowPlaying, big: Boolean, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val kick = remember { FuselineValue(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(now.skips) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (motion.reduced) return@LaunchedEffect
        kick.snapTo(1f)
        kick.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 520f))
    }
    val small = if (big) 34.dp else 32.dp
    val main = if (big) 42.dp else 38.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        RoundIcon(FuseIcons.SkipBack, "Previous", small, filled = false, enabled = !now.off, nudge = { if (now.direction < 0) -kick.value else 0f }, onClick = onPrevious)
        RoundIcon(
            when {
                now.off -> FuseIcons.Power
                now.playing -> FuseIcons.Pause
                else -> FuseIcons.Play
            },
            when {
                now.off -> "Turn on the menu music"
                now.playing -> "Pause"
                else -> "Play"
            },
            main, filled = true, enabled = true, nudge = { 0f }, onClick = onToggle,
        )
        RoundIcon(FuseIcons.SkipForward, "Next", small, filled = false, enabled = !now.off, nudge = { if (now.direction > 0) kick.value else 0f }, onClick = onNext)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: Dp, filled: Boolean, enabled: Boolean, nudge: () -> Float, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) c.text else c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f))
            .fuseClickable(shape = CircleShape, role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(
            icon,
            size = if (filled) Size.iconS + 2.dp else Size.iconS,
            tint = if (filled) c.ink else c.text.copy(alpha = if (enabled) 0.9f else 0.35f),
            modifier = Modifier.graphicsLayer { translationX = nudge() * 5.dp.toPx() },
        )
    }
}

/**
 * Brightness or volume as a tall tile, filling from the bottom like a glass: drag it up or down,
 * or with the controller press it, then any direction sets it. The value sits at the top, the icon
 * at the bottom, both turning to the accent's text colour where the fill passes under them.
 */
@Composable
internal fun TallSlider(
    value: Float,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    adjusting: Boolean,
    modifier: Modifier,
    onPress: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(value) }
    val eased by fuselineFloat(value.coerceIn(0f, 1f), motion.focusSpring(), label = "tall fill")
    val v = if (dragging) finger else eased
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val accent = c.accent
    QuickCard(selected, modifier.semantics { contentDescription = label }, accent = adjusting, onClick = onPress) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        var start = current
                        var startY = down.position.y
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { moved, _ ->
                            moved.consume()
                            start = current
                            startY = moved.position.y
                        } ?: return@awaitEachGesture
                        finger = start
                        dragging = true
                        verticalDrag(slop.id) { moved ->
                            moved.consume()
                            finger = (start - (moved.position.y - startY) / height).coerceIn(0f, 1f)
                            change(finger)
                        }
                        dragging = false
                    }
                }
                .drawBehind {
                    val h = size.height * v
                    if (h > 0f) drawRect(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.82f))), Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
                },
        ) {
            val words: @Composable (Color) -> Unit = { tint ->
                Column(Modifier.fillMaxSize().padding(Space.m), verticalArrangement = Arrangement.SpaceBetween) {
                    FText("${(v * 100).roundToInt()}%", Fuse.type.numeric, color = tint, maxLines = 1)
                    Column {
                        FuseIcon(icon, size = Size.iconM, tint = tint)
                        Spacer(Modifier.height(Space.xs))
                        FText(label, Fuse.type.label, color = tint, maxLines = 1)
                    }
                }
            }
            words(c.text)
            Box(
                Modifier.matchParentSize().drawWithContent {
                    clipRect(top = size.height * (1f - v)) { this@drawWithContent.drawContent() }
                },
            ) { words(c.onAccent) }
        }
    }
}

/**
 * The second screen's three ways, side by side under its name: Off, Fuse (the games on the screen
 * below) and Flipped (on the screen above). The marker glides to the choice.
 */
@Composable
internal fun ChoiceWidget(
    title: String,
    icon: ImageVector,
    choices: List<String>,
    chosen: Int,
    detail: String,
    selected: Boolean,
    modifier: Modifier,
    onChoose: (Int) -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    QuickCard(selected, modifier) {
        Column(Modifier.fillMaxSize().padding(Space.m), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(icon, size = Size.iconS, tint = c.text.copy(alpha = 0.82f))
                Spacer(Modifier.width(Space.s))
                FText(title, Fuse.type.label, maxLines = 1)
                Spacer(Modifier.width(Space.s))
                Swap(targetState = detail, transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.FAST)) }, modifier = Modifier.weight(1f), label = "choice detail") { d ->
                    FText(d, Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.fillMaxWidth(), align = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            val at by fuselineFloat(chosen.toFloat(), motion.focusSpring(), label = "choice")
            val marker = c.accent
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(PillShape)
                    .background(c.text.copy(alpha = if (c.isDark) 0.06f else 0.05f))
                    .drawBehind {
                        val inset = 3.dp.toPx()
                        val w = (size.width - inset * 2) / choices.size
                        drawRoundRect(marker, Offset(inset + w * at, inset), androidx.compose.ui.geometry.Size(w, size.height - inset * 2), CornerRadius((size.height - inset * 2) / 2))
                    },
            ) {
                Row(Modifier.fillMaxSize()) {
                    choices.forEachIndexed { i, label ->
                        val on = i == chosen
                        val tint by fuselineColor(if (on) c.onAccent else c.textMuted, motion.tween(Durations.FAST), label = "choice text")
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(PillShape)
                                .fuseClickable(shape = PillShape, role = Role.Tab, onClick = { onChoose(i) })
                                .semantics { this.selected = on },
                            contentAlignment = Alignment.Center,
                        ) { FText(label, Fuse.type.label, color = tint, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** The last place while editing: a dashed outline with a plus, for putting more in. */
@Composable
internal fun AddTile(selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val shape = controlTileShape()
    val line = c.text.copy(alpha = 0.32f)
    QuickCard(selected, modifier, onClick = onClick) {
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val inset = 1.5.dp.toPx()
                val outline = shape.createOutline(androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2), layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                val stroke = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                onDrawBehind { translate(inset, inset) { drawPath(path, line, style = stroke) } }
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FuseIcon(FuseIcons.Plus, size = Size.iconM, tint = c.text)
                Spacer(Modifier.height(Space.xs))
                FText("Add", Fuse.type.label, maxLines = 1)
            }
        }
    }
}

/**
 * A small round badge on a tile's corner while editing: remove (in the danger colour), or change its
 * size. Solid, with a shadow, so it reads over any tile.
 */
@Composable
internal fun EditBadge(icon: ImageVector, label: String, danger: Boolean = false, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        modifier
            .size(24.dp)
            .graphicsLayer { shadowElevation = 6.dp.toPx(); shape = CircleShape; clip = true }
            .background(if (danger) c.danger else c.text)
            .fuseClickable(shape = CircleShape, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = 13.dp, tint = if (danger) Color.White else c.ink)
    }
}
