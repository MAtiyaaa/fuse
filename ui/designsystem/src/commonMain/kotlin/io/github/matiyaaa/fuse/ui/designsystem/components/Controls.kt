package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlin.math.floor

// ------------------------------------------------------------------------------------- switches

/**
 * On/off switch. The knob's position and a check mark inside it carry the state, not only colour.
 * The knob travels on a lively spring and stretches a little mid-way, like something soft being
 * pushed; under Reduced motion it moves at once.
 */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val t by animateFloatAsState(
        if (on) 1f else 0f,
        if (motion.reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 650f),
        label = "toggle",
    )
    val offTrack = c.text.copy(alpha = if (c.isDark) 0.16f else 0.14f)
    val onTrack = c.accent
    val knobOn = if (c.onAccent.luminanceApprox() > 0.5f) c.onAccent else Color.White
    val knobOff = if (c.isDark) lerp(c.surfaceRaised, c.text, 0.86f) else Color.White
    val check = c.accent
    val shade = if (c.isDark) Color.Black.copy(alpha = 0.32f) else c.text.copy(alpha = 0.18f)
    Spacer(
        modifier
            .size(width = TOGGLE_WIDTH, height = TOGGLE_HEIGHT)
            .drawWithCache {
                val pad = 3.dp.toPx()
                val d = size.height - pad * 2
                // A check drawn in the knob's own box, built once.
                val mark = Path().apply {
                    moveTo(d * 0.29f, d * 0.52f)
                    lineTo(d * 0.44f, d * 0.67f)
                    lineTo(d * 0.72f, d * 0.36f)
                }
                val markStroke = Stroke(d * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    val dim = if (enabled) 1f else 0.4f
                    val tc = t.coerceIn(0f, 1f)
                    drawRoundRect(lerp(offTrack, onTrack, tc), cornerRadius = CornerRadius(size.height / 2), alpha = dim)
                    // Stretches most half way across, back to round at either end.
                    val stretch = d * 0.3f * (4f * tc * (1f - tc))
                    val kw = d + stretch
                    val x = pad + (size.width - pad * 2 - kw) * t.coerceIn(-0.04f, 1.04f)
                    val r = CornerRadius(d / 2)
                    drawRoundRect(shade, Offset(x, pad + 1.dp.toPx()), androidx.compose.ui.geometry.Size(kw, d), r, alpha = dim)
                    drawRoundRect(lerp(knobOff, knobOn, tc), Offset(x, pad), androidx.compose.ui.geometry.Size(kw, d), r, alpha = dim)
                    val markAlpha = ((tc - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    if (markAlpha > 0f) {
                        translate(x + stretch / 2, pad) { drawPath(mark, check, alpha = markAlpha * dim, style = markStroke) }
                    }
                }
            },
    )
}

private val TOGGLE_WIDTH = 46.dp
private val TOGGLE_HEIGHT = 28.dp

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ------------------------------------------------------------------------------------- progress

/**
 * A thin progress bar: a quiet track and a solid fill with round ends that eases to each new value.
 * With [value] null the length isn't known: a segment runs along the track, its front pulling ahead
 * and its back catching up, so it reads as work going on (it rests in the middle under Reduced
 * motion).
 */
@Composable
fun ProgressBar(value: Float?, modifier: Modifier = Modifier, color: Color = Fuse.colors.accent, height: Dp = TRACK) {
    val track = Fuse.colors.text.copy(alpha = 0.12f)
    val motion = Fuse.motion
    if (value != null) {
        // An interruptible spring, so a value that keeps changing (a download) flows instead of stepping.
        val v by animateFloatAsState(value.coerceIn(0f, 1f), motion.value(), label = "progress")
        Spacer(
            modifier.height(height).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                if (v > 0f) drawRoundRect(color, size = size.copy(width = (size.width * v).coerceAtLeast(size.height)), cornerRadius = r)
            },
        )
    } else {
        val phase = if (motion.reduced) {
            null
        } else {
            rememberInfiniteTransition(label = "indeterminate").animateFloat(
                0f, 1f, infiniteRepeatable(tween(INDETERMINATE_MS, easing = LinearEasing), RepeatMode.Restart), label = "phase",
            )
        }
        Spacer(
            modifier.height(height).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val w = size.width
                val (from, to) = if (phase == null) {
                    w * 0.35f to w * 0.65f
                } else {
                    // The front leads on an easing curve; the back follows the same curve later.
                    val p = phase.value
                    val head = FastOutSlowInEasing.transform((p / 0.75f).coerceIn(0f, 1f))
                    val tail = FastOutSlowInEasing.transform(((p - 0.25f) / 0.75f).coerceIn(0f, 1f))
                    (-0.1f + 1.2f * tail) * w to (-0.1f + 1.2f * head) * w
                }
                val left = from.coerceAtLeast(0f)
                val right = to.coerceAtMost(w)
                if (right - left > 0.5f) drawRoundRect(color, Offset(left, 0f), size.copy(width = right - left), r)
            },
        )
    }
}

