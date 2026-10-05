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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.model.isJellyfin
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.flourishOn
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.openJellyfin
import io.github.matiyaaa.fuse.ui.shell.app.openSyncHub
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.StageInfo
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.jellyfin.openMedia
import io.github.matiyaaa.fuse.ui.shell.jellyfin.play
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Width of a widget card for its span, in tile units, so widgets line up with game tiles. */
@Composable
fun widgetWidth(span: WidgetSpan): Dp {
    val m = LocalTileMetrics.current
    return m.icon * span.columns + m.gap * (span.columns - 1)
}

/** A Home widget drawn as a card the same height as the tiles around it. */
@Composable
fun WidgetCard(
    kind: WidgetKind,
    span: WidgetSpan,
    feed: HomeFeed,
    cartridge: CartridgeStatus,
    selected: Boolean,
    clock24h: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = LocalTileMetrics.current.icon,
) {
    Tile(
        selected = selected,
        modifier = modifier.size(width = widgetWidth(span), height = height),
        glow = widgetTint(kind, feed, cartridge),
        onClick = onClick,
    ) {
        WidgetContent(kind, feed, cartridge, clock24h)
    }
}

/**
 * A widget's face, without the tile around it (Channel Mode wraps it in its own tile).
 *
 * Every face is built the same way, so a row of them reads as one set of objects: a raised fill
 * lit faintly from its top corner in the widget's own colour, a header (a small icon and the
 * widget's name in the overline style), then its value in Sora with tabular figures, numbers large
 * and units small, sized to fit, and a quiet caption. Faces pick their layout from the room they
 * get: wide cards put a chart beside the value, short ones (handhelds) drop the least useful line.
 */
@Composable
fun WidgetContent(kind: WidgetKind, feed: HomeFeed, cartridge: CartridgeStatus, clock24h: Boolean) {
    if (kind in MediaKinds) {
        MediaCarousel(kind, mediaItems(kind, feed), FaceSize.WIDE)
        return
    }
    val c = Fuse.colors
    val tint = widgetTint(kind, feed, cartridge)
    BoxWithConstraints(Modifier.fillMaxSize().widgetSurface(c.surfaceRaised, tint.copy(alpha = if (c.isDark) WASH_DARK else WASH_LIGHT))) {
        val room = widgetRoom(maxWidth, maxHeight)
        CompositionLocalProvider(LocalWidgetRoom provides room) {
        Column(Modifier.fillMaxSize().padding(if (room.compact) Space.m else Space.l)) {
            val note = widgetEmptyNote(kind, feed)
            if (note != null) {
                // A channel can be placed before it has anything to show: it says what will fill it.
                EmptyFace(widgetIcon(kind), widgetLabel(kind), note)
                return@Column
            }
            when (kind) {
                WidgetKind.PLAYTIME_WEEK -> PlaytimeWeek(feed, room)
                WidgetKind.PLAYTIME_TOTAL -> BigNumber(FuseIcons.Hourglass, widgetLabel(kind), playtimeText(feed.playtime.totalSeconds), "Tracked by Fuse", short = "Total")
                WidgetKind.MOST_PLAYED -> MostPlayed(feed, room)
                WidgetKind.CURRENT_GAME -> CurrentGame(feed, room)
                WidgetKind.RECENT_ACHIEVEMENT -> RecentAchievement(feed, room)
                WidgetKind.RECENT_ACHIEVEMENTS -> RecentAchievements(feed, room)
                WidgetKind.ACHIEVEMENT_PROGRESS -> AchievementProgress(feed, room)
                WidgetKind.RECENTLY_MASTERED -> RecentlyMastered(feed, room)
                WidgetKind.CARTRIDGE_DOWNLOADS -> CartridgeWidget(cartridge, room)
                WidgetKind.STORAGE -> StorageWidget(feed)
                WidgetKind.CLOCK -> ClockWidget(clock24h)
                else -> WidgetHeader(widgetIcon(kind), kind.title())
            }
        }
        }
    }
}

/** The name in a face's header: the widget's title, or a shorter one where a one-tile card needs it. */
internal fun widgetLabel(kind: WidgetKind): String = when (kind) {
    // "Total playtime" would be cut short on a one-tile card.
    WidgetKind.PLAYTIME_TOTAL -> "All time"
    else -> kind.title()
}

