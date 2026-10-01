package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.TileMetrics
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

val LocalTileMetrics = staticCompositionLocalOf { TileMetrics.forHeight(720.dp, 1280.dp) }

/** Square box art or tall posters on game tiles (Settings, Appearance, Game art). */
val LocalGameArt = staticCompositionLocalOf { GameArtStyle.BOX_ART }

/**
 * The size of a game tile for a [base] size: a square, or a 2:3 poster of about the same area
 * (a little narrower, a little taller), so a row of posters reads as a shelf of cases.
 */
fun GameArtStyle.tileSize(base: Dp): DpSize = when (this) {
    GameArtStyle.BOX_ART -> DpSize(base, base)
    GameArtStyle.POSTER -> DpSize(base * 0.8f, base * 1.2f)
}

/**
 * A game's art as a poster: its portrait cover, else its square art or icon drawn whole over a soft
 * copy of itself, else its wide art the same way.
 */
@Composable
fun PosterGameArt(art: Art, modifier: Modifier = Modifier, fallback: @Composable () -> Unit = {}) {
    if (art.boxart != null) {
        Artwork(model = art.boxart, modifier = modifier, fallback = fallback)
    } else {
        Artwork(
            model = art.square ?: art.icon ?: art.grid,
            modifier = modifier,
            backdrop = true,
            backdropBlur = if (Fuse.quality.blur) 14.dp else 0.dp,
            fallback = fallback,
        )
    }
}

/**
 * A game's art in a square: its square box art, else its icon, else its portrait cover drawn whole
 * over a soft copy of itself (so its title isn't cropped away), else its wide art.
 */
@Composable
fun SquareGameArt(art: Art, modifier: Modifier = Modifier, fallback: @Composable () -> Unit = {}) {
    val direct = art.square ?: art.icon
    if (direct == null && art.boxart != null) {
        Artwork(
            model = art.boxart,
            modifier = modifier,
            backdrop = true,
            backdropBlur = if (Fuse.quality.blur) 14.dp else 0.dp,
            fallback = fallback,
        )
    } else {
        Artwork(model = direct ?: art.grid, modifier = modifier, fallback = fallback)
    }
}

/**
 * A game tile: its square box art, or its poster when Game art is set to posters (sized by
 * [tileSize]). The tile of the Grid layout, most Home shelves and Cartridge's downloads.
 */
@Composable
fun GameIconTile(
    card: GameCard,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = LocalTileMetrics.current.icon,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val accent = card.accent.toColor()
    val style = LocalGameArt.current
    val poster = style == GameArtStyle.POSTER
    // Posters have softer corners for their size, like a game case.
    val corner = if (poster) Fuse.geometry.tileCornerFraction * 0.62f else Fuse.geometry.tileCornerFraction
    Tile(
        selected = selected,
        glow = accent,
        modifier = modifier.size(style.tileSize(size)),
        shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(corner),
        cornerFraction = corner,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        val art = Modifier.fillMaxSize().alpha(if (card.missing) 0.45f else 1f)
        val label = card.platformShort.takeIf { LocalTileShowsSystem.current }
        if (poster) {
            PosterGameArt(card.art, art, fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.BOX, label = label) })
        } else {
            SquareGameArt(card.art, art, fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.ICON, label = label) })
        }
        TileBorder(LocalTileBorders.current.of(card.platformId), accent, corner, label)
        TileBadges(card, Modifier.align(Alignment.TopEnd))
    }
}

/** Portrait box art: Capsule Mode and Cover Grid. */
@Composable
fun GameCoverTile(
    card: GameCard,
    selected: Boolean,
    width: Dp,
    modifier: Modifier = Modifier,
    aspect: Float = Aspect.CAPSULE,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val accent = card.accent.toColor()
    Tile(
        selected = selected,
        glow = accent,
        cornerFraction = Fuse.geometry.tileCornerFraction * 0.55f,
        shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.55f),
        modifier = modifier.width(width).aspectRatio(aspect),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Artwork(
            model = card.art.boxart ?: card.art.grid ?: card.art.square ?: card.art.icon,
            modifier = Modifier.fillMaxSize().alpha(if (card.missing) 0.45f else 1f),
            fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.BOX, label = card.platformShort.takeIf { LocalTileShowsSystem.current }) },
        )
        TileBorder(LocalTileBorders.current.of(card.platformId), accent, Fuse.geometry.tileCornerFraction * 0.55f, card.platformShort.takeIf { LocalTileShowsSystem.current })
        TileBadges(card, Modifier.align(Alignment.TopEnd))
    }
}

