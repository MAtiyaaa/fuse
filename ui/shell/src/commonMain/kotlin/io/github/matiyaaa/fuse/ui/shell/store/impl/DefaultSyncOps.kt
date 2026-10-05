package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.ui.shell.store.SyncOps
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Fuse Sync in the store: the service the platform built ([io.github.matiyaaa.fuse.ui.shell.store.FuseServices.syncService])
 * over this library ([LibraryProfileData]), and its settings as the database has them (the service
 * writes some itself, the profile in use among them).
 */
internal class DefaultSyncOps(
    private val ctx: StoreContext,
    val port: LibraryProfileData,
    /** Changes the settings and has the interface follow. */
    private val write: suspend ((AppSettings) -> AppSettings) -> Unit,
) : SyncOps {
    override val service: SyncService? = runCatching { ctx.services.syncService(port, ctx.scope) }.getOrNull()

    override val config: StateFlow<SyncSettings> = ctx.data.settings.settings.map { it.sync }
        .stateIn(ctx.scope, SharingStarted.Eagerly, ctx.settings.value.sync)

    override suspend fun configure(change: (SyncSettings) -> SyncSettings) {
        write { it.copy(sync = change(it.sync)) }
        service?.changed()
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val s = service
        if (s != null) s.setEnabled(enabled) else configure { it.copy(enabled = enabled) }
    }

    override suspend fun setOwnHome(own: Boolean) {
        write { s ->
            val scope = s.sync.homeScope
            when {
                own && scope != LibraryProfileData.HOME_DEVICE ->
                    // This device's own Home starts as the one it had last time, or the profile's as it is now.
                    s.copy(sync = s.sync.copy(homeScope = LibraryProfileData.HOME_DEVICE), home = s.home.copy(layout = s.sync.deviceHome ?: s.home.layout))
                !own && scope == LibraryProfileData.HOME_DEVICE ->
                    s.copy(sync = s.sync.copy(homeScope = LibraryProfileData.HOME_REJOIN, deviceHome = s.home.layout))
                else -> s
            }
        }
        // The profile's Home comes back on the next round, which is now.
        if (!own) service?.let { svc -> ctx.scope.launch { runCatching { svc.syncNow() } } }
    }
}
