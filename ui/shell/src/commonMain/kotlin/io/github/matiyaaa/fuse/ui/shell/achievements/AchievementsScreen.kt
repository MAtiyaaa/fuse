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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
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
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import androidx.compose.foundation.gestures.Orientation

private const val RECENT = "recent"
private const val PROGRESS = "progress"
private const val COMPLETED = "completed"
private const val CONNECT = "connect"
private const val WEEK = "week"
private const val PEOPLE = "people"

/**
 * Every achievement in one place: RetroAchievements when it is set up, and what this device keeps
 * for the library's games (PS3, PS4, Vita and Xbox 360 trophies, Steam achievements). One row of
 * recent unlocks and one shelf each of games in progress and completed, mixed by time, each item
 * marked with where it is from. Anything Fuse knows in the library opens that game. With
 * RetroAchievements, the achievement of the week and the people the person follows too.
 */
@Composable
fun AchievementsScreen(app: AppState) {
    val store = app.store
    val configured by store.achievements.configured.collectAsState()
    val feed by store.achievements.feed.collectAsState()
    val local by store.achievements.local.collectAsState()
    PageEffect(Unit) {
        app.hero = null
        store.achievements.refresh()
    }
    // RetroAchievements set up but its profile not here yet: it is waited for a while, then left out.
    var waited by remember(configured) { mutableStateOf(false) }
    PageEffect(configured) {
        if (configured) {
            delay(SLOW_MS)
            waited = true
        }
    }
    val unified = remember(feed, local) { UnifiedAchievements.of(feed, local.orEmpty()) }
    // RetroAchievements' week and the people followed: asked as the page opens, each cached for a while.
    val week by produceState<io.github.matiyaaa.fuse.ui.shell.store.WeekAchievement?>(null, configured) {
        value = if (configured) runCatching { store.achievements.week() }.getOrNull() else null
    }
    val following by produceState(emptyList<io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer>(), configured) {
        value = if (configured) runCatching { store.achievements.following() }.getOrDefault(emptyList()) else emptyList()
    }
    val loading = local == null || (configured && feed == null && !waited && unified.empty)
    if (loading) {
        AchievementsLoading()
        return
    }
    Achievements(app, unified, configured, raAnswered = feed != null, week = week, following = following)
}

