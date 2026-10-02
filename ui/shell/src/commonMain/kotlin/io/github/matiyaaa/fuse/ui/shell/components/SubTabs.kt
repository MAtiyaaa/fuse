package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tabs inside a section (Addons' Cartridge and Store) that fold away as the page under them
 * scrolls. The page reports whether it has left its top ([ReportScroll]); [extraTop] is the room the
 * full tabs take beyond the folded ones, which the page keeps above its first row so nothing starts
 * under them.
 */
@Stable
class SubTabsState(val extraTop: Dp) {
    var collapsed by mutableStateOf(false)
}

/** The tabs over the page being shown, when it is inside some; null elsewhere. */
val LocalSubTabs = staticCompositionLocalOf<SubTabsState?> { null }

/** The room a page keeps above its first row for the tabs over it (none without tabs). */
@Composable
fun subTabsRoom(): Dp = LocalSubTabs.current?.extraTop ?: 0.dp

/** Tells the tabs over this page (if any) whether [list] has scrolled away from its top. */
@Composable
fun ReportScroll(list: LazyListState) {
    val tabs = LocalSubTabs.current ?: return
    LaunchedEffect(list, tabs) {
        snapshotFlow { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > FOLD_AFTER_PX }
            .distinctUntilChanged()
            .collect { tabs.collapsed = it }
    }
    // The next page starts at its top, with the tabs open.
    DisposableEffect(tabs) { onDispose { tabs.collapsed = false } }
}

private const val FOLD_AFTER_PX = 12

/** One folded tab: its icon, and whether it has a count to show (as a dot). */
data class CompactTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val badge: String? = null)

/**
 * The tabs folded: their icons only, in a small pill at the top left. The active one is lit in the
 * accent; a tab with a count carries a dot. A tap switches, like the full tabs.
 */
@Composable
fun CompactTabs(items: List<CompactTab>, active: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Row(
        modifier
            .clip(PillShape)
            .background(c.surfaceRaised.copy(alpha = 0.92f))
            .border(1.dp, c.hairline, PillShape)
            .padding(PAD),
        horizontalArrangement = Arrangement.spacedBy(PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, tab ->
            val on = i == active
            val fill by animateColorAsState(if (on) c.accent else c.text.copy(alpha = 0f), Fuse.motion.tween(Durations.FAST), label = "tab")
            val tint by animateColorAsState(if (on) c.onAccent else c.textMuted, Fuse.motion.tween(Durations.FAST), label = "tabTint")
            Box(
                Modifier
                    .size(COMPACT_TAB)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(i) }
                    .semantics { contentDescription = tab.label; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(tab.icon, size = Size.iconS, tint = tint)
                // A count shows as a dot here; the open tabs spell it out.
                if (tab.badge != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp).size(8.dp).clip(CircleShape).background(if (on) c.onAccent else c.accent))
                }
            }
        }
    }
}

/** A folded tab's size; the whole pill is this plus its padding. */
val COMPACT_TAB: Dp = 32.dp

private val PAD: Dp = 4.dp