/** What fills a widget that has nothing to show yet, or null when it has. */
internal fun widgetEmptyNote(kind: WidgetKind, feed: HomeFeed): String? {
    val a = feed.achievements
    val connect = "Connect RetroAchievements in Settings"
    return when (kind) {
        WidgetKind.PLAYTIME_WEEK, WidgetKind.PLAYTIME_TOTAL -> if (feed.playtime.totalSeconds <= 0) "Time you play shows here" else null
        WidgetKind.MOST_PLAYED -> if (feed.mostPlayed.isEmpty()) "Games you play most show here" else null
        WidgetKind.CURRENT_GAME -> if (feed.playtime.currentGame == null) "Nothing is running" else null
        WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS -> when {
            a == null -> connect
            a.recent.isEmpty() -> "Achievements you unlock show here"
            else -> null
        }
        WidgetKind.ACHIEVEMENT_PROGRESS -> when {
            a == null -> connect
            a.inProgress.isEmpty() -> "Play a game with achievements to see progress"
            else -> null
        }
        WidgetKind.RECENTLY_MASTERED -> when {
            a == null -> connect
            a.recentlyMastered.isEmpty() -> "Games you master show here"
            else -> null
        }
        WidgetKind.STORAGE -> if (feed.storage == null) "Add a library folder to see its space" else null
        else -> null
    }
}

/** A face with nothing to show yet: its header, and what will fill it where the value would be. */
@Composable
internal fun ColumnScope.EmptyFace(icon: ImageVector, label: String, note: String) {
    val c = Fuse.colors
    WidgetHeader(icon, label)
    if (LocalWidgetRoom.current.roomy) {
        // A large face says it in the middle, under its icon, rather than in a corner of an empty card.
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(Size.touch).clip(androidx.compose.foundation.shape.CircleShape).background(c.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                FuseIcon(icon, size = Size.iconL, tint = c.textMuted)
            }
            Spacer(Modifier.height(Space.m))
            FText(note, Fuse.type.label, color = c.textMuted, align = TextAlign.Center, maxLines = 3)
        }
        Spacer(Modifier.weight(1.3f))
        return
    }
    Spacer(Modifier.weight(1f))
    FText(note, Fuse.type.label, color = c.textMuted, maxLines = 2)
}

/** The time Home shows on its stage, shared with the clock card so they turn over together. */
internal val LocalHomeTime = compositionLocalOf<String?> { null }

/**
 * How much room a face has: [compact] when short, [wide] when it can put a chart beside its value,
 * [small] when narrow too (one tile on a handheld), where only the essentials fit.
 */
internal class WidgetRoom(val compact: Boolean, val wide: Boolean, val small: Boolean = false, val roomy: Boolean = false) {
    /** A short, narrow card: header, value and at most a few words under it. */
    val tiny: Boolean get() = compact && small
}

/** How much room a face [width] by [height] has. */
internal fun widgetRoom(width: Dp, height: Dp) = WidgetRoom(
    compact = height < COMPACT_BELOW,
    wide = width >= height * WIDE_RATIO,
    small = width < SMALL_BELOW,
    roomy = height >= ROOMY_FROM && width >= ROOMY_FROM,
)

/** The room of the face being drawn, so its value can size itself to it. */
internal val LocalWidgetRoom = staticCompositionLocalOf { WidgetRoom(compact = false, wide = false) }

/** Narrower than this a face is a single handheld tile. */
private val SMALL_BELOW = 120.dp

/** From this height and width a face is large enough to centre what it says. */
private val ROOMY_FROM = 220.dp

/** Below this height a face is short (a 6 inch handheld's shelf) and keeps to its essentials. */
private val COMPACT_BELOW = 120.dp

/** From this width-to-height ratio a face is wide enough to put a chart beside its value. */
private const val WIDE_RATIO = 1.8f

/** Strength of a face's corner light in its own colour, in dark and light themes. */
internal const val WASH_DARK = 0.13f
internal const val WASH_LIGHT = 0.08f

/** The icon that leads a widget's header (and its channel's label). */
internal fun widgetIcon(kind: WidgetKind): ImageVector = when (kind) {
    WidgetKind.PLAYTIME_WEEK -> FuseIcons.Calendar
    WidgetKind.PLAYTIME_TOTAL -> FuseIcons.Hourglass
    WidgetKind.MOST_PLAYED -> FuseIcons.TrendingUp
    WidgetKind.CURRENT_GAME -> FuseIcons.Gamepad
    WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS -> FuseIcons.Trophy
    WidgetKind.ACHIEVEMENT_PROGRESS -> FuseIcons.Target
    WidgetKind.RECENTLY_MASTERED -> FuseIcons.Crown
    WidgetKind.CARTRIDGE_DOWNLOADS -> FuseMarks.Cartridge
    WidgetKind.STORAGE -> FuseIcons.HardDrive
    WidgetKind.CLOCK -> FuseIcons.Clock3
    WidgetKind.CONTINUE_PLAYING -> FuseIcons.CirclePlay
    WidgetKind.RECENTLY_PLAYED -> FuseIcons.History
    WidgetKind.FAVORITES -> FuseIcons.Heart
    WidgetKind.RECENTLY_ADDED -> FuseIcons.Sparkles
    WidgetKind.PINNED_GAMES -> FuseIcons.Pin
    WidgetKind.PINNED_APPS -> FuseIcons.AppWindow
    WidgetKind.COLLECTIONS -> FuseIcons.Bookmark
    WidgetKind.SYSTEMS -> FuseIcons.Chip
    WidgetKind.JELLYFIN_CONTINUE -> FuseIcons.MonitorPlay
    WidgetKind.JELLYFIN_NEXT_UP -> FuseIcons.SkipForward
    WidgetKind.JELLYFIN_RECENTLY_ADDED -> FuseIcons.Film
    WidgetKind.JELLYFIN_FAVORITES -> FuseIcons.Heart
    WidgetKind.JELLYFIN_MOVIES -> FuseIcons.Clapperboard
    WidgetKind.JELLYFIN_MUSIC -> FuseIcons.Disc
    WidgetKind.SYNC_STATUS -> FuseIcons.RefreshCcw
    WidgetKind.SYNC_DEVICES -> FuseIcons.MonitorSmartphone
}

