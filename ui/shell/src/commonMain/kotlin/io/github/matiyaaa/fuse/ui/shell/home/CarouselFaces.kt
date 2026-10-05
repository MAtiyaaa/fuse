package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.jellyfin.POSTER_WIDTH
import io.github.matiyaaa.fuse.ui.shell.jellyfin.WIDE_WIDTH
import io.github.matiyaaa.fuse.ui.shell.jellyfin.accentOf
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** The corners of a catalogue's cards, against the theme's tile corner: the widget's own. */
@Composable
private fun cardShape() = Fuse.geometry.tileCornerFraction.let { f -> remember(f) { SquircleShape.fraction(f * 0.6f) } }

/** How much room the face has, for type sizes. */
private enum class Room { TINY, SHORT, TALL, BIG }

private fun roomOf(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) = when {
    height >= 300.dp && width >= 360.dp -> Room.BIG
    height >= 200.dp -> Room.TALL
    height >= 120.dp -> Room.SHORT
    else -> Room.TINY
}

// ------------------------------------------------------------------------------------ games

/**
 * A list of games as a catalogue: one game's art fills the card in front, its logo (or name) and a
 * line about it over its own colour deepened, and the next game waits at the right edge. New in
 * Library, Recently Played, Favourites and Pinned all turn this way.
 */
@Composable
internal fun GameCarousel(kind: WidgetKind, games: List<GameCard>, feed: HomeFeed, face: FaceSize) {
    if (games.isEmpty()) {
        Framed(kind, null, null) { EmptyFace(widgetIcon(kind), kind.title(), emptyGamesText(kind)) }
        return
    }
    val shown = games.take(CATALOGUE_ITEMS)
    val compact = face == FaceSize.SMALL || face == FaceSize.TALL
    Carousel(
        count = shown.size,
        peek = true,
        cardShape = cardShape(),
        header = { dots -> CarouselHeader(widgetIcon(kind), widgetLabel(kind), dots, compact = compact) },
    ) { i, depth ->
        GameSlide(shown[i], gameLine(kind, shown[i]), feed.systems.firstOrNull { it.platform.id == shown[i].platformId }, face, depth)
    }
}

@Composable
private fun GameSlide(game: GameCard, line: String, system: PlatformCard?, face: FaceSize, depth: CarouselDepth) {
    val c = Fuse.colors
    val accent = game.accent.toColor()
    val deep = lerp(accent, Color.Black, 0.86f)
    val backdrop = game.art.hero ?: game.art.screenshot ?: game.art.grid
    val cover = game.art.boxart ?: game.art.square
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val room = roomOf(w, h)
        val pad = if (room == Room.TINY || w < 200.dp) Space.m else Space.l
        Box(Modifier.fillMaxSize().carouselParallax(depth)) {
            when {
                backdrop != null -> Artwork(
                    backdrop, Modifier.fillMaxSize(),
                    focusX = game.art.heroFocusX, focusY = game.art.heroFocusY,
                    fallback = { AccentField(accent) },
                )
                cover != null && face == FaceSize.SMALL -> Artwork(cover, Modifier.fillMaxSize(), fallback = { AccentField(accent) })
                else -> AccentField(accent)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to deep.copy(alpha = 0.94f))))
        if (w > h * 1.4f) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to deep.copy(alpha = 0.7f), 0.6f to Color.Transparent)))
        // A cover stands beside the words where there is room, like a box on a shelf.
        val coverBeside = cover != null && backdrop != null && room != Room.TINY && face != FaceSize.SMALL && w >= 300.dp
        // A card waiting at the edge shows its picture only: its words come in as it comes forward.
        Row(Modifier.fillMaxSize().padding(pad).graphicsLayer { alpha = frontness(depth) }, verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                val logoHeight = when (room) {
                    Room.BIG -> (h * 0.24f).coerceIn(48.dp, 140.dp)
                    Room.TALL -> (h * 0.26f).coerceIn(40.dp, 84.dp)
                    Room.SHORT -> (h * 0.3f).coerceIn(30.dp, 56.dp)
                    Room.TINY -> (h * 0.3f).coerceIn(22.dp, 40.dp)
                }
                GameMark(
                    game,
                    Modifier.widthIn(max = if (coverBeside) w * 0.56f else w * 0.8f).fillMaxWidth().height(logoHeight),
                    when (room) {
                        Room.BIG -> Fuse.type.title
                        Room.TALL -> Fuse.type.titleSmall
                        else -> Fuse.type.bodyStrong
                    },
                )
                if (room != Room.TINY && line.isNotEmpty()) {
                    Spacer(Modifier.height(Space.xs + Space.xxs))
                    FText(line, Fuse.type.caption, color = c.onArtMuted, maxLines = 1, fit = true)
                }
            }
            if (coverBeside) {
                Spacer(Modifier.width(Space.l))
                Box(Modifier.height((h * if (room == Room.SHORT) 0.78f else 0.62f).coerceAtMost(260.dp)).aspectRatio(Aspect.BOX).lifted(CoverShape)) {
                    Artwork(cover, Modifier.fillMaxSize(), fallback = { GeneratedArt(game.title, accent, slot = ArtSlot.BOX, showText = false) })
                }
            }
        }
        // The system it runs on, top right, where the card has the room for it beside a cover.
        if (w >= 260.dp && room != Room.TINY && !(coverBeside && room == Room.SHORT)) {
            Box(Modifier.align(Alignment.TopEnd).padding(pad).graphicsLayer { alpha = frontness(depth) }) { PlatformChip(game, system) }
        }
    }
}

