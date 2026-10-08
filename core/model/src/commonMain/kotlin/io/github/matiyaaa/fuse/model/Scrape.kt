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
    GAMETDB("GameTDB", false),
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
    /** Scanner-proven serial, when available. Never a title-search guess. */
    val serial: String? = null,
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

/** The evidence behind a reusable provider identity. Names are never claims by themselves. */
@Serializable
enum class ProviderClaimOrigin { USER_CONFIRMED, AUTOMATIC, IMPORTED }

/**
 * Provider-neutral household knowledge. Unknown provider names survive older clients; only an
 * unambiguous trusted claim is used for direct requests. User confirmation is stronger than a
 * later automatic result, and disagreeing automatic identities require review rather than a
 * last-arriving overwrite.
 */
@Serializable
data class ProviderClaim(
    val provider: String,
    val gameId: String,
    val origin: ProviderClaimOrigin = ProviderClaimOrigin.AUTOMATIC,
    val confidence: Float = 1f,
    val originDeviceId: String? = null,
    val verifiedAtMillis: Long = 0,
) {
    /** Known provider names have one spelling across legacy and current household clients. */
    val canonicalProvider: String get() = ScrapeProviderId.entries
        .firstOrNull { it.name.equals(provider, ignoreCase = true) }?.name ?: provider

    val trusted: Boolean get() = provider.isNotBlank() && gameId.isNotBlank() &&
        (origin == ProviderClaimOrigin.USER_CONFIRMED || confidence.isFinite() && confidence >= 0.9f)
}

/** Contradictory claims remain inspectable; only a unique strongest identity is reusable. */
fun trustedProviderIds(claims: List<ProviderClaim>): Map<String, String> = buildMap {
    for ((provider, options) in claims.filter { it.trusted }.groupBy { it.canonicalProvider }) {
        val confirmed = options.filter { it.origin == ProviderClaimOrigin.USER_CONFIRMED }
        val best = if (confirmed.isNotEmpty()) confirmed else options
        best.map { it.gameId }.distinct().singleOrNull()?.let { put(provider, it) }
    }
}
