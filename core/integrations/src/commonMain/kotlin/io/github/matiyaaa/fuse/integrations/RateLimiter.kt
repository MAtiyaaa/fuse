package io.github.matiyaaa.fuse.integrations

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

/**
 * Per-host request pacing: at most [maxConcurrency] calls in flight for one host, and consecutive
 * call starts at least [minIntervalMillis] apart. Suspends instead of blocking. One instance is
 * normally shared by every client talking to the same provider.
 *
 * [nowMillis] is a monotonic clock; tests pass the coroutine test scheduler's virtual time.
 */
class RateLimiter(
    val minIntervalMillis: Long,
    val maxConcurrency: Int = 1,
    private val nowMillis: () -> Long = monotonicClock(),
) {
    init {
        require(minIntervalMillis >= 0) { "minIntervalMillis must be >= 0" }
        require(maxConcurrency >= 1) { "maxConcurrency must be >= 1" }
    }

    private class Gate(concurrency: Int) {
        val permits = Semaphore(concurrency)
        val spacing = Mutex()
        var lastStart: Long? = null
    }

    private val gatesLock = Mutex()
    private val gates = HashMap<String, Gate>()

    /** Runs [block] once a slot for [host] is free and the minimum interval has passed. */
    suspend fun <T> withPermit(host: String, block: suspend () -> T): T {
        val gate = gatesLock.withLock { gates.getOrPut(host.lowercase()) { Gate(maxConcurrency) } }
        return gate.permits.withPermit {
            gate.spacing.withLock {
                val last = gate.lastStart
                if (last != null) {
                    val wait = last + minIntervalMillis - nowMillis()
                    if (wait > 0) delay(wait)
                }
                gate.lastStart = nowMillis()
            }
            block()
        }
    }

    companion object {
        /** No pacing at all; for tests and for hosts without limits. */
        fun unlimited(): RateLimiter = RateLimiter(minIntervalMillis = 0, maxConcurrency = 64)

        /** A monotonic millisecond clock starting at zero. */
        fun monotonicClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