private const val INDETERMINATE_MS = 1500
private val TRACK = Size.track

/**
 * Circular progress (achievement completion, downloads): a round-capped arc on a faint ring that
 * eases to each new value. [content] sits in the middle (a percentage, an icon).
 */
@Composable
fun ProgressRing(
    value: Float,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    stroke: Dp = 4.dp,
    color: Color = Fuse.colors.accent,
    trackColor: Color = Fuse.colors.text.copy(alpha = 0.12f),
    content: (@Composable () -> Unit)? = null,
) {
    val v by animateFloatAsState(value.coerceIn(0f, 1f), Fuse.motion.value(), label = "ring")
    Box(
        modifier.size(size).drawWithCache {
            val s = stroke.toPx()
            val box = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
            val at = Offset(s / 2, s / 2)
            val trackStroke = Stroke(s)
            val arcStroke = Stroke(s, cap = StrokeCap.Round)
            onDrawBehind {
                drawArc(trackColor, 0f, 360f, false, at, box, style = trackStroke)
                // Even a sliver shows as a dot, so "just started" never looks like "nothing".
                if (v > 0f) drawArc(color, -90f, (360f * v).coerceAtLeast(0.5f), false, at, box, style = arcStroke)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        content?.invoke()
    }
}

/**
 * Fuse's loading indicator: an arc that grows as it chases round a faint track, then lets its tail
 * catch up, turning a little further each time. Under Reduced motion it turns slowly at one length.
 */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = Fuse.colors.text) {
    val reduced = Fuse.motion.reduced
    val t = rememberInfiniteTransition(label = "spin")
    val turn = t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (reduced) 2400 else 1600, easing = LinearEasing)), label = "turn",
    )
    // Four grow-and-catch-up cycles, each ending 270 degrees further on, before it repeats exactly.
    val cycle = t.animateFloat(0f, 4f, infiniteRepeatable(tween(SPIN_CYCLE_MS * 4, easing = LinearEasing)), label = "cycle")
    Spacer(
        modifier.size(size).drawWithCache {
            val s = (this.size.minDimension * 0.09f).coerceIn(2.dp.toPx(), 3.5.dp.toPx())
            val box = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
            val at = Offset(s / 2, s / 2)
            val trackStroke = Stroke(s)
            val arcStroke = Stroke(s, cap = StrokeCap.Round)
            onDrawBehind {
                drawArc(color.copy(alpha = color.alpha * 0.14f), 0f, 360f, false, at, box, style = trackStroke)
                if (reduced) {
                    drawArc(color, turn.value, 100f, false, at, box, style = arcStroke)
                } else {
                    val k = cycle.value
                    val n = floor(k)
                    val f = k - n
                    val head = FastOutSlowInEasing.transform((f / 0.5f).coerceIn(0f, 1f)) * 250f
                    val tail = FastOutSlowInEasing.transform(((f - 0.5f) / 0.5f).coerceIn(0f, 1f)) * 250f
                    val start = turn.value + n * 270f + tail
                    drawArc(color, start, (head - tail).coerceAtLeast(12f), false, at, box, style = arcStroke)
                }
            }
        },
    )
}

private const val SPIN_CYCLE_MS = 1300

// ------------------------------------------------------------------------------------- sliders

/**
 * Horizontal value slider, stepped by the controller (left/right) or dragged by touch. Selected, the
 * track thickens and the knob grows inside a focus ring; the value eases to each step. [valueText]
 * shows the value after the bar (in tabular figures, so it never jitters).
 */
