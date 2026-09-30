package io.github.matiyaaa.fuse

import android.content.ActivityNotFoundException
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges one activity-result launcher to a suspend call. A new request cancels an unanswered one
 * (it completes with [cancelled]) so no caller waits forever.
 */
internal class ResultSlot<O>(private val cancelled: O) {
    private var pending: CompletableDeferred<O>? = null

    fun complete(value: O) {
        pending?.complete(value)
        pending = null
    }

    fun cancel() = complete(cancelled)

    suspend fun request(launch: () -> Unit): O {
        cancel()
        val deferred = CompletableDeferred<O>()
        pending = deferred
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            // No app on the device handles this request (for example no document picker).
            cancel()
        }
        return deferred.await()
    }
}
