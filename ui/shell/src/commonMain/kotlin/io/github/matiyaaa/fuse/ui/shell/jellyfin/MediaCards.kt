package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.background
import io.github.matiyaaa.fuse.ui.designsystem.effects.ditheredBackground
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular

/** Room between a card and its words for the focus bar the tile draws under itself, as it lifts. */
private val TEXT_GAP = Size.sparkGap + Size.sparkHeight + Space.s

/** A quiet colour for media without art, from its name, so placeholders differ but stay calm. */
internal fun accentOf(name: String): Color {
    val palette = listOf(0xFF5B6FB5, 0xFF7867B5, 0xFF5E9C8F, 0xFFB25E5E, 0xFF8C84B8, 0xFF5A8FBF, 0xFFC9A45C, 0xFF7D8BA8)
    return Color(palette[(name.hashCode() and 0x7FFFFFFF) % palette.size])
}

/**
 * A poster: the item's tall art (square for music), a watched tick or how many are left unwatched,
 * and how far in it is; the title and a quiet second line under it.
 */
@Composable
internal fun PosterCard(item: MediaItem, selected: Boolean, width: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val square = item.type == MediaType.ALBUM || item.type == MediaType.ARTIST || item.type == MediaType.SONG
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(TEXT_GAP)) {
        Tile(selected = selected, modifier = Modifier.fillMaxWidth().aspectRatio(if (square) 1f else 2f / 3f), glow = accentOf(item.name), onClick = onClick, onLongClick = onLongClick) {
            Artwork(item.poster.at(POSTER_WIDTH), Modifier.fillMaxSize(), fallback = { GeneratedArt(item.name, accentOf(item.name), Modifier.fillMaxSize(), slot = ArtSlot.BOX) })
            Badges(item, Modifier.align(Alignment.TopEnd).padding(Space.s))
            item.progress?.let { Progress(it, Modifier.align(Alignment.BottomCenter)) }
        }
        CardText(item.name, posterLine(item), selected)
    }
}

/**
 * A wide card, for things to continue or watch next: the episode's still (or the film's backdrop)
 * with how far in it is, the show or film, and the episode and time left.
 */
@Composable
internal fun WideCard(item: MediaItem, selected: Boolean, width: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val art = (if (item.type == MediaType.EPISODE) item.thumb else item.backdrop ?: item.thumb) ?: item.poster
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(TEXT_GAP)) {
        Tile(selected = selected, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), glow = accentOf(item.name), onClick = onClick, onLongClick = onLongClick) {
            Artwork(art.at(WIDE_WIDTH), Modifier.fillMaxSize(), fallback = { GeneratedArt(item.seriesName ?: item.name, accentOf(item.name), Modifier.fillMaxSize(), slot = ArtSlot.WIDE) })
            // A logo or the name over the lower part, for art without words in it.
            Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter).ditheredBackground(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Badges(item, Modifier.align(Alignment.TopEnd).padding(Space.s))
            item.progress?.let { Progress(it, Modifier.align(Alignment.BottomCenter)) }
        }
        val title = if (item.type == MediaType.EPISODE) item.seriesName ?: item.name else item.name
        val line = when {
            item.type == MediaType.EPISODE -> listOfNotNull(item.episodeLabel, item.name.takeIf { it != title }).joinToString("  ·  ")
            item.progress != null -> item.leftMs?.let { "${minutes(it)} left" }
            else -> item.year?.toString()
        }
        CardText(title, line, selected)
    }
}

/**
 * Something to carry on with or watch next, as a scene: the show's or film's wide art, its logo (or
 * name) over a shade at the foot, what it is ("S2:E5  ·  The Long Night"), and how far in as a slim
 * bar with the time left beside it. Everything sits inside the art, so a shelf of them reads as a
 * row of scenes rather than pictures with captions. Chosen, a play mark shows what A does.
 */
@Composable
internal fun ContinueCard(item: MediaItem, selected: Boolean, width: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val episode = item.type == MediaType.EPISODE
    // The show's own wide art reads better behind its logo than an episode's random frame.
    val art = item.backdrop ?: item.thumb ?: item.poster
    val title = if (episode) item.seriesName ?: item.name else item.name
    val line = when {
        episode -> listOfNotNull(item.episodeLabel, item.name.takeIf { it != title }).joinToString("  \u00B7  ")
        else -> listOfNotNull(item.year?.toString(), item.runtimeMs?.let { minutes(it) }).joinToString("  \u00B7  ")
    }.ifEmpty { null }
    Tile(
        selected = selected, label = listOfNotNull(title, line).joinToString(", "),
        modifier = Modifier.width(width).aspectRatio(16f / 9f), glow = accentOf(title), onClick = onClick, onLongClick = onLongClick,
    ) {
        Artwork(art.at(WIDE_WIDTH), Modifier.fillMaxSize(), fallback = { GeneratedArt(title, accentOf(title), Modifier.fillMaxSize(), slot = ArtSlot.WIDE, showText = false) })
        Box(
            Modifier.fillMaxSize().ditheredBackground(
                Brush.verticalGradient(0.3f to Color.Transparent, 0.62f to Color.Black.copy(alpha = 0.42f), 1f to Color.Black.copy(alpha = 0.86f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            val name: @Composable () -> Unit = { FText(title, Fuse.type.titleSmall, color = Color.White, maxLines = 1) }
            val logo = item.logo
            if (logo != null) {
                Artwork(
                    logo.at(LOGO_WIDTH), Modifier.height(width * LOGO_SHARE).fillMaxWidth(0.62f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit, focusX = 0f, focusY = 1f, fallback = name,
                )
            } else {
                name()
            }
            line?.let { FText(it, Fuse.type.caption, color = Color.White.copy(alpha = 0.86f), maxLines = 1) }
            val progress = item.progress
            if (progress != null) {
                Row(Modifier.padding(top = Space.xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.24f))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Fuse.colors.accent))
                    }
                    item.leftMs?.let { FText("${minutes(it)} left", Fuse.type.caption.tabular(), color = Color.White.copy(alpha = 0.86f), maxLines = 1) }
                }
            }
        }
        // What A does, where the eye already is.
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(Space.m).size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Play, size = 16.dp, tint = Color(0xFF101114))
            }
        } else {
            Badges(item, Modifier.align(Alignment.TopEnd).padding(Space.s))
        }
    }
}

