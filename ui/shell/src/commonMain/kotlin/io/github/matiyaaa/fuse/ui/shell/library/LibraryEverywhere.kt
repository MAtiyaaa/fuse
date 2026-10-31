package io.github.matiyaaa.fuse.ui.shell.library

import io.github.matiyaaa.fuse.ui.designsystem.focus.glideTo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
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
import androidx.compose.ui.graphics.graphicsLayer
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
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
import io.github.matiyaaa.fuse.ui.shell.components.tileSize
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import io.github.matiyaaa.fuse.ui.designsystem.input.mouseHover
import kotlinx.coroutines.launch

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
internal fun sameGameKey(card: GameCard): String = card.platformId.value + "|" + sameTitleKey(card.title)

/**
 * A game's name as sources agree on it: "&" and "and" alike, a leading "The" or a trailing ", The"
 * dropped, and what is in brackets (a region, "[v131072]" for the update a Switch copy carries, a
 * release group's tag) left out, as is a bare "v0" at the end. "Mario & Sonic at the Olympic Games
 * [0100...][v0]" here and "Mario and Sonic at the Olympic Games" on RomM are one game, shown once.
 */
internal fun sameTitleKey(title: String): String {
    val plain = title.lowercase()
        .replace(BRACKETED, " ")
        .replace("&", " and ")
        .replace(TRAILING_VERSION, "")
        .replace(TRAILING_THE, "")
        .trim()
        .removePrefix("the ")
    return plain.filter { it.isLetterOrDigit() }.ifEmpty { title.lowercase().filter { it.isLetterOrDigit() } }
}

/**
 * The names in [cards] that more than one system has (Deadpool on PlayStation 3, PlayStation 4 and
 * Xbox 360): in a list of several systems, those games say which system each is ([sameTitleKey]).
 */
internal fun namesOnSeveralSystems(cards: List<GameCard>): Set<String> =
    cards.groupBy { sameTitleKey(it.title) }.filterValues { same -> same.distinctBy { it.platformId }.size > 1 }.keys

