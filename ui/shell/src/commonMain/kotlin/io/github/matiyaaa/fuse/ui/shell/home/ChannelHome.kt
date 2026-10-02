package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.SpatialSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.carried
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.packCells
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.components.CoverCollage
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.StageInfo
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** Columns of the board on a landscape screen, and on a narrow one (a phone held upright). */
private const val BOARD_COLUMNS = 4
private const val BOARD_COLUMNS_NARROW = 2

/** Narrower than this, the board uses [BOARD_COLUMNS_NARROW] so channels stay big enough to read. */
private val NARROW_BELOW = 600.dp

/** Shorter than this, the spotlight and the board tighten up (handhelds). */
private val COMPACT_BELOW = 560.dp

/** Channel corners, as a share of the theme's tile corner: wide channels would look bloated with a tile's. */
private const val CHANNEL_CORNER = 0.6f

/**
 * Channel Mode: Home as a board of tiles you arrange yourself. A spotlight across the top tells the
 * focused channel's story (its game's logo, what it holds, the covers that come next) over that
 * game's art, and each channel below is a collage of what is inside it: stacked covers, system
 * cards, app icons or a live widget, each a lit object with its name and count underneath. Hold a
 * channel and drag it into place by touch, or hold confirm to pick it up and move it with the
 * D-pad; the others slide out of its way and a well marks where it will land. The time lives in
 * the top bar.
 */
