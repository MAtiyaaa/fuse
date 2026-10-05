package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The five shapes a widget's face is designed for, from its size on the board: one cell, a strip
 * (wide and one cell tall), a column (one cell wide and taller), a square of four, and anything
 * larger. Each face lays out what it shows for its shape, and the room it actually has decides
 * the details (how many covers, rows or chips fit).
 */
internal enum class FaceSize {
    SMALL, WIDE, TALL, SQUARE, LARGE;

    companion object {
        fun of(size: BoardSize): FaceSize = when {
            size.width == 1 && size.height == 1 -> SMALL
            size.height == 1 -> WIDE
            size.width == 1 -> TALL
            size.width == 2 && size.height == 2 -> SQUARE
            else -> LARGE
        }
    }
}

/**
 * A widget's face on the board at [size]. Lists of games are shown through their art; everything
 * else sits on the widgets' raised surface, lit in its own colour, under a small header.
 */
@Composable
internal fun BoardFace(kind: WidgetKind, size: BoardSize, feed: HomeFeed, cartridge: CartridgeStatus, clock24h: Boolean) {
    val face = FaceSize.of(size)
    when (kind) {
        WidgetKind.CONTINUE_PLAYING -> ContinueFace(feed, face)
        WidgetKind.FAVORITES, WidgetKind.RECENTLY_PLAYED, WidgetKind.RECENTLY_ADDED, WidgetKind.PINNED_GAMES,
        -> GameCarousel(kind, boardGames(kind, feed), feed, face)
        WidgetKind.CURRENT_GAME -> GamesFace(kind, listOfNotNull(feed.playtime.currentGame), face) { g ->
            feed.playtime.currentSince?.let { "Started ${agoText(it)}" } ?: g.platformShort
        }
        WidgetKind.SYSTEMS -> SystemsCarousel(feed, face)
        WidgetKind.PINNED_APPS -> AppsFace(feed)
        WidgetKind.COLLECTIONS -> CollectionsCarousel(feed, face)
        in MediaKinds -> MediaCarousel(kind, mediaItems(kind, feed), face)
        WidgetKind.SYNC_STATUS -> SyncStatusFace(face)
        WidgetKind.SYNC_DEVICES -> SyncDevicesFace(face)
        else -> Framed(kind, feed, cartridge) {
            when (kind) {
                WidgetKind.PLAYTIME_WEEK -> WeekFace(feed, face)
                WidgetKind.PLAYTIME_TOTAL -> TotalFace(feed, face)
                WidgetKind.MOST_PLAYED -> MostPlayedFace(feed, face)
                WidgetKind.RECENT_ACHIEVEMENT -> LatestAchievementFace(feed, face)
                WidgetKind.RECENT_ACHIEVEMENTS -> AchievementsFace(feed, face)
                WidgetKind.ACHIEVEMENT_PROGRESS -> ProgressFace(feed, face)
                WidgetKind.RECENTLY_MASTERED -> MasteredFace(feed, face)
                WidgetKind.CARTRIDGE_DOWNLOADS -> CartridgeFace(cartridge, face)
                WidgetKind.STORAGE -> StorageFaceNew(feed, face)
                WidgetKind.CLOCK -> ClockFaceNew(clock24h, face)
                else -> WidgetHeader(widgetIcon(kind), kind.title())
            }
        }
    }
}

/** The games a game widget shows, in its order. */
internal fun boardGames(kind: WidgetKind, feed: HomeFeed): List<GameCard> = when (kind) {
    WidgetKind.CONTINUE_PLAYING -> feed.continuePlaying
    WidgetKind.RECENTLY_PLAYED -> feed.recentlyPlayed
    WidgetKind.FAVORITES -> feed.favorites
    WidgetKind.RECENTLY_ADDED -> feed.recentlyAdded
    WidgetKind.PINNED_GAMES -> feed.pinnedGames
    WidgetKind.MOST_PLAYED -> feed.mostPlayed
    WidgetKind.CURRENT_GAME -> listOfNotNull(feed.playtime.currentGame)
    else -> emptyList()
}

// ------------------------------------------------------------------------------------- games

/**
 * A list of games told through its first game's art: the art fills the face, the widget's name,
 * the game's title and a line about it sit at the bottom left over a scrim, and the next games
 * stand as covers along the bottom, or down the right side of a wide face, as many as fit. One
 * cell shows the first game alone, like a poster.
 */
