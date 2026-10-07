package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.sync.DeviceCommand
import io.github.matiyaaa.fuse.sync.RemoteTransfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Games on the household's other devices that this device doesn't have are shown with ids from
 * here down ([householdGameId]); RomM's are above it ([rommGameId]); the library's own are positive.
 */
const val HOUSEHOLD_BASE: Long = -(1L shl 52)

/** The id another device's game is shown with here, from its place in this device's own list ([seq] from 1). */
fun householdGameId(seq: Long): GameId = GameId(HOUSEHOLD_BASE - seq)

/** The place behind a [householdGameId], or null for any other game. */
val GameId.householdOnly: Long? get() = if (value <= HOUSEHOLD_BASE - 1) HOUSEHOLD_BASE - value else null

/** A game Fuse shows but doesn't have (RomM's, or another device's). */
val GameId.remoteOnly: Boolean get() = value < 0

/** How sure Fuse is that a copy is the same game, byte for byte, as the others. */
enum class CopyCheck {
    /** Its files' hashes match another copy (or RomM's). */
    VERIFIED,

    /** Its files are still being read. */
    CHECKING,

    /** Its files differ from another copy: another version, region or dump. */
    DIFFERENT,

    /** Nothing to compare it with. */
    UNKNOWN,
}

/** Where a copy is. */
enum class CopyPlace { HERE, DEVICE, ROMM }

/** One copy of a game, for "Available on". */
@Immutable
data class CopyView(
    val place: CopyPlace,
    /** The device's id, "here", or the RomM server's. */
    val id: String,
    val name: String,
    /** The device's system ("LINUX", "ANDROID"...), for its picture. */
    val platform: String = "",
    val online: Boolean = true,
    val lastSeen: Long = 0,
    val sizeBytes: Long = 0,
    val check: CopyCheck = CopyCheck.UNKNOWN,
    /** When it came there (0 when not known). */
    val date: Long = 0,
    /** It came through Fuse (from RomM or another device), not simply found there. */
    val downloaded: Boolean = false,
    /** Where it came from, as the person reads it ("RomM", "Gaming PC"). */
    val from: String? = null,
)

/** Every copy of a game Fuse knows, this device's first, then other devices', then RomM's. */
@Immutable
data class GameCopies(val copies: List<CopyView>) {
    val here: CopyView? get() = copies.firstOrNull { it.place == CopyPlace.HERE }
    val devices: List<CopyView> get() = copies.filter { it.place == CopyPlace.DEVICE }
    val romm: CopyView? get() = copies.firstOrNull { it.place == CopyPlace.ROMM }
}

/** One of the household's devices. */
@Immutable
data class HouseholdDevice(
    val id: String,
    val name: String,
    val platform: String,
    val online: Boolean,
    val lastSeen: Long,
    /** It takes games other devices send it. */
    val accepts: Boolean,
    val games: Int,
)

/** Whether a game can be sent to a device. */
enum class SendState {
    /** Here: "Download Here". */
    THIS_DEVICE,
    /** It takes it now. */
    READY,
    /** It is off or away: asked now, done when it is back. */
    QUEUED,
    /** It has the game already. */
    HAS_IT,
    /** It doesn't take games from other devices. */
    REFUSES,
}

@Immutable
data class SendTarget(val device: String, val name: String, val platform: String, val state: SendState, val lastSeen: Long = 0)

/** A game on the household's other devices, as the lists show it. */
@Immutable
data class HouseholdGame(
    val id: GameId,
    val card: GameCard,
    /** The household's id for it. */
    val key: String,
    /** The devices that have it, by name. */
    val holders: List<String>,
    val onlineHolders: Int,
    val sizeBytes: Long,
    val addedAt: Long,
    /** Its transfer here, while one runs. */
    val transfer: String? = null,
    val downloading: Boolean = false,
)

/** A system the household's other devices have games for. */
@Immutable
data class HouseholdSystem(val platform: PlatformId, val name: String, val games: Int, val art: Art = Art.None, val accent: Long? = null)