@Composable
fun ChannelHome(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.library.home.collectAsState()
    val cartridge by store.cartridge.status.collectAsState()

    // Without a single game, system or app the board would be a wall of empty channels: Home says
    // how to begin instead (after a moment for the library to load, shown as the board's outline).
    val nothing = feed.systems.isEmpty() && feed.pinnedApps.isEmpty() && feed.continuePlaying.isEmpty()
    val loading = rememberHomeLoading(app, feed, nothing)
    if (nothing) {
        if (loading) HomeSkeleton(app, channels = true) else HomeEmpty(app)
        return
    }

    // The Cartridge channel only while Cartridge support is on and it is installed; Collections only while on.
    val widgets = prefs.home.widgets.filter {
        it.visible && app.offers(it.kind) && (it.kind != WidgetKind.CARTRIDGE_DOWNLOADS || cartridge.installed) && (it.kind != WidgetKind.COLLECTIONS || prefs.collectionsEnabled)
    }.sortedBy { it.order }
    val drag = rememberDragReorderState()
    // While a channel is held, the board shows the order it would land in.
    val shown = drag.arrange(widgets) { it.id }
    val sel = rememberRouteState(app.navigator, "home.channels") { SpatialSelection() }
    sel.clamp(widgets.size)
    var carrying by remember { mutableStateOf(false) }
    val current = widgets.getOrNull(sel.index)
    val reveal = rememberReveal()

    val systems = rememberSystems(app)
    LaunchedEffect(current?.id, carrying, systems) {
        val game = current?.let { firstGame(it.kind, feed) }
        app.hero = game?.room(systems[game.platformId])
        app.hints = if (carrying) {
            listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Put down"))
        } else {
            listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to move"), Hint(HintButton.OPTIONS, "Options"))
        }
    }

    /** Moves the channel at [from] to [to]; the ones between slide over by one. */
    fun moveTo(from: Int, to: Int) {
        if (from !in widgets.indices || to !in widgets.indices || from == to) return
        val ids = widgets.map { it.id }
        store.updatePrefs { p -> p.copy(home = p.home.copy(widgets = HomeArrange.moveOne(p.home.widgets, ids, ids[from], to))) }
        sel.index = to
    }

    fun menu(w: HomeWidget?) = ContextMenuSpec(
        title = w?.kind?.title() ?: "Home",
        subtitle = "Home",
        actions = listOfNotNull(
            w?.let {
                MenuAction("move", "Move this channel", FuseIcons.Move, detail = "Then drag it, or use the D-pad", onSelect = {
                    app.closeOverlays()
                    carrying = true
                    drag.arm(it.id)
                })
            },
        ) + app.homeStyleActions(),
    )

    fun open(w: HomeWidget) = app.openWidget(w.kind, feed, firstGame(w.kind, feed))

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap outside the channels puts a carried one down.
            .pointerInput(carrying) { if (carrying) detectTapGestures { carrying = false; drag.arm(null) } },
    ) {
        val columns = if (maxWidth < NARROW_BELOW) BOARD_COLUMNS_NARROW else BOARD_COLUMNS
        val cells = remember(shown, columns) { packCells(shown.map { channelSpan(it).columns }, columns) }

        InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen, longPress = true) { e ->
            if (carrying) {
                return@InputLayer when (e.action) {
                    NavAction.LEFT -> { moveTo(sel.index, sel.index - 1); NavResult.MOVED }
                    NavAction.RIGHT -> { moveTo(sel.index, sel.index + 1); NavResult.MOVED }
                    // Up and down take the place of the channel above or below, the same one plain moves land on.
                    NavAction.UP, NavAction.DOWN -> {
                        val probe = SpatialSelection(sel.index)
                        if (probe.move(e.action, cells) == NavResult.MOVED) moveTo(sel.index, probe.index)
                        NavResult.MOVED
                    }
                    NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> { carrying = false; drag.arm(null); NavResult.CONSUMED }
                    else -> NavResult.CONSUMED
                }
            }
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, cells)
                NavAction.SELECT -> { current?.let(::open); NavResult.ACTIVATED }
                NavAction.REORDER -> { carrying = true; NavResult.ACTIVATED }
                NavAction.CONTEXT -> { app.openContextMenu(menu(current)); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        }

        val compact = maxHeight < COMPACT_BELOW
        val narrow = columns == BOARD_COLUMNS_NARROW
        val spotlightHeight = (maxHeight * if (narrow) 0.22f else 0.3f).coerceIn(118.dp, 250.dp)
        val gap = if (narrow) Space.m else Space.l
        val unit = ((maxWidth - Space.gutter * 2 - gap * (columns - 1)) / columns).coerceAtMost(maxHeight * 0.26f)
        val tileHeight = unit * 0.78f
        val grid = rememberLazyGridState()
        // The focused row comes to the top of the board, the way the focused shelf does in Flow,
        // so a half-cut row never sits under the spotlight.
        FollowSelection(grid, { sel.index }, anchor = 0f, enabled = { drag.heldKey == null })
        val time = rememberClockText(prefs.clock24h)
        CompositionLocalProvider(LocalHomeTime provides time) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            ChannelSpotlight(
                current,
                feed,
                cartridge,
                time,
                compact,
                Modifier.fillMaxWidth().height(spotlightHeight).padding(horizontal = Space.gutter).reveal(reveal, 0),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = if (compact) Space.m else Space.l, bottom = Size.hintHeight + Space.xxl),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
                modifier = Modifier
                    .weight(1f)
                    .fadingEdges(grid, top = if (compact) Space.m else Space.l)
                    .dragReorder(
                        drag,
                        visibleKeys = { grid.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { grid.scrollBy(it) },
                        keepScroll = { grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) },
                        longPressMs = ReorderDefaults.liftMs(prefs.input.longPressMs.toLong()),
                        endInset = Size.hintHeight,
                        onLift = { key ->
                            app.focusZone = FocusZone.CONTENT
                            sel.index = widgets.indexOfFirst { it.id == key }.coerceAtLeast(0)
                            app.platform.haptics.lift()
                        },
                        onTarget = { app.platform.haptics.slot() },
                        // A hold let go where it started opens the channel's options.
                        onHoldReleased = { key -> widgets.firstOrNull { it.id == key }?.let { app.openContextMenu(menu(it)) } },
                        onDrop = { key, to ->
                            moveTo(widgets.indexOfFirst { it.id == key }, to)
                            sel.index = to
                            carrying = false
                            app.platform.haptics.drop()
                        },
                    ),
            ) {
                itemsIndexed(shown, key = { _, w -> w.id }, span = { _, w -> GridItemSpan(channelSpan(w).columns.coerceAtMost(columns)) }) { at, w ->
                    val i = widgets.indexOf(w)
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val held = drag.heldKey == w.id
                    val moving = (selected && carrying) || held
                    val lift by animateFloatAsState(if ((selected && carrying) || held) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                    val well by animateFloatAsState(if (moving) 1f else 0f, Fuse.motion.fade(Durations.FAST), label = "well")
                    val fraction = Fuse.geometry.tileCornerFraction * CHANNEL_CORNER
                    val shape = remember(fraction) { SquircleShape.fraction(fraction) }
                    Box(
                        Modifier
                            // Channels slide into their new places as one is carried past them; the held one follows the finger.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (held) null else ReorderDefaults.Placement)
                            .reveal(reveal, 1 + at)
                            // Where the channel will land stays marked while it floats.
                            .dropWell({ well }, shape, height = tileHeight)
                            .reorderItem(drag, w.id, liftScale = 1f)
                            .zIndex(if (selected && carrying) 1f else 0f),
                    ) {
                        Channel(
                            widget = w,
                            feed = feed,
                            cartridge = cartridge,
                            clock24h = prefs.clock24h,
                            selected = selected,
                            moving = moving,
                            held = held,
                            height = tileHeight,
                            shape = shape,
                            cornerFraction = fraction,
                            lift = { lift },
                            onClick = {
                                app.focusZone = FocusZone.CONTENT
                                when {
                                    carrying -> carrying = false
                                    // Channels that play a game show it first; the rest open at once.
                                    sel.index == i || w.kind !in playChannels -> { sel.index = i; open(w) }
                                    else -> sel.index = i
                                }
                            },
                        )
                    }
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

/** How many things a channel holds, for the quiet count beside its name; none for widgets. */
private fun channelCount(kind: WidgetKind, feed: HomeFeed): Int? = when (kind) {
    in gameChannels -> channelGames(kind, feed).size
    WidgetKind.SYSTEMS -> feed.systems.size
    WidgetKind.PINNED_APPS -> feed.pinnedApps.size
    WidgetKind.COLLECTIONS -> feed.collections.size
    else -> null
}

/** Channels whose confirm plays a game, so a first tap only shows it. */
private val playChannels = setOf(
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.PINNED_GAMES, WidgetKind.CURRENT_GAME,
)

private val gameChannels = setOf(
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.FAVORITES, WidgetKind.RECENTLY_ADDED,
    WidgetKind.PINNED_GAMES, WidgetKind.MOST_PLAYED,
)

/** What the spotlight says about a channel. */
private fun spotlightInfo(widget: HomeWidget, feed: HomeFeed, cartridge: CartridgeStatus, time: String, accent: Long): StageInfo {
    val kind = widget.kind
    val key = widget.id
    val game = firstGame(kind, feed)
    if (game != null) {
        val count = channelGames(kind, feed).size
        return game.stage(extra = if (count > 1) listOf("$count games") else emptyList())
            .copy(key = "$key:${game.id.value}", eyebrow = "${kind.title()}  ·  ${game.platformShort}")
    }
    return when (kind) {
        in gameChannels -> StageInfo(key, kind.title(), eyebrow = "Home", meta = listOf(emptyNote(kind)), accent = accent)
        WidgetKind.SYSTEMS -> {
            val ready = feed.systems.count { it.emulatorInstalled }
            StageInfo(
                key, "${feed.systems.size} ${if (feed.systems.size == 1) "system" else "systems"}", eyebrow = "Systems",
                meta = listOf(gamesText(feed.systems.sumOf { it.gameCount }), "$ready ready to play"), accent = accent,
            )
        }
        WidgetKind.PINNED_APPS -> StageInfo(
            key, "Your apps", eyebrow = "Apps",
            meta = listOf(if (feed.pinnedApps.isEmpty()) "Pin apps from the Apps tab" else "${feed.pinnedApps.size} pinned"), accent = accent,
        )
        WidgetKind.COLLECTIONS -> {
            val first = feed.collections.firstOrNull()
            StageInfo(
                key, first?.name ?: "Collections", eyebrow = "Collections",
                meta = if (first == null) listOf("Make one from a game's options") else listOf(gamesText(first.gameCount), "${feed.collections.size} collections"),
                accent = accent,
            )
        }
        else -> widgetStage(kind, key, feed, cartridge, time, accent)
    }
}

/** "1 game", "12 games". */
internal fun gamesText(count: Int): String = "$count ${if (count == 1) "game" else "games"}"

private fun emptyNote(kind: WidgetKind): String = when (kind) {
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED -> "Games you play show here"
    WidgetKind.FAVORITES -> "Add favourites from a game's options"
    WidgetKind.PINNED_GAMES -> "Pin games from their options"
    WidgetKind.MOST_PLAYED -> "Games you play most show here"
    WidgetKind.RECENTLY_ADDED -> "New games show here after a scan"
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
    cartridge: CartridgeStatus,
    time: String,
    compact: Boolean,
    modifier: Modifier,
) {
    val accent = Fuse.colors.accent.toArgb().toLong() and 0xFFFFFFFFL
    BoxWithConstraints(modifier) {
        val stripHeight = maxHeight * 0.78f
        val wide = maxWidth > 560.dp
        // The strip keeps one width whatever it shows, so the story on the left never shifts as focus moves.
        val stripWidth = maxWidth * STRIP_SHARE
        val logoHeight = if (compact) Size.thumbL else (maxHeight * 0.42f).coerceAtMost(104.dp)
        val motion = Fuse.motion
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            Stage(
                widget?.let { spotlightInfo(it, feed, cartridge, time, accent) },
                Modifier.weight(1f),
                logoHeight = logoHeight,
                titleStyle = if (compact) Fuse.type.display else Fuse.type.hero,
            )
            if (wide && widget != null) {
                Spacer(Modifier.width(Space.l))
                Box(Modifier.width(stripWidth), contentAlignment = Alignment.BottomEnd) {
                    AnimatedContent(
                        targetState = widget.id,
                        transitionSpec = { fadeIn(motion.enter(Durations.BASE)) togetherWith fadeOut(motion.exit(Durations.INSTANT)) },
                        contentAlignment = Alignment.BottomEnd,
                        label = "spotlight strip",
                    ) { _ -> SpotlightStrip(widget.kind, feed, stripHeight, stripWidth) }
                }
            }
        }
    }
}

/** Share of the spotlight's width its strip takes on the right. */
private const val STRIP_SHARE = 0.4f

/**
 * What comes next in the channel: covers of the following games, system cards or app icons, as
 * many as fit [width].
 */
@Composable
private fun SpotlightStrip(kind: WidgetKind, feed: HomeFeed, height: Dp, width: Dp) {
    val c = Fuse.colors
    val fraction = Fuse.geometry.tileCornerFraction * 0.5f
    val shape = remember(fraction) { SquircleShape.fraction(fraction) }
    val gap = Space.m
    /** How many items of [each] width (the i-th one) fit, up to [max]. */
    fun fit(max: Int, each: (Int) -> Dp): Int {
        var used = 0.dp
        for (i in 0 until max) {
            used += each(i) + if (i > 0) gap else 0.dp
            if (used > width) return i
        }
        return max
    }
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
        when (kind) {
            in gameChannels -> channelGames(kind, feed).drop(1).take(fit(4) { i -> height * (1f - i * 0.08f) * Aspect.BOX }).forEachIndexed { i, g ->
                // Each cover a little smaller and quieter than the one before, as if further away.
                Box(
                    Modifier.height(height * (1f - i * 0.08f)).aspectRatio(Aspect.BOX)
                        .graphicsLayer { alpha = 1f - i * 0.16f }
                        .lifted(shape),
                ) {
                    Artwork(g.art.boxart ?: g.art.grid ?: g.art.square ?: g.art.icon, Modifier.fillMaxSize(), fallback = {
                        GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.BOX, label = g.platformShort)
                    })
                }
            }
            WidgetKind.SYSTEMS -> feed.systems.take(fit(4) { height * 0.5f * Aspect.SYSTEM_CARD }).forEach { s ->
                val chip = RoundedCornerShape(Fuse.geometry.control)
                SystemChip(s, chip, Modifier.height(height * 0.5f).aspectRatio(Aspect.SYSTEM_CARD).lifted(chip))
            }
            WidgetKind.PINNED_APPS -> feed.pinnedApps.take(fit(5) { height * 0.5f }).forEach { a ->
                Artwork(a.icon, Modifier.size(height * 0.5f).clip(shape), contentScale = ContentScale.Fit)
            }
            // A quiet echo of the week, smaller than the story beside it.
            WidgetKind.PLAYTIME_WEEK, WidgetKind.PLAYTIME_TOTAL ->
                WeekBars(feed.playtime.lastSevenDays.ifEmpty { List(7) { 0L } }, Modifier.width((height * 1.5f).coerceAtMost(width)).height(height * 0.56f), letters = true)
            WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.RECENTLY_MASTERED, WidgetKind.ACHIEVEMENT_PROGRESS ->
                feed.achievements?.recent?.take(fit(4) { height * 0.46f })?.forEach { a ->
                    Artwork(a.achievement.badgeUrl, Modifier.size(height * 0.46f).lifted(shape), fallback = {
                        Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                            FuseIcon(FuseIcons.Trophy, tint = c.warning)
                        }
                    })
                }
            else -> Unit
        }
    }
}

