package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.SpatialSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.packCells
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed

private const val BOARD_COLUMNS = 4

/**
 * Channel Mode: Home as a board of tiles you arrange yourself. Each channel shows something live
 * (a game's art, a clock, your week). Hold confirm (or long press) on a channel to pick it up, move
 * it with the D-pad and put it down again.
 */
@Composable
fun ChannelHome(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.library.home.collectAsState()
    val cartridge by store.cartridge.status.collectAsState()
    val widgets = prefs.home.widgets.filter { it.visible }.sortedBy { it.order }
    val cells = remember(widgets) { packCells(widgets.map { channelSpan(it).columns }, BOARD_COLUMNS) }
    val sel = rememberRouteState(app.navigator, "home.channels") { SpatialSelection() }
    sel.clamp(widgets.size)
    var carrying by remember { mutableStateOf(false) }
    val current = widgets.getOrNull(sel.index)

    LaunchedEffect(current?.id, carrying) {
        val game = current?.let { firstGame(it.kind, feed) }
        app.hero = game?.let {
            io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource(it.id, it.art.hero ?: it.art.grid, it.accent.toColor())
        }
        app.hints = if (carrying) {
            listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Put down"))
        } else {
            listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to move"), Hint(HintButton.OPTIONS, "Options"))
        }
    }

    fun swap(target: Int) {
        if (target !in widgets.indices || target == sel.index) return
        val a = widgets[sel.index]
        val b = widgets[target]
        store.updatePrefs { p ->
            p.copy(home = p.home.copy(widgets = p.home.widgets.map {
                when (it.id) {
                    a.id -> it.copy(order = b.order)
                    b.id -> it.copy(order = a.order)
                    else -> it
                }
            }))
        }
        sel.index = target
    }

    fun open(w: HomeWidget) {
        when (w.kind) {
            WidgetKind.SYSTEMS -> app.selectTab(io.github.matiyaaa.fuse.model.Destination.SYSTEMS)
            WidgetKind.PINNED_APPS -> app.selectTab(io.github.matiyaaa.fuse.model.Destination.APPS)
            WidgetKind.COLLECTIONS -> feed.collections.firstOrNull()?.let { app.go(Route.CollectionGames(it.id, it.name)) }
            WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.PINNED_GAMES, WidgetKind.CURRENT_GAME ->
                firstGame(w.kind, feed)?.let { app.play(it) }
            WidgetKind.FAVORITES, WidgetKind.RECENTLY_ADDED, WidgetKind.MOST_PLAYED ->
                app.selectTab(io.github.matiyaaa.fuse.model.Destination.LIBRARY)
            WidgetKind.CARTRIDGE_DOWNLOADS -> app.selectTab(io.github.matiyaaa.fuse.model.Destination.CARTRIDGE)
            WidgetKind.CLOCK -> app.quickMenuOpen = true
            else -> app.go(Route.Settings("achievements"))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen, longPress = true) { e ->
        if (carrying) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> { swap(sel.index - 1); NavResult.MOVED }
                NavAction.RIGHT -> { swap(sel.index + 1); NavResult.MOVED }
                NavAction.UP -> { swap((sel.index - BOARD_COLUMNS).coerceAtLeast(0)); NavResult.MOVED }
                NavAction.DOWN -> { swap((sel.index + BOARD_COLUMNS).coerceAtMost(widgets.lastIndex)); NavResult.MOVED }
                NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> { carrying = false; NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, cells)
            NavAction.SELECT -> { current?.let(::open); NavResult.ACTIVATED }
            NavAction.REORDER -> { carrying = true; NavResult.ACTIVATED }
            NavAction.CONTEXT -> { app.go(Route.Settings("home")); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = ((maxWidth - Space.gutter * 2 - Space.l * (BOARD_COLUMNS - 1)) / BOARD_COLUMNS).coerceAtMost(maxHeight * 0.3f)
        val grid = rememberLazyGridState()
        FollowSelection(grid, { sel.index }, anchor = 0.25f)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            val time = rememberClockText(prefs.clock24h)
            Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.Bottom) {
                FText(time, Fuse.type.numericLarge)
                Spacer(Modifier.size(Space.l))
                FText(formatDate(), Fuse.type.title, color = Fuse.colors.textMuted)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(BOARD_COLUMNS),
                state = grid,
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.xxl),
                horizontalArrangement = Arrangement.spacedBy(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xl),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(widgets, key = { _, w -> w.id }, span = { _, w -> GridItemSpan(channelSpan(w).columns.coerceAtMost(BOARD_COLUMNS)) }) { i, w ->
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val lift by animateFloatAsState(if (selected && carrying) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                    Box(Modifier.graphicsLayer { translationY = -10.dp.toPx() * lift; rotationZ = -1.2f * lift }) {
                        Channel(
                            widget = w,
                            feed = feed,
                            cartridge = cartridge,
                            clock24h = prefs.clock24h,
                            selected = selected,
                            height = unit * 0.78f,
                            carrying = selected && carrying,
                            onClick = {
                                if (sel.index == i) open(w) else sel.index = i
                                app.focusZone = FocusZone.CONTENT
                            },
                            onLongClick = { sel.index = i; carrying = true },
                        )
                    }
                }
            }
        }
    }
}

