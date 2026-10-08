package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderWorkTest {
    @Test fun visibleFollowerPromotesSharedQueuedWorkWithoutStartingAnotherCall() = runTest {
        val queue = ProviderWork(1)
        val reuse = RequestReuse { testScheduler.currentTime }
        val holding = CompletableDeferred<Unit>()
        val first = launch { queue.run(ScrapeDemand.BACKGROUND) { holding.await() } }
        runCurrent()
        val order = mutableListOf<String>()
        val bulk = launch { queue.run(ScrapeDemand.BACKGROUND) { order += "bulk" } }
        var calls = 0
        val owner = async {
            reuse.run("shared", demand = ScrapeDemand.BACKGROUND) { priority ->
                queue.run(priority) { calls++; order += "shared"; ApiResult.Success("art") }
            }
        }
        runCurrent()
        val foreground = async {
            reuse.run<String>("shared", demand = ScrapeDemand.VISIBLE) { error("must join owner") }
        }
        val backgroundFollower = async {
            reuse.run<String>("shared", demand = ScrapeDemand.BACKGROUND) { error("must not lower priority") }
        }
        runCurrent()
        holding.complete(Unit)
        first.join(); bulk.join()
        assertEquals("art", assertIs<ApiResult.Success<String>>(owner.await()).value)
        assertEquals(owner.await(), foreground.await())
        assertEquals(owner.await(), backgroundFollower.await())
        assertEquals(listOf("shared", "bulk"), order)
        assertEquals(1, calls)
    }

    @Test fun cancelledOwnerDoesNotCancelAnotherScreensLiveRequest() = runTest {
        val reuse = RequestReuse { testScheduler.currentTime }
        val entered = CompletableDeferred<Unit>()
        val owner = launch {
            reuse.run("shared") { entered.complete(Unit); delay(1000); ApiResult.Success("old") }
        }
        entered.await()
        var replacementCalls = 0
        val follower = async { reuse.run("shared") { replacementCalls++; ApiResult.Success("ready") } }
        runCurrent()
        owner.cancel()
        owner.join()
        assertEquals("ready", assertIs<ApiResult.Success<String>>(follower.await()).value)
        assertEquals(1, replacementCalls)
    }

    @Test fun fiveIdenticalRequestsUseOneCallAndSuccessfulResultCache() = runTest {
        val reuse = RequestReuse { testScheduler.currentTime }
        var calls = 0
        val tasks = List(5) { async { reuse.run("game:42") { calls++; delay(20); ApiResult.Success("art") } } }
        tasks.forEach { assertEquals("art", assertIs<ApiResult.Success<String>>(it.await()).value) }
        reuse.run("game:42") { calls++; ApiResult.Success("wrong") }
        assertEquals(1, calls)
    }

    @Test fun failuresAreRetryableAndNegativeAnswersExpireSooner() = runTest {
        var now = 0L
        val reuse = RequestReuse { now }
        var calls = 0
        repeat(2) { reuse.run<String>("failure") { calls++; ApiResult.NetworkError("offline") } }
        assertEquals(2, calls)
        reuse.run("missing", { it.isEmpty() }) { calls++; ApiResult.Success(emptyList<String>()) }
        now = 300001
        reuse.run("missing", { it.isEmpty() }) { calls++; ApiResult.Success(listOf("found")) }
        assertEquals(4, calls)
    }

    @Test fun explicitRetryRefreshesOnlyMissingAnswers() = runTest {
        val reuse = RequestReuse { 0L }
        var calls = 0
        suspend fun ask(retry: Boolean = false) = reuse.run("missing", { it.isEmpty() }, retryMissing = retry) {
            calls++
            ApiResult.Success(if (calls == 1) emptyList<String>() else listOf("found"))
        }
        ask(); ask()
        assertEquals(1, calls)
        assertEquals(listOf("found"), assertIs<ApiResult.Success<List<String>>>(ask(true)).value)
        ask(true)
        assertEquals(2, calls)
    }

    @Test fun visibleWorkPassesQueuedBulkAndCancelledWaiterDoesNotStrandQueue() = runTest {
        val queue = ProviderWork(1)
        val holding = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { queue.run(ScrapeDemand.BACKGROUND) { holding.await() } }
        runCurrent()
        val cancelled = launch { queue.run(ScrapeDemand.VISIBLE) { error("cancelled") } }
        val bulk = launch { queue.run(ScrapeDemand.BACKGROUND) { order += "bulk" } }
        val visible = launch { queue.run(ScrapeDemand.VISIBLE) { order += "visible" } }
        runCurrent()
        cancelled.cancel(); runCurrent(); holding.complete(Unit)
        first.join(); bulk.join(); visible.join()
        assertEquals(listOf("visible", "bulk"), order)
    }
}