/** Jellyfin's widgets, which show films and episodes rather than games. */
internal val MediaKinds = WidgetKind.entries.filter { it.isJellyfin }.toSet()

/** What a Jellyfin widget shows, first first. */
internal fun mediaItems(kind: WidgetKind, feed: HomeFeed): List<io.github.matiyaaa.fuse.jellyfin.MediaItem> = when (kind) {
    WidgetKind.JELLYFIN_CONTINUE -> feed.media.continueWatching
    WidgetKind.JELLYFIN_NEXT_UP -> feed.media.nextUp
    WidgetKind.JELLYFIN_RECENTLY_ADDED -> feed.media.recentlyAdded
    WidgetKind.JELLYFIN_FAVORITES -> feed.media.favorites
    WidgetKind.JELLYFIN_MOVIES -> feed.media.movies
    WidgetKind.JELLYFIN_MUSIC -> feed.media.music
    else -> emptyList()
}

/** A film's name, or an episode's show. */
internal fun mediaTitle(m: io.github.matiyaaa.fuse.jellyfin.MediaItem): String =
    if (m.type == io.github.matiyaaa.fuse.jellyfin.MediaType.EPISODE) m.seriesName ?: m.name else m.name

/** Under it: the episode, and how long is left when it was started. */
internal fun mediaCaption(m: io.github.matiyaaa.fuse.jellyfin.MediaItem): String? = listOfNotNull(
    if (m.type == io.github.matiyaaa.fuse.jellyfin.MediaType.EPISODE) listOfNotNull(m.episodeLabel, m.name.takeIf { it != m.seriesName }).joinToString(" ") else m.year?.toString(),
    m.leftMs?.takeIf { m.progress != null }?.let { "${io.github.matiyaaa.fuse.ui.shell.jellyfin.minutes(it)} left" },
).filter { it.isNotBlank() }.joinToString("  ·  ").ifEmpty { null }

/**
 * A widget's own colour: the light in its corner and its glow when focused. Playtime and downloads
 * take the accent, achievements gold, the game being played its own colour; storage turns to the
 * warning colour when the drive is nearly full. The rest stay neutral, so the accent stays rare.
 */
@Composable
internal fun widgetTint(kind: WidgetKind, feed: HomeFeed, cartridge: CartridgeStatus): Color {
    val c = Fuse.colors
    return when (kind) {
        WidgetKind.PLAYTIME_WEEK, WidgetKind.PLAYTIME_TOTAL, WidgetKind.MOST_PLAYED -> c.accent
        WidgetKind.CARTRIDGE_DOWNLOADS -> io.github.matiyaaa.fuse.ui.designsystem.icons.CartridgeBrand.ORANGE.toColor()
        WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.ACHIEVEMENT_PROGRESS, WidgetKind.RECENTLY_MASTERED -> c.warning
        WidgetKind.CURRENT_GAME -> feed.playtime.currentGame?.accent?.toColor() ?: c.accent
        WidgetKind.STORAGE -> if (storageLow(feed)) c.warning else c.text
        in MediaKinds ->
            mediaItems(kind, feed).firstOrNull()?.let { io.github.matiyaaa.fuse.ui.shell.jellyfin.accentOf(it.name) } ?: c.accent
        else -> c.text
    }
}

internal fun storageLow(feed: HomeFeed): Boolean = feed.storage?.let { it.freeBytes.toFloat() / it.totalBytes.coerceAtLeast(1) < 0.1f } ?: false

