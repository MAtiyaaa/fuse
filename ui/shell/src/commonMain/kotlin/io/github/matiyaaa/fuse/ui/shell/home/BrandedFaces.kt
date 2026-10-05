package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.CartridgeBrand
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/**
 * The Cartridge app's own icon where Fuse can show it (an Android app icon model), else null and
 * Fuse's drawn Cartridge mark stands in.
 */
internal val LocalCartridgeIcon = staticCompositionLocalOf<Any?> { null }

// ----------------------------------------------------------------------------- continue playing

/**
 * Continue playing: the games you were playing, most recent first, one at a time. The triggers
 * (L2, R2) or a swipe turn to the one before it; the dots say where you are. Nothing peeks in from
 * the side: each is its own page.
 */
@Composable
internal fun ContinueFace(feed: HomeFeed, face: FaceSize) {
    val games = feed.continuePlaying.take(CONTINUE_PAGES)
    if (games.isEmpty()) {
        FramedEmpty(WidgetKind.CONTINUE_PLAYING, "Games you play show here")
        return
    }
    val compact = face == FaceSize.SMALL || face == FaceSize.TALL
    Carousel(
        count = games.size,
        peek = false,
        header = { dots -> CarouselHeader(FuseIcons.CirclePlay, "Continue playing", dots, compact = compact) },
    ) { i, depth ->
        ContinueSlide(games[i], feed, face, depth, underHeader = if (compact) 44.dp else 64.dp)
    }
}

/**
 * One game to continue. Its own art fills the face,
 * its logo (or, without one, its name set large) sits where the eye lands, with the system it runs
 * on, when it was last played and a clear way back in. The scrim is the game's own colour deepened,
 * strongest only behind the words, so bright art stays bright where nothing sits on it.
 *
 * Each shape is laid out for itself: one cell is a poster with its logo, a strip puts the logo and
 * a Continue button side by side, a column stands the cover over the logo, and larger faces add the
 * cover beside a large logo and the time played.
 */
@Composable
private fun ContinueSlide(game: GameCard, feed: HomeFeed, face: FaceSize, depth: CarouselDepth?, underHeader: Dp = 0.dp) {
    val system = feed.systems.firstOrNull { it.platform.id == game.platformId }
    val accent = game.accent.toColor()
    val deep = lerp(accent, Color.Black, 0.86f)
    val backdrop = game.art.hero ?: game.art.screenshot ?: game.art.grid
    val cover = game.art.boxart ?: game.art.square
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val pad = if (h < 140.dp || w < 200.dp) Space.m else Space.l
        // The room: the game's background art, else its colour with its cover set into it.
        Box(Modifier.fillMaxSize().carouselParallax(depth)) {
            if (backdrop != null) {
                Artwork(
                    backdrop, Modifier.fillMaxSize(),
                    focusX = game.art.heroFocusX, focusY = game.art.heroFocusY,
                    fallback = { AccentField(accent) },
                )
            } else {
                AccentField(accent)
            }
        }
        // Deep colour under the words: up from the bottom, and in from the left on a wide face.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to deep.copy(alpha = 0.42f), 0.22f to deep.copy(alpha = 0.08f), 0.45f to Color.Transparent, 1f to deep.copy(alpha = 0.94f))))
        if (w > h) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to deep.copy(alpha = 0.72f), 0.55f to Color.Transparent)))
        val coverBeside = cover != null && (face == FaceSize.LARGE || (face == FaceSize.SQUARE && backdrop == null)) && w >= 320.dp
        when (face) {
            FaceSize.SMALL -> Column(Modifier.fillMaxSize().padding(pad)) {
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    GameMark(game, Modifier.weight(1f).height((h * 0.34f).coerceIn(22.dp, 56.dp)), Fuse.type.bodyStrong)
                    Spacer(Modifier.width(Space.s))
                    PlayDisc(accent)
                }
            }
            FaceSize.TALL -> Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
                // Room for the name and dots that stay over the top.
                Spacer(Modifier.height(Space.l + Space.m))
                if (cover != null) {
                    Box(Modifier.weight(1f).aspectRatio(Aspect.BOX, matchHeightConstraintsFirst = true).lifted(CoverShape)) {
                        Artwork(cover, Modifier.fillMaxSize(), fallback = { GeneratedArt(game.title, accent, slot = ArtSlot.BOX, showText = false) })
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(Space.m))
                GameMark(game, Modifier.fillMaxWidth().height((h * 0.12f).coerceIn(28.dp, 64.dp)), Fuse.type.titleSmall, center = true)
                Spacer(Modifier.height(Space.xs))
                FText(lastPlayed(game), Fuse.type.caption, color = Fuse.colors.onArtMuted, maxLines = 1, align = TextAlign.Center)
            }
            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                // The name and dots run along the top; the game's system sits just under them.
                Spacer(Modifier.height((underHeader - pad).coerceAtLeast(0.dp)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    PlatformChip(game, system)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    val big = face == FaceSize.LARGE || face == FaceSize.SQUARE
                    Column(Modifier.weight(1f)) {
                        val logoHeight = if (big) (h * 0.26f).coerceIn(44.dp, 150.dp) else (h * 0.36f).coerceIn(28.dp, 84.dp)
                        GameMark(
                            game,
                            Modifier.widthIn(max = if (coverBeside) w * 0.5f else w * 0.66f).fillMaxWidth().height(logoHeight),
                            if (big && h >= 300.dp) Fuse.type.display else Fuse.type.title,
                        )
                        Spacer(Modifier.height(Space.s))
                        FText(continueLine(game), Fuse.type.caption, color = Fuse.colors.onArtMuted, maxLines = 1, fit = true)
                        if (big) {
                            Spacer(Modifier.height(Space.m))
                            ContinuePill(accent)
                        }
                    }
                    if (coverBeside) {
                        Spacer(Modifier.width(Space.l))
                        Box(Modifier.height((h * 0.6f).coerceAtMost(300.dp)).aspectRatio(Aspect.BOX).lifted(CoverShape)) {
                            Artwork(cover, Modifier.fillMaxSize(), fallback = { GeneratedArt(game.title, accent, slot = ArtSlot.BOX, showText = false) })
                        }
                    } else if (face == FaceSize.WIDE) {
                        // A strip has its button beside the logo, where there is room for it.
                        Spacer(Modifier.width(Space.m))
                        ContinuePill(accent)
                    }
                }
            }
        }
    }
}