@Composable
private fun GamesFace(kind: WidgetKind, games: List<GameCard>, face: FaceSize, caption: (GameCard) -> String) {
    val c = Fuse.colors
    val first = games.firstOrNull()
    if (first == null) {
        Framed(kind, null, null) { EmptyFace(widgetIcon(kind), kind.title(), emptyGamesNote(kind)) }
        return
    }
    val corner = Fuse.geometry.tileCornerFraction * COVER_CORNER
    val shape = remember(corner) { SquircleShape.fraction(corner) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= maxHeight * WIDE_COVERS
        val pad = if (maxHeight < 140.dp) Space.m else Space.l
        val rest = if (face == FaceSize.SMALL) emptyList() else games.drop(1)
        // Covers of the next games: down the right of a wide face, else along the bottom.
        val coverHeight = when {
            rest.isEmpty() -> 0.dp
            wide -> (maxHeight - pad * 2).coerceAtMost(COVER_MAX)
            else -> (maxHeight * 0.3f).coerceIn(56.dp, COVER_MAX)
        }
        val coverWidth = coverHeight * Aspect.BOX
        val room = if (wide) maxWidth * 0.48f else maxWidth - pad * 2
        val fit = if (coverWidth <= 0.dp) 0 else ((room + Space.s) / (coverWidth + Space.s)).toInt().coerceIn(0, minOf(rest.size, MAX_COVERS))
        val withCaption = maxHeight >= 120.dp
        val titleStyle = when {
            maxHeight >= 420.dp && maxWidth >= 560.dp -> Fuse.type.display
            maxHeight >= 300.dp && maxWidth >= 360.dp -> Fuse.type.title
            maxHeight >= 160.dp -> Fuse.type.titleSmall
            else -> Fuse.type.bodyStrong
        }
        Artwork(
            first.art.hero ?: first.art.grid ?: first.art.screenshot ?: first.art.boxart,
            Modifier.fillMaxSize(),
            focusX = first.art.heroFocusX,
            focusY = first.art.heroFocusY,
            fallback = { GeneratedArt(first.title, first.accent.toColor(), slot = ArtSlot.WIDE, showText = false) },
        )
        // A scrim under the words (from the bottom, and from the left on a wide face).
        val scrim = c.artScrim
        Box(
            Modifier.fillMaxSize().background(
                if (wide) {
                    Brush.horizontalGradient(0f to scrim, 0.62f to scrim.copy(alpha = scrim.alpha * 0.25f), 1f to scrim.copy(alpha = scrim.alpha * 0.5f))
                } else {
                    Brush.verticalGradient(0f to Color.Transparent, 0.4f to scrim.copy(alpha = scrim.alpha * 0.2f), 1f to scrim)
                },
            ),
        )
        Column(Modifier.fillMaxSize().padding(pad)) {
            ArtLabel(kind)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    FText(first.title, titleStyle, color = c.onArt, maxLines = 2)
                    if (withCaption) {
                        Spacer(Modifier.height(Space.xxs))
                        FText(caption(first), Fuse.type.caption, color = c.onArtMuted, maxLines = 1, fit = true)
                    }
                }
                if (wide && fit > 0) {
                    Spacer(Modifier.width(Space.l))
                    Covers(rest.take(fit), coverHeight, shape)
                }
            }
            if (!wide && fit > 0) {
                Spacer(Modifier.height(Space.m))
                Covers(rest.take(fit), coverHeight, shape)
            }
        }
    }
}

/** Covers standing in a row, each a small lit object over a contact shadow. */
@Composable
private fun Covers(games: List<GameCard>, height: Dp, shape: androidx.compose.ui.graphics.Shape) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
        for (g in games) {
            Box(Modifier.height(height).aspectRatio(Aspect.BOX).lifted(shape)) {
                Artwork(g.art.boxart ?: g.art.grid ?: g.art.square ?: g.art.icon, Modifier.fillMaxSize(), fallback = {
                    GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.BOX, label = g.platformShort, showText = false)
                })
            }
        }
    }
}

/** A widget's name over art: its icon and name in the overline style, in the colours that read on art. */
@Composable
private fun ArtLabel(kind: WidgetKind) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(widgetIcon(kind), size = Size.iconXS, tint = c.onArtMuted)
        Spacer(Modifier.width(Space.s - Space.xxs))
        FText(widgetLabel(kind).uppercase(), Fuse.type.overline, color = c.onArtMuted, maxLines = 1, fit = true)
    }
}

private fun emptyGamesNote(kind: WidgetKind): String = when (kind) {
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED -> "Games you play show here"
    WidgetKind.FAVORITES -> "Add favourites from a game's options"
    WidgetKind.PINNED_GAMES -> "Pin games from their options"
    WidgetKind.RECENTLY_ADDED -> "New games show here after a scan"
    WidgetKind.CURRENT_GAME -> "Nothing is running"
    else -> "Nothing here yet"
}

// ------------------------------------------------------------------- systems, apps, collections

