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
) {
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
