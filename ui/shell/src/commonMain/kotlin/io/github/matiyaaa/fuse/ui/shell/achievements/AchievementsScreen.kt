package io.github.matiyaaa.fuse.ui.shell.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.elevated
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val RECENT = "recent"
private const val PROGRESS = "progress"
private const val MASTERED = "mastered"

/**
 * RetroAchievements at a glance: your profile, what you unlocked lately, games in progress and games
 * you mastered. Anything Fuse matched to a game in your library opens that game.
 */
@Composable
fun AchievementsScreen(app: AppState) {
    val store = app.store
    val configured by store.achievements.configured.collectAsState()
    val feed by store.achievements.feed.collectAsState()
    LaunchedEffect(Unit) {
        app.hero = null
        store.achievements.refresh()
    }
    val f = feed
    if (!configured || f == null) {
        NotConnected(app, configured)
        return
    }
    Connected(app, f)
}

@Composable
private fun NotConnected(app: AppState, configured: Boolean) {
    // Connected but nothing has arrived yet: the page holds its shape. If the profile still hasn't
    // come after a while, say so and offer to ask again rather than shimmer forever.
    var slow by remember(configured) { mutableStateOf(false) }
    LaunchedEffect(configured) {
        if (configured) {
            delay(SLOW_MS)
            slow = true
        }
    }
    LaunchedEffect(configured, slow) {
        app.hints = when {
            !configured -> listOf(Hint(HintButton.CONFIRM, "Connect"))
            slow -> listOf(Hint(HintButton.CONFIRM, "Try again"))
            else -> emptyList()
        }
    }
    fun retry() {
        slow = false
        app.store.achievements.refresh(force = true)
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.SELECT -> when {
                !configured -> { app.go(Route.Settings("accounts")); NavResult.ACTIVATED }
                slow -> { retry(); NavResult.ACTIVATED }
                else -> NavResult.BLOCKED
            }
            NavAction.LEFT, NavAction.RIGHT, NavAction.DOWN -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }
    val c = Fuse.colors
    if (configured && !slow) {
        AchievementsLoading()
        return
    }
    val focused = app.focusZone == FocusZone.CONTENT
    Box(
        Modifier.fillMaxSize().padding(horizontal = Space.gutter).padding(top = Size.hudHeight, bottom = Size.hintHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (configured) {
            EmptyState(
                FuseIcons.CloudOff,
                "RetroAchievements hasn't answered",
                message = "Your profile hasn't arrived yet. Check that this device is online, then try again.",
                actionLabel = "Try again",
                actionSelected = focused,
                actionIcon = FuseIcons.Refresh,
                onAction = ::retry,
            )
        } else {
            EmptyState(
                FuseIcons.Trophy,
                "Connect RetroAchievements",
                message = "See your recent unlocks, games in progress and mastered sets here and on each game's page. " +
                    "You need your username and web API key from retroachievements.org, Settings.",
                tint = c.accent,
                actionLabel = "Connect",
                actionSelected = focused,
                actionIcon = FuseIcons.Link,
                onAction = { app.go(Route.Settings("accounts")) },
            )
        }
    }
}

/** The page's shape while the profile loads: the profile line and a row of cards, shimmering. */
@Composable
private fun AchievementsLoading() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val badge = badgeSize(maxHeight)
        // Only whole cards, so the placeholder row never ends in a sliver.
        val cards = ((maxWidth - Space.gutter * 2 + Space.xl) / (badge * CARD_WIDTH + Space.xl)).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxSize().padding(top = Size.hudHeight + Space.m)) {
            Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                Skeleton(Modifier.size(AVATAR), shape = CircleShape)
                Spacer(Modifier.width(Space.l))
                Column(Modifier.width(badge * 3)) {
                    SkeletonText(lines = 1, style = Fuse.type.title, lastLineFraction = 0.6f)
                    Spacer(Modifier.height(Space.s))
                    SkeletonText(lines = 1, style = Fuse.type.body, modifier = Modifier.fillMaxWidth(0.8f))
                }
            }
            Spacer(Modifier.height(Space.xxl))
            repeat(2) {
                Skeleton(Modifier.padding(horizontal = Space.gutter).width(badge).height(Space.m))
                Spacer(Modifier.height(Space.m))
                Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    repeat(cards) {
                        Row(Modifier.width(badge * CARD_WIDTH), verticalAlignment = Alignment.CenterVertically) {
                            Skeleton(Modifier.size(badge), shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction))
                            Spacer(Modifier.width(Space.m))
                            SkeletonText(Modifier.weight(1f), lines = 2, style = Fuse.type.bodyStrong)
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl + Size.sparkClearance))
            }
        }
    }
}

