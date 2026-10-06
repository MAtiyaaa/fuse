package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferSettings
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.TransferSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What can be done with one transfer from the Downloads page. */
enum class TransferAction(val label: String) {
    PAUSE("Pause"),
    RESUME("Resume"),
    RETRY("Try Again"),
    CANCEL("Cancel"),
    MOVE_UP("Move Up"),
    MOVE_DOWN("Move Down"),
    MOVE_TO_TOP("Move to Top"),
    REMOVE("Remove from List"),

    /** Opens where the transfer really runs (Cartridge, the Store's app page). */
    OPEN("Open"),
}

/**
 * One row of the Downloads page. [mirrored] names the app or part of Fuse that runs it when it isn't
 * Fuse's own queue (Cartridge's downloads, the Store's installs, a Fuse update): Fuse shows them all
 * in one place and offers what that source allows.
 */
@Immutable
data class TransferRow(
    val item: TransferItem,
    val actions: List<TransferAction>,
    val mirrored: String? = null,
    /** The game in Fuse's library this is (a RomM download once it landed, an upload), for its art. */
    val game: io.github.matiyaaa.fuse.model.GameId? = null,
)

/**
 * Downloads: every transfer Fuse makes for the person, in one list (Fuse RomM's games, uploads and
 * BIOS, Jellyfin's films, apps and emulators, Fuse's own update), plus Cartridge's downloads as it
 * reports them. [rows] changes only when a transfer starts, stops or moves; the moving numbers of each
 * are in [live], so a page with many transfers redraws only the rows whose numbers move.
 */
interface TransfersOps {
    val rows: StateFlow<List<TransferRow>>
    val summary: StateFlow<TransferSummary>
    val settings: StateFlow<TransferSettings>

    fun live(id: String): StateFlow<TransferLive>
    fun act(id: String, action: TransferAction)
    fun pauseAll()
    fun resumeAll()
    fun clearFinished()

    object None : TransfersOps {
        override val rows: StateFlow<List<TransferRow>> = MutableStateFlow(emptyList())
        override val summary: StateFlow<TransferSummary> = MutableStateFlow(TransferSummary())
        override val settings: StateFlow<TransferSettings> = MutableStateFlow(TransferSettings())
        override fun live(id: String): StateFlow<TransferLive> = MutableStateFlow(TransferLive())
        override fun act(id: String, action: TransferAction) = Unit
        override fun pauseAll() = Unit
        override fun resumeAll() = Unit
        override fun clearFinished() = Unit
    }
}

/** Which transfers a Downloads filter shows. */
enum class TransferFilter(val label: String) {
    ALL("All"),
    DOWNLOADS("Downloads"),
    UPLOADS("Uploads"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    ;

    fun shows(row: TransferRow): Boolean = when (this) {
        ALL -> true
        DOWNLOADS -> !row.item.upload && !row.item.status.finished
        UPLOADS -> row.item.upload && !row.item.status.finished
        COMPLETED -> row.item.status == TransferStatus.DONE
        FAILED -> row.item.status == TransferStatus.FAILED
    }
}

/** The order the page lists transfers in: moving first, then waiting and queued (in queue order), paused, failed, then finished newest first. */
fun transferOrder(rows: List<TransferRow>): List<TransferRow> = rows.sortedWith(
    compareBy<TransferRow>(
        {
            when (it.item.status) {
                TransferStatus.ACTIVE -> 0
                TransferStatus.WAITING -> 1
                TransferStatus.QUEUED -> 2
                TransferStatus.PAUSED -> 3
                TransferStatus.FAILED -> 4
                TransferStatus.DONE -> 5
                TransferStatus.CANCELLED -> 6
            }
        },
        { if (it.item.status.finished) -(it.item.finishedAt ?: 0) else it.item.order },
        { it.item.createdAt },
    ),
)