private fun channelSpan(w: HomeWidget): WidgetSpan = when (w.kind) {
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENT_ACHIEVEMENTS -> WidgetSpan.MEDIUM
    WidgetKind.PLAYTIME_WEEK, WidgetKind.CARTRIDGE_DOWNLOADS -> WidgetSpan.MEDIUM
    else -> WidgetSpan.SMALL
}

private fun firstGame(kind: WidgetKind, feed: HomeFeed): GameCard? = when (kind) {
    WidgetKind.CONTINUE_PLAYING -> feed.continuePlaying.firstOrNull()
    WidgetKind.RECENTLY_PLAYED -> feed.recentlyPlayed.firstOrNull()
    WidgetKind.FAVORITES -> feed.favorites.firstOrNull()
    WidgetKind.RECENTLY_ADDED -> feed.recentlyAdded.firstOrNull()
    WidgetKind.PINNED_GAMES -> feed.pinnedGames.firstOrNull()
    WidgetKind.MOST_PLAYED -> feed.mostPlayed.firstOrNull()
    WidgetKind.CURRENT_GAME -> feed.playtime.currentGame
    else -> null
}

@Composable
private fun Channel(
    widget: HomeWidget,
    feed: HomeFeed,
    cartridge: io.github.matiyaaa.fuse.model.CartridgeStatus,
    clock24h: Boolean,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    carrying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Fuse.colors
    val kind = widget.kind
    val game = firstGame(kind, feed)
    val gameShelf = kind in setOf(
        WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.FAVORITES, WidgetKind.RECENTLY_ADDED,
        WidgetKind.PINNED_GAMES, WidgetKind.MOST_PLAYED,
    )
    Column {
        Tile(
            selected = selected,
            modifier = Modifier.fillMaxWidth().height(height)
                .then(if (carrying) Modifier.border(2.dp, c.accent, io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.6f)) else Modifier),
            cornerFraction = Fuse.geometry.tileCornerFraction * 0.6f,
            shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.6f),
            glow = game?.accent?.toColor() ?: c.accent,
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            when {
                gameShelf && game != null -> {
                    Artwork(
                        game.art.hero ?: game.art.grid ?: game.art.boxart,
                        Modifier.fillMaxSize(),
                        fallback = { GeneratedArt(game.title, game.accent.toColor(), slot = ArtSlot.WIDE, label = game.platformShort) },
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                    FText(game.title, Fuse.type.bodyStrong, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(Space.m))
                }
                kind == WidgetKind.SYSTEMS -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
                    Column {
                        SectionLabel("Systems")
                        Spacer(Modifier.height(Space.s))
                        FText("${feed.systems.size}", Fuse.type.numericLarge)
                        FText(feed.systems.take(4).joinToString("  ") { it.platform.shortName }, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    }
                }
                kind == WidgetKind.PINNED_APPS -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
                    Column {
                        SectionLabel("Apps")
                        Spacer(Modifier.height(Space.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            for (a in feed.pinnedApps.take(4)) Artwork(a.icon, Modifier.size(36.dp))
                        }
                    }
                }
                kind == WidgetKind.COLLECTIONS -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
                    Column {
                        SectionLabel("Collections")
                        Spacer(Modifier.height(Space.s))
                        for (col in feed.collections.take(3)) FText(col.name, Fuse.type.bodyStrong, maxLines = 1)
                    }
                }
                else -> WidgetContent(kind, feed, cartridge, clock24h)
            }
        }
        Spacer(Modifier.height(Space.s + Space.xs))
        FText(kind.title(), Fuse.type.label, color = if (selected) c.text else c.textMuted, maxLines = 1)
    }
}

