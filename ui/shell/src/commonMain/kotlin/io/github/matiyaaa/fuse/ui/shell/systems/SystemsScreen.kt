package io.github.matiyaaa.fuse.ui.shell.systems

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.carried
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
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
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Crossfade
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.Spring
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.LocateRequest
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.startLocate
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeWidget
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Every system Fuse found games for, arranged by hand like Home's board: each system has its own
 * place and size, on as many pages as wanted, under a slim header with the chosen system's logo or
 * name, its game count and emulator. Its artwork panel from the system art pack stands on the right
 * as the backdrop (a background the user chose fills the screen instead). Holding a system (or
 * holding confirm, or Options, Arrange Systems) arranges them exactly as Home is arranged: drag or
 * carry, resize, add back a system taken off, pages, Undo and Reset. The arrangement is kept with
 * the settings, so it follows the person's profile, and its order is the systems' order everywhere.
 */
@Composable
fun SystemsScreen(app: AppState) {
    val platforms by app.store.library.platforms.collectAsState()
    val systems = platforms.filter { it.gameCount > 0 }
    var chosen by remember { mutableStateOf<String?>(null) }
    val current = systems.firstOrNull { it.platform.id.value == chosen } ?: systems.firstOrNull()
    // On a two-screen device the board is kept once with the menus on top and once with them below.
    val twoScreens = app.platform.features.secondScreen || app.menusOnSecondScreen
    val looks = remember(twoScreens, app.menusOnSecondScreen) { SystemsLooks(twoScreens, app.menusOnSecondScreen) }
    val space = remember(systems, looks) { SystemsSpace(app, systems, looks) { w -> chosen = w?.target } }

    // Logos and art panels of the systems are decoded ahead, so the header never waits.
    val index = systems.indexOf(current).coerceAtLeast(0)
    PrefetchArt(remember(systems) { systems.map { it.art.logo } }, index, size = 360.dp)
    PrefetchArt(remember(systems) { systems.map { it.art.boxart } }, index, size = 480.dp)

    val reveal = rememberReveal()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactHeader = maxHeight < Size.touch * 12
        // A phone held upright gives the header the whole width; wider screens keep the art's side free.
        val headerWidth = if (maxWidth < Size.touch * 14) 1f else 0.62f
        // The art pack's panel stands on the right, unless the user chose a background for the system.
        if (current?.art?.hero == null) SystemShowcase(current, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(maxHeight * 0.46f))
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (compactHeader) Space.s else Space.xl))
            SystemHeader(current, compactHeader, Modifier.padding(horizontal = Space.gutter).reveal(reveal, 0), widthFraction = headerWidth)
            if (systems.isEmpty()) {
                PageEffect(Unit) {
                    app.hints = emptyList()
                    app.hero = null
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xl), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.Gamepad,
                        "No systems yet",
                        message = "Systems appear here once Fuse finds games for them. Add the folder your games are in from Settings, Library.",
                        compact = compactHeader,
                        modifier = Modifier.padding(bottom = Size.hintHeight),
                    )
                }
                return@Column
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                io.github.matiyaaa.fuse.ui.shell.home.BoardPages(app, space)
            }
        }
    }
}

/**
 * Which of the Systems page's two arrangements shows: the one for the screen the menus are on
 * ([flippedHere] when they are on the lower screen), or, while arranging, the other screen's.
 */
@androidx.compose.runtime.Stable
internal class SystemsLooks(val twoScreens: Boolean, val flippedHere: Boolean) {
    /** The other screen's look while it is being arranged from this one; null for this screen's own. */
    var editingFlipped by mutableStateOf<Boolean?>(null)
    val flipped: Boolean get() = editingFlipped ?: flippedHere
}