/** A small object resting on the room: clipped to [shape], over a tile's contact shadow, with a lit top edge. */
@Composable
private fun Modifier.lifted(shape: Shape): Modifier {
    val c = Fuse.colors
    val shadow = c.shadow
    return graphicsLayer {
        this.shape = shape
        clip = true
        shadowElevation = Elevation.tile.shadow.toPx() * 2
        spotShadowColor = shadow
        ambientShadowColor = shadow.copy(alpha = shadow.alpha * 0.5f)
    }.lightEdge(shape, Elevation.tile.edgeAlpha(c.isDark))
}

/** A system as a small card in its colour, with its logo (or short name) in the colour that reads on art. */
@Composable
private fun SystemChip(s: PlatformCard, shape: Shape, modifier: Modifier) {
    val c = Fuse.colors
    val accent = s.platform.accent.toColor()
    // A lit gradient from the system's colour into a deeper shade of it, like the system tiles.
    val deep = c.shadow.copy(alpha = 1f)
    val brush = remember(accent, deep) { Brush.linearGradient(listOf(lerp(accent, c.onArt, 0.06f), lerp(accent, deep, 0.42f))) }
    Box(modifier.clip(shape).background(brush).lightEdge(shape, Elevation.raised.edgeAlpha(true)), contentAlignment = Alignment.Center) {
        val name: @Composable () -> Unit = { FText(s.platform.shortName, Fuse.type.bodyStrong, color = c.onArt, maxLines = 1) }
        if (s.art.logo != null) {
            Artwork(s.art.logo, Modifier.fillMaxSize().padding(Space.s), contentScale = ContentScale.Fit, tint = c.onArt, fallback = name)
        } else {
            name()
        }
    }
}

