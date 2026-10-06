package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuse Sync by Fuse, as the interface uses it: the service (setting up, profiles, saves) and its
 * settings. While it is off, nothing of it shows anywhere and nothing runs.
 */
interface SyncOps {
    /** Fuse Sync on this device, or null where it can't run (a build or test without it). */
    val service: SyncService?

    /** Its settings as stored: on or off, the role, what syncs, the profile at startup, Home's scope. */
    val config: StateFlow<SyncSettings>

    /** True while it is on here: the gate for every piece of its interface. */
    val inUse: Boolean get() = service != null && config.value.enabled

    suspend fun configure(change: (SyncSettings) -> SyncSettings)

    /** Turns it on or off here; off keeps everything on this device as it is (the people who played here too, with [keepProfiles]). */
    suspend fun setEnabled(enabled: Boolean, keepProfiles: Boolean = true)

    /**
     * Home on this device: the profile's, the same on every device ([own] false), or this device's
     * own ([own] true). Switching keeps both: the profile's Home is untouched while this device has
     * its own, and this device's comes back when it is chosen again.
     */
    suspend fun setOwnHome(own: Boolean)

    /** What Fuse Sync knows [game]'s saves by here (its emulator, files and names), or null. */
    suspend fun saveQuery(game: io.github.matiyaaa.fuse.model.GameId): io.github.matiyaaa.fuse.sync.SaveQuery? = null

    /** Where each emulator in the library keeps its saves here (Settings, Save folders), for Fuse Sync and Syncthing alike. */
    suspend fun saveFolders(): List<io.github.matiyaaa.fuse.sync.EmulatorSaves> = emptyList()

    /** Keeps [emulator]'s saves at [path] from now on, or (null) where Fuse finds them by itself. */
    suspend fun setSaveFolder(emulator: String, path: String?) =
        configure { s -> s.copy(saveFolders = if (path.isNullOrBlank()) s.saveFolders - emulator else s.saveFolders + (emulator to path)) }

    object None : SyncOps {
        override val service: SyncService? = null
        override val config: StateFlow<SyncSettings> = MutableStateFlow(SyncSettings())
        override suspend fun configure(change: (SyncSettings) -> SyncSettings) = Unit
        override suspend fun setEnabled(enabled: Boolean, keepProfiles: Boolean) = Unit
        override suspend fun setOwnHome(own: Boolean) = Unit
    }
}
