package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.KeepSelectionInView
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderMath
import io.github.matiyaaa.fuse.ui.designsystem.focus.carried
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderHandle
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.tween

/** One row of a [ReorderList]. A [locked] row keeps its place (Home stays first). */
@Immutable
data class ReorderEntry(
    val key: String,
    val label: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val locked: Boolean = false,
)

/**
 * The order a [ReorderList] shows, and the controller's part in changing it: the selected row, and
 * whether it is carried. Pure state, so the rules are tested without drawing anything.
 *
 * The list has one more place than it has rows, a "Done" button after the last one.
 */
@Stable
class ReorderListState(keys: List<String>) {
    /** The keys in the order shown. */
    var order: List<String> by mutableStateOf(keys)
        private set

    val selection = LinearSelection(0)

    /** True while the controller carries the selected row. */
    var carrying by mutableStateOf(false)
        private set

    private var before: List<String> = keys

    /** Takes a new order from outside (the setting changed elsewhere), unless a row is carried. */
    fun sync(keys: List<String>) {
        if (!carrying && keys != order) order = keys
    }

    /** Picks up the selected row; false for a locked row or the Done button. */
    fun pickUp(locked: (String) -> Boolean): Boolean {
        val key = order.getOrNull(selection.index) ?: return false
        if (locked(key)) return false
        before = order
        carrying = true
        return true
    }

    /** Moves the carried row by [delta] places; false at an end or against a locked row. */
    fun step(delta: Int, locked: (String) -> Boolean): Boolean {
        if (!carrying) return false
        val from = selection.index
        val to = from + delta
        val over = order.getOrNull(to) ?: return false
        if (locked(over)) return false
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        selection.index = to
        return true
    }

    /** Puts the carried row down; returns the new order when it differs from before the pick-up. */
    fun drop(): List<String>? {
        if (!carrying) return null
        carrying = false
        return order.takeIf { it != before }
    }

    /** Puts the carried row back where it was picked up. */
    fun cancel() {
        if (!carrying) return
        val key = order.getOrNull(selection.index)
        order = before
        carrying = false
        if (key != null) selection.index = order.indexOf(key).coerceAtLeast(0)
    }

    /**
     * Moves [key] to [to] (a touch drop), never onto or above a locked row; returns the new order,
     * or null when nothing changed.
     */
    fun place(key: String, to: Int, locked: (String) -> Boolean): List<String>? {
        val from = order.indexOf(key)
        if (from < 0 || locked(key)) return null
        val firstFree = order.indexOfFirst { !locked(it) }.coerceAtLeast(0)
        val lastFree = order.indexOfLast { !locked(it) }.coerceAtLeast(firstFree)
        val target = to.coerceIn(firstFree, lastFree)
        if (target == from) return null
        order = order.toMutableList().apply { add(target, removeAt(from)) }
        selection.index = target
        return order
    }