/** The household's games as this device sees them. */
@Immutable
data class HouseholdState(
    /** Fuse Sync is linked and its host offers the household's games. */
    val supported: Boolean = false,
    /** Fuse Sync is linked, whatever its host offers. */
    val linked: Boolean = false,
    val devices: List<HouseholdDevice> = emptyList(),
    /** Games on other devices that this device doesn't have. */
    val games: Int = 0,
)

/** Another device's transfer, for Downloads. */
@Immutable
data class RemoteTransferRow(
    val device: String,
    val deviceName: String,
    val online: Boolean,
    val lastSeen: Long,
    val item: RemoteTransfer,
) {
    val id: String get() = "remote:$device:${item.key}"
}

/**
 * The Remote Library: games on the household's other devices, brought here or sent to another
 * device from wherever is best, and every device's transfers. Remembered here, so it opens at once
 * and stays browsable while the host or a device is away.
 */
interface ReachOps {
    val supported: Boolean
    val state: StateFlow<HouseholdState>

    /** Games recently added on other devices (this device's own left out). */
    val recent: StateFlow<List<HouseholdGame>>
    val systems: StateFlow<List<HouseholdSystem>>
    fun games(platform: PlatformId?): Flow<List<HouseholdGame>>

    /** Other devices' games that RomM doesn't have (by their files, never a look-alike name). */
    val notOnRomm: StateFlow<List<HouseholdGame>>

    /** Every copy of [id] (any game: the library's, RomM's or another device's). */
    fun availability(id: GameId): Flow<GameCopies?>

    /** The library game another device's game became once it came here, or null. */
    fun libraryGame(id: GameId): Flow<GameId?> = flowOf(null)

    /** Brings [id] here from wherever is best; null when it was queued, else why not. */
    suspend fun download(id: GameId): String?

    suspend fun sendTargets(id: GameId): List<SendTarget>

    /** Has [device] bring [id] (now, or when it is back); null when asked, else why not. */
    suspend fun sendTo(id: GameId, device: String): String?

    /** Whether RomM uploads can be asked for [id] here or on the device that has it. */
    suspend fun canUploadToRomm(id: GameId): Boolean = false

    /** Sends [id] to RomM from the device that has it (this one, when it does); null when asked. */
    suspend fun uploadToRomm(id: GameId): String?

    /** Other devices' transfers, while [watchTransfers] is on. */
    val remoteTransfers: StateFlow<List<RemoteTransferRow>>

    /** What this device asked of others, still open or just done. */
    val requests: StateFlow<List<DeviceCommand>>
    fun watchTransfers(on: Boolean)
    fun actRemote(row: RemoteTransferRow, action: TransferAction)
    suspend fun cancelRequest(id: String)

    /** This device's own id in the household ("" without one). */
    val self: String get() = ""

    object None : ReachOps {
        override val supported = false
        override val state: StateFlow<HouseholdState> = MutableStateFlow(HouseholdState())
        override val recent: StateFlow<List<HouseholdGame>> = MutableStateFlow(emptyList())
        override val systems: StateFlow<List<HouseholdSystem>> = MutableStateFlow(emptyList())
        override fun games(platform: PlatformId?): Flow<List<HouseholdGame>> = flowOf(emptyList())
        override val notOnRomm: StateFlow<List<HouseholdGame>> = MutableStateFlow(emptyList())
        override fun availability(id: GameId): Flow<GameCopies?> = flowOf(null)
        override suspend fun download(id: GameId): String? = "Fuse Sync isn't set up."
        override suspend fun sendTargets(id: GameId): List<SendTarget> = emptyList()
        override suspend fun sendTo(id: GameId, device: String): String? = "Fuse Sync isn't set up."
        override suspend fun uploadToRomm(id: GameId): String? = "Fuse Sync isn't set up."
        override val remoteTransfers: StateFlow<List<RemoteTransferRow>> = MutableStateFlow(emptyList())
        override val requests: StateFlow<List<DeviceCommand>> = MutableStateFlow(emptyList())
        override fun watchTransfers(on: Boolean) = Unit
        override fun actRemote(row: RemoteTransferRow, action: TransferAction) = Unit
        override suspend fun cancelRequest(id: String) = Unit
    }
}