/** A game's colour as a lit field, for games without background art. */
@Composable
internal fun AccentField(accent: Color) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                0f to lerp(accent, Color.White, 0.08f),
                0.7f to lerp(accent, Color.Black, 0.55f),
                1f to lerp(accent, Color.Black, 0.78f),
                center = Offset(0f, 0f),
                radius = 1_400f,
            ),
        ),
    )
}

/**
 * The game's logo, white on its art, anchored bottom left (or centred); without one, its name set
 * in [style] over a short bar in the game's colour, so the face never falls back to a bare caption.
 */
@Composable
internal fun GameMark(game: GameCard, modifier: Modifier, style: androidx.compose.ui.text.TextStyle, center: Boolean = false) {
    val c = Fuse.colors
    val name: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
            FText(game.title, style, color = c.onArt, maxLines = 2, align = if (center) TextAlign.Center else null)
            Spacer(Modifier.height(Space.xs))
            Box(Modifier.width(Space.xl).height(3.dp).clip(PillShape).background(game.accent.toColor().let { lerp(it, Color.White, 0.25f) }))
        }
    }
    Box(modifier) {
        val logo = game.art.logo
        if (logo != null) {
            Artwork(
                logo, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                focusX = if (center) 0.5f else 0f,
                focusY = 1f,
                loading = false,
                fallback = name,
            )
        } else {
            name()
        }
    }
}