private fun gameLine(kind: WidgetKind, g: GameCard): String = when (kind) {
    WidgetKind.RECENTLY_ADDED -> "Added ${agoText(g.addedAt)}"
    WidgetKind.RECENTLY_PLAYED -> g.lastPlayedAt?.let { "Played ${agoText(it)}" } ?: g.platformShort
    WidgetKind.FAVORITES, WidgetKind.PINNED_GAMES -> listOfNotNull(
        g.platformShort,
        g.playSeconds.takeIf { it >= 60 }?.let { "${playtimeText(it)} played" },
    ).joinToString("  ·  ")
    else -> g.platformShort
}

private fun emptyGamesText(kind: WidgetKind): String = when (kind) {
    WidgetKind.RECENTLY_PLAYED -> "Games you play show here"
    WidgetKind.FAVORITES -> "Add favourites from a game's options"
    WidgetKind.PINNED_GAMES -> "Pin games from their options"
    WidgetKind.RECENTLY_ADDED -> "New games show here after a scan"
    else -> "Nothing here yet"
}

// ---------------------------------------------------------------------------------- systems

/**
 * Your systems as a catalogue, the one you played last first: each card is the system's own art
 * (the pack's console, its logo and colour), with how many games it has, and the next system
 * waiting at the edge.
 */
@Composable
internal fun SystemsCarousel(feed: HomeFeed, face: FaceSize) {
    val all = systemsInOrder(feed)
    if (all.isEmpty()) {
        Framed(WidgetKind.SYSTEMS, null, null) { EmptyFace(FuseIcons.Chip, WidgetKind.SYSTEMS.title(), "Systems show once Fuse finds games") }
        return
    }
    val compact = face == FaceSize.SMALL || face == FaceSize.TALL
    Carousel(
        count = all.size,
        peek = true,
        cardShape = cardShape(),
        header = { dots -> CarouselHeader(FuseIcons.Chip, WidgetKind.SYSTEMS.title(), dots, compact = compact) },
    ) { i, depth ->
        SystemSlide(all[i], face, depth)
    }
}

/** The systems in the order the carousel shows them: the one played last first, then the rest as listed. */
internal fun systemsInOrder(feed: HomeFeed): List<PlatformCard> {
    val systems = feed.systems
    val lead = feed.continuePlaying.firstOrNull()?.platformId?.let { id -> systems.firstOrNull { it.platform.id == id } } ?: return systems
    return listOf(lead) + systems.filter { it !== lead }
}