/** The raised fill with a soft light in [wash] gathering in its top right corner. Built once per size. */
internal fun Modifier.widgetSurface(base: Color, wash: Color): Modifier = drawWithCache {
    val light = Brush.radialGradient(
        0f to wash,
        0.55f to wash.copy(alpha = wash.alpha * 0.35f),
        1f to Color.Transparent,
        center = Offset(size.width, 0f),
        radius = maxOf(size.width, size.height) * 0.95f,
    )
    onDrawBehind {
        drawRect(base)
        drawRect(light)
    }
}

/**
 * A face's header: a small icon and the widget's name, with an optional value on the right. On a
 * card too small for the name it uses [short] instead, and failing that the icon alone, rather
 * than a name cut short.
 */
@Composable
internal fun WidgetHeader(icon: ImageVector, label: String, tint: Color = Fuse.colors.textMuted, trailing: String? = null, short: String? = null) {
    val c = Fuse.colors
    val style = Fuse.type.overline
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val trailingWidth = trailing?.let { measurer.measure(it, Fuse.type.numericSmall, maxLines = 1).size.width + with(density) { Space.s.roundToPx() } } ?: 0
        val room = constraints.maxWidth - with(density) { (Size.iconXS + Space.s - Space.xxs).roundToPx() } - trailingWidth
        val text = listOfNotNull(label, short).map { it.uppercase() }.firstOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= room }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(icon, size = Size.iconXS, tint = tint)
            if (text != null) {
                Spacer(Modifier.width(Space.s - Space.xxs))
                FText(text, style, color = tint, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (trailing != null) FText(trailing, Fuse.type.numericSmall, color = c.textMuted, maxLines = 1)
        }
    }
}

@Composable
internal fun WidgetCaption(text: String, color: Color = Fuse.colors.textMuted, modifier: Modifier = Modifier) {
    FText(text, Fuse.type.caption, color = color, maxLines = 1, modifier = modifier)
}

/**
 * A widget's value in Sora with tabular figures: its numbers large and its units ("h", "GB free",
 * "PM") small and muted, in the largest size that fits on one line. Text without numbers ("Under
 * a minute") is set smaller, on up to two lines.
 */
