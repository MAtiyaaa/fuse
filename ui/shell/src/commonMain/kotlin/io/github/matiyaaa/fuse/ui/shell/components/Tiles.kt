package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
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
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

val LocalTileMetrics = staticCompositionLocalOf { TileMetrics.forHeight(720.dp, 1280.dp) }

/** Square game icon: the tile of Icon Mode and most Home shelves. */
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
    Tile(
        selected = selected,
        glow = accent,
        modifier = modifier.size(size),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Artwork(
            model = card.art.icon ?: card.art.boxart ?: card.art.grid,
            modifier = Modifier.fillMaxSize().alpha(if (card.missing) 0.45f else 1f),
            fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.ICON, label = card.platformShort) },
        )
        TileBorder(LocalTileBorders.current.of(card.platformId), accent, Fuse.geometry.tileCornerFraction, card.platformShort)
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
            model = card.art.boxart ?: card.art.grid ?: card.art.icon,
            modifier = Modifier.fillMaxSize().alpha(if (card.missing) 0.45f else 1f),
            fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.BOX, label = card.platformShort) },
        )
        TileBorder(LocalTileBorders.current.of(card.platformId), accent, Fuse.geometry.tileCornerFraction * 0.55f, card.platformShort)
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
 * What a system card shows. An icon the user chose wins; then the system art pack's look (brand
 * colour, the artwork panel on the right, the logo in white, as in console-style frontends); with
 * no art at all, Fuse's own typographic [SystemGlyph].
 */
@Composable
fun SystemCardArt(card: PlatformCard, large: Boolean = false) {
    val accent = card.platform.accent.toColor()
    when {
        card.art.icon != null -> Artwork(card.art.icon, Modifier.fillMaxSize(), fallback = { SystemGlyph(card, accent, large) })
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
            if (cardHeight > 96.dp) {
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

/** A small warning mark when a system has no emulator or is missing firmware. */
@Composable
private fun SystemWarning(card: PlatformCard, modifier: Modifier) {
    if (card.bios.state == BiosState.MISSING || card.bios.state == BiosState.PARTIAL || !card.emulatorInstalled) {
        Box(
            modifier.padding(Space.s).size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { FuseIcon(FuseIcons.Warning, size = 13.dp, tint = Fuse.colors.warning) }
    }
}

/** Generated system art: short name large, full name and count small. */
@Composable
fun SystemGlyph(card: PlatformCard, accent: Color, large: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
        Column(
            Modifier.fillMaxSize().padding(if (large) Space.xl else Space.m),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            FText(
                card.platform.manufacturer?.uppercase() ?: "",
                Fuse.type.overline,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
            Column {
                FText(
                    card.platform.shortName,
                    if (large) Fuse.type.hero else Fuse.type.title,
                    color = Color.White,
                    maxLines = 1,
                )
                FText(
                    "${card.gameCount} ${if (card.gameCount == 1) "game" else "games"}",
                    Fuse.type.caption,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
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
