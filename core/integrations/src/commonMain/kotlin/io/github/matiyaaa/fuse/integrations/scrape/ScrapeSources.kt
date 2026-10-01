package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.Dates
import io.github.matiyaaa.fuse.integrations.igdb.IgdbClient
import io.github.matiyaaa.fuse.integrations.igdb.IgdbGame
import io.github.matiyaaa.fuse.integrations.igdb.IgdbImageSize
import io.github.matiyaaa.fuse.integrations.igdb.IgdbImages
import io.github.matiyaaa.fuse.integrations.igdb.IgdbPlatforms
import io.github.matiyaaa.fuse.integrations.libretro.LibretroThumbnailType
import io.github.matiyaaa.fuse.integrations.libretro.LibretroThumbnails
import io.github.matiyaaa.fuse.integrations.map
import io.github.matiyaaa.fuse.integrations.match.MatchInput
import io.github.matiyaaa.fuse.integrations.match.PlatformEvidence
import io.github.matiyaaa.fuse.integrations.screenscraper.ScreenScraperClient
import io.github.matiyaaa.fuse.integrations.screenscraper.ScreenScraperSystems
import io.github.matiyaaa.fuse.integrations.screenscraper.SsGame
import io.github.matiyaaa.fuse.integrations.screenscraper.SsRomQuery
import io.github.matiyaaa.fuse.integrations.steamgriddb.SgdbAssetType
import io.github.matiyaaa.fuse.integrations.steamgriddb.SgdbDimension
import io.github.matiyaaa.fuse.integrations.steamgriddb.SgdbFilters
import io.github.matiyaaa.fuse.integrations.steamgriddb.SteamGridDbClient
import io.github.matiyaaa.fuse.integrations.steamgriddb.toArtworkOption
import io.github.matiyaaa.fuse.integrations.thegamesdb.TgdbImageType
import io.github.matiyaaa.fuse.integrations.thegamesdb.TgdbPlatforms
import io.github.matiyaaa.fuse.integrations.thegamesdb.TheGamesDbClient
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery

/** One game a provider search returned, with the metadata and artwork that came with it. */
data class ProviderGame(
    val provider: ScrapeProviderId,
    val providerGameId: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val platformNames: List<String> = emptyList(),
    val platformEvidence: PlatformEvidence? = null,
    val year: Int? = null,
    val regions: List<String> = emptyList(),
    val previewUrl: String? = null,
    val metadata: GameMetadata? = null,
    /** Artwork the search already returned (IGDB covers, ScreenScraper medias, TheGamesDB boxart). */
    val artwork: List<ArtworkOption> = emptyList(),
    val identifiedByChecksum: Boolean = false,
) {
    fun toMatchInput(): MatchInput = MatchInput(
        provider = provider,
        providerGameId = providerGameId,
        title = title,
        alternativeTitles = alternativeTitles,
        platformNames = platformNames,
        platformEvidence = platformEvidence,
        year = year,
        regions = regions,
        previewUrl = previewUrl,
        identifiedByChecksum = identifiedByChecksum,
    )
}

/** A provider as the [ScrapeCoordinator] uses it. Implementations only read; they never store. */
interface ScrapeSource {
    val id: ScrapeProviderId

    /** False for artwork-only providers (SteamGridDB, libretro). */
    val providesMetadata: Boolean

    /** The art kinds this source can ever return, so a fill never asks for what it can't have. */
    val artworkKinds: Set<MediaKind> get() = MediaKind.entries.toSet()

    /** False for sources that look names up exactly, where a keyword search ([SearchNames.keywords]) never hits. */
    val searchesByKeyword: Boolean get() = true

    /** Finds games for [query]. */
    suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>>

    /**
     * The game with this source's [id], for a game identified before: no search and no matching.
     * Success(null) when the source has no such game or can't look games up by id.
     */
    suspend fun byId(id: String, query: ScrapeQuery): ApiResult<ProviderGame?> = ApiResult.Success(null)

    /** Artwork of the [kinds] for a game this source returned from [search]. */
    suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>): ApiResult<List<ArtworkOption>>
}

