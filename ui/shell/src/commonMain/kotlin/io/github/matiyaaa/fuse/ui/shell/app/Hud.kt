package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.SwapTransform
import io.github.matiyaaa.fuse.ui.fuseline.expandHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.scaleIn
import io.github.matiyaaa.fuse.ui.fuseline.scaleOut
import io.github.matiyaaa.fuse.ui.fuseline.shrinkHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlin.time.Clock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The buttons at the end of the tab line, reachable with the stick after the last tab. */
enum class HudButton {
    /** Downloads, while anything moves (or just failed): the one way into every transfer. */
    DOWNLOADS,
    SEARCH,
    SETTINGS,

    /** The profile in use (Fuse Sync), beside the status: opens "Who's playing?". */
    PROFILE,

    /** The status at the far right (Wi-Fi, battery, clock), which opens the quick menu. */
    STATUS,
}

/** The person playing here, for the top line (Fuse Sync's profile in use). */
@androidx.compose.runtime.Immutable
data class HudProfile(val name: String, val avatar: String)

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
    /** Who is playing here, when Fuse Sync is in use: their avatar sits with the status. */
    profile: HudProfile? = null,
    /** Transfers moving (or failed), for the Downloads button; null hides it. */
    downloads: HudDownloads? = null,
) {
    val time = rememberClockText(clock24h)
    val anchors = remember { HudAnchors() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Size.hudHeight)
            .onGloballyPositioned { anchors.setLine(it.positionInRoot().x) },
    ) {
        // Room for every label: about 120dp per tab plus the mark, the LB/RB glyphs, buttons and status.
        val labels = maxWidth > 120.dp * destinations.size + 430.dp
        // Narrow screens keep the tabs whole by giving up status first: Wi-Fi and battery, then the
        // clock (a phone held upright shows its own).
        // The person playing is named beside their avatar only where nothing else needs the room.
        val roomForName = maxWidth > 1100.dp
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
            // The room the tabs have: too little for the open tab's name (a small second screen with
            // the menus on it), and every tab is its icon, never a name cut short.
            var room by remember { mutableIntStateOf(Int.MAX_VALUE) }
            val nameFits = with(LocalDensity.current) { room.toDp() } >= ACTIVE_NAME_ROOM
            Row(Modifier.weight(1f).onSizeChanged { room = it.width }, verticalAlignment = Alignment.CenterVertically) {
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
                val activeX = remember { floatArrayOf(0f) }
                // Scrolled by touch, then left alone: after a while the tabs glide back so the place
                // you are on is centred again, as moving with the controller leaves them.
                var touchedAt by remember { mutableStateOf(0L) }
                LaunchedEffect(touchedAt) {
                    if (touchedAt == 0L) return@LaunchedEffect
                    delay(TABS_SETTLE_MS)
                    val centred = (activeX[0] - (viewport - activeWidth.intValue) / 2f).toInt().coerceIn(0, scroll.maxValue)
                    if (centred != scroll.value) scroll.fuselineScrollTo(centred)
                }
                // Again once its label has opened, and with room to spare so the fade never covers it.
                LaunchedEffect(active, labels, activeWidth.intValue, destinations, viewport) {
                    val requester = active?.let { requesters[it] } ?: return@LaunchedEffect
                    requester.bringIntoView(Rect(-edge, 0f, activeWidth.intValue + edge, 1f))
                }
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .onSizeChanged { viewport = it.width }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    if (e.changes.any { it.pressed }) touchedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
                                }
                            }
                        }
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
                            sections.label(d), sections.icon(d), selected = d == active, focused = tabsFocused && focusedButton == null && d == active, showLabel = labels || (d == active && nameFits),
                            modifier = Modifier
                                .bringIntoViewRequester(requesters.getValue(d))
                                .onPlaced {
                                    x = it.positionInParent().x
                                    w = it.size.width
                                    if (d == active) {
                                        activeWidth.intValue = it.size.width
                                        activeX[0] = it.positionInParent().x
                                    }
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
            if (downloads != null) {
                HudDownloadsButton(
                    downloads,
                    focused = tabsFocused && focusedButton == HudButton.DOWNLOADS,
                    active = activeButton == HudButton.DOWNLOADS,
                    modifier = Modifier.anchor(anchors, HudButton.DOWNLOADS),
                ) { onButton(HudButton.DOWNLOADS) }
                Spacer(Modifier.width(Space.xxs))
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
            if (statusRoom != StatusRoom.NONE || profile != null) {
                // A hairline keeps the things you open apart from the things you read.
                Spacer(Modifier.width(Space.xs))
                Box(Modifier.width(Size.divider).height(Size.iconM).background(Fuse.colors.hairlineStrong))
                Spacer(Modifier.width(Space.xs))
            }
            if (statusRoom != StatusRoom.NONE) {
                val shape = rememberHudShape(insetX = false)
                val statusFocus by fuselineFloat(if (tabsFocused && focusedButton == HudButton.STATUS) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "statusFocus")
                // With someone to switch to at the far end, the status draws tighter to make room.
                val tight = profile != null
                Box(
                    Modifier
                        .height(Size.touch)
                        .fuseClickable(shape = shape, scale = false, role = Role.Button, onClickLabel = "Quick menu", onClick = onStatusClick)
                        .hudFocus(shape, { statusFocus }, Fuse.colors.text.copy(alpha = if (Fuse.colors.isDark) 0.12f else 0.08f), Fuse.colors.focus)
                        .padding(horizontal = if (tight) Space.s else Space.m),
                    contentAlignment = Alignment.Center,
                ) {
                    val all = statusRoom == StatusRoom.ALL
                    StatusCluster(
                        if (all) status else status.copy(batteryPercent = null),
                        time,
                        showWifi = showWifi && all,
                        showBluetooth = showBluetooth && all,
                        compact = tight,
                    )
                }
            }
            if (profile != null) {
                Spacer(Modifier.width(Space.xxs))
                HudProfileAvatar(
                    profile,
                    focused = tabsFocused && focusedButton == HudButton.PROFILE,
                    modifier = Modifier.anchor(anchors, HudButton.PROFILE),
                ) { onButton(HudButton.PROFILE) }
            }
        }
        val key: Any? = activeButton ?: active
        if (key != null) ActiveMarker(anchors, key)
    }
}