private val BRACKETED = Regex("""\s*[\[(][^\])]*[\])]""")
private val TRAILING_VERSION = Regex("""\s+v\d+(\.\d+)*\s*$""")
private val TRAILING_THE = Regex(""",\s*the\s*$""")

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

    // Where the games are: this device, RomM, the household's other devices, the computers that
    // stream. Shown as the first row when there is more than one, each opening its whole list.
    val householdState by store.reach.state.collectAsState()
    val sources = remember(platforms, rommOn, rommState.link, rommSystems, householdState, streamed, streamHosts, home.recentlyAdded, rommGames, householdRecent) {
        buildList {
            val here = platforms.filter { it.gameCount > 0 }
            add(SourceCard(SRC_HERE, "This device", FuseIcons.HardDrive, gamesText(here.sumOf { it.gameCount }), if (here.size == 1) "1 system" else "${here.size} systems", live = null, covers = home.recentlyAdded.take(COVER_POOL)))
            // RomM once it is turned on and set up; turned off, it has no card.
            if (rommOn && rommState.link != RommLink.OFF) {
                val (detail, live) = when (rommState.link) {
                    RommLink.ONLINE -> "Connected" to true
                    RommLink.CONNECTING -> "Connecting" to null
                    RommLink.OFFLINE -> "Not answering, its list as it was" to false
                    RommLink.SIGNED_OUT -> "Sign in again in Settings" to false
                    else -> "Off" to false
                }
                add(SourceCard(SRC_ROMM, "RomM", FuseIcons.Cloud, gamesText(rommSystems.sumOf { it.games }), detail, live, covers = rommGames.take(COVER_POOL).map { it.card }))
            }
            if (householdState.linked && householdState.devices.isNotEmpty()) {
                val on = householdState.devices.count { it.online }
                add(SourceCard(SRC_DEVICES, "Other devices", FuseIcons.MonitorSmartphone, gamesText(householdState.games), "$on of ${householdState.devices.size} on now", live = on > 0, covers = householdRecent.take(COVER_POOL).map { it.card }))
            }
            if (streamed.isNotEmpty()) {
                val on = streamHosts.count { it.online == true }
                add(SourceCard(SRC_STREAM, "Your computers", FuseIcons.Cast, if (streamed.size == 1) "1 app" else "${streamed.size} apps", if (streamHosts.size == 1) "1 computer streams" else "${streamHosts.size} computers stream", live = on > 0, covers = streamed.take(COVER_POOL).map { it.card }))
            }
        }
    }
    // Where you left off: the games last played here, to carry on from.
    val jump = remember(home.continuePlaying, home.recentlyPlayed) {
        home.continuePlaying.ifEmpty { home.recentlyPlayed }.take(JUMP_LIMIT).map { EverywhereGame.Here(it) }
    }

    val anything = recent.isNotEmpty() || systems.isNotEmpty() || jump.isNotEmpty() || onRomm.isNotEmpty() || elsewhere.isNotEmpty() || streamed.isNotEmpty()
    val rows = buildList {
        if (anything && sources.size > 1) add(ROW_SOURCES)
        if (jump.isNotEmpty()) add(ROW_JUMP)
        if (recent.isNotEmpty()) add(ROW_RECENT)
        if (systems.isNotEmpty()) add(ROW_SYSTEMS)
        if (rommOn && onRomm.isNotEmpty()) add(ROW_ROMM)
        if (elsewhere.isNotEmpty()) add(ROW_ELSEWHERE)
        if (streamed.isNotEmpty()) add(ROW_STREAM)
    }
    // Each shelf of games ends in See All (the systems and the sources open their own lists already).
    fun sizeOf(key: String) = when (key) {
        ROW_SOURCES -> sources.size
        ROW_JUMP -> jump.size + 1
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
        ROW_JUMP -> jump
        ROW_RECENT -> recent
        ROW_ROMM -> onRomm
        ROW_ELSEWHERE -> elsewhere
        ROW_STREAM -> streamed
        else -> emptyList()
    }
    val chosen = gamesOf(row).getOrNull(col)
    val chosenSystem = if (row == ROW_SYSTEMS) systems.getOrNull(col) else null
    val chosenSource = if (row == ROW_SOURCES) sources.getOrNull(col) else null
    // Games arriving (another device answering, RomM's list coming in) can reorder a shelf: the
    // selection stays on the game it was on, never on whatever slid into its place.
    val following = remember { HashMap<String, String>() }
    val lists = remember { HashMap<String, List<EverywhereGame>>() }
    for (key in listOf(ROW_JUMP, ROW_RECENT, ROW_ROMM, ROW_ELSEWHERE, ROW_STREAM)) {
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
        is EverywhereGame.Here -> app.openContextMenu(
            app.gameMenu(e.card).let { menu ->
                // For when nothing in view calls: a reel that lands on a game from the backlog.
                menu.copy(actions = menu.actions + io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("surprise", "Surprise Me", FuseIcons.Dice, detail = "A game from your backlog", onSelect = {
                    app.closeOverlays()
                    app.surprise = io.github.matiyaaa.fuse.ui.shell.app.SurpriseSpec()
                }))
            },
        )
        is EverywhereGame.OnRomm -> app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.romm.gameMenu(app, e.game))
        is EverywhereGame.Elsewhere -> app.openContextMenu(householdMenu(app, e.game, romm = rommState.canUpload))
        is EverywhereGame.Streamed -> streamFrom(app, e.app, always = true)
    }
    // Every game of the system from everywhere, installed or not, each once.
    fun openSystem(s: EverywhereSystem) = app.go(Route.SystemEverywhere(s.card.platform.id, s.card.platform.name, s.rommSlug))
    fun showView(segment: LibrarySegment, sort: io.github.matiyaaa.fuse.model.SortOrder? = null) {
        sort?.let { s -> app.store.updatePrefs { it.copy(librarySort = s) } }
        view.segment = segment
        view.grid.index = 0
        view.selectedId = null
    }
    fun openSource(s: SourceCard) = when (s.key) {
        SRC_HERE -> showView(LibrarySegment.INSTALLED)
        SRC_ROMM -> app.go(Route.RommGames(null, "On RomM"))
        SRC_DEVICES -> app.go(Route.HouseholdGames(null, "On other devices"))
        else -> app.openStreaming()
    }
    fun seeAll(key: String) = when (key) {
        ROW_JUMP -> showView(LibrarySegment.RECENT)
        // Everything on this device, newest first: the Installed view sorted by when games came.
        ROW_RECENT -> showView(LibrarySegment.INSTALLED, io.github.matiyaaa.fuse.model.SortOrder.RECENTLY_ADDED)
        ROW_ROMM -> app.go(Route.RommGames(null, "On RomM"))
        ROW_ELSEWHERE -> app.go(Route.HouseholdGames(null, "On other devices"))
        ROW_STREAM -> app.openStreaming()
        else -> Unit
    }
    // What Select does on what is chosen: the same for a press, a click and a tap.
    fun activate(key: String, i: Int) {
        when (key) {
            ROW_SOURCES -> sources.getOrNull(i)?.let(::openSource)
            ROW_SYSTEMS -> systems.getOrNull(i)?.let(::openSystem)
            else -> gamesOf(key).getOrNull(i)?.let(::open) ?: seeAll(key)
        }
    }
    // A click, a tap or the mouse pointing chooses it, as the D-pad would.
    fun point(key: String, i: Int) {
        app.focusZone = io.github.matiyaaa.fuse.ui.shell.app.FocusZone.CONTENT
        view.inHeader = false
        sel.row = rows.indexOf(key).coerceAtLeast(0)
        sel.setColumn(key, i)
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
            row == ROW_SOURCES || row == ROW_SYSTEMS -> listOf(Hint(HintButton.CONFIRM, "Open"))
            chosen == null && row != null -> listOf(Hint(HintButton.CONFIRM, "See all"))
            chosen is EverywhereGame.Streamed -> listOf(Hint(HintButton.CONFIRM, "Stream"), Hint(HintButton.OPTIONS, "Choose computer"))
            col == 0 && chosen != null -> listOf(Hint(HintButton.CONFIRM, if (chosen is EverywhereGame.Here) app.gameConfirmLabel else "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.DPAD, "Left: See all"))
            chosen is EverywhereGame.Here -> listOf(Hint(HintButton.CONFIRM, app.gameConfirmLabel), Hint(HintButton.OPTIONS, "Options"))
            chosen != null -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"))
            else -> emptyList()
        }
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> {
                // Left from a shelf's first game reaches its See all, at the far end, in one press.
                val key = row
                if (e.action == NavAction.LEFT && key != null && key.endsInSeeAll() && col == 0 && sizeOf(key) > 1) {
                    sel.setColumn(key, sizeOf(key) - 1)
                    return@InputLayer NavResult.MOVED
                }
                val r = if (rows.isEmpty()) NavResult.IGNORED else sel.move(e.action, rows, ::sizeOf)
                when {
                    // Up from the first row goes to the Library's tabs.
                    r == NavResult.IGNORED && e.action == NavAction.UP -> { view.inHeader = true; NavResult.MOVED }
                    r == NavResult.IGNORED -> NavResult.BLOCKED
                    else -> r
                }
            }
            NavAction.SELECT -> {
                val key = row ?: return@InputLayer NavResult.BLOCKED
                activate(key, col)
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                chosen?.let(::options)
                if (chosen != null) NavResult.ACTIVATED else NavResult.BLOCKED
            }
            // The triggers jump along a row a screenful at a time, to its See all at the end and back.
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
    val density = androidx.compose.ui.platform.LocalDensity.current
    val glide by androidx.compose.runtime.rememberUpdatedState(Fuse.motion.followScroll())
    BoxWithConstraints(modifier.testTag("library.all")) {
        if (rows.isEmpty()) {
            io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState(
                FuseIcons.Library, "Nothing here yet",
                Modifier.fillMaxSize().padding(horizontal = Space.gutter),
                message = "Every game you have shows here: the ones on this device, and what is on RomM and your other devices once they are set up.",
                actionLabel = "Add a game folder", actionSelected = focused, actionIcon = FuseIcons.FolderPlus,
                onAction = { app.go(Route.Settings("library")) },
                compact = maxHeight < STAGE_FROM,
            )
            InputLayer(enabled = focused) { e ->
                when (e.action) {
                    NavAction.SELECT -> { app.go(Route.Settings("library")); NavResult.ACTIVATED }
                    NavAction.UP -> { view.inHeader = true; NavResult.MOVED }
                    else -> NavResult.IGNORED
                }
            }
            return@BoxWithConstraints
        }
        // Tiles sized to the screen: a few whole ones and the edge of the next, so a phone held
        // upright shows a row of games rather than two, and a TV doesn't run them small.
        val metrics = LocalTileMetrics.current
        val tile = shelfTile(maxWidth, metrics.gap, metrics.icon)
        // What you're on, and where it is, always in the same place above the shelves, with its
        // buttons for a mouse or a finger. A short screen names it under its shelf instead.
        val stage = maxHeight >= STAGE_FROM
        val stageH = stageHeight(maxHeight)
        // Only scrolls when the chosen row won't be wholly on screen once its name has opened
        // under it, so rows that fit keep their titles. The glide measures the row every frame,
        // so the row it left closing above it never carries it past.
        LaunchedEffect(sel.row) {
            if (rows.isEmpty()) return@LaunchedEffect
            val to = sel.row.coerceIn(0, rows.lastIndex)
            val grows = if (!stage && rows[to].endsInSeeAll()) with(density) { (Space.xxl + Space.l).roundToPx() } else 0
            val info = list.layoutInfo
            val seen = info.visibleItemsInfo.firstOrNull { it.index == to }
            val whole = seen != null && seen.offset >= info.viewportStartOffset && seen.offset + seen.size + grows <= info.viewportEndOffset
            if (!whole) list.glideTo(to, spec = glide, settleMs = io.github.matiyaaa.fuse.ui.fuseline.Durations.SLOW)
        }
        if (stage) {
            AllStage(
                chosen, chosenSystem, chosenSource,
                seeAllOf = if (row != null && chosen == null && chosenSystem == null && chosenSource == null) shelfName(row) else null,
                seeAllGames = if (row != null && chosen == null) gamesOf(row).map { it.card } else emptyList(),
                onPrimary = { row?.let { activate(it, col) } },
                onOptions = chosen?.takeIf { it !is EverywhereGame.Streamed || it.app.hosts.size > 1 }?.let { c -> { options(c) } },
                primaryLabel = when (chosen) {
                    is EverywhereGame.Here -> app.gameConfirmLabel
                    is EverywhereGame.Streamed -> "Stream"
                    null -> if (chosenSystem != null || chosenSource != null) "Open" else "See all"
                    else -> "Open"
                },
                modifier = Modifier.fillMaxWidth().height(stageH),
            )
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = if (stage) stageH else 0.dp).fadingEdges(list, top = Space.l, bottom = Size.hintHeight + Space.s).dragScroll(list),
            contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            for ((r, key) in rows.withIndex()) item(key) {
                val selected = if (focused && sel.row == r) sel.column(key) else -1
                when (key) {
                    ROW_SOURCES -> SourcesBand(sources, selected, maxWidth, onOpen = { i -> point(key, i); activate(key, i) }, onPoint = { i -> point(key, i) })
                    ROW_SYSTEMS -> SystemsShelf(systems, selected, tile, onOpen = { i -> point(key, i); activate(key, i) }, onPoint = { i -> point(key, i) })
                    else -> {
                        val games = gamesOf(key)
                        EverywhereShelf(
                            shelfName(key), shelfIcon(key), games, selected, tile,
                            onOpen = { i -> point(key, i); open(games[i]) },
                            onOptions = { i -> point(key, i); options(games[i]) },
                            onSeeAll = { point(key, games.size); seeAll(key) },
                            onPoint = { i -> point(key, i) },
                            noun = if (key == ROW_STREAM) "app" else "game",
                            named = !stage,
                            // What See all opens holds every game of its place, not only the newest
                            // the shelf shows: it says so ("1,923 games", never the shelf's 40).
                            total = when (key) {
                                ROW_ELSEWHERE -> householdState.games
                                ROW_ROMM -> rommSystems.sumOf { (it.games - it.installed).coerceAtLeast(0) }
                                ROW_RECENT -> platforms.sumOf { it.gameCount }
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }
}

/** What a shelf's game is across arrivals: its kind and id, as the shelf keys its tiles. */
private fun identity(g: EverywhereGame): String = g::class.simpleName + g.card.id.value

private const val ROW_SOURCES = "sources"
private const val ROW_JUMP = "jump"
private const val ROW_RECENT = "recent"
private const val ROW_SYSTEMS = "systems"
private const val ROW_ROMM = "romm"
private const val ROW_ELSEWHERE = "elsewhere"
private const val ROW_STREAM = "stream"

/** Rows of games end in a See all tile; the sources and the systems open lists of their own. */
private fun String.endsInSeeAll(): Boolean = this != ROW_SOURCES && this != ROW_SYSTEMS

private fun shelfName(key: String): String = when (key) {
    ROW_JUMP -> "Jump Back In"
    ROW_RECENT -> "Recently Added"
    ROW_ROMM -> "On RomM"
    ROW_STREAM -> "Stream From Your Computers"
    ROW_SYSTEMS -> "Systems"
    ROW_SOURCES -> "Where Your Games Are"
    else -> "On Other Devices"
}

private fun shelfIcon(key: String): ImageVector = when (key) {
    ROW_JUMP -> FuseIcons.History
    ROW_RECENT -> FuseIcons.Sparkles
    ROW_ROMM -> FuseIcons.Cloud
    ROW_STREAM -> FuseIcons.Cast
    ROW_SYSTEMS -> FuseIcons.Chip
    else -> FuseIcons.MonitorSmartphone
}

/** A place games are, for the row of them at the top of All. [live] is whether it answers now, when that means anything. */
@Immutable
internal data class SourceCard(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val count: String,
    val detail: String,
    val live: Boolean?,
    /** A few of the place's games, fanned on its card as a collection's are (those with art first). */
    val covers: List<GameCard> = emptyList(),
)

/**
 * Each place's own colour, so the four read apart at a glance and the same everywhere they appear:
 * this device in the theme's accent, RomM in violet, the household in teal, the computers that
 * stream in the blue Moonlight's tile has.
 */
@Composable
private fun placeTint(key: String): androidx.compose.ui.graphics.Color = when (key) {
    SRC_HERE -> Fuse.colors.accent
    SRC_ROMM -> ROMM_TINT
    SRC_DEVICES -> DEVICES_TINT
    else -> STREAM_TINT
}

private val ROMM_TINT = androidx.compose.ui.graphics.Color(0xFF8B6CF6)
private val DEVICES_TINT = androidx.compose.ui.graphics.Color(0xFF1FB5A3)
private val STREAM_TINT = androidx.compose.ui.graphics.Color(0xFF5B8DEF)

private const val SRC_HERE = "here"
private const val SRC_ROMM = "romm"
private const val SRC_DEVICES = "devices"
private const val SRC_STREAM = "stream"

/** How many of a place's newest games its card picks its covers from (those with art first). */
private const val COVER_POOL = 12

/** The most games Jump Back In holds. */
private const val JUMP_LIMIT = 20

/**
 * A shelf's tile on a screen [width] wide: a few whole tiles and the edge of the next, never larger
 * than the theme's own tile, nor so small its art can't be read.
 */
internal fun shelfTile(width: Dp, gap: Dp, largest: Dp): Dp {
    val showing = when {
        width < 480.dp -> 3.4f
        width < 760.dp -> 4.4f
        width < 1100.dp -> 5.5f
        width < 1500.dp -> 6.6f
        else -> 7.6f
    }
    val room = width - Space.gutter - gap * kotlin.math.floor(showing)
    return (room / showing).coerceIn(MIN_SHELF_TILE, largest.coerceAtLeast(MIN_SHELF_TILE))
}

/** The smallest a shelf's tile gets. */
private val MIN_SHELF_TILE = 84.dp

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

/**
 * Where the games are, as a row of cards: how many each place holds and whether it answers now.
 * On a wide screen they share the width; on a narrow one they scroll.
 */
@Composable
private fun SourcesBand(sources: List<SourceCard>, selected: Int, width: Dp, onOpen: (Int) -> Unit, onPoint: (Int) -> Unit) {
    val state = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.designsystem.focus.GlideTo(state, if (selected >= 0) maxOf(0, selected - 1) else -1)
    val gap = LocalTileMetrics.current.gap
    val shared = (width - Space.gutter * 2 - gap * (sources.size - 1)) / sources.size
    val cardW = shared.coerceIn(SOURCE_MIN_W, SOURCE_MAX_W)
    LazyRow(
        state = state,
        modifier = Modifier.dragScroll(state, Orientation.Horizontal),
        // Below the cards, room for the chosen one's lift and its accent bar, so neither ever reaches
        // the next shelf's title.
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Size.sparkClearance + Space.s),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(sources, key = { _, s -> s.key }) { i, s ->
            SourceTile(s, selected == i, Modifier.width(cardW).mouseHover { onPoint(i) }) { onOpen(i) }
        }
    }
}

/**
 * A place games are, as a card made of its own games: a few of them fanned at its right over their
 * art (or the place's colour where there is none), and on the left its mark in the place's colour,
 * its name, how many games it holds and whether it answers now. Never a flat panel: what a place
 * holds is what it looks like.
 */
@Composable
private fun SourceTile(s: SourceCard, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val tint = placeTint(s.key)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(io.github.matiyaaa.fuse.ui.designsystem.theme.Radius.l)
    Tile(
        selected,
        modifier.height(SOURCE_H),
        shape = shape,
        cornerFraction = 0.12f,
        glow = tint,
        onClick = onClick,
        maxGrow = Space.s,
        label = "${s.title}, ${s.count}, ${s.detail}",
    ) {
        val night = io.github.matiyaaa.fuse.ui.shell.components.ART_NIGHT
        // The card's ground: the place's own colour, deep, lit a little from its top left.
        val ground = androidx.compose.ui.graphics.lerp(night, tint, 0.26f)
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(androidx.compose.ui.graphics.lerp(night, tint, 0.36f), ground),
                ),
            ),
        )
        // Its games stand in their own part of the card, at the right, and melt into the ground at
        // their left edge. The words never sit over a cover: on any art, at any card width, they are
        // on the place's own colour (a fan reaching under them made them hard to read on a handheld).
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(SOURCE_FAN_SHARE)) {
            if (s.covers.isNotEmpty()) {
                io.github.matiyaaa.fuse.ui.shell.components.CoverCollage(s.covers, SOURCE_H, fanAt = Alignment.BottomCenter, tint = tint, coverShare = SOURCE_COVER)
            } else {
                // Nothing to fan yet (a place still answering): its mark, large and quiet, where the covers would be.
                FuseIcon(s.icon, size = SOURCE_H * 0.5f, tint = tint.copy(alpha = 0.34f), modifier = Modifier.align(Alignment.Center))
            }
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to ground,
                        0.32f to ground.copy(alpha = 0.55f),
                        0.6f to ground.copy(alpha = 0f),
                    ),
                ),
            )
        }
        Row(
            Modifier.fillMaxHeight().fillMaxWidth(1f - SOURCE_FAN_SHARE * 0.62f).padding(start = Space.l, end = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(SOURCE_MARK)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.34f))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f), androidx.compose.ui.graphics.Color.Transparent))),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(s.icon, size = Size.iconM, tint = c.onArt)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
                FText(s.title, Fuse.type.label, color = c.onArt.copy(alpha = 0.88f), maxLines = 1)
                FText(s.count, Fuse.type.titleSmall.copy(fontFeatureSettings = "tnum"), color = c.onArt, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.live != null) {
                        Box(Modifier.size(STATUS_DOT).clip(CircleShape).background(if (s.live) c.success else c.onArtMuted.copy(alpha = 0.5f)))
                        Spacer(Modifier.width(Space.xs))
                    }
                    FText(s.detail, Fuse.type.caption, color = c.onArtMuted, maxLines = 1)
                }
            }
        }
    }
}