/** The page's shape while the sets load: the header and a row of cards, shimmering. */
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
private fun Achievements(
    app: AppState,
    all: UnifiedAchievements,
    configured: Boolean,
    raAnswered: Boolean,
    week: io.github.matiyaaa.fuse.ui.shell.store.WeekAchievement?,
    following: List<io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer>,
) {
    val rows = buildList {
        if (all.recent.isNotEmpty()) add(RECENT)
        if (week != null) add(WEEK)
        if (all.inProgress.isNotEmpty()) add(PROGRESS)
        if (following.isNotEmpty()) add(PEOPLE)
        if (all.completed.isNotEmpty()) add(COMPLETED)
        // RetroAchievements is offered, never a gate: a quiet row at the end.
        if (!configured || !raAnswered) add(CONNECT)
    }
    fun sizeOf(key: String) = when (key) {
        RECENT -> all.recent.size
        PROGRESS -> all.inProgress.size
        COMPLETED -> all.completed.size
        WEEK -> 1
        PEOPLE -> following.size
        CONNECT -> 1
        else -> 0
    }
    val sel = rememberRouteState(app.navigator, "achievements") { ShelfSelection() }
    sel.clamp(rows, ::sizeOf)
    val rowKey = rows.getOrNull(sel.row)
    val col = rowKey?.let(sel::column) ?: 0

    fun open(gameId: GameId?, title: String) {
        if (gameId != null) app.go(Route.GameInfo(gameId)) else app.toasts.show("$title isn't matched to a game in your library")
    }
    fun connect() {
        if (configured) {
            app.store.achievements.refresh(force = true)
            app.toasts.show("Asking RetroAchievements again")
        } else {
            app.go(Route.Settings("accounts"))
        }
    }
    fun activate() {
        when (rowKey) {
            RECENT -> all.recent.getOrNull(col)?.let { open(it.gameId, it.game) }
            PROGRESS -> all.inProgress.getOrNull(col)?.let { open(it.gameId, it.state.title) }
            COMPLETED -> all.completed.getOrNull(col)?.let { open(it.gameId, it.state.title) }
            WEEK -> week?.let { open(it.localGame, it.game) }
            PEOPLE -> following.getOrNull(col)?.let { app.platform.openUrl("https://retroachievements.org/user/${it.name}") }
            CONNECT -> connect()
        }
    }

    PageEffect(rowKey) {
        app.hints = listOfNotNull(
            when (rowKey) {
                null -> null
                CONNECT -> Hint(HintButton.CONFIRM, if (configured) "Try again" else "Connect")
                WEEK -> if (week?.localGame != null) Hint(HintButton.CONFIRM, "Open game") else null
                PEOPLE -> Hint(HintButton.CONFIRM, "Their profile")
                else -> Hint(HintButton.CONFIRM, "Open game")
            },
            Hint(HintButton.OPTIONS, "Refresh"),
        )
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
            Header(all, Modifier.padding(horizontal = Space.gutter).reveal(reveal, 0))
            Spacer(Modifier.height(Space.l))
            if (all.empty) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter).padding(bottom = Size.hintHeight + Space.xl), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.Trophy,
                        "No achievements yet",
                        message = "Fuse gathers them here from RetroAchievements and from the trophies RPCS3, shadPS4, Vita3K and Xenia keep, " +
                            "and Steam's achievements. Play a game, and what you unlock shows up here.",
                        modifier = Modifier.reveal(reveal, 1),
                        actionLabel = when {
                            !configured -> "Connect RetroAchievements"
                            !raAnswered -> "Ask RetroAchievements again"
                            else -> null
                        },
                        actionSelected = app.focusZone == FocusZone.CONTENT && rowKey == CONNECT,
                        actionIcon = if (configured) FuseIcons.Refresh else FuseIcons.Link,
                        onAction = ::connect,
                    )
                }
                return@Column
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().weight(1f).fadingEdges(list, top = Space.xl, bottom = 0.dp).dragScroll(list),
                contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(rows, key = { _, k -> k }) { index, key ->
                    val active = index == sel.row && app.focusZone == FocusZone.CONTENT
                    val selectedCol = if (active) sel.column(key) else -1
                    val m = Modifier.reveal(reveal, index + 1)
                    when (key) {
                        RECENT -> Section("Recently unlocked", sel.column(key), all.recent, m) { i, r ->
                            UnlockCard(r, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(r.gameId, r.game) }
                        }
                        PROGRESS -> Section("In progress", sel.column(key), all.inProgress, m) { i, g ->
                            GameProgressCard(g, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(g.gameId, g.state.title) }
                        }
                        COMPLETED -> Section("Completed", sel.column(key), all.completed, m) { i, g ->
                            GameProgressCard(g, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(g.gameId, g.state.title) }
                        }
                        WEEK -> week?.let { w ->
                            Section("Achievement of the week", 0, listOf(w), m) { _, it ->
                                WeekCard(it, selectedCol == 0, badge) { sel.row = index; open(it.localGame, it.game) }
                            }
                        }
                        PEOPLE -> Section("People you follow", sel.column(key), following, m) { i, p ->
                            PersonCard(p, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); app.platform.openUrl("https://retroachievements.org/user/${p.name}") }
                        }
                        CONNECT -> ConnectRow(configured, selected = active, modifier = m.padding(horizontal = Space.gutter), onClick = { sel.row = index; connect() })
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
            modifier = Modifier.dragScroll(row, Orientation.Horizontal),
            // The spark bar under a focused card stays clear of the next section.
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 3, top = Space.xs, bottom = Size.sparkClearance),
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            itemsIndexed(items) { i, item -> card(i, item) }
        }
    }
}

/**
 * Who you are and what you've earned everywhere: the RetroAchievements profile when it is set up,
 * then the totals across every source set large, and under them what each source adds.
 */