/** How long tabs scrolled by touch stay put before gliding back to the place you are on. */
private const val TABS_SETTLE_MS = 10_000L

/** How much of the status cluster the line has room for. */
private enum class StatusRoom { NONE, CLOCK, ALL }

/** Below this width the clock goes too (phones held upright show their own); below [COMPACT] only it stays. */
private val NARROW = 480.dp
private val COMPACT = 720.dp

/**
 * Where each tab and button of the line sits (its centre, across the window), so the active marker
 * can travel between them. Layout writes it and drawing reads it, in the same frame: a plain map
 * (no recomposition), with [version] read by the marker's drawing alone so a move redraws it.
 */
@Stable
private class HudAnchors {
    val centres = HashMap<Any, Float>()
    var lineX = 0f
    var version by mutableIntStateOf(0)

    fun setLine(x: Float) {
        if (lineX != x) {
            lineX = x
            version++
        }
    }

    fun set(key: Any, x: Float) {
        if (centres[key] != x) {
            centres[key] = x
            version++
        }
    }
}

private fun Modifier.anchor(anchors: HudAnchors, key: Any): Modifier = onGloballyPositioned { c ->
    // Unclipped, so a tab half scrolled out of the carousel still reports its true centre.
    anchors.set(key, c.localToRoot(Offset(c.size.width / 2f, 0f)).x)
}