/** Pinned apps as icons, as many as fit. */
@Composable
private fun AppsFace(feed: HomeFeed) {
    Framed(WidgetKind.PINNED_APPS, feed, null) {
        val apps = feed.pinnedApps
        WidgetHeader(FuseIcons.AppWindow, WidgetKind.PINNED_APPS.title(), trailing = apps.size.takeIf { it > 0 }?.toString())
        if (apps.isEmpty()) {
            Spacer(Modifier.weight(1f))
            FText("Pin apps from the Apps tab", Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 2)
            return@Framed
        }
        Spacer(Modifier.height(Space.s))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The largest icons that still fit every app (or as many as fit at the smallest size),
            // named underneath when there is room.
            val gap = Space.m
            val label = 22.dp
            fun fits(size: androidx.compose.ui.unit.Dp, named: Boolean): Pair<Int, Int> {
                val columns = ((maxWidth + gap) / (size + gap)).toInt().coerceAtLeast(1)
                val rows = ((maxHeight + gap) / (size + (if (named) label else 0.dp) + gap)).toInt().coerceAtLeast(0)
                return columns to rows
            }
            val choice = APP_SIZES.firstNotNullOfOrNull { size ->
                val named = size >= 64.dp
                val (columns, rows) = fits(size, named)
                if (columns * rows >= apps.size) Triple(size, named, columns) else null
            } ?: Triple(APP_SIZES.last(), false, fits(APP_SIZES.last(), false).first)
            val (icon, named, columns) = choice
            val rows = fits(icon, named).second.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.Start) {
                apps.take(columns * rows).chunked(columns).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { a ->
                            Column(Modifier.width(icon), horizontalAlignment = Alignment.CenterHorizontally) {
                                Artwork(a.icon, Modifier.size(icon), contentScale = ContentScale.Fit)
                                if (named) {
                                    Spacer(Modifier.height(Space.xs))
                                    FText(a.entry.customTitle ?: a.entry.label, Fuse.type.caption, color = Fuse.colors.textMuted, align = TextAlign.Center, maxLines = 1, fit = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- the surface

/**
 * The raised surface the non-art faces sit on, lit in the widget's colour, with the same padding
 * as every face and its "nothing yet" note when there is nothing to show. [feed] and [cartridge]
 * are null for faces that handle their own empty state.
 */
@Composable
internal fun Framed(
    kind: WidgetKind,
    feed: HomeFeed?,
    cartridge: CartridgeStatus?,
    tint: androidx.compose.ui.graphics.Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fuse.colors
    val tint = tint ?: if (feed != null && cartridge != null) widgetTint(kind, feed, cartridge) else c.text
    BoxWithConstraints(Modifier.fillMaxSize().widgetSurface(c.surfaceRaised, tint.copy(alpha = if (c.isDark) WASH_DARK else WASH_LIGHT))) {
        val room = widgetRoom(maxWidth, maxHeight)
        CompositionLocalProvider(LocalWidgetRoom provides room) {
            Column(Modifier.fillMaxSize().padding(if (room.compact) Space.m else Space.l)) {
                val note = if (feed != null) widgetEmptyNote(kind, feed) else null
                if (note != null) EmptyFace(widgetIcon(kind), widgetLabel(kind), note) else content()
            }
        }
    }
}

// --------------------------------------------------------------------------------- playtime

@Composable
private fun ColumnScope.WeekFace(feed: HomeFeed, face: FaceSize) {
    val p = feed.playtime
    val days = p.lastSevenDays.ifEmpty { List(7) { 0L } }
    val week = playtimeText(p.weekSeconds)
    val today = "${playtimeText(days.last())} today"
    WidgetHeader(FuseIcons.Calendar, WidgetKind.PLAYTIME_WEEK.title())
    when (face) {
        FaceSize.SMALL -> {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    WidgetValue(week)
                    if (!LocalWidgetRoom.current.tiny) WidgetCaption(today)
                }
                // The week in miniature, where the cell has the width for it.
                if (!LocalWidgetRoom.current.small) {
                    Spacer(Modifier.width(Space.m))
                    WeekBars(days, Modifier.width(72.dp).height(40.dp), letters = false)
                }
            }
        }
        FaceSize.WIDE -> {
            Spacer(Modifier.height(Space.s))
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    WidgetValue(week)
                    if (!LocalWidgetRoom.current.compact) WidgetCaption("${playtimeText(p.totalSeconds)} in total")
                }
                Spacer(Modifier.width(Space.l))
                WeekBars(days, Modifier.weight(1f).fillMaxHeight(), letters = !LocalWidgetRoom.current.compact)
            }
        }
        FaceSize.TALL, FaceSize.SQUARE -> {
            Spacer(Modifier.height(Space.xs))
            WidgetValue(week)
            WidgetCaption(today)
            Spacer(Modifier.height(Space.m))
            WeekBars(days, Modifier.fillMaxWidth().weight(1f), letters = true)
        }
        FaceSize.LARGE -> {
            Spacer(Modifier.height(Space.xs))
            WidgetValue(week)
            WidgetCaption("$today  ·  ${playtimeText(p.totalSeconds)} in total")
            Spacer(Modifier.height(Space.l))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Side by side on a wide face; on a tall one the week sits over the games.
                if (maxWidth >= maxHeight || feed.mostPlayed.isEmpty()) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                        WeekBars(days, Modifier.weight(1.4f).fillMaxHeight(), letters = true)
                        if (feed.mostPlayed.isNotEmpty()) TopGames(feed.mostPlayed, Modifier.weight(1f).fillMaxHeight(), title = "Most played")
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                        WeekBars(days, Modifier.weight(1f).fillMaxWidth(), letters = true)
                        TopGames(feed.mostPlayed, Modifier.weight(0.8f).fillMaxWidth(), title = "Most played")
                    }
                }
            }
        }
    }
}

