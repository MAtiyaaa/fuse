package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.HomeWidget

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