/** IGDB: metadata plus cover (box art), artworks (hero) and screenshots. */
class IgdbSource(private val client: IgdbClient, private val limit: Int = 10) : ScrapeSource {
    override val id = ScrapeProviderId.IGDB
    override val providesMetadata = true
    override val artworkKinds = setOf(MediaKind.BOXART, MediaKind.HERO, MediaKind.SCREENSHOT)

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> {
        val platformId = IgdbPlatforms.idFor(query.platform)
        if (platformId != null) {
            val filtered = client.searchGames(query.title, listOf(platformId), limit)
            if (filtered !is ApiResult.Success || filtered.value.isNotEmpty()) {
                return filtered.map { list -> list.map { it.toProviderGame(PlatformEvidence.MATCH) } }
            }
        }
        return client.searchGames(query.title, emptyList(), limit).map { list -> list.map { it.toProviderGame(null) } }
    }

    override suspend fun byId(id: String, query: ScrapeQuery): ApiResult<ProviderGame?> {
        val n = id.toLongOrNull() ?: return ApiResult.Success(null)
        return client.gameById(n).map { it?.toProviderGame(PlatformEvidence.MATCH) }
    }

    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>) =
        ApiResult.Success(game.artwork.filter { it.kind in kinds })

    private fun IgdbGame.toProviderGame(evidence: PlatformEvidence?): ProviderGame {
        val art = buildList {
            cover?.imageId?.takeIf { it.isNotBlank() }?.let {
                add(igdbArt(MediaKind.BOXART, it, IgdbImageSize.COVER_BIG, IgdbImageSize.COVER_SMALL))
            }
            artworks.mapNotNull { it.imageId.takeIf { id -> id.isNotBlank() } }.forEach {
                add(igdbArt(MediaKind.HERO, it, IgdbImageSize.P1080, IgdbImageSize.SCREENSHOT_MED))
            }
            screenshots.mapNotNull { it.imageId.takeIf { id -> id.isNotBlank() } }.forEach {
                add(igdbArt(MediaKind.SCREENSHOT, it, IgdbImageSize.SCREENSHOT_HUGE, IgdbImageSize.SCREENSHOT_MED))
            }
        }
        val year = firstReleaseDate?.let { Dates.yearOfEpochSeconds(it) }
        return ProviderGame(
            provider = ScrapeProviderId.IGDB,
            providerGameId = id.toString(),
            title = name,
            platformNames = platforms.flatMap { listOfNotNull(it.name, it.abbreviation) },
            platformEvidence = evidence,
            year = year,
            previewUrl = cover?.imageId?.let { IgdbImages.url(it, IgdbImageSize.COVER_BIG) },
            metadata = GameMetadata(
                description = summary?.takeIf { it.isNotBlank() },
                releaseYear = year,
                developer = developers.firstOrNull(),
                publisher = publishers.firstOrNull(),
                genres = genres.map { it.name }.filter { it.isNotBlank() },
                franchise = series,
                rating = totalRating?.takeIf { it > 0 }?.let { kotlin.math.round(it).toInt().coerceIn(0, 100) },
                source = MetadataSource.IGDB,
            ),
            artwork = art,
        )
    }

    private fun igdbArt(kind: MediaKind, imageId: String, size: IgdbImageSize, thumb: IgdbImageSize) = ArtworkOption(
        provider = ScrapeProviderId.IGDB,
        kind = kind,
        url = IgdbImages.url(imageId, size),
        thumbUrl = IgdbImages.url(imageId, thumb),
        width = null,
        height = null,
    )
}

