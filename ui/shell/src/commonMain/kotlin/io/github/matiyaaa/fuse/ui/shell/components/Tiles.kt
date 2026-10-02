package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.effects.skeleton
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
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
    GameArtStyle.POSTER -> DpSize(base * POSTER_WIDTH, base * POSTER_HEIGHT)
}

/**
 * The base size whose [tileSize] is exactly [width] wide: for grids whose columns set the width, so
 * a tile fills its column and keeps its shape instead of being stretched to it.
 */
fun GameArtStyle.baseForWidth(width: Dp): Dp = when (this) {
    GameArtStyle.BOX_ART -> width
    GameArtStyle.POSTER -> width / POSTER_WIDTH
}

/** Corner of a game tile in this art style, as a fraction of its short side. Posters are softer, like a case. */
@Composable
internal fun GameArtStyle.cornerFraction(): Float =
    if (this == GameArtStyle.POSTER) Fuse.geometry.tileCornerFraction * POSTER_CORNER else Fuse.geometry.tileCornerFraction

/** Corner of portrait covers (Capsule Mode, Cover grid), as a fraction of their short side. */
@Composable
internal fun coverCornerFraction(): Float = Fuse.geometry.tileCornerFraction * COVER_CORNER

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
            backdropBlur = if (Fuse.quality.blur) BACKDROP_BLUR else 0.dp,
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
            backdropBlur = if (Fuse.quality.blur) BACKDROP_BLUR else 0.dp,
            fallback = fallback,
        )
    } else {
        Artwork(model = direct ?: art.grid, modifier = modifier, fallback = fallback)
    }
}

/**
 * A game tile: its square box art, or its poster when Game art is set to posters (sized by
 * [tileSize]). The tile of the Grid layout, most Home shelves and Cartridge's downloads.
 *
 * Art-less games get generated art that carries the platform tag (outside a system's own page);
 * real art speaks for itself. State marks sit in the top corner ([GameMarks]).
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
    val corner = style.cornerFraction()
    val shape = remember(corner) { SquircleShape.fraction(corner) }
    val dims = style.tileSize(size)
    Tile(
        selected = selected,
        glow = accent,
        modifier = modifier.size(dims),
        shape = shape,
        cornerFraction = corner,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        val art = Modifier.fillMaxSize().alpha(if (card.missing) MISSING_ART_ALPHA else 1f)
        val label = card.platformShort.takeIf { LocalTileShowsSystem.current }
        val border = LocalTileBorders.current.of(card.platformId)
        // A border that names the system takes the tag; generated art then leaves it out.
        val artLabel = label.takeUnless { border.carriesTag }
        if (poster) {
            PosterGameArt(card.art, art, fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.BOX, label = artLabel) })
        } else {
            SquareGameArt(card.art, art, fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.ICON, label = artLabel) })
        }
        val inset = markInset(minOf(dims.width, dims.height), corner)
        TileBorder(border, accent, corner, label, inset, badgeAt = if (poster) Alignment.TopStart else Alignment.BottomStart)
        // On posters the tag shares the top edge with the marks.
        GameMarks(card, Modifier.align(Alignment.TopEnd), inset = inset, reserve = if (poster && label != null) tagRoom(label, dims.width) else 0.dp)
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
    val corner = coverCornerFraction()
    val shape = remember(corner) { SquircleShape.fraction(corner) }
    val label = card.platformShort.takeIf { LocalTileShowsSystem.current }
    val border = LocalTileBorders.current.of(card.platformId)
    val artLabel = label.takeUnless { border.carriesTag }
    Tile(
        selected = selected,
        glow = accent,
        cornerFraction = corner,
        shape = shape,
        modifier = modifier.width(width).aspectRatio(aspect),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Artwork(
            model = card.art.boxart ?: card.art.grid ?: card.art.square ?: card.art.icon,
            modifier = Modifier.fillMaxSize().alpha(if (card.missing) MISSING_ART_ALPHA else 1f),
            fallback = { GeneratedArt(card.title, accent, slot = ArtSlot.BOX, label = artLabel) },
        )
        val inset = markInset(width, corner)
        TileBorder(border, accent, corner, label, inset, badgeAt = Alignment.TopStart)
        GameMarks(card, Modifier.align(Alignment.TopEnd), inset = inset, reserve = if (label != null) tagRoom(label, width) else 0.dp)
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
    val c = Fuse.colors
    val accent = card.accent.toColor()
    val corner = Fuse.geometry.tileCornerFraction * WIDE_CORNER
    val shape = remember(corner) { SquircleShape.fraction(corner) }
    Tile(
        selected = selected,
        glow = accent,
        cornerFraction = corner,
        shape = shape,
        modifier = modifier.size(width = height * WIDE_ASPECT, height = height),
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
        // The art darkens toward the bottom, where the title sits, so it reads on any picture.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to c.artScrim)))
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
                FText(card.title, Fuse.type.titleSmall, color = c.onArt, maxLines = 2)
            }
            if (caption != null) FText(caption, Fuse.type.caption, color = c.onArtMuted, maxLines = 1)
        }
        GameMarks(card, Modifier.align(Alignment.TopEnd), inset = markInset(height, corner))
    }
}

/**
 * A placeholder in the shape of a game tile, for a shelf or grid whose games are still on their way:
 * the tile's own outline in the skeleton colour, with the calm shared shimmer passing over it. The
 * shimmer rests under Reduced and Minimal motion and in Low Power Mode, leaving a still outline.
 */