@Composable
private fun Header(all: UnifiedAchievements, modifier: Modifier) {
    val c = Fuse.colors
    val user = all.user
    val t = all.totals
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(AVATAR).elevated(Elevation.raised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                user?.avatarUrl != null -> Artwork(user.avatarUrl, Modifier.fillMaxSize())
                user != null -> FuseIcon(FuseIcons.CircleUser, size = Size.iconXL, tint = c.textMuted)
                else -> FuseIcon(FuseIcons.Trophy, size = Size.iconL, tint = c.accent)
            }
        }
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            FText(user?.username ?: "Achievements", Fuse.type.title, maxLines = 1)
            val parts = listOfNotNull(
                t.raPoints?.let { "${grouped(it)} RetroAchievements points" },
                if (t.trophies > 0) listOfNotNull(
                    t.platinum.takeIf { it > 0 }?.let { "$it platinum" },
                    t.gold.takeIf { it > 0 }?.let { "$it gold" },
                    t.silver.takeIf { it > 0 }?.let { "$it silver" },
                    t.bronze.takeIf { it > 0 }?.let { "$it bronze" },
                ).joinToString(", ") else null,
                t.gamerscore.takeIf { it > 0 }?.let { "${grouped(it.toLong())} gamerscore" },
                t.steam.takeIf { it > 0 }?.let { "$it on Steam" },
            )
            val line = parts.ifEmpty { listOf("Everything you unlock, wherever it's from") }.joinToString("  ·  ")
            Spacer(Modifier.height(Space.xxs))
            FText(line, Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.l))
        Stat(grouped(t.unlocked.toLong()), "unlocked")
        StatDivider()
        Stat("${t.completed}", "completed")
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

/** "12,480": thousands grouped, so large totals read at a glance. */
internal fun grouped(n: Long): String {
    val digits = n.toString().trimStart('-')
    val sign = if (n < 0) "-" else ""
    return sign + digits.reversed().chunked(3).joinToString(",").reversed()
}

/** Where an item is from, small and quiet beside what it's worth. */
@Composable
private fun OriginMark(origin: AchievementOrigin) {
    Badge(origin.mark, color = Fuse.colors.textMuted, filled = false)
}

/**
 * A recent unlock: its badge as a tile, the achievement's name, its game and when, and what it was
 * worth (points, a trophy's grade, gamerscore), marked with where it is from.
 */
@Composable
private fun UnlockCard(r: UnlockEntry, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(Modifier.width(size * CARD_WIDTH), verticalAlignment = Alignment.CenterVertically) {
        Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                Artwork(r.badge, Modifier.fillMaxSize(), fallback = {
                    FuseIcon(FuseIcons.Trophy, size = Size.iconXL, tint = c.accent)
                })
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(r.title, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 2)
            // The game gives way before the time does.
            Row {
                FText(r.game, Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                FText("  ·  ${agoText(r.earnedAt)}", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OriginMark(r.origin)
                r.worth?.let { Badge(it, color = gradeColor(it) ?: c.accent, filled = false) }
                if (r.hardcore) Badge("Hardcore", color = c.warning, filled = false)
            }
        }
    }
}

/**
 * The achievement of the week: its badge as a tile (in colour once the person has it), what it asks,
 * its game and system, what it is worth, and how many players have it this week.
 */
@Composable
private fun WeekCard(w: io.github.matiyaaa.fuse.ui.shell.store.WeekAchievement, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(Modifier.width(size * CARD_WIDTH * 2), verticalAlignment = Alignment.CenterVertically) {
        Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                // Without the badge (not asked yet, or offline), the game's art as everywhere else in Fuse.
                Artwork(w.badgeUrl ?: w.gameIconUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = {
                    GeneratedArt(w.game, c.accent, Modifier.fillMaxSize(), slot = ArtSlot.ICON)
                })
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(w.title, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 1)
            FText(w.description, Fuse.type.caption, color = c.textMuted, maxLines = 2)
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Badge("${w.points} points", color = c.accent, filled = false)
                if (w.earned) Badge("Yours", color = c.success, filled = false)
                FText(
                    listOf(w.game, w.console).filter { it.isNotBlank() }.joinToString("  ·  "),
                    Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (w.players > 0) {
                Spacer(Modifier.height(Space.xxs))
                FText("${grouped(w.unlocks.toLong())} of ${grouped(w.players.toLong())} players have it this week", Fuse.type.caption, color = c.textFaint, maxLines = 1)
            }
        }
    }
}

/** Someone the person follows: their picture as a tile, their name and points, and what they last played. */
@Composable
private fun PersonCard(p: io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(Modifier.width(size * CARD_WIDTH), verticalAlignment = Alignment.CenterVertically) {
        Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                Artwork(p.avatarUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = {
                    GeneratedArt(p.name, c.accent, Modifier.fillMaxSize(), slot = ArtSlot.ICON)
                })
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(p.name, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 1)
            FText(
                "${grouped(p.points)} points" + if (p.followsYou) "  ·  follows you" else "",
                Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1,
            )
            (p.presence ?: p.game)?.let {
                Spacer(Modifier.height(Space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.Gamepad, size = Size.iconXS, tint = c.accent)
                    Spacer(Modifier.width(Space.xs))
                    FText(it, Fuse.type.caption, color = c.text, maxLines = 2)
                }
            }
        }
    }
}

