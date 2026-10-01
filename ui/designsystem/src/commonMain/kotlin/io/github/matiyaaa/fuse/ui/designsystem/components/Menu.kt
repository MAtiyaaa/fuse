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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
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
    /** A group that opens in place: a quiet [summary] and a chevron that turns when [open]. */
    data class Disclosure(val open: Boolean, val summary: String? = null) : Trailing
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
    /** Artwork before the label, such as a game's logo. */
    val art: MenuArt? = null,
    /** How deep in an open group the row sits; each level steps it in. */
    val indent: Int = 0,
    val onSelect: () -> Unit = {},
)

/**
 * Artwork in a menu row: a logo fitted into a wide slot, or square art at the slot's start. Every
 * row with art uses the same slot, so labels line up whether a game has a logo or not.
 */
data class MenuArt(
    val model: Any?,
    val square: Boolean = false,
    /** With no artwork (or while it can't load), a generated square with this title's initials. */
    val fallbackTitle: String? = null,
    /** ARGB colour for the generated square. */
    val accent: Long = 0xFF8A93A6,
    /** The slot is logo-wide, so labels line up with rows that show a logo; off in lists of square art only. */
    val wide: Boolean = true,
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
        if (action.indent > 0) {
            // Rows inside an open group step in behind a faint rule, so the group reads as one.
            Spacer(Modifier.width(Space.m))
            Box(Modifier.width(1.dp).height(Size.row - Space.m).background(c.text.copy(alpha = 0.1f)))
            Spacer(Modifier.width(Space.l * action.indent - Space.m - 1.dp))
        }
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
        action.art?.let { art ->
            Box(Modifier.width(if (art.wide) MENU_ART_WIDTH else MENU_ART_HEIGHT).height(MENU_ART_HEIGHT), contentAlignment = Alignment.CenterStart) {
                val square = Modifier.size(MENU_ART_HEIGHT).clip(RoundedCornerShape(Fuse.geometry.control))
                val generated: @Composable () -> Unit = {
                    art.fallbackTitle?.let { GeneratedArt(it, Color(art.accent), square, slot = ArtSlot.ICON) }
                }
                if (art.model != null) {
                    Artwork(
                        art.model,
                        if (art.square) square else Modifier.fillMaxSize(),
                        contentScale = if (art.square) ContentScale.Crop else ContentScale.Fit,
                        focusX = 0f,
                        fallback = generated,
                    )
                } else {
                    generated()
                }
            }
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
            is Trailing.Disclosure -> Row(verticalAlignment = Alignment.CenterVertically) {
                val turn by animateFloatAsState(if (tr.open) 180f else 0f, motion.focusSpring(), label = "disclosure")
                if (tr.summary != null) {
                    FText(tr.summary, t.label, color = c.textMuted, maxLines = 1)
                    Spacer(Modifier.width(Space.s))
                }
                FuseIcon(FuseIcons.ChevronDown, size = 18.dp, tint = if (tr.open) c.text else c.textMuted, modifier = Modifier.graphicsLayer { rotationZ = turn })
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
    /** Takes all the height it may; off for lists in dialogs, which are only as tall as their rows. */
    fill: Boolean = true,
) {
    val state = rememberLazyListState()
    FollowSelection(state, { selection.index + if (header != null) 1 else 0 }, anchor = 0.3f)
    LazyColumn(
        modifier = if (fill) modifier.fillMaxHeight() else modifier,
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

private val MENU_ART_WIDTH = 104.dp
private val MENU_ART_HEIGHT = 44.dp
