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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdgesHorizontal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
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
 * slides to the active one. Controller focus outlines a tab without filling it, so the active one
 * always reads. The gutter sits inside the scrolling row, so the first tab's outline is never cut at
 * the screen edge, and the focused tab is always scrolled fully into view.
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
    val scroll = rememberScrollState()
    // Where each tab sits in the row (x and width in pixels), for the underline and for scrolling.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val requesters = remember { mutableMapOf<Int, BringIntoViewRequester>() }
    val target = bounds[active]
    val lineX by animateFloatAsState(target?.first ?: 0f, motion.followSpring(), label = "tab line x")
    val lineW by animateFloatAsState(target?.second ?: 0f, motion.followSpring(), label = "tab line w")
    val lineAlpha by animateFloatAsState(if (target != null) 1f else 0f, motion.fade(Durations.FAST), label = "tab line")
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val shown = focused ?: active
    LaunchedEffect(shown, bounds[shown]) {
        val w = bounds[shown]?.second ?: return@LaunchedEffect
        requesters[shown]?.bringIntoView(Rect(-edge, 0f, w + edge, 1f))
    }
    Row(
        modifier
            .fadingEdgesHorizontal(start = scroll.value > 0, end = scroll.value < scroll.maxValue, width = 32.dp)
            .horizontalScroll(scroll)
            .padding(start = gutter - Space.m, end = Space.s, top = Space.xxs, bottom = Space.xxs)
            .drawBehind {
                if (lineW <= 0f) return@drawBehind
                val h = 3.dp.toPx()
                val inset = Space.m.toPx()
                drawRoundRect(
                    c.accent.copy(alpha = lineAlpha),
                    topLeft = Offset(lineX + inset, size.height - h),
                    size = androidx.compose.ui.geometry.Size((lineW - inset * 2).coerceAtLeast(h), h),
                    cornerRadius = CornerRadius(h / 2),
                )
            },
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
            tab.warn -> c.warning.copy(alpha = if (active || focused) 1f else 0.75f)
            active || focused -> c.text
            else -> c.text.copy(alpha = 0.5f)
        },
        motion.tween(Durations.FAST),
        label = "tab tint",
    )
    val bg by animateColorAsState(if (focused) c.text.copy(alpha = 0.1f) else Color.Transparent, motion.tween(Durations.FAST), label = "tab bg")
    Row(
        modifier
            .clip(PillShape)
            .background(bg)
            .drawBehind {
                if (focused) drawRoundRect(c.focus.copy(alpha = 0.85f), cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.5.dp.toPx()))
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { this.selected = focused }
            .padding(horizontal = Space.m, vertical = Space.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.icon != null) {
            FuseIcon(tab.icon, size = 16.dp, tint = tint)
            Spacer(Modifier.width(Space.xs + 2.dp))
        }
        FText(tab.label, Fuse.type.titleSmall, color = tint, maxLines = 1)
        AnimatedVisibility(
            visible = tab.badge != null,
            enter = expandHorizontally(motion.tween(Durations.BASE)) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.tween(Durations.FAST)) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s))
                CountPill(tab.badge.orEmpty())
            }
        }
    }
}

/** A quiet pill with a number (or a short word) in it. */
@Composable
fun CountPill(text: String) {
    val c = Fuse.colors
    Box(
        Modifier
            .clip(PillShape)
            .background(c.text.copy(alpha = 0.1f))
            .padding(horizontal = Space.s, vertical = Space.xxs),
    ) {
        FText(text, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}
