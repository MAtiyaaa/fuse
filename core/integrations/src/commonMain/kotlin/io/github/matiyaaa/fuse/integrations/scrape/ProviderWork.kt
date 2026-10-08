package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.time.Clock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Foreground requests pass queued bulk work. Running network calls are never restarted. */
enum class ScrapeDemand { VISIBLE, NEW_GAME, NEARBY, BACKGROUND }

/**
 * One provider's queue, with a bounded number of in-flight calls. Cancellation returns the slot,
 * including cancellation while waiting; a cancelled waiter can never strand the provider.
 */
class ProviderWork(private val concurrency: Int = 2) {
    init { require(concurrency > 0) }
    internal class Priority(@Volatile var demand: ScrapeDemand)
    private data class Waiting(val priority: Priority, val ready: CompletableDeferred<Unit>)
    private val lock = Mutex()
    private var active = 0
    private val waiting = ArrayList<Waiting>()

    suspend fun <T> run(demand: ScrapeDemand, block: suspend () -> T): T = run(Priority(demand), block)

    internal suspend fun <T> run(priority: Priority, block: suspend () -> T): T {
        val waiter = Waiting(priority, CompletableDeferred())
        lock.withLock {
            if (active < concurrency) { active++; waiter.ready.complete(Unit) } else waiting += waiter
        }
        try {
            waiter.ready.await()
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    // If still queued, no slot was acquired. If granted, release it even when await
                    // lost a race with cancellation.
                    if (!waiting.remove(waiter)) {
                        active--
                        val next = waiting.minByOrNull { it.priority.demand.ordinal }
                        if (next != null) { waiting.remove(next); active++; next.ready.complete(Unit) }
                    }
                }
            }
        }
    }
}

/**
 * Coalesces identical calls and keeps only successful answers. Empty successes expire sooner;
 * authentication/network/quota failures are never misremembered as missing artwork. Every key has
 * one result type, enforced by the callers' operation prefixes. Storage remains outside this class.
 */
internal class RequestReuse(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private data class Kept(val value: ApiResult<Any?>, val expires: Long)
    private data class Pending(
        val ready: CompletableDeferred<ApiResult<Any?>>,
        val priority: ProviderWork.Priority,
    )
    private val lock = Mutex()
    private val kept = LinkedHashMap<String, Kept>()
    private val pending = HashMap<String, Pending>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(
        key: String,
        empty: (T) -> Boolean = { false },
        demand: ScrapeDemand = ScrapeDemand.VISIBLE,
        block: suspend (ProviderWork.Priority) -> ApiResult<T>,
    ): ApiResult<T> {
        var owner = false
        val job = lock.withLock {
            kept[key]?.takeIf { it.expires > now() }?.let { return it.value as ApiResult<T> }
            pending.getOrPut(key) {
                owner = true
                Pending(CompletableDeferred(), ProviderWork.Priority(demand))
            }.also {
                // The queued owner reads the shared priority when a slot opens. This also
                // handles a follower arriving before the owner has entered the queue.
                if (demand.ordinal < it.priority.demand.ordinal) it.priority.demand = demand
            }
        }
        if (!owner) {
            try {
                return job.ready.await() as ApiResult<T>
            } catch (e: CancellationException) {
                // Leaving one screen cancels its owned call, not another screen still waiting
                // for the same artwork. A live follower can own a replacement request.
                currentCoroutineContext().ensureActive()
                return run(key, empty, demand, block)
            }
        }
        try {
            val value = block(job.priority)
            lock.withLock {
                if (value is ApiResult.Success) {
                    kept.remove(key)
                    kept[key] = Kept(value as ApiResult<Any?>, now() + if (empty(value.value)) EMPTY_TTL else FOUND_TTL)
                    while (kept.size > 512) kept.remove(kept.keys.first())
                }
                pending.remove(key)
                job.ready.complete(value as ApiResult<Any?>)
            }
            return value
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                lock.withLock { pending.remove(key); job.ready.completeExceptionally(e) }
            }
            throw e
        }
    }

    private companion object {
        const val FOUND_TTL = 6 * 60 * 60_000L
        const val EMPTY_TTL = 5 * 60_000L
    }
}

/** All screens sharing a coordinator share each provider's scheduling and successful answers. */
internal class ReusingSource(private val source: ScrapeSource, private val demand: suspend () -> ScrapeDemand) : ScrapeSource by source {
    private val queue = ProviderWork()
    private val reuse = RequestReuse()

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> =
        reuse.run("search:$query", { it.isEmpty() }, demand()) { priority -> queue.run(priority) { source.search(query) } }

    override suspend fun byId(id: String, query: ScrapeQuery): ApiResult<ProviderGame?> =
        reuse.run("id:$id:$query", { it == null }, demand()) { priority -> queue.run(priority) { source.byId(id, query) } }

    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>): ApiResult<List<ArtworkOption>> =
        reuse.run("art:${game.providerGameId}:$query:${kinds.map { it.name }.sorted()}", { it.isEmpty() }, demand()) { priority ->
            queue.run(priority) { source.artwork(game, query, kinds) }
        }
}
