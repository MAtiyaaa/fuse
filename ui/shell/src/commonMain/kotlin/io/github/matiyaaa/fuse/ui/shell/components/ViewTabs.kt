package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdgesHorizontal
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.expandHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.rememberGlide
import io.github.matiyaaa.fuse.ui.fuseline.shrinkHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.tween

/** One tab of [ViewTabs]. [warn] tints it as a warning; [badge] is a small count shown after the label. */
data class ViewTab(
    val label: String,
    val icon: ImageVector? = null,
    val warn: Boolean = false,
    val badge: String? = null,
)

/**
 * Views of one list (All, Favourites; Pinned, All apps) as text tabs, marked the way the top line
 * marks its sections: the active tab's name in full colour with the short accent bar under it. The
 * bar glides from tab to tab (its leading edge first, the other following, never stretching longer
 * than three bars), so a change of view reads as one mark moving.
 *
 * Every tab may carry a count ([ViewTab.badge]) in tabular figures: the active tab's in a lit pill,
 * the others' quiet. Controller focus is the top line's too, a quiet fill and an outline that never
 * hides which tab is active; the mouse gets a soft hover and every tab a press. The gutter sits
 * inside the scrolling row, so the first tab's outline is never cut at the screen edge, and the
 * focused tab is always scrolled fully into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewTabs(
    items: List<ViewTab>,
    active: Int,
    focused: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = Space.gutter,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    // Where each tab sits in the row (x and width in pixels), for scrolling, and the centre of its
    // name (icon and label, not its count), for the bar.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val names = remember { mutableStateMapOf<Int, Float>() }
    val requesters = remember { mutableMapOf<Int, BringIntoViewRequester>() }
    val tab = bounds[active]
    val name = names[active]
    val centre = if (tab != null && name != null) with(density) { (tab.first + name).toDp() } else null
    val half = Size.sparkWidth / 2
    val glide = centre?.let { rememberGlide(it - half, it + half, active) }
    val lineAlpha by fuselineFloat(if (centre != null) 1f else 0f, motion.fade(Durations.FAST), label = "tab line")
    val edge = with(density) { EDGE_ROOM.toPx() }
    val shown = focused ?: active
    LaunchedEffect(shown, bounds[shown]) {
        val w = bounds[shown]?.second ?: return@LaunchedEffect
        requesters[shown]?.bringIntoView(Rect(-edge, 0f, w + edge, 1f))
    }
    val accent = c.accent
    // The first tab's name lines up with the gutter; its outline reaches into it.
    val startPad = gutter - TAB_PAD
    Row(
        modifier
            .fadingEdgesHorizontal(start = scroll.value > 0, end = scroll.value < scroll.maxValue, width = Space.xxl)
            .horizontalScroll(scroll)
            // Drawn across the padded row, so the bar sits in the room under the tabs.
            .drawBehind {
                val g = glide ?: return@drawBehind
                val target = centre ?: return@drawBehind
                val h = Size.sparkHeight.toPx()
                val pad = startPad.toPx()
                var start = g.start.toPx()
                var end = g.end.toPx()
                val longest = Size.sparkWidth.toPx() * MAX_STRETCH
                if (end - start > longest) {
                    // Led by the edge that is travelling.
                    if (target.toPx() > (start + end) / 2) start = end - longest else end = start + longest
                }
                drawRoundRect(
                    accent,
                    topLeft = Offset(pad + start, size.height - h),
                    size = androidx.compose.ui.geometry.Size((end - start).coerceAtLeast(h), h),
                    cornerRadius = CornerRadius(h / 2),
                    alpha = lineAlpha,
                )
            }
            .padding(start = startPad, end = Space.s, top = LINE_ROOM, bottom = LINE_ROOM),
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val requester = requesters.getOrPut(i) { BringIntoViewRequester() }
            TabLabel(
                item,
                active = i == active,
                focused = i == focused,
                modifier = Modifier
                    .bringIntoViewRequester(requester)
                    .onPlaced { bounds[i] = it.positionInParent().x to it.size.width.toFloat() },
                onName = { names[i] = it },
                onClick = { onSelect(i) },
            )
        }
    }
}

@Composable
private fun TabLabel(
    tab: ViewTab,
    active: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onName: (Float) -> Unit,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val tint by fuselineColor(
        when {
            tab.warn -> c.warning.copy(alpha = if (active || focused) 1f else WARN_REST)
            active || focused -> c.text
            else -> c.textMuted
        },
        motion.tween(Durations.FAST),
        label = "tab tint",
    )
    val focus by fuselineFloat(if (focused) 1f else 0f, motion.tween(Durations.FAST), label = "tab focus")
    val shape = rememberLineShape()
    // The name is placed inside the tab's padding, which its own position leaves out.
    val pad = with(LocalDensity.current) { TAB_PAD.toPx() }
    Row(
        modifier
            .fuseClickable(shape = shape, role = Role.Tab, onClick = onClick)
            .lineFocus(shape, { focus }, lineFocusFill(), c.focus)
            .semantics { this.selected = focused }
            .padding(horizontal = TAB_PAD, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.onPlaced { onName(pad + it.positionInParent().x + it.size.width / 2f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.icon != null) {
                FuseIcon(tab.icon, size = Size.iconS, tint = tint)
                Spacer(Modifier.width(Space.s - Space.xxs))
            }
            FText(tab.label, Fuse.type.titleSmall, color = tint, maxLines = 1)
        }
        Appear(
            visible = tab.badge != null,
            enter = expandHorizontally(motion.enter(Durations.BASE)) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.exit(Durations.FAST)) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s))
                // The last count stays while the pill folds away, so it never blanks mid-exit.
                val text = remember { arrayOf("") }
                tab.badge?.let { text[0] = it }
                CountPill(text[0], emphasised = active)
            }
        }
    }
}

/**
 * A small pill with a number (or a short word) in it, in tabular figures. [emphasised] lights it
 * (the active tab's count, a heading's total); otherwise it stays quiet beside its label.
 */
