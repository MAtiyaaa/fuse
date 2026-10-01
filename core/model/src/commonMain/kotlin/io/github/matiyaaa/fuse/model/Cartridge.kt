package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * What Fuse knows about the companion Cartridge app. Filled from Cartridge's read-only status
 * provider when it has one (Cartridge 0.9.10+), otherwise only [installed]/[version].
 * Never contains server addresses, tokens or passwords.
 */
@Serializable
data class CartridgeStatus(
    val installed: Boolean = false,
    val version: String? = null,
    /** True when Cartridge understands Fuse's deep links and status provider. */
    val bridge: Boolean = false,
    val connected: Boolean? = null,
    val activeDownloads: Int = 0,
    val queuedDownloads: Int = 0,
    /** 0..1 across the active queue, when known. */
    val progress: Float? = null,
    val currentTitle: String? = null,
    val currentPlatform: String? = null,
    /** Epoch millis Cartridge last changed anything on disk (download finished, library synced). */
    val libraryChangedAt: Long = 0,
    val recent: List<CartridgeDownload> = emptyList(),
    /** Every download in Cartridge's queue with its own progress (bridge 2 and later), in order. */
    val queue: List<CartridgeQueueItem> = emptyList(),
    /** The bridge protocol Cartridge speaks: 0 without a bridge, 1 for 0.9.10, 2 adds the queue and games, 3 uploads. */
    val protocol: Int = 0,
    /** Games Fuse handed to Cartridge to upload to RomM, newest first (bridge 3 and later). */
    val uploads: List<CartridgeUploadItem> = emptyList(),
    /**
     * Counts changes to Cartridge's downloaded games (Fuse's own counter, not Cartridge's): when it
     * moves, Fuse reads the games and their RomM details again.
     */
    val gamesRevision: Long = 0,
    val checkedAt: Long = 0,
)

/**
 * A game Cartridge downloaded and still has on disk, with RomM's details (bridge protocol 2).
 * Pictures are files Cartridge hands over itself: a content URI on Android, an absolute path on
 * Linux; never a RomM address.
 */
@Serializable
data class CartridgeGame(
    val romId: Long,
    /** The file or folder Cartridge saved the game to. */
    val path: String?,
    val title: String,
    val platformSlug: String,
    val summary: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val developer: String? = null,
    val publisher: String? = null,
    /** 0..100 */
    val rating: Int? = null,
    val players: String? = null,
    val series: List<String> = emptyList(),
    val cover: String? = null,
    val logo: String? = null,
    val screenshot: String? = null,
    /** Moves when the download, the details or a picture changed. */
    val updatedAt: Long = 0,
)

/** One game in Cartridge's download queue. */
@Serializable
data class CartridgeQueueItem(
    val romId: Long,
    val title: String,
    val platformSlug: String,
    val state: QueueState,
    val received: Long,
    /** Bytes in total, when known. */
    val total: Long?,
) {
    /** 0..1 when the size is known. */
    val progress: Float? get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
}

@Serializable
enum class QueueState { DOWNLOADING, QUEUED, PAUSED, FAILED }

/**
 * A game Fuse asks Cartridge to upload to RomM (bridge 3): every file of it, the one that names the
 * game first. Cartridge shows what will be sent and uploads only after the user confirms there.
 */
@Serializable
data class CartridgeUpload(
    val title: String,
    /** Fuse's platform id, which is also RomM's platform slug for the systems both know. */
    val platformSlug: String,
    val files: List<UploadFile>,
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * One file of an upload. [folder] is where it goes inside the game on RomM: empty for the game
 * itself, or a relative, forward-slashed folder such as "dlc" or "update" (RomM's categories).
 */
@Serializable
data class UploadFile(val path: String, val name: String, val folder: String, val sizeBytes: Long)

/** One upload in Cartridge, as the bridge reports it. */
@Serializable
data class CartridgeUploadItem(
    val id: String,
    val title: String,
    val platformSlug: String,
    val state: UploadState,
    val sent: Long,
    /** Bytes in total, when known. */
    val total: Long?,
    val files: Int = 0,
    /** The game on RomM once it exists there. */
    val romId: Long? = null,
    /** Why it failed, in Cartridge's words (never a server address). */
    val error: String? = null,
    val updatedAt: Long = 0,
) {
    val progress: Float? get() = total?.takeIf { it > 0 }?.let { (sent.toFloat() / it).coerceIn(0f, 1f) }
    val active: Boolean get() = state == UploadState.WAITING || state == UploadState.UPLOADING || state == UploadState.SCANNING
}

@Serializable
enum class UploadState {
    /** Waiting for the user to confirm in Cartridge, or for its turn. */
    WAITING,
    UPLOADING,
    /** Sent; RomM is adding it to the library. */
    SCANNING,
    DONE,
    FAILED,
    CANCELLED,
}

@Serializable
data class CartridgeDownload(
    val romId: Long,
    val title: String,
    val platformSlug: String,
    /** Local path Cartridge saved the game to (used to rescan exactly that folder). */
    val path: String?,
    val finishedAt: Long,
)

/** Places Fuse can open inside Cartridge. See INTEGRATIONS.md, "Cartridge Bridge". */
@Serializable
sealed interface CartridgeRoute {
    @Serializable data object Home : CartridgeRoute
    @Serializable data object Library : CartridgeRoute
    @Serializable data object Downloads : CartridgeRoute
    @Serializable data object Consoles : CartridgeRoute
    @Serializable data object Settings : CartridgeRoute
    @Serializable data object Sync : CartridgeRoute
    @Serializable data class Platform(val slug: String) : CartridgeRoute
    @Serializable data class Game(val romId: Long) : CartridgeRoute
    @Serializable data class Search(val query: String, val platformSlug: String? = null) : CartridgeRoute
    @Serializable data class Bios(val platformSlug: String) : CartridgeRoute
}

/** A release of Fuse or Cartridge on GitHub, used for "Install Cartridge" and Fuse updates. */
@Serializable
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val publishedAt: String?,
    val assets: List<ReleaseAsset>,
    val htmlUrl: String,
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    /** "sha256:<hex>" when GitHub reports a digest for the asset. */
    val digest: String? = null,
)