/** How much of a place's card its games' fan takes, at its right. */
private const val SOURCE_FAN_SHARE = 0.46f

/** A place's mark on its card. */
private val SOURCE_MARK = 40.dp

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
    onPoint: (Int) -> Unit,
    /** What the shelf holds, for its count ("game", or "app" for what computers stream). */
    noun: String = "game",
    /** The chosen one is named under the shelf (no stage above to name it). */
    named: Boolean = true,
    /** How many See all opens, when it holds more than the shelf shows. */
    total: Int? = null,
) {
    val state = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.designsystem.focus.GlideTo(state, if (selected >= 0) maxOf(0, selected - 1) else -1)
    // A name more than one system has here: each of those tiles says its system.
    val shared = remember(games) { namesOnSeveralSystems(games.map { it.card }) }
    Column {
        val count = (total ?: games.size).coerceAtLeast(games.size)
        val counted = if (count == 1) "1 $noun" else "${countText(count)} ${noun}s"
        ShelfHeader(title, icon, counted, onSeeAll)
        MouseScrolledRow(state) {
            LazyRow(
                state = state,
                modifier = Modifier.dragScroll(state, Orientation.Horizontal),
                contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)),
                horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap),
            ) {
                itemsIndexed(games, key = { _, g -> g::class.simpleName + g.card.id.value }) { i, g ->
                    val m = Modifier.mouseHover { onPoint(i) }
                    androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.shell.components.LocalTileTagsSystem provides (sameTitleKey(g.card.title) in shared)) {
                        when (g) {
                            is EverywhereGame.Here -> androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.shell.components.LocalTileShowsInstalled provides true) {
                                GameIconTile(g.card, selected == i, m, size = tile, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                            }
                            is EverywhereGame.OnRomm -> RommTile(g.game, selected == i, tile, m, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                            is EverywhereGame.Elsewhere -> HouseholdTile(g.game, selected == i, tile, m, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                            is EverywhereGame.Streamed -> GameIconTile(g.card, selected == i, m, size = tile, onClick = { onOpen(i) }, onLongClick = { onOptions(i) })
                        }
                    }
                }
                item("seeall") { SeeAllTile(games.map { it.card }, counted, selected == games.size, tile, Modifier.mouseHover { onPoint(games.size) }, onSeeAll) }
            }
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
private fun SystemsShelf(systems: List<EverywhereSystem>, selected: Int, tile: Dp, onOpen: (Int) -> Unit, onPoint: (Int) -> Unit) {
    val state = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.designsystem.focus.GlideTo(state, if (selected >= 0) maxOf(0, selected - 1) else -1)
    Column {
        ShelfHeader("Systems", FuseIcons.Chip, if (systems.size == 1) "1 system" else "${systems.size} systems", null)
        MouseScrolledRow(state) {
            LazyRow(state = state, modifier = Modifier.dragScroll(state, Orientation.Horizontal), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)), horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap)) {
                itemsIndexed(systems, key = { _, s -> s.card.platform.id.value }) { i, s ->
                    SystemTile(s.card, selected == i, Modifier.mouseHover { onPoint(i) }, size = tile, onClick = { onOpen(i) })
                }
            }
        }
    }
}

