package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** How a shelf draws its items. */
enum class ShelfStyle { WIDE, ICON, SYSTEM, APP, WIDGETS, COLLECTION }

@Immutable
sealed interface ShelfItem {
    val key: String

    data class Game(val card: GameCard, val caption: String? = null) : ShelfItem {
        override val key = "g${card.id.value}"
    }

    data class System(val card: PlatformCard) : ShelfItem {
        override val key = "p${card.platform.id.value}"
    }

    data class App(val card: AppCard) : ShelfItem {
        override val key = "a${card.entry.id}"
    }

    data class Collection(val collection: GameCollection) : ShelfItem {
        override val key = "c${collection.id.value}"
    }

    data class Widget(val kind: WidgetKind, val span: WidgetSpan) : ShelfItem {
        override val key = "w${kind.name}"
    }
}

@Immutable
data class Shelf(
    val key: String,
    val title: String,
    val style: ShelfStyle,
    val items: List<ShelfItem>,
    /** The widgets that produced this shelf (several small widgets share one "At a glance" shelf). */
    val widgets: List<HomeWidget>,
)

private val glanceKinds = setOf(
    WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.ACHIEVEMENT_PROGRESS, WidgetKind.RECENTLY_MASTERED,
    WidgetKind.PLAYTIME_TOTAL, WidgetKind.PLAYTIME_WEEK, WidgetKind.MOST_PLAYED, WidgetKind.CURRENT_GAME,
    WidgetKind.CARTRIDGE_DOWNLOADS, WidgetKind.STORAGE, WidgetKind.CLOCK,
)

fun WidgetKind.title(): String = when (this) {
    WidgetKind.CONTINUE_PLAYING -> "Continue playing"
    WidgetKind.RECENTLY_PLAYED -> "Recently played"
    WidgetKind.FAVORITES -> "Favourites"
    WidgetKind.RECENTLY_ADDED -> "New in your library"
    WidgetKind.PINNED_GAMES -> "Pinned"
    WidgetKind.PINNED_APPS -> "Apps"
    WidgetKind.COLLECTIONS -> "Collections"
    WidgetKind.SYSTEMS -> "Systems"
    WidgetKind.RECENT_ACHIEVEMENT -> "Latest achievement"
    WidgetKind.RECENT_ACHIEVEMENTS -> "Recent achievements"
    WidgetKind.ACHIEVEMENT_PROGRESS -> "Achievement progress"
    WidgetKind.RECENTLY_MASTERED -> "Recently mastered"
    WidgetKind.PLAYTIME_TOTAL -> "Total playtime"
    WidgetKind.PLAYTIME_WEEK -> "This week"
    WidgetKind.MOST_PLAYED -> "Most played"
    WidgetKind.CURRENT_GAME -> "Now playing"
    WidgetKind.CARTRIDGE_DOWNLOADS -> "Cartridge"
    WidgetKind.STORAGE -> "Storage"
    WidgetKind.CLOCK -> "Clock"
}

/**
 * Turns the user's widget list into shelves. Game and app widgets become their own shelves; small
 * widgets that sit next to each other share an "At a glance" shelf. Empty shelves are dropped so
 * Home never shows a heading over nothing.
 */
fun buildShelves(widgets: List<HomeWidget>, feed: HomeFeed, achievementsOn: Boolean, cartridgeInstalled: Boolean): List<Shelf> {
    val out = mutableListOf<Shelf>()
    val glance = mutableListOf<HomeWidget>()
    fun flushGlance() {
        if (glance.isEmpty()) return
        val items = glance.filter { widgetHasContent(it.kind, feed, achievementsOn, cartridgeInstalled) }
            .map { ShelfItem.Widget(it.kind, it.span) }
        if (items.isNotEmpty()) {
            out += Shelf("glance-${glance.first().id}", "At a glance", ShelfStyle.WIDGETS, items, glance.toList())
        }
        glance.clear()
    }
    for (w in widgets.filter { it.visible }.sortedBy { it.order }) {
        if (w.kind in glanceKinds) {
            glance += w
            continue
        }
        flushGlance()
        val shelf = when (w.kind) {
            WidgetKind.CONTINUE_PLAYING -> gameShelf(w, feed.continuePlaying, ShelfStyle.WIDE) { "Played ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it.lastPlayedAt ?: 0)}" }
            WidgetKind.RECENTLY_PLAYED -> gameShelf(w, feed.recentlyPlayed, ShelfStyle.ICON)
            WidgetKind.FAVORITES -> gameShelf(w, feed.favorites, ShelfStyle.ICON)
            WidgetKind.RECENTLY_ADDED -> gameShelf(w, feed.recentlyAdded, ShelfStyle.ICON)
            WidgetKind.PINNED_GAMES -> gameShelf(w, feed.pinnedGames, ShelfStyle.ICON)
            WidgetKind.SYSTEMS -> feed.systems.takeIf { it.isNotEmpty() }?.let { list ->
                Shelf(w.id, w.kind.title(), ShelfStyle.SYSTEM, list.map { ShelfItem.System(it) }, listOf(w))
            }
            WidgetKind.PINNED_APPS -> feed.pinnedApps.takeIf { it.isNotEmpty() }?.let { list ->
                Shelf(w.id, w.kind.title(), ShelfStyle.APP, list.map { ShelfItem.App(it) }, listOf(w))
            }
            WidgetKind.COLLECTIONS -> feed.collections.takeIf { it.isNotEmpty() }?.let { list ->
                Shelf(w.id, w.kind.title(), ShelfStyle.COLLECTION, list.map { ShelfItem.Collection(it) }, listOf(w))
            }
            WidgetKind.RECENT_ACHIEVEMENTS -> if (achievementsOn && feed.achievements?.recent?.isNotEmpty() == true) {
                Shelf(w.id, w.kind.title(), ShelfStyle.WIDGETS, listOf(ShelfItem.Widget(w.kind, WidgetSpan.WIDE)), listOf(w))
            } else null
            else -> null
        }
        if (shelf != null) out += shelf
    }
    flushGlance()
    return out
}

private fun gameShelf(w: HomeWidget, games: List<GameCard>, style: ShelfStyle, caption: ((GameCard) -> String?)? = null): Shelf? =
    games.takeIf { it.isNotEmpty() }?.let { list ->
        Shelf(w.id, w.kind.title(), style, list.map { ShelfItem.Game(it, caption?.invoke(it)) }, listOf(w))
    }

private fun widgetHasContent(kind: WidgetKind, feed: HomeFeed, achievementsOn: Boolean, cartridgeInstalled: Boolean): Boolean = when (kind) {
    WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS -> achievementsOn && feed.achievements?.recent?.isNotEmpty() == true
    WidgetKind.ACHIEVEMENT_PROGRESS -> achievementsOn && feed.achievements?.inProgress?.isNotEmpty() == true
    WidgetKind.RECENTLY_MASTERED -> achievementsOn && feed.achievements?.recentlyMastered?.isNotEmpty() == true
    WidgetKind.PLAYTIME_TOTAL, WidgetKind.PLAYTIME_WEEK -> feed.playtime.totalSeconds > 0
    WidgetKind.MOST_PLAYED -> feed.mostPlayed.isNotEmpty()
    WidgetKind.CURRENT_GAME -> feed.playtime.currentGame != null
    WidgetKind.CARTRIDGE_DOWNLOADS -> cartridgeInstalled
    WidgetKind.STORAGE -> feed.storage != null
    WidgetKind.CLOCK -> true
    else -> true
}
