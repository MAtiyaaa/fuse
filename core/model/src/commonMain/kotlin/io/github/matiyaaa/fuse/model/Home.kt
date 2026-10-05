package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * Root destinations, in their default order. The user can hide and reorder them (Settings, Home).
 * Cartridge only shows while Cartridge is installed.
 */
@Serializable
enum class Destination { HOME, SYSTEMS, LIBRARY, ACHIEVEMENTS, APPS, CARTRIDGE }

@Serializable
enum class HomeMode {
    /** A continuous dashboard of rows. */
    FLOW,
    /** A spatial board of movable tiles ("channels"). */
    CHANNELS,
}

/** Everything that can sit on Home: Flow's rows (the [isRow] kinds), and every kind on the Channels board. */
@Serializable
enum class WidgetKind(val defaultSpan: WidgetSpan) {
    CONTINUE_PLAYING(WidgetSpan.WIDE),
    RECENTLY_PLAYED(WidgetSpan.WIDE),
    FAVORITES(WidgetSpan.WIDE),
    RECENTLY_ADDED(WidgetSpan.WIDE),
    PINNED_GAMES(WidgetSpan.WIDE),
    PINNED_APPS(WidgetSpan.WIDE),
    COLLECTIONS(WidgetSpan.WIDE),
    SYSTEMS(WidgetSpan.WIDE),
    RECENT_ACHIEVEMENT(WidgetSpan.MEDIUM),
    RECENT_ACHIEVEMENTS(WidgetSpan.WIDE),
    ACHIEVEMENT_PROGRESS(WidgetSpan.MEDIUM),
    RECENTLY_MASTERED(WidgetSpan.MEDIUM),
    PLAYTIME_TOTAL(WidgetSpan.SMALL),
    PLAYTIME_WEEK(WidgetSpan.MEDIUM),
    MOST_PLAYED(WidgetSpan.MEDIUM),
    CURRENT_GAME(WidgetSpan.MEDIUM),
    CARTRIDGE_DOWNLOADS(WidgetSpan.MEDIUM),
    STORAGE(WidgetSpan.SMALL),
    CLOCK(WidgetSpan.SMALL),

    /** Jellyfin, offered only while it is turned on: what you were watching, what's next, what's new. */
    JELLYFIN_CONTINUE(WidgetSpan.WIDE),
    JELLYFIN_NEXT_UP(WidgetSpan.WIDE),
    JELLYFIN_RECENTLY_ADDED(WidgetSpan.WIDE),
}

@Serializable
enum class WidgetSpan(val columns: Int, val rows: Int) { SMALL(1, 1), MEDIUM(2, 1), WIDE(4, 1), LARGE(2, 2) }

/** RetroAchievements' widgets: shown and offered only once RetroAchievements is connected. */
val WidgetKind.isAchievements: Boolean
    get() = this == WidgetKind.RECENT_ACHIEVEMENT || this == WidgetKind.RECENT_ACHIEVEMENTS ||
        this == WidgetKind.ACHIEVEMENT_PROGRESS || this == WidgetKind.RECENTLY_MASTERED

/** True for the kinds Flow shows as rows; the rest (clock, storage, playtime...) live on the board only. */
val WidgetKind.isRow: Boolean
    get() = this in RowKinds

private val RowKinds = setOf(
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.FAVORITES, WidgetKind.RECENTLY_ADDED,
    WidgetKind.PINNED_GAMES, WidgetKind.MOST_PLAYED, WidgetKind.PINNED_APPS, WidgetKind.COLLECTIONS,
    WidgetKind.SYSTEMS, WidgetKind.RECENT_ACHIEVEMENTS,
    WidgetKind.JELLYFIN_CONTINUE, WidgetKind.JELLYFIN_NEXT_UP, WidgetKind.JELLYFIN_RECENTLY_ADDED,
)

/**
 * A widget's size on the Channels board, in cells of its grid: one to [MAX_WIDTH] across and one
 * to [MAX_HEIGHT] down.
 */
data class BoardSize(val width: Int, val height: Int) {
    /** This size made to fit a board of [columns] (a phone held upright has two). */
    fun fit(columns: Int): BoardSize = BoardSize(width.coerceIn(1, minOf(MAX_WIDTH, columns.coerceAtLeast(1))), height.coerceIn(1, MAX_HEIGHT))

    companion object {
        const val MAX_WIDTH = 4
        const val MAX_HEIGHT = 3
    }
}

/** Where a widget's top left corner sits on the board, in cells. */
@Serializable
data class GridSpot(val column: Int, val row: Int)

/** The size a widget of this kind takes on the board until it is resized. */
val WidgetKind.boardSize: BoardSize
    get() = when (this) {
        WidgetKind.CONTINUE_PLAYING -> BoardSize(2, 2)
        WidgetKind.CLOCK, WidgetKind.STORAGE, WidgetKind.PLAYTIME_TOTAL, WidgetKind.RECENT_ACHIEVEMENT,
        WidgetKind.ACHIEVEMENT_PROGRESS, WidgetKind.RECENTLY_MASTERED,
        -> BoardSize(1, 1)
        else -> BoardSize(2, 1)
    }

/** One of the board's pages after the first: its own widgets, arranged on their own. */
@Serializable
data class HomePage(
    val id: String,
    val widgets: List<HomeWidget> = emptyList(),
)

