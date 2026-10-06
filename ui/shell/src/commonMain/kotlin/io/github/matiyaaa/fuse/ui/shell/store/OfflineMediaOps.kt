package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.OfflineEntry
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Where one film or episode stands on this device. */
sealed interface OfflineState {
    data object None : OfflineState

    /** On its way through Downloads: [progress] 0..1 when known, [waiting] when it waits for something. */
    data class Downloading(val progress: Float?, val waiting: Boolean = false) : OfflineState

    /** Kept here and ready to play. */
    data class Here(val entry: OfflineEntry) : OfflineState

    /** Kept on a drive that isn't connected now. */
    data class Away(val entry: OfflineEntry) : OfflineState

    data class Failed(val message: String) : OfflineState
}

/** What a download of a film, an episode, a season or a show would bring: shown before it starts. */
@Immutable
data class OfflinePlanView(
    val title: String,
    val items: List<MediaItem>,
    /** Already here or on the way, so left out. */
    val already: Int,
    val totalBytes: Long,
    val folder: String,
    val problem: String? = null,
)

/**
 * Films and episodes from Jellyfin kept on this device for watching without the server: downloaded
 * through Downloads, played by Fuse Player from the file, listed and tidied on the Storage page
 * (delete, move to another drive). A kept film on a drive that isn't connected stays listed, never
 * forgotten on its own.
 */
interface OfflineMediaOps {
    val supported: Boolean
    val entries: StateFlow<List<OfflineEntry>>

    /** Where new downloads go. */
    val folder: StateFlow<String>

    fun state(itemId: String): Flow<OfflineState>

    /** What downloading [item] (a film, an episode, a season or a whole show) would bring. */
    suspend fun plan(item: MediaItem): OfflinePlanView

    /** Starts [plan]'s downloads; returns how many were queued. */
    suspend fun start(plan: OfflinePlanView): Int

    /** Deletes the kept files of [keys] (a drive that isn't connected only forgets them, its files stay). */
    suspend fun remove(keys: List<String>): Int

    /** Moves [keys] to [folder] (another drive) through Downloads; returns how many will move. */
    suspend fun move(keys: List<String>, folder: String): Int

    suspend fun entry(itemId: String): OfflineEntry?

    /** Plays kept films and episodes from their files. */
    fun resolver(): PlaybackResolver?

    /** Looks again at which drives are here. */
    fun refresh()

    object None : OfflineMediaOps {
        override val supported = false
        override val entries: StateFlow<List<OfflineEntry>> = MutableStateFlow(emptyList())
        override val folder: StateFlow<String> = MutableStateFlow("")
        override fun state(itemId: String): Flow<OfflineState> = flowOf(OfflineState.None)
        override suspend fun plan(item: MediaItem) = OfflinePlanView(item.name, emptyList(), 0, 0, "", "Downloads aren't available here.")
        override suspend fun start(plan: OfflinePlanView) = 0
        override suspend fun remove(keys: List<String>) = 0
        override suspend fun move(keys: List<String>, folder: String) = 0
        override suspend fun entry(itemId: String): OfflineEntry? = null
        override fun resolver(): PlaybackResolver? = null
        override fun refresh() = Unit
    }
}