/**
 * A row a mouse can page through: pointing at it shows an arrow at each end it can still scroll
 * towards, a screenful a click. A controller, the keyboard and a finger move it as before.
 */
@Composable
private fun MouseScrolledRow(state: androidx.compose.foundation.lazy.LazyListState, content: @Composable () -> Unit) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(Modifier.hoverable(hover)) {
        content()
        fun page(forward: Boolean) {
            scope.launch {
                val width = state.layoutInfo.viewportSize.width
                state.animateScrollBy((if (forward) 1 else -1) * width * PAGE_SHARE)
            }
        }
        val c = Fuse.colors
        @Composable
        fun Arrow(forward: Boolean, show: Boolean) {
            io.github.matiyaaa.fuse.ui.fuseline.Appear(
                visible = hovered && show,
                modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = Space.s),
                enter = io.github.matiyaaa.fuse.ui.fuseline.fadeIn(Fuse.motion.fade(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST)),
                exit = io.github.matiyaaa.fuse.ui.fuseline.fadeOut(Fuse.motion.fade(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST)),
            ) {
                Box(Modifier.clip(CircleShape).background(c.surfaceOverlay)) {
                    io.github.matiyaaa.fuse.ui.designsystem.components.IconButton(
                        if (forward) FuseIcons.ChevronRight else FuseIcons.ChevronLeft, selected = false, onClick = { page(forward) },
                        size = ARROW_SIZE, contentDescription = if (forward) "Scroll right" else "Scroll left",
                    )
                }
            }
        }
        Arrow(false, state.canScrollBackward)
        Arrow(true, state.canScrollForward)
    }
}

