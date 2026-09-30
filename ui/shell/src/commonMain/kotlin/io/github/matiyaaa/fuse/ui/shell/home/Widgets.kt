package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed

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
    val c = Fuse.colors
    Tile(
        selected = selected,
        modifier = modifier.size(width = widgetWidth(span), height = height),
        glow = c.accent,
        onClick = onClick,
    ) {
        WidgetContent(kind, feed, cartridge, clock24h)
    }
}

/** A widget's content, without the tile around it (Channel Mode wraps it in its own tile). */
@Composable
fun WidgetContent(kind: WidgetKind, feed: HomeFeed, cartridge: CartridgeStatus, clock24h: Boolean) {
    Box(Modifier.fillMaxSize().background(Fuse.colors.surfaceRaised)) {
        Column(Modifier.fillMaxSize().padding(Space.m)) {
            when (kind) {
                WidgetKind.PLAYTIME_WEEK -> PlaytimeWeek(feed)
                WidgetKind.PLAYTIME_TOTAL -> BigNumber(WidgetKind.PLAYTIME_TOTAL.title(), playtimeText(feed.playtime.totalSeconds), "Tracked by Fuse")
                WidgetKind.MOST_PLAYED -> MostPlayed(feed)
                WidgetKind.CURRENT_GAME -> CurrentGame(feed)
                WidgetKind.RECENT_ACHIEVEMENT -> RecentAchievement(feed)
                WidgetKind.RECENT_ACHIEVEMENTS -> RecentAchievements(feed)
                WidgetKind.ACHIEVEMENT_PROGRESS -> AchievementProgress(feed)
                WidgetKind.RECENTLY_MASTERED -> RecentlyMastered(feed)
                WidgetKind.CARTRIDGE_DOWNLOADS -> CartridgeWidget(cartridge)
                WidgetKind.STORAGE -> StorageWidget(feed)
                WidgetKind.CLOCK -> ClockWidget(clock24h)
                else -> SectionLabel(kind.title())
            }
        }
    }
}

