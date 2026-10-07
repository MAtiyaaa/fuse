package io.github.matiyaaa.fuse.transfer

import io.github.matiyaaa.fuse.model.StorageVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Does one kind of transfer for the [TransferManager]: a RomM game, a Jellyfin film, an upload.
 * [run] moves the bytes through [io] and returns once the result is checked and in place; it throws
 * [TransferInterrupted] (or an [IOException]) when the network or server went away (Fuse waits and
 * tries again, carrying on from where it was), [DriveMissing] when a drive went, and
 * [TransferFailure] when it can't be done.
 */
interface TransferHandler {
    val source: String

    suspend fun run(item: TransferItem, io: TransferIo)

    /** The person cancelled [item]: whatever it left half done (a partial file, an upload session) goes. */
    suspend fun discard(item: TransferItem, io: TransferIo) {}

    /** Called once [item] finished well, outside the transfer's own work (rescans, notices). */
    suspend fun done(item: TransferItem) {}
}

/** What a running transfer may ask of the manager. */
interface TransferIo {
    /** A folder kept for this transfer until it finishes (its partial files, if it can't keep them beside the destination). */
    val workDir: String

    /** Where [place] is now; throws [DriveMissing] when its drive isn't connected. */
    suspend fun resolve(place: TransferPlace): String

    /** Bytes moved so far, and the total when known. Cheap: call it as often as bytes move. */
    fun progress(done: Long, total: Long? = null)

    fun phase(phase: TransferPhase)

    /** Keeps the handler's own note with the transfer (survives a restart). */
    suspend fun note(payload: String)

    /** Waits until [bytes] more may move, under the bandwidth limit and the while-playing rule. */
    suspend fun throttle(bytes: Int)
}

/**
 * Fuse's one queue of transfers (see [newTransfers]). Every download and upload Fuse makes for the
 * person goes through it, so the Downloads page shows them all and their limits hold together.
 */
interface Transfers {
    val items: StateFlow<List<TransferItem>>
    val settings: StateFlow<TransferSettings>
    val summary: StateFlow<TransferSummary>

    fun start()
    fun register(handler: TransferHandler)
    fun configure(settings: TransferSettings)
    fun conditions(c: TransferConditions)
    fun drivesChanged()

    /** A device came back or went: transfers waiting for one look again at once. */
    fun devicesChanged() {}
    fun live(id: String): StateFlow<TransferLive>
    suspend fun enqueue(item: TransferItem): String
    fun pause(id: String): Job
    fun resume(id: String): Job
    fun retry(id: String): Job
    fun pauseAll(): Job
    fun resumeAll(): Job
    fun cancel(id: String): Job
    fun moveUp(id: String): Job
    fun moveDown(id: String): Job
    fun moveToTop(id: String): Job
    fun clearFinished(): Job
    fun remove(id: String): Job
}

/** Fuse's transfers, kept in [dir] (a folder of Fuse's own data, never its cache). */
expect fun newTransfers(dir: String, scope: CoroutineScope, clock: () -> Long, volumes: suspend () -> List<StorageVolume>): Transfers