/**
 * The tile that ends a shelf, into its whole list: the shelf's own games fanned as a collection's
 * are, "See all" and how many over them, and an arrow in a glass disc. The shelf's games are what
 * it opens, so they are what it shows.
 */
@Composable
private fun SeeAllTile(games: List<GameCard>, count: String, selected: Boolean, size: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val dims = with(io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt.current) { tileSize(size) }
    // A small tile (a short screen's shelf) says See all alone over a smaller fan: the shelf's own
    // line already says how many, and an arrow or a count there would crowd the words.
    val small = dims.width < SEE_ALL_SMALL
    Tile(selected, modifier.size(dims).semantics { contentDescription = "See all, $count" }, onClick = onClick, label = "See all, $count") {
        io.github.matiyaaa.fuse.ui.shell.components.CoverCollage(games, dims.height, coverShare = if (small) SEE_ALL_COVER_SMALL else SEE_ALL_COVER)
        // A shade from the top behind the words, so they read on a bright cover as on a dark one.
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(SEE_ALL_SHADE).align(Alignment.TopStart).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f),
                    0.55f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f),
                    1f to androidx.compose.ui.graphics.Color.Transparent,
                ),
            ),
        )
        Column(Modifier.align(Alignment.TopStart).padding(if (small) Space.s else Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText("See all", Fuse.type.bodyStrong, color = c.onArt, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (!small) {
                    Spacer(Modifier.width(Space.xxs))
                    FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = c.onArt)
                }
            }
            if (!small) FText(count, Fuse.type.caption, color = c.onArtMuted, maxLines = 1)
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
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).heightIn(min = Size.touch), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconS, tint = c.accent)
        Spacer(Modifier.width(Space.s))
        // The name keeps all the room but the count's, which goes first on a narrow screen.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            FText(title, Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.s))
            FText(count, Fuse.type.caption, color = c.textFaint, maxLines = 1)
        }
        if (onSeeAll != null) {
            Row(
                Modifier
                    .heightIn(min = Size.touch)
                    .clip(io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape)
                    .fuseClickable(shape = io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape, role = Role.Button, onClick = onSeeAll)
                    .padding(horizontal = Space.m),
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
 * The All page's stage: what is chosen, drawn small (a game's art, a system's or a place's mark),
 * with its name, where it is and its system and year, and the buttons a mouse or a finger uses on
 * it (a controller's own buttons do the same, as the hints say).
 */
@Composable
private fun AllStage(
    chosen: EverywhereGame?,
    system: EverywhereSystem?,
    source: SourceCard?,
    seeAllOf: String?,
    seeAllGames: List<GameCard>,
    onPrimary: () -> Unit,
    onOptions: (() -> Unit)?,
    primaryLabel: String,
    modifier: Modifier,
) {
    val c = Fuse.colors
    BoxWithConstraints(modifier.padding(horizontal = Space.gutter).padding(top = Space.m, bottom = Space.m), contentAlignment = Alignment.BottomStart) {
        val thumb = (maxHeight).coerceAtMost(STAGE_THUMB_MAX)
        val wide = maxWidth >= STAGE_WIDE
        val motion = Fuse.motion
        io.github.matiyaaa.fuse.ui.fuseline.Swap(
            targetState = listOf(chosen?.let { it::class.simpleName + it.card.id.value }, system?.card?.platform?.id?.value, source?.key, seeAllOf),
            transitionSpec = {
                io.github.matiyaaa.fuse.ui.fuseline.fadeIn(motion.fade(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST)) togetherWith
                    io.github.matiyaaa.fuse.ui.fuseline.fadeOut(motion.fade(io.github.matiyaaa.fuse.ui.fuseline.Durations.INSTANT))
            },
            contentAlignment = Alignment.BottomStart,
            label = "allStage",
        ) { _ ->
            Row(verticalAlignment = Alignment.Bottom) {
                val corner = Fuse.geometry.tileCornerFraction
                val shape = remember(corner) { io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(corner) }
                // What is chosen, as the object it is on its shelf, a little larger: the game's art
                // (or the art its tile makes for it), the system's card, a place's games.
                StageObject(shape) {
                    when {
                        chosen != null -> io.github.matiyaaa.fuse.ui.shell.components.GameThumb(chosen.card.art, thumb, shape, fallback = {
                            io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt(
                                chosen.card.title, chosen.card.accent.toColor(),
                                slot = io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot.ICON, label = chosen.card.platformShort,
                            )
                        })
                        system != null -> Box(Modifier.size(thumb).clip(shape)) { io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt(system.card) }
                        source != null -> Box(Modifier.size(thumb).clip(shape)) {
                            io.github.matiyaaa.fuse.ui.shell.components.CoverCollage(source.covers, thumb, tint = placeTint(source.key))
                            if (source.covers.isEmpty()) FuseIcon(source.icon, size = thumb * 0.4f, tint = c.onArt, modifier = Modifier.align(Alignment.Center))
                        }
                        else -> Box(Modifier.size(thumb).clip(shape)) {
                            io.github.matiyaaa.fuse.ui.shell.components.CoverCollage(seeAllGames, thumb)
                        }
                    }
                }
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                    val title = chosen?.card?.title ?: system?.card?.platform?.name ?: source?.title ?: "See all"
                    val logo = chosen?.card?.art?.logo
                    val name: @Composable () -> Unit = { FText(title, if (wide) Fuse.type.display else Fuse.type.title, maxLines = 2) }
                    if (logo != null && wide) {
                        io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(
                            logo, Modifier.height(STAGE_LOGO).widthIn(max = 480.dp).fillMaxWidth(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit, focusX = 0f, focusY = 1f, loading = false, fallback = name,
                        )
                    } else name()
                    Spacer(Modifier.height(Space.s))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        when {
                            chosen != null -> {
                                WhereChip(chosen)
                                FText(listOfNotNull(chosen.card.platformShort, chosen.card.year?.toString()).joinToString("  ·  "), Fuse.type.label, color = c.textMuted, maxLines = 1)
                            }
                            system != null -> FText(
                                listOfNotNull(gamesText(system.card.gameCount).takeIf { system.local }, if (system.local) "on this device" else "on RomM or your other devices").joinToString(" "),
                                Fuse.type.label, color = c.textMuted, maxLines = 1,
                            )
                            source != null -> FText("${source.count}  ·  ${source.detail}", Fuse.type.label, color = c.textMuted, maxLines = 1)
                            seeAllOf != null -> FText("All of $seeAllOf, in a list of its own", Fuse.type.label, color = c.textMuted, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        val primaryIcon = when {
                            chosen is EverywhereGame.Here -> FuseIcons.Play
                            chosen is EverywhereGame.Streamed -> FuseIcons.Cast
                            chosen != null -> FuseIcons.Info
                            else -> FuseIcons.ChevronRight
                        }
                        io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton(
                            primaryLabel, selected = false, onClick = onPrimary, icon = primaryIcon,
                            kind = io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind.PRIMARY, height = STAGE_BUTTON,
                        )
                        if (onOptions != null) {
                            io.github.matiyaaa.fuse.ui.designsystem.components.IconButton(FuseIcons.More, selected = false, onClick = onOptions, size = STAGE_BUTTON, contentDescription = "Options")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The stage's picture as an object standing over the room: the tiles' contact shadow at the raised
 * level and their light edge along its top, so it reads as the chosen tile brought forward rather
 * than a picture pasted on the page.
 */
@Composable
private fun StageObject(shape: androidx.compose.ui.graphics.Shape, content: @Composable () -> Unit) {
    val shadow = Fuse.colors.shadow
    Box(
        Modifier
            .graphicsLayer {
                shadowElevation = io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation.raised.shadow.toPx()
                spotShadowColor = shadow
                ambientShadowColor = shadow.copy(alpha = shadow.alpha * 0.5f)
                this.shape = shape
                clip = true
            }
            .lightEdge(shape, io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation.tile.edge),
    ) { content() }
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

/** The stage's height: about a fifth of the page, within reason. */
private fun stageHeight(page: Dp): Dp = (page * 0.24f).coerceIn(150.dp, 220.dp)

/** Shorter pages than this have no stage: the chosen game is named under its shelf instead. */
private val STAGE_FROM = 520.dp

/** From this width the stage sets its title in the display face, a game's logo in its place. */
private val STAGE_WIDE = 640.dp

/** The logo's height on the stage. */
private val STAGE_LOGO = 72.dp

/** The stage's art at its largest. */
private val STAGE_THUMB_MAX = 196.dp

/** The stage's buttons: a step under the standard height, so they sit with its words. */
private val STAGE_BUTTON = 40.dp

/** A place's card: its height, the share of it its covers stand, and its width's bounds. */
private val SOURCE_H = 112.dp
private const val SOURCE_COVER = 0.74f
private val SOURCE_MIN_W = 240.dp
private val SOURCE_MAX_W = 400.dp

/** The covers at the end of a shelf, as a share of its height, and the arrow's disc. */
private const val SEE_ALL_COVER = 0.52f

/** How far down the See all tile the shade behind its words reaches. */
private const val SEE_ALL_SHADE = 0.65f

/** A See all tile narrower than this says See all alone, over a smaller fan ([SEE_ALL_COVER_SMALL]). */
private val SEE_ALL_SMALL = 120.dp
private const val SEE_ALL_COVER_SMALL = 0.42f

/** The dot saying whether a source answers now. */
private val STATUS_DOT = 8.dp

/** A shelf's arrows for a mouse. */
private val ARROW_SIZE = 40.dp

/** How much of its width one click of a shelf's arrow scrolls. */
private const val PAGE_SHARE = 0.8f

/** How far the triggers jump along a shelf. */
private const val PAGE_STEP = 6

/** A count as people read it: "1,923", grouped by thousands. */
internal fun countText(n: Int): String {
    val digits = n.toString()
    if (n < 1000) return digits
    val out = StringBuilder()
    for ((i, ch) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(ch)
    }
    return out.toString()
}
