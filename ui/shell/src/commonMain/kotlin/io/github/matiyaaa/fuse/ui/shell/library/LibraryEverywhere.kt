package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.gameConfirmLabel
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.reach.HouseholdTile
import io.github.matiyaaa.fuse.ui.shell.reach.holdersText
import io.github.matiyaaa.fuse.ui.shell.reach.householdMenu
import io.github.matiyaaa.fuse.ui.shell.romm.RommTile
import io.github.matiyaaa.fuse.ui.shell.romm.ShelfTitle
import io.github.matiyaaa.fuse.ui.shell.romm.liftRoom
import io.github.matiyaaa.fuse.ui.shell.romm.systemCard
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdGame
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence

/**
 * One game on the All page, wherever it is: on this device, on RomM, or on another device of the
 * household. A game the library has is always shown as the library's own (with its Installed mark);
 * RomM and the household only add games this device doesn't have, so nothing appears twice.
 */
@Immutable
internal sealed class EverywhereGame {
    abstract val card: GameCard
    abstract val addedAt: Long

    data class Here(override val card: GameCard) : EverywhereGame() {
        override val addedAt: Long get() = card.addedAt
    }

    data class OnRomm(val game: RommGame) : EverywhereGame() {
        override val card: GameCard get() = game.card
        override val addedAt: Long get() = game.card.addedAt
    }

    data class Elsewhere(val game: HouseholdGame) : EverywhereGame() {
        override val card: GameCard get() = game.card
        override val addedAt: Long get() = game.addedAt
    }

    /** Where it is, in a few words, for the line under its shelf. */
    val where: String get() = when (this) {
        is Here -> "Installed"
        is OnRomm -> when (game.presence) {
            RommPresence.ON_SERVER -> "On RomM"
            RommPresence.QUEUED -> "Waiting to download"
            RommPresence.DOWNLOADING -> "Downloading"
            RommPresence.INSTALLED -> "Installed"
            RommPresence.PARTLY_INSTALLED -> "Partly installed"
        }
        is Elsewhere -> if (game.downloading) "Coming here" else holdersText(game)
    }
}

/** The key a game is known by across sources: its system and its name, so one game is one tile. */
internal fun sameGameKey(card: GameCard): String = card.platformId.value + "|" + card.title.lowercase().filter { it.isLetterOrDigit() }

/**
 * Every source's recent games as one shelf, newest first: the library's own, then RomM's and the
 * household's that this device doesn't have. A game is listed once, under the source closest to
 * hand (this device, then RomM, then another device).
 */
internal fun mergeRecent(here: List<GameCard>, romm: List<RommGame>, elsewhere: List<HouseholdGame>, limit: Int = 30): List<EverywhereGame> {
    val seen = HashSet<String>()
    val ids = HashSet<Long>()
    val out = ArrayList<EverywhereGame>()
    fun add(e: EverywhereGame, id: Long?) {
        val key = sameGameKey(e.card)
        if (key in seen || (id != null && id in ids)) return
        seen += key
        if (id != null) ids += id
        out += e
    }
    for (c in here) add(EverywhereGame.Here(c), c.id.value)
    for (g in romm) add(EverywhereGame.OnRomm(g), g.game?.value ?: g.card.id.value)
    for (g in elsewhere) add(EverywhereGame.Elsewhere(g), g.id.value)
    return out.sortedByDescending { it.addedAt }.take(limit)
}

/** A system on the All page: the library's card for it, and where to open its games. */
@Immutable
internal data class EverywhereSystem(val card: PlatformCard, val rommSlug: String?, val local: Boolean)

/**
 * Library, All: every game wherever it is, as shelves. Recently Added (from this device, RomM and
 * the household), the Systems, what is On RomM and what is On Other Devices; a source that isn't in
 * use has no shelf. Each shelf ends in See All, which opens its whole list. A game the library has
 * shows the Installed mark here only, since everywhere else in the Library is already this device.
 */