@Composable
private fun ColumnScope.BigNumber(label: String, value: String, caption: String?) {
    SectionLabel(label)
    Spacer(Modifier.weight(1f))
    FText(value, Fuse.type.title, maxLines = 1)
    if (caption != null) FText(caption, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
}

@Composable
private fun ColumnScope.PlaytimeWeek(feed: HomeFeed) {
    val c = Fuse.colors
    val days = feed.playtime.lastSevenDays.ifEmpty { List(7) { 0L } }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            SectionLabel("This week")
            Spacer(Modifier.height(Space.xs))
            FText(playtimeText(feed.playtime.weekSeconds), Fuse.type.title, maxLines = 1)
        }
    }
    Spacer(Modifier.weight(1f))
    val max = (days.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (days.size - 1)) / days.size
        days.forEachIndexed { i, v ->
            val h = (size.height * (v.toFloat() / max)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = if (i == days.lastIndex) c.accent else c.text.copy(alpha = 0.22f),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ColumnScope.MostPlayed(feed: HomeFeed) {
    val c = Fuse.colors
    SectionLabel("Most played")
    Spacer(Modifier.height(Space.s))
    val top = feed.mostPlayed.take(3)
    val max = (top.maxOfOrNull { it.playSeconds } ?: 1L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        for (g in top) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(g.title, Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                FText(playtimeText(g.playSeconds), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            ProgressBar(g.playSeconds.toFloat() / max, Modifier.fillMaxWidth(), height = 3.dp)
        }
    }
}

@Composable
private fun ColumnScope.CurrentGame(feed: HomeFeed) {
    val g = feed.playtime.currentGame ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Artwork(
            g.art.icon ?: g.art.boxart,
            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
            fallback = { GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.ICON) },
        )
        Column {
            SectionLabel("Now playing")
            FText(g.title, Fuse.type.bodyStrong, maxLines = 2)
        }
    }
}

@Composable
private fun ColumnScope.RecentAchievement(feed: HomeFeed) {
    val a = feed.achievements?.recent?.firstOrNull() ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
        Artwork(a.achievement.badgeUrl, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            SectionLabel(if (a.hardcore) "Unlocked · Hardcore" else "Unlocked")
            FText(a.achievement.title, Fuse.type.bodyStrong, maxLines = 2)
            FText("${a.gameTitle} · ${a.achievement.points} pts", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.RecentAchievements(feed: HomeFeed) {
    val list = feed.achievements?.recent.orEmpty().take(6)
    SectionLabel("Recent achievements")
    Spacer(Modifier.height(Space.s))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        for (a in list) {
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.achievement.badgeUrl, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.height(Space.xs))
                FText(a.achievement.title, Fuse.type.caption, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ColumnScope.AchievementProgress(feed: HomeFeed) {
    val s = feed.achievements?.inProgress?.firstOrNull() ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            ProgressRing(s.progress, size = 60.dp, stroke = 5.dp)
            FText("${(s.progress * 100).toInt()}%", Fuse.type.label)
        }
        Column(Modifier.weight(1f)) {
            SectionLabel("In progress")
            FText(s.title, Fuse.type.bodyStrong, maxLines = 2)
            FText("${s.earned} of ${s.total}", Fuse.type.caption, color = Fuse.colors.textMuted)
        }
    }
}

@Composable
private fun ColumnScope.RecentlyMastered(feed: HomeFeed) {
    val s = feed.achievements?.recentlyMastered?.firstOrNull() ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
        Artwork(s.iconUrl, Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                FuseIcon(FuseIcons.Award, size = 14.dp, tint = Fuse.colors.warning)
                SectionLabel("Mastered", color = Fuse.colors.warning)
            }
            FText(s.title, Fuse.type.bodyStrong, maxLines = 2)
        }
    }
}

@Composable
private fun ColumnScope.CartridgeWidget(status: CartridgeStatus) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FuseIcon(FuseIcons.CloudDownload, size = 16.dp, tint = c.textMuted)
        SectionLabel("Cartridge")
    }
    Spacer(Modifier.weight(1f))
    when {
        !status.installed -> {
            FText("Get games from RomM", Fuse.type.bodyStrong, maxLines = 1)
            FText("Install Cartridge", Fuse.type.caption, color = c.accent, maxLines = 1)
        }
        status.activeDownloads > 0 -> {
            FText(status.currentTitle ?: "Downloading", Fuse.type.bodyStrong, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            ProgressBar(status.progress, Modifier.fillMaxWidth())
            if (status.queuedDownloads > 0) FText("${status.queuedDownloads} more queued", Fuse.type.caption, color = c.textMuted)
        }
        else -> {
            FText(if (status.recent.isNotEmpty()) "Latest: ${status.recent.first().title}" else "No downloads", Fuse.type.bodyStrong, maxLines = 1)
            FText(
                when (status.connected) { true -> "Connected to RomM"; false -> "Not connected"; null -> if (status.bridge) "Open to connect" else "Update Cartridge for live status" },
                Fuse.type.caption, color = c.textMuted, maxLines = 1,
            )
        }
    }
}

@Composable
private fun ColumnScope.StorageWidget(feed: HomeFeed) {
    val s = feed.storage ?: return
    val used = 1f - s.freeBytes.toFloat() / s.totalBytes.coerceAtLeast(1)
    SectionLabel("Storage")
    Spacer(Modifier.weight(1f))
    FText("${bytesText(s.freeBytes)} free", Fuse.type.bodyStrong, maxLines = 1)
    Spacer(Modifier.height(Space.xs))
    ProgressBar(used, Modifier.fillMaxWidth(), color = if (used > 0.9f) Fuse.colors.warning else Fuse.colors.text)
    FText(s.label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
}

@Composable
private fun ColumnScope.ClockWidget(clock24h: Boolean) {
    val time = rememberClockText(clock24h)
    Spacer(Modifier.weight(1f))
    FText(time, Fuse.type.numericLarge, maxLines = 1)
    FText(formatDate(), Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
}

fun bytesText(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return when {
        gb >= 100 -> "${gb.toInt()} GB"
        gb >= 1 -> "${(gb * 10).toInt() / 10.0} GB"
        else -> "${bytes / 1_000_000} MB"
    }
}

