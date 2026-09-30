package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource

/** What a media fill job should fetch for one owner, and what it may replace. */
data class FillPlan(
    /** Kinds to ask providers for. */
    val fetch: Set<MediaKind>,
    /** Kinds in [fetch] that already have media which the new art replaces. */
    val replace: Set<MediaKind>,
    /** Kinds in [replace] whose existing media includes the user's own ([MediaSource.USER]) items. */
    val replacesCustom: Set<MediaKind>,
    /** Selected kinds left alone because the user set custom media for them. */
    val skippedCustom: Set<MediaKind>,
    /** Selected kinds left alone because they already have media ([MediaFillMode.FILL_MISSING]). */
    val skippedPresent: Set<MediaKind>,
) {
    val isEmpty: Boolean get() = fetch.isEmpty()
}

/**
 * Decides what a fill job may touch. Custom media ([MediaSource.USER]) is never replaced, except
 * with [MediaFillMode.REPLACE_SELECTED] and `includeCustom = true`, which is the explicit "reset my
 * custom art for these kinds" action. [MediaFillMode.REPLACE_ALL] never touches custom media.
 */
object FillPlanner {

    /** Plans a job over the [selected] kinds (REPLACE_ALL usually passes every kind it can fill). */
    fun plan(
        existing: MediaSet,
        mode: MediaFillMode,
        selected: Set<MediaKind>,
        includeCustom: Boolean = false,
    ): FillPlan {
        val fetch = LinkedHashSet<MediaKind>()
        val replace = LinkedHashSet<MediaKind>()
        val replacesCustom = LinkedHashSet<MediaKind>()
        val skippedCustom = LinkedHashSet<MediaKind>()
        val skippedPresent = LinkedHashSet<MediaKind>()
        for (kind in MediaKind.entries.filter { it in selected }) {
            val items = existing.all(kind)
            val hasCustom = items.any { it.isCustom }
            when (mode) {
                MediaFillMode.FILL_MISSING ->
                    if (items.isEmpty()) fetch += kind else if (hasCustom) skippedCustom += kind else skippedPresent += kind
                MediaFillMode.REPLACE_SELECTED ->
                    if (hasCustom && !includeCustom) {
                        skippedCustom += kind
                    } else {
                        fetch += kind
                        if (items.isNotEmpty()) replace += kind
                        if (hasCustom) replacesCustom += kind
                    }
                MediaFillMode.REPLACE_ALL ->
                    if (hasCustom) {
                        skippedCustom += kind
                    } else {
                        fetch += kind
                        if (items.isNotEmpty()) replace += kind
                    }
            }
        }
        return FillPlan(fetch, replace, replacesCustom, skippedCustom, skippedPresent)
    }

    /** The existing items the data layer may delete when it stores new art for [kind] under [plan]. */
    fun replaceable(existing: MediaSet, plan: FillPlan, kind: MediaKind): List<MediaItem> {
        if (kind !in plan.replace) return emptyList()
        val allowCustom = kind in plan.replacesCustom
        return existing.all(kind).filter { !it.isCustom || allowCustom }
    }
}
