package io.github.matiyaaa.fuse.ui.shell.store.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Fuse's Background Task Scheduler: the work nobody is waiting on (finding series, working out
 * suggestions, identifying games, building the search index) in one place.
 *
 * - It runs at most [PARALLEL] jobs at once, on threads of its own, so the burst of jobs a scan or a
 *   start sets off can never take every core from the interface and from search.
 * - The jobs that are only nice to have wait while a game is running and while the device sleeps or
 *   has just woken ([PerformanceGovernor]). They catch up after.
 * - What ran, how often and for how long is kept ([activity]) for the Performance Observatory.
 *
 * Saves and transfers never go through it: nothing here may hold them back.
 */
internal class BackgroundWork(
    private val governor: PerformanceGovernor,
    private val clockMs: () -> Long = { io.github.matiyaaa.fuse.ui.fuseline.monotonicNanos() / 1_000_000L },
) {
    /**
     * A kind of job: whether it waits out games, and whether it is thinking (on the background
     * threads) or mostly reading files and the network (where it already runs, only counted).
     */
    enum class Job(val label: String, val holdsForGames: Boolean, val thinks: Boolean) {
        SERIES("Finding series", holdsForGames = true, thinks = true),
        SUGGESTIONS("Working out suggestions", holdsForGames = true, thinks = true),
        IDENTIFY("Reading discs to identify games", holdsForGames = true, thinks = false),
        SEARCH_INDEX("Building the search index", holdsForGames = false, thinks = false),
    }

    /** How often a job has run and what it cost. */
    data class Activity(
        val runs: Int = 0,
        val running: Int = 0,
        val lastMs: Long = 0,
        val longestMs: Long = 0,
        val totalMs: Long = 0,
        val held: Int = 0,
    )

    /** Background threads: [PARALLEL] at most, whatever the device has. */
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(PARALLEL)

    private val stats = MutableStateFlow<Map<Job, Activity>>(emptyMap())

    /** Each job's runs so far, for the Performance Observatory. */
    val activity: StateFlow<Map<Job, Activity>> = stats

    /** Runs [block] as [job]: after a game, if it waits for one, and on the background threads if it thinks. */
    suspend fun <T> run(job: Job, block: suspend () -> T): T {
        if (job.holdsForGames && !governor.policy.background) {
            stats.update { it + (job to (it[job] ?: Activity()).let { a -> a.copy(held = a.held + 1) }) }
            governor.await { it.background }
        }
        return if (job.thinks) withContext(dispatcher) { timed(job) { block() } } else timed(job) { block() }
    }

    /** Counts [block] as a run of [job] where it already runs (work that must stay on its own dispatcher). */
    suspend fun <T> timed(job: Job, block: suspend () -> T): T {
        stats.update { it + (job to (it[job] ?: Activity()).let { a -> a.copy(running = a.running + 1) }) }
        val start = clockMs()
        try {
            return block()
        } finally {
            val ms = clockMs() - start
            stats.update {
                it + (job to (it[job] ?: Activity()).let { a ->
                    a.copy(runs = a.runs + 1, running = a.running - 1, lastMs = ms, longestMs = maxOf(a.longestMs, ms), totalMs = a.totalMs + ms)
                })
            }
        }
    }

    companion object {
        /** Two at once: enough to keep up after a scan, few enough to leave a phone's other cores alone. */
        const val PARALLEL = 2
    }
}