@Composable
private fun SystemSlide(s: PlatformCard, face: FaceSize, depth: CarouselDepth) {
    val c = Fuse.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val room = roomOf(maxWidth, maxHeight)
        when {
            // Art of the person's own choosing (or a square icon) fills the card; its name goes over a soft floor.
            (s.art.square ?: s.art.icon) != null -> {
                Box(Modifier.fillMaxSize().carouselParallax(depth)) { SystemCardArt(s, large = room == Room.BIG) }
                if (face != FaceSize.SMALL) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to c.artScrim)))
                    Column(Modifier.align(Alignment.BottomStart).padding(if (room == Room.TINY) Space.m else Space.l).graphicsLayer { alpha = frontness(depth) }) {
                        FText(s.platform.name, if (room == Room.BIG) Fuse.type.title else Fuse.type.bodyStrong, color = c.onArt, maxLines = 1, fit = true)
                        FText(gamesText(s.gameCount), Fuse.type.caption.tabular(), color = c.onArtMuted, maxLines = 1, fit = true)
                    }
                }
            }
            s.art.boxart != null || s.art.logo != null -> SystemPackSlide(s, room, depth)
            else -> Box(Modifier.fillMaxSize()) { SystemCardArt(s, large = room == Room.BIG) }
        }
    }
}

/** How much a card is the one in front: 1 there, fading to 0 as it moves aside (a peeking card's words don't show cut off). */
private fun frontness(depth: CarouselDepth): Float = (1f - kotlin.math.abs(depth()) * 1.6f).coerceIn(0f, 1f)

/**
 * A system from the art pack, laid out for a wide card: its colour across the card, the console's
 * art as a panel on the right that fades into it, and the logo set modestly at the bottom left with
 * the count under it, the way a console's own menu shows it. Sizes come from the card's height, so
 * the logo never grows past a label on a wide screen.
 */
@Composable
private fun BoxWithConstraintsScope.SystemPackSlide(s: PlatformCard, room: Room, depth: CarouselDepth) {
    val c = Fuse.colors
    val accent = s.platform.accent.toColor()
    val deep = lerp(accent, Color.Black, 0.6f)
    val w = maxWidth
    val h = maxHeight
    val pad = if (room == Room.TINY || w < 220.dp) Space.m else Space.l
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(accent, Color.Black, 0.12f), deep))))
    // A soft sheen from the top left, so a flat brand colour has some depth.
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset.Zero, radius = with(LocalDensity.current) { (h * 1.6f).toPx() })))
    if (s.art.boxart != null) {
        val panel = (h * 0.9f).coerceAtLeast(w * 0.3f).coerceAtMost(w * 0.46f)
        // Clipped to its panel: the parallax zoom never lets the art spill past the fade.
        Box(Modifier.align(Alignment.CenterEnd).width(panel).fillMaxHeight().clipToBounds()) {
            Box(Modifier.fillMaxSize().carouselParallax(depth)) {
                Artwork(s.art.boxart, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, focusX = 0.5f, focusY = 0.38f)
            }
        }
        // The panel's left edge melts into the card's colour.
        Box(
            Modifier.align(Alignment.CenterEnd).width(panel).fillMaxHeight()
                .background(Brush.horizontalGradient(0f to lerp(accent, Color.Black, 0.35f), 0.55f to Color.Transparent)),
        )
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to deep.copy(alpha = 0.55f))))
    val logoHeight = when (room) {
        Room.BIG -> (h * 0.16f).coerceIn(36.dp, 56.dp)
        Room.TALL -> (h * 0.17f).coerceIn(28.dp, 44.dp)
        Room.SHORT -> (h * 0.2f).coerceIn(22.dp, 34.dp)
        Room.TINY -> (h * 0.22f).coerceIn(18.dp, 28.dp)
    }
    Column(
        Modifier.align(Alignment.BottomStart).padding(pad).widthIn(max = (w * 0.42f).coerceAtMost(260.dp)).graphicsLayer { alpha = frontness(depth) },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (s.art.logo != null) {
            Artwork(
                s.art.logo,
                Modifier.fillMaxWidth().height(logoHeight),
                contentScale = ContentScale.Fit, focusX = 0f, focusY = 1f, tint = c.onArt,
                fallback = { FText(s.platform.shortName, Fuse.type.title, color = c.onArt, maxLines = 1, fit = true) },
            )
        } else {
            FText(s.platform.shortName, if (room == Room.BIG) Fuse.type.title else Fuse.type.bodyStrong, color = c.onArt, maxLines = 1, fit = true)
        }
        if (room != Room.TINY) {
            FText(gamesText(s.gameCount), Fuse.type.caption.tabular(), color = c.onArtMuted, maxLines = 1, fit = true)
        }
    }
}

