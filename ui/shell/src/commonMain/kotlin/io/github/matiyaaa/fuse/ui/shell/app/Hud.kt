package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusCluster
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyphDefaults
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.expandHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberGlide
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.shrinkHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The buttons at the end of the tab line, reachable with the stick after the last tab. */
enum class HudButton {
    SEARCH,
    SETTINGS,

    /** The status at the far right (Wi-Fi, battery, clock), which opens the quick menu. */
    STATUS,
}

/**
 * The top line: Fuse's mark and the section tabs on the left, then Search and Settings, and status
 * on the right. Every tab shows its name when there is room (icons alone left people guessing what
 * they were); on narrow screens only the active one does. When controller focus moves up into the
 * line the focused item gets an outline, and LB/RB switch sections from anywhere.
 *
 * One short accent bar marks where you are, and it glides there: from tab to tab, and over to Search
 * or Settings while one of those is open ([activeButton]). The line never claims a tab is open when
 * it isn't, so [active] is null then. Every item answers the mouse (a soft highlight) and the touch
 * (a press), with targets of [Size.touch].
 */
@Composable
fun Hud(
    destinations: List<Destination>,
    /** How this device names and draws each section. */
    sections: Sections,
    active: Destination?,
    tabsFocused: Boolean,
    status: SystemStatus,
    clock24h: Boolean,
    showWifi: Boolean,
    showBluetooth: Boolean,
    onSelect: (Destination) -> Unit,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = Space.gutter,
    focusedButton: HudButton? = null,
    onButton: (HudButton) -> Unit = {},
    activities: List<HudActivity> = emptyList(),
    /** Search or Settings is the page that is open: its button shows as the active place. */
    activeButton: HudButton? = null,
) {
    val time = rememberClockText(clock24h)
    val anchors = remember { HudAnchors() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Size.hudHeight)
            .onGloballyPositioned { anchors.lineX = it.positionInRoot().x },
    ) {
        // Room for every label: about 120dp per tab plus the mark, the LB/RB glyphs, buttons and status.
        val labels = maxWidth > 120.dp * destinations.size + 430.dp
        // Narrow screens keep the tabs whole by giving up status first: Wi-Fi and battery, then the
        // clock (a phone held upright shows its own).
        val statusRoom = when {
            maxWidth < NARROW -> StatusRoom.NONE
            maxWidth < COMPACT -> StatusRoom.CLOCK
            else -> StatusRoom.ALL
        }
        val glyphs by fuselineFloat(if (tabsFocused) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "tab glyphs")
        Row(
            Modifier.fillMaxSize().padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FuseMark(Modifier.size(Size.iconL))
            Spacer(Modifier.width(Space.s))
            // The tabs take what is left after the buttons and status, which never shrink. The LB/RB
            // glyphs keep their place while hidden, so nothing moves when the stick reaches the tabs;
            // a phone held upright gives that room to the tabs instead.
            val shoulders = statusRoom != StatusRoom.NONE
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (shoulders) {
                    Box(Modifier.alpha(glyphs)) { ButtonGlyph(HintButton.PREV, size = ButtonGlyphDefaults.SmallSize, color = Fuse.colors.textFaint) }
                    Spacer(Modifier.width(Space.xs))
                }
                // When the tabs don't all fit they scroll like a carousel: the active one is always
                // shown whole, and tabs slipping past either edge shrink and fade into it.
                val scroll = rememberScrollState()
                val requesters = remember(destinations) { destinations.associateWith { BringIntoViewRequester() } }
                var viewport by remember { mutableIntStateOf(0) }
                val edge = with(LocalDensity.current) { 56.dp.toPx() }
                val activeWidth = remember { mutableIntStateOf(0) }
                // Again once its label has opened, and with room to spare so the fade never covers it.
                LaunchedEffect(active, labels, activeWidth.intValue, destinations, viewport) {
                    val requester = active?.let { requesters[it] } ?: return@LaunchedEffect
                    requester.bringIntoView(Rect(-edge, 0f, activeWidth.intValue + edge, 1f))
                }
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .onSizeChanged { viewport = it.width }
                        .fadeSides(fadeLeft = { scroll.value > 0 }, fadeRight = { scroll.value < scroll.maxValue })
                        .horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Keyed by section, so each tab keeps its own place when tabs come and go (Cartridge's
                        // appears once it is installed, or after coming back from it).
                    for (d in destinations) key(d) {
                        var x by remember { mutableFloatStateOf(0f) }
                        var w by remember { mutableIntStateOf(0) }
                        Tab(
                            sections.label(d), sections.icon(d), selected = d == active, focused = tabsFocused && focusedButton == null && d == active, showLabel = labels || d == active,
                            modifier = Modifier
                                .bringIntoViewRequester(requesters.getValue(d))
                                .onPlaced {
                                    x = it.positionInParent().x
                                    w = it.size.width
                                    if (d == active) activeWidth.intValue = it.size.width
                                }
                                .anchor(anchors, d)
                                .graphicsLayer {
                                    // The active tab always shows whole and at full strength.
                                    if (viewport <= 0 || scroll.maxValue == 0 || d == active) return@graphicsLayer
                                    val center = x - scroll.value + w / 2f
                                    val nearest = minOf(center, viewport - center)
                                    val t = (nearest / edge).coerceIn(0f, 1f)
                                    val s = 0.84f + 0.16f * t
                                    scaleX = s
                                    scaleY = s
                                    alpha = 0.45f + 0.55f * t
                                },
                            onClick = { onSelect(d) },
                        )
                    }
                }
                if (shoulders) {
                    Spacer(Modifier.width(Space.xs))
                    Box(Modifier.alpha(glyphs)) { ButtonGlyph(HintButton.NEXT, size = ButtonGlyphDefaults.SmallSize, color = Fuse.colors.textFaint) }
                }
            }
            Spacer(Modifier.width(Space.m))
            for (a in activities) {
                HudActivityChip(a)
                Spacer(Modifier.width(Space.xs))
            }
            HudIconButton(
                FuseIcons.Search, "Search",
                focused = tabsFocused && focusedButton == HudButton.SEARCH,
                active = activeButton == HudButton.SEARCH,
                modifier = Modifier.anchor(anchors, HudButton.SEARCH),
            ) { onButton(HudButton.SEARCH) }
            HudIconButton(
                FuseIcons.Settings, "Settings",
                focused = tabsFocused && focusedButton == HudButton.SETTINGS,
                active = activeButton == HudButton.SETTINGS,
                modifier = Modifier.anchor(anchors, HudButton.SETTINGS),
            ) { onButton(HudButton.SETTINGS) }
            if (statusRoom != StatusRoom.NONE) {
                // A hairline keeps the things you open apart from the things you read.
                Spacer(Modifier.width(Space.xs))
                Box(Modifier.width(Size.divider).height(Size.iconM).background(Fuse.colors.hairlineStrong))
                Spacer(Modifier.width(Space.xs))
                val shape = rememberHudShape(insetX = false)
                val statusFocus by fuselineFloat(if (tabsFocused && focusedButton == HudButton.STATUS) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "statusFocus")
                Box(
                    Modifier
                        .height(Size.touch)
                        .fuseClickable(shape = shape, scale = false, role = Role.Button, onClickLabel = "Quick menu", onClick = onStatusClick)
                        .hudFocus(shape, { statusFocus }, Fuse.colors.text.copy(alpha = if (Fuse.colors.isDark) 0.12f else 0.08f), Fuse.colors.focus)
                        .padding(horizontal = Space.m),
                    contentAlignment = Alignment.Center,
                ) {
                    val all = statusRoom == StatusRoom.ALL
                    StatusCluster(
                        if (all) status else status.copy(batteryPercent = null),
                        time,
                        showWifi = showWifi && all,
                        showBluetooth = showBluetooth && all,
                    )
                }
            }
        }
        val key: Any? = activeButton ?: active
        if (key != null) ActiveMarker(anchors, key)
    }
}

