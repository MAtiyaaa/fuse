package io.github.matiyaaa.fuse.ui.designsystem.effects

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Fuseline 4.2: how long Fuse took to start, in the steps a person sees. The first frame drawn,
 * Home with its games, the first art on screen. Each step is marked once, the first time it
 * happens, as milliseconds since the app's own clock started ([origin], set by the platform to
 * when the process began where it can know). The report is one line for the log, so a slow start
 * on any device says where its time went. Nothing about the games or the person is kept.
 */
object StartupTrace {
    private val epoch = TimeSource.Monotonic.markNow()

    /** Milliseconds between the process starting and this object's clock starting (0 when unknown). */
    @Volatile var origin: Long = 0

    private val marks = LinkedHashMap<String, Long>()

    /** Called once every step in [STEPS] has been marked. */
    @Volatile var onComplete: ((String) -> Unit)? = null

    /** Marks [step] the first time it happens; later marks are ignored. */
    fun mark(step: String) {
        val done: String?
        synchronized(marks) {
            if (step in marks) return
            marks[step] = origin + epoch.elapsedNow().inWholeMilliseconds
            done = if (STEPS.all { it in marks }) report() else null
        }
        done?.let { onComplete?.invoke(it) }
    }

    fun at(step: String): Long? = synchronized(marks) { marks[step] }

    /** "Started in 1840 ms: window 420, first frame 610, Home 900, art 1840" (the steps marked so far). */
    fun report(): String = synchronized(marks) {
        val last = marks.values.maxOrNull() ?: 0
        "Started in $last ms: " + marks.entries.joinToString(", ") { (k, v) -> "$k $v" }
    }

    /** For tests: forget every mark. */
    fun reset() = synchronized(marks) { marks.clear() }

    const val WINDOW = "window"
    const val FRAME = "first frame"
    const val STORE = "library open"
    const val HOME = "Home"
    const val ART = "art"
    val STEPS = listOf(FRAME, STORE, HOME, ART)
}
