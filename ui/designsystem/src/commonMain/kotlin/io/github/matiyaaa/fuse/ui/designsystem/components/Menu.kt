package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/** What the right side of a menu row shows. */
sealed interface Trailing {
    data object None : Trailing
    data object Chevron : Trailing
    data class Value(val text: String) : Trailing
    data class Switch(val on: Boolean) : Trailing
    data class Check(val on: Boolean) : Trailing
    /** Marks a setting as coming from a parent scope ("Global", "PlayStation 2"). */
    data class Inherited(val from: String) : Trailing
    data class Badge(val text: String) : Trailing
    /** A running job: [text] over a small bar ([fraction] null while its size isn't known). */
    data class Progress(val fraction: Float?, val text: String) : Trailing
}

@Immutable
data class MenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val trailing: Trailing = Trailing.None,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    /** Shown dimmed with this note instead of doing anything (integration unfinished, not supported here). */
    val unavailableReason: String? = null,
    val onSelect: () -> Unit = {},
)

/**
 * One row in a menu or settings list. Selection is shown with a raised background, a leading accent
 * bar and brighter text, so it's visible in any colour theme and to colour-blind users.
 */
@Composable
fun MenuRow(
    action: MenuAction,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Shown as the current choice without controller focus (a quieter background, no bar). */
    marked: Boolean = false,
    onClick: () -> Unit = action.onSelect,
) {
    val c = Fuse.colors
    val t = Fuse.type
    val motion = Fuse.motion
    val sel by animateFloatAsState(if (selected) 1f else 0f, motion.tween(Durations.FAST), label = "row")
    val bg by animateColorAsState(
        when {
            selected -> c.text.copy(alpha = 0.1f)
            marked -> c.text.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        motion.tween(Durations.FAST),
        label = "rowbg",
    )
    val available = action.enabled && action.unavailableReason == null
    val tint = when {
        !available -> c.textFaint
        action.destructive -> c.danger
        else -> c.text
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Size.row)
            .clip(RoundedCornerShape(Fuse.geometry.control))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, enabled = available, onClick = onClick)
            // Screen readers (and the UI audit) can tell which row the controller is on.
            .semantics { this.selected = selected }
            .padding(end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 22.dp)
                .graphicsLayer { scaleY = sel; alpha = sel }
                .clip(RoundedCornerShape(2.dp))
                .background(c.accent),
        )
        Spacer(Modifier.width(Space.m))
        if (action.icon != null) {
            FuseIcon(action.icon, tint = if (selected) tint else tint.copy(alpha = if (available) 0.78f else 0.5f))
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
            FText(action.label, t.bodyStrong, color = if (selected) tint else tint.copy(alpha = 0.92f), maxLines = 2)
            val detail = action.unavailableReason ?: action.detail
            if (detail != null) FText(detail, t.caption, color = c.textMuted, maxLines = 3)
        }
        Spacer(Modifier.width(Space.m))
        when (val tr = action.trailing) {
            Trailing.None -> Unit
            Trailing.Chevron -> FuseIcon(FuseIcons.ChevronRight, size = 18.dp, tint = c.textMuted)
            is Trailing.Value -> FText(tr.text, t.label, color = c.textMuted, maxLines = 1)
            is Trailing.Switch -> Toggle(tr.on, enabled = available)
            is Trailing.Check -> if (tr.on) FuseIcon(FuseIcons.Check, tint = c.accent)
            is Trailing.Inherited -> Chip(tr.from, icon = FuseIcons.Layers, color = c.textMuted)
            is Trailing.Badge -> Chip(tr.text, color = c.accent)
            is Trailing.Progress -> Column(Modifier.width(112.dp), horizontalAlignment = Alignment.End) {
                FText(tr.text, t.label, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                ProgressBar(tr.fraction, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A vertical list of [actions] driven by [selection]. Handles its own Up/Down/Select; call from
 * inside an [io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer] via [handleMenuAction].
 */
@Composable
fun MenuList(
    actions: List<MenuAction>,
    selection: LinearSelection,
    modifier: Modifier = Modifier,
    /** False while focus is elsewhere (another pane), so no row looks selected. */
    showSelection: Boolean = true,
    /** Keeps a quiet marker on the selected row while focus is in another pane (settings sections). */
    dimSelection: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val state = rememberLazyListState()
    FollowSelection(state, { selection.index + if (header != null) 1 else 0 }, anchor = 0.3f)
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        state = state,
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        if (header != null) item { header() }
        itemsIndexed(actions, key = { _, a -> a.id }) { i, a ->
            MenuRow(a, selected = showSelection && !dimSelection && i == selection.index, marked = showSelection && dimSelection && i == selection.index, onClick = {
                selection.select(i, actions.size)
                if (a.enabled && a.unavailableReason == null) a.onSelect()
            })
        }
    }
}

/** Standard controller handling for a [MenuList]. */
fun handleMenuAction(event: NavEvent, actions: List<MenuAction>, selection: LinearSelection): NavResult {
    return when (event.action) {
        NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN ->
            selection.move(event.action, actions.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
        NavAction.SELECT -> {
            val a = actions.getOrNull(selection.index) ?: return NavResult.BLOCKED
            if (!a.enabled || a.unavailableReason != null) return NavResult.BLOCKED
            a.onSelect()
            NavResult.ACTIVATED
        }
        else -> NavResult.IGNORED
    }
}
