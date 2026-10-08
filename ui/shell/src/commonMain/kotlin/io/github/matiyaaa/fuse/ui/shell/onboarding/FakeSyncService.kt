package io.github.matiyaaa.fuse.ui.shell.onboarding

import io.github.matiyaaa.fuse.sync.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Profiles and host setup entirely in memory, accepting every rehearsal address, code and PIN. */
class FakeSyncService(override val canHost: Boolean = true) : SyncService {
    override val status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    override val profiles = MutableStateFlow<List<ProfileInfo>>(emptyList())
    override val activeProfile = MutableStateFlow<ProfileInfo?>(null)
    override val activity = MutableStateFlow<List<SyncActivity>>(emptyList())
    override val host = MutableStateFlow<HostView?>(null)
    override val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    var onSetup: (String, String) -> Unit = { _, _ -> }
    override val defaultName = "Rehearsal device"
    private var nextProfile = 0
    override fun lifetimeState() = ServiceState(false, false, "Rehearsal service")
    override suspend fun setEnabled(enabled: Boolean, keepProfiles: Boolean) {
        status.value = if (enabled) SyncStatus.NotSetUp else SyncStatus.Off
        if (!keepProfiles) { profiles.value = emptyList(); activeProfile.value = null }
    }
    override suspend fun stopHosting(): Result<Unit> { host.value = null; return Result.success(Unit) }
    override suspend fun discover() = listOf(NearbyHost("Rehearsal household", "rehearsal", "rehearsal.local"))
    override suspend fun connect(address: String, code: String, remoteAddress: String?): Result<String> {
        status.value = SyncStatus.Online("Rehearsal household", Route.LOCAL)
        onSetup("CLIENT", "Rehearsal household")
        return Result.success("Rehearsal household")
    }
    override suspend fun askToJoin(address: String, remoteAddress: String?) = Result.success(JoinWaiting("Rehearsal household", "123456", true))
    override suspend fun awaitJoin() = connect("", "", null)
    override suspend fun joinWithAccount(username: String, password: String) = connect("", "", null)
    override suspend fun hostHere(name: String, installService: Boolean): Result<HostView> {
        val view = HostView(name, true, 47301, listOf("rehearsal.local"), "123456", null, lifetimeState())
        host.value = view
        onSetup("HOST", name)
        status.value = SyncStatus.Online(name, Route.LOCAL)
        return Result.success(view)
    }
    override suspend fun installService() = Result.success(lifetimeState())
    override suspend fun removeService() = Result.success(lifetimeState())
    override suspend fun newPairingCode() = "123456"
    override suspend fun createProfile(name: String, avatar: String, pin: String?): Result<ProfileInfo> {
        if (profiles.value.size >= 20) return Result.failure(IllegalStateException("Twenty rehearsal profiles are already present"))
        val profile = ProfileInfo("rehearsal.${++nextProfile}", name, avatar, !pin.isNullOrEmpty(), nextProfile.toLong())
        profiles.value += profile
        return Result.success(profile)
    }
    override suspend fun reorderProfiles(ids: List<String>): Result<Unit> {
        profiles.value = ids.mapNotNull { id -> profiles.value.find { it.id == id } } + profiles.value.filter { it.id !in ids }
        return Result.success(Unit)
    }
    override suspend fun changeProfile(id: String, change: ProfileChange): Result<ProfileInfo> {
        val old = profiles.value.firstOrNull { it.id == id } ?: return Result.failure(IllegalArgumentException("Choose a rehearsal profile"))
        val profile = old.copy(name = change.name ?: old.name, avatar = change.avatar ?: old.avatar, protected = if (change.removePin) false else if (change.pin != null) true else old.protected)
        profiles.value = profiles.value.map { if (it.id == id) profile else it }
        if (activeProfile.value?.id == id) activeProfile.value = profile
        return Result.success(profile)
    }
    override suspend fun deleteProfile(id: String): Result<Unit> {
        profiles.value = profiles.value.filterNot { it.id == id }
        if (activeProfile.value?.id == id) activeProfile.value = null
        return Result.success(Unit)
    }
    override suspend fun openProfile(id: String, pin: String?) = Result.success(Unit)
    override suspend fun switchTo(id: String?, pin: String?): Result<Unit> {
        activeProfile.value = profiles.value.find { it.id == id }
        return Result.success(Unit)
    }
    override suspend fun syncNow() = Result.success(Unit)
    override suspend fun beforeLaunch(query: SaveQuery, waitForOthers: Boolean) = LaunchGate.Go()
    override suspend fun settle(conflict: SaveConflict, keepHere: Boolean) = Result.success(Unit)
    override suspend fun afterExit(query: SaveQuery, startedAt: Long, endedAt: Long) = Unit
    override suspend fun versions(query: SaveQuery, kind: SaveKind) = emptyList<SaveVersion>()
    override suspend fun restore(query: SaveQuery, kind: SaveKind, version: String) = Result.success(Unit)
    override suspend fun keepVersion(version: String, keep: Boolean) = Result.success(Unit)
    override fun changed() = Unit
    override suspend fun unlink(keepProfiles: Boolean): Result<Unit> { setEnabled(false, keepProfiles); return Result.success(Unit) }
    override suspend fun renameDevice(id: String, name: String) = Result.success(Unit)
    override suspend fun revokeDevice(id: String) = Result.success(Unit)
    override fun stop() {
        profiles.value = emptyList()
        activeProfile.value = null
        status.value = SyncStatus.Off
        host.value = null
        devices.value = emptyList()
        activity.value = emptyList()
    }
}
