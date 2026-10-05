package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.GameKey
import io.github.matiyaaa.fuse.sync.HostHello
import io.github.matiyaaa.fuse.sync.HostStatus
import io.github.matiyaaa.fuse.sync.HostView
import io.github.matiyaaa.fuse.sync.LaunchGate
import io.github.matiyaaa.fuse.sync.NearbyHost
import io.github.matiyaaa.fuse.sync.ProfileChange
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.RevisionReason
import io.github.matiyaaa.fuse.sync.Route
import io.github.matiyaaa.fuse.sync.SaveConflict
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.sync.SaveVersion
import io.github.matiyaaa.fuse.sync.ServiceState
import io.github.matiyaaa.fuse.sync.SyncActivity
import io.github.matiyaaa.fuse.sync.SyncException
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Fuse Sync for the audit: a made-up household (a gaming PC hosting, a Steam Deck and a phone, three
 * people) whose screens can be shown in every state without a network. Settings are the store's
 * own, so the interface follows them as it does the real service.
 */
internal class AuditSync(private val settings: SettingsStore) : SyncService {
    override val status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    override val profiles = MutableStateFlow<List<ProfileInfo>>(emptyList())
    override val activeProfile = MutableStateFlow<ProfileInfo?>(null)
    override val activity = MutableStateFlow<List<SyncActivity>>(emptyList())
    override val host = MutableStateFlow<HostView?>(null)
    override val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val canHost = true
    override val defaultName = "Gaming PC"
    override fun lifetimeState() = ServiceState(installed = false, running = false, description = "Starts with this computer, before anyone signs in")

