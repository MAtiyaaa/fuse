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
    val checkedAt: Long = 0,
)

@Serializable
data class CartridgeDownload(
    val romId: Long,
    val title: String,
    val platformSlug: String,
    /** Local path Cartridge saved the game to (used to rescan exactly that folder). */
    val path: String?,
    val finishedAt: Long,
)

/** Places Fuse can open inside Cartridge. See docs/INTEGRATIONS.md, "Cartridge Bridge". */
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