@Composable
private fun Channel(
    widget: HomeWidget,
    feed: HomeFeed,
    cartridge: CartridgeStatus,
    clock24h: Boolean,
    selected: Boolean,
    moving: Boolean,
    held: Boolean,
    height: Dp,
    shape: Shape,
    cornerFraction: Float,
    lift: () -> Float,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val kind = widget.kind
    val games = channelGames(kind, feed)
    val game = games.firstOrNull()
    Column {
        Tile(
            selected = selected,
            // Held or carried, a channel floats over a soft shadow, the same by touch or controller.
            modifier = Modifier.fillMaxWidth().height(height).carried(lift, shape),
            cornerFraction = cornerFraction,
            shape = shape,
            glow = game?.accent?.toColor() ?: if (kind in gameChannels || kind == WidgetKind.SYSTEMS) c.accent else widgetTint(kind, feed, cartridge),
            onClick = onClick,
        ) {
            when {
                kind in gameChannels && game != null -> CoverCollage(games, height)
                kind in gameChannels -> ChannelNote(widgetIcon(kind), emptyNote(kind))
                kind == WidgetKind.SYSTEMS -> SystemsBoard(feed.systems, cornerFraction)
                kind == WidgetKind.PINNED_APPS -> AppsBoard(feed, height)
                kind == WidgetKind.COLLECTIONS -> CollectionsBoard(feed)
                else -> WidgetContent(kind, feed, cartridge, clock24h)
            }
        }
        // Clear of the spark bar under a focused channel.
        Spacer(Modifier.height(Size.sparkClearance - Space.xs))
        // Following a finger, the channel travels without its name, which would run over the others'.
        val label by animateFloatAsState(if (held) 0f else 1f, Fuse.motion.fade(Durations.FAST), label = "label")
        Box(Modifier.graphicsLayer { alpha = label }) {
            ChannelLabel(kind, channelCount(kind, feed), selected || moving, moving)
        }
    }
}