/** The Systems page's systems on a board of their own (see [SystemsScreen]). */
internal class SystemsSpace(
    private val app: AppState,
    private val systems: List<PlatformCard>,
    private val looks: SystemsLooks = SystemsLooks(twoScreens = false, flippedHere = false),
    private val onChosen: (HomeWidget?) -> Unit,
) : io.github.matiyaaa.fuse.ui.shell.home.BoardSpace() {
    private val byId = systems.associateBy { it.platform.id.value }

    override val key = "systems"
    override val name = "Systems"
    override val item = "system"
    override val top: Dp get() = Space.xs

    private fun card(w: HomeWidget?): PlatformCard? = w?.target?.let(byId::get)

    /**
     * The arrangement as kept, with every system it doesn't have yet joining the first page in the
     * systems' order, one card each. Systems without games here stay in it, unseen, for when they
     * come back.
     */
    override fun config(p: UiPrefs): HomeLayoutConfig {
        val kept = if (looks.flipped) p.systemsBoardFlipped ?: SystemsBoard.flippedFrom(p.systemsBoard) else p.systemsBoard
        return SystemsBoard.withNew(SystemsBoard.migrate(kept), systems.map { it.platform.id.value })
    }

    /**
     * Kept, and its reading order is the systems' order everywhere else (Home's Systems, the
     * Library). The lower screen's arrangement is its own: its order and sizes change nothing else.
     */
    override fun keep(p: UiPrefs, c: HomeLayoutConfig): UiPrefs =
        if (looks.flipped) p.copy(systemsBoardFlipped = c) else p.copy(systemsBoard = c, systemOrder = SystemsBoard.order(c, p.systemOrder))

    override val packed: Boolean get() = true
    override val addSize: io.github.matiyaaa.fuse.model.BoardSize get() = SystemsBoard.CARD

    override val look: io.github.matiyaaa.fuse.ui.shell.home.BoardLook?
        get() = if (!looks.twoScreens) null else io.github.matiyaaa.fuse.ui.shell.home.BoardLook(
            labels = listOf("Fuse Mode", "Flipped"),
            icons = listOf(FuseIcons.PanelTop, FuseIcons.PanelBottom),
            editing = if (looks.flipped) 1 else 0,
            here = if (looks.flippedHere) 1 else 0,
            pick = { i -> looks.editingFlipped = (i == 1).takeIf { it != looks.flippedHere } },
        )

    override fun arrangingEnded() {
        looks.editingFlipped = null
    }

    /** Whether the look shown is for the lower screen, and what it is called in settings. */
    private val lookKey: String get() = if (looks.flipped) SystemsBoard.FLIPPED else SystemsBoard.FUSE

    /** A row's worth already being written down, so it is written once. */
    private var noting: Pair<String, Int>? = null

    override fun shown(c: HomeLayoutConfig, page: Int) = c.boardWidgets(page).filter { it.visible && it.target in byId }
    override fun title(w: HomeWidget) = card(w)?.platform?.name ?: "System"

    /**
     * The board's columns: two to a card, as many cards as suit this screen and as many more as
     * the person asked for in Settings. Arranging the other screen's look from this one uses the
     * cards that screen showed last time, so it looks as it will there.
     */
    override fun columns(narrow: Boolean, small: Boolean, width: Dp): Int {
        val p = app.store.prefs.value
        val key = lookKey
        val step = if (looks.flipped) p.systemTileStepFlipped else p.systemTileStep
        val cards = if (looks.flipped == looks.flippedHere) {
            SystemsBoard.cards(narrow, small, width).also { n ->
                if (p.systemsCardsSeen[key] != n && noting != key to n) {
                    noting = key to n
                    app.scope.launch { app.store.updatePrefs { it.copy(systemsCardsSeen = it.systemsCardsSeen + (key to n)) } }
                }
            }
        } else {
            p.systemsCardsSeen[key] ?: if (looks.flipped) SystemsBoard.FLIPPED_CARDS else SystemsBoard.cards(narrow, small, width)
        }
        return SystemsBoard.columns(cards, step)
    }

    /**
     * A row's height: as tall as a column is wide (with the gaps evened out), so two columns by two
     * rows is a true square, the size of a game's box art, and a card (three by two) keeps its shape.
     */
    override fun cellHeight(cellW: Dp, gapX: Dp, gapY: Dp, narrow: Boolean): Dp =
        (cellW + (gapX - gapY) / 2).coerceIn(CELL_MIN, CELL_MAX)

    override val maxHeight: Int get() = SystemsBoard.MAX_ROWS
    override fun allowed(size: io.github.matiyaaa.fuse.model.BoardSize, columns: Int) = SystemsBoard.allowed(size, columns)
    override fun resizeStep(rect: io.github.matiyaaa.fuse.ui.shell.home.BoardRect, action: NavAction, columns: Int) = SystemsBoard.step(rect, action, columns)

    @Composable
    override fun Face(w: HomeWidget, size: io.github.matiyaaa.fuse.model.BoardSize, at: Int) {
        val c = card(w) ?: return
        // Narrower than a card: the system's picture on its own square tile, the size of a game's box art.
        if (size.width < SystemsBoard.GRAIN) {
            val prefs by app.store.prefs.collectAsState()
            SystemTileFace(c, prefs.systemTiles[c.platform.id.value])
        } else {
            SystemCardFace(c)
        }
    }

    @Composable
    override fun glow(w: HomeWidget, at: Int): Color = card(w)?.platform?.accent?.toColor() ?: Fuse.colors.accent

    override fun hero(w: HomeWidget?, at: Int): HeroSource? = card(w)?.let { HeroSource(it.platform.id, it.art.hero, it.platform.accent.toColor()) }
    override fun chosen(w: HomeWidget?) = onChosen(w)
    override fun open(w: HomeWidget, at: Int) {
        card(w)?.let { app.go(Route.PlatformGames(it.platform.id)) }
    }

    /** Systems taken off the board, to put back on this page. */
    override fun addable(c: HomeLayoutConfig, page: Int): List<io.github.matiyaaa.fuse.ui.shell.home.Addable> =
        SystemsBoard.hidden(c)
            .mapNotNull { w -> card(w)?.let { io.github.matiyaaa.fuse.ui.shell.home.Addable("add.${w.id}", it.platform.name, FuseIcons.Gamepad, w, detail = gamesText(it.gameCount)) } }

    /** Back from wherever it was kept, onto [page], shown, in the first free place. */
    override fun add(c: HomeLayoutConfig, page: Int, a: io.github.matiyaaa.fuse.ui.shell.home.Addable) = SystemsBoard.putBack(c, page, a.widget)

    /** Taken off, a system is kept hidden where it was, so it doesn't come straight back as new. */
    override fun removed(list: List<HomeWidget>, w: HomeWidget) = SystemsBoard.takeOff(list, w.id)

    override fun resetTitle(page: Int) = if (page == 0) "Put Systems back as they came?" else "Clear this page?"
    override fun resetMessage(page: Int) = if (page == 0) {
        "Every system on this page goes back to one card, in order, and systems you took off come back. Undo brings your arrangement back."
    } else {
        "Its systems go back to the first page. The page stays, and Undo brings them back."
    }
    override fun resetLabel(page: Int) = if (page == 0) "Reset Systems" else "Clear page"
    override fun reset(page: Int) {
        app.store.updatePrefs { p ->
            val c = config(p)
            val next = if (page == 0) {
                c.withBoard(0, c.boardWidgets(0).map { it.copy(visible = true, width = SystemsBoard.CARD.width, height = SystemsBoard.CARD.height, spots = emptyMap()) })
            } else {
                c.withBoard(page, emptyList())
            }
            keep(p, next)
        }
    }
    override fun resetDone(page: Int) = if (page == 0) "Systems are back as they came" else "This page's systems are back on the first"

    override val emptyPage = "Put the systems you want together here: handhelds on one page, home consoles on another. The right stick or a swipe turns between pages."
    override val removePageMessage = "Its systems go back to the first page. Their games stay as they are."

    /** Every system on [page] made [size]. */
    private fun sizeAll(page: Int, size: io.github.matiyaaa.fuse.model.BoardSize) {
        app.store.updatePrefs { p ->
            val c = config(p)
            keep(p, c.withBoard(page, c.boardWidgets(page).map { it.copy(width = size.width, height = size.height, spots = emptyMap()) }))
        }
        app.toasts.show(if (size == SystemsBoard.SMALL) "Every system on this page is a small tile" else "Every system on this page is a card")
    }

    override fun menu(w: HomeWidget?, arranging: Boolean, actions: List<MenuAction>): ContextMenuSpec {
        val c = card(w)
        if (c == null || arranging) {
            val all = if (arranging) listOf(
                MenuAction("all.small", "Make Every System Small", FuseIcons.Grid, detail = "Each the size of a game's box art, with the system's picture", onSelect = { app.closeOverlays(); sizeAll(shownPage, SystemsBoard.SMALL) }),
                MenuAction("all.cards", "Make Every System a Card", FuseIcons.RectHorizontal, detail = "Each one card, with its art and logo", onSelect = { app.closeOverlays(); sizeAll(shownPage, SystemsBoard.CARD) }),
            ) else emptyList()
            return ContextMenuSpec(title = c?.platform?.name ?: "Systems", subtitle = "Systems", icon = FuseIcons.Grid, actions = actions + all + listOfNotNull(c?.let { tileLookAction(it) }))
        }
        val own = app.systemMenu(c)
        return own.copy(actions = own.actions.take(1) + actions + listOf(tileLookAction(c)) + own.actions.drop(1))
    }

    private fun tileLookAction(c: PlatformCard) = MenuAction(
        "tile", "Small Tile", FuseIcons.Palette, detail = "Picture, pattern and colour when ${c.platform.shortName} is small",
        trailing = Trailing.Chevron, onSelect = { app.systemTileMenu(c) },
    )

    /** The page the board shows, as Home's pages remember it. */
    var shownPage: Int = 0

    override fun chosenPage(page: Int) {
        shownPage = page
    }

    private companion object {
        val CELL_MIN = 30.dp
        val CELL_MAX = 210.dp
    }
}