@Composable
fun GameTileSkeleton(modifier: Modifier = Modifier, cornerFraction: Float = Fuse.geometry.tileCornerFraction) {
    val shape = remember(cornerFraction) { SquircleShape.fraction(cornerFraction) }
    Box(modifier.skeleton(shape))
}

// ------------------------------------------------------------------------------------- marks

/**
 * One state a game can be in that its tile shows as a mark: its [icon], what it means in [words]
 * (for screen readers and the stage's tag), and whether the stage names it ([onStage]; a favourite's
 * heart is plain enough on its tile).
 */
@Immutable
internal data class GameMark(
    val icon: ImageVector,
    val words: String,
    val tint: MarkTint,
    val count: Int? = null,
    val onStage: Boolean = true,
)

/** How a mark is coloured: as text on art, or as a warning. */
internal enum class MarkTint { PLAIN, WARNING }

/**
 * What a game's marks are, in order of importance: its file is missing, an update or DLC waits, it
 * spans several discs, it is a favourite. Tiles, list rows and the stage use the same icons, so a
 * mark means the same thing everywhere.
 */
internal fun GameCard.marks(favourite: Boolean = true): List<GameMark> = buildList {
    if (missing) add(GameMark(FuseIcons.FileQuestion, "File missing", MarkTint.WARNING))
    if (updates > 0) add(GameMark(FuseIcons.ArrowUp, if (updates == 1) "Update" else "$updates updates", MarkTint.PLAIN))
    if (dlc > 0) add(GameMark(FuseIcons.Puzzle, "$dlc DLC", MarkTint.PLAIN))
    if (discs > 1) add(GameMark(FuseIcons.Disc, "$discs discs", MarkTint.PLAIN, count = discs))
    if (favorite && favourite) add(GameMark(FuseIcons.Heart, "Favourite", MarkTint.PLAIN, onStage = false))
}

/**
 * False in a view of favourites only, where a heart on every tile would say nothing (as a platform
 * tag says nothing inside a system's own page, see [LocalTileShowsSystem]).
 */
val LocalTileShowsFavourite = staticCompositionLocalOf { true }

/**
 * A game's state marks in a tile's top corner, one consistent family: small dark-glass discs lit
 * along their top edge, each holding one icon (a pill with its number for several discs). The most
 * important mark sits in the corner and the rest line up beside it; marks that would not fit the
 * tile's width (less [reserve] at its start, where a platform tag shares the top edge) are left out rather
 * than crowding the art.
 */
@Composable
internal fun GameMarks(card: GameCard, modifier: Modifier = Modifier, inset: Dp = Space.s, reserve: Dp = 0.dp) {
    val marks = card.marks(favourite = LocalTileShowsFavourite.current)
    if (marks.isEmpty()) return
    val gap = Space.xs
    Layout(
        content = { for (m in marks) TileMark(m) },
        modifier = modifier
            .padding(inset)
            .semantics { contentDescription = marks.joinToString(", ") { it.words } },
    ) { measurables, constraints ->
        val room = constraints.maxWidth - reserve.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val gapPx = gap.roundToPx()
        val placeables = measurables.map { it.measure(loose) }
        // Keep marks in order of importance while they fit.
        var used = 0
        val kept = placeables.takeWhile { p ->
            val next = used + (if (used > 0) gapPx else 0) + p.width
            (next <= room).also { if (it) used = next }
        }
        val height = kept.maxOfOrNull { it.height } ?: 0
        layout(used, height) {
            // The most important mark in the corner, the next ones toward the middle.
            var x = used
            for (p in kept) {
                x -= p.width
                p.place(x, (height - p.height) / 2)
                x -= gapPx
            }
        }
    }
}