/** A channel's name under it, with a small icon and a quiet count; "Moving" while it is carried. */
@Composable
private fun ChannelLabel(kind: WidgetKind, count: Int?, lit: Boolean, moving: Boolean) {
    val c = Fuse.colors
    val color by animateColorAsState(if (lit) c.text else c.textMuted, Fuse.motion.fade(Durations.FAST), label = "channel label")
    Row(Modifier.fillMaxWidth().heightIn(min = Size.badge), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(widgetIcon(kind), size = Size.iconXS, tint = color)
        Spacer(Modifier.width(Space.s - Space.xxs))
        FText(kind.title(), Fuse.type.label, color = color, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (count != null && count > 0 && !moving) {
            Spacer(Modifier.width(Space.s))
            FText(count.toString(), Fuse.type.numericSmall, color = c.textFaint, maxLines = 1)
        }
        if (moving) {
            Spacer(Modifier.width(Space.s))
            Badge("Moving", icon = FuseIcons.Move)
        }
    }
}

/** An empty channel: its icon in a soft disc and what will fill it. */
@Composable
private fun ChannelNote(icon: androidx.compose.ui.graphics.vector.ImageVector, note: String) {
    val c = Fuse.colors
    Column(
        Modifier.fillMaxSize().widgetSurface(c.surfaceRaised, c.text.copy(alpha = if (c.isDark) 0.06f else 0.04f)).padding(Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(Size.chip).clip(CircleShape).background(c.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconM, tint = c.textMuted)
        }
        Spacer(Modifier.height(Space.s))
        FText(note, Fuse.type.caption, color = c.textMuted, align = TextAlign.Center, maxLines = 2)
    }
}

/**
 * Up to four systems, each a small card in its colour. Their corners follow the channel's, less
 * the inset, so the cards sit in it like they were cut for it.
 */
@Composable
private fun SystemsBoard(systems: List<PlatformCard>, cornerFraction: Float) {
    val c = Fuse.colors
    BoxWithConstraints(Modifier.fillMaxSize().widgetSurface(c.surfaceRaised, c.text.copy(alpha = if (c.isDark) 0.06f else 0.04f)).padding(Space.s)) {
        val outer = minOf(maxWidth, maxHeight) * cornerFraction + Space.s
        val chip = RoundedCornerShape((outer - Space.s).coerceAtLeast(Radius.xs))
        val shown = systems.take(4)
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            shown.chunked(2).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    row.forEach { SystemChip(it, chip, Modifier.weight(1f).fillMaxHeight()) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppsBoard(feed: HomeFeed, height: Dp) {
    val c = Fuse.colors
    val apps = feed.pinnedApps.take(4)
    if (apps.isEmpty()) {
        ChannelNote(FuseIcons.AppWindow, "Pin apps from the Apps tab")
        return
    }
    Box(Modifier.fillMaxSize().widgetSurface(c.surfaceRaised, c.text.copy(alpha = if (c.isDark) 0.06f else 0.04f)).padding(Space.m), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            apps.forEach { a -> Artwork(a.icon, Modifier.size((height * 0.38f).coerceAtMost(Size.thumbL)), contentScale = ContentScale.Fit) }
        }
    }
}

@Composable
private fun CollectionsBoard(feed: HomeFeed) {
    val c = Fuse.colors
    if (feed.collections.isEmpty()) {
        ChannelNote(FuseIcons.Bookmark, "Make one from a game's options")
        return
    }
    Column(
        Modifier.fillMaxSize().widgetSurface(c.surfaceRaised, c.text.copy(alpha = if (c.isDark) 0.06f else 0.04f)).padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (col in feed.collections.take(3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.Bookmark, size = Size.iconXS, tint = c.textMuted)
                Spacer(Modifier.width(Space.s))
                FText(col.name, Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                FText("${col.gameCount}", Fuse.type.numericSmall, color = c.textFaint, maxLines = 1)
            }
        }
    }
}