/** A library: its own picture with its name over it, or a calm placeholder with an icon. */
@Composable
internal fun LibraryCard(item: MediaItem, selected: Boolean, width: Dp, onClick: () -> Unit) {
    Tile(selected = selected, modifier = Modifier.width(width).aspectRatio(16f / 9f), glow = accentOf(item.name), onClick = onClick) {
        Artwork(item.poster.at(WIDE_WIDTH), Modifier.fillMaxSize(), fallback = { GeneratedArt(item.name, accentOf(item.name), Modifier.fillMaxSize(), slot = ArtSlot.WIDE, showText = false) })
        Box(Modifier.fillMaxSize().ditheredBackground(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))))
        Row(Modifier.align(Alignment.BottomStart).padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(libraryIcon(item), size = 18.dp, tint = Color.White)
            Spacer(Modifier.width(Space.s))
            FText(item.name, Fuse.type.bodyStrong, color = Color.White, maxLines = 1)
        }
    }
}

/** A person: a round photo, the name, and the part they play. */
@Composable
internal fun PersonCard(name: String, role: String?, photo: Any?, selected: Boolean, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TEXT_GAP)) {
        Tile(selected = selected, modifier = Modifier.size(width), shape = CircleShape, cornerFraction = 0.5f, glow = accentOf(name), onClick = onClick) {
            Artwork(photo, Modifier.fillMaxSize(), fallback = { GeneratedArt(name, accentOf(name), Modifier.fillMaxSize()) })
        }
        FText(name, Fuse.type.label, color = if (selected) Fuse.colors.text else Fuse.colors.text.copy(alpha = 0.9f), maxLines = 1)
        if (role != null) FText(role, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

internal fun libraryIcon(item: MediaItem) = when (item.library) {
    io.github.matiyaaa.fuse.jellyfin.LibraryKind.MOVIES -> FuseIcons.Film
    io.github.matiyaaa.fuse.jellyfin.LibraryKind.SHOWS -> FuseIcons.Tv
    io.github.matiyaaa.fuse.jellyfin.LibraryKind.MUSIC -> FuseIcons.Music
    io.github.matiyaaa.fuse.jellyfin.LibraryKind.COLLECTIONS -> FuseIcons.Layers
    else -> FuseIcons.Folder
}

@Composable
private fun CardText(title: String, line: String?, selected: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xxs)) {
        FText(title, Fuse.type.label, color = if (selected) Fuse.colors.text else Fuse.colors.text.copy(alpha = 0.9f), maxLines = 1)
        if (line != null) FText(line, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

/** A tick when watched; for a show or season, how many episodes are left. */
@Composable
private fun Badges(item: MediaItem, modifier: Modifier) {
    val unplayed = item.unplayed?.takeIf { it > 0 && (item.type == MediaType.SERIES || item.type == MediaType.SEASON) }
    when {
        item.played -> Box(modifier.size(24.dp).clip(CircleShape).background(Fuse.colors.accent), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.Check, size = 14.dp, tint = Fuse.colors.onAccent)
        }
        unplayed != null -> Box(modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(Fuse.colors.accent).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            FText(unplayed.toString(), Fuse.type.caption.tabular(), color = Fuse.colors.onAccent, maxLines = 1)
        }
        item.favorite -> Box(modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.HeartFilled, size = 13.dp, tint = Color.White)
        }
    }
}

/** How far in: a thin accent bar along the bottom edge. */
@Composable
private fun Progress(fraction: Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Fuse.colors.accent))
    }
}

private fun posterLine(item: MediaItem): String? = when (item.type) {
    MediaType.SERIES -> listOfNotNull(item.year?.let { y -> if (item.endYear != null && item.endYear != y) "$y to ${item.endYear}" else "$y" }, seriesCount(item)).joinToString("  ·  ").ifEmpty { null }
    MediaType.SEASON -> item.childCount?.let { if (it == 1) "1 episode" else "$it episodes" }
    MediaType.EPISODE -> listOfNotNull(item.episodeLabel, item.seriesName).joinToString("  ·  ")
    MediaType.ALBUM -> item.albumArtist ?: item.artists.firstOrNull() ?: item.year?.toString()
    MediaType.SONG -> item.artists.firstOrNull() ?: item.album
    MediaType.COLLECTION -> item.childCount?.let { if (it == 1) "1 title" else "$it titles" }
    else -> listOfNotNull(item.year?.toString(), item.runtimeMs?.let { minutes(it) }).joinToString("  ·  ").ifEmpty { null }
}

/** "1 h 52 min", "2 h" or "48 min". */
internal fun minutes(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return when {
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

/** How wide a logo is asked for, for the cards that show one. */
private const val LOGO_WIDTH = 480

/** A logo's height on a [ContinueCard], as a share of the card's width. */
private const val LOGO_SHARE = 0.15f

/** A show's count for its line: its new episodes in a list of what's new, else its seasons. */
internal fun seriesCount(item: MediaItem): String? = item.newEpisodes?.let { if (it == 1) "1 new episode" else "$it new episodes" }
    ?: item.childCount?.let { if (it == 1) "1 season" else "$it seasons" }
