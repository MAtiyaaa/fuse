package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * How Home's widgets are rearranged, by touch or with the D-pad alike. What Home shows is a list of
 * blocks (a Flow shelf, or one channel), each made of one or more widgets; moving a block moves its
 * widgets together. Widgets that show nothing right now (hidden, empty, not offered here) stay right
 * behind the widget they followed, so they come back where they were.
 */
object HomeArrange {
    /** [widgets] with the block at [from] moved to [to], renumbered in the new order. */
    fun moveBlock(widgets: List<HomeWidget>, blocks: List<List<String>>, from: Int, to: Int): List<HomeWidget> {
        if (from !in blocks.indices || to !in blocks.indices || from == to) return widgets
        val blockOf = HashMap<String, Int>()
        blocks.forEachIndexed { b, ids -> ids.forEach { blockOf[it] = b } }
        // Widgets before the first shown one stay at the very start.
        val head = mutableListOf<HomeWidget>()
        val groups = List(blocks.size) { mutableListOf<HomeWidget>() }
        var current = -1
        for (w in widgets.sortedBy { it.order }) {
            blockOf[w.id]?.let { current = it }
            if (current < 0) head += w else groups[current] += w
        }
        val order = blocks.indices.toMutableList().apply { add(to, removeAt(from)) }
        return (head + order.flatMap { groups[it] }).mapIndexed { i, w -> w.copy(order = i) }
    }

    /** [widgets] with the shown widget [id] moved to place [to] among the [shown] ones. */
    fun moveOne(widgets: List<HomeWidget>, shown: List<String>, id: String, to: Int): List<HomeWidget> =
        moveBlock(widgets, shown.map { listOf(it) }, shown.indexOf(id), to)
}

/**
 * Where a held shelf or channel will land: a quiet well with a dashed edge, drawn in the item's own
 * place while the item floats with the finger above it, so the drop place is always visible (the
 * others make room around it). [shown] runs from 0 (no well) to 1 and is read only while drawing.
 *
 * The well is [shape] inset by [horizontal] on each side, starting [top] from the item's top (a
 * negative value reaches above it), [height] tall or down to the item's bottom. Put this before the
 * item's `reorderItem` in the modifier chain so it stays put while the item moves.
 */
@Composable
internal fun Modifier.dropWell(
    shown: () -> Float,
    shape: Shape,
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
    height: Dp? = null,
): Modifier {
    val c = Fuse.colors
    val fill = c.surfaceDim.copy(alpha = if (c.isDark) 0.6f else 0.75f)
    val edge = c.hairlineStrong
    return drawWithCache {
        val left = horizontal.toPx()
        val y = top.toPx()
        val w = (size.width - left * 2).coerceAtLeast(0f)
        val h = (height?.toPx() ?: (size.height - y)).coerceAtLeast(0f)
        val path = Path().apply {
            addOutline(shape.createOutline(androidx.compose.ui.geometry.Size(w, h), layoutDirection, this@drawWithCache))
            translate(Offset(left, y))
        }
        val dash = Stroke(
            width = Size.focusStroke.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(Space.s.toPx(), (Space.xs + Space.xxs).toPx())),
        )
        onDrawBehind {
            val a = shown()
            if (a > 0.01f) {
                drawPath(path, fill, alpha = a.coerceAtMost(1f))
                drawPath(path, edge, alpha = a.coerceAtMost(1f), style = dash)
            }
        }
    }
}

/**
 * The mark beside something being moved: a solid "Moving" badge and what moves it ([how]), so
 * the arranging state reads at a glance and says how to finish.
 */
@Composable
internal fun MovingNote(how: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Badge("Moving", icon = FuseIcons.Move)
        Spacer(Modifier.width(Space.s))
        FText(how, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
    }
}