/** The Systems page's arrangement rules, apart from drawing it. */
internal object SystemsBoard {
    private val CARD_TARGET = 300.dp

    /** Columns of the grid to one card. Rows are as tall as columns are wide, two to a card. */
    const val GRAIN = 3
    const val ROWS = 2

    /** A system's usual size: one card, three columns by two rows. */
    val CARD = io.github.matiyaaa.fuse.model.BoardSize(GRAIN, ROWS)

    /** A small system: a square two by two, the size of a game's box art. */
    val SMALL = io.github.matiyaaa.fuse.model.BoardSize(2, ROWS)

    /** The tallest a system can be: three cards. */
    const val MAX_ROWS = ROWS * 3

    /** The widest a system can be: four cards, never wider than the board's whole cards. */
    private fun maxWidth(columns: Int) = minOf(GRAIN * 4, columns / GRAIN * GRAIN).coerceAtLeast(GRAIN)

    /**
     * [size] made one a system can be: a small square, or whole cards across; and whole cards
     * down (a small square can be as tall as a card, two cards or three, like a tall box).
     */
    fun allowed(size: io.github.matiyaaa.fuse.model.BoardSize, columns: Int): io.github.matiyaaa.fuse.model.BoardSize {
        val width = if (size.width < GRAIN) SMALL.width else (kotlin.math.round(size.width / GRAIN.toFloat()).toInt() * GRAIN).coerceIn(GRAIN, maxWidth(columns))
        val height = (kotlin.math.round(size.height / ROWS.toFloat()).toInt() * ROWS).coerceIn(ROWS, MAX_ROWS)
        return io.github.matiyaaa.fuse.model.BoardSize(width, height)
    }

