package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.components.CountPill
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.systems.SystemHeader

/** One item of the header row, for controller focus: a view, or a button. */
internal sealed interface HeaderItem {
    data class View(val segment: LibrarySegment) : HeaderItem
    data class Button(val button: LibraryButton) : HeaderItem
}

/**
 * The top of a library view. The whole library shows its views as tabs with a sliding underline
 * (All, Favourites, Recently played and, while they have games, Missing, Hidden and Removed). A
 * system shows its own header, which folds away by [collapse] as you move down its games, and a
 * collection shows its name. A compact toolbar on the right holds Collections, the system filter,
 * Sort and View; focus outlines its buttons and never fills them, so their values stay readable.
 */
@Composable
internal fun LibraryHeader(
    app: AppState,
    scope: LibraryScope,
    platform: PlatformCard?,
    compact: Boolean,
    collapse: Float,
    system: PlatformCard?,
    count: Int?,
    header: List<HeaderItem>,
    sort: SortOrder,
    layout: LibraryLayout,
    state: LibraryViewState,
    onView: (Int, LibrarySegment) -> Unit,
    onButton: (Int, LibraryButton) -> Unit,
) {
    val c = Fuse.colors
    fun focused(i: Int) = state.inHeader && app.focusZone == FocusZone.CONTENT && i == state.headerIndex
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Every button names its value when there is room; on narrow screens only the focused one does.
        val roomy = maxWidth >= 1000.dp
        Row(
            Modifier.fillMaxWidth().padding(end = Space.gutter, top = if (platform != null) 0.dp else Space.xs, bottom = Space.xs),
            verticalAlignment = if (platform != null) Alignment.Bottom else Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                when {
                    scope == LibraryScope.All -> LibraryTabs(header, state.segment, count, ::focused, onView)
                    // The same header as the Systems screen, in the same place, so the logo stays put.
                    platform != null -> SystemHeader(platform, compact, Modifier.padding(start = Space.gutter), widthFraction = 0.9f, collapse = collapse)
                    else -> Row(Modifier.padding(start = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                        val title = when (scope) {
                            is LibraryScope.OfPlatform -> scope.platform.value
                            is LibraryScope.OfCollection -> scope.name
                            LibraryScope.All -> "Library"
                        }
                        FText(title, Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (count != null) {
                            Spacer(Modifier.width(Space.m))
                            CountPill(count.toString())
                        }
                    }
                }
            }
            val tools = header.withIndex().filter { it.value is HeaderItem.Button }
            if (tools.isNotEmpty()) {
                Spacer(Modifier.width(Space.m))
                Row(
                    Modifier
                        .clip(PillShape)
                        .background(c.text.copy(alpha = 0.06f))
                        .border(1.dp, c.text.copy(alpha = 0.08f), PillShape)
                        .padding(Space.xs),
                    horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((i, item) in tools) {
                        val button = (item as HeaderItem.Button).button
                        val (label, icon) = when (button) {
                            LibraryButton.COLLECTIONS -> "Collections" to FuseIcons.LibraryBig
                            LibraryButton.ADD_GAMES -> "Add or remove games" to FuseIcons.ListPlus
                            LibraryButton.SYSTEM -> (system?.platform?.shortName ?: "All systems") to FuseIcons.Filter
                            LibraryButton.SORT -> sortLabel(sort) to FuseIcons.Sort
                            LibraryButton.VIEW -> layoutLabel(layout) to layoutIcon(layout)
                        }
                        val on = button == LibraryButton.SYSTEM && system != null
                        ToolButton(label, icon, focused = focused(i), active = on, showLabel = roomy || focused(i) || on) { onButton(i, button) }
                    }
                }
            }
        }
    }
}

/** The views of the whole library, as tabs. The active one carries the game count. */
@Composable
private fun LibraryTabs(
    header: List<HeaderItem>,
    active: LibrarySegment,
    count: Int?,
    focused: (Int) -> Boolean,
    onView: (Int, LibrarySegment) -> Unit,
) {
    // The views always come first in the header, so a tab's index is its header index.
    val views = header.filterIsInstance<HeaderItem.View>().map { it.segment }
    ViewTabs(
        items = views.map { s ->
            ViewTab(
                label = s.label,
                icon = if (s == LibrarySegment.MISSING) FuseIcons.FileQuestion else null,
                warn = s == LibrarySegment.MISSING,
                badge = if (s == active) count?.toString() else null,
            )
        },
        active = views.indexOf(active),
        focused = views.indices.firstOrNull { focused(it) },
        onSelect = { i -> onView(i, views[i]) },
    )
}

/** A button in the header toolbar: an icon and its current value. [active] tints it with the accent. */
@Composable
private fun ToolButton(label: String, icon: ImageVector, focused: Boolean, active: Boolean, showLabel: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val bg by animateColorAsState(
        when {
            focused -> c.text.copy(alpha = 0.14f)
            active -> c.accent.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        motion.tween(Durations.FAST),
        label = "tool bg",
    )
    val tint = when {
        active -> c.accent
        focused -> c.text
        else -> c.text.copy(alpha = 0.78f)
    }
    Row(
        Modifier
            .height(36.dp)
            .clip(PillShape)
            .background(bg)
            .drawBehind {
                if (focused) drawRoundRect(c.focus.copy(alpha = 0.85f), cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.5.dp.toPx()))
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { this.selected = focused }
            .padding(horizontal = Space.m - 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 16.dp, tint = tint)
        AnimatedVisibility(
            visible = showLabel,
            enter = expandHorizontally(motion.tween(Durations.BASE)) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.tween(Durations.FAST)) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s - 2.dp))
                FText(label, Fuse.type.label, color = tint, maxLines = 1)
            }
        }
    }
}
