package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.ui.shell.store.JobActivity
import io.github.matiyaaa.fuse.ui.shell.store.LibraryProbe
import io.github.matiyaaa.fuse.ui.shell.store.ObservatoryOps
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** The Performance Observatory over the Background Task Scheduler's own record and the real library. */
internal class DefaultObservatoryOps(
    private val ctx: StoreContext,
    private val search: suspend (String) -> Unit,
) : ObservatoryOps {
    override val jobs: StateFlow<List<JobActivity>> = ctx.work.activity
        .map { all -> BackgroundWork.Job.entries.map { job -> (all[job] ?: BackgroundWork.Activity()).toActivity(job) } }
        .stateIn(ctx.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override suspend fun probe(): LibraryProbe {
        fun ms(mark: TimeSource.Monotonic.ValueTimeMark) = mark.elapsedNow().inWholeMicroseconds / 1000.0
        val counts = TimeSource.Monotonic.markNow()
        val perSystem = ctx.data.games.platformCounts().first()
        val countsMs = ms(counts)
        val index = TimeSource.Monotonic.markNow()
        val entries = withContext(ioDispatcher) { ctx.data.search.readEntries() }
        val indexMs = ms(index)
        if (entries.isEmpty()) return LibraryProbe(0, countsMs, indexMs)
        val searched = TimeSource.Monotonic.markNow()
        // A word most libraries have somewhere, so the search ranks many names.
        search("the")
        return LibraryProbe(perSystem.values.sum(), countsMs, indexMs, ms(searched))
    }

    private fun BackgroundWork.Activity.toActivity(job: BackgroundWork.Job) =
        JobActivity(job.label, runs, running, held, lastMs, longestMs, totalMs)
}