/** The system a game runs on, as a small frosted chip with the system's logo (or its short name). */
@Composable
internal fun PlatformChip(game: GameCard, system: PlatformCard?) {
    val c = Fuse.colors
    Box(
        Modifier
            .clip(PillShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .border(1.dp, c.onArt.copy(alpha = 0.14f), PillShape)
            .padding(horizontal = Space.m, vertical = Space.xs + Space.xxs),
        contentAlignment = Alignment.Center,
    ) {
        val logo = system?.art?.logo
        val short: @Composable () -> Unit = { FText(game.platformShort, Fuse.type.overline, color = c.onArt, maxLines = 1, fit = true) }
        if (logo != null) {
            Artwork(logo, Modifier.height(14.dp).widthIn(max = 84.dp).width(84.dp), contentScale = ContentScale.Fit, tint = c.onArt, loading = false, fallback = short)
        } else {
            short()
        }
    }
}

/** A solid accent pill: the button's glyph and "Continue". */
@Composable
private fun ContinuePill(accent: Color) {
    val c = Fuse.colors
    Row(
        Modifier
            .graphicsLayer {
                shape = PillShape
                clip = true
                shadowElevation = 6.dp.toPx()
            }
            .background(c.onArt)
            .padding(start = Space.s, end = Space.l, top = Space.xs + Space.xxs, bottom = Space.xs + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButtonGlyph(HintButton.CONFIRM, size = 20.dp, color = lerp(accent, Color.Black, 0.6f))
        Spacer(Modifier.width(Space.s))
        FText("Continue", Fuse.type.label, color = lerp(accent, Color.Black, 0.75f), maxLines = 1, fit = true)
    }
}

/** A round play button for a one-cell face. */
@Composable
internal fun PlayDisc(accent: Color) {
    val c = Fuse.colors
    Box(
        Modifier.size(Size.chip).graphicsLayer {
            shape = CircleShape
            clip = true
            shadowElevation = 6.dp.toPx()
        }.background(c.onArt),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(FuseIcons.Play, size = Size.iconS, tint = lerp(accent, Color.Black, 0.7f))
    }
}

private fun lastPlayed(g: GameCard): String = g.lastPlayedAt?.let { "Played ${agoText(it)}" } ?: g.platformShort

/** "Played 2 h ago · 14 h in all", or as much of it as is known. */
private fun continueLine(g: GameCard): String = listOfNotNull(
    g.lastPlayedAt?.let { "Played ${agoText(it)}" },
    g.playSeconds.takeIf { it >= 60 }?.let { "${playtimeText(it)} in all" },
).joinToString("  ·  ").ifEmpty { g.platformShort }

internal val CoverShape = SquircleShape.fraction(0.08f)

/** How many games Continue Playing turns through. */
private const val CONTINUE_PAGES = 10

// ---------------------------------------------------------------------------------- shared marks

/**
 * A widget's mark: [icon] in a lit disc of [tint], with a soft glow behind it; [stacked] sets two
 * fainter discs behind, like a stack. The way a non-game widget shows what it is without words.
 */
@Composable
internal fun Emblem(icon: ImageVector, tint: Color, size: Dp, stacked: Boolean = false) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.5f).background(Brush.radialGradient(0f to tint.copy(alpha = 0.32f), 0.65f to tint.copy(alpha = 0.06f), 1f to Color.Transparent)))
        if (stacked) {
            Box(Modifier.size(size * 0.86f).offset(x = size * 0.1f, y = -size * 0.08f).clip(SquircleShape.fraction(0.32f)).background(tint.copy(alpha = 0.22f)))
        }
        Box(
            Modifier.size(size * 0.86f)
                .graphicsLayer {
                    shape = SquircleShape.fraction(0.32f)
                    clip = true
                    shadowElevation = 8.dp.toPx()
                    spotShadowColor = tint
                }
                .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.12f), lerp(tint, Color.Black, 0.28f)))),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(icon, size = size * 0.44f, tint = Color.White)
        }
    }
}

/**
 * Cartridge's icon: its own (the installed app's) where Fuse can read it, else drawn as Cartridge
 * draws it, its orange mark on a near-black squircle, with an orange glow beneath.
 */
@Composable
internal fun CartridgeEmblem(size: Dp) {
    val icon = LocalCartridgeIcon.current
    val mark: @Composable () -> Unit = { CartridgeIcon(size) }
    if (icon == null) {
        mark()
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Artwork(icon, Modifier.size(size * 0.86f), contentScale = ContentScale.Fit, loading = false, fallback = mark)
        }
    }
}

/** Cartridge's launcher icon, drawn: [CartridgeBrand.ORANGE] mark on [CartridgeBrand.INK]. */
@Composable
internal fun CartridgeIcon(size: Dp) {
    val orange = CartridgeBrand.ORANGE.toColor()
    val shape = remember { SquircleShape.fraction(0.3f) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.4f).background(Brush.radialGradient(0f to orange.copy(alpha = 0.26f), 0.6f to orange.copy(alpha = 0.05f), 1f to Color.Transparent)))
        Box(
            Modifier.size(size * 0.86f)
                .graphicsLayer {
                    this.shape = shape
                    clip = true
                    shadowElevation = 8.dp.toPx()
                    spotShadowColor = orange
                }
                .background(CartridgeBrand.INK.toColor())
                .border(1.dp, Color.White.copy(alpha = 0.07f), shape),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(FuseMarks.Cartridge, size = size * 0.52f, tint = orange)
        }
    }
}

/** An empty widget: its frame, mark and what would show. */
@Composable
private fun FramedEmpty(kind: WidgetKind, note: String) {
    Framed(kind, null, null) { EmptyFace(widgetIcon(kind), kind.title(), note) }
}

/** Favourites' heart, and Cartridge's mark colour. */
private const val FAVORITE_RED = 0xFFE5486EL
internal const val CARTRIDGE_TINT = CartridgeBrand.ORANGE

