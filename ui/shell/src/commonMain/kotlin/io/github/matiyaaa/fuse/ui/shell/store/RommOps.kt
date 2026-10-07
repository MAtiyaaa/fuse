package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.romm.BiosPick
import io.github.matiyaaa.fuse.romm.FoundRomm
import io.github.matiyaaa.fuse.romm.RommDiscovery
import io.github.matiyaaa.fuse.romm.MirrorProgress
import io.github.matiyaaa.fuse.romm.RommDeviceCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Where Fuse RomM stands with its server. */
enum class RommLink {
    /** Turned off (its setup is kept). */
    OFF,

    /** On, with no server set up yet. */
    NOT_SET_UP,
    CONNECTING,
    ONLINE,

    /** The server isn't answering: the library mirror is shown as it was. */
    OFFLINE,

    /** The server answered but no longer accepts Fuse's sign-in. */
    SIGNED_OUT,
}

/** Fuse RomM as the interface shows it. Never a token, a password or an address with one. */
@Immutable
data class RommState(
    val link: RommLink = RommLink.OFF,
    val route: NetRoute? = null,
    val version: String = "",
    val account: String = "",
    /** The sign-in may send games to RomM. */
    val canUpload: Boolean = false,
    /** The sign-in may ask RomM to scan. */
    val canScan: Boolean = false,
    /** A sync of the library mirror running, with how far it came. */
    val syncing: MirrorProgress? = null,
    /** When the mirror last caught up, on this device's clock; 0 when never. */
    val syncedAt: Long = 0,
    val games: Int = 0,
    val newGames: Int = 0,
    /** What went wrong last, in words for the person. */
    val problem: String? = null,
) {
    val usable: Boolean get() = link == RommLink.ONLINE || (link == RommLink.OFFLINE && games > 0)
}

/** Where a RomM game stands on this device: one game, whichever of these it is. */
enum class RommPresence { ON_SERVER, DOWNLOADING, QUEUED, INSTALLED, PARTLY_INSTALLED }

/**
 * A RomM game in Fuse's lists: drawn as any Fuse game is ([card], with Fuse's own art rules), and
 * known for what it is on RomM. A game already in the library carries its own [card].
 */
@Immutable
data class RommGame(
    val romId: Long,
    val card: GameCard,
    val presence: RommPresence,
    /** The Fuse game it is, when it is in the library. */
    val game: GameId? = null,
    val sizeBytes: Long = 0,
    val isNew: Boolean = false,
    /** The transfer bringing it in, while one is. */
    val transfer: String? = null,
)

/** A system on the server, drawn with the same art as Fuse's Systems page. */
@Immutable
data class RommSystem(
    val platform: PlatformId?,
    val slug: String,
    val name: String,
    val games: Int,
    val installed: Int,
    val sizeBytes: Long,
    /** The system's art, as on Fuse's Systems page. */
    val art: Art = Art.None,
    /** The system's brand colour from the art pack, when it has one. */
    val accent: Long? = null,
)

@Immutable
/** Library games RomM hasn't got: the first of them as cards, and how many there are in all. */
data class RommNotOnServer(val games: List<GameCard> = emptyList(), val total: Int = 0)

data class RommCollectionCard(val id: String, val name: String, val smart: Boolean, val games: Int, val covers: List<GameCard>)

/** One part of a game on RomM (the game, a disc, an update, DLC), and whether it is here. */
@Immutable
data class RommPartView(
    val kind: ContentKind,
    val label: String,
    val sizeBytes: Long,
    val fileIds: List<Long>,
    val here: Boolean,
    val downloading: Boolean = false,
)

/** What Fuse RomM adds to a game's page: the game on RomM, its parts, and what can be done. */
@Immutable
data class RommGameView(
    val romId: Long,
    val name: String,
    val parts: List<RommPartView>,
    /** The game was matched to this Fuse game, with the reason ("Same file", "Same title id"). */
    val matchedBy: String? = null,
    val transfer: String? = null,
) {
    val missing: List<RommPartView> get() = parts.filter { !it.here && it.kind.holdsGameData }
    val missingDiscs: List<RommPartView> get() = missing.filter { it.kind == ContentKind.GAME && it.label.startsWith("Disc") }
}

