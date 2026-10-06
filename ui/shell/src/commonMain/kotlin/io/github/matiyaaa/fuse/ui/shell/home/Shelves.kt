package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.WidgetSpan
import io.github.matiyaaa.fuse.model.isRow
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
    /** The widget that produced this shelf. */
    val widgets: List<HomeWidget>,
) {
    /** The widget kind this shelf shows. */
    val kind: WidgetKind? get() = widgets.firstOrNull()?.kind

    /**
     * How many things the shelf holds, for the quiet count after its title; none for widget shelves,
     * where a number would only count the cards.
     */
    val count: Int? get() = if (style == ShelfStyle.WIDGETS) null else items.size
}

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
    WidgetKind.JELLYFIN_CONTINUE -> "Continue watching"
    WidgetKind.JELLYFIN_NEXT_UP -> "Next up"
    WidgetKind.JELLYFIN_RECENTLY_ADDED -> "New on Jellyfin"
    WidgetKind.JELLYFIN_FAVORITES -> "Jellyfin favourites"
    WidgetKind.JELLYFIN_MOVIES -> "New films"
    WidgetKind.JELLYFIN_MUSIC -> "New music"
    WidgetKind.SYNC_STATUS -> "Fuse Sync"
    WidgetKind.SYNC_DEVICES -> "Your devices"
}

/**
 * Turns the user's widget list into Network's rows: only the kinds that are rows ([isRow]); the
 * widgets that aren't (clock, storage, playtime and the like) live on the Fused board. Empty
 * rows are dropped so Home never shows a heading over nothing.
 */
fun buildShelves(widgets: List<HomeWidget>, feed: HomeFeed, achievementsOn: Boolean): List<Shelf> {
    val out = mutableListOf<Shelf>()
    for (w in widgets.filter { it.visible && it.kind.isRow }.sortedBy { it.order }) {
        val shelf = when (w.kind) {
            WidgetKind.CONTINUE_PLAYING -> gameShelf(w, feed.continuePlaying, ShelfStyle.WIDE) { "Played ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it.lastPlayedAt ?: 0)}" }
            WidgetKind.RECENTLY_PLAYED -> gameShelf(w, feed.recentlyPlayed, ShelfStyle.ICON)
            WidgetKind.FAVORITES -> gameShelf(w, feed.favorites, ShelfStyle.ICON)
            WidgetKind.RECENTLY_ADDED -> gameShelf(w, feed.recentlyAdded, ShelfStyle.ICON)
            WidgetKind.PINNED_GAMES -> gameShelf(w, feed.pinnedGames, ShelfStyle.ICON)
            WidgetKind.MOST_PLAYED -> gameShelf(w, feed.mostPlayed, ShelfStyle.ICON) { io.github.matiyaaa.fuse.ui.shell.components.playtimeText(it.playSeconds) }
            WidgetKind.SYSTEMS -> feed.systems.takeIf { it.isNotEmpty() }?.let { list ->
                Shelf(w.id, w.kind.title(), ShelfStyle.SYSTEM, list.map { ShelfItem.System(it) }, listOf(w))
            }
            WidgetKind.PINNED_APPS -> feed.pinnedApps.takeIf { it.isNotEmpty() }?.let { list ->
                Shelf(w.id, w.kind.title(), ShelfStyle.APP, list.map { ShelfItem.App(it) }, listOf(w))
            }
            WidgetKind.COLLECTIONS -> feed.collections.takeIf { it.isNotEmpty() }?.let { list ->
                Shelf(w.id, w.kind.title(), ShelfStyle.COLLECTION, list.map { ShelfItem.Collection(it) }, listOf(w))
            }
            in MediaKinds -> if (mediaItems(w.kind, feed).isNotEmpty()) {
                Shelf(w.id, w.kind.title(), ShelfStyle.WIDGETS, listOf(ShelfItem.Widget(w.kind, WidgetSpan.WIDE)), listOf(w))
            } else null
            WidgetKind.RECENT_ACHIEVEMENTS -> if (achievementsOn && feed.achievements?.recent?.isNotEmpty() == true) {
                Shelf(w.id, w.kind.title(), ShelfStyle.WIDGETS, listOf(ShelfItem.Widget(w.kind, WidgetSpan.WIDE)), listOf(w))
            } else null
            else -> null
        }
        if (shelf != null) out += shelf
    }
    return out
}

private fun gameShelf(w: HomeWidget, games: List<GameCard>, style: ShelfStyle, caption: ((GameCard) -> String?)? = null): Shelf? =
    games.takeIf { it.isNotEmpty() }?.let { list ->
        Shelf(w.id, w.kind.title(), style, list.map { ShelfItem.Game(it, caption?.invoke(it)) }, listOf(w))
    }
