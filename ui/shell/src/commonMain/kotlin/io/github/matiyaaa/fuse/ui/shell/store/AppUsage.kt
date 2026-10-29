package io.github.matiyaaa.fuse.ui.shell.store

/**
 * Which app was in front, as the system keeps it (Android's usage statistics, once the person gives
 * Fuse Usage access): for counting only the time a game was on screen, not the time spent in
 * another app before coming back to Fuse.
 */
interface AppUsage {
    /** Whether Fuse may read it now. */
    fun allowed(): Boolean

    /** Opens the system page where the person gives Fuse Usage access, or takes it back. */
    fun askForAccess()

    /**
     * The moments [packageName] came to the front (true) or left it (false) between [from] and
     * [to], one for each of its screens; null when they can't be read.
     */
    suspend fun moves(packageName: String, from: Long, to: Long): List<Pair<Long, Boolean>>?
}

/**
 * How long an app was in front between [from] and [to], from its [moves]: from each time its first
 * screen came to the front until its last one left. One that leaves before anything came means it
 * was in front already when the time began; one still in front at the end counts to [to].
 */
fun timeInFront(moves: List<Pair<Long, Boolean>>, from: Long, to: Long): Long {
    if (to <= from) return 0
    var open = 0
    var since = from
    var total = 0L
    var first = true
    for ((at, front) in moves.sortedBy { it.first }) {
        val t = at.coerceIn(from, to)
        if (front) {
            if (open == 0) since = t
            open++
        } else if (open > 0) {
            open--
            if (open == 0) total += t - since
        } else if (first) {
            total += t - from
        }
        first = false
    }
    if (open > 0) total += to - since
    return total.coerceIn(0, to - from)
}