/** What to download of a RomM game. */
sealed interface RommDownloadWhat {
    data object Game : RommDownloadWhat
    data object MissingDiscs : RommDownloadWhat
    data object Updates : RommDownloadWhat
    data object Dlc : RommDownloadWhat
    data object Everything : RommDownloadWhat
    data class Files(val ids: List<Long>, val label: String) : RommDownloadWhat
}

/** What an upload would send, shown before it starts. */
@Immutable
data class RommUploadPlan(
    val title: String,
    val platformName: String,
    /** RomM's system for it; null when the server has no such system (nothing can be sent). */
    val platformId: Long?,
    val files: List<RommUploadLine>,
    val totalBytes: Long,
    /** The sign-in can't upload: what to do instead. */
    val needsPermission: Boolean,
    val serverName: String,
    val problem: String? = null,
)

@Immutable
data class RommUploadLine(val name: String, val folder: String, val sizeBytes: Long, val kind: ContentKind)

/** The result of testing the addresses. */
@Immutable
data class RommTest(val localOk: Boolean?, val remoteOk: Boolean?, val version: String?, val problem: String?)

/**
 * Fuse RomM, the Fuse RomM native integration: a RomM server's library as one more source feeding
 * Fuse. Its games are Fuse games (the same tiles, art rules and game page), its downloads go through
 * Downloads into the library Fuse already has, and Fuse's games go back to RomM from the same place.
 */
interface RommOps {
    val supported: Boolean
    val state: StateFlow<RommState>

    /** Short messages for the person: an upload done, new games on the server, firmware brought over. */
    val notices: Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()

    // ---------------------------------------------------------------- set-up and sign-in
    suspend fun test(local: String, remote: String, mode: RouteMode): RommTest
    suspend fun setAddresses(local: String, remote: String, mode: RouteMode)

    /** RomM servers on this network, found the way Fuse Sync and Jellyfin find theirs ([RommDiscovery]). */
    suspend fun discover(): List<FoundRomm> = emptyList()

    /** Starts pairing with the addresses set: RomM shows the code to approve. [upload] asks for upload rights too. */
    suspend fun startPairing(upload: Boolean): Result<RommDeviceCode>

    /** Waits for the pairing to be approved (or to run out); the account's name when it was. */
    suspend fun awaitPairing(code: RommDeviceCode): Result<String>
    suspend fun usePairCode(code: String): Result<String>
    suspend fun usePassword(username: String, password: String): Result<String>
    suspend fun useToken(token: String): Result<String>

    /** Turns Fuse RomM on or off. On turns Cartridge off in Fuse, so only one RomM integration runs; its setup stays. */
    suspend fun setEnabled(enabled: Boolean)

    /** Forgets the sign-in (the addresses and preferences stay). */
    suspend fun signOut()

    /** Brings the mirror up to date ([full]: reads everything again). */
    fun refresh(full: Boolean = false)

    // ---------------------------------------------------------------- browsing
    val systems: StateFlow<List<RommSystem>>
    val recent: StateFlow<List<RommGame>>
    val newGames: StateFlow<List<RommGame>>
    val collections: StateFlow<List<RommCollectionCard>>

    /**
     * Games in the library that RomM has no match for (apps aside), once the server's whole
     * library is known: the ones to send to RomM. [RommNotOnServer.total] counts them all.
     */
    val notOnServer: StateFlow<RommNotOnServer> get() = MutableStateFlow(RommNotOnServer())

    /** Library games on [platform] that RomM has no match for, every one of them (empty until the server's library is known). */
    fun notOnServerOn(platform: PlatformId): Flow<List<GameCard>> = flowOf(emptyList())
    fun games(slug: String?): Flow<List<RommGame>>
    fun collection(id: String): Flow<List<RommGame>>
    fun search(text: String): Flow<List<RommGame>>
    fun markNewSeen()