// ------------------------------------------------------------------------------ collections

/**
 * Your collections as a catalogue: each card lit in a colour of its own, its mark, its name set
 * large and how many games are in it, with the next collection waiting at the edge.
 */
@Composable
internal fun CollectionsCarousel(feed: HomeFeed, face: FaceSize) {
    val all = feed.collections
    if (all.isEmpty()) {
        Framed(WidgetKind.COLLECTIONS, null, null) { EmptyFace(FuseIcons.Bookmark, WidgetKind.COLLECTIONS.title(), "Make one from a game's options") }
        return
    }
    val compact = face == FaceSize.SMALL || face == FaceSize.TALL
    Carousel(
        count = all.size,
        peek = true,
        cardShape = cardShape(),
        header = { dots -> CarouselHeader(FuseIcons.Bookmark, WidgetKind.COLLECTIONS.title(), dots, compact = compact) },
    ) { i, depth ->
        CollectionSlide(all[i], depth)
    }
}

@Composable
private fun CollectionSlide(col: GameCollection, depth: CarouselDepth) {
    val c = Fuse.colors
    val tint = accentOf(col.name)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val room = roomOf(maxWidth, maxHeight)
        Box(
            Modifier.fillMaxSize().carouselParallax(depth).background(
                Brush.radialGradient(
                    0f to lerp(tint, Color.White, 0.1f),
                    0.65f to lerp(tint, Color.Black, 0.55f),
                    1f to lerp(tint, Color.Black, 0.78f),
                    center = Offset(0f, 0f),
                    radius = 1_200f,
                ),
            ),
        )
        // A large bookmark set into the card's right, like a ribbon in a book.
        val mark = (maxHeight * 0.9f).coerceAtMost(maxWidth * 0.5f)
        FuseIcon(
            FuseIcons.Bookmark, size = mark, tint = Color.White.copy(alpha = 0.1f),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Space.l),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(if (room == Room.TINY) Space.m else Space.l).graphicsLayer { alpha = frontness(depth) }) {
            FText(
                col.name,
                when (room) {
                    Room.BIG -> Fuse.type.display
                    Room.TALL -> Fuse.type.title
                    else -> Fuse.type.titleSmall
                },
                color = c.onArt, maxLines = 2,
            )
            Spacer(Modifier.height(Space.xxs))
            FText(gamesText(col.gameCount), Fuse.type.caption.tabular(), color = c.onArtMuted, maxLines = 1, fit = true)
        }
    }
}

// --------------------------------------------------------------------------------- jellyfin

/**
 * Jellyfin's widgets as catalogues: each film, episode or album in its own picture, its title and
 * where it's up to, how far in it is, and the next one at the edge.
 */
@Composable
internal fun MediaCarousel(kind: WidgetKind, items: List<MediaItem>, face: FaceSize) {
    if (items.isEmpty()) {
        Framed(kind, null, null) { EmptyFace(widgetIcon(kind), kind.title(), emptyMediaText(kind)) }
        return
    }
    val shown = items.take(CATALOGUE_ITEMS)
    val compact = face == FaceSize.SMALL || face == FaceSize.TALL
    Carousel(
        count = shown.size,
        peek = true,
        cardShape = cardShape(),
        header = { dots -> CarouselHeader(widgetIcon(kind), widgetLabel(kind), dots, compact = compact) },
    ) { i, depth ->
        MediaSlide(shown[i], face, depth)
    }
}