@Composable
internal fun LibraryEverywhere(app: AppState, view: LibraryViewState, focused: Boolean, modifier: Modifier = Modifier) {
    val store = app.store
    val home by store.homeFeed.collectAsState()
    val platforms by store.library.platforms.collectAsState()
    val romm = store.romm
    val rommState by romm.state.collectAsState()
    val rommRecent by romm.recent.collectAsState()
    val rommNew by romm.newGames.collectAsState()
    val rommSystems by romm.systems.collectAsState()
    val householdRecent by store.reach.recent.collectAsState()
    val library = rememberSystems(app)

    val rommOn = romm.supported && rommState.link != RommLink.NOT_SET_UP
    val rommGames = remember(rommNew, rommRecent) { (rommNew + rommRecent).distinctBy { it.romId } }
    val onRomm = remember(rommGames) { rommGames.filter { it.presence != RommPresence.INSTALLED }.map { EverywhereGame.OnRomm(it) } }
    val elsewhere = remember(householdRecent) { householdRecent.map { EverywhereGame.Elsewhere(it) } }
    val recent = remember(home.recentlyAdded, rommGames, householdRecent) { mergeRecent(home.recentlyAdded, rommGames, householdRecent) }
    val systems = remember(platforms, rommSystems, library) {
        val local = platforms.filter { it.gameCount > 0 }.map { EverywhereSystem(it, rommSystems.firstOrNull { r -> r.platform == it.platform.id }?.slug, local = true) }
        val known = local.map { it.card.platform.id }.toSet()
        val remote = rommSystems.filter { it.platform != null && it.platform !in known }
            .mapNotNull { r -> systemCard(r, library)?.let { EverywhereSystem(it, r.slug, local = false) } }
        local + remote
    }

    val rows = buildList {
        if (recent.isNotEmpty()) add(ROW_RECENT)
        if (systems.isNotEmpty()) add(ROW_SYSTEMS)
        if (rommOn && onRomm.isNotEmpty()) add(ROW_ROMM)
        if (elsewhere.isNotEmpty()) add(ROW_ELSEWHERE)
    }
    // Each shelf ends in See All (systems excepted: the Systems tab holds them all already).
    fun sizeOf(key: String) = when (key) {
        ROW_RECENT -> recent.size + 1
        ROW_SYSTEMS -> systems.size
        ROW_ROMM -> onRomm.size + 1
        ROW_ELSEWHERE -> elsewhere.size + 1
        else -> 0
    }
    val sel = view.shelves
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row)
    val col = row?.let { sel.column(it) } ?: 0
    fun gamesOf(key: String?): List<EverywhereGame> = when (key) {
        ROW_RECENT -> recent
        ROW_ROMM -> onRomm
        ROW_ELSEWHERE -> elsewhere
        else -> emptyList()
    }
    val chosen = gamesOf(row).getOrNull(col)
    val chosenSystem = if (row == ROW_SYSTEMS) systems.getOrNull(col) else null

    fun open(e: EverywhereGame) = when (e) {
        is EverywhereGame.Here -> app.activateGame(e.card)
        is EverywhereGame.OnRomm -> app.go(Route.GameInfo(e.game.game ?: e.card.id))
        is EverywhereGame.Elsewhere -> app.go(Route.GameInfo(e.game.id))
    }
    fun options(e: EverywhereGame) = when (e) {
        is EverywhereGame.Here -> app.openContextMenu(app.gameMenu(e.card))
        is EverywhereGame.OnRomm -> app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.romm.gameMenu(app, e.game))
        is EverywhereGame.Elsewhere -> app.openContextMenu(householdMenu(app, e.game, romm = rommState.canUpload))
    }
    fun openSystem(s: EverywhereSystem) = when {
        s.local -> app.go(Route.PlatformGames(s.card.platform.id))
        s.rommSlug != null -> app.go(Route.RommGames(s.rommSlug, s.card.platform.shortName))
        else -> Unit
    }
    fun seeAll(key: String) = when (key) {
        // Everything on this device, newest first: the Installed view sorted by when games came.
        ROW_RECENT -> {
            app.store.updatePrefs { it.copy(librarySort = io.github.matiyaaa.fuse.model.SortOrder.RECENTLY_ADDED) }
            view.segment = LibrarySegment.INSTALLED
            view.grid.index = 0
            view.selectedId = null
        }
        ROW_ROMM -> app.go(Route.RommGames(null, "On RomM"))
        ROW_ELSEWHERE -> app.go(Route.HouseholdGames(null, "On other devices"))
        else -> Unit
    }

    PageEffect(focused, chosen?.card?.id, chosen?.card?.art, chosenSystem?.card?.art) {
        if (!focused) return@PageEffect
        app.hero = when {
            chosenSystem != null -> io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource(chosenSystem.card.platform.id, chosenSystem.card.art.hero, chosenSystem.card.platform.accent.toColor())
            chosen != null -> chosen.card.room(library[chosen.card.platformId])
            else -> null
        }
    }
    PageEffect(focused, row, col, chosen == null) {
        if (!focused) return@PageEffect
        app.hints = when {
            row == ROW_SYSTEMS -> listOf(Hint(HintButton.CONFIRM, "Open"))
            chosen == null && row != null -> listOf(Hint(HintButton.CONFIRM, "See all"))
            chosen is EverywhereGame.Here -> listOf(Hint(HintButton.CONFIRM, app.gameConfirmLabel), Hint(HintButton.OPTIONS, "Options"))
            chosen != null -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"))
            else -> emptyList()
        }
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> {
                val r = if (rows.isEmpty()) NavResult.IGNORED else sel.move(e.action, rows, ::sizeOf)
                when {
                    // Up from the first shelf goes to the Library's tabs.
                    r == NavResult.IGNORED && e.action == NavAction.UP -> { view.inHeader = true; NavResult.MOVED }
                    r == NavResult.IGNORED -> NavResult.BLOCKED
                    else -> r
                }
            }
            NavAction.SELECT -> {
                val key = row ?: return@InputLayer NavResult.BLOCKED
                when {
                    key == ROW_SYSTEMS -> systems.getOrNull(col)?.let(::openSystem)
                    chosen != null -> open(chosen)
                    else -> seeAll(key)
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                chosen?.let(::options)
                if (chosen != null) NavResult.ACTIVATED else NavResult.BLOCKED
            }
            else -> NavResult.IGNORED
        }
    }

    io.github.matiyaaa.fuse.ui.shell.app.CompanionShowsCopies(true)
    val list = rememberLazyListState()
    LaunchedEffect(sel.row) { if (rows.isNotEmpty()) list.animateScrollToItem(sel.row.coerceIn(0, rows.lastIndex)) }
    BoxWithConstraints(modifier.testTag("library.all")) {
        val tile = LocalTileMetrics.current.icon
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
                FText("Games you add show up here, with what's on RomM and your other devices when those are set up.", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 3)
            }
            return@BoxWithConstraints
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().fadingEdges(list, top = Space.l, bottom = Space.xl),
            contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            for ((r, key) in rows.withIndex()) item(key) {
                val selected = if (focused && sel.row == r) sel.column(key) else -1
                when (key) {
                    ROW_SYSTEMS -> SystemsShelf(systems, selected, tile) { i -> sel.row = r; sel.setColumn(key, i); openSystem(systems[i]) }
                    else -> {
                        val (title, icon) = when (key) {
                            ROW_RECENT -> "Recently Added" to FuseIcons.Sparkles
                            ROW_ROMM -> "On RomM" to FuseIcons.Cloud
                            else -> "On Other Devices" to FuseIcons.MonitorSmartphone
                        }
                        val games = gamesOf(key)
                        EverywhereShelf(
                            title, icon, games, selected, tile,
                            onOpen = { i -> sel.row = r; sel.setColumn(key, i); open(games[i]) },
                            onOptions = { i -> sel.row = r; sel.setColumn(key, i); options(games[i]) },
                            onSeeAll = { sel.row = r; sel.setColumn(key, games.size); seeAll(key) },
                        )
                    }
                }
            }
        }
    }
}