    // ---------------------------------------------------------------- one game
    /** A RomM game Fuse doesn't have yet, as the game page shows it. */
    fun detail(romId: Long): Flow<GameDetail?>

    /** What RomM has of a Fuse game (null when it isn't on RomM, or Fuse RomM is off). */
    fun forGame(game: GameId): Flow<RommGameView?>
    fun forRom(romId: Long): Flow<RommGameView?>

    /** The library game a RomM game became (downloaded, or found here), or null while it is only on RomM. */
    fun libraryGame(romId: Long): Flow<GameId?> = flowOf(null)

    /** Queues a download; null when it was queued, else why not. */
    suspend fun download(romId: Long, what: RommDownloadWhat = RommDownloadWhat.Game): String?

    suspend fun uploadPlan(game: GameId): RommUploadPlan?

    /** Queues an upload of the plan; null when it was queued, else why not. */
    suspend fun upload(game: GameId, plan: RommUploadPlan): String?

    // ---------------------------------------------------------------- BIOS
    /** The BIOS this device needs that the server has ([all]: every file the server has for systems played here). */
    suspend fun biosPlan(all: Boolean): List<BiosPick>

    /** Queues [picks]; how many were queued. */
    suspend fun downloadBios(picks: List<BiosPick>): Int

    object None : RommOps {
        override val supported = false
        override val state: StateFlow<RommState> = MutableStateFlow(RommState())
        override suspend fun test(local: String, remote: String, mode: RouteMode) = RommTest(null, null, null, "Fuse RomM isn't available here.")
        override suspend fun setAddresses(local: String, remote: String, mode: RouteMode) = Unit
        override suspend fun startPairing(upload: Boolean): Result<RommDeviceCode> = Result.failure(UnsupportedOperationException())
        override suspend fun awaitPairing(code: RommDeviceCode): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun usePairCode(code: String): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun usePassword(username: String, password: String): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun useToken(token: String): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun signOut() = Unit
        override fun refresh(full: Boolean) = Unit
        override val systems: StateFlow<List<RommSystem>> = MutableStateFlow(emptyList())
        override val recent: StateFlow<List<RommGame>> = MutableStateFlow(emptyList())
        override val newGames: StateFlow<List<RommGame>> = MutableStateFlow(emptyList())
        override val collections: StateFlow<List<RommCollectionCard>> = MutableStateFlow(emptyList())
        override fun games(slug: String?): Flow<List<RommGame>> = flowOf(emptyList())
        override fun collection(id: String): Flow<List<RommGame>> = flowOf(emptyList())
        override fun search(text: String): Flow<List<RommGame>> = flowOf(emptyList())
        override fun markNewSeen() = Unit
        override fun detail(romId: Long): Flow<GameDetail?> = flowOf(null)
        override fun forGame(game: GameId): Flow<RommGameView?> = flowOf(null)
        override fun forRom(romId: Long): Flow<RommGameView?> = flowOf(null)
        override suspend fun download(romId: Long, what: RommDownloadWhat): String? = "Fuse RomM isn't available here."
        override suspend fun uploadPlan(game: GameId): RommUploadPlan? = null
        override suspend fun upload(game: GameId, plan: RommUploadPlan): String? = "Fuse RomM isn't available here."
        override suspend fun biosPlan(all: Boolean): List<BiosPick> = emptyList()
        override suspend fun downloadBios(picks: List<BiosPick>): Int = 0
    }
}

/** A RomM game Fuse doesn't have yet is shown with this id (never a real game's: those are positive). */
fun rommGameId(romId: Long): GameId = GameId(-romId)

/** The RomM id behind a [rommGameId], or null for any other game (the library's, or another device's: see [HOUSEHOLD_BASE]). */
val GameId.rommOnly: Long? get() = if (value < 0 && value > HOUSEHOLD_BASE) -value else null
