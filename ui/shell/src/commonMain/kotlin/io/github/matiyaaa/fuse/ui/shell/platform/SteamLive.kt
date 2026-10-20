package io.github.matiyaaa.fuse.ui.shell.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * One change to Steam's library made through Steam's own running client, by the Fuse Decky plugin
 * (bridge protocol 2) or by Fuse itself through Steam's local debug port ([SteamClientLink]), so
 * Steam needn't close or restart for it. [appId] is the entry's id in Steam
 * (unsigned 32-bit) for an update, a removal or a start ([Op.RUN]: Steam starts the entry as if it
 * were chosen in its library). [art] names Fuse's own picture files by Steam's asset type
 * (`capsule`, `hero`, `logo`, `header`, `icon`); the plugin fetches each through the bridge, never
 * by path. [logo] is where the logo sits over the hero, set only on an entry with no position of
 * the person's own.
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
    val logo: SteamLogo? = null,
) {
    enum class Op { ADD, UPDATE, REMOVE, RUN }
}

/**
 * Where an entry's logo sits over its hero, as Steam's client takes it (`LogoPosition` in
 * `@decky/ui`'s App.d.ts: `pinnedPosition` and its size as percentages of the hero).
 */
@Serializable
data class SteamLogo(val pinned: String, val widthPct: Int, val heightPct: Int) {
    companion object {
        /** As Fuse's own game page places a logo over its picture: pinned bottom left, up to 70% of its width and about a third of its height. */
        val FUSE = SteamLogo("BottomLeft", 70, 35)
    }
}

/** How one change went: the entry's id in Steam (a new entry's, for an add), or why it failed. */
data class SteamLiveResult(val ok: Boolean, val appId: Long? = null, val message: String? = null)

/**
 * Changes waiting to be made in Steam's running client, and what was made this way while Steam has
 * been running (Steam writes its library file only when it quits, so until then the file doesn't
 * show them; [made] does). Whatever makes them (the Fuse Decky plugin, or Fuse through Steam's
 * local debug port) takes the waiting changes when it asks ([take], which also says it is there),
 * makes each, and reports ([done]). Each change is handed out once, so the two can never both make
 * it.
 *
 * Steam's client counts as reachable ([connected]) for a few seconds after something last asked:
 * each asks every couple of seconds while Steam runs. Changes not made in time are taken back
 * ([offer] gives up on them), and Fuse goes the usual way: written when Steam closes.
 */
class SteamLiveQueue(private val now: () -> Long) {
    private val lock = Mutex()
    private val waiting = LinkedHashMap<Long, Pair<SteamLiveChange, CompletableDeferred<SteamLiveResult>>>()
    private val handedOut = HashSet<Long>()
    private val madeHere = HashMap<Long, Long>()
    private val notices = ArrayDeque<String>()
    private var next = 1L

    @kotlin.concurrent.Volatile private var lastAsked = Long.MIN_VALUE / 2

    /** Something asked recently, so changes can go through Steam's running client. */
    val connected: Boolean get() = now() - lastAsked < CONNECTED_MS

    /** Fuse's entries made or changed in Steam's running client since Steam started: game id to the entry's id in Steam. */
    suspend fun made(): Map<Long, Long> = lock.withLock { HashMap(madeHere) }

    /**
     * Queues [changes] for Steam's running client and waits up to [waitMs] for them to be made. The
     * results are in the same order; a change not made in time is taken back and comes back as not
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
                // Taken back, unless it was reported meanwhile (then its answer is in).
                val takenBack = lock.withLock { (waiting.remove(id) != null).also { handedOut.remove(id) } }
                if (takenBack) SteamLiveResult(false, message = "Steam didn't answer in time") else result.await()
            }
        }
    }

    /**
     * The changes waiting that nobody has taken yet, to make; each is handed out once. Asking also
     * says Steam's client is reachable.
     */
    suspend fun take(): List<SteamLiveChange> = lock.withLock {
        lastAsked = now()
        waiting.entries.filter { handedOut.add(it.key) }.map { it.value.first }
    }

    /** Says Steam's client is reachable without taking anything (for what asks only to keep that true). */
    fun reachable() {
        lastAsked = now()
    }

    /** A waiting change, by its id (for its pictures). */
    suspend fun change(id: Long): SteamLiveChange? = lock.withLock { waiting[id]?.first }

    /** Change [id] was made (or couldn't be). False for a change no longer waiting. */
    suspend fun done(id: Long, result: SteamLiveResult): Boolean = lock.withLock {
        handedOut.remove(id)
        val (change, deferred) = waiting.remove(id) ?: return@withLock false
        if (result.ok) {
            when (change.op) {
                SteamLiveChange.Op.ADD, SteamLiveChange.Op.UPDATE -> (result.appId ?: change.appId)?.let { madeHere[change.gameId] = it }
                SteamLiveChange.Op.REMOVE -> madeHere.remove(change.gameId)
                SteamLiveChange.Op.RUN -> Unit
            }
        }
        deferred.complete(result)
        true
    }

    /**
     * Something Fuse would say on its own screen, for Steam to show instead (a game is in front of
     * Fuse in Game Mode). Kept only while the plugin is there to show it, and only the latest few.
     */
    suspend fun notify(message: String) {
        if (!connected) return
        lock.withLock {
            notices.remove(message)
            notices.addLast(message)
            while (notices.size > MAX_NOTICES) notices.removeFirst()
        }
    }

    /** The notices waiting for Steam to show, each once. */
    suspend fun takeNotices(): List<String> = lock.withLock { notices.toList().also { notices.clear() } }

    /** Steam closed: it wrote what was made into its library file, which says it from now on. */
    suspend fun steamClosed() = lock.withLock { madeHere.clear() }

    companion object {
        /** How long after something last asked Steam's client still counts as reachable. */
        const val CONNECTED_MS = 10_000L

        /** How long a change waits to be made before Fuse goes the usual way. */
        const val OFFER_WAIT_MS = 15_000L

        /** The most notices kept for Steam to show. */
        const val MAX_NOTICES = 4
    }
}

/**
 * Steam's own running client, reached through the local debug port Steam opens when a file named
 * `.cef-enable-remote-debugging` is in its folder (as Decky Loader's installer sets it up). Through
 * it Fuse makes [SteamLiveQueue]'s changes itself, with the same calls the Fuse Decky plugin makes,
 * so Steam's library changes while Steam runs with no plugin at all. Off until the person turns it
 * on: the port lets programs on this computer reach Steam's client.
 */
interface SteamClientLink {
    /** Steam is set to open its local debug port when it starts. */
    suspend fun allowed(): Boolean

    /** Sets it [on] (or off) for every Steam here; Steam takes either the next time it starts. */
    suspend fun allow(on: Boolean): Result<String>

    /** Steam's running client answers on the port right now. */
    suspend fun listening(): Boolean
}
