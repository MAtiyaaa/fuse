package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/** Metadata/artwork sources. The user orders them; RomM data (via Cartridge) is preferred when present. */
@Serializable
enum class ScrapeProviderId(val displayName: String, val needsCredentials: Boolean) {
    LOCAL("Local media", false),
    ROMM("RomM (via Cartridge)", false),
    STEAMGRIDDB("SteamGridDB", true),
    IGDB("IGDB", true),
    THEGAMESDB("TheGamesDB", true),
    SCREENSCRAPER("ScreenScraper", true),
    LIBRETRO("Libretro thumbnails", false),
}

/** What Fuse asks a provider. */
@Serializable
data class ScrapeQuery(
    val title: String,
    val platform: PlatformId,
    val platformName: String,
    val fileName: String?,
    val regions: List<String> = emptyList(),
    val year: Int? = null,
    val md5: String? = null,
    val crc32: String? = null,
    val sizeBytes: Long? = null,
    val preferredLanguage: String = "en",
    val preferredRegion: String? = null,
    /** Other names the game goes by (its original or cleaned file name, a name a provider gave), searched when [title] finds nothing sure. */
    val alsoKnownAs: List<String> = emptyList(),
)

/** One possible match with a confidence the user can see before anything is saved. */
@Serializable
data class ScrapeCandidate(
    val provider: ScrapeProviderId,
    val providerGameId: String,
    val title: String,
    val platformName: String? = null,
    val year: Int? = null,
    /** 0..1, computed by Fuse's matcher, not the provider. */
    val confidence: Float,
    /** Why the score is what it is, for the candidate preview. */
    val reasons: List<String> = emptyList(),
    val previewUrl: String? = null,
)

/** One artwork option offered by a provider (SteamGridDB grids/heroes/logos/icons, IGDB covers...). */
@Serializable
data class ArtworkOption(
    val provider: ScrapeProviderId,
    val kind: MediaKind,
    val url: String,
    val thumbUrl: String?,
    val width: Int?,
    val height: Int?,
    val style: String? = null,
    val score: Int? = null,
    val animated: Boolean = false,
    val author: String? = null,
)

/** Provider readiness for Settings -> Media & Scraping. */
@Serializable
data class ProviderStatus(
    val id: ScrapeProviderId,
    val enabled: Boolean,
    val configured: Boolean,
    val note: String? = null,
)
