package io.github.matiyaaa.fuse.ui.shell.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuse Performance Observatory, the store's side: what Fuse's background work has cost since it
 * started, and how quickly the library answers right now. Nothing here runs until it is asked:
 * [jobs] is kept as the work runs anyway, and [probe] reads only when called.
 */
interface ObservatoryOps {
    /** Each kind of background job, with what it has cost so far. */
    val jobs: StateFlow<List<JobActivity>>

    /** Times a few real reads of the library as Fuse makes them (Home's counts, the search index, a search). */
    suspend fun probe(): LibraryProbe

    object None : ObservatoryOps {
        override val jobs: StateFlow<List<JobActivity>> = MutableStateFlow(emptyList())
        override suspend fun probe() = LibraryProbe()
    }
}

/** One kind of background job (finding series, building the search index) and what it has cost. */
data class JobActivity(
    val label: String,
    val runs: Int,
    /** Running this moment. */
    val running: Int,
    /** Times it waited for a game to end, or the device to wake, before running. */
    val held: Int,
    val lastMs: Long,
    val longestMs: Long,
    val totalMs: Long,
)

/** How long the library's reads took just now, each in milliseconds; null when there was nothing to read. */
data class LibraryProbe(
    val games: Int = 0,
    /** Home's count of games per system, from the database. */
    val countsMs: Double? = null,
    /** Every game read for the search index, from the database. */
    val indexMs: Double? = null,
    /** A search through the whole library, from the index. */
    val searchMs: Double? = null,
)
