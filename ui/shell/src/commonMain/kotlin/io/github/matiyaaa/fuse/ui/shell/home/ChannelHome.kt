package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.SpatialSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.packCells
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.StageInfo
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed

private const val BOARD_COLUMNS = 4

/**
 * Channel Mode: Home as a board of tiles you arrange yourself. A spotlight across the top tells the
 * focused channel's story (its game's logo, what it holds, the covers that come next) over that
 * game's art, and each channel below is a collage of what is inside it: stacked covers, system
 * logos, app icons or a live widget. Hold confirm (or long press) on a channel to pick it up, move
 * it with the D-pad and put it down again. The time lives in the top bar.
 */
@Composable
fun ChannelHome(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.library.home.collectAsState()
    val cartridge by store.cartridge.status.collectAsState()
    // The Cartridge channel only while Cartridge support is on and it is installed.
    val widgets = prefs.home.widgets.filter { it.visible && (it.kind != WidgetKind.CARTRIDGE_DOWNLOADS || cartridge.installed) }.sortedBy { it.order }
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
                firstGame(w.kind, feed)?.let { app.activateGame(it) }
            WidgetKind.FAVORITES, WidgetKind.RECENTLY_ADDED, WidgetKind.MOST_PLAYED ->
                app.selectTab(io.github.matiyaaa.fuse.model.Destination.LIBRARY)
            WidgetKind.CARTRIDGE_DOWNLOADS -> app.selectTab(io.github.matiyaaa.fuse.model.Destination.CARTRIDGE)
            WidgetKind.CLOCK -> app.quickMenuOpen = true
            else -> app.selectTab(io.github.matiyaaa.fuse.model.Destination.ACHIEVEMENTS)
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
            NavAction.CONTEXT -> {
                app.openContextMenu(
                    ContextMenuSpec(
                        title = current?.kind?.title() ?: "Home",
                        subtitle = "Home",
                        actions = listOfNotNull(
                            current?.let { MenuAction("move", "Move this channel", FuseIcons.Move, detail = "Or hold the confirm button on it", onSelect = { app.closeOverlays(); carrying = true }) },
                        ) + app.homeStyleActions(),
                    ),
                )
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap outside the channels puts a carried one down.
            .pointerInput(carrying) { if (carrying) detectTapGestures { carrying = false } },
    ) {
        val compact = maxHeight < 560.dp
        val spotlightHeight = (maxHeight * 0.3f).coerceIn(118.dp, 250.dp)
        val unit = ((maxWidth - Space.gutter * 2 - Space.l * (BOARD_COLUMNS - 1)) / BOARD_COLUMNS).coerceAtMost(maxHeight * 0.26f)
        val grid = rememberLazyGridState()
        FollowSelection(grid, { sel.index }, anchor = 0.25f)
        val time = rememberClockText(prefs.clock24h)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            ChannelSpotlight(
                current,
                feed,
                cartridge,
                time,
                compact,
                Modifier.fillMaxWidth().height(spotlightHeight).padding(horizontal = Space.gutter),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(BOARD_COLUMNS),
                state = grid,
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = if (compact) Space.m else Space.l, bottom = Size.hintHeight + Space.xxl),
                horizontalArrangement = Arrangement.spacedBy(Space.l),
                verticalArrangement = Arrangement.spacedBy(if (compact) Space.m else Space.xl),
                modifier = Modifier.weight(1f).fadingEdges(top = if (grid.canScrollBackward) 40.dp else 0.dp),
            ) {
                itemsIndexed(widgets, key = { _, w -> w.id }, span = { _, w -> GridItemSpan(channelSpan(w).columns.coerceAtMost(BOARD_COLUMNS)) }) { i, w ->
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val lift by animateFloatAsState(if (selected && carrying) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                    Box(
                        Modifier
                            // Channels slide into their new places as one is carried past them.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            .zIndex(if (selected && carrying) 1f else 0f)
                            .graphicsLayer { translationY = -10.dp.toPx() * lift; rotationZ = -1.2f * lift },
                    ) {
                        Channel(
                            widget = w,
                            feed = feed,
                            cartridge = cartridge,
                            clock24h = prefs.clock24h,
                            selected = selected,
                            height = unit * 0.78f,
                            carrying = selected && carrying,
                            onClick = {
                                app.focusZone = FocusZone.CONTENT
                                when {
                                    carrying -> carrying = false
                                    sel.index == i -> open(w)
                                    else -> sel.index = i
                                }
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

private fun firstGame(kind: WidgetKind, feed: HomeFeed): GameCard? = channelGames(kind, feed).firstOrNull()

/** The games a channel holds, in its order; empty for channels that aren't game lists. */
private fun channelGames(kind: WidgetKind, feed: HomeFeed): List<GameCard> = when (kind) {
    WidgetKind.CONTINUE_PLAYING -> feed.continuePlaying
    WidgetKind.RECENTLY_PLAYED -> feed.recentlyPlayed
    WidgetKind.FAVORITES -> feed.favorites
    WidgetKind.RECENTLY_ADDED -> feed.recentlyAdded
    WidgetKind.PINNED_GAMES -> feed.pinnedGames
    WidgetKind.MOST_PLAYED -> feed.mostPlayed
    WidgetKind.CURRENT_GAME -> listOfNotNull(feed.playtime.currentGame)
    else -> emptyList()
}

private val gameChannels = setOf(
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.FAVORITES, WidgetKind.RECENTLY_ADDED,
    WidgetKind.PINNED_GAMES, WidgetKind.MOST_PLAYED,
)

/** What the spotlight says about a channel. */
private fun spotlightInfo(widget: HomeWidget, feed: HomeFeed, cartridge: io.github.matiyaaa.fuse.model.CartridgeStatus, time: String): StageInfo {
    val kind = widget.kind
    val key = widget.id
    val game = firstGame(kind, feed)
    if (game != null) {
        val count = channelGames(kind, feed).size
        return game.stage(extra = if (count > 1) listOf("$count games") else emptyList())
            .copy(key = "$key:${game.id.value}", eyebrow = "${kind.title()}  ·  ${game.platformShort}")
    }
    return when (kind) {
        in gameChannels, WidgetKind.CURRENT_GAME -> StageInfo(key, kind.title(), eyebrow = "Home", meta = listOf(emptyNote(kind)))
        WidgetKind.SYSTEMS -> {
            val ready = feed.systems.count { it.emulatorInstalled }
            StageInfo(
                key, "${feed.systems.size} ${if (feed.systems.size == 1) "system" else "systems"}", eyebrow = "Systems",
                meta = listOf("${feed.systems.sumOf { it.gameCount }} games", "$ready ready to play"),
            )
        }
        WidgetKind.PINNED_APPS -> StageInfo(key, "Your apps", eyebrow = "Apps", meta = listOf(if (feed.pinnedApps.isEmpty()) "Pin apps from the Apps tab" else "${feed.pinnedApps.size} pinned"))
        WidgetKind.COLLECTIONS -> {
            val first = feed.collections.firstOrNull()
            StageInfo(
                key, first?.name ?: "Collections", eyebrow = "Collections",
                meta = if (first == null) listOf("Make one from a game's options") else listOf("${first.gameCount} games", "${feed.collections.size} collections"),
            )
        }
        WidgetKind.CARTRIDGE_DOWNLOADS -> {
            val current = cartridge.queue.firstOrNull { it.state == io.github.matiyaaa.fuse.model.QueueState.DOWNLOADING }
            val title = current?.title ?: cartridge.currentTitle
            StageInfo(
                key, title ?: "Nothing downloading", eyebrow = "Cartridge",
                meta = listOfNotNull(
                    (current?.progress ?: cartridge.progress)?.let { "${(it * 100).toInt()}%" },
                    cartridge.queue.count { it.state == io.github.matiyaaa.fuse.model.QueueState.QUEUED }.takeIf { it > 0 }?.let { "$it waiting" },
                ).ifEmpty { listOf("Downloads from your RomM server show here") },
            )
        }
        WidgetKind.PLAYTIME_WEEK -> StageInfo(key, playtimeText(feed.playtime.weekSeconds), eyebrow = "This week", meta = listOf("${playtimeText(feed.playtime.totalSeconds)} in total"))
        WidgetKind.PLAYTIME_TOTAL -> StageInfo(key, playtimeText(feed.playtime.totalSeconds), eyebrow = "Total playtime", meta = listOf("Only time Fuse saw you play"))
        WidgetKind.CLOCK -> StageInfo(key, time, eyebrow = "Clock", meta = listOf(formatDate()))
        WidgetKind.STORAGE -> feed.storage?.let {
            StageInfo(key, "${bytesText(it.freeBytes)} free", eyebrow = "Storage", meta = listOf("of ${bytesText(it.totalBytes)}", it.label))
        } ?: StageInfo(key, "Storage", eyebrow = "Storage", meta = listOf("Add a library folder to see its space"))
        else -> feed.achievements?.recent?.firstOrNull()?.let {
            StageInfo(key, it.achievement.title, eyebrow = kind.title(), meta = listOfNotNull(it.gameTitle, "${it.achievement.points} points"))
        } ?: StageInfo(key, kind.title(), eyebrow = "Achievements", meta = listOf("Connect RetroAchievements in Settings"))
    }
}

private fun emptyNote(kind: WidgetKind): String = when (kind) {
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED -> "Games you play show here"
    WidgetKind.FAVORITES -> "Add favourites from a game's options"
    WidgetKind.PINNED_GAMES -> "Pin games from their options"
    WidgetKind.CURRENT_GAME -> "Nothing is running"
    else -> "Nothing here yet"
}

/**
 * The focused channel, told big: its game's logo (or its title) and details on the left, and on
 * wide screens the covers, systems or apps it holds on the right.
 */
@Composable
private fun ChannelSpotlight(
    widget: HomeWidget?,
    feed: HomeFeed,
    cartridge: io.github.matiyaaa.fuse.model.CartridgeStatus,
    time: String,
    compact: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val stripHeight = maxHeight * 0.78f
        val wide = maxWidth > 560.dp
        val logoHeight = if (compact) 56.dp else (maxHeight * 0.42f).coerceAtMost(104.dp)
        val motion = Fuse.motion
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            Stage(
                widget?.let { spotlightInfo(it, feed, cartridge, time) },
                Modifier.weight(1f),
                logoHeight = logoHeight,
            )
            if (wide && widget != null) {
                Spacer(Modifier.width(Space.l))
                AnimatedContent(
                    targetState = widget.id,
                    transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) },
                    label = "spotlight strip",
                ) { _ -> SpotlightStrip(widget.kind, feed, stripHeight) }
            }
        }
    }
}

/** What comes next in the channel: covers of the following games, system logos or app icons. */
@Composable
private fun SpotlightStrip(kind: WidgetKind, feed: HomeFeed, height: androidx.compose.ui.unit.Dp) {
    val c = Fuse.colors
    val shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.5f)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Bottom) {
        when (kind) {
            in gameChannels -> channelGames(kind, feed).drop(1).take(4).forEachIndexed { i, g ->
                Box(
                    Modifier.height(height * (1f - i * 0.08f)).aspectRatio(0.72f).clip(shape)
                        .graphicsLayer { alpha = 1f - i * 0.16f },
                ) {
                    Artwork(g.art.boxart ?: g.art.grid ?: g.art.icon, Modifier.fillMaxSize(), fallback = {
                        GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.BOX, label = g.platformShort)
                    })
                }
            }
            WidgetKind.SYSTEMS -> feed.systems.take(4).forEach { s ->
                SystemChip(s, Modifier.height(height * 0.5f).aspectRatio(1.6f).clip(shape))
            }
            WidgetKind.PINNED_APPS -> feed.pinnedApps.take(5).forEach { a ->
                Artwork(a.icon, Modifier.size(height * 0.5f).clip(shape), contentScale = ContentScale.Fit)
            }
            WidgetKind.PLAYTIME_WEEK, WidgetKind.PLAYTIME_TOTAL -> WeekBars(feed, Modifier.width(height * 1.8f).height(height * 0.62f))
            WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.RECENTLY_MASTERED, WidgetKind.ACHIEVEMENT_PROGRESS ->
                feed.achievements?.recent?.take(4)?.forEach { a ->
                    Artwork(a.achievement.badgeUrl, Modifier.size(height * 0.46f).clip(shape), fallback = {
                        Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                            io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon(FuseIcons.Trophy, tint = c.textMuted)
                        }
                    })
                }
            else -> Unit
        }
    }
}