/** A placed widget. In Flow only [order] matters; on the board [width] and [height] do too. */
@Serializable
data class HomeWidget(
    val id: String,
    val kind: WidgetKind,
    val order: Int,
    val span: WidgetSpan = kind.defaultSpan,
    val column: Int = 0,
    val row: Int = 0,
    /** Game, app or collection a pinned tile points at. */
    val target: String? = null,
    val visible: Boolean = true,
    /** Cells across on the board; null for the kind's own size. */
    val width: Int? = null,
    /** Cells down on the board; null for the kind's own size. */
    val height: Int? = null,
    /**
     * Where it sits on the board, per board width in columns (a landscape board has four, a phone
     * held upright two), so each keeps its own arrangement. Empty until the board is first arranged:
     * the board then packs widgets in [order], the way it did before widgets had places.
     */
    val spots: Map<Int, GridSpot> = emptyMap(),
) {
    /** Its size on the board: what it was resized to, else its kind's. */
    val boardSize: BoardSize
        get() = BoardSize(width ?: kind.boardSize.width, height ?: kind.boardSize.height).fit(BoardSize.MAX_WIDTH)
}

@Serializable
data class HomeLayoutConfig(
    val mode: HomeMode = HomeMode.FLOW,
    /** Flow's rows, in [HomeWidget.order]. Kinds that aren't rows are kept here but not shown. */
    val widgets: List<HomeWidget> = DefaultFlow,
    /** The Channels board, in reading order; null until it is first changed (see [boardWidgets]). */
    val board: List<HomeWidget>? = null,
    /** The board's pages after the first ([board]), in order; empty while Home is one page. */
    val pages: List<HomePage> = emptyList(),
) {
    /** How many pages the board has: the first, and [pages]. */
    val pageCount: Int get() = 1 + pages.size

    /** The widgets of [page] (0 is the first), in order; an unknown page is empty. */
    fun boardWidgets(page: Int): List<HomeWidget> = if (page <= 0) boardWidgets() else pages.getOrNull(page - 1)?.widgets.orEmpty()

    /** [page]'s widgets as stored, for Undo: null for a first page never changed. */
    fun storedBoard(page: Int): List<HomeWidget>? = if (page <= 0) board else pages.getOrNull(page - 1)?.widgets

    /** This config with [page]'s widgets replaced by [widgets] (null puts the first page back as it came). */
    fun withBoard(page: Int, widgets: List<HomeWidget>?): HomeLayoutConfig = when {
        page <= 0 -> copy(board = widgets)
        page - 1 in pages.indices -> copy(pages = pages.mapIndexed { i, p -> if (i == page - 1) p.copy(widgets = widgets.orEmpty()) else p })
        else -> this
    }

    /** A new, empty page at the end, with an id no other page has. */
    fun addPage(): HomeLayoutConfig {
        val taken = pages.map { it.id }.toSet()
        val id = generateSequence(pages.size + 2) { it + 1 }.map { "page$it" }.first { it !in taken }
        return copy(pages = pages + HomePage(id))
    }

    /** Without [page] (never the first, which is always there). */
    fun removePage(page: Int): HomeLayoutConfig = if (page <= 0 || page - 1 !in pages.indices) this else copy(pages = pages.filterIndexed { i, _ -> i != page - 1 })

    /** [page] moved to [to] among the pages after the first (the first page stays first). */
    fun movePage(page: Int, to: Int): HomeLayoutConfig {
        if (page <= 0 || to <= 0 || page - 1 !in pages.indices || to - 1 !in pages.indices || page == to) return this
        val list = pages.toMutableList()
        list.add(to - 1, list.removeAt(page - 1))
        return copy(pages = list)
    }

    /**
     * The board's widgets in order. Before the board is ever changed it is made from what Home
     * already had: the default board for a Home left as it came, else the shown widgets in their
     * order, each at its kind's size, so a board arranged before boards had sizes keeps its order.
     */
    fun boardWidgets(): List<HomeWidget> = board ?: if (widgets.map { it.kind }.let { it == LegacyFlow || it == DefaultFlow.map { w -> w.kind } }) {
        DefaultBoard
    } else {
        widgets.filter { it.visible }.sortedBy { it.order }.mapIndexed { i, w -> w.copy(order = i) }
    }

    companion object {
        val DefaultFlow = listOf(
            WidgetKind.CONTINUE_PLAYING,
            WidgetKind.SYSTEMS,
            WidgetKind.RECENTLY_ADDED,
            WidgetKind.FAVORITES,
            WidgetKind.RECENT_ACHIEVEMENTS,
            WidgetKind.PINNED_APPS,
        ).mapIndexed { i, k -> HomeWidget(id = k.name.lowercase(), kind = k, order = i) }

        /** Home's widgets as they came before 0.1.6, when Flow and the board shared one list. */
        private val LegacyFlow = listOf(
            WidgetKind.CONTINUE_PLAYING, WidgetKind.SYSTEMS, WidgetKind.RECENTLY_ADDED, WidgetKind.FAVORITES,
            WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.PLAYTIME_WEEK, WidgetKind.CARTRIDGE_DOWNLOADS, WidgetKind.PINNED_APPS,
        )

        /** The board as it comes: what you were playing large, the time and space beside it, then the rest. */
        val DefaultBoard = listOf(
            WidgetKind.CONTINUE_PLAYING,
            WidgetKind.CLOCK,
            WidgetKind.STORAGE,
            WidgetKind.PLAYTIME_WEEK,
            WidgetKind.SYSTEMS,
            WidgetKind.RECENTLY_ADDED,
            WidgetKind.FAVORITES,
            WidgetKind.RECENT_ACHIEVEMENTS,
            WidgetKind.CARTRIDGE_DOWNLOADS,
            WidgetKind.PINNED_APPS,
        ).mapIndexed { i, k -> HomeWidget(id = k.name.lowercase(), kind = k, order = i) }
    }
}
