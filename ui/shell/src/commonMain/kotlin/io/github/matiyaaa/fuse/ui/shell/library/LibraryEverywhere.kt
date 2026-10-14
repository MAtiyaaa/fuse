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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.togetherWith
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.shell.components.gamesText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
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
import io.github.matiyaaa.fuse.ui.shell.app.openStreaming
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
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import androidx.compose.foundation.gestures.Orientation

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

    /** An app a computer at home streams through Moonlight: once, however many computers offer it. */
    data class Streamed(val app: StreamedApp) : EverywhereGame() {
        override val card: GameCard get() = app.card
        override val addedAt: Long get() = 0
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
        is Streamed -> app.where
    }
}

/**
 * An app the household's computers stream (Sunshine, Apollo), with every computer that offers it,
 * as one tile. Its art is the library's when the library has a game of the same name.
 */
@Immutable
internal data class StreamedApp(val name: String, val hosts: List<io.github.matiyaaa.fuse.ui.shell.store.StreamHostView>, val card: GameCard) {
    /** Where it streams from, in a few words. */
    val where: String get() = when {
        hosts.size == 1 -> "Streams from ${hosts.single().host.name}"
        else -> "Streams from ${hosts.size} computers"
    }
}

/**
 * The apps the household's computers offer, each once (by name, whatever its case), its computers
 * in the order they were added. [art] gives the library's art for a name it has a game for.
 */