    /**
     * The controller: Up and Down choose a row (and Done after the last), A picks it up, Up and
     * Down move it, A puts it down and B puts it back. [onMoved] gets each new order as it is put
     * down; [onDone] closes the list; [onLift] and [onSlot] are the moments to feel.
     */
    fun handle(
        e: NavEvent,
        locked: (String) -> Boolean,
        onMoved: (List<String>) -> Unit,
        onDone: () -> Unit,
        onLift: () -> Unit = {},
        onSlot: () -> Unit = {},
    ): NavResult {
        val places = order.size + 1
        if (carrying) {
            return when (e.action) {
                NavAction.UP -> if (step(-1, locked)) { onSlot(); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (step(1, locked)) { onSlot(); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT, NavAction.REORDER -> { drop()?.let(onMoved); NavResult.ACTIVATED }
                NavAction.BACK -> { cancel(); NavResult.CONSUMED }
                // Nothing else while a row is in hand, so it is never left floating.
                else -> NavResult.CONSUMED
            }
        }
        return when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN ->
                selection.move(e.action, places, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            NavAction.SELECT, NavAction.REORDER -> when {
                selection.index >= order.size -> { onDone(); NavResult.ACTIVATED }
                pickUp(locked) -> { onLift(); NavResult.ACTIVATED }
                else -> NavResult.BLOCKED
            }
            NavAction.BACK -> { onDone(); NavResult.CONSUMED }
            else -> NavResult.IGNORED
        }
    }
}

@Composable
fun rememberReorderListState(keys: List<String>): ReorderListState {
    val state = remember { ReorderListState(keys) }
    state.sync(keys)
    return state
}

/**
 * A list made for putting things in order, the way a phone's edit mode does it: every row has a
 * grip, and dragging the grip moves the row at once (a hold anywhere on the row works too) while
 * the others slide out of the way. With the controller, A picks the selected row up, the D-pad
 * moves it and A puts it down. A [ReorderEntry.locked] row shows a lock and keeps its place.
 *
 * Each new order goes to [onMoved] as soon as a row is put down; [onDone] is the Done button.
 * [onLift], [onSlot] and [onDrop] are for haptics. [focused] says whether the controller is here.
 */
@Composable
fun ReorderList(
    entries: List<ReorderEntry>,
    state: ReorderListState,
    onMoved: (List<String>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = true,
    longPressMs: Long? = null,
    onLift: () -> Unit = {},
    onSlot: () -> Unit = {},
    onDrop: () -> Unit = {},
) {
    val byKey = remember(entries) { entries.associateBy { it.key } }
    val locked: (String) -> Boolean = { byKey[it]?.locked == true }
    val drag = rememberDragReorderState()
    val shown = drag.arrange(state.order.mapNotNull { byKey[it] }) { it.key }
    val list = rememberLazyListState()
    val margin = with(LocalDensity.current) { Size.row.roundToPx() }
    KeepSelectionInView(list, { state.selection.index.coerceAtMost(state.order.lastIndex.coerceAtLeast(0)) }, margin, enabled = { drag.heldKey == null })
    Column(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier
                .weight(1f, fill = false)
                .dragReorder(
                    drag,
                    // Locked rows are neither lifted nor landed on.
                    visibleKeys = { list.layoutInfo.visibleItemsInfo.map { it.key }.filter { it is String && !locked(it) } },
                    scrollBy = { list.scrollBy(it) },
                    keepScroll = { list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) },
                    longPressMs = longPressMs,
                    instantHandles = true,
                    lane = ReorderMath.Lane.COLUMN,
                    onLift = { key ->
                        state.selection.index = state.order.indexOf(key).coerceAtLeast(0)
                        onLift()
                    },
                    onTarget = onSlot,
                    onDrop = { key, to ->
                        state.place(key.toString(), to, locked)?.let(onMoved)
                        onDrop()
                    },
                ),
            contentPadding = PaddingValues(vertical = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            itemsIndexed(shown, key = { _, e -> e.key }) { i, entry ->
                val held = drag.heldKey == entry.key
                val selected = focused && (if (drag.heldKey != null) held else i == state.selection.index)
                val carried = state.carrying && i == state.selection.index
                ReorderRow(
                    entry,
                    place = i + 1,
                    selected = selected,
                    lifted = held || carried,
                    carried = carried,
                    handle = Modifier.reorderHandle(drag, entry.key),
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (held || carried) null else ReorderDefaults.Placement)
                        .reorderItem(drag, entry.key, liftScale = LIFT_SCALE, shape = RoundedCornerShape(Fuse.geometry.control))
                        .zIndex(if (carried) 1f else 0f),
                    onClick = {
                        // A tap chooses the row; with a row carried, a tap on another puts it there.
                        if (state.carrying) {
                            val key = state.order.getOrNull(state.selection.index)
                            val dropped = state.drop()
                            val placed = key?.let { state.place(it, i, locked) }
                            (placed ?: dropped)?.let(onMoved)
                        } else {
                            state.selection.index = i
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(Space.l))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // While a row is in hand, where it is; the header above never changes, so nothing jumps.
            val carried = if (state.carrying) byKey[state.order.getOrNull(state.selection.index)] else null
            Box(Modifier.weight(1f).padding(end = Space.l)) {
                if (carried != null) {
                    FText(
                        "Moving ${carried.label}, place ${state.selection.index + 1} of ${state.order.size}",
                        Fuse.type.caption,
                        color = Fuse.colors.textMuted,
                        maxLines = 2,
                    )
                }
            }
            FuseButton(
                "Done",
                selected = focused && !state.carrying && state.selection.index >= state.order.size,
                onClick = onDone,
                kind = ButtonKind.PRIMARY,
            )
        }
    }
}

@Composable
private fun ReorderRow(
    entry: ReorderEntry,
    place: Int,
    selected: Boolean,
    lifted: Boolean,
    carried: Boolean,
    handle: Modifier,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val t = Fuse.type
    val motion = Fuse.motion
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val sel by fuselineFloat(if (selected) 1f else 0f, motion.tween(Durations.FAST), label = "reorderSel")
    val lift by fuselineFloat(if (carried) 1f else 0f, motion.focusSpring(), label = "reorderCarry")
    // A row in hand stands on its own surface, so its shadow reads over the rows underneath.
    val panel = LocalPanelFill.current ?: c.surfaceRaised
    val bg by fuselineColor(
        when {
            lifted -> c.surfaceOverlay
            selected -> c.text.copy(alpha = if (c.isDark) 0.1f else 0.07f)
            else -> panel.copy(alpha = 0f)
        },
        motion.tween(Durations.FAST),
        label = "reorderBg",
    )
    val edge = when {
        lifted -> c.accent.copy(alpha = 0.55f)
        selected && Fuse.look.highContrastFocus -> c.focus
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Size.row)
            .carried({ lift }, shape, scale = LIFT_SCALE)
            .clip(shape)
            .background(bg)
            .border(Size.stroke, edge, shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(BAR_WIDTH)
                .height(BAR_HEIGHT)
                .graphicsLayer {
                    scaleY = sel
                    alpha = sel
                }
                .clip(RoundedCornerShape(2.dp))
                .background(c.accent),
        )
        Spacer(Modifier.width(Space.m))
        FText(
            "$place",
            t.label.tabular(),
            color = if (selected || lifted) c.text else c.textFaint,
            modifier = Modifier.widthIn(min = PLACE_WIDTH),
            maxLines = 1,
        )
        if (entry.icon != null) {
            Box(
                Modifier
                    .size(ICON_WELL)
                    .clip(RoundedCornerShape(Fuse.geometry.control))
                    .background(c.text.copy(alpha = if (selected || lifted) (if (c.isDark) 0.13f else 0.1f) else (if (c.isDark) 0.07f else 0.055f))),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(entry.icon, size = Size.iconS + 2.dp, tint = if (entry.locked) c.textMuted else c.text)
            }
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f).padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(entry.label, t.bodyStrong, color = if (entry.locked) c.textMuted else c.text, maxLines = 1)
            if (entry.detail != null) FText(entry.detail, t.caption, color = c.textMuted, maxLines = 2)
        }
        // The grip: a whole finger's width, so it is easy to catch. A locked row shows why it stays.
        Box(
            (if (entry.locked) Modifier else handle.semantics { contentDescription = "Move ${entry.label}" }).size(Size.touch),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(
                if (entry.locked) FuseIcons.Lock else FuseIcons.Grip,
                size = Size.iconM,
                tint = when {
                    entry.locked -> c.textFaint
                    lifted -> c.accent
                    selected -> c.text
                    else -> c.textMuted
                },
            )
        }
    }
}

private const val LIFT_SCALE = 1.03f
private val BAR_WIDTH = 3.dp
private val BAR_HEIGHT = 22.dp
private val ICON_WELL = 32.dp
private val PLACE_WIDTH = 20.dp