    /**
     * One controller step of resizing: right makes a small square a card, then a card wider by a
     * card; left the other way, down to the small square; down and up a card taller or shorter.
     */
    fun step(rect: io.github.matiyaaa.fuse.ui.shell.home.BoardRect, action: NavAction, columns: Int): io.github.matiyaaa.fuse.ui.shell.home.BoardRect? {
        val w = rect.width
        val h = rect.height
        return when (action) {
            NavAction.RIGHT -> (if (w < GRAIN) GRAIN else w + GRAIN).takeIf { it <= maxWidth(columns) }?.let { rect.copy(width = it) }
            NavAction.LEFT -> when {
                w > GRAIN -> rect.copy(width = w - GRAIN)
                w == GRAIN -> rect.copy(width = SMALL.width)
                else -> null
            }
            NavAction.DOWN -> (h + ROWS).takeIf { it <= MAX_ROWS }?.let { rect.copy(height = it) }
            NavAction.UP -> (h - ROWS).takeIf { it >= ROWS }?.let { rect.copy(height = it) }
            else -> null
        }
    }

    /** The two looks' names where they are kept: the menus on top, and below. */
    const val FUSE = "fuse"
    const val FLIPPED = "flipped"

    /** Cards across the lower screen until it has been seen: three, as a Thor's has. */
    const val FLIPPED_CARDS = 3

    /** The most cards a row can gain over its usual count (Settings, Systems, System size). */
    const val MAX_STEP = 2

    /** The board's columns for [cards] across, [step] more than usual. */
    fun columns(cards: Int, step: Int): Int = (cards + step.coerceIn(0, MAX_STEP)) * GRAIN

    /**
     * Cards across the Systems board for a screen [width] wide: about six across a 1080p screen
     * and a TV, never fewer than three. A handheld's screen ([small]) keeps the grid it always had,
     * about a seventh of its width a card and never smaller than a thumb (five across a Thor's
     * upper screen), so its systems stay the size they were; a phone held upright ([narrow]) shows two.
     */
    fun cards(narrow: Boolean, small: Boolean, width: Dp): Int = when {
        narrow -> 2
        small -> {
            val usable = width - Space.gutter * 2
            val target = (width * 0.135f).coerceAtLeast(Size.touch * 2 + Space.l)
            ((usable + Space.m) / (target + Space.m)).toInt().coerceIn(if (width < Size.touch * 12) 2 else 3, 8)
        }
        else -> (width / CARD_TARGET).roundToInt().coerceIn(3, 6)
    }

    /** One system's card on the board. */
    fun tile(id: String) = HomeWidget(id = "system.$id", kind = io.github.matiyaaa.fuse.model.WidgetKind.SYSTEMS, order = 0, target = id, width = CARD.width, height = CARD.height)

    /**
     * [c] in [GRAIN] columns and [ROWS] rows to a card: an arrangement kept before a system could be
     * small keeps each system's size in cards, and its order. Places come from the order now, so none
     * are kept.
     */
    fun migrate(c: HomeLayoutConfig): HomeLayoutConfig {
        if (c.grain >= GRAIN) return c
        fun scaled(w: HomeWidget) = w.copy(
            width = ((w.width ?: 1) * GRAIN).coerceAtMost(GRAIN * 4),
            height = ((w.height ?: 1) * ROWS).coerceAtMost(MAX_ROWS),
            spots = emptyMap(),
        )
        return c.copy(board = c.board?.map(::scaled), pages = c.pages.map { pg -> pg.copy(widgets = pg.widgets.map(::scaled)) }, grain = GRAIN)
    }

    /** The lower screen's arrangement before it is first arranged there: [main]'s pages and order, every system one card. */
    fun flippedFrom(main: HomeLayoutConfig): HomeLayoutConfig {
        val m = migrate(main)
        fun card(w: HomeWidget) = w.copy(width = CARD.width, height = CARD.height, spots = emptyMap())
        return m.copy(board = m.board?.map(::card), pages = m.pages.map { pg -> pg.copy(widgets = pg.widgets.map(::card)) })
    }

    private fun all(c: HomeLayoutConfig) = c.board.orEmpty() + c.pages.flatMap { it.widgets }

    /**
     * [c] with every system of [ids] it doesn't have yet joining the first page, in that order, one
     * card each. Systems it has without games here stay in it, unseen, for when they come back.
     */
    fun withNew(c: HomeLayoutConfig, ids: List<String>): HomeLayoutConfig {
        val placed = all(c).mapNotNullTo(HashSet()) { it.target }
        val fresh = ids.filter { it !in placed }.map(::tile)
        if (c.board != null && fresh.isEmpty()) return c
        return c.copy(board = (c.board.orEmpty() + fresh).mapIndexed { i, w -> w.copy(order = i) })
    }

