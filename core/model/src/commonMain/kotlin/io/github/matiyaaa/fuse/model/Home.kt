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

/** Everything that can sit on Home, in Flow rows or as Channel tiles. */
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
}

@Serializable
enum class WidgetSpan(val columns: Int, val rows: Int) { SMALL(1, 1), MEDIUM(2, 1), WIDE(4, 1), LARGE(2, 2) }

/** A placed widget. In Flow mode only [order] matters; in Channel mode [column]/[row] do too. */
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
)

@Serializable
data class HomeLayoutConfig(
    val mode: HomeMode = HomeMode.FLOW,
    val widgets: List<HomeWidget> = DefaultFlow,
) {
    companion object {
        val DefaultFlow = listOf(
            WidgetKind.CONTINUE_PLAYING,
            WidgetKind.SYSTEMS,
            WidgetKind.RECENTLY_ADDED,
            WidgetKind.FAVORITES,
            WidgetKind.RECENT_ACHIEVEMENTS,
            WidgetKind.PLAYTIME_WEEK,
            WidgetKind.CARTRIDGE_DOWNLOADS,
            WidgetKind.PINNED_APPS,
        ).mapIndexed { i, k -> HomeWidget(id = k.name.lowercase(), kind = k, order = i) }
    }
}
