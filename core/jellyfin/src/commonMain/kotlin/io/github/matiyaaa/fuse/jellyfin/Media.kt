package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.playback.Chapter
import io.github.matiyaaa.fuse.playback.MediaKind
import io.github.matiyaaa.fuse.playback.PlayItem

/** What a media item is, in Fuse's words. */
enum class MediaType { MOVIE, SERIES, SEASON, EPISODE, COLLECTION, FOLDER, LIBRARY, ALBUM, ARTIST, SONG, PERSON, PLAYLIST, VIDEO, OTHER }

/** What a library holds. */
enum class LibraryKind { MOVIES, SHOWS, MUSIC, COLLECTIONS, VIDEOS, MIXED, OTHER }

/** Which picture of an item. */
enum class ArtKind(val jellyfin: String) { PRIMARY("Primary"), BACKDROP("Backdrop"), LOGO("Logo"), THUMB("Thumb") }

/**
 * A picture on the server, for the image loader: the item, which picture, its tag (it changes
 * when the picture does) and the width wanted. The loader turns it into a URL on whichever route
 * is in use, but caches it by these fields alone, so switching between local and remote keeps
 * every picture already loaded.
 */
data class JellyfinArt(val itemId: String, val kind: ArtKind, val tag: String, val index: Int = 0, val width: Int = 0) {
    /** The cache key: the same picture on any route. */
    val key: String get() = "jellyfin:$itemId:${kind.name}:$index:$tag:$width"

    fun sized(w: Int) = copy(width = w)
}

data class CastMember(val id: String?, val name: String, val role: String?, val kind: String?, val photo: JellyfinArt?)

/**
 * A movie, show, season, episode, album, song or anything else on the server, as Fuse shows it.
 * Times are milliseconds. Episodes carry their show's art where they have none of their own.
 */
data class MediaItem(
    val id: String,
    val name: String,
    val type: MediaType,
    val overview: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val endYear: Int? = null,
    val premiere: String? = null,
    val runtimeMs: Long? = null,
    val rating: Double? = null,
    val criticRating: Double? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val parentId: String? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val resumeMs: Long = 0,
    val playedPercent: Double? = null,
    val unplayed: Int? = null,
    val childCount: Int? = null,
    val poster: JellyfinArt? = null,
    /** An episode's or season's show poster, tall where the episode's own picture is wide. */
    val seriesPoster: JellyfinArt? = null,
    val backdrop: JellyfinArt? = null,
    val backdrops: List<JellyfinArt> = emptyList(),
    val logo: JellyfinArt? = null,
    val thumb: JellyfinArt? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artists: List<String> = emptyList(),
    val albumArtist: String? = null,
    val artistId: String? = null,
    val versions: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val library: LibraryKind? = null,
    /** Width over height of the item's main picture, for laying out shelves. */
    val aspect: Float? = null,
    /** "Continuing" or "Ended" for shows. */
    val status: String? = null,
    /** Video size, codecs and HDR for the facts line (from the first version). */
    val videoFacts: String? = null,
    val audioFacts: String? = null,
) {
    /** Time left to watch, for "Continue watching". */
    val leftMs: Long? get() = runtimeMs?.let { (it - resumeMs).coerceAtLeast(0) }

    val progress: Float? get() = if (resumeMs > 0 && (runtimeMs ?: 0) > 0) (resumeMs.toFloat() / runtimeMs!!).coerceIn(0f, 1f) else null

    val isPlayable: Boolean get() = type == MediaType.MOVIE || type == MediaType.EPISODE || type == MediaType.VIDEO || type == MediaType.SONG

    /** "S1:E3" for an episode. */
    val episodeLabel: String? get() = if (season != null && episode != null) "S$season:E$episode" else episode?.let { "E$it" }

    /** For Fuse Player. */
    fun toPlayItem(): PlayItem = PlayItem(
        id = id,
        title = if (type == MediaType.EPISODE) seriesName ?: name else name,
        subtitle = when (type) {
            MediaType.EPISODE -> listOfNotNull(episodeLabel, name).joinToString("  ·  ")
            MediaType.SONG -> listOfNotNull(artists.firstOrNull() ?: albumArtist, album).joinToString("  ·  ").ifEmpty { null }
            else -> year?.toString()
        },
        kind = if (type == MediaType.SONG) MediaKind.AUDIO else MediaKind.VIDEO,
        durationMs = runtimeMs,
        chapters = chapters,
        artwork = if (type == MediaType.EPISODE) thumb ?: poster else poster,
        backdrop = backdrop,
        logo = logo,
        poster = if (type == MediaType.EPISODE || type == MediaType.SEASON) seriesPoster ?: poster else poster,
        resumeMs = resumeMs,
        seriesId = seriesId,
        seasonId = seasonId,
        season = season,
        episode = episode,
        album = album,
        artist = artists.firstOrNull() ?: albumArtist,
    )
}

/** A page of items and how many there are in all. */
data class MediaPage(val items: List<MediaItem>, val total: Int, val start: Int) {
    val hasMore: Boolean get() = start + items.size < total
}

/** A row on the Jellyfin home. */
data class Shelf(val id: String, val title: String, val kind: ShelfKind, val items: List<MediaItem>, val libraryId: String? = null)

/** What Home's Jellyfin widgets show. */
data class MediaFeed(
    val continueWatching: List<MediaItem> = emptyList(),
    val nextUp: List<MediaItem> = emptyList(),
    val recentlyAdded: List<MediaItem> = emptyList(),
)

enum class ShelfKind { CONTINUE, NEXT_UP, LATEST, LIBRARY, FAVORITES, COLLECTIONS, MUSIC }

/** How items are sorted in a library. */
enum class MediaSort(val jellyfin: String, val label: String, val descending: Boolean) {
    NAME("SortName", "A to Z", false),
    ADDED("DateCreated", "Recently added", true),
    RELEASED("PremiereDate,ProductionYear", "Release date", true),
    RATING("CommunityRating", "Rating", true),
    PLAYED("DatePlayed", "Recently played", true),
}

/** Which items a library page shows. */
enum class MediaFilter(val jellyfin: String?, val label: String) {
    ALL(null, "All"),
    UNPLAYED("IsUnplayed", "Unwatched"),
    FAVORITES("IsFavorite", "Favourites"),
}