/** Wide landscape tile with the logo or title laid over its art: Continue Playing. */
@Composable
fun GameWideTile(
    card: GameCard,
    selected: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val accent = card.accent.toColor()
    Tile(
        selected = selected,
        glow = accent,
        cornerFraction = Fuse.geometry.tileCornerFraction * 0.7f,
        shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.7f),
        modifier = modifier.size(width = height * 1.78f, height = height),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Artwork(
            model = card.art.hero ?: card.art.grid ?: card.art.boxart,
            modifier = Modifier.fillMaxSize(),
            focusX = card.art.heroFocusX,
            focusY = card.art.heroFocusY,
            fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.WIDE, showText = false) },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(Space.m).fillMaxWidth(0.8f)) {
            if (card.art.logo != null) {
                Artwork(
                    model = card.art.logo,
                    modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(2.6f),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 1f,
                )
            } else {
                FText(card.title, Fuse.type.titleSmall, color = Color.White, maxLines = 2)
            }
            if (caption != null) FText(caption, Fuse.type.caption, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}

/** Small state marks in a tile corner: favourite, DLC/update available, missing file. */
@Composable
private fun TileBadges(card: GameCard, modifier: Modifier) {
    val marks = buildList {
        if (card.missing) add(FuseIcons.Warning)
        if (card.favorite) add(FuseIcons.Heart)
        if (card.dlc > 0 || card.updates > 0) add(FuseIcons.Layers)
    }
    if (marks.isEmpty()) return
    Row(modifier.padding(Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        for (icon in marks) {
            Box(
                Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { FuseIcon(icon, size = 13.dp, tint = Color.White) }
        }
    }
}

/** A system: its own icon art if set, then its system art pack card, otherwise an original typographic tile. */
@Composable
fun SystemTile(
    card: PlatformCard,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = LocalTileMetrics.current.icon,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val accent = card.platform.accent.toColor()
    Tile(
        selected = selected,
        glow = accent,
        modifier = modifier.size(size),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        SystemCardArt(card)
    }
}

/**
 * What a system card shows. Box art or an icon the user chose wins; then the system art pack's look (brand
 * colour, the artwork panel on the right, the logo in white, as in console-style frontends); with
 * no art at all, Fuse's own typographic [SystemGlyph].
 */
@Composable
fun SystemCardArt(card: PlatformCard, large: Boolean = false) {
    val accent = card.platform.accent.toColor()
    when {
        (card.art.square ?: card.art.icon) != null ->
            Artwork(card.art.square ?: card.art.icon, Modifier.fillMaxSize(), fallback = { SystemGlyph(card, accent, large) })
        card.art.boxart != null || card.art.logo != null -> PackCard(card, accent, large)
        else -> SystemGlyph(card, accent, large)
    }
}

@Composable
private fun PackCard(card: PlatformCard, accent: Color, large: Boolean) {
    val deep = lerp(accent, Color.Black, 0.55f)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(lerp(accent, Color.Black, 0.1f), deep))),
    ) {
        val cardHeight = maxHeight
        // Pack artwork is a tall panel (about 454 x 1080); it fills the height on the right.
        val artWidth = (maxHeight * 0.52f).coerceAtMost(maxWidth * 0.6f)
        if (card.art.boxart != null) {
            Artwork(
                card.art.boxart,
                Modifier.align(Alignment.CenterEnd).width(artWidth).fillMaxHeight(),
                contentScale = ContentScale.Crop,
                focusX = 0.5f,
                focusY = 0.35f,
            )
            // Blend the panel's left edge into the card colour.
            Box(
                Modifier.align(Alignment.CenterEnd).width(artWidth).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(lerp(accent, Color.Black, 0.3f), Color.Transparent), endX = with(LocalDensity.current) { (artWidth * 0.45f).toPx() })),
            )
        }
        // A soft floor so the white logo reads on light brand colours too.
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(if (large) Space.xl else Space.m).fillMaxWidth(0.62f),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (card.art.logo != null) {
                Artwork(
                    card.art.logo,
                    Modifier.fillMaxWidth().height((cardHeight * 0.24f).coerceIn(18.dp, 72.dp)),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 1f,
                    tint = Color.White,
                    fallback = { FText(card.platform.shortName, Fuse.type.title, color = Color.White, maxLines = 1) },
                )
            } else {
                FText(card.platform.shortName, if (large) Fuse.type.hero else Fuse.type.title, color = Color.White, maxLines = 1)
            }
            if (large || cardHeight > 120.dp) {
                FText(
                    "${card.gameCount} ${if (card.gameCount == 1) "game" else "games"}",
                    Fuse.type.caption,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
        }
        SystemWarning(card, Modifier.align(Alignment.TopEnd))
    }
}

/** A small warning mark when a system has no emulator. Firmware is told on the system's own page. */
@Composable
private fun SystemWarning(card: PlatformCard, modifier: Modifier) {
    if (!card.emulatorInstalled) {
        Box(
            modifier.padding(Space.s).size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { FuseIcon(FuseIcons.Warning, size = 13.dp, tint = Fuse.colors.warning) }
    }
}

/** Generated system art: short name large, maker and count small when the tile has room. */
@Composable
fun SystemGlyph(card: PlatformCard, accent: Color, large: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Small tiles (the Systems grid, Home) show the name alone.
        val roomy = large || maxHeight > 120.dp
        GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
        Column(
            Modifier.fillMaxSize().padding(if (large) Space.xl else if (roomy) Space.m else Space.s + Space.xs),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            FText(
                if (roomy) card.platform.manufacturer?.uppercase() ?: "" else "",
                Fuse.type.overline,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
            Column {
                FText(
                    card.platform.shortName,
                    if (large) Fuse.type.hero else if (roomy) Fuse.type.title else Fuse.type.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                )
                if (roomy) {
                    FText(
                        "${card.gameCount} ${if (card.gameCount == 1) "game" else "games"}",
                        Fuse.type.caption,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }
        }
        SystemWarning(card, Modifier.align(Alignment.TopEnd))
    }
}

/** An Android app or Linux application: its icon centred on a soft squircle. */
@Composable
fun AppTile(
    app: AppCard,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = LocalTileMetrics.current.icon,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val c = Fuse.colors
    Tile(
        selected = selected,
        glow = c.accent,
        modifier = modifier.size(size),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            Artwork(
                model = app.icon,
                modifier = Modifier.fillMaxSize(0.62f),
                contentScale = ContentScale.Fit,
                fallback = { GeneratedArt(app.entry.displayTitle, c.accent, slot = ArtSlot.ICON) },
            )
        }
    }
}