/** The play-time marks people count toward, in hours. */
private val MILESTONES = listOf(1L, 5L, 10L, 25L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L)

/**
 * All the time Fuse has seen you play, set large, and how near it is to the next round mark (a
 * ring on larger faces, a bar on a strip): today and this week beside it, and on the largest, the
 * games it went to.
 */
@Composable
private fun ColumnScope.TotalFace(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val p = feed.playtime
    val total = playtimeText(p.totalSeconds)
    val hours = p.totalSeconds / 3_600f
    val next = MILESTONES.firstOrNull { it > hours } ?: (((hours / 10_000).toLong() + 1) * 10_000)
    val previous = MILESTONES.lastOrNull { it <= hours } ?: 0L
    val toward = ((hours - previous) / (next - previous)).coerceIn(0f, 1f)
    val left = "${playtimeText(((next - hours) * 3_600).toLong().coerceAtLeast(60))} to $next h"
    val todaySeconds = p.lastSevenDays.lastOrNull() ?: 0L
    WidgetHeader(FuseIcons.Hourglass, widgetLabel(WidgetKind.PLAYTIME_TOTAL), short = "Total")
    when (face) {
        FaceSize.SMALL -> {
            Spacer(Modifier.weight(1f))
            WidgetValue(total)
            Spacer(Modifier.height(Space.xs))
            ProgressBar(toward, Modifier.fillMaxWidth(), color = c.accent)
            if (!LocalWidgetRoom.current.tiny) {
                Spacer(Modifier.height(Space.xs))
                WidgetCaption(left)
            }
        }
        FaceSize.WIDE -> {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    WidgetValue(total)
                    WidgetCaption(left)
                }
                Spacer(Modifier.width(Space.l))
                StatPair(playtimeText(todaySeconds), "today")
                Spacer(Modifier.width(Space.l))
                StatPair(playtimeText(p.weekSeconds), "this week")
            }
            Spacer(Modifier.height(Space.s))
            ProgressBar(toward, Modifier.fillMaxWidth(), color = c.accent)
        }
        else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = maxWidth > maxHeight * 1.2f
            val showGames = face == FaceSize.LARGE && feed.mostPlayed.isNotEmpty()
            val ring = (if (side) maxHeight * 0.8f else min(maxWidth, maxHeight * (if (showGames) 0.42f else 0.62f)) * 0.9f).coerceIn(96.dp, 240.dp)
            val big = ring >= 180.dp
            val dial: @Composable () -> Unit = {
                ProgressRing(toward, size = ring, stroke = Size.track * if (big) 3 else 2, color = c.accent) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FText(total, (if (big) Fuse.type.display else Fuse.type.title).tabular(), maxLines = 1)
                        FText(if (big) "of play" else "played", Fuse.type.caption, color = c.textMuted, maxLines = 1, fit = true)
                    }
                }
            }
            val facts: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    StatPair(playtimeText(todaySeconds), "today")
                    StatPair(playtimeText(p.weekSeconds), "this week")
                    StatPair(left, "to the next mark", small = true)
                }
            }
            if (side) {
                Row(
                    Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                    // Without the games beside them, the ring and its facts stand in the middle.
                    horizontalArrangement = Arrangement.spacedBy(Space.xl, if (showGames) Alignment.Start else Alignment.CenterHorizontally),
                ) {
                    dial()
                    facts()
                    if (showGames) TopGames(feed.mostPlayed, Modifier.weight(1f).fillMaxHeight(), title = "Most played")
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Space.s))
                    dial()
                    Spacer(Modifier.height(Space.m))
                    WidgetCaption("$left  ·  ${playtimeText(p.weekSeconds)} this week")
                    if (showGames) {
                        Spacer(Modifier.height(Space.l))
                        TopGames(feed.mostPlayed, Modifier.weight(1f).fillMaxWidth(), title = "Most played")
                    }
                }
            }
        }
    }
}

/** A value over its label, as a pair in a row of facts. */
@Composable
private fun StatPair(value: String, label: String, small: Boolean = false) {
    Column {
        FText(value, (if (small) Fuse.type.label else Fuse.type.titleSmall).tabular(), maxLines = 1)
        FText(label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1, fit = true)
    }
}