    /** Where a handheld's emulators keep their saves: found, chosen, private, and not supported. */
    override suspend fun saveFolders(samples: List<io.github.matiyaaa.fuse.sync.SaveQuery>) = listOf(
        io.github.matiyaaa.fuse.sync.EmulatorSaves("lemuroid", "lemuroid", listOf("snes", "gba"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.NOT_FOUND,
            note = "Lemuroid keeps its saves in its own private Android folder, which no other app can open. RetroArch, with its saves in your storage, syncs fully."),
        io.github.matiyaaa.fuse.sync.EmulatorSaves("drastic", "drastic", listOf("nds"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.NOT_FOUND,
            note = "In DraStic, Change Options, General, System Directory, choose Scoped Storage Folder and a folder named DraStic in your storage; Fuse finds it there."),
        io.github.matiyaaa.fuse.sync.EmulatorSaves("retroarch", "retroarch", listOf("snes", "nes", "genesis"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.FOUND, where = "/storage/emulated/0/RetroArch/saves", canChoose = false),
        io.github.matiyaaa.fuse.sync.EmulatorSaves("azahar", "azahar", listOf("3ds"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.FOUND, where = "/storage/emulated/0/Azahar/sdmc/Nintendo 3DS", chosen = "/storage/emulated/0/Azahar"),
        io.github.matiyaaa.fuse.sync.EmulatorSaves("eden", "eden", listOf("switch"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.FOUND, where = "/storage/emulated/0/Eden/nand/user/save"),
        io.github.matiyaaa.fuse.sync.EmulatorSaves("melonds", "melonds", listOf("nds"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.FOUND, where = "Beside each game"),
        io.github.matiyaaa.fuse.sync.EmulatorSaves("winlator", "winlator", listOf("windows"), io.github.matiyaaa.fuse.sync.EmulatorSaves.State.UNSUPPORTED,
            note = "Windows games keep their own saves inside this app's Windows drive, a different place for every game.", canChoose = false),
    )

    /** The next game launched meets a save conflict. */
    @Volatile var conflictNext: SaveConflict? = null

    private val now get() = System.currentTimeMillis()

    /** The household as it is once set up: three people, three devices, a little history. */
    fun household(asHost: Boolean, playing: String? = "mo") {
        val t = now
        profiles.value = listOf(
            ProfileInfo("mo", "Mo", "cat", protected = false, createdAt = t - 90 * DAY, storageBytes = 412_000_000, devices = listOf("pc", "deck")),
            ProfileInfo("sam", "Sam", "rocket", protected = true, createdAt = t - 60 * DAY, storageBytes = 96_000_000, devices = listOf("phone")),
            ProfileInfo("lina", "Lina", "flower", protected = false, createdAt = t - 20 * DAY, storageBytes = 22_000_000),
        )
        activeProfile.value = profiles.value.firstOrNull { it.id == playing }
        devices.value = listOf(
            DeviceInfo("pc", "Gaming PC", "LINUX", profile = "mo", lastSeen = t - 20_000, lastSync = t - 40_000, connection = "LOCAL"),
            DeviceInfo("deck", "Steam Deck", "LINUX", profile = "mo", lastSeen = t - 3 * HOUR, lastSync = t - 3 * HOUR, connection = "LOCAL"),
            DeviceInfo("phone", "Retroid Pocket 5", "ANDROID", profile = "sam", lastSeen = t - 50_000, lastSync = t - 60_000, connection = "REMOTE"),
        )
        activity.value = listOf(
            SyncActivity(t - 4 * MINUTE, "Pokemon Emerald: saved save", "gba:t.pokemon-emerald", "save"),
            SyncActivity(t - 40 * MINUTE, "Brought in changes from your other devices"),
            SyncActivity(t - 3 * HOUR, "Final Fantasy VII: took the save from Steam Deck; this one is in its history", "psx:s.scus94163", "conflict"),
            SyncActivity(t - 5 * HOUR, "Switched to Mo"),
        )
        if (asHost) {
            host.value = HostView(
                name = "Gaming PC", running = true, port = 47311, addresses = listOf("192.168.1.20:47311"), pairingCode = "K7Q2M9XD",
                status = HostStatus(HostHello(hostId = "h1", name = "Gaming PC", fuseVersion = "0.3.0"), profiles.value, devices.value, 530_000_000, 1840, 212, 9_000, t - 6 * DAY, freeBytes = 380_000_000_000),
                service = ServiceState(installed = true, running = true, description = "Starts with this computer, before anyone signs in"),
            )
        }
        status.value = SyncStatus.Online("Gaming PC", Route.LOCAL)
        runBlocking {
            settings.update {
                it.copy(sync = it.sync.copy(
                    enabled = true, role = if (asHost) "HOST" else "CLIENT", deviceId = if (asHost) "pc" else "deck",
                    deviceName = if (asHost) "Gaming PC" else "Steam Deck", hostName = "Gaming PC", hostId = "h1",
                    localAddress = "192.168.1.20:47311", activeProfile = playing.orEmpty(),
                ))
            }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(sync = it.sync.copy(enabled = enabled)) }
        val c = settings.current().sync
        status.value = when {
            !enabled -> SyncStatus.Off
            c.role.isEmpty() -> SyncStatus.NotSetUp
            else -> SyncStatus.Online(c.hostName, Route.LOCAL)
        }
    }

    override suspend fun stopHosting(): Result<Unit> = Result.success(Unit)
    override suspend fun discover(): List<NearbyHost> {
        delay(400)
        return listOf(NearbyHost("Gaming PC", "h1", "192.168.1.20:47311"))
    }

    /** Devices asking to join, for the card that lets them in. */
    override val joinRequests = MutableStateFlow<List<io.github.matiyaaa.fuse.sync.JoinAsk>>(emptyList())

    override suspend fun askToJoin(address: String, remoteAddress: String?): Result<io.github.matiyaaa.fuse.sync.JoinWaiting> {
        delay(200)
        return Result.success(io.github.matiyaaa.fuse.sync.JoinWaiting("Gaming PC", "482 913"))
    }

    /** Waits as long as the audit looks: nobody lets it in here. */
    override suspend fun awaitJoin(): Result<String> = kotlinx.coroutines.awaitCancellation()

    override suspend fun answerJoin(id: String, allow: Boolean): Result<Unit> {
        joinRequests.value = joinRequests.value.filterNot { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun connect(address: String, code: String, remoteAddress: String?): Result<String> {
        delay(300)
        household(asHost = false, playing = null)
        return Result.success("Gaming PC")
    }

    override suspend fun hostHere(name: String, installService: Boolean): Result<HostView> {
        delay(300)
        household(asHost = true, playing = null)
        profiles.value = emptyList()
        devices.value = devices.value.take(1)
        activity.value = emptyList()
        return Result.success(host.value!!)
    }

    override suspend fun installService() = Result.success(lifetimeState().copy(installed = true, running = true))
    override suspend fun removeService() = Result.success(lifetimeState())
    override suspend fun newPairingCode(): String = "K7Q2M9XD"

    override suspend fun createProfile(name: String, avatar: String, pin: String?): Result<ProfileInfo> {
        val p = ProfileInfo(name.lowercase(), name, avatar, protected = pin != null, createdAt = now)
        profiles.value = profiles.value + p
        return Result.success(p)
    }

    override suspend fun changeProfile(id: String, change: ProfileChange): Result<ProfileInfo> =
        Result.success(profiles.value.first { it.id == id })

    override suspend fun deleteProfile(id: String): Result<Unit> = Result.success(Unit)
    override suspend fun openProfile(id: String, pin: String?): Result<Unit> = Result.success(Unit)

    override suspend fun switchTo(id: String?, pin: String?): Result<Unit> {
        val p = profiles.value.firstOrNull { it.id == id }
        if (p?.protected == true && pin != "2468") return Result.failure(SyncException("That PIN isn't right.", "wrong-pin", 403))
        delay(250)
        activeProfile.value = p
        settings.update { it.copy(sync = it.sync.copy(activeProfile = id.orEmpty())) }
        return Result.success(Unit)
    }

    override suspend fun syncNow(): Result<Unit> = Result.success(Unit)

    override suspend fun beforeLaunch(query: SaveQuery): LaunchGate = conflictNext?.let { conflictNext = null; LaunchGate.Conflict(it) } ?: LaunchGate.Go()

    override suspend fun settle(conflict: SaveConflict, keepHere: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun afterExit(query: SaveQuery, startedAt: Long, endedAt: Long) = Unit

    override suspend fun versions(query: SaveQuery, kind: SaveKind): List<SaveVersion> {
        val t = now
        return if (kind == SaveKind.SAVE) listOf(
            SaveVersion("v6", "Steam Deck", t - 3 * HOUR, 41 * HOUR_S + 12 * 60, 131_072, RevisionReason.PLAYED, current = true),
            SaveVersion("v5", "Gaming PC", t - 3 * HOUR - 5 * MINUTE, 40 * HOUR_S + 50 * 60, 131_072, RevisionReason.CONFLICT_COPY, current = false),
            SaveVersion("v4", "Gaming PC", t - DAY - 2 * HOUR, 39 * HOUR_S, 131_072, RevisionReason.PLAYED, current = false),
            SaveVersion("v3", "Retroid Pocket 5", t - 3 * DAY, 35 * HOUR_S + 20 * 60, 131_072, RevisionReason.MILESTONE, current = false),
            SaveVersion("v2", "Steam Deck", t - 9 * DAY, 28 * HOUR_S, 131_072, RevisionReason.PLAYED, current = false),
            SaveVersion("v1", "Gaming PC", t - 30 * DAY, 2 * HOUR_S, 131_072, RevisionReason.BEFORE_RESTORE, current = false),
        ) else emptyList()
    }

    override suspend fun report(): io.github.matiyaaa.fuse.sync.ProfileReport? {
        val p = activeProfile.value ?: return null
        val t = now
        fun v(id: String, dev: String, devName: String, ago: Long, play: Long, bytes: Long, reason: RevisionReason = RevisionReason.PLAYED, current: Boolean = false, files: List<Pair<String, Long>>) =
            io.github.matiyaaa.fuse.sync.VersionReport(id, dev, devName, t - ago, play, reason, current, bytes,
                files.mapIndexed { i, (f, b) -> io.github.matiyaaa.fuse.sync.FileReport(f, b, "objects/${"%02x".format(i * 37 + id.length)}/${id}9c41e0b27d5f8a${i}3e") })
        fun slot(kind: SaveKind, format: String, vs: List<io.github.matiyaaa.fuse.sync.VersionReport>) = io.github.matiyaaa.fuse.sync.SlotReport(kind, format, vs.sumOf { it.bytes }, vs)
        val games = listOf(
            io.github.matiyaaa.fuse.sync.GameReport("psx:s.scus94163", "Final Fantasy VII", "psx", 41 * HOUR_S + 12 * 60, mapOf("deck" to 28 * HOUR_S, "pc" to 13 * HOUR_S + 12 * 60), 37, t - 3 * HOUR, true, listOf(
                slot(SaveKind.SAVE, "duckstation.card", listOf(
                    v("v6", "deck", "Steam Deck", 3 * HOUR, 41 * HOUR_S + 12 * 60, 131_072, current = true, files = listOf("SCUS94163.mcd" to 131_072L)),
                    v("v5", "pc", "Gaming PC", 3 * HOUR + 5 * MINUTE, 40 * HOUR_S + 50 * 60, 131_072, RevisionReason.CONFLICT_COPY, files = listOf("SCUS94163.mcd" to 131_072L)),
                    v("v4", "pc", "Gaming PC", DAY + 2 * HOUR, 39 * HOUR_S, 131_072, files = listOf("SCUS94163.mcd" to 131_072L)),
                    v("v3", "phone", "Retroid Pocket 5", 3 * DAY, 35 * HOUR_S, 131_072, RevisionReason.MILESTONE, files = listOf("SCUS94163.mcd" to 131_072L)),
                )),
                slot(SaveKind.STATE, "duckstation.state", listOf(
                    v("s2", "deck", "Steam Deck", 3 * HOUR, 41 * HOUR_S, 2_412_000, current = true, files = listOf("state1.sav" to 2_412_000L)),
                )),
            )),
            io.github.matiyaaa.fuse.sync.GameReport("gba:t.pokemon-emerald", "Pokemon Emerald", "gba", 26 * HOUR_S, mapOf("phone" to 18 * HOUR_S, "deck" to 8 * HOUR_S), 52, t - 4 * MINUTE, false, listOf(
                slot(SaveKind.SAVE, "retroarch.srm", listOf(
                    v("e3", "phone", "Retroid Pocket 5", 4 * MINUTE, 26 * HOUR_S, 131_072, current = true, files = listOf("Pokemon Emerald.srm" to 131_072L)),
                    v("e2", "deck", "Steam Deck", 2 * DAY, 22 * HOUR_S, 131_072, files = listOf("Pokemon Emerald.srm" to 131_072L)),
                )),
            )),
            io.github.matiyaaa.fuse.sync.GameReport("ps2:s.slus21005", "Shadow of the Colossus", "ps2", 9 * HOUR_S, mapOf("pc" to 9 * HOUR_S), 7, t - 5 * DAY, false, listOf(
                slot(SaveKind.MEMORY_CARD, "pcsx2.card", listOf(
                    v("c1", "pc", "Gaming PC", 5 * DAY, 9 * HOUR_S, 8_650_752, current = true, files = listOf("Mcd001.ps2" to 8_650_752L)),
                )),
            )),
            io.github.matiyaaa.fuse.sync.GameReport("snes:t.chrono-trigger", "Chrono Trigger", "snes", 4 * HOUR_S + 30 * 60, mapOf("deck" to 4 * HOUR_S + 30 * 60), 6, t - 9 * DAY, false, emptyList()),
        )
        return io.github.matiyaaa.fuse.sync.ProfileReport(
            p, games.sumOf { it.playSeconds },
            mapOf("deck" to 40 * HOUR_S + 30 * 60, "phone" to 18 * HOUR_S, "pc" to 22 * HOUR_S + 12 * 60),
            devices.value, games, 412_000_000, "/home/mo/.local/share/fuse/sync/host",
        )
    }

    override suspend fun restore(query: SaveQuery, kind: SaveKind, version: String): Result<Unit> = Result.success(Unit)
    override suspend fun keepVersion(version: String, keep: Boolean): Result<Unit> = Result.success(Unit)
    override fun changed() = Unit
    override suspend fun unlink(): Result<Unit> = Result.success(Unit)
    override suspend fun renameDevice(id: String, name: String): Result<Unit> = Result.success(Unit)
    override suspend fun revokeDevice(id: String): Result<Unit> = Result.success(Unit)
    override fun stop() = Unit

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val HOUR_S = 3_600L

        /** A conflict on Final Fantasy VII between this Steam Deck and the PC. */
        fun conflict(): SaveConflict {
            val t = System.currentTimeMillis()
            return SaveConflict.forPreview(
                game = GameKey("psx", "s.scus94163"), title = "Final Fantasy VII", kind = SaveKind.SAVE,
                here = io.github.matiyaaa.fuse.sync.SaveSide("Steam Deck", t - 2 * HOUR, 41 * HOUR_S + 12 * 60, 131_072, 1),
                host = io.github.matiyaaa.fuse.sync.SaveSide("Gaming PC", t - 26 * MINUTE, 40 * HOUR_S + 50 * 60, 131_072, 1),
            )
        }
    }
}