/** A system as a small card in its colour, with its logo (or short name) in white. */
@Composable
private fun SystemChip(s: io.github.matiyaaa.fuse.ui.shell.store.PlatformCard, modifier: Modifier) {
    val accent = s.platform.accent.toColor()
    Box(modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.35f)))), contentAlignment = Alignment.Center) {
        val name: @Composable () -> Unit = { FText(s.platform.shortName, Fuse.type.bodyStrong, color = Color.White, maxLines = 1) }
        if (s.art.logo != null) {
            Artwork(s.art.logo, Modifier.fillMaxSize().padding(Space.s), contentScale = ContentScale.Fit, tint = Color.White, fallback = name)
        } else {
            name()
        }
    }
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
                gameShelf && game != null -> CoverCollage(channelGames(kind, feed), height)
                gameShelf -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
                    FText(emptyNote(kind), Fuse.type.caption, color = c.textMuted, maxLines = 3, modifier = Modifier.align(Alignment.BottomStart))
                }
                kind == WidgetKind.SYSTEMS -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.s)) {
                    // Up to four systems, each a small card in its colour.
                    val systems = feed.systems.take(4)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        systems.chunked(2).forEach { row ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                row.forEach { SystemChip(it, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Fuse.geometry.control))) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                kind == WidgetKind.PINNED_APPS -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m), contentAlignment = Alignment.Center) {
                    val apps = feed.pinnedApps.take(4)
                    if (apps.isEmpty()) {
                        FText("Pin apps from the Apps tab", Fuse.type.caption, color = c.textMuted, maxLines = 2)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                            apps.forEach { a -> Artwork(a.icon, Modifier.size(height * 0.36f), contentScale = ContentScale.Fit) }
                        }
                    }
                }
                kind == WidgetKind.COLLECTIONS -> Box(Modifier.fillMaxSize().background(c.surfaceRaised).padding(Space.m)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (feed.collections.isEmpty()) FText("Make one from a game's options", Fuse.type.caption, color = c.textMuted, maxLines = 2)
                        for (col in feed.collections.take(3)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon(FuseIcons.Bookmark, size = 16.dp, tint = c.accent)
                                FText(col.name, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                FText("${col.gameCount}", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                            }
                        }
                    }
                }
                else -> WidgetContent(kind, feed, cartridge, clock24h)
            }
        }
        Spacer(Modifier.height(Space.s + Space.xs))
        FText(kind.title(), Fuse.type.label, color = if (selected) c.text else c.textMuted, maxLines = 1)
    }
}