@Composable
private fun Connected(app: AppState, feed: AchievementsFeed) {
    val rows = buildList {
        if (feed.recent.isNotEmpty()) add(RECENT)
        if (feed.inProgress.isNotEmpty()) add(PROGRESS)
        if (feed.recentlyMastered.isNotEmpty()) add(MASTERED)
    }
    fun sizeOf(key: String) = when (key) {
        RECENT -> feed.recent.size
        PROGRESS -> feed.inProgress.size
        MASTERED -> feed.recentlyMastered.size
        else -> 0
    }
    val sel = rememberRouteState(app.navigator, "achievements") { ShelfSelection() }
    sel.clamp(rows, ::sizeOf)
    val rowKey = rows.getOrNull(sel.row)
    val col = rowKey?.let(sel::column) ?: 0

    // Games Fuse matched to the library, by RetroAchievements game id.
    val local: Map<Long, GameId> = feed.recent.mapNotNull { r -> r.localGameId?.let { r.achievement.gameId to it } }.toMap()

    fun open(raGameId: Long, title: String) {
        val id = local[raGameId]
        if (id != null) app.go(Route.GameInfo(id)) else app.toasts.show("$title isn't matched to a game in your library")
    }

    fun activate() {
        when (rowKey) {
            RECENT -> feed.recent.getOrNull(col)?.let { open(it.achievement.gameId, it.gameTitle) }
            PROGRESS -> feed.inProgress.getOrNull(col)?.let { open(it.raGameId, it.title) }
            MASTERED -> feed.recentlyMastered.getOrNull(col)?.let { open(it.raGameId, it.title) }
        }
    }

    // Open game only while there is something to open.
    val any = rows.isNotEmpty()
    LaunchedEffect(any) {
        app.hints = listOfNotNull(Hint(HintButton.CONFIRM, "Open game").takeIf { any }, Hint(HintButton.OPTIONS, "Refresh"))
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf)
            NavAction.SELECT -> { activate(); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { app.store.achievements.refresh(force = true); app.toasts.show("Refreshing achievements"); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val badge = badgeSize(maxHeight)
        val list = rememberLazyListState()
        val reveal = rememberReveal(Unit)
        FollowSelection(list, { sel.row }, anchor = 0f)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            Profile(feed, Modifier.padding(horizontal = Space.gutter).reveal(reveal, 0))
            Spacer(Modifier.height(Space.l))
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter).padding(bottom = Size.hintHeight + Space.xl), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.Trophy,
                        "No unlocks yet",
                        message = "Play a game with RetroAchievements turned on in your emulator, and your unlocks show up here.",
                        modifier = Modifier.reveal(reveal, 1),
                    )
                }
                return@Column
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().weight(1f).fadingEdges(list, top = Space.xl, bottom = 0.dp),
                contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(rows, key = { _, k -> k }) { index, key ->
                    val active = index == sel.row && app.focusZone == FocusZone.CONTENT
                    val selectedCol = if (active) sel.column(key) else -1
                    val m = Modifier.reveal(reveal, index + 1)
                    when (key) {
                        RECENT -> Section("Recent unlocks", sel.column(key), feed.recent, m) { i, r ->
                            UnlockCard(r, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(r.achievement.gameId, r.gameTitle) }
                        }
                        PROGRESS -> Section("In progress", sel.column(key), feed.inProgress, m) { i, s ->
                            GameProgressCard(s, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(s.raGameId, s.title) }
                        }
                        MASTERED -> Section("Mastered", sel.column(key), feed.recentlyMastered, m) { i, s ->
                            GameProgressCard(s, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(s.raGameId, s.title) }
                        }
                    }
                }
            }
        }
    }
}

/** Badges and game icons take a fifth of the height, within what reads on a handheld and a TV. */
private fun badgeSize(height: Dp): Dp = (height * 0.2f).coerceIn(Space.x4 + Space.s, Space.x5 + Space.xl)

@Composable
private fun <T> Section(title: String, column: Int, items: List<T>, modifier: Modifier = Modifier, card: @Composable (Int, T) -> Unit) {
    val row = rememberLazyListState()
    FollowSelection(row, { column }, anchor = 0f)
    Column(modifier) {
        SectionLabel(title, Modifier.fillMaxWidth().padding(horizontal = Space.gutter), count = items.size.toString())
        Spacer(Modifier.height(Space.m))
        LazyRow(
            state = row,
            // The spark bar under a focused card stays clear of the next section.
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 3, top = Space.xs, bottom = Size.sparkClearance),
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            itemsIndexed(items) { i, item -> card(i, item) }
        }
    }
}