    /** The systems' order as [c] reads, page after page, then any of [before] it doesn't name. */
    fun order(c: HomeLayoutConfig, before: List<String>): List<String> {
        val order = all(c).mapNotNull { it.target }.distinct()
        return order + before.filter { it !in order }
    }

    /** Systems taken off the board. */
    fun hidden(c: HomeLayoutConfig): List<HomeWidget> = all(c).filter { !it.visible }

    /** [list] with system [id] taken off: kept hidden where it was. */
    fun takeOff(list: List<HomeWidget>, id: String) = list.map { if (it.id == id) it.copy(visible = false) else it }

    /** [c] with [w] back from wherever it was kept, onto [page], shown, in the first free place. */
    fun putBack(c: HomeLayoutConfig, page: Int, w: HomeWidget): HomeLayoutConfig {
        var out = c
        for (pg in 0 until c.pageCount) out = out.withBoard(pg, out.boardWidgets(pg).filterNot { it.id == w.id })
        val list = out.boardWidgets(page)
        return out.withBoard(page, (list + w.copy(visible = true, spots = emptyMap())).mapIndexed { i, x -> x.copy(order = i) })
    }
}

/**
 * A system's face on the Systems board, at any size it is given: its own square art or icon whole
 * where it has one; otherwise the system's colour with the art pack's panel standing at the right
 * (no wider than suits the card's height) and its logo (or name) set inside the coloured part,
 * kept clear of the panel and of the card's rounded corners however wide, tall or small it is.
 */
@Composable
private fun SystemCardFace(card: PlatformCard) {
    val art = card.art
    if ((art.square ?: art.icon) != null) {
        SystemCardArt(card)
        return
    }
    val c = Fuse.colors
    val accent = card.platform.accent.toColor()
    val cornerFraction = Fuse.geometry.tileCornerFraction
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val short = minOf(w, h)
        GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
        // The panel keeps its tall shape: about two thirds of the card's height wide, at most half the card.
        val panel = if (art.boxart != null) minOf(h * 0.62f, w * 0.5f) else 0.dp
        if (art.boxart != null) {
            io.github.matiyaaa.fuse.ui.shell.components.SystemPanel(art, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(panel))
            PanelMelt(accent, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(panel))
        }
        // A floor under the logo, so it reads on any colour.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to c.artScrim.copy(alpha = c.artScrim.alpha * 0.6f))) })
        // Clear of the rounded corner (a curve's inset is under half its radius) and in proportion to the card.
        val inset = maxOf(short * 0.08f, short * cornerFraction * 0.5f, Space.xs)
        // The coloured part left of the panel, which the panel's melt overlaps a little.
        val roomW = (w - panel * 0.8f - inset * 2).coerceAtLeast(short * 0.3f)
        val logoH = minOf(h * 0.3f, roomW * 0.42f, h - inset * 2).coerceAtLeast(Space.s)
        Box(Modifier.align(Alignment.BottomStart).padding(inset).width(roomW).height(logoH), contentAlignment = Alignment.BottomStart) {
            val name: @Composable () -> Unit = {
                val style = Fuse.type.title
                BasicText(
                    card.platform.shortName,
                    style = style.copy(color = c.onArt),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.4f, maxFontSize = style.fontSize * 2.2f),
                )
            }
            if (art.logo != null) {
                Artwork(art.logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, focusX = 0f, focusY = 1f, tint = c.onArt, fallback = name)
            } else {
                name()
            }
        }
        if (!card.emulatorInstalled) {
            IconBadge(FuseIcons.Warning, Modifier.align(Alignment.TopEnd).padding(inset * 0.75f), tint = c.warning, background = c.artScrim, size = Size.badge)
        }
    }
}

/**
 * The left edge of an art pack's panel, melted into the card's colour with a plain gradient drawn
 * over it: no offscreen layer per card, which a grid of a dozen systems can't afford on a handheld.
 */
@Composable
private fun PanelMelt(accent: Color, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            val brush = Brush.horizontalGradient(0f to accent.copy(alpha = 0.92f), 0.3f to accent.copy(alpha = 0.45f), 0.62f to Color.Transparent)
            onDrawBehind { drawRect(brush) }
        },
    )
}

/**
 * A small square standing for a system in lists, pickers, search results and page headers: its own
 * square art or icon where it has one, else its logo on its colour, else its short name set as
 * large as fits ("PS2", "Switch"), so systems never become ambiguous initials.
 */