/** How much of the status cluster the line has room for. */
private enum class StatusRoom { NONE, CLOCK, ALL }

/** Below this width the clock goes too (phones held upright show their own); below [COMPACT] only it stays. */
private val NARROW = 480.dp
private val COMPACT = 720.dp

/**
 * Where each tab and button of the line sits (its centre, across the window), so the active marker
 * can travel between them. Updated by layout only when something actually moved.
 */
@Stable
private class HudAnchors {
    val centres = mutableStateMapOf<Any, Float>()
    var lineX by mutableFloatStateOf(0f)

    fun set(key: Any, x: Float) {
        if (centres[key] != x) centres[key] = x
    }
}

private fun Modifier.anchor(anchors: HudAnchors, key: Any): Modifier = onGloballyPositioned { c ->
    // Unclipped, so a tab half scrolled out of the carousel still reports its true centre.
    anchors.set(key, c.localToRoot(Offset(c.size.width / 2f, 0f)).x)
}

/**
 * The accent bar under the active place. It glides between tabs (and to Search or Settings): the
 * leading edge first, the trailing one following, so it stretches a little toward where it goes,
 * never more than three bars long however far it travels. While the same tab moves it follows it
 * frame for frame instead of chasing it. It snaps under Reduced motion.
 */
@Composable
private fun BoxScope.ActiveMarker(anchors: HudAnchors, key: Any) {
    val at = anchors.centres[key] ?: return
    val centre = with(LocalDensity.current) { (at - anchors.lineX).toDp() }
    val half = Size.sparkWidth / 2
    // Keyed by the place it marks: it glides when the place changes, and when that tab only moves
    // (the carousel scrolling, a label opening beside it) it stays exactly under it.
    val glide = rememberGlide(centre - half, centre + half, key)
    val accent = Fuse.colors.accent
    Spacer(
        Modifier.matchParentSize().drawBehind {
            var start = glide.start.toPx()
            var end = glide.end.toPx()
            val bar = Size.sparkWidth.toPx()
            val longest = bar * 3
            if (end - start > longest) {
                // Led by the edge that is travelling.
                if (centre.toPx() > (start + end) / 2) start = end - longest else end = start + longest
            }
            val h = Size.sparkHeight.toPx()
            val top = size.height / 2 + Size.iconM.toPx() / 2 + Space.xs.toPx()
            drawRoundRect(accent, Offset(start, top), GSize(end - start, h), CornerRadius(h / 2))
        },
    )
}