/**
 * Who is signed in: the avatar, the name with rank and motto, and the totals that matter (points,
 * recent unlocks and mastered sets) set large in tabular figures, divided by hairlines.
 */
@Composable
private fun Profile(feed: AchievementsFeed, modifier: Modifier) {
    val c = Fuse.colors
    val user = feed.user
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(AVATAR).elevated(Elevation.raised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (user.avatarUrl != null) Artwork(user.avatarUrl, Modifier.fillMaxSize()) else FuseIcon(FuseIcons.CircleUser, size = Size.iconXL, tint = c.textMuted)
        }
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            FText(user.username, Fuse.type.title, maxLines = 1)
            val line = listOfNotNull(user.rank?.let { "Rank ${grouped(it)}" }, user.motto?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            if (line.isNotEmpty()) {
                Spacer(Modifier.height(Space.xxs))
                FText(line, Fuse.type.body, color = c.textMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(Space.l))
        Stat(grouped(user.points), "points")
        StatDivider()
        Stat("${feed.recent.size}", "recent")
        StatDivider()
        Stat("${feed.recentlyMastered.size}", "mastered")
    }
}

@Composable
private fun StatDivider() {
    Spacer(Modifier.width(Space.xl))
    Box(Modifier.width(Size.divider).height(Space.xxl + Space.s).background(Fuse.colors.hairline))
    Spacer(Modifier.width(Space.xl))
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        FText(value, Fuse.type.title.tabular(), maxLines = 1)
        FText(label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

/** "12,480": thousands grouped, so large point totals read at a glance. */
private fun grouped(n: Long): String {
    val digits = n.toString().trimStart('-')
    val sign = if (n < 0) "-" else ""
    return sign + digits.reversed().chunked(3).joinToString(",").reversed()
}

/**
 * A recent unlock: its badge as a tile, the achievement's name, its game, and what it was worth
 * (with a Hardcore mark when it was earned that way).
 */
@Composable
private fun UnlockCard(r: RecentAchievement, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(Modifier.width(size * CARD_WIDTH), verticalAlignment = Alignment.CenterVertically) {
        Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                Artwork(r.achievement.badgeUrl.ifBlank { null }, Modifier.fillMaxSize(), fallback = {
                    FuseIcon(FuseIcons.Trophy, size = Size.iconXL, tint = c.accent)
                })
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(r.achievement.title, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 2)
            // The game gives way before the time does.
            Row {
                FText(r.gameTitle, Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                FText("  ·  ${agoText(r.earnedAt)}", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Badge("${r.achievement.points} pts", color = c.accent, filled = false)
                if (r.hardcore) Badge("Hardcore", color = c.warning, filled = false)
            }
        }
    }
}

/**
 * A game's set: its icon as a tile and how far along it is as a ring with the percentage inside, the
 * count beside it. A mastered set closes its ring in gold, with a crown.
 */
@Composable
private fun GameProgressCard(s: AchievementState, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    val ring = if (s.mastered) c.warning else c.accent
    Row(Modifier.width(size * CARD_WIDTH), verticalAlignment = Alignment.CenterVertically) {
        Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                // Without its icon a game gets generated art, like everywhere else in Fuse.
                Artwork(s.iconUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = {
                    GeneratedArt(s.title, c.accent, Modifier.fillMaxSize(), slot = ArtSlot.ICON)
                })
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(s.title, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 2)
            s.consoleName?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(s.progress, size = Size.thumb, stroke = Size.track, color = ring) {
                    if (s.mastered) FuseIcon(FuseIcons.Crown, size = Size.iconS, tint = ring)
                    else FText("${(s.progress * 100).roundToInt()}", Fuse.type.numericSmall, color = c.text, maxLines = 1)
                }
                Spacer(Modifier.width(Space.s))
                Column {
                    FText(if (s.mastered) "Mastered" else "${s.earned} of ${s.total}", Fuse.type.label, color = if (s.mastered) ring else c.text, maxLines = 1)
                    FText(if (s.mastered) "${s.total} of ${s.total}" else "achievements", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** How wide a card is, in badges: the badge, then its text. */
private const val CARD_WIDTH = 2.4f

private val AVATAR = Space.x4

/** How long the page waits for a first profile before offering to ask again. */
private const val SLOW_MS = 10_000L
