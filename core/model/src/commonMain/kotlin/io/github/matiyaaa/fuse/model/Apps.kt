package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * A launchable application: an Android app from the launcher APIs, or a Linux .desktop entry.
 */
@Serializable
data class AppEntry(
    /** Android: package/activity. Linux: the .desktop file id. */
    val id: String,
    val label: String,
    val packageName: String,
    val isGame: Boolean,
    val customTitle: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val installedAt: Long? = null,
    val lastUsedAt: Long? = null,
) {
    val displayTitle: String get() = customTitle ?: label
}

@Serializable
enum class AppFilter { PINNED, GAMES, ALL }