/**
 * A game channel as a collage: the first game's background art, dimmed, with the covers of the first
 * three games fanned over it.
 */
@Composable
private fun CoverCollage(games: List<GameCard>, height: androidx.compose.ui.unit.Dp) {
    val first = games.first()
    val shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.45f)
    Box(Modifier.fillMaxSize()) {
        Artwork(
            first.art.hero ?: first.art.grid ?: first.art.boxart,
            Modifier.fillMaxSize(),
            focusX = first.art.heroFocusX,
            focusY = first.art.heroFocusY,
            fallback = { GeneratedArt(first.title, first.accent.toColor(), slot = ArtSlot.WIDE, showText = false) },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.1f), 1f to Color.Black.copy(alpha = 0.55f))))
        val cover = height * 0.66f
        val fan = games.take(3)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = Space.m)) {
            // Back to front, so the first game sits on top in the middle.
            val slots = listOf(1 to -1f, 2 to 1f, 0 to 0f).filter { it.first < fan.size }
            for ((index, side) in slots) {
                val g = fan[index]
                Box(
                    Modifier
                        .offset(x = cover * 0.46f * side)
                        .height(if (index == 0) cover else cover * 0.88f)
                        .aspectRatio(0.72f)
                        .graphicsLayer { rotationZ = 6f * side; shadowElevation = 12f; this.shape = shape; clip = true }
                        .align(Alignment.BottomCenter),
                ) {
                    Artwork(g.art.boxart ?: g.art.grid ?: g.art.icon, Modifier.fillMaxSize(), fallback = {
                        // Only the front cover names its game; the ones behind would show cut-off words.
                        GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.BOX, label = g.platformShort, showText = index == 0)
                    })
                }
            }
        }
    }
}

/** The last seven days of play as bars, today in the accent colour. */
@Composable
private fun WeekBars(feed: HomeFeed, modifier: Modifier) {
    val c = Fuse.colors
    val days = feed.playtime.lastSevenDays.ifEmpty { List(7) { 0L } }
    val max = (days.maxOrNull() ?: 0L).coerceAtLeast(1L)
    androidx.compose.foundation.Canvas(modifier) {
        val gap = 8.dp.toPx()
        val w = (size.width - gap * (days.size - 1)) / days.size
        days.forEachIndexed { i, v ->
            val h = (size.height * (v.toFloat() / max)).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                color = if (i == days.lastIndex) c.accent else c.text.copy(alpha = 0.24f),
                topLeft = androidx.compose.ui.geometry.Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
}