@Composable
fun SliderBar(
    value: Float,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Makes the bar touchable: a tap or a drag along it sets the value (0..1). */
    onChange: ((Float) -> Unit)? = null,
    enabled: Boolean = true,
    valueText: String? = null,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    var dragging by remember { mutableStateOf(false) }
    // Under a finger the knob follows exactly; otherwise it eases to the new value.
    val eased by animateFloatAsState(value.coerceIn(0f, 1f), motion.focusSpring(), label = "slider")
    val sel by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "sliderFocus")
    val grab by animateFloatAsState(if (dragging) 1f else 0f, motion.tween(Durations.FAST), label = "grab")
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    val trackColor = c.text.copy(alpha = 0.14f)
    val fill = c.accent
    val knob = Color.White
    val knobEdge = c.text.copy(alpha = if (c.isDark) 0f else 0.18f)
    val ring = c.focus
    val shade = if (c.isDark) Color.Black.copy(alpha = 0.35f) else c.text.copy(alpha = 0.16f)
    val bar: @Composable (Modifier) -> Unit = { m ->
        Box(
            m
                .heightIn(min = 24.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .then(
                    if (onChange == null || !enabled) Modifier else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            dragging = true
                            change?.invoke((down.position.x / width).coerceIn(0f, 1f))
                            drag(down.id) { moved ->
                                moved.consume()
                                change?.invoke((moved.position.x / width).coerceIn(0f, 1f))
                            }
                            dragging = false
                        }
                    },
                )
                .drawWithCache {
                    val edgeStroke = Stroke(1.dp.toPx())
                    val ringStroke = Stroke(Size.focusStroke.toPx())
                    onDrawBehind {
                    val v = if (dragging) current.coerceIn(0f, 1f) else eased
                    val th = (4f + 2f * sel).dp.toPx()
                    val knobR = (7f + 3f * sel + 1.5f * grab).dp.toPx()
                    val cy = size.height / 2
                    // The knob never leaves the track, so the ends stay reachable and visible.
                    val left = knobR
                    val right = size.width - knobR
                    val x = left + (right - left) * v
                    val r = CornerRadius(th / 2)
                    drawRoundRect(trackColor, Offset(0f, cy - th / 2), androidx.compose.ui.geometry.Size(size.width, th), r)
                    drawRoundRect(fill, Offset(0f, cy - th / 2), androidx.compose.ui.geometry.Size(x.coerceAtLeast(th), th), r)
                    drawCircle(shade, knobR, Offset(x, cy + 1.dp.toPx()))
                    drawCircle(knob, knobR, Offset(x, cy))
                    if (knobEdge.alpha > 0f) drawCircle(knobEdge, knobR, Offset(x, cy), style = edgeStroke)
                    if (sel > 0.01f) {
                        drawCircle(ring.copy(alpha = ring.alpha * sel), knobR + (1f + 2f * sel).dp.toPx() + 1.dp.toPx(), Offset(x, cy), style = ringStroke)
                    }
                    }
                },
        )
    }
    if (valueText == null) {
        bar(modifier)
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            bar(Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            FText(
                valueText,
                Fuse.type.numeric,
                color = if (selected) c.text else c.textMuted,
                maxLines = 1,
                align = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.widthIn(min = 44.dp),
            )
        }
    }
}

/**
 * A large touch slider for status and control pages: the whole bar fills with the value, with its
 * icon, label and value inside. Where the fill passes under them they turn to the accent's own text
 * colour, so they stay readable at any value. Dragging moves the value by how far the finger travels
 * (not to where it lands), so a tap never jumps it; a sideways drag is the slider's, so a pager
 * around it doesn't turn the page. [enabled] false dims it and ignores touches.
 */