private const val ROW_RECENT = "recent"
private const val ROW_SYSTEMS = "systems"
private const val ROW_ROMM = "romm"
private const val ROW_ELSEWHERE = "elsewhere"

/** A shelf of games from any source, its chosen one named underneath, ending in See All. */
@Composable
private fun EverywhereShelf(
    title: String,
    icon: ImageVector,
    games: List<EverywhereGame>,
    selected: Int,
    tile: Dp,
    onOpen: (Int) -> Unit,
    onOptions: (Int) -> Unit,
    onSeeAll: () -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle(title, icon, if (games.size == 1) "1 game" else "${games.size} games")
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)),
            horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap),
        ) {
            itemsIndexed(games, key = { _, g -> g::class.simpleName + g.card.id.value }) { i, g ->
                when (g) {
                    is EverywhereGame.Here -> androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsInstalled provides true) {
                        GameIconTile(g.card, selected == i, size = tile, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                    }
                    is EverywhereGame.OnRomm -> RommTile(g.game, selected == i, tile, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                    is EverywhereGame.Elsewhere -> HouseholdTile(g.game, selected == i, tile, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                }
            }
            item("seeall") { SeeAllTile(selected == games.size, tile, onSeeAll) }
        }
        val chosen = games.getOrNull(selected)
        // Room for both lines, so the next shelf's title never cuts the second.
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.m).heightIn(min = Space.xxl + Space.l)) {
            if (chosen != null) {
                FText(chosen.card.title, Fuse.type.bodyStrong, maxLines = 1)
                FText(listOfNotNull(chosen.card.platformShort, chosen.card.year?.toString(), chosen.where).joinToString("  ·  "), Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
            } else if (selected == games.size) {
                FText("See all", Fuse.type.bodyStrong, maxLines = 1)
            }
        }
    }
}

/** The systems with games anywhere: the library's first, then systems only RomM has. */
@Composable
private fun SystemsShelf(systems: List<EverywhereSystem>, selected: Int, tile: Dp, onOpen: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle("Systems", FuseIcons.Chip, "${systems.size}")
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)), horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap)) {
            itemsIndexed(systems, key = { _, s -> s.card.platform.id.value }) { i, s ->
                SystemTile(s.card, selected == i, size = tile, onClick = { onOpen(i) })
            }
        }
    }
}

/** The tile that ends a shelf: an arrow into the shelf's whole list. */
@Composable
private fun SeeAllTile(selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    Tile(selected, Modifier.size(size).semantics { contentDescription = "See all" }, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(Size.touch).clip(CircleShape).background(c.text.copy(alpha = if (c.isDark) 0.1f else 0.06f)), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.ChevronRight, size = Size.iconM, tint = c.text)
                }
                Spacer(Modifier.height(Space.s))
                FText("See all", Fuse.type.label, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