@Composable
internal fun WidgetValue(text: String, modifier: Modifier = Modifier) {
    val t = Fuse.type
    val c = Fuse.colors
    if (!text.any { it.isDigit() }) {
        FText(text, t.titleSmall, color = c.text, maxLines = 2, modifier = modifier)
        return
    }
    // A short card (a handheld's) starts a size down, a short narrow one two, so the value leaves
    // room for its caption.
    val room = LocalWidgetRoom.current
    val skip = when {
        room.tiny -> 2
        room.compact -> 1
        else -> 0
    }
    val steps = remember(t, skip) {
        listOf(
            t.numericLarge to t.titleSmall,
            t.display.tabular() to t.numeric,
            t.title.tabular() to t.numericSmall,
            t.titleSmall.tabular() to t.numericSmall,
            t.numeric to t.numericSmall,
        ).drop(skip)
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val max = constraints.maxWidth
        val muted = c.textMuted
        val pick = steps.firstOrNull { (big, unit) ->
            measurer.measure(valueText(text, unit, muted), big, softWrap = false, maxLines = 1).size.width <= max
        } ?: steps.last()
        BasicText(
            valueText(text, pick.second, muted),
            style = pick.first.copy(color = c.text),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val numberRun = Regex("""\d[\d.,:]*""")

/** [text] with every run that isn't a number set in [unit] and [unitColor]. */
private fun valueText(text: String, unit: TextStyle, unitColor: Color): AnnotatedString = buildAnnotatedString {
    val unitStyle = SpanStyle(
        color = unitColor,
        fontSize = unit.fontSize,
        fontWeight = unit.fontWeight,
        fontFamily = unit.fontFamily,
        letterSpacing = unit.letterSpacing,
    )
    var at = 0
    for (m in numberRun.findAll(text)) {
        if (m.range.first > at) withStyle(unitStyle) { append(text.substring(at, m.range.first)) }
        append(m.value)
        at = m.range.last + 1
    }
    if (at < text.length) withStyle(unitStyle) { append(text.substring(at)) }
}

@Composable
private fun ColumnScope.BigNumber(icon: ImageVector, label: String, value: String, caption: String?, short: String? = null) {
    WidgetHeader(icon, label, short = short)
    Spacer(Modifier.weight(1f))
    WidgetValue(value)
    // A single handheld tile keeps to its value.
    if (caption != null && !LocalWidgetRoom.current.tiny) WidgetCaption(caption)
}

@Composable
private fun ColumnScope.PlaytimeWeek(feed: HomeFeed, room: WidgetRoom) {
    val days = feed.playtime.lastSevenDays.ifEmpty { List(7) { 0L } }
    val week = playtimeText(feed.playtime.weekSeconds)
    val total = "${playtimeText(feed.playtime.totalSeconds)} in total"
    if (room.wide) {
        // The header across the top; under it the value on the left and the week as a chart on the right.
        WidgetHeader(FuseIcons.Calendar, WidgetKind.PLAYTIME_WEEK.title())
        Spacer(Modifier.height(Space.s))
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                WidgetValue(week)
                // A short card keeps the value; the total is on the stage beside it.
                if (!room.compact) WidgetCaption(total)
            }
            Spacer(Modifier.width(Space.l))
            WeekBars(days, Modifier.weight(1f).fillMaxHeight(), letters = !room.compact)
        }
    } else {
        WidgetHeader(FuseIcons.Calendar, WidgetKind.PLAYTIME_WEEK.title())
        Spacer(Modifier.height(Space.xs))
        WidgetValue(week)
        Spacer(Modifier.height(Space.s))
        WeekBars(days, Modifier.fillMaxWidth().weight(1f), letters = false)
    }
}

/**
 * The last seven days of play as bars, oldest first, today in the accent colour and every other
 * day quiet. Bars grow in once when they appear (not under Reduced motion or in Low Power Mode).
 * With [letters], each day's initial sits under its bar, today's brighter.
 */
@Composable
internal fun WeekBars(days: List<Long>, modifier: Modifier, letters: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val flourish = motion.flourishOn(Fuse.quality)
    val grow = remember { FuselineValue(if (flourish) 0f else 1f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, motion.value()) }
    val max = (days.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val rest = c.text.copy(alpha = if (c.isDark) 0.2f else 0.14f)
    val today = c.accent
    val baseline = c.hairline
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val gap = Space.s.toPx().coerceAtMost(size.width / days.size * 0.3f)
            val w = (size.width - gap * (days.size - 1)) / days.size
            val corner = CornerRadius(minOf(w / 2, Space.xs.toPx()))
            val stub = Space.xxs.toPx()
            val g = grow.value
            days.forEachIndexed { i, v ->
                // Each bar starts a beat after the one before it, so the week fills from the left.
                val p = ((g - i * 0.05f) / 0.7f).coerceIn(0f, 1f)
                val h = (size.height * (v.toFloat() / max) * p).coerceAtLeast(stub)
                drawRoundRect(
                    color = if (i == days.lastIndex) today else rest,
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = corner,
                )
            }
            drawRect(baseline, topLeft = Offset(0f, size.height - Size.divider.toPx() / 2), size = androidx.compose.ui.geometry.Size(size.width, Size.divider.toPx()))
        }
        if (letters) {
            Spacer(Modifier.height(Space.xs))
            val initials = remember(days.size) { dayInitials(days.size) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                initials.forEachIndexed { i, d ->
                    FText(
                        d, Fuse.type.overline, color = if (i == initials.lastIndex) c.text else c.textFaint,
                        align = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Initials of the last [count] days, oldest first, ending today ("F S S M T W T"). */
private fun dayInitials(count: Int): List<String> {
    // Monday first, as DayOfWeek counts.
    val week = listOf("M", "T", "W", "T", "F", "S", "S")
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek.ordinal
    return List(count) { i -> week[((today - (count - 1 - i)) % 7 + 7) % 7] }
}

/** Today as "Thursday" and "1 October", for the clock. */
internal fun todayParts(): Pair<String, String> {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val day = t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return day to "${t.day} $month"
}

@Composable
private fun ColumnScope.MostPlayed(feed: HomeFeed, room: WidgetRoom) {
    val c = Fuse.colors
    WidgetHeader(FuseIcons.TrendingUp, WidgetKind.MOST_PLAYED.title())
    Spacer(Modifier.height(Space.s))
    val top = feed.mostPlayed.take(if (room.compact) 2 else 3)
    val max = (top.maxOfOrNull { it.playSeconds } ?: 1L).coerceAtLeast(1L)
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
        top.forEachIndexed { i, g ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FText(g.title, Fuse.type.label, color = if (i == 0) c.text else c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    FText(playtimeText(g.playSeconds), Fuse.type.numericSmall, color = c.textMuted, maxLines = 1)
                }
                // The leader in the accent, the rest quiet.
                ProgressBar(g.playSeconds.toFloat() / max, Modifier.fillMaxWidth(), color = if (i == 0) c.accent else c.textFaint, height = Space.xxs)
            }
        }
    }
}

/** A face led by a piece of art: the game being played, an achievement's badge. */
@Composable
private fun ColumnScope.ArtFace(
    room: WidgetRoom,
    icon: ImageVector,
    label: String,
    title: String,
    caption: String?,
    tint: Color = Fuse.colors.textMuted,
    art: @Composable (Modifier) -> Unit,
) {
    val artSize = if (room.compact) Size.thumb else Size.thumbL
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(MIN_ART_CORNER))
    WidgetHeader(icon, label, tint)
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        art(Modifier.size(artSize).clip(shape))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(title, Fuse.type.bodyStrong, maxLines = if (room.compact || caption != null) 1 else 2)
            if (caption != null) WidgetCaption(caption)
        }
    }
}

/** Art keeps a little rounding even in the sharp corner family. */
private const val MIN_ART_CORNER = 0.14f

@Composable
private fun ColumnScope.CurrentGame(feed: HomeFeed, room: WidgetRoom) {
    val g = feed.playtime.currentGame ?: return
    val since = feed.playtime.currentSince?.let { "Started ${agoText(it)}" }
    ArtFace(room, FuseIcons.Gamepad, WidgetKind.CURRENT_GAME.title(), g.title, since ?: g.platformShort) { m ->
        SquareGameArt(g.art, m, fallback = { GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.ICON) })
    }
}