@Composable
fun FillSlider(
    value: Float,
    onChange: (Float) -> Unit,
    icon: ImageVector,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    var dragging by remember { mutableStateOf(false) }
    val eased by animateFloatAsState(value.coerceIn(0f, 1f), motion.focusSpring(), label = "fill")
    val v = if (dragging) value.coerceIn(0f, 1f) else eased
    val grab by animateFloatAsState(if (dragging) 1f else 0f, motion.tween(Durations.FAST), label = "fillGrab")
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    // Soft corners, but never rounder than the theme's panels (sharp themes keep their edges).
    val shape = RoundedCornerShape(Fuse.geometry.panel.coerceAtMost(Radius.l))
    val accent = c.accent
    val handle = c.onAccent
    val content: @Composable (Color) -> Unit = { tint ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            FuseIcon(icon, tint = tint, size = Size.iconM)
            FText(label, Fuse.type.bodyStrong, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
            FText(valueText, Fuse.type.numeric, color = tint, maxLines = 1)
        }
    }
    Box(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f - 0.012f * grab
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(c.text.copy(alpha = 0.08f))
            .litEdge(shape, top = Color.White.copy(alpha = if (c.isDark) 0.1f else 0.5f), rest = c.hairline)
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        var start = current
                        var startX = down.position.x
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { moved, _ ->
                            moved.consume()
                            start = current
                            startX = moved.position.x
                        } ?: return@awaitEachGesture
                        dragging = true
                        horizontalDrag(slop.id) { moved ->
                            moved.consume()
                            change((start + (moved.position.x - startX) / width).coerceIn(0f, 1f))
                        }
                        dragging = false
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.matchParentSize().drawWithCache {
                val brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.82f), accent))
                onDrawBehind {
                    val w = size.width * v
                    if (w > 0f) {
                        drawRect(brush, size = size.copy(width = w))
                        // A small grip at the fill's edge, longer while held.
                        val gh = size.height * (0.32f + 0.12f * grab)
                        val gw = 3.dp.toPx()
                        val gx = (w - gw - 6.dp.toPx()).coerceAtLeast(2.dp.toPx())
                        if (w > 14.dp.toPx()) {
                            drawRoundRect(handle.copy(alpha = 0.55f), Offset(gx, (size.height - gh) / 2), androidx.compose.ui.geometry.Size(gw, gh), CornerRadius(gw / 2))
                        }
                    }
                }
            },
        )
        content(c.text)
        // The same content in the accent's text colour, shown only over the fill.
        Box(
            Modifier.matchParentSize().drawWithContent {
                clipRect(right = size.width * v) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            content(c.onAccent)
        }
    }
}

// ------------------------------------------------------------------------------------- choices

