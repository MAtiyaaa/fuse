package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingConflict
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingDevice
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingFolder
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingGate
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingInstall
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingPendingDevice
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingPlanFolder
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingService
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Syncthing for the audit: one running on this computer, a Steam Deck and a phone it syncs with,
 * and the save folders of the library's emulators, so every state of its page can be shown
 * without a Syncthing. Whether it is on is written to the store's own settings, as the real one does.
 */
internal class AuditSyncthing(private val settings: SettingsStore) : SyncthingService {
    override val state = MutableStateFlow<SyncthingState>(SyncthingState.Off)
    override val devices = MutableStateFlow<List<SyncthingDevice>>(emptyList())
    override val pendingDevices = MutableStateFlow<List<SyncthingPendingDevice>>(emptyList())
    override val folders = MutableStateFlow<List<SyncthingFolder>>(emptyList())
    override val install = SyncthingInstall("Syncthing", "https://syncthing.net/downloads/", "Free, from syncthing.net. Install it, start it, then look again", canStart = false)

    /** What the next look finds. */
    @Volatile var finds: SyncthingState = SyncthingState.NotFound(installed = false)

    override fun useLibrary(samples: suspend () -> List<SaveQuery>) = Unit

    override suspend fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(syncthing = it.syncthing.copy(enabled = enabled)) }
        if (enabled) find() else state.value = SyncthingState.Off
    }

    override suspend fun find(): SyncthingState = finds.also { state.value = it }

    override suspend fun connect(address: String, apiKey: String): Result<SyncthingState> = Result.success(connected().also { household() })

    override fun startApp() = false

    override suspend fun addDevice(id: String, name: String): Result<Unit> = Result.success(Unit)

    override suspend fun removeDevice(id: String): Result<Unit> = Result.success(Unit)

    override suspend fun plan(samples: List<SaveQuery>) = planned

    override suspend fun planLibrary() = planned

    override suspend fun share(folders: List<SyncthingPlanFolder>, keepVersions: Boolean): Result<Int> = Result.success(folders.size)

    override suspend fun unshare(folderId: String): Result<Unit> = Result.success(Unit)

    override suspend fun refresh() = Unit

    override suspend fun beforeLaunch(query: SaveQuery): SyncthingGate = SyncthingGate.Go()

    override suspend fun afterExit(query: SaveQuery) = Unit

    override suspend fun resolve(conflict: SyncthingConflict, keepThis: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        state.value = SyncthingState.Off
    }

    private fun connected() = SyncthingState.Connected(
        "http://127.0.0.1:8384", "v2.0.10", "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD", "Gaming PC",
    ).also { state.value = it }

    private val planned = listOf(
        SyncthingPlanFolder("fuse-retroarch-saves", "RetroArch saves", "/home/mo/.config/retroarch/saves", "RetroArch", SaveKind.SAVE, shared = true),
        SyncthingPlanFolder("fuse-retroarch-states", "RetroArch save states", "/home/mo/.config/retroarch/states", "RetroArch", SaveKind.STATE, shared = true),
        SyncthingPlanFolder("fuse-duckstation-cards", "DuckStation memory cards", "/home/mo/.local/share/duckstation/memcards", "DuckStation", SaveKind.MEMORY_CARD),
        SyncthingPlanFolder("fuse-dolphin-saves", "Dolphin saves", "/home/mo/Games/GameCube", "Dolphin", SaveKind.SAVE, blocked = "Beside the games themselves, which Fuse never syncs"),
    )

    /** Connected, with a Deck and a phone, one asking to join, and two folders shared. */
    fun household() {
        connected()
        devices.value = listOf(
            SyncthingDevice("MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD", "Gaming PC", connected = true, address = null, self = true),
            SyncthingDevice("P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MFZWI3D-BONSGYC-YLTMRWG-C43ENR5", "Steam Deck", connected = true, address = "192.168.1.40:22000"),
            SyncthingDevice("QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD-P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI", "Retroid Pocket 5", connected = false, address = null),
        )
        pendingDevices.value = listOf(SyncthingPendingDevice("C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD-P56IOI7-MZJNU2Y-IQGDREY", "Lina's laptop", "192.168.1.52:22000"))
        folders.value = listOf(
            SyncthingFolder("fuse-retroarch-saves", "RetroArch saves", "/home/mo/.config/retroarch/saves", "idle", 0, 48_000_000, listOf("deck", "phone"), keepsVersions = true),
            SyncthingFolder("fuse-retroarch-states", "RetroArch save states", "/home/mo/.config/retroarch/states", "syncing", 12_400_000, 310_000_000, listOf("deck"), keepsVersions = true),
        )
    }
}