/**
 * The accent bar under the active place. When the place changes it glides there (to a tab, Search
 * or Settings): the leading edge first, the trailing one following, so it stretches a little toward
 * where it goes, never more than three bars long however far it travels.
 *
 * What moves is only how far along the way it is; both ends of the way are read from the line's
 * layout as it is drawn. So the bar can never trail a tab that moves while it travels (the carousel
 * scrolling the new tab into view, its label opening) and, once there, sits exactly under its tab
 * in every frame, with no catching up. It snaps under Reduced motion.
 */
@Composable
private fun BoxScope.ActiveMarker(anchors: HudAnchors, key: Any) {
    val motion = Fuse.motion
    val accent = Fuse.colors.accent
    // Where the way starts: the place it was under (followed live), or the point it had reached
    // when a new place was chosen mid-way.
    val from = remember { arrayOf<Any?>(key) }
    val fromX = remember { floatArrayOf(Float.NaN) }
    val shown = remember { arrayOf<Any?>(key) }
    val lead = remember { FuselineValue(1f) }
    val trail = remember { FuselineValue(1f) }
    val drawn = remember { floatArrayOf(Float.NaN) }
    LaunchedEffect(key) {
        val before = shown[0]
        shown[0] = key
        if (before == key) return@LaunchedEffect
        // Mid-way: start from the point reached, so nothing jumps.
        val moving = lead.value < 1f || trail.value < 1f
        from[0] = if (moving) null else before
        fromX[0] = drawn[0]
        lead.snapTo(0f)
        trail.snapTo(0f)
        coroutineScope {
            launch { lead.animateTo(1f, motion.glide()) }
            launch { trail.animateTo(1f, motion.glideTrail()) }
        }
    }
    Spacer(
        Modifier.matchParentSize().drawBehind {
            anchors.version // Redrawn whenever a place moves.
            val target = anchors.centres[key]?.minus(anchors.lineX) ?: return@drawBehind
            val start0 = from[0]?.let { anchors.centres[it]?.minus(anchors.lineX) } ?: fromX[0].takeIf { !it.isNaN() } ?: target
            val l = lead.value
            val t = trail.value
            val atLead = start0 + (target - start0) * l
            val atTrail = start0 + (target - start0) * t
            drawn[0] = (atLead + atTrail) / 2
            val half = Size.sparkWidth.toPx() / 2
            var start: Float
            var end: Float
            if (target >= start0) {
                start = atTrail - half
                end = atLead + half
            } else {
                start = atLead - half
                end = atTrail + half
            }
            val longest = Size.sparkWidth.toPx() * 3
            if (end - start > longest) {
                // Led by the edge that is travelling.
                if (target >= start0) start = end - longest else end = start + longest
            }
            val h = Size.sparkHeight.toPx()
            val top = size.height / 2 + Size.iconM.toPx() / 2 + Space.xs.toPx()
            drawRoundRect(accent, Offset(start, top), GSize(end - start, h), CornerRadius(h / 2))
        },
    )
}

/**
 * What the Downloads button shows: how many transfers move, whether any go up, overall progress
 * where the sizes are known, and failures waiting to be looked at.
 */
data class HudDownloads(val active: Int, val uploading: Boolean, val progress: Float?, val failed: Int, val waiting: Int) {
    val label: String get() = buildString {
        append("Downloads")
        if (active > 0) append(": $active moving")
        if (uploading) append(", uploads among them")
        if (failed > 0) append(", $failed failed")
        append(". Select to open")
    }
}

/**
 * The Downloads button: an arrow in a ring that fills with the overall progress (turning while sizes
 * are unknown), a small count when more than one moves, an up arrow when uploads are among them, and
 * an accent dot when something failed. Reached with the stick like Search and Settings.
 */
