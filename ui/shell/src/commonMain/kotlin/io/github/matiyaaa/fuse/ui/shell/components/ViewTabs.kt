package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdgesHorizontal
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberGlide
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/** One tab of [ViewTabs]. [warn] tints it as a warning; [badge] is a small count shown after the label. */
data class ViewTab(
    val label: String,
    val icon: ImageVector? = null,
    val warn: Boolean = false,
    val badge: String? = null,
)

/**
 * Views of one list (All, Favourites; Pinned, All apps) as text tabs with an accent underline that
 * glides to the active one: its leading edge runs ahead and the trailing edge follows, so it reads as
 * one mark moving and settles to the new tab's width. The underline sits under the tab with a little
 * air, so a focus outline never runs through it.
 *
 * Every tab may carry a count ([ViewTab.badge]) in tabular figures: the active tab's in a lit pill,
 * the others' quiet. Controller focus outlines a tab without filling it, so the active one always
 * reads; the mouse gets a soft hover and every tab a press. The gutter sits inside the scrolling row,
 * so the first tab's outline is never cut at the screen edge, and the focused tab is always scrolled
 * fully into view.
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
    // Where each tab sits in the row (x and width in pixels), for the underline and for scrolling.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val requesters = remember { mutableMapOf<Int, BringIntoViewRequester>() }
    val target = bounds[active]
    // The underline spans the tab's content (its label and count), not its padding.
    val inset = Space.m
    val glide = if (target != null) {
        with(density) { rememberGlide(target.first.toDp() + inset, (target.first + target.second).toDp() - inset) }
    } else {
        null
    }
    val lineAlpha by animateFloatAsState(if (target != null) 1f else 0f, motion.fade(Durations.FAST), label = "tab line")
    val edge = with(density) { EDGE_ROOM.toPx() }
    val shown = focused ?: active
    LaunchedEffect(shown, bounds[shown]) {
        val w = bounds[shown]?.second ?: return@LaunchedEffect
        requesters[shown]?.bringIntoView(Rect(-edge, 0f, w + edge, 1f))
    }
    val accent = c.accent
    val startPad = gutter - Space.m
    Row(
        modifier
            .fadingEdgesHorizontal(start = scroll.value > 0, end = scroll.value < scroll.maxValue, width = Space.xxl)
            .horizontalScroll(scroll)
            // Drawn across the padded row, so the underline sits in the room under the tabs.
            .drawBehind {
                val g = glide ?: return@drawBehind
                val h = Size.sparkHeight.toPx()
                val left = startPad.toPx() + g.start.toPx()
                val width = (g.end.toPx() - g.start.toPx()).coerceAtLeast(h)
                drawRoundRect(
                    accent,
                    topLeft = Offset(left, size.height - h),
                    size = androidx.compose.ui.geometry.Size(width, h),
                    cornerRadius = CornerRadius(h / 2),
                    alpha = lineAlpha,
                )
            }
            .padding(start = startPad, end = Space.s, top = LINE_ROOM, bottom = LINE_ROOM),
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, tab ->
            val requester = requesters.getOrPut(i) { BringIntoViewRequester() }
            TabLabel(
                tab,
                active = i == active,
                focused = i == focused,
                modifier = Modifier
                    .bringIntoViewRequester(requester)
                    .onPlaced { bounds[i] = it.positionInParent().x to it.size.width.toFloat() },
                onClick = { onSelect(i) },
            )
        }
    }
}

@Composable
private fun TabLabel(tab: ViewTab, active: Boolean, focused: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val tint by animateColorAsState(
        when {
            tab.warn -> c.warning.copy(alpha = if (active || focused) 1f else WARN_REST)
            active || focused -> c.text
            else -> c.textMuted
        },
        motion.tween(Durations.FAST),
        label = "tab tint",
    )
    val bg by animateColorAsState(if (focused) c.text.copy(alpha = FOCUS_FILL) else Color.Transparent, motion.tween(Durations.FAST), label = "tab bg")
    val ring by animateFloatAsState(if (focused) 1f else 0f, motion.focusSpring(), label = "tab ring")
    val focus = c.focus
    Row(
        modifier
            .drawBehind {
                if (bg.alpha > 0f) drawRoundRect(bg, cornerRadius = CornerRadius(size.height / 2))
                if (ring > 0.01f) {
                    val w = Size.focusStroke.toPx()
                    drawRoundRect(
                        focus,
                        topLeft = Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius((size.height - w) / 2),
                        alpha = ring.coerceIn(0f, 1f),
                        style = Stroke(w),
                    )
                }
            }
            .fuseClickable(shape = PillShape, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = focused }
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.icon != null) {
            FuseIcon(tab.icon, size = Size.iconS, tint = tint)
            Spacer(Modifier.width(Space.s - Space.xxs))
        }
        FText(tab.label, Fuse.type.titleSmall, color = tint, maxLines = 1)
        AnimatedVisibility(
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
    val fill by animateColorAsState(c.text.copy(alpha = if (emphasised) COUNT_FILL_ON else COUNT_FILL), motion.tween(Durations.FAST), label = "count fill")
    val tint by animateColorAsState(if (emphasised) c.text else c.textMuted, motion.tween(Durations.FAST), label = "count tint")
    Box(
        modifier
            .drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2)) }
            .padding(horizontal = Space.s - Space.xxs, vertical = Space.xxs),
        contentAlignment = Alignment.Center,
    ) {
        FText(text, Fuse.type.numericSmall, color = tint, maxLines = 1)
    }
}

/** Room above and below the tabs: the underline lives in the bottom one, clear of the focus outline. */
private val LINE_ROOM = Space.xs + Size.sparkHeight

/** Room kept beside the focused tab when it is scrolled into view, so the edge fade never covers it. */
private val EDGE_ROOM = 48.dp

/** Fill behind the focused tab, under its outline. */
private const val FOCUS_FILL = 0.1f

/** A warning tab at rest is a little quieter than when it is active or focused. */
private const val WARN_REST = 0.8f

/** Count pill fills, quiet and lit. */
private const val COUNT_FILL = 0.06f
private const val COUNT_FILL_ON = 0.12f