@Composable
private fun ColumnScope.MostPlayedFace(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val top = feed.mostPlayed
    WidgetHeader(FuseIcons.TrendingUp, WidgetKind.MOST_PLAYED.title())
    if (face == FaceSize.SMALL) {
        // The leader: its art, its name and its time, with a crown for first place.
        val g = top.first()
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.16f))
            Box {
                io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt(
                    g.art, Modifier.size(Size.thumb).clip(shape),
                    fallback = { GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.ICON) },
                )
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(g.title, Fuse.type.label, maxLines = 2)
                FText(playtimeText(g.playSeconds), Fuse.type.numericSmall, color = c.accent, maxLines = 1, fit = true)
            }
        }
        return
    }
    Spacer(Modifier.height(Space.s))
    TopGames(top, Modifier.weight(1f).fillMaxWidth(), art = face == FaceSize.LARGE || face == FaceSize.SQUARE)
}

/**
 * The games played most, each with its time over a bar against the leader's (the leader in the
 * accent), as many as fit; with [art], each beside its square art.
 */
@Composable
private fun TopGames(games: List<GameCard>, modifier: Modifier, title: String? = null, art: Boolean = false) {
    val c = Fuse.colors
    BoxWithConstraints(modifier) {
        val head = if (title != null) 22.dp else 0.dp
        val rowH = if (art) Size.thumb + Space.xs else 34.dp
        val count = ((maxHeight - head + Space.s) / (rowH + Space.s)).toInt().coerceIn(1, games.size)
        val shown = games.take(count)
        val max = (shown.maxOfOrNull { it.playSeconds } ?: 1L).coerceAtLeast(1L)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (title != null) FText(title.uppercase(), Fuse.type.overline, color = c.textFaint, maxLines = 1, fit = true)
            shown.forEachIndexed { i, g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (art) {
                        val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.14f))
                        io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt(
                            g.art, Modifier.size(Size.thumb).clip(shape),
                            fallback = { GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.ICON) },
                        )
                        Spacer(Modifier.width(Space.m))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FText(g.title, Fuse.type.label, color = if (i == 0) c.text else c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(Space.s))
                            FText(playtimeText(g.playSeconds), Fuse.type.numericSmall, color = c.textMuted, maxLines = 1, fit = true)
                        }
                        ProgressBar(g.playSeconds.toFloat() / max, Modifier.fillMaxWidth(), color = if (i == 0) c.accent else c.textMuted.copy(alpha = 0.6f), height = Space.xs)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- achievements