@Composable
fun CountPill(text: String, modifier: Modifier = Modifier, emphasised: Boolean = true) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val fill by fuselineColor(c.text.copy(alpha = if (emphasised) COUNT_FILL_ON else COUNT_FILL), motion.tween(Durations.FAST), label = "count fill")
    val tint by fuselineColor(if (emphasised) c.text else c.textMuted, motion.tween(Durations.FAST), label = "count tint")
    Box(
        modifier
            .drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2)) }
            .padding(horizontal = Space.s - Space.xxs, vertical = Space.xxs),
        contentAlignment = Alignment.Center,
    ) {
        FText(text, Fuse.type.numericSmall, color = tint, maxLines = 1)
    }
}

/**
 * The shape tabs and header buttons highlight in, as the top line's do: a pill, or the theme's
 * small control corner in sharp themes.
 */
@Composable
internal fun rememberLineShape(): Shape {
    val geometry = Fuse.geometry
    val sharp = geometry.family == CornerFamily.SHARP
    val corner = geometry.control
    return remember(sharp, corner) { if (sharp) RoundedCornerShape(corner) else PillShape }
}

/** The quiet fill under a focused tab or header button (the top line's). */
@Composable
internal fun lineFocusFill(): Color {
    val c = Fuse.colors
    return c.text.copy(alpha = if (c.isDark) FOCUS_FILL else FOCUS_FILL_LIGHT)
}

/**
 * Controller focus on a tab or a header button, drawn as the top line draws it: [fill] and an
 * outline in [ring], in [shape], fading with [focus] (0..1). Read while drawing, so focus moves
 * never recompose the row.
 */
internal fun Modifier.lineFocus(shape: Shape, focus: () -> Float, fill: Color, ring: Color): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: (size.height / 2)
    val sw = Size.focusStroke.toPx()
    val stroke = Stroke(sw)
    onDrawBehind {
        val f = focus().coerceIn(0f, 1f)
        if (f <= 0.01f) return@onDrawBehind
        drawRoundRect(fill, cornerRadius = CornerRadius(radius), alpha = f)
        drawRoundRect(
            ring,
            topLeft = Offset(sw / 2, sw / 2),
            size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius((radius - sw / 2).coerceAtLeast(0f)),
            alpha = f,
            style = stroke,
        )
    }
}

/** Room either side of a tab's name, inside its focus outline. */
private val TAB_PAD = Space.m

/** Room above and below the tabs: the bar lives in the bottom one, clear of the focus outline. */
private val LINE_ROOM = Space.xs + Size.sparkHeight

/** Room kept beside the focused tab when it is scrolled into view, so the edge fade never covers it. */
private val EDGE_ROOM = Space.x3

/** The bar stretches to at most this many of its own lengths while it glides, as the top line's does. */
private const val MAX_STRETCH = 3f

/** Fill behind the focused tab, under its outline, in dark and light themes (the top line's). */
private const val FOCUS_FILL = 0.12f
private const val FOCUS_FILL_LIGHT = 0.08f

/** A warning tab at rest is a little quieter than when it is active or focused. */
private const val WARN_REST = 0.8f

/** Count pill fills, quiet and lit. */
private const val COUNT_FILL = 0.06f
private const val COUNT_FILL_ON = 0.12f
