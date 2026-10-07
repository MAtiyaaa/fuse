package io.github.matiyaaa.fuse.ui.fuseline.bench

import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Drawing, as Compose does it, reduced to its mechanism: each reader (a layer's draw block) records
 * the state objects it reads, indexed from each state to the readers that read it (as Compose's
 * scope maps are); when a frame's changes are applied, every reader that read something that
 * changed runs again, in order, and no other. A value that publishes nothing costs its readers
 * nothing; one that publishes makes them read it again.
 *
 * Readers are numbered 0 until [count]; [read] runs reader i, its reads recorded.
 */
class DrawModel(private val count: Int, private val read: (Int) -> Unit) {
    private val seen = Array(count) { ArrayList<Any>(2) }
    private val readers = HashMap<Any, IntList>()
    private val dirty = BooleanArray(count)
    private var anyDirty = false
    private val handle: ObserverHandle = Snapshot.registerApplyObserver { changed, _ ->
        for (c in changed) {
            val rs = readers[c] ?: continue
            for (k in 0 until rs.size) {
                val i = rs[k]
                if (!dirty[i]) {
                    dirty[i] = true
                    anyDirty = true
                }
            }
        }
    }

    private var drawing = -1
    private val record: (Any) -> Unit = { s ->
        val list = seen[drawing]
        if (!list.contains(s)) {
            list += s
            readers.getOrPut(s) { IntList() }.add(drawing)
        }
    }

    /** Runs reader [i] now, recording what it reads. */
    fun draw(i: Int) {
        forget(i)
        dirty[i] = false
        drawing = i
        Snapshot.observe(readObserver = record) { read(i) }
        drawing = -1
    }

    private fun forget(i: Int) {
        val list = seen[i]
        for (s in list) readers[s]?.remove(i)
        list.clear()
    }

    /** Stops drawing reader [i] (offscreen): nothing it read wakes it. */
    fun hide(i: Int) {
        forget(i)
        dirty[i] = false
    }

    /**
     * After a frame: applies its changes and runs every reader they touched, in order. State made
     * since the last frame is first marked as made, as Compose's recomposer does after composing, so
     * that writes to it count as changes (until then a new state object's writes are its own).
     */
    fun frame() {
        Snapshot.notifyObjectsInitialized()
        Snapshot.sendApplyNotifications()
        if (!anyDirty) return
        anyDirty = false
        for (i in 0 until count) if (dirty[i]) draw(i)
    }

    fun dispose() = handle.dispose()

    /** A small list of reader numbers, without boxing them. */
    class IntList {
        private var items = IntArray(2)
        var size = 0
            private set

        operator fun get(k: Int) = items[k]

        fun add(i: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = i
        }

        fun remove(i: Int) {
            for (k in 0 until size) if (items[k] == i) {
                items[k] = items[--size]
                return
            }
        }
    }
}
