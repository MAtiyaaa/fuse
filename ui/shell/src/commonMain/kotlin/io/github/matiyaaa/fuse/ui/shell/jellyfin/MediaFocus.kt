package io.github.matiyaaa.fuse.ui.shell.jellyfin

import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType

/**
 * The Jellyfin items the main screen has shown lately, by the key their room carries ("jf:<id>"),
 * so the second screen can show what is in focus without asking the server again.
 */
internal object MediaFocus {
    private const val KEEP = 64
    private val items = object : LinkedHashMap<String, MediaItem>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > KEEP
    }

    fun keyOf(item: MediaItem) = "jf:${item.id}"

    fun put(item: MediaItem) = synchronized(items) { items[keyOf(item)] = item }

    /** The item behind [key], when it is one of Jellyfin's. */
    fun get(key: Any?): MediaItem? = (key as? String)?.takeIf { it.startsWith("jf:") }?.let { synchronized(items) { items[it] } }
}

/**
 * The facts line for the second screen: the year (a show's first), a film's length, its rating. A
 * show is never a run of years or a count of seasons.
 */
internal fun mediaFacts(item: MediaItem): String = listOfNotNull(
    when (item.type) {
        MediaType.EPISODE -> listOfNotNull(item.seriesName, item.episodeLabel).joinToString("  ·  ").ifEmpty { null }
        else -> item.year?.toString()
    },
    item.runtimeMs?.takeIf { item.type != MediaType.SERIES && item.type != MediaType.SEASON }?.let { minutes(it) },
    item.officialRating,
    item.rating?.let { r -> "${(r * 10).toInt() / 10.0}" },
).joinToString("  ·  ")