@Composable
private fun MediaSlide(m: MediaItem, face: FaceSize, depth: CarouselDepth) {
    val c = Fuse.colors
    val accent = accentOf(m.seriesName ?: m.name)
    val deep = lerp(accent, Color.Black, 0.86f)
    val album = m.type == MediaType.ALBUM
    val wide = (if (m.type == MediaType.EPISODE) m.thumb ?: m.backdrop else m.backdrop ?: m.thumb)
    val poster = if (m.type == MediaType.EPISODE) m.seriesPoster ?: m.poster else m.poster
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val room = roomOf(w, h)
        val pad = if (room == Room.TINY || w < 200.dp) Space.m else Space.l
        Box(Modifier.fillMaxSize().carouselParallax(depth)) {
            when {
                wide != null && !album -> Artwork(wide.sized(WIDE_WIDTH), Modifier.fillMaxSize(), fallback = { AccentField(accent) })
                // An album without a backdrop: its cover, blurred into a field of its colour.
                else -> AccentField(accent)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to deep.copy(alpha = 0.94f))))
        if (w > h * 1.4f) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to deep.copy(alpha = 0.72f), 0.6f to Color.Transparent)))
        val coverBeside = poster != null && room != Room.TINY && face != FaceSize.SMALL && (w >= 300.dp || album || wide == null)
        // A card waiting at the edge shows its picture only: its words come in as it comes forward.
        Row(Modifier.fillMaxSize().padding(pad).graphicsLayer { alpha = frontness(depth) }, verticalAlignment = Alignment.Bottom) {
            if (coverBeside && album) {
                MediaCover(poster, accent, m, h, square = true)
                Spacer(Modifier.width(Space.l))
            }
            Column(Modifier.weight(1f)) {
                FText(
                    mediaTitle(m),
                    when (room) {
                        Room.BIG -> Fuse.type.title
                        Room.TALL -> Fuse.type.titleSmall
                        else -> Fuse.type.bodyStrong
                    },
                    color = c.onArt, maxLines = 2,
                )
                val line = if (album) m.albumArtist ?: m.artists.firstOrNull() ?: m.year?.toString() else mediaCaption(m)
                if (room != Room.TINY && line != null) {
                    Spacer(Modifier.height(Space.xxs))
                    FText(line, Fuse.type.caption, color = c.onArtMuted, maxLines = 1, fit = true)
                }
                m.progress?.let { p ->
                    Spacer(Modifier.height(Space.s))
                    ProgressBar(p, Modifier.fillMaxWidth(if (coverBeside) 0.8f else 0.6f))
                }
            }
            if (coverBeside && !album) {
                Spacer(Modifier.width(Space.l))
                MediaCover(poster, accent, m, h, square = false)
            }
        }
    }
}

@Composable
private fun MediaCover(poster: io.github.matiyaaa.fuse.jellyfin.JellyfinArt?, accent: Color, m: MediaItem, h: androidx.compose.ui.unit.Dp, square: Boolean) {
    Box(Modifier.fillMaxHeight(if (square) 0.82f else 0.72f).heightCap(h).aspectRatio(if (square) 1f else 2f / 3f).lifted(CoverShape)) {
        Artwork(poster?.sized(POSTER_WIDTH), Modifier.fillMaxSize(), fallback = {
            GeneratedArt(m.seriesName ?: m.name, accent, slot = if (square) ArtSlot.ICON else ArtSlot.BOX, showText = false)
        })
    }
}

/** Covers never grow past a comfortable size on a very tall face. */
private fun Modifier.heightCap(h: androidx.compose.ui.unit.Dp): Modifier = if (h > 340.dp) this.height(260.dp) else this

private fun emptyMediaText(kind: WidgetKind): String = when (kind) {
    WidgetKind.JELLYFIN_CONTINUE -> "Films and episodes you stop part way show here"
    WidgetKind.JELLYFIN_NEXT_UP -> "The next episode of shows you watch shows here"
    WidgetKind.JELLYFIN_FAVORITES -> "Films and shows you mark as favourites show here"
    WidgetKind.JELLYFIN_MOVIES -> "New films on your server show here"
    WidgetKind.JELLYFIN_MUSIC -> "New albums on your server show here"
    else -> "What's new on your Jellyfin server shows here"
}

/** At most this many items in a catalogue. */
private const val CATALOGUE_ITEMS = 16