/** One mark on art: an icon in a dark-glass disc, or a pill with a count. */
@Composable
private fun TileMark(mark: GameMark) {
    val c = Fuse.colors
    val tint = if (mark.tint == MarkTint.WARNING) c.warning else c.onArt
    if (mark.count == null) {
        IconBadge(mark.icon, tint = tint, background = MARK_GLASS, size = Size.badge)
    } else {
        Row(
            Modifier
                .height(Size.badge)
                .clip(PillShape)
                .background(MARK_GLASS)
                .lightEdge(PillShape, MARK_EDGE)
                .padding(start = Space.s - Space.xxs, end = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xxs + Space.hair),
        ) {
            FuseIcon(mark.icon, size = Size.badgeIcon, tint = tint)
            FText(mark.count.toString(), Fuse.type.numericSmall, color = tint, maxLines = 1)
        }
    }
}

/**
 * The same marks inline, for list rows: plain icons in the muted text colour (the warning colour for
 * a missing file), with the disc count beside its icon.
 */
@Composable
internal fun GameMarksInline(card: GameCard, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    val marks = card.marks(favourite = LocalTileShowsFavourite.current)
    if (marks.isEmpty()) return
    val c = Fuse.colors
    Row(
        modifier.semantics { contentDescription = marks.joinToString(", ") { it.words } },
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (m in marks) {
            val tint = when {
                m.tint == MarkTint.WARNING -> c.warning
                emphasised -> c.text
                else -> c.textMuted
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(m.icon, size = Size.iconXS, tint = tint)
                if (m.count != null) {
                    Spacer(Modifier.width(Space.xxs))
                    FText(m.count.toString(), Fuse.type.numericSmall, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/**
 * How far marks sit from a tile's corner: a little more on larger tiles and on strongly rounded
 * corners, so a disc never meets the curve. Matches the inset of the platform tag on generated art,
 * so a cover's tag and its marks share one line.
 */
internal fun markInset(short: Dp, cornerFraction: Float): Dp =
    maxOf(short * TAG_INSET, short * cornerFraction * CORNER_CLEAR).coerceIn(Space.xs, Space.l)

/**
 * Room to keep for a platform tag in the top corner of a cover [width] wide, so marks beside it never
 * touch it: about as wide as the tag generated art (or a border) sets there, with a gap after it.
 * Tags are sized from the art's short side, so this follows them on every tile size.
 */
internal fun tagRoom(label: String, width: Dp): Dp {
    val type = (width * TAG_TYPE).coerceIn(TAG_TYPE_MIN, TAG_TYPE_MAX)
    return type * (TAG_LETTER * label.length + TAG_PADDING) + Space.xs
}

/**
 * A system's short name as a small uppercase tag, the same tag generated art carries: for list rows
 * and anywhere a game's platform is named beside it rather than on its art.
 */
@Composable
fun PlatformTag(text: String, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    val c = Fuse.colors
    Box(
        modifier
            .clip(PillShape)
            .background(c.text.copy(alpha = if (emphasised) TAG_FILL_ON else TAG_FILL))
            .padding(horizontal = Space.s - Space.xxs, vertical = Space.xxs),
    ) {
        FText(text.uppercase(), Fuse.type.overline, color = if (emphasised) c.text else c.textMuted, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------ systems

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
    val c = Fuse.colors
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
        // A soft floor so the logo reads on light brand colours too.
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to c.artScrim.copy(alpha = c.artScrim.alpha * PACK_FLOOR))),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(if (large) Space.xl else Space.m).fillMaxWidth(0.62f),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (card.art.logo != null) {
                Artwork(
                    card.art.logo,
                    Modifier.fillMaxWidth().height((cardHeight * 0.24f).coerceIn(Space.l + Space.xxs, Space.x4 + Space.s)),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 1f,
                    tint = c.onArt,
                    fallback = { FText(card.platform.shortName, Fuse.type.title, color = c.onArt, maxLines = 1) },
                )
            } else {
                FText(card.platform.shortName, if (large) Fuse.type.hero else Fuse.type.title, color = c.onArt, maxLines = 1)
            }
            if (large || cardHeight > ROOMY_CARD) {
                FText(gamesText(card.gameCount), Fuse.type.caption.copy(fontFeatureSettings = "tnum"), color = c.onArtMuted, maxLines = 1)
            }
        }
        SystemWarning(card, Modifier.align(Alignment.TopEnd))
    }
}

/** A small warning mark when a system has no emulator. Firmware is told on the system's own page. */
@Composable
private fun SystemWarning(card: PlatformCard, modifier: Modifier) {
    if (!card.emulatorInstalled) {
        IconBadge(
            FuseIcons.Warning,
            modifier = modifier.padding(Space.s).semantics { contentDescription = "No emulator installed" },
            tint = Fuse.colors.warning,
            background = MARK_GLASS,
            size = Size.badge,
        )
    }
}

/** Generated system art: short name large, maker and count small when the tile has room. */
@Composable
fun SystemGlyph(card: PlatformCard, accent: Color, large: Boolean = false) {
    val c = Fuse.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Small tiles (the Systems grid, Home) show the name alone.
        val roomy = large || maxHeight > ROOMY_CARD
        GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
        Column(
            Modifier.fillMaxSize().padding(if (large) Space.xl else if (roomy) Space.m else Space.s + Space.xs),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            FText(
                if (roomy) card.platform.manufacturer?.uppercase() ?: "" else "",
                Fuse.type.overline,
                color = c.onArtMuted,
                maxLines = 1,
            )
            Column {
                FText(
                    card.platform.shortName,
                    if (large) Fuse.type.hero else if (roomy) Fuse.type.title else Fuse.type.titleSmall,
                    color = c.onArt,
                    maxLines = 1,
                )
                if (roomy) {
                    FText(gamesText(card.gameCount), Fuse.type.caption.copy(fontFeatureSettings = "tnum"), color = c.onArtMuted, maxLines = 1)
                }
            }
        }
        SystemWarning(card, Modifier.align(Alignment.TopEnd))
    }
}

/** "1 game", "12 games". */
internal fun gamesText(count: Int): String = "$count ${if (count == 1) "game" else "games"}"

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

/** A poster's width and height against the square it replaces: about the same area, taller. */
private const val POSTER_WIDTH = 0.8f
private const val POSTER_HEIGHT = 1.2f

/** Art of a game whose file is missing is dimmed, so the tile reads as "not here right now". */
private const val MISSING_ART_ALPHA = 0.45f

/** Posters' corners against the theme's tile corner. */
private const val POSTER_CORNER = 0.62f

/** Portrait covers' corners against the theme's tile corner. */
private const val COVER_CORNER = 0.55f

/** Wide tiles' corners against the theme's tile corner. */
private const val WIDE_CORNER = 0.7f

/** Wide tiles are a little wider than 16:9, so a logo and a caption fit beside each other. */
private const val WIDE_ASPECT = 1.78f

/** Blur of the soft copy behind art drawn whole. */
private val BACKDROP_BLUR = 14.dp

/** The dark glass under marks on art: dark enough for white icons on the brightest cover. */
private val MARK_GLASS = Color.Black.copy(alpha = 0.55f)

/** Strength of the light edge along a mark's top. */
private const val MARK_EDGE = 0.22f

/** Platform tags and marks sit this share of the art's short side in from its edges. */
private const val TAG_INSET = 0.07f


/**
 * How generated art sizes its platform tag: type at this share of the art's short side, within these
 * bounds, each letter (with its tracking) about this many of its size wide, plus the pill's padding.
 */
private const val TAG_TYPE = 0.062f
private val TAG_TYPE_MIN = 8.dp
private val TAG_TYPE_MAX = 11.dp
private const val TAG_LETTER = 0.86f
private const val TAG_PADDING = 1.4f

/** How much of the corner radius a mark keeps clear of, so it never meets the curve. */
private const val CORNER_CLEAR = 0.3f

/** Fill of a platform tag in a row, and of the selected row's. */
private const val TAG_FILL = 0.07f
private const val TAG_FILL_ON = 0.12f

/** System cards taller than this show their maker and game count. */
private val ROOMY_CARD = 120.dp

/** The floor under a pack card's logo, as a share of the art scrim. */
private const val PACK_FLOOR = 0.62f

