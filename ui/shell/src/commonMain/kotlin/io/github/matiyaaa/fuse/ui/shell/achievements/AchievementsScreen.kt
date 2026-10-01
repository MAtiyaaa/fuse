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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed

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
    LaunchedEffect(configured) {
        app.hints = if (configured) emptyList() else listOf(Hint(HintButton.CONFIRM, "Connect"))
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.SELECT -> if (!configured) { app.go(Route.Settings("achievements")); NavResult.ACTIVATED } else NavResult.BLOCKED
            NavAction.LEFT, NavAction.RIGHT, NavAction.DOWN -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }
    val c = Fuse.colors
    Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Trophy, size = 32.dp, tint = c.accent)
            }
            Spacer(Modifier.height(Space.l))
            if (configured) {
                FText("Loading your achievements", Fuse.type.title, align = TextAlign.Center)
                Spacer(Modifier.height(Space.s))
                FText("Fuse is asking RetroAchievements for your profile.", Fuse.type.body, color = c.textMuted, align = TextAlign.Center)
            } else {
                FText("Connect RetroAchievements", Fuse.type.title, align = TextAlign.Center)
                Spacer(Modifier.height(Space.s))
                FText(
                    "See your recent unlocks, games in progress and mastered sets here and on each game's page. You need your username and web API key from retroachievements.org, Settings.",
                    Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 5,
                )
                Spacer(Modifier.height(Space.xl))
                FuseButton("Connect", selected = true, kind = ButtonKind.PRIMARY, icon = FuseIcons.Link, onClick = { app.go(Route.Settings("achievements")) })
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

    LaunchedEffect(Unit) {
        app.hints = listOf(Hint(HintButton.CONFIRM, "Open game"), Hint(HintButton.OPTIONS, "Refresh"))
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
        val badge = (maxHeight * 0.2f).coerceIn(72.dp, 120.dp)
        val list = rememberLazyListState()
        FollowSelection(list, { sel.row }, anchor = 0f)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            Profile(feed, Modifier.padding(horizontal = Space.gutter))
            Spacer(Modifier.height(Space.l))
            if (rows.isEmpty()) {
                FText("Play a game with RetroAchievements turned on in your emulator, and your unlocks show up here.", Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().weight(1f).fadingEdges(top = if (list.canScrollBackward) 24.dp else 0.dp),
                contentPadding = PaddingValues(bottom = Size.hintHeight + Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(rows, key = { _, k -> k }) { index, key ->
                    val active = index == sel.row && app.focusZone == FocusZone.CONTENT
                    val selectedCol = if (active) sel.column(key) else -1
                    when (key) {
                        RECENT -> Section("Recent unlocks", sel.column(key), feed.recent) { i, r ->
                            UnlockCard(r, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(r.achievement.gameId, r.gameTitle) }
                        }
                        PROGRESS -> Section("In progress", sel.column(key), feed.inProgress) { i, s ->
                            GameProgressCard(s, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(s.raGameId, s.title) }
                        }
                        MASTERED -> Section("Mastered", sel.column(key), feed.recentlyMastered) { i, s ->
                            GameProgressCard(s, i == selectedCol, badge) { sel.row = index; sel.setColumn(key, i); open(s.raGameId, s.title) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Section(title: String, column: Int, items: List<T>, card: @Composable (Int, T) -> Unit) {
    val row = rememberLazyListState()
    FollowSelection(row, { column }, anchor = 0f)
    Column {
        SectionLabel(title, Modifier.fillMaxWidth().padding(horizontal = Space.gutter), count = items.size.toString())
        Spacer(Modifier.height(Space.m))
        LazyRow(
            state = row,
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 3, bottom = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            itemsIndexed(items) { i, item -> card(i, item) }
        }
    }
}

@Composable
private fun Profile(feed: AchievementsFeed, modifier: Modifier) {
    val c = Fuse.colors
    val user = feed.user
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            if (user.avatarUrl != null) Artwork(user.avatarUrl, Modifier.fillMaxSize()) else FuseIcon(FuseIcons.User, tint = c.textMuted)
        }
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            FText(user.username, Fuse.type.title, maxLines = 1)
            FText(
                listOfNotNull("${user.points} points", user.rank?.let { "Rank $it" }, user.motto?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                Fuse.type.body, color = c.textMuted, maxLines = 1,
            )
        }
        Stat("${feed.recent.size}", "recent")
        Spacer(Modifier.width(Space.xl))
        Stat("${feed.recentlyMastered.size}", "mastered")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        FText(value, Fuse.type.numericLarge, maxLines = 1)
        FText(label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun UnlockCard(r: RecentAchievement, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Column(Modifier.width(size * 2.2f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
                Box(Modifier.fillMaxSize().background(c.surfaceRaised)) {
                    Artwork(r.achievement.badgeUrl, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(r.achievement.title, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.85f), maxLines = 2)
                FText(r.gameTitle, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                FText("${r.achievement.points} pts  ·  ${agoText(r.earnedAt)}${if (r.hardcore) "  ·  Hardcore" else ""}", Fuse.type.caption, color = c.textFaint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GameProgressCard(s: AchievementState, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Column(Modifier.width(size * 2.2f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(selected = selected, modifier = Modifier.size(size), onClick = onClick) {
                Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                    if (s.iconUrl != null) Artwork(s.iconUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else FuseIcon(FuseIcons.Gamepad, tint = c.textMuted)
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(s.title, Fuse.type.bodyStrong, maxLines = 2)
                s.consoleName?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
                Spacer(Modifier.height(Space.s))
                ProgressBar(s.progress, Modifier.fillMaxWidth(), color = if (s.mastered) c.warning else c.accent)
                Spacer(Modifier.height(Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.mastered) {
                        FuseIcon(FuseIcons.Award, size = 14.dp, tint = c.warning)
                        Spacer(Modifier.width(Space.xs))
                    }
                    FText(if (s.mastered) "Mastered" else "${s.earned} of ${s.total}", Fuse.type.caption, color = c.textFaint, maxLines = 1)
                }
            }
        }
    }
}