internal fun streamedApps(hosts: List<io.github.matiyaaa.fuse.ui.shell.store.StreamHostView>, art: (String) -> io.github.matiyaaa.fuse.ui.shell.store.Art?): List<StreamedApp> {
    val byName = LinkedHashMap<String, Pair<String, MutableList<io.github.matiyaaa.fuse.ui.shell.store.StreamHostView>>>()
    for (h in hosts) for (a in h.host.apps) {
        val name = a.trim().ifEmpty { continue }
        val key = name.lowercase()
        val entry = byName.getOrPut(key) { name to ArrayList() }
        if (entry.second.none { it.host.id == h.host.id }) entry.second += h
    }
    return byName.entries.mapIndexed { i, (key, value) ->
        val (name, on) = value
        StreamedApp(
            name, on,
            GameCard(
                // Never a library game's id: choosing it streams, it never starts a copy here.
                id = io.github.matiyaaa.fuse.model.GameId(-1_000_000L - i),
                platformId = io.github.matiyaaa.fuse.model.PlatformId("stream"),
                title = name, platformShort = "Moonlight", accent = 0xFF5B8DEF,
                art = art(key) ?: io.github.matiyaaa.fuse.ui.shell.store.Art.None,
            ),
        )
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
    // What the computers at home stream, when streaming is on: once each, with the library's art where it has the game.
    val prefs by store.prefs.collectAsState()
    val streamHosts by store.streaming.hosts.collectAsState()
    val streamingOn = store.streaming.supported && prefs.streaming.enabled
    val allGames = remember(streamingOn) { if (streamingOn) store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()) else kotlinx.coroutines.flow.flowOf(emptyList()) }
    val everyGame by allGames.collectAsState(initial = emptyList())
    val streamed = remember(streamHosts, everyGame, streamingOn) {
        if (!streamingOn) emptyList() else {
            val byTitle = everyGame.associateBy { it.title.lowercase() }
            streamedApps(streamHosts) { key -> byTitle[key]?.art }.map { EverywhereGame.Streamed(it) }
        }
    }

    val rommOn = romm.supported && rommState.link != RommLink.NOT_SET_UP
    val rommGames = remember(rommNew, rommRecent) { (rommNew + rommRecent).distinctBy { it.romId } }
    val onRomm = remember(rommGames) { rommGames.filter { it.presence != RommPresence.INSTALLED }.map { EverywhereGame.OnRomm(it) } }
    val elsewhere = remember(householdRecent) { householdRecent.map { EverywhereGame.Elsewhere(it) } }
    val recent = remember(home.recentlyAdded, rommGames, householdRecent) { mergeRecent(home.recentlyAdded, rommGames, householdRecent) }
    val householdSystems by store.reach.systems.collectAsState()
    val systems = remember(platforms, rommSystems, householdSystems, library) {
        val local = platforms.filter { it.gameCount > 0 }.map { EverywhereSystem(it, rommSystems.firstOrNull { r -> r.platform == it.platform.id }?.slug, local = true) }
        val known = local.map { it.card.platform.id }.toMutableSet()
        val remote = rommSystems.filter { it.platform != null && it.platform !in known }
            .mapNotNull { r -> systemCard(r, library)?.let { EverywhereSystem(it, r.slug, local = false) } }
        known += remote.map { it.card.platform.id }
        // Systems only the household's other devices have.
        val elsewhere = householdSystems.filter { it.platform !in known }
            .mapNotNull { h -> io.github.matiyaaa.fuse.ui.shell.reach.householdSystemCard(h, library)?.let { EverywhereSystem(it, null, local = false) } }
        local + remote + elsewhere
    }

    val rows = buildList {
        if (recent.isNotEmpty()) add(ROW_RECENT)
        if (systems.isNotEmpty()) add(ROW_SYSTEMS)
        if (rommOn && onRomm.isNotEmpty()) add(ROW_ROMM)
        if (elsewhere.isNotEmpty()) add(ROW_ELSEWHERE)
        if (streamed.isNotEmpty()) add(ROW_STREAM)
    }
    // Each shelf ends in See All (systems excepted: the Systems tab holds them all already).
    fun sizeOf(key: String) = when (key) {
        ROW_RECENT -> recent.size + 1
        ROW_SYSTEMS -> systems.size
        ROW_ROMM -> onRomm.size + 1
        ROW_ELSEWHERE -> elsewhere.size + 1
        ROW_STREAM -> streamed.size + 1
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
        ROW_STREAM -> streamed
        else -> emptyList()
    }
    val chosen = gamesOf(row).getOrNull(col)
    val chosenSystem = if (row == ROW_SYSTEMS) systems.getOrNull(col) else null
    // Games arriving (another device answering, RomM's list coming in) can reorder a shelf: the
    // selection stays on the game it was on, never on whatever slid into its place.
    val following = remember { HashMap<String, String>() }
    val lists = remember { HashMap<String, List<EverywhereGame>>() }
    for (key in listOf(ROW_RECENT, ROW_ROMM, ROW_ELSEWHERE, ROW_STREAM)) {
        val games = gamesOf(key)
        val at = sel.column(key)
        val was = following[key]
        // Only when the shelf itself changed: a move the person made is theirs to keep.
        val arrived = lists.put(key, games).let { it != null && it !== games }
        if (was != null && arrived) {
            val now = games.indexOfFirst { identity(it) == was }
            if (now >= 0 && now != at) sel.setColumn(key, now)
        }
        games.getOrNull(sel.column(key))?.let { following[key] = identity(it) } ?: following.remove(key)
    }

    fun open(e: EverywhereGame) = when (e) {
        is EverywhereGame.Here -> app.activateGame(e.card)
        is EverywhereGame.OnRomm -> app.go(Route.GameInfo(e.game.game ?: e.card.id))
        is EverywhereGame.Elsewhere -> app.go(Route.GameInfo(e.game.id))
        is EverywhereGame.Streamed -> streamFrom(app, e.app)
    }
    fun options(e: EverywhereGame) = when (e) {
        is EverywhereGame.Here -> app.openContextMenu(app.gameMenu(e.card))
        is EverywhereGame.OnRomm -> app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.romm.gameMenu(app, e.game))
        is EverywhereGame.Elsewhere -> app.openContextMenu(householdMenu(app, e.game, romm = rommState.canUpload))
        is EverywhereGame.Streamed -> streamFrom(app, e.app, always = true)
    }
    // Every game of the system from everywhere, installed or not, each once.
    fun openSystem(s: EverywhereSystem) = app.go(Route.SystemEverywhere(s.card.platform.id, s.card.platform.name, s.rommSlug))
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
        ROW_STREAM -> app.openStreaming()
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
            chosen is EverywhereGame.Streamed -> listOf(Hint(HintButton.CONFIRM, "Stream"), Hint(HintButton.OPTIONS, "Choose computer"))
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
            // The triggers jump along a shelf a screenful at a time, to its See all at the end and back.
            NavAction.PAGE_DOWN, NavAction.PAGE_UP -> {
                val key = row ?: return@InputLayer NavResult.BLOCKED
                val last = sizeOf(key) - 1
                val step = if (e.action == NavAction.PAGE_DOWN) PAGE_STEP else -PAGE_STEP
                val to = (col + step).coerceIn(0, last)
                if (to == col) NavResult.BLOCKED else { sel.setColumn(key, to); NavResult.MOVED }
            }
            else -> NavResult.IGNORED
        }
    }

    io.github.matiyaaa.fuse.ui.shell.app.CompanionShowsCopies(true)
    val list = rememberLazyListState()
    // Only scrolls when the chosen shelf isn't wholly on screen, so shelves that fit keep their titles.
    LaunchedEffect(sel.row) {
        if (rows.isEmpty()) return@LaunchedEffect
        val to = sel.row.coerceIn(0, rows.lastIndex)
        val info = list.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == to }
        val whole = seen != null && seen.offset >= info.viewportStartOffset && seen.offset + seen.size <= info.viewportEndOffset
        if (!whole) list.animateScrollToItem(to)
    }
    BoxWithConstraints(modifier.testTag("library.all")) {
        val tile = LocalTileMetrics.current.icon
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
                FText("Games you add show up here, with what's on RomM and your other devices when those are set up.", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 3)
            }
            return@BoxWithConstraints
        }
        // What you're on, and where it is, always in the same place above the shelves (as Installed's
        // stage does), so moving along a shelf never makes the page jump. Small screens skip it.
        val stage = maxHeight >= STAGE_FROM
        if (stage) AllStage(chosen, chosenSystem, if (row != null && chosen == null && chosenSystem == null) row else null, Modifier.fillMaxWidth().height(stageHeight(maxHeight)))
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = if (stage) stageHeight(maxHeight) else 0.dp).fadingEdges(list, top = Space.l, bottom = Space.xl).dragScroll(list),
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
                            ROW_STREAM -> "Stream From Your Computers" to FuseIcons.Cast
                            else -> "On Other Devices" to FuseIcons.MonitorSmartphone
                        }
                        val games = gamesOf(key)
                        EverywhereShelf(
                            title, icon, games, selected, tile,
                            onOpen = { i -> sel.row = r; sel.setColumn(key, i); open(games[i]) },
                            onOptions = { i -> sel.row = r; sel.setColumn(key, i); options(games[i]) },
                            onSeeAll = { sel.row = r; sel.setColumn(key, games.size); seeAll(key) },
                            noun = if (key == ROW_STREAM) "app" else "game",
                            named = !stage,
                        )
                    }
                }
            }
        }
    }
}