/**
 * Something working in the background, shown in the top line: an icon in a ring that fills with
 * [progress] (spinning while it is null), or with an accent dot when it needs you ([attention]).
 */
data class HudActivity(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val progress: Float? = null,
    val attention: Boolean = false,
    /** A state rather than work (safe mode): the ring stays quiet, with no turning arc. */
    val steady: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun HudActivityChip(a: HudActivity) {
    val c = Fuse.colors
    val sweep by fuselineFloat((a.progress ?: 0f).coerceIn(0f, 1f), Fuse.motion.value(), label = "activity")
    // An unknown amount turns; under Reduced motion and in Low Power Mode it rests as a quarter arc.
    val turning = a.progress == null && !a.attention && !a.steady && !Fuse.motion.reduced && Fuse.quality.animatedBackground
    val angle = if (turning) {
        rememberLoopClock(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = Curves.Linear)), label = "angle")
    } else {
        null
    }
    Box(
        Modifier
            .size(Size.touch)
            .fuseClickable(shape = CircleShape, role = Role.Button, onClickLabel = a.label, onClick = a.onClick)
            .semantics { contentDescription = a.label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Size.chip)
                .drawWithCache {
                    val stroke = Size.track.toPx() * 0.6f
                    val inset = stroke / 2
                    val arc = GSize(size.width - stroke, size.height - stroke)
                    val track = Stroke(stroke)
                    val line = Stroke(stroke, cap = StrokeCap.Round)
                    onDrawBehind {
                        drawArc(c.text.copy(alpha = 0.14f), 0f, 360f, false, Offset(inset, inset), arc, style = track)
                        when {
                            a.progress != null -> drawArc(c.accent, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = line)
                            !a.attention && !a.steady -> drawArc(c.accent, angle?.value ?: -90f, 90f, false, Offset(inset, inset), arc, style = line)
                        }
                        if (a.attention) drawCircle(c.accent, radius = Size.dot.toPx() / 2, center = Offset(size.width - Size.dot.toPx() * 0.75f, Size.dot.toPx() * 0.75f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(a.icon, size = Size.iconS, tint = c.text)
        }
    }
}

/**
 * The shape the line's items highlight in: a pill [Space.xxs] inside the item's touch target, so the
 * outline of a focused tab clears the accent bar under its label. [insetX] also steps the sides in
 * (round icon buttons). Sharp themes keep their own small corner instead of a pill.
 */
private class HudShape(val insetX: Boolean, val corner: Dp? = null) : Shape {
    override fun createOutline(size: GSize, layoutDirection: LayoutDirection, density: Density): Outline {
        val inset = with(density) { Space.xxs.toPx() }
        val x = if (insetX) inset else 0f
        val h = size.height - inset * 2
        val r = corner?.let { with(density) { it.toPx() } }?.coerceAtMost(h / 2) ?: (h / 2)
        return Outline.Rounded(RoundRect(x, inset, size.width - x, size.height - inset, CornerRadius(r)))
    }

    override fun equals(other: Any?) = other is HudShape && other.insetX == insetX && other.corner == corner

    override fun hashCode() = insetX.hashCode() * 31 + (corner?.hashCode() ?: 0)
}

@Composable
private fun rememberHudShape(insetX: Boolean): HudShape {
    val geometry = Fuse.geometry
    val corner = if (geometry.family == CornerFamily.SHARP) geometry.control else null
    return remember(insetX, corner) { HudShape(insetX, corner) }
}

/**
 * Controller focus on a line item: a quiet fill and an outline in the focus colour, in [shape],
 * fading in and out with [focus] (0..1).
 */
private fun Modifier.hudFocus(shape: Shape, focus: () -> Float, fill: Color, ring: Color): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val rect = (outline as? Outline.Rounded)?.roundRect
    val sw = Size.focusStroke.toPx()
    val stroke = Stroke(sw)
    onDrawBehind {
        val f = focus()
        if (f <= 0.01f || rect == null) return@onDrawBehind
        val r = CornerRadius(rect.topLeftCornerRadius.x)
        drawRoundRect(fill, Offset(rect.left, rect.top), GSize(rect.width, rect.height), r, alpha = f)
        drawRoundRect(
            ring,
            Offset(rect.left + sw / 2, rect.top + sw / 2),
            GSize(rect.width - sw, rect.height - sw),
            CornerRadius((r.x - sw / 2).coerceAtLeast(0f)),
            alpha = f,
            style = stroke,
        )
    }
}

/** A round icon button in the top line: the outline shows controller focus, full colour shows it is open. */
@Composable
private fun HudIconButton(icon: ImageVector, label: String, focused: Boolean, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = rememberHudShape(insetX = true)
    val focus by fuselineFloat(if (focused) 1f else 0f, motion.tween(Durations.FAST), label = "hudbtn")
    val tint by fuselineColor(if (focused || active) c.text else c.textMuted, motion.tween(Durations.FAST), label = "hudbtnTint")
    val fill = c.text.copy(alpha = if (c.isDark) 0.12f else 0.08f)
    Box(
        modifier
            .size(Size.touch)
            .fuseClickable(shape = shape, role = Role.Button, onClickLabel = label, onClick = onClick)
            .hudFocus(shape, { focus }, fill, c.focus)
            .semantics {
                contentDescription = label
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = Size.iconM, tint = tint)
    }
}

@Composable
private fun Tab(label: String, icon: ImageVector, selected: Boolean, focused: Boolean, showLabel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = rememberHudShape(insetX = false)
    val tint by fuselineColor(if (selected || focused) c.text else c.textMuted, motion.tween(Durations.FAST), label = "tab")
    val focus by fuselineFloat(if (focused) 1f else 0f, motion.tween(Durations.FAST), label = "tabFocus")
    val fill = c.text.copy(alpha = if (c.isDark) 0.12f else 0.08f)
    Row(
        modifier
            .height(Size.touch)
            .fuseClickable(shape = shape, role = Role.Tab, onClickLabel = label, onClick = onClick)
            .hudFocus(shape, { focus }, fill, c.focus)
            .semantics { this.selected = selected }
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = Size.iconM, tint = tint)
        Appear(
            visible = showLabel,
            enter = expandHorizontally(motion.tween(Durations.BASE)) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.tween(Durations.FAST)) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s))
                FText(label, Fuse.type.bodyStrong, color = tint, maxLines = 1)
            }
        }
    }
}

