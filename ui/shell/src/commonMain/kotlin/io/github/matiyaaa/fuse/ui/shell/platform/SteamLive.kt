package io.github.matiyaaa.fuse.ui.shell.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * One change to Steam's library made through Steam's own running client by the Fuse Decky plugin
 * (bridge protocol 2), so Steam needn't close or restart for it. [appId] is the entry's id in Steam
 * (unsigned 32-bit) for an update or a removal. [art] names Fuse's own picture files by Steam's
 * asset type (`capsule`, `hero`, `logo`, `header`, `icon`); the plugin fetches each through the
 * bridge, never by path.
 */
@Serializable
data class SteamLiveChange(
    val id: Long = 0,
    val op: Op,
    val gameId: Long,
    val appId: Long? = null,
    val name: String = "",
    val exe: String = "",
    val startDir: String = "",
    val launchOptions: String = "",
    val art: Map<String, String> = emptyMap(),
) {
    enum class Op { ADD, UPDATE, REMOVE }
}

/** How the plugin says one change went: the entry's id in Steam (a new entry's, for an add), or why it failed. */
data class SteamLiveResult(val ok: Boolean, val appId: Long? = null, val message: String? = null)

/**
 * Changes waiting for the Fuse Decky plugin to make them in Steam's running client, and what was
 * made this way while Steam has been running (Steam writes its library file only when it quits, so
 * until then the file doesn't show them; [made] does). The plugin takes the waiting changes when it
 * asks ([take], which also says it is there), makes each, and reports ([done]).
 *
 * The plugin counts as there ([connected]) for a few seconds after it last asked: it asks every
 * couple of seconds while Steam runs. Changes it doesn't make in time are taken back ([offer] gives
 * up on them), and Fuse goes the usual way: written when Steam closes.
 */
class SteamLiveQueue(private val now: () -> Long) {
    private val lock = Mutex()
    private val waiting = LinkedHashMap<Long, Pair<SteamLiveChange, CompletableDeferred<SteamLiveResult>>>()
    private val madeHere = HashMap<Long, Long>()
    private var next = 1L

    @kotlin.concurrent.Volatile private var lastAsked = Long.MIN_VALUE / 2

    /** The plugin asked recently, so changes can go through Steam's running client. */
    val connected: Boolean get() = now() - lastAsked < CONNECTED_MS

    /** Fuse's entries made or changed through the plugin since Steam started: game id to the entry's id in Steam. */
    suspend fun made(): Map<Long, Long> = lock.withLock { HashMap(madeHere) }

    /**
     * Queues [changes] for the plugin and waits up to [waitMs] for it to make them. The results are
     * in the same order; a change the plugin didn't make in time is taken back and comes back as not
     * done, so nothing is half made by both ways.
     */
    suspend fun offer(changes: List<SteamLiveChange>, waitMs: Long = OFFER_WAIT_MS): List<SteamLiveResult> {
        val queued = lock.withLock {
            changes.map { c ->
                val id = next++
                val result = CompletableDeferred<SteamLiveResult>()
                waiting[id] = c.copy(id = id) to result
                id to result
            }
        }
        return queued.map { (id, result) ->
            withTimeoutOrNull(waitMs) { result.await() } ?: run {
                // Taken back, unless the plugin reported it meanwhile (then its answer is in).
                val takenBack = lock.withLock { waiting.remove(id) != null }
                if (takenBack) SteamLiveResult(false, message = "The Fuse plugin didn't answer in time") else result.await()
            }
        }
    }

    /** The changes waiting, for the plugin to make; asking also says the plugin is there. */
    suspend fun take(): List<SteamLiveChange> = lock.withLock {
        lastAsked = now()
        waiting.values.map { it.first }
    }

    /** A waiting change, by its id (for its pictures). */
    suspend fun change(id: Long): SteamLiveChange? = lock.withLock { waiting[id]?.first }

    /** The plugin made (or couldn't make) change [id]. False for a change no longer waiting. */
    suspend fun done(id: Long, result: SteamLiveResult): Boolean = lock.withLock {
        val (change, deferred) = waiting.remove(id) ?: return@withLock false
        if (result.ok) {
            when (change.op) {
                SteamLiveChange.Op.ADD, SteamLiveChange.Op.UPDATE -> (result.appId ?: change.appId)?.let { madeHere[change.gameId] = it }
                SteamLiveChange.Op.REMOVE -> madeHere.remove(change.gameId)
            }
        }
        deferred.complete(result)
        true
    }

    /** Steam closed: it wrote what was made into its library file, which says it from now on. */
    suspend fun steamClosed() = lock.withLock { madeHere.clear() }

    companion object {
        /** How long after the plugin last asked it still counts as there. */
        const val CONNECTED_MS = 10_000L

        /** How long a change waits for the plugin before Fuse goes the usual way. */
        const val OFFER_WAIT_MS = 15_000L
    }
}