/** What a shelf's game is across arrivals: its kind and id, as the shelf keys its tiles. */
private fun identity(g: EverywhereGame): String = g::class.simpleName + g.card.id.value

private const val ROW_RECENT = "recent"
private const val ROW_SYSTEMS = "systems"
private const val ROW_ROMM = "romm"
private const val ROW_ELSEWHERE = "elsewhere"
private const val ROW_STREAM = "stream"

/**
 * Streams [a] through Moonlight. With one computer offering it (and not [always] asking), straight
 * away; with several, the person chooses, seeing which are on, asleep (and woken) or not answering.
 * One tile for the app however many computers have it: switching computers never adds a game.
 */
private fun streamFrom(app: AppState, a: StreamedApp, always: Boolean = false) {
    val ops = app.store.streaming
    if (a.hosts.size == 1 && !always) {
        ops.stream(a.hosts.single().host.id, a.name)
        return
    }
    app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
        title = "Stream ${a.name} from",
        icon = FuseIcons.Cast,
        message = "Moonlight streams it from the computer you choose. One that sleeps is woken first.",
        options = a.hosts.map { h ->
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                "host.${h.host.id}", h.host.name, FuseIcons.Monitor,
                detail = when {
                    h.online == true -> h.info?.takeIf { it.busy }?.let { "On, streaming something now" } ?: "On and ready"
                    h.canWake -> "Asleep or away: Fuse wakes it first"
                    h.online == false -> "Not answering"
                    else -> "Checking"
                },
                onSelect = {
                    app.choice = null
                    ops.stream(h.host.id, a.name)
                },
            )
        },
    )
}

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
    /** What the shelf holds, for its count ("game", or "app" for what computers stream). */
    noun: String = "game",
    /** The chosen one is named under the shelf (no stage above to name it). */
    named: Boolean = true,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfHeader(title, icon, if (games.size == 1) "1 $noun" else "${games.size} ${noun}s", onSeeAll)
        LazyRow(
            state = state,
            modifier = Modifier.dragScroll(state, Orientation.Horizontal),
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
                    is EverywhereGame.Streamed -> GameIconTile(g.card, selected == i, size = tile, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                }
            }
            item("seeall") { SeeAllTile(selected == games.size, tile, onSeeAll) }
        }
        if (!named) return@Column
        val chosen = games.getOrNull(selected)
        // Room for both lines only under the shelf the selection is on, opening as it arrives: the
        // other shelves sit at the same rhythm as Home's and the systems shelf, with no empty band.
        val room by io.github.matiyaaa.fuse.ui.fuseline.fuselineDp(
            if (selected >= 0) Space.xxl + Space.l else 0.dp,
            Fuse.motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.BASE), label = "shelfName",
        )
        Column(Modifier.padding(horizontal = Space.gutter).height(room).clipToBounds()) {
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
        ShelfHeader("Systems", FuseIcons.Chip, if (systems.size == 1) "1 system" else "${systems.size} systems", null)
        LazyRow(state = state, modifier = Modifier.dragScroll(state, Orientation.Horizontal), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)), horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap)) {
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


/**
 * A shelf's title, with how many it holds and, at the far end, See all as a button (the triggers
 * reach it from the shelf too).
 */
@Composable
private fun ShelfHeader(title: String, icon: ImageVector, count: String, onSeeAll: (() -> Unit)?) {
    val c = Fuse.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconS, tint = c.accent)
        Spacer(Modifier.width(Space.s))
        FText(title, Fuse.type.titleSmall, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        FText(count, Fuse.type.caption, color = c.textFaint, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (onSeeAll != null) {
            Row(
                Modifier
                    .clip(io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape)
                    .fuseClickable(shape = io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape, role = Role.Button, onClick = onSeeAll)
                    .padding(horizontal = Space.m, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FText("See all", Fuse.type.label, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.width(Space.xxs))
                FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = c.textMuted)
            }
        }
    }
}

/**
 * The All page's stage: the chosen game's logo (or its name), and under it where it is (here, on
 * RomM, on another device, streamed), its system and year; a system says how many games it has.
 */
@Composable
private fun AllStage(chosen: EverywhereGame?, system: EverywhereSystem?, seeAllOf: String?, modifier: Modifier) {
    val c = Fuse.colors
    Box(modifier.padding(horizontal = Space.gutter).padding(top = Space.m, bottom = Space.m), contentAlignment = Alignment.BottomStart) {
        androidx.compose.animation.AnimatedContent(
            targetState = Triple(chosen?.let { it::class.simpleName + it.card.id.value }, system?.card?.platform?.id?.value, seeAllOf),
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            },
            label = "allStage",
        ) { _ ->
            Column(verticalArrangement = Arrangement.Bottom) {
                when {
                    chosen != null -> {
                        val card = chosen.card
                        val logo = card.art.logo
                        val name: @Composable () -> Unit = { FText(card.title, Fuse.type.display, maxLines = 2) }
                        if (logo != null) {
                            io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(
                                logo, Modifier.height(STAGE_LOGO).widthIn(max = 520.dp).fillMaxWidth(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit, focusX = 0f, focusY = 1f, loading = false, fallback = name,
                            )
                        } else name()
                        Spacer(Modifier.height(Space.s))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            WhereChip(chosen)
                            FText(listOfNotNull(card.platformShort, card.year?.toString()).joinToString("  ·  "), Fuse.type.label, color = c.textMuted, maxLines = 1)
                        }
                    }
                    system != null -> {
                        FText(system.card.platform.name, Fuse.type.display, maxLines = 1)
                        Spacer(Modifier.height(Space.s))
                        FText(listOfNotNull(gamesText(system.card.gameCount).takeIf { system.local }, if (system.local) "on this device" else "on RomM or your other devices").joinToString(" "), Fuse.type.label, color = c.textMuted, maxLines = 1)
                    }
                    seeAllOf != null -> {
                        FText("See all", Fuse.type.display, maxLines = 1)
                        Spacer(Modifier.height(Space.s))
                        FText("Every one, in a list of its own", Fuse.type.label, color = c.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Where a game is, as a small chip: its icon and a word or two, in the accent when it is here. */
@Composable
private fun WhereChip(g: EverywhereGame) {
    val c = Fuse.colors
    val here = g is EverywhereGame.Here
    val icon = when (g) {
        is EverywhereGame.Here -> FuseIcons.HardDrive
        is EverywhereGame.OnRomm -> FuseIcons.Cloud
        is EverywhereGame.Elsewhere -> FuseIcons.MonitorSmartphone
        is EverywhereGame.Streamed -> FuseIcons.Cast
    }
    Row(
        Modifier
            .clip(io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape)
            .background(if (here) c.accentSoft else c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f))
            .padding(horizontal = Space.m, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = Size.iconS, tint = if (here) c.accent else c.textMuted)
        Spacer(Modifier.width(Space.xs))
        FText(g.where, Fuse.type.label, color = if (here) c.text else c.textMuted, maxLines = 1)
    }
}

/** The stage's height: a third of the page, within reason. */
private fun stageHeight(page: Dp): Dp = (page * 0.26f).coerceIn(140.dp, 230.dp)

/** Shorter pages than this have no stage: the chosen game is named under its shelf instead. */
private val STAGE_FROM = 520.dp

/** The logo's height on the stage. */
private val STAGE_LOGO = 96.dp

/** How far the triggers jump along a shelf. */
private const val PAGE_STEP = 6