/**
 * A soft band of the room's colour behind the top line, so its tabs, buttons and status read over
 * any art in every theme: dark art under a bright theme's dark text, or bright art under a dark
 * theme's white text. It is strongest behind the line and gone a little below it, and it eases in
 * and out with [art] (whether a game's or system's art fills the room). Content scrolled up under
 * the line fades into it the same way.
 */
@Composable
fun HudScrim(art: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    // Bright themes put dark text over art that is often dark at the top, so they need more.
    val full = if (c.isDark) 0.62f else 0.9f
    val strength by fuselineFloat(if (art) full else full * 0.5f, Fuse.motion.fade(Durations.SLOW), label = "hudScrim")
    val ink = c.ink
    Spacer(
        modifier
            .fillMaxWidth()
            .height(Size.hudHeight + Space.xxl)
            .drawWithCache {
                // Full behind the line, then a long eased tail, so it never reads as a bar.
                val brush = Brush.verticalGradient(
                    0f to ink,
                    0.4f to ink.copy(alpha = 0.95f),
                    0.55f to ink.copy(alpha = 0.72f),
                    0.7f to ink.copy(alpha = 0.4f),
                    0.85f to ink.copy(alpha = 0.14f),
                    1f to ink.copy(alpha = 0f),
                )
                onDrawBehind { drawRect(brush, alpha = strength) }
            },
    )
}