/**
 * A row of mutually exclusive options ("Grid, List, Covers") with one indicator that glides to the
 * chosen one, stretching as it travels. [focused] is controller focus on the whole control: the
 * indicator then inverts and takes the focus ring. Segments share the width equally (as wide as the
 * widest label). Each segment is a touch target that calls [onSelect].
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    icons: List<ImageVector?> = emptyList(),
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val count = options.size.coerceAtLeast(1)
    val glide = rememberStretchGlide(selectedIndex.coerceIn(0, count - 1).toFloat())
    val f by animateFloatAsState(if (focused) 1f else 0f, motion.focusSpring(), label = "segFocus")
    val shape = controlShape()
    val thumbRest = if (c.isDark) c.text.copy(alpha = 0.16f) else c.surface
    val thumbFocus = c.text
    val ring = c.focus
    val shade = if (c.isDark) Color.Black.copy(alpha = 0.3f) else c.text.copy(alpha = 0.12f)
    val pill = Fuse.geometry.family != io.github.matiyaaa.fuse.model.CornerFamily.SHARP
    val corner = Fuse.geometry.control
    Row(
        modifier
            .width(IntrinsicSize.Max)
            .height(Size.touch - Space.xs)
            .clip(shape)
            .background(c.quietFill())
            .drawWithCache {
                val ringStroke = Stroke(1.5.dp.toPx())
                onDrawBehind {
                val inset = 3.dp.toPx()
                val segW = (size.width - inset * 2) / count
                val left = inset + segW * glide.start
                val right = inset + segW * glide.end
                val h = size.height - inset * 2
                val r = CornerRadius(if (pill) h / 2 else (corner.toPx() - inset).coerceAtLeast(2.dp.toPx()))
                val w = (right - left).coerceAtLeast(h)
                drawRoundRect(shade, Offset(left, inset + 1.dp.toPx()), androidx.compose.ui.geometry.Size(w, h), r)
                drawRoundRect(lerp(thumbRest, thumbFocus, f), Offset(left, inset), androidx.compose.ui.geometry.Size(w, h), r)
                if (f > 0.01f) {
                    drawRoundRect(
                        ring.copy(alpha = ring.alpha * f),
                        Offset(left - 1.dp.toPx(), inset - 1.dp.toPx()),
                        androidx.compose.ui.geometry.Size(w + 2.dp.toPx(), h + 2.dp.toPx()),
                        CornerRadius(r.x + 1.dp.toPx()),
                        style = ringStroke,
                    )
                }
                }
            }
            .padding(Space.xxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            val tint by animateColorAsState(
                when {
                    on && focused -> c.ink
                    on -> c.text
                    else -> c.textMuted
                },
                motion.tween(Durations.FAST),
                label = "segTint",
            )
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(i) }
                    .semantics { selected = on }
                    .padding(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.getOrNull(i)?.let { FuseIcon(it, size = Size.iconS, tint = tint) }
                FText(label, Fuse.type.label, color = tint, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------------------------- labels

/**
 * Small rounded label: platform names, counts, states, and tabs or filters. [selected] fills it;
 * [focused] lifts it and adds the focus ring so controller focus is visible even on the selected
 * chip. [onClick] makes it tappable, with hover and press feedback.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Fuse.colors.text,
    background: Color = color.copy(alpha = 0.12f),
    selected: Boolean = false,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val ring by animateFloatAsState(if (focused) 1f else 0f, motion.focusSpring(), label = "chipFocus")
    val interaction = remember { MutableInteractionSource() }
    val press = rememberAtomPress(interaction, onClick != null)
    val fill by animateColorAsState(if (selected) c.text else background, motion.tween(Durations.FAST), label = "chipFill")
    val tint by animateColorAsState(if (selected) c.ink else color, motion.tween(Durations.FAST), label = "chipTint")
    val hover = c.hoverOverlay()
    // Chips are pills like the buttons, and keep the theme's corners in sharp themes.
    val shape = controlShape()
    Row(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f + 0.06f * ring - 0.04f * press.pressed
                scaleX = s
                scaleY = s
            }
            // The ring sits just outside the chip, so focusing never changes the layout.
            .ringOutside({ ring }, c.focus, controlRingShape())
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, null, onClick = onClick).semantics { this.selected = focused }
                } else {
                    Modifier
                },
            )
            .background(fill)
            .drawBehind { if (press.hovered > 0f) drawRect(hover, alpha = press.hovered) }
            .padding(horizontal = Space.m, vertical = Space.xs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            FuseIcon(icon, size = CHIP_ICON, tint = tint)
            Box(Modifier.width(Space.xs + 2.dp))
        }
        FText(text, Fuse.type.label, color = tint, maxLines = 1)
    }
}

private val CHIP_ICON = Size.iconXS

/**
 * A small solid label that marks a state on a tile or row ("New", "Update", "2"). [filled] badges
 * are solid in [color] with text that reads on it; quiet ones are a soft tint of it.
 */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Fuse.colors.accent,
    filled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = Fuse.colors
    val on = when {
        !filled -> color
        color == c.accent -> c.onAccent
        color.luminanceApprox() > 0.55f -> Color.Black.copy(alpha = 0.85f)
        else -> Color.White
    }
    Row(
        modifier
            .heightIn(min = BADGE_HEIGHT)
            .clip(PillShape)
            .background(if (filled) color else color.copy(alpha = 0.16f))
            .padding(horizontal = Space.s - Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xxs + 1.dp),
    ) {
        if (icon != null) FuseIcon(icon, size = 12.dp, tint = on)
        FText(text, Fuse.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFeatureSettings = "tnum"), color = on, maxLines = 1)
    }
}

private val BADGE_HEIGHT = 20.dp

/**
 * A round state mark laid on art or a row: an icon in a small disc (favourite, update, warning).
 * The default disc is a dark glass that reads over any artwork.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Fuse.colors.onArt,
    background: Color = Color.Black.copy(alpha = 0.55f),
    size: Dp = Size.badge,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .litEdge(CircleShape, top = Color.White.copy(alpha = 0.22f), rest = Color.White.copy(alpha = 0.06f), reach = size / 2),
        contentAlignment = Alignment.Center,
    ) {
        // Size.badgeIcon in the standard badge, in proportion at other sizes.
        FuseIcon(icon, size = Size.badgeIcon * (size / Size.badge), tint = tint)
    }
}

/** A status dot + label, for connection and readiness states (shape differs per state too). */
@Composable
fun StatusDot(ok: Boolean?, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val color by animateColorAsState(
        when (ok) { true -> c.success; false -> c.danger; null -> c.textFaint },
        Fuse.motion.tween(Durations.BASE),
        label = "statusDot",
    )
    Spacer(
        modifier.size(10.dp).drawWithCache {
            val ringStroke = Stroke(2.dp.toPx())
            onDrawBehind {
                when (ok) {
                    true -> drawCircle(color)
                    false -> drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = ringStroke)
                    null -> drawCircle(color, radius = size.minDimension / 3)
                }
            }
        },
    )
}