@Composable
private fun ColumnScope.RecentAchievement(feed: HomeFeed, room: WidgetRoom) {
    val a = feed.achievements?.recent?.firstOrNull() ?: return
    ArtFace(
        room, FuseIcons.Trophy, if (a.hardcore) "Unlocked · Hardcore" else "Unlocked",
        a.achievement.title, "${a.gameTitle} · ${a.achievement.points} points", Fuse.colors.warning,
    ) { m -> Artwork(a.achievement.badgeUrl, m, fallback = { BadgeFallback() }) }
}

@Composable
internal fun BadgeFallback() {
    val c = Fuse.colors
    Box(Modifier.fillMaxSize().widgetSurface(c.surface, c.warning.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        FuseIcon(FuseIcons.Trophy, size = Size.iconM, tint = c.warning)
    }
}

@Composable
private fun ColumnScope.RecentAchievements(feed: HomeFeed, room: WidgetRoom) {
    val c = Fuse.colors
    val list = feed.achievements?.recent.orEmpty().take(6)
    val badge = if (room.compact) Size.thumb else Size.thumbL
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(MIN_ART_CORNER))
    WidgetHeader(FuseIcons.Trophy, WidgetKind.RECENT_ACHIEVEMENTS.title(), c.warning, trailing = list.size.takeIf { it > 0 }?.toString())
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        for (a in list) {
            Column(Modifier.width(badge + Space.l), horizontalAlignment = Alignment.Start) {
                Artwork(a.achievement.badgeUrl, Modifier.size(badge).clip(shape), fallback = { BadgeFallback() })
                if (!room.compact) {
                    Spacer(Modifier.height(Space.xs))
                    FText(a.achievement.title, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AchievementProgress(feed: HomeFeed, room: WidgetRoom) {
    val c = Fuse.colors
    val s = feed.achievements?.inProgress?.firstOrNull() ?: return
    WidgetHeader(FuseIcons.Target, "In progress", c.warning)
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(s.progress, size = if (room.compact) Size.thumb else Size.thumbL, stroke = Size.track, color = c.warning) {
            FText("${(s.progress * 100).toInt()}%", Fuse.type.numericSmall, maxLines = 1)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(s.title, Fuse.type.bodyStrong, maxLines = 1)
            FText("${s.earned} of ${s.total}", Fuse.type.numericSmall, color = c.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.RecentlyMastered(feed: HomeFeed, room: WidgetRoom) {
    val s = feed.achievements?.recentlyMastered?.firstOrNull() ?: return
    ArtFace(room, FuseIcons.Crown, "Mastered", s.title, s.consoleName, Fuse.colors.warning) { m ->
        Artwork(s.iconUrl, m, fallback = { BadgeFallback() })
    }
}

/**
 * The download Cartridge is on, read the same way by the widget and the stage, so the two never
 * show different numbers: the item downloading now (its own progress when the queue reports one,
 * else the queue's), and how many wait behind it.
 */
internal class CartridgeNow(val title: String?, val progress: Float?, val waiting: Int) {
    val percent: String? get() = progress?.let { "${(it * 100).toInt()}%" }

    companion object {
        fun of(status: CartridgeStatus): CartridgeNow {
            val current = status.queue.firstOrNull { it.state == QueueState.DOWNLOADING }
            val waiting = if (status.queue.isNotEmpty()) status.queue.count { it.state == QueueState.QUEUED } else status.queuedDownloads
            return CartridgeNow(current?.title ?: status.currentTitle, current?.progress ?: status.progress, waiting)
        }
    }
}

@Composable
private fun ColumnScope.CartridgeWidget(status: CartridgeStatus, room: WidgetRoom) {
    val c = Fuse.colors
    val downloading = status.installed && status.activeDownloads > 0
    val now = CartridgeNow.of(status)
    WidgetHeader(
        FuseMarks.Cartridge, WidgetKind.CARTRIDGE_DOWNLOADS.title(),
        trailing = if (downloading) now.percent else null,
    )
    Spacer(Modifier.weight(1f))
    when {
        !status.installed -> {
            FText("Get games from RomM", Fuse.type.bodyStrong, maxLines = 1)
            WidgetCaption("Install Cartridge", c.accent)
        }
        downloading -> {
            FText(now.title ?: "Downloading", Fuse.type.bodyStrong, maxLines = 1)
            Spacer(Modifier.height(Space.s))
            ProgressBar(now.progress, Modifier.fillMaxWidth())
            if (!room.compact) {
                Spacer(Modifier.height(Space.xs + Space.xxs))
                WidgetCaption(if (now.waiting > 0) "${now.waiting} more queued" else "Downloading from RomM")
            }
        }
        else -> {
            FText(status.recent.firstOrNull()?.let { "Latest: ${it.title}" } ?: "No downloads", Fuse.type.bodyStrong, maxLines = 1)
            // Being connected is the normal state; only its absence is worth a line.
            when (status.connected) {
                true -> Unit
                false -> WidgetCaption("Not connected to RomM", c.warning)
                null -> WidgetCaption(if (status.bridge) "Open to connect" else "Update Cartridge for live status")
            }
        }
    }
}

@Composable
private fun ColumnScope.StorageWidget(feed: HomeFeed) {
    val c = Fuse.colors
    val s = feed.storage ?: return
    val used = 1f - s.freeBytes.toFloat() / s.totalBytes.coerceAtLeast(1)
    val low = storageLow(feed)
    WidgetHeader(FuseIcons.HardDrive, WidgetKind.STORAGE.title(), if (low) c.warning else c.textMuted)
    Spacer(Modifier.weight(1f))
    WidgetValue(bytesText(s.freeBytes))
    Spacer(Modifier.height(Space.s))
    ProgressBar(used, Modifier.fillMaxWidth(), color = if (low) c.warning else c.textMuted)
    Spacer(Modifier.height(Space.xs + Space.xxs))
    WidgetCaption(if (LocalWidgetRoom.current.tiny) "of ${bytesText(s.totalBytes)}" else "free of ${bytesText(s.totalBytes)}")
}

@Composable
private fun ColumnScope.ClockWidget(clock24h: Boolean) {
    // Home's own clock when it provides one, so the card and the stage never show different minutes.
    val time = LocalHomeTime.current ?: rememberClockText(clock24h)
    val (day, date) = todayParts()
    WidgetHeader(FuseIcons.Clock3, day, short = day.take(3))
    Spacer(Modifier.weight(1f))
    WidgetValue(time)
    WidgetCaption(date)
}

/**
 * What the stage (Flow) and the spotlight (Channels) tell about a widget, the same in both modes:
 * its value told big under the widget's name, with what it means beside it. [accent] colours the
 * stage's dot.
 */
internal fun widgetStage(kind: WidgetKind, key: Any, feed: HomeFeed, cartridge: CartridgeStatus, time: String, accent: Long): StageInfo {
    fun info(title: String, eyebrow: String, vararg meta: String?) = StageInfo(key, title, eyebrow = eyebrow, meta = meta.filterNotNull(), accent = accent)
    val p = feed.playtime
    return when (kind) {
        WidgetKind.PLAYTIME_WEEK -> info(playtimeText(p.weekSeconds), kind.title(), "${playtimeText(p.totalSeconds)} in total", "Only time Fuse saw you play")
        WidgetKind.PLAYTIME_TOTAL -> info(playtimeText(p.totalSeconds), kind.title(), "Only time Fuse saw you play", "Imported time is kept separate")
        WidgetKind.MOST_PLAYED -> feed.mostPlayed.firstOrNull()?.let {
            info(it.title, kind.title(), "${playtimeText(it.playSeconds)} played", it.platformShort)
        } ?: info(kind.title(), "Home", "Games you play show here")
        WidgetKind.CURRENT_GAME -> p.currentGame?.let {
            info(it.title, kind.title(), it.platformShort, p.currentSince?.let { s -> "Started ${agoText(s)}" })
        } ?: info(kind.title(), "Home", "Nothing is running")
        WidgetKind.CARTRIDGE_DOWNLOADS -> {
            val now = CartridgeNow.of(cartridge)
            if (now.title == null) {
                info("Nothing downloading", kind.title(), "Downloads from your RomM server show here")
            } else {
                info(now.title, kind.title(), now.percent, now.waiting.takeIf { it > 0 }?.let { "$it more queued" })
            }
        }
        WidgetKind.STORAGE -> feed.storage?.let {
            info("${bytesText(it.freeBytes)} free", kind.title(), "of ${bytesText(it.totalBytes)}", it.label)
        } ?: info(kind.title(), kind.title(), "Add a library folder to see its space")
        WidgetKind.CLOCK -> {
            val (day, date) = todayParts()
            info(time, "Today", "$day $date")
        }
        in MediaKinds -> mediaItems(kind, feed).firstOrNull()?.let {
            info(mediaTitle(it), kind.title(), mediaCaption(it), "Jellyfin")
        } ?: info(kind.title(), "Jellyfin", "Nothing here yet")
        WidgetKind.SYNC_STATUS, WidgetKind.SYNC_DEVICES -> info(kind.title(), "Fuse Sync", "Your saves, play time and settings on every device")
        else -> feed.achievements?.recent?.firstOrNull()?.let {
            info(it.achievement.title, kind.title(), it.gameTitle, "${it.achievement.points} points")
        } ?: info(kind.title(), "Achievements", "Connect RetroAchievements in Settings")
    }
}

/** "1.2 GB", "640 MB", "12 KB": sizes as people read them (decimal units, like drives are sold). */
fun bytesText(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return when {
        // Drives are sold in terabytes: "1 TB", "1.8 TB", never "1000 GB".
        gb >= 999.5 -> (gb / 1000).let { tb -> if (tb >= 10) "${tb.toInt()} TB" else "${(tb * 10 + 0.5).toInt() / 10.0} TB".replace(".0 TB", " TB") }
        gb >= 100 -> "${gb.toInt()} GB"
        gb >= 1 -> "${(gb * 10).toInt() / 10.0} GB"
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes > 0 -> "${((bytes + 999) / 1_000).coerceAtLeast(1)} KB"
        else -> "0 KB"
    }
}

/**
 * Where a widget leads when it is opened, the same on the board and in Flow: each one opens the
 * place its numbers come from. [at] is the item a carousel shows in front (the first otherwise):
 * its game, system, collection or film is the one opened.
 */
internal fun AppState.openWidget(kind: WidgetKind, feed: HomeFeed, at: Int = 0) {
    val game = boardGames(kind, feed).let { it.getOrNull(at) ?: it.firstOrNull() }
    val media = mediaItems(kind, feed).let { it.getOrNull(at) ?: it.firstOrNull() }
    when (kind) {
        WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.PINNED_GAMES, WidgetKind.CURRENT_GAME,
        WidgetKind.MOST_PLAYED, WidgetKind.RECENTLY_ADDED,
        -> game?.let { activateGame(it) } ?: selectTab(Destination.LIBRARY)
        // Favourites opens the game in front; with none, the Library on them.
        WidgetKind.FAVORITES -> game?.let { activateGame(it) } ?: run {
            librarySegment = io.github.matiyaaa.fuse.ui.shell.library.LibrarySegment.FAVORITES
            selectTab(Destination.LIBRARY)
        }
        WidgetKind.SYSTEMS -> feed.systems.getOrNull(at)?.let { go(Route.PlatformGames(it.platform.id)) } ?: selectTab(Destination.SYSTEMS)
        WidgetKind.PINNED_APPS -> selectTab(Destination.APPS)
        WidgetKind.COLLECTIONS -> (feed.collections.getOrNull(at) ?: feed.collections.firstOrNull())?.let { go(Route.CollectionGames(it.id, it.name)) } ?: go(Route.Collections)
        WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.ACHIEVEMENT_PROGRESS,
        WidgetKind.RECENTLY_MASTERED,
        -> selectTab(Destination.ACHIEVEMENTS)
        WidgetKind.PLAYTIME_WEEK, WidgetKind.PLAYTIME_TOTAL -> go(Route.PlayTime)
        WidgetKind.CARTRIDGE_DOWNLOADS -> selectTab(Destination.CARTRIDGE)
        WidgetKind.STORAGE -> go(Route.Storage)
        WidgetKind.CLOCK -> quickMenuOpen = true
        // Something you were watching plays on; anything else opens its page.
        WidgetKind.JELLYFIN_CONTINUE, WidgetKind.JELLYFIN_NEXT_UP -> media?.let { play(it) } ?: openJellyfin()
        WidgetKind.JELLYFIN_RECENTLY_ADDED, WidgetKind.JELLYFIN_FAVORITES, WidgetKind.JELLYFIN_MOVIES, WidgetKind.JELLYFIN_MUSIC,
        -> media?.let { openMedia(it) } ?: openJellyfin()
        // Fuse Sync's widgets open its tab in Addons: the Hub, with every save and device.
        WidgetKind.SYNC_STATUS, WidgetKind.SYNC_DEVICES -> openSyncHub()
    }
}