/**
 * Fuse's mark: a squircle frame with a lit fuse line running into it and a spark at the end, drawn
 * from the brand art ([BrandArt]).
 */
@Composable
fun FuseMark(modifier: Modifier = Modifier, color: Color = Fuse.colors.text, spark: Color = Fuse.colors.accent) {
    Canvas(modifier) {
        drawBrandMark(Offset.Zero, size.width, color, spark)
    }
}

/** Current local time, updated on the minute (not every second) to keep the idle home screen idle. */
@Composable
fun rememberClockText(clock24h: Boolean): String {
    var text by remember { mutableStateOf(formatTime(clock24h)) }
    LaunchedEffect(clock24h) {
        while (true) {
            text = formatTime(clock24h)
            val now = Clock.System.now().toEpochMilliseconds()
            delay(60_000 - now % 60_000 + 50)
        }
    }
    return text
}

fun formatTime(clock24h: Boolean): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return if (clock24h) {
        "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    } else {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        "$h:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "AM" else "PM"}"
    }
}

fun formatDate(): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val day = t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$day, $month ${t.day}"
}

/** Fades the left and/or right edge out while there is more to scroll that way. */
private fun Modifier.fadeSides(fadeLeft: () -> Boolean, fadeRight: () -> Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = size.width
        if (w <= 0f) return@drawWithContent
        val f = (28.dp.toPx() / w).coerceIn(0f, 0.3f)
        val left = fadeLeft()
        val right = fadeRight()
        if (!left && !right) return@drawWithContent
        drawRect(
            Brush.horizontalGradient(
                0f to (if (left) Color.Transparent else Color.Black),
                f to Color.Black,
                1f - f to Color.Black,
                1f to (if (right) Color.Transparent else Color.Black),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