@Composable
internal fun SystemMark(card: PlatformCard, size: Dp, modifier: Modifier = Modifier) {
    val accent = card.platform.accent.toColor()
    val fraction = Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f
    val shape = remember(fraction) { SquircleShape.fraction(fraction) }
    val name: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val style = Fuse.type.titleSmall
            BasicText(
                card.platform.shortName,
                Modifier.padding(horizontal = size * 0.12f),
                style = style.copy(color = Fuse.colors.onArt, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.45f, maxFontSize = style.fontSize * (size / Size.thumbL).coerceIn(0.7f, 1.6f)),
            )
        }
    }
    val generated: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
            val logo = card.art.logo
            if (logo != null) {
                Artwork(
                    logo,
                    Modifier.fillMaxSize().padding(size * 0.16f),
                    contentScale = ContentScale.Fit,
                    tint = Fuse.colors.onArt,
                    fallback = name,
                )
            } else {
                name()
            }
        }
    }
    Box(modifier.size(size).clip(shape)) {
        val own = card.art.square ?: card.art.icon
        if (own != null) Artwork(own, Modifier.fillMaxSize(), fallback = generated) else generated()
    }
}

internal fun gamesText(n: Int) = "$n ${if (n == 1) "game" else "games"}"

/** What is wrong with a system's firmware, when Fuse looked and knows: missing or partly found. */
private fun firmwareProblem(card: PlatformCard): String? = when (card.bios.state) {
    BiosState.MISSING -> "Firmware missing"
    BiosState.PARTIAL -> "Firmware partly found"
    else -> null
}

/**
 * The focused system, told big: its maker and year as a small line on top, its logo (or name), then
 * how many games it has and the emulator they start in, or a warning when none is installed.
 * Firmware details live on the system's page. [collapse] folds it away upwards (0 shows it all, 1
 * hides it), for a system's page scrolled down its games.
 *
 * With [showMeta] (the Systems screen) the name sits in a slot as tall as a logo, so moving between
 * systems with and without a logo never moves the grid below.
 */
@Composable
internal fun SystemHeader(
    card: PlatformCard?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.62f,
    collapse: Float = 0f,
    /** The maker line, game count and emulator around the logo; the system's own page leaves them out. */
    showMeta: Boolean = true,
    logoHeight: Dp = if (compact) Size.touch - Space.s else Size.touch + Space.l,
    nameStyle: TextStyle = if (compact) Fuse.type.title else Fuse.type.display,
    /** Said in place of the maker line while the system is being moved ("Moving, place 3 of 11"). */
    moving: String? = null,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Swap(
        targetState = card,
        modifier = modifier.fillMaxWidth(widthFraction).foldAway(collapse),
        contentKey = { it?.platform?.id },
        transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.exit(Durations.INSTANT)) },
        contentAlignment = Alignment.BottomStart,
        label = "system header",
    ) { s ->
        if (s == null) {
            Spacer(Modifier.height(logoHeight))
            return@Swap
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s)) {
            if (showMeta) {
                val eyebrow = listOfNotNull(s.platform.manufacturer?.uppercase(), s.platform.releaseYear?.toString()).joinToString("  ·  ")
                Row(Modifier.height(Size.iconS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (moving != null) {
                        // Carried: the line says where it is now, so each step of the D-pad is felt.
                        FuseIcon(FuseIcons.Move, size = Size.iconS, tint = c.text)
                        FText(moving.uppercase(), Fuse.type.overline.tabular(), color = c.text, maxLines = 1)
                    } else {
                        Box(Modifier.size(Size.dot).background(s.platform.accent.toColor(), CircleShape))
                        FText(eyebrow.ifEmpty { "SYSTEM" }, Fuse.type.overline.tabular(), color = c.textMuted, maxLines = 1)
                    }
                }
            }
            val name: @Composable () -> Unit = {
                FText(s.platform.name, nameStyle, color = c.text, maxLines = 1)
            }
            Box(if (showMeta) Modifier.height(logoHeight) else Modifier, contentAlignment = Alignment.BottomStart) {
                if (s.art.logo != null) {
                    Artwork(
                        s.art.logo,
                        Modifier.height(logoHeight).fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                        focusX = 0f,
                        focusY = if (showMeta) 1f else 0.5f,
                        tint = c.text,
                        fallback = name,
                    )
                } else {
                    name()
                }
            }
            if (showMeta) BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Only firmware Fuse knows is missing is told; one it can't check is never a warning.
                val problem = firmwareProblem(s)
                // Where the line would crowd (a phone held upright), the firmware gets its own line.
                val apart = problem != null && maxWidth < Size.touch * 8
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        FText(gamesText(s.gameCount), Fuse.type.bodyStrong.tabular(), color = c.text, maxLines = 1)
                        FText("·", Fuse.type.body, color = c.textFaint)
                        if (s.emulatorInstalled && s.emulatorName != null) {
                            FuseIcon(FuseIcons.Chip, size = Size.iconS, tint = c.textMuted)
                            FText(s.emulatorName, Fuse.type.body, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        } else {
                            FuseIcon(FuseIcons.Warning, size = Size.iconS, tint = c.warning)
                            FText("No emulator installed", Fuse.type.body, color = c.warning, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        }
                        if (problem != null && !apart) {
                            FText("·", Fuse.type.body, color = c.textFaint)
                            FirmwareProblem(problem)
                        }
                    }
                    if (problem != null && apart) FirmwareProblem(problem)
                }
            }
        }
    }
}

