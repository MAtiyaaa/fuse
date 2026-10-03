package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.library.content.ContentPlan
import io.github.matiyaaa.fuse.library.content.ContentState
import io.github.matiyaaa.fuse.library.content.PlanItem
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How a game's content gets into its emulator here. */
enum class InstallMode {
    /** Fuse runs the emulator's own installer, step by step, and checks each step took. */
    FUSE,

    /** The emulator only installs from its own screens (every Android PS3 and Vita emulator): Fuse lists what to install, in order. */
    GUIDED,

    /** No emulator here installs this game's content. */
    UNAVAILABLE,
}

/**
 * A game's installable content for the emulator that plays it: what is on disk, what the emulator
 * already holds, and what is left, in order.
 */
data class GameContentView(
    val gameId: GameId,
    val title: String,
    val platformName: String,
    val emulatorId: EmulatorId?,
    /** The emulator that installs it (RPCS3, Vita3K), or the one to install it in by hand. */
    val emulatorName: String?,
    val mode: InstallMode,
    val plan: ContentPlan,
    /** Why it can't install here, or what to know first. */
    val note: String? = null,
    /** Words for where a file is (USB drive, SD card), by path prefix. */
    val places: List<Pair<String, String>> = emptyList(),
    /** Files that look like content but couldn't be read (a damaged download). */
    val unreadable: List<String> = emptyList(),
) {
    val states: List<ContentState> get() = plan.states

    /** Where [path] is, in words, or null on the main drive. */
    fun placeOf(path: String): String? = places.firstOrNull { path.startsWith(it.first) }?.second
}

/** An install that is running: step [step] of [of], and the last line the installer printed. */
data class InstallProgress(val gameId: GameId, val step: Int, val of: Int, val item: PlanItem, val line: String? = null)

/** How an install went. Nothing is reported installed unless Fuse saw it in the emulator's storage. */
data class InstallReport(
    val installed: List<PlanItem>,
    val failed: PlanItem? = null,
    /** What went wrong, for people. */
    val message: String? = null,
    /** The end of what the installer printed (licence keys removed). */
    val details: String? = null,
    val cancelled: Boolean = false,
) {
    val ok: Boolean get() = failed == null && !cancelled && message == null
}

/**
 * Fuse's own installs of PlayStation 3 and Vita content (games, updates, DLC, licences) into RPCS3
 * and Vita3K, from any drive, independent of RomM and Cartridge.
 */
interface ContentOps {
    /** The install that is running, if any. */
    val progress: StateFlow<InstallProgress?>

    /**
     * What [id] has to install, or null for a game with nothing to install (another system, or no
     * package among its files). [picked] are licence files the user chose by content id, [keys]
     * zRIFs they pasted; both are used for this plan only and never stored.
     */
    suspend fun view(id: GameId, picked: Map<String, String> = emptyMap(), keys: Map<String, String> = emptyMap()): GameContentView?

    /** Installs what is left, in order, checking each step; stops at the first one that didn't take. */
    suspend fun install(id: GameId, picked: Map<String, String> = emptyMap(), keys: Map<String, String> = emptyMap()): InstallReport

    /** Stops the running install (the emulator's installer is closed; a retry starts where it left off). */
    fun cancel()

    object None : ContentOps {
        override val progress: StateFlow<InstallProgress?> = MutableStateFlow(null)
        override suspend fun view(id: GameId, picked: Map<String, String>, keys: Map<String, String>): GameContentView? = null
        override suspend fun install(id: GameId, picked: Map<String, String>, keys: Map<String, String>) =
            InstallReport(emptyList(), message = "Installing isn't available here.")
        override fun cancel() = Unit
    }
}
