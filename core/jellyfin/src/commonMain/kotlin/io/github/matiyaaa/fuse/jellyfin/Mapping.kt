package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.playback.Chapter

/** Ticks (100 ns) to milliseconds. */
internal fun ticksToMs(ticks: Long?): Long? = ticks?.div(10_000)

internal fun msToTicks(ms: Long): Long = ms * 10_000

internal fun typeOf(dto: ItemDto): MediaType = when (dto.type) {
    "Movie" -> MediaType.MOVIE
    "Series" -> MediaType.SERIES
    "Season" -> MediaType.SEASON
    "Episode" -> MediaType.EPISODE
    "BoxSet" -> MediaType.COLLECTION
    "CollectionFolder", "UserView" -> MediaType.LIBRARY
    "Folder" -> MediaType.FOLDER
    "MusicAlbum" -> MediaType.ALBUM
    "MusicArtist" -> MediaType.ARTIST
    "Audio" -> MediaType.SONG
    "Person" -> MediaType.PERSON
    "Playlist" -> MediaType.PLAYLIST
    "Video", "MusicVideo" -> MediaType.VIDEO
    else -> MediaType.OTHER
}

internal fun libraryOf(collectionType: String?): LibraryKind? = when (collectionType?.lowercase()) {
    null -> null
    "movies" -> LibraryKind.MOVIES
    "tvshows" -> LibraryKind.SHOWS
    "music" -> LibraryKind.MUSIC
    "boxsets" -> LibraryKind.COLLECTIONS
    "homevideos", "musicvideos" -> LibraryKind.VIDEOS
    "mixed" -> LibraryKind.MIXED
    else -> LibraryKind.OTHER
}

/** Jellyfin's item as Fuse's [MediaItem]: art falls back to the show's or album's where it has none. */
internal fun ItemDto.toMedia(): MediaItem {
    val t = typeOf(this)
    val tags = imageTags.orEmpty()
    fun art(kind: ArtKind, tag: String?, owner: String = id, index: Int = 0) = tag?.let { JellyfinArt(owner, kind, it, index) }
    val poster = art(ArtKind.PRIMARY, tags["Primary"])
        ?: (if (t == MediaType.SONG) albumId?.let { a -> art(ArtKind.PRIMARY, albumPrimaryImageTag, a) } else null)
        ?: (if (t == MediaType.EPISODE || t == MediaType.SEASON) seriesId?.let { s -> art(ArtKind.PRIMARY, seriesPrimaryImageTag, s) } else null)
    val ownBackdrops = backdropImageTags.orEmpty().mapIndexed { i, tag -> JellyfinArt(id, ArtKind.BACKDROP, tag, i) }
    val backdrops = ownBackdrops.ifEmpty {
        val owner = parentBackdropItemId
        if (owner != null) parentBackdropImageTags.orEmpty().mapIndexed { i, tag -> JellyfinArt(owner, ArtKind.BACKDROP, tag, i) } else emptyList()
    }
    val logo = art(ArtKind.LOGO, tags["Logo"]) ?: parentLogoItemId?.let { o -> art(ArtKind.LOGO, parentLogoImageTag, o) }
    // An episode's own still is its Primary; its show's wide art is a Thumb.
    val thumb = if (t == MediaType.EPISODE) art(ArtKind.PRIMARY, tags["Primary"]) else art(ArtKind.THUMB, tags["Thumb"]) ?: parentThumbItemId?.let { o -> art(ArtKind.THUMB, parentThumbImageTag, o) }
    val firstSource = mediaSources?.firstOrNull()
    val video = firstSource?.mediaStreams?.firstOrNull { it.type == "Video" }
    val audio = firstSource?.mediaStreams?.firstOrNull { it.type == "Audio" && it.isDefault } ?: firstSource?.mediaStreams?.firstOrNull { it.type == "Audio" }
    return MediaItem(
        id = id,
        name = name ?: "Untitled",
        type = t,
        overview = overview?.trim()?.takeIf { it.isNotEmpty() },
        tagline = taglines?.firstOrNull(),
        year = productionYear ?: premiereDate?.take(4)?.toIntOrNull(),
        endYear = endDate?.take(4)?.toIntOrNull(),
        premiere = premiereDate,
        runtimeMs = ticksToMs(runTimeTicks),
        rating = communityRating,
        criticRating = criticRating,
        officialRating = officialRating,
        genres = genres.orEmpty(),
        studios = studios.orEmpty().mapNotNull { it.name },
        cast = people.orEmpty().filter { it.type == "Actor" || it.type == "GuestStar" }.mapNotNull { p ->
            val n = p.name ?: return@mapNotNull null
            CastMember(p.id, n, p.role?.takeIf { it.isNotBlank() }, p.type, p.id?.let { pid -> p.primaryImageTag?.let { JellyfinArt(pid, ArtKind.PRIMARY, it) } })
        },
        directors = people.orEmpty().filter { it.type == "Director" }.mapNotNull { it.name },
        writers = people.orEmpty().filter { it.type == "Writer" }.mapNotNull { it.name },
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        seasonName = seasonName,
        season = if (t == MediaType.SEASON) indexNumber else parentIndexNumber,
        episode = if (t == MediaType.EPISODE || t == MediaType.SONG) indexNumber else null,
        parentId = parentId,
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        resumeMs = ticksToMs(userData?.playbackPositionTicks) ?: 0,
        playedPercent = userData?.playedPercentage,
        unplayed = userData?.unplayedItemCount,
        childCount = childCount ?: recursiveItemCount,
        poster = poster,
        seriesPoster = if (t == MediaType.EPISODE || t == MediaType.SEASON) seriesId?.let { s -> art(ArtKind.PRIMARY, seriesPrimaryImageTag, s) } else null,
        backdrop = backdrops.firstOrNull(),
        backdrops = backdrops,
        logo = logo,
        thumb = thumb,
        album = album,
        albumId = albumId,
        artists = artists.orEmpty().ifEmpty { artistItems.orEmpty().mapNotNull { it.name } },
        albumArtist = albumArtist,
        artistId = (albumArtists ?: artistItems)?.firstOrNull()?.id,
        versions = mediaSources.orEmpty().mapNotNull { it.name }.takeIf { it.size > 1 }.orEmpty(),
        chapters = chapters.orEmpty().map { Chapter(ticksToMs(it.startPositionTicks) ?: 0, it.name.orEmpty()) },
        library = libraryOf(collectionType),
        aspect = primaryImageAspectRatio?.toFloat(),
        status = status,
        videoFacts = video?.let { v -> listOfNotNull(resolutionLabel(v.width, v.height), v.codec?.uppercase(), v.videoRangeType?.takeIf { it != "SDR" }).joinToString(" ").ifEmpty { null } },
        audioFacts = audio?.let { a -> listOfNotNull(a.codec?.uppercase(), a.channels?.let { channelLabel(it) }).joinToString(" ").ifEmpty { null } },
    )
}

internal fun resolutionLabel(width: Int?, height: Int?): String? {
    val w = width ?: return null
    val h = height ?: 0
    return when {
        w >= 3800 || h >= 2100 -> "4K"
        w >= 2500 || h >= 1400 -> "1440p"
        w >= 1900 || h >= 1000 -> "1080p"
        w >= 1200 || h >= 700 -> "720p"
        else -> "SD"
    }
}

internal fun channelLabel(n: Int): String = when (n) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$n ch"
}