@Composable
private fun ColumnScope.LatestAchievementFace(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val a = feed.achievements?.recent?.firstOrNull() ?: return
    val label = if (a.hardcore) "Unlocked · Hardcore" else "Unlocked"
    when (face) {
        FaceSize.SMALL, FaceSize.WIDE -> {
            WidgetHeader(FuseIcons.Trophy, label, c.warning, short = "Unlocked")
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(a.achievement.badgeUrl, if (LocalWidgetRoom.current.compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(a.achievement.title, Fuse.type.bodyStrong, maxLines = if (face == FaceSize.WIDE) 2 else 1)
                    WidgetCaption(if (face == FaceSize.WIDE) "${a.gameTitle}  ·  ${a.achievement.points} points" else "${a.achievement.points} points")
                }
            }
        }
        else -> {
            WidgetHeader(FuseIcons.Trophy, label, c.warning, short = "Unlocked")
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val badge = (min(maxWidth, maxHeight) * 0.5f).coerceIn(Size.thumbL, 132.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Badge(a.achievement.badgeUrl, badge, glow = true)
                    Spacer(Modifier.height(Space.m))
                    FText(a.achievement.title, Fuse.type.titleSmall, align = TextAlign.Center, maxLines = 2)
                    Spacer(Modifier.height(Space.xxs))
                    FText("${a.gameTitle}  ·  ${a.achievement.points} points", Fuse.type.caption, color = c.textMuted, align = TextAlign.Center, maxLines = 1, fit = true)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AchievementsFace(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val list = feed.achievements?.recent.orEmpty()
    WidgetHeader(FuseIcons.Trophy, WidgetKind.RECENT_ACHIEVEMENTS.title(), c.warning, trailing = list.size.takeIf { it > 0 && face != FaceSize.SMALL }?.toString(), short = "Recent")
    Spacer(Modifier.height(Space.s))
    when (face) {
        FaceSize.SMALL -> {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(list.first().achievement.badgeUrl, Size.thumb)
                Spacer(Modifier.width(Space.m))
                Column {
                    FText("${list.size}", Fuse.type.titleSmall.tabular(), maxLines = 1, fit = true)
                    WidgetCaption(if (list.size == 1) "unlocked" else "unlocked lately")
                }
            }
        }
        FaceSize.TALL -> {
            // Down the column: each badge beside its title.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val rows = ((maxHeight + Space.s) / (Size.thumb + Space.s)).toInt().coerceIn(1, list.size)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (a in list.take(rows)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(a.achievement.badgeUrl, Size.thumb)
                            Spacer(Modifier.width(Space.s))
                            Column(Modifier.weight(1f)) {
                                FText(a.achievement.title, Fuse.type.label, maxLines = 1, fit = true)
                                FText(a.gameTitle, Fuse.type.caption, color = c.textMuted, maxLines = 1, fit = true)
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // A wall of badges, each over its title when there is room: on a strip one row, on
            // larger faces the largest badges that still show them all, centred.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = if (face == FaceSize.WIDE) Alignment.BottomStart else Alignment.Center) {
                val titled = face != FaceSize.WIDE || maxHeight >= 110.dp
                val title = if (titled) 22.dp else 0.dp
                fun grid(badge: Dp): Triple<Int, Int, Dp> {
                    val cellW = badge + Space.l
                    val columns = ((maxWidth + Space.m) / (cellW + Space.m)).toInt().coerceAtLeast(1)
                    val rows = if (face == FaceSize.WIDE) 1 else ((maxHeight + Space.m) / (badge + title + Space.m)).toInt().coerceAtLeast(1)
                    return Triple(columns, rows, cellW)
                }
                val badge = if (face == FaceSize.WIDE) {
                    (maxHeight - title).coerceIn(Size.thumb, Size.thumbL)
                } else {
                    BADGE_SIZES.firstOrNull { b -> grid(b).let { (c, r, _) -> c * r >= list.size } } ?: BADGE_SIZES.last()
                }
                val (columns, rows, cellW) = grid(badge)
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    list.take(columns * rows).chunked(columns).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            for (a in line) {
                                Column(Modifier.width(cellW), horizontalAlignment = if (face == FaceSize.WIDE) Alignment.Start else Alignment.CenterHorizontally) {
                                    Badge(a.achievement.badgeUrl, badge)
                                    if (titled) {
                                        Spacer(Modifier.height(Space.xs))
                                        FText(a.achievement.title, Fuse.type.caption, color = c.textMuted, align = if (face == FaceSize.WIDE) null else TextAlign.Center, maxLines = 1, fit = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ProgressFace(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val states = feed.achievements?.inProgress.orEmpty()
    val s = states.first()
    WidgetHeader(FuseIcons.Target, "In progress", c.warning)
    when (face) {
        FaceSize.SMALL, FaceSize.WIDE -> {
            Spacer(Modifier.weight(1f))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // A long strip shows a game for each stretch of it.
                val shown = states.take(if (face == FaceSize.WIDE) (maxWidth / STRIP_ENTRY).toInt().coerceIn(1, 4) else 1)
                val ring = if (LocalWidgetRoom.current.compact) Size.thumb else Size.thumbL
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    for (st in shown) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Ring(st.progress, ring)
                            Spacer(Modifier.width(Space.m))
                            Column(Modifier.weight(1f)) {
                                FText(st.title, Fuse.type.bodyStrong, maxLines = 1, fit = true)
                                FText("${st.earned} of ${st.total}", Fuse.type.numericSmall, color = c.textMuted, maxLines = 1, fit = true)
                            }
                        }
                    }
                }
            }
        }
        FaceSize.TALL, FaceSize.SQUARE -> {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ring = (min(maxWidth, maxHeight) * 0.55f).coerceIn(Size.thumbL, 140.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ring(s.progress, ring, big = true)
                    Spacer(Modifier.height(Space.m))
                    FText(s.title, Fuse.type.bodyStrong, align = TextAlign.Center, maxLines = 2)
                    FText("${s.earned} of ${s.total}", Fuse.type.numericSmall, color = c.textMuted, maxLines = 1, fit = true)
                }
            }
        }
        FaceSize.LARGE -> {
            // Each game in progress as a ring with its name, side by side, as many as fit.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val columns = ((maxWidth + Space.l) / (RING_CARD + Space.l)).toInt().coerceIn(1, states.size)
                val rows = ((maxHeight + Space.l) / (RING_CARD + Space.l)).toInt().coerceIn(1, (states.size + columns - 1) / columns)
                val ring = ((maxHeight - Space.l * (rows - 1)) / rows - 48.dp).coerceIn(Size.thumbL, 120.dp)
                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    states.take(columns * rows).chunked(columns).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                            for (st in line) {
                                Column(Modifier.width(RING_CARD), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Ring(st.progress, ring, big = ring >= 96.dp)
                                    Spacer(Modifier.height(Space.s))
                                    FText(st.title, Fuse.type.label, align = TextAlign.Center, maxLines = 1, fit = true)
                                    FText("${st.earned} of ${st.total}", Fuse.type.numericSmall, color = c.textMuted, maxLines = 1, fit = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MasteredFace(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val list = feed.achievements?.recentlyMastered.orEmpty()
    val s = list.first()
    WidgetHeader(FuseIcons.Crown, "Mastered", c.warning)
    when (face) {
        FaceSize.SMALL, FaceSize.WIDE -> {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(s.iconUrl, if (LocalWidgetRoom.current.compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(s.title, Fuse.type.bodyStrong, maxLines = 1, fit = true)
                    WidgetCaption(s.consoleName.orEmpty())
                }
            }
        }
        FaceSize.LARGE -> {
            Spacer(Modifier.height(Space.m))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val rows = ((maxHeight + Space.s) / (Size.thumb + Space.s)).toInt().coerceIn(1, list.size)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (m in list.take(rows)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(m.iconUrl, Size.thumb)
                            Spacer(Modifier.width(Space.m))
                            Column(Modifier.weight(1f)) {
                                FText(m.title, Fuse.type.label, maxLines = 1, fit = true)
                                FText(m.consoleName.orEmpty(), Fuse.type.caption, color = c.textMuted, maxLines = 1, fit = true)
                            }
                        }
                    }
                }
            }
        }
        else -> {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val badge = (min(maxWidth, maxHeight) * 0.5f).coerceIn(Size.thumbL, 132.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Badge(s.iconUrl, badge, glow = true)
                    Spacer(Modifier.height(Space.m))
                    FText(s.title, Fuse.type.titleSmall, align = TextAlign.Center, maxLines = 2)
                    FText(s.consoleName.orEmpty(), Fuse.type.caption, color = c.textMuted, align = TextAlign.Center, maxLines = 1, fit = true)
                }
            }
        }
    }
}

/** An achievement's badge, or a game's icon, in a rounded square; with [glow], lit gold from behind. */
@Composable
private fun Badge(model: Any?, size: Dp, glow: Boolean = false) {
    val c = Fuse.colors
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.14f))
    Box(contentAlignment = Alignment.Center) {
        if (glow) {
            Box(
                Modifier.size(size * 1.7f).background(
                    Brush.radialGradient(0f to c.warning.copy(alpha = 0.28f), 0.6f to c.warning.copy(alpha = 0.06f), 1f to Color.Transparent),
                ),
            )
        }
        Artwork(model, Modifier.size(size).lifted(shape), fallback = { BadgeFallback() })
    }
}

/** A progress ring in gold with the share inside it. */
@Composable
private fun Ring(progress: Float, size: Dp, big: Boolean = false) {
    ProgressRing(progress, size = size, stroke = if (big) Size.track * 2 else Size.track, color = Fuse.colors.warning) {
        FText("${(progress * 100).toInt()}%", if (big) Fuse.type.titleSmall.tabular() else Fuse.type.numericSmall, maxLines = 1, fit = true)
    }
}

// --------------------------------------------------------------------------------- cartridge

@Composable
private fun ColumnScope.CartridgeFace(cartridge: CartridgeStatus, face: FaceSize) {
    val c = Fuse.colors
    val downloading = cartridge.installed && cartridge.activeDownloads > 0
    val now = CartridgeNow.of(cartridge)
    WidgetHeader(FuseMarks.Cartridge, WidgetKind.CARTRIDGE_DOWNLOADS.title(), trailing = if (downloading && face == FaceSize.WIDE) now.percent else null)
    if (!cartridge.installed) {
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CartridgeEmblem(if (LocalWidgetRoom.current.compact) 40.dp else 56.dp)
            Spacer(Modifier.width(Space.m))
            Column {
                FText("Get games from RomM", Fuse.type.bodyStrong, maxLines = 2)
                WidgetCaption("Install Cartridge", CARTRIDGE_TINT.toColor())
            }
        }
        return
    }
    if (face == FaceSize.SMALL) {
        Spacer(Modifier.weight(1f))
        if (downloading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(now.progress ?: 0f, size = Size.thumb, stroke = Size.track, color = CARTRIDGE_TINT.toColor()) {
                    FuseIcon(FuseIcons.ArrowDown, size = Size.iconXS, tint = c.text)
                }
                Spacer(Modifier.width(Space.s))
                Column {
                    FText(now.percent ?: "…", Fuse.type.titleSmall.tabular(), maxLines = 1, fit = true)
                    WidgetCaption(if (now.waiting > 0) "${now.waiting} queued" else "Downloading")
                }
            }
        } else {
            CartridgeEmblem(36.dp)
            Spacer(Modifier.height(Space.xs))
            FText(if (cartridge.connected == false) "Not connected" else "Cartridge", Fuse.type.bodyStrong, maxLines = 1, fit = true)
            WidgetCaption(cartridge.recent.firstOrNull()?.title?.let { "Latest: $it" } ?: "No downloads")
        }
        return
    }
    // Being connected is the normal state; only its absence is worth a line.
    val status = when (cartridge.connected) {
        true -> null
        false -> "Not connected to RomM"
        null -> if (cartridge.bridge) "Open to connect" else "Update Cartridge for live status"
    }
    if (face == FaceSize.WIDE) {
        Spacer(Modifier.weight(1f))
        if (downloading) {
            FText(now.title ?: "Downloading", Fuse.type.bodyStrong, maxLines = 1, fit = true)
            Spacer(Modifier.height(Space.s))
            ProgressBar(now.progress, Modifier.fillMaxWidth(), color = CARTRIDGE_TINT.toColor())
            Spacer(Modifier.height(Space.xs + Space.xxs))
            WidgetCaption(if (now.waiting > 0) "${now.waiting} more queued" else "Downloading from RomM")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CartridgeEmblem(if (LocalWidgetRoom.current.compact) 36.dp else 48.dp)
                Spacer(Modifier.width(Space.m))
                Column {
                    FText(cartridge.recent.firstOrNull()?.let { "Latest: ${it.title}" } ?: "No downloads", Fuse.type.bodyStrong, maxLines = 1, fit = true)
                    status?.let { WidgetCaption(it) }
                }
            }
        }
        return
    }
    // Taller faces: the download as a ring, with what it is and what waits beside it or under it.
    val queued = cartridge.queue.filter { it.state == QueueState.QUEUED }.map { it.title }
    val lines = (queued.map { FuseIcons.Clock3 to it } + cartridge.recent.drop(if (downloading) 0 else 1).map { FuseIcons.Check to it.title })
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = maxWidth > maxHeight * 1.3f
        val ring = (if (side) maxHeight * 0.78f else min(maxWidth, maxHeight) * 0.46f).coerceIn(Size.thumbL, if (face == FaceSize.LARGE) 260.dp else 200.dp)
        val big = ring >= 150.dp
        val room = if (side) maxHeight else maxHeight - ring - Space.l
        val shown = lines.take(((room - 64.dp) / 22.dp).toInt().coerceIn(0, QUEUE_LINES))
        val dial: @Composable () -> Unit = {
            if (downloading) {
                ProgressRing(now.progress ?: 0f, size = ring, stroke = Size.track * if (big) 3 else 2, color = CARTRIDGE_TINT.toColor()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FText(now.percent ?: "", (if (big) Fuse.type.title else Fuse.type.titleSmall).tabular(), maxLines = 1)
                        FText("downloaded", Fuse.type.caption, color = c.textMuted, maxLines = 1, fit = true)
                    }
                }
            } else {
                Box(Modifier.size(ring), contentAlignment = Alignment.Center) { CartridgeEmblem(ring * 0.62f) }
            }
        }
        val words: @Composable () -> Unit = {
            Column(horizontalAlignment = if (side) Alignment.Start else Alignment.CenterHorizontally) {
                FText(
                    if (downloading) now.title ?: "Downloading" else cartridge.recent.firstOrNull()?.let { "Latest: ${it.title}" } ?: "No downloads",
                    if (big) Fuse.type.titleSmall else Fuse.type.bodyStrong,
                    align = if (side) null else TextAlign.Center,
                    maxLines = 2,
                )
                (if (downloading) (if (now.waiting > 0) "${now.waiting} more queued" else "Downloading from RomM") else status)?.let { WidgetCaption(it) }
                if (shown.isNotEmpty()) {
                    Spacer(Modifier.height(Space.s))
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        for ((icon, title) in shown) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FuseIcon(icon, size = Size.iconXS, tint = c.textFaint)
                                Spacer(Modifier.width(Space.s))
                                FText(title, Fuse.type.caption, color = c.textMuted, maxLines = 1, fit = true)
                            }
                        }
                    }
                }
            }
        }
        if (side) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) { dial(); words() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                dial()
                Spacer(Modifier.height(Space.l))
                words()
            }
        }
    }
}



/** Covers' corners against the theme's tile corner. */
private const val COVER_CORNER = 0.45f

/** A face this much wider than tall puts its covers down the right. */
private const val WIDE_COVERS = 1.7f
private val COVER_MAX = 150.dp
private const val MAX_COVERS = 6

/** Icon sizes the Apps widget tries, largest first. */
private val APP_SIZES = listOf(112.dp, 96.dp, 80.dp, 72.dp, 64.dp, 56.dp, 48.dp, 40.dp)

/** Badge sizes the recent achievements try on larger faces, largest first. */
private val BADGE_SIZES = listOf(96.dp, 80.dp, 72.dp, 64.dp, 56.dp, 48.dp)

/** The width of a game in progress on a large face, and of one on a strip. */
private val RING_CARD = 168.dp
private val STRIP_ENTRY = 260.dp

/** Lines of Cartridge's queue on a tall face. */
private const val QUEUE_LINES = 4