/** A firmware warning in the header: a key and what is wrong, in the warning colour. */
@Composable
private fun FirmwareProblem(problem: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FuseIcon(FuseIcons.Key, size = Size.iconS, tint = Fuse.colors.warning)
        FText(problem, Fuse.type.body, color = Fuse.colors.warning, maxLines = 1)
    }
}

/**
 * Folds content away upwards by [fraction]: it slides up under its own top edge, fades out a little
 * ahead of the slide, and gives its height back to whatever follows.
 */
internal fun Modifier.foldAway(fraction: Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val f = fraction.coerceIn(0f, 1f)
        val h = (p.height * (1f - f)).roundToInt()
        layout(p.width, h) {
            p.placeWithLayer(0, h - p.height) { alpha = (1f - f * 1.6f).coerceIn(0f, 1f) }
        }
    }

/**
 * The system art pack's tall artwork panel (made for the right side of a frontend's system view),
 * fading into the background on its left and toward the bottom so the grid stays calm.
 */
@Composable
internal fun SystemShowcase(card: PlatformCard?, modifier: Modifier) {
    val art = card?.art?.boxart
    Crossfade(targetState = art, modifier = modifier, animationSpec = Fuse.motion.fade(Durations.SLOW), label = "showcase") { model ->
        if (model == null) return@Crossfade
        Artwork(
            model,
            Modifier.fillMaxSize().panelFade(),
            contentScale = ContentScale.Crop,
            focusX = 0.5f,
            focusY = 0.3f,
        )
    }
}

/**
 * Fades a side panel into the background: from nothing on its left to full on its right, clear of
 * the status bar at the top and quieter toward the bottom.
 */
internal fun Modifier.panelFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.55f to Color.Black), blendMode = BlendMode.DstIn)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.2f to Color.Black.copy(alpha = 0.8f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.15f)),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Options for a system (Context button or long press on its card). [onMove] adds "Move this system"
 * where systems can be arranged.
 */
fun AppState.systemMenu(card: PlatformCard, onMove: (() -> Unit)? = null): ContextMenuSpec {
    val p = card.platform
    val owner = MediaOwner.OfPlatform(p.id)
    return ContextMenuSpec(
        title = p.name,
        subtitle = listOfNotNull(gamesText(card.gameCount), card.emulatorName?.takeIf { card.emulatorInstalled }).joinToString("  ·  "),
        icon = FuseIcons.Gamepad,
        art = card.art.square ?: card.art.icon,
        actions = listOfNotNull(
            MenuAction("open", "Open", FuseIcons.Grid, onSelect = { closeOverlays(); go(Route.PlatformGames(p.id)) }),
            onMove?.let { move ->
                MenuAction("move", "Move this system", FuseIcons.Move, detail = "Or hold confirm. By touch, hold it and drag", onSelect = { closeOverlays(); move() })
            },
            MenuAction("settings", "System Settings", FuseIcons.Settings, trailing = Trailing.Chevron, onSelect = { closeOverlays(); go(Route.PlatformSettings(p.id)) }),
            MenuAction("media", "Change System Media", FuseIcons.Image, detail = "Icon, background and logo for ${p.shortName}", trailing = Trailing.Chevron, onSelect = {
                closeOverlays(); go(Route.Media(owner, p.name))
            }),
            MenuAction("default", "Restore Fuse Default Art", FuseIcons.RotateCcw, detail = "Fuse's own icon, background and logo for ${p.shortName}", onSelect = {
                closeOverlays()
                restoreSystemArt(listOf(p.id), p.shortName)
            }),
            artUndo?.let { u ->
                MenuAction("default.undo", "Undo Restore Art", FuseIcons.Undo, detail = "Puts back the art that was there", onSelect = { closeOverlays(); undoSystemArt(u) })
            },
            MenuAction("fill", "Fill Missing Game Art", FuseIcons.Wand, detail = "Only games without art; your custom art is never replaced", onSelect = {
                closeOverlays()
                store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, platform = p.id)
                toasts.show("Finding missing art for ${p.shortName}")
            }),
            MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Value(card.emulatorName ?: "None"), onSelect = { platformEmulatorPicker(card) }),
            MenuAction("openemu", "Open Emulator", FuseIcons.External, unavailableReason = if (!card.emulatorInstalled) "No emulator installed" else null, onSelect = {
                closeOverlays()
                val id = store.emulators.installed.value.firstOrNull { it.name == card.emulatorName }?.id
                if (id != null) scope.launch { store.emulators.openEmulator(id) }
            }),
            MenuAction("folder", "ROM Folders", FuseIcons.Folder, detail = card.romFolders.joinToString("\n").ifBlank { "None found" }, onSelect = {}),
            MenuAction("bios", "BIOS and Firmware", FuseIcons.Key, trailing = Trailing.Chevron, onSelect = { closeOverlays(); go(Route.PlatformSettings(p.id)) }),
            MenuAction("rescan", "Rescan", FuseIcons.Refresh, onSelect = {
                closeOverlays(); store.sources.rescan(ScanScope.PLATFORM, p.id); toasts.show("Rescanning ${p.shortName}")
            }),
            if (!store.cartridge.status.value.installed) null else MenuAction("cartridge", "Browse in Cartridge", FuseIcons.CloudDownload, onSelect = {
                closeOverlays(); store.cartridge.open(CartridgeRoute.Platform(p.id.value))
            }),
        ),
    )
}