/** TheGamesDB: metadata plus boxart, fanart (hero), banners (grid), screenshots and clear logos. */
class TheGamesDbSource(private val client: TheGamesDbClient) : ScrapeSource {
    override val id = ScrapeProviderId.THEGAMESDB
    override val providesMetadata = true
    override val artworkKinds = setOf(MediaKind.BOXART, MediaKind.HERO, MediaKind.GRID, MediaKind.SCREENSHOT, MediaKind.LOGO)

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> {
        val platformId = TgdbPlatforms.idFor(query.platform)
        val first = client.searchByName(query.title, listOfNotNull(platformId))
        val result = if (platformId != null && first is ApiResult.Success && first.value.games.isEmpty()) {
            client.searchByName(query.title)
        } else {
            first
        }
        return result.map { r ->
            r.games.map { g ->
                val year = Dates.yearIn(g.releaseDate)
                val boxart = r.boxartFor(g.id)
                ProviderGame(
                    provider = ScrapeProviderId.THEGAMESDB,
                    providerGameId = g.id.toString(),
                    title = g.gameTitle,
                    platformNames = listOfNotNull(r.platformNames[g.platform]),
                    platformEvidence = if (platformId != null && g.platform == platformId) PlatformEvidence.MATCH else null,
                    year = year,
                    previewUrl = boxart.firstOrNull()?.let { it.thumbUrl ?: it.url },
                    metadata = GameMetadata(
                        description = g.overview?.takeIf { it.isNotBlank() },
                        releaseYear = year,
                        players = g.players?.takeIf { it.isNotBlank() && it != "0" },
                        source = MetadataSource.THEGAMESDB,
                    ),
                    artwork = boxart,
                )
            }
        }
    }

    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>): ApiResult<List<ArtworkOption>> {
        val types = kinds.flatMap { kind ->
            when (kind) {
                MediaKind.BOXART -> listOf(TgdbImageType.BOXART)
                MediaKind.HERO -> listOf(TgdbImageType.FANART)
                MediaKind.GRID -> listOf(TgdbImageType.BANNER)
                MediaKind.SCREENSHOT -> listOf(TgdbImageType.SCREENSHOT, TgdbImageType.TITLESCREEN)
                MediaKind.LOGO -> listOf(TgdbImageType.CLEARLOGO)
                else -> emptyList()
            }
        }.toSet()
        if (types.isEmpty()) return ApiResult.Success(emptyList())
        val gameId = game.providerGameId.toLongOrNull() ?: return ApiResult.Success(emptyList())
        return client.images(listOf(gameId), types).map { r -> r.artworkFor(gameId).filter { it.kind in kinds } }
    }
}

/** ScreenScraper: checksum identification when hashes are known, else title search. */
class ScreenScraperSource(private val client: ScreenScraperClient) : ScrapeSource {
    override val id = ScrapeProviderId.SCREENSCRAPER
    override val providesMetadata = true

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> {
        val systemId = ScreenScraperSystems.idFor(query.platform)
        if (query.md5 != null || query.crc32 != null) {
            val byHash = client.gameInfo(
                SsRomQuery(
                    crc32 = query.crc32,
                    md5 = query.md5,
                    sizeBytes = query.sizeBytes,
                    systemId = systemId,
                    fileName = query.fileName,
                ),
            )
            if (byHash !is ApiResult.Success || byHash.value != null) {
                return byHash.map { game -> listOfNotNull(game?.toProviderGame(query, systemId)) }
            }
        }
        return client.search(query.title, systemId).map { list -> list.map { it.toProviderGame(query, systemId) } }
    }

    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>) =
        ApiResult.Success(game.artwork.filter { it.kind in kinds })

    private fun SsGame.toProviderGame(query: ScrapeQuery, systemId: Int?): ProviderGame {
        val regions = listOfNotNull(query.preferredRegion) + query.regions
        return ProviderGame(
            provider = ScrapeProviderId.SCREENSCRAPER,
            providerGameId = id.orEmpty(),
            title = name(regions),
            alternativeTitles = noms.map { it.text }.filter { it.isNotBlank() }.distinct(),
            platformNames = listOfNotNull(systeme?.text?.takeIf { it.isNotBlank() }),
            platformEvidence = if (systemId != null && systeme?.id == systemId.toString()) PlatformEvidence.MATCH else null,
            year = year,
            regions = noms.mapNotNull { it.region },
            previewUrl = null,
            metadata = toMetadata(query.preferredLanguage),
            artwork = artwork(regions),
            identifiedByChecksum = matchesChecksum(query.md5, query.crc32),
        )
    }
}

/**
 * SteamGridDB: artwork only. Portrait grids become box art, wide grids stay grids, plus heroes,
 * logos and icons. [baseFilters] applies to every request (nsfw/humor/epilepsy excluded by default).
 */