@Composable
private fun HudDownloadsButton(d: HudDownloads, focused: Boolean, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val sweep by fuselineFloat((d.progress ?: 0f).coerceIn(0f, 1f), Fuse.motion.value(), label = "downloads")
    val moving = d.active > 0
    val turning = moving && d.progress == null && !Fuse.motion.reduced && Fuse.quality.animatedBackground
    val angle = if (turning) rememberLoopClock(label = "dlspin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = Curves.Linear)), label = "dlangle") else null
    val shape = rememberHudShape(insetX = true)
    val focus by fuselineFloat(if (focused) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "dlFocus")
    Box(
        modifier
            .size(Size.touch)
            .fuseClickable(shape = shape, scale = false, role = Role.Button, onClickLabel = d.label, onClick = onClick)
            .hudFocus(shape, { maxOf(focus, if (active) 0.6f else 0f) }, c.text.copy(alpha = if (c.isDark) 0.12f else 0.08f), c.focus)
            .semantics { contentDescription = d.label },
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
                        if (moving) drawArc(c.text.copy(alpha = 0.14f), 0f, 360f, false, Offset(inset, inset), arc, style = track)
                        when {
                            moving && d.progress != null -> drawArc(c.accent, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = line)
                            moving -> drawArc(c.accent, angle?.value ?: -90f, 90f, false, Offset(inset, inset), arc, style = line)
                        }
                        if (d.failed > 0) drawCircle(c.danger, radius = Size.dot.toPx() / 2, center = Offset(size.width - Size.dot.toPx() * 0.75f, Size.dot.toPx() * 0.75f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(FuseIcons.Download, size = Size.iconS, tint = c.text)
            if (d.uploading) {
                Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.ArrowUp, size = 10.dp, tint = c.onAccent)
                }
            }
        }
        if (d.active > 1) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).height(16.dp).clip(CircleShape).background(c.surfaceRaised).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                FText("${d.active}", Fuse.type.numericSmall, color = c.text, maxLines = 1)
            }
        }
    }
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

/**
 * The person playing here, at the far end of the line: their avatar alone, in a ring of the accent
 * that lights when the stick reaches it. Switching profile turns the old face out and the new one in.
 */
@Composable
private fun HudProfileAvatar(profile: HudProfile, focused: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val focus by fuselineFloat(if (focused) 1f else 0f, motion.tween(Durations.FAST), label = "hudProfile")
    val ring = c.accent
    Box(
        modifier
            .size(Size.touch)
            .fuseClickable(shape = CircleShape, role = Role.Button, onClickLabel = "Switch profile", onClick = onClick)
            .semantics { contentDescription = "Playing as ${profile.name}. Switch profile" }
            .drawBehind {
                val r = size.minDimension / 2f
                // A soft halo while focused, and a ring that is always there, brighter on focus.
                drawCircle(ring.copy(alpha = 0.22f * focus), r)
                drawCircle(ring.copy(alpha = 0.45f + 0.55f * focus), r - 2.dp.toPx(), style = Stroke((1.5f + focus).dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Swap(
            profile,
            transitionSpec = {
                SwapTransform(
                    fadeIn(motion.fade(Durations.BASE)) + scaleIn(motion.tween(Durations.BASE), initialScale = 0.6f),
                    fadeOut(motion.fade(Durations.FAST)) + scaleOut(motion.tween(Durations.FAST), targetScale = 1.2f),
                )
            },
            contentAlignment = Alignment.Center,
            label = "hudProfileSwap",
            contentKey = { it.avatar + it.name },
        ) { p ->
            io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar(p.avatar, Size.touch - 12.dp)
        }
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
        FuseIcon(icon, tint = { tint }, size = Size.iconM)
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
        FuseIcon(icon, tint = { tint }, size = Size.iconM)
        Appear(
            visible = showLabel,
            // The label opens out of its icon, reading from its first letter.
            enter = expandHorizontally(motion.tween(Durations.BASE), expandFrom = Alignment.Start) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.tween(Durations.FAST), shrinkTowards = Alignment.Start) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s))
                FText(label, Fuse.type.bodyStrong, color = { tint }, maxLines = 1)
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

/** The least room the tabs need to show the open tab's name beside the others' icons. */
private val ACTIVE_NAME_ROOM = 200.dp