fun AppState.platformEmulatorPicker(card: PlatformCard) {
    contextMenu = null
    val options = store.emulators.optionsFor(card.platform.id)
    choice = ChoiceSpec(
        title = "Emulator for ${card.platform.name}",
        message = if (options.none { it.installed }) "None installed yet. Fuse notices when you install one." else "Games use this unless they have their own choice.",
        options = listOf(
            MenuAction("auto", "Automatic", FuseIcons.Sparkles, detail = "The first installed emulator in Fuse's recommended order", onSelect = {
                scope.launch { store.emulators.setPlatformEmulator(card.platform.id, null) }
                choice = null
            }),
        ) + options.map { o ->
            // Where Fuse can be shown an emulator, one it didn't find can be located instead.
            val locate = !o.installed && store.emulators.canLocate
            MenuAction(
                "e${o.id}", o.name, FuseIcons.Chip,
                detail = if (locate) "Not found. Show Fuse where it is" else o.note,
                unavailableReason = if (o.installed || locate) null else "Not installed",
                onSelect = {
                    if (locate) {
                        startLocate(LocateRequest(o.id, o.name, platform = card.platform.id))
                    } else {
                        scope.launch { store.emulators.setPlatformEmulator(card.platform.id, o.id) }
                        choice = null
                    }
                },
            )
        },
    )
}

/**
 * Moves the system [key] to place [to] and saves the whole order, so Home, Systems and the
 * Library's system picker all follow it. The order is worked out from the saved order as it is at
 * this moment, never from a list a screen kept, so a move can't undo the one before it. Returns
 * the system's new place, or -1 when it isn't shown.
 */
fun AppState.moveSystem(key: String, to: Int): Int {
    var placed = -1
    store.updatePrefs { p ->
        val ids = SystemOrder.shown(store.library.platforms.value.map { it.platform.id.value }, p.systemOrder).toMutableList()
        val from = ids.indexOf(key)
        if (from < 0) return@updatePrefs p
        placed = to.coerceIn(0, ids.lastIndex)
        if (placed == from) return@updatePrefs p
        ids.add(placed, ids.removeAt(from))
        p.copy(systemOrder = SystemOrder.save(ids, p.systemOrder))
    }
    return placed
}

/** Moves the system [key] by [delta] places; its place when it can't go further, -1 when it isn't shown. */
fun AppState.moveSystemBy(key: String, delta: Int): Int {
    val ids = SystemOrder.shown(store.library.platforms.value.map { it.platform.id.value }, store.prefs.value.systemOrder)
    val from = ids.indexOf(key)
    if (from < 0) return -1
    if (from + delta !in ids.indices) return from
    return moveSystem(key, from + delta)
}

/** The rules for the systems' order, kept apart so they can be tested. */
internal object SystemOrder {
    /**
     * [shown] (the systems on screen, as the store listed them) in the order [saved] gives them:
     * saved ones first, the rest after them in the order they are listed, as the store does.
     */
    fun shown(shown: List<String>, saved: List<String>): List<String> {
        val rank = saved.withIndex().associate { (i, id) -> id to i }
        return shown.withIndex().sortedBy { (i, id) -> rank[id] ?: (saved.size + i) }.map { it.value }
    }

    /** The order to save: every shown system in [ids] order, then systems hidden right now as they were. */
    fun save(ids: List<String>, saved: List<String>): List<String> = ids + saved.filterNot { it in ids }
}

/**
 * Puts [ids] (every system when empty) back to Fuse's own art after asking, keeping what was there
 * so it can be undone from the same menu (or Settings, Systems) while Fuse runs.
 */
internal fun AppState.restoreSystemArt(ids: List<io.github.matiyaaa.fuse.model.PlatformId>, name: String?) {
    confirm = io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec(
        if (name != null) "Restore Fuse's art for $name?" else "Restore Fuse's art for every system?",
        "Downloaded and chosen art is put aside and Fuse's own shows again. Nothing is downloaded for ${if (name != null) "it" else "them"} by itself after this. You can undo it.",
        "Restore",
    ) {
        scope.launch {
            val undo = store.media.restoreDefaultSystemArt(ids)
            if (undo == null) {
                toasts.show("System art can't be changed here")
                return@launch
            }
            artUndo = undo
            toasts.show(if (name != null) "Fuse's art is back for $name. Undo it in its options" else "Fuse's art is back for ${undo.count} systems. Undo it in Settings, Systems", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.SUCCESS, icon = FuseIcons.RotateCcw, durationMs = 5000)
        }
    }
}

internal fun AppState.undoSystemArt(u: io.github.matiyaaa.fuse.ui.shell.store.ArtUndo) {
    scope.launch {
        store.media.undoSystemArt(u)
        artUndo = null
        toasts.show("The art that was there is back", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.SUCCESS, icon = FuseIcons.Undo)
    }
}