class SteamGridDbSource(
    private val client: SteamGridDbClient,
    private val baseFilters: SgdbFilters = SgdbFilters(limit = 20),
) : ScrapeSource {
    override val id = ScrapeProviderId.STEAMGRIDDB
    override val providesMetadata = false
    override val artworkKinds = setOf(MediaKind.SQUARE, MediaKind.BOXART, MediaKind.GRID, MediaKind.HERO, MediaKind.LOGO, MediaKind.ICON)

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> =
        client.searchAutocomplete(query.title).map { list -> list.map { it.toProviderGame() } }

    override suspend fun byId(id: String, query: ScrapeQuery): ApiResult<ProviderGame?> {
        val n = id.toLongOrNull() ?: return ApiResult.Success(null)
        return client.gameById(n).map { it?.toProviderGame() }
    }

    private fun io.github.matiyaaa.fuse.integrations.steamgriddb.SgdbGame.toProviderGame() = ProviderGame(
        provider = ScrapeProviderId.STEAMGRIDDB,
        providerGameId = id.toString(),
        title = name,
        year = releaseDate?.let { Dates.yearOfEpochSeconds(it) },
    )

    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>): ApiResult<List<ArtworkOption>> {
        val gameId = game.providerGameId.toLongOrNull() ?: return ApiResult.Success(emptyList())
        val requests = buildList {
            if (MediaKind.SQUARE in kinds) add(SgdbAssetType.GRID to baseFilters.copy(dimensions = SgdbDimension.SQUARE))
            if (MediaKind.BOXART in kinds) add(SgdbAssetType.GRID to baseFilters.copy(dimensions = SgdbDimension.PORTRAIT))
            if (MediaKind.GRID in kinds) {
                add(SgdbAssetType.GRID to baseFilters.copy(dimensions = setOf(SgdbDimension.GRID_460x215, SgdbDimension.GRID_920x430)))
            }
            if (MediaKind.HERO in kinds) add(SgdbAssetType.HERO to baseFilters)
            if (MediaKind.LOGO in kinds) add(SgdbAssetType.LOGO to baseFilters)
            if (MediaKind.ICON in kinds) add(SgdbAssetType.ICON to baseFilters)
        }
        val out = ArrayList<ArtworkOption>()
        for ((type, filters) in requests) {
            when (val page = client.assets(type, gameId, filters)) {
                is ApiResult.Failure -> return page
                is ApiResult.Success -> out += page.value.data.map { it.toArtworkOption(type) }.filter { it.kind in kinds }
            }
        }
        return ApiResult.Success(out)
    }
}

/**
 * Libretro thumbnails: artwork only, found by name. "Search" is a real HEAD probe for a box art or
 * snap under the label, file name and short name; the found name is the game id.
 */
class LibretroSource(private val thumbnails: LibretroThumbnails) : ScrapeSource {
    override val id = ScrapeProviderId.LIBRETRO
    override val providesMetadata = false
    override val artworkKinds = LibretroThumbnailType.entries.map { it.kind }.toSet()
    override val searchesByKeyword = false

    override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> =
        thumbnails.find(query.platform, query.title, query.fileName, searched)
            .map { found ->
                found.firstOrNull()?.let { hit ->
                    listOf(
                        ProviderGame(
                            provider = ScrapeProviderId.LIBRETRO,
                            providerGameId = "${hit.system}/${hit.name}",
                            title = hit.name,
                            platformEvidence = PlatformEvidence.MATCH,
                            previewUrl = hit.url,
                            artwork = found.map { it.toArtworkOption() },
                        ),
                    )
                } ?: emptyList()
            }

    /** Box art and snaps were already looked up by [search]; only the other types are asked for. */
    override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>): ApiResult<List<ArtworkOption>> {
        val known = game.artwork.filter { it.kind in kinds }
        val types = LibretroThumbnailType.entries.filter { it.kind in kinds && it !in searched }.toSet()
        if (types.isEmpty()) return ApiResult.Success(known)
        return thumbnails.find(query.platform, game.title, null, types).map { list -> known + list.map { it.toArtworkOption() } }
    }

    private companion object {
        val searched = setOf(LibretroThumbnailType.BOXART, LibretroThumbnailType.SNAP)
    }
}
