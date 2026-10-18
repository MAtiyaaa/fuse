package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whether the device is awake for Fuse's own rounds on the network: its screen on, or Fuse holding
 * it awake for work (a transfer). A handheld asleep in a bag gets none of them. The long wait for
 * the host's news, the look at who is around and the household's lists answered every half minute
 * all night, and each answer woke the device and its Wi-Fi again; now they wait for it to wake and
 * catch up at once when it does. Saves are never held by this: a save made as the screen goes off
 * still goes (Android's PlaySyncService), and so does any change sent on its own.
 */
object Awake {
    /** For a device that never sleeps under Fuse (a computer, the host). */
    val always: StateFlow<Boolean> = MutableStateFlow(true)
}

/** Suspends until the device is awake. */
internal suspend fun StateFlow<Boolean>.awaitAwake() {
    if (!value) first { it }
}

/**
 * [block] while the device stays awake: cancelled as soon as it sleeps, and then null (also null
 * when it is asleep already). A failure in [block] is thrown as it is.
 */
internal suspend fun <T> StateFlow<Boolean>.untilAsleep(block: suspend () -> T): T? {
    if (!value) return null
    return coroutineScope {
        val work = async { block() }
        val sleep = launch {
            first { !it }
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // Cancelled by sleep, not from outside: nothing came, and that is all.
            if (isActive && work.isCancelled) null else throw e
        } finally {
            sleep.cancel()
        }
    }
}