/** A trophy's grade in its own colour; null for anything else (points, gamerscore). */
@Composable
private fun gradeColor(worth: String): androidx.compose.ui.graphics.Color? = when (worth) {
    "Platinum" -> PLATINUM
    "Gold" -> Fuse.colors.warning
    "Silver" -> SILVER
    "Bronze" -> BRONZE
    else -> null
}

/**
 * A game's set: its icon as a tile and how far along it is as a ring with the percentage inside, the
 * count beside it and where it is from. A completed set closes its ring in gold, with a crown.
 */
@Composable
private fun GameProgressCard(g: GameEntry, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    val s = g.state
    val done = g.completed
    val ring = if (done) c.warning else c.accent
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OriginMark(g.origin)
                s.consoleName?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
            }
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(s.progress, size = Size.thumb, stroke = Size.track, color = ring) {
                    if (done) FuseIcon(FuseIcons.Crown, size = Size.iconS, tint = ring)
                    else FText("${(s.progress * 100).roundToInt()}", Fuse.type.numericSmall, color = c.text, maxLines = 1)
                }
                Spacer(Modifier.width(Space.s))
                Column {
                    val noun = if (s.source.noun == "Trophies") "trophies" else "achievements"
                    FText(if (done) (if (g.origin == AchievementOrigin.RETRO) "Mastered" else "Completed") else "${s.earned} of ${s.total}", Fuse.type.label, color = if (done) ring else c.text, maxLines = 1)
                    FText(if (done) "${s.total} of ${s.total}" else noun, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** RetroAchievements, offered quietly at the end: connect it, or ask it again when it hasn't answered. */
@Composable
private fun ConnectRow(configured: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton(
            if (configured) "Ask RetroAchievements again" else "Connect RetroAchievements",
            selected = selected,
            icon = if (configured) FuseIcons.Refresh else FuseIcons.Link,
            onClick = onClick,
        )
        Spacer(Modifier.width(Space.l))
        FText(
            if (configured) "Your RetroAchievements profile hasn't arrived. Check this device is online."
            else "Add your RetroAchievements unlocks here too, with your username and web API key.",
            Fuse.type.caption, color = c.textMuted, maxLines = 2, modifier = Modifier.weight(1f),
        )
    }
}

/** How wide a card is, in badges: the badge, then its text. */
private const val CARD_WIDTH = 2.4f

private val AVATAR = Space.x4

/** How long the page waits for a first RetroAchievements profile before showing what it has without it. */
private const val SLOW_MS = 10_000L

private val PLATINUM = androidx.compose.ui.graphics.Color(0xFF9FB8D6)
private val SILVER = androidx.compose.ui.graphics.Color(0xFFA9B0B8)
private val BRONZE = androidx.compose.ui.graphics.Color(0xFFC0814E)
