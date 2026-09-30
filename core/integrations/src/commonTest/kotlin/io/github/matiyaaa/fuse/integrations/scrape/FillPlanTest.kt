package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillPlanTest {

    private val existing = MediaSet(
        listOf(
            MediaItem(MediaKind.BOXART, MediaSource.USER, localPath = "/custom/box.png"),
            MediaItem(MediaKind.HERO, MediaSource.STEAMGRIDDB, remoteUrl = "https://cdn/hero.png"),
            MediaItem(MediaKind.SCREENSHOT, MediaSource.USER, localPath = "/custom/shot.png"),
            MediaItem(MediaKind.SCREENSHOT, MediaSource.IGDB, remoteUrl = "https://igdb/shot.jpg", order = 1),
        ),
    )
    private val all = setOf(MediaKind.BOXART, MediaKind.HERO, MediaKind.LOGO, MediaKind.SCREENSHOT, MediaKind.GRID)

    @Test
    fun fillMissingOnlyFetchesEmptyKinds() {
        val plan = FillPlanner.plan(existing, MediaFillMode.FILL_MISSING, all)
        assertEquals(setOf(MediaKind.LOGO, MediaKind.GRID), plan.fetch)
        assertTrue(plan.replace.isEmpty())
        assertEquals(setOf(MediaKind.BOXART, MediaKind.SCREENSHOT), plan.skippedCustom)
        assertEquals(setOf(MediaKind.HERO), plan.skippedPresent)
    }

    @Test
    fun replaceSelectedSkipsCustomByDefault() {
        val plan = FillPlanner.plan(existing, MediaFillMode.REPLACE_SELECTED, setOf(MediaKind.BOXART, MediaKind.HERO, MediaKind.SCREENSHOT))
        assertEquals(setOf(MediaKind.HERO), plan.fetch)
        assertEquals(setOf(MediaKind.HERO), plan.replace)
        assertTrue(plan.replacesCustom.isEmpty())
        assertEquals(setOf(MediaKind.BOXART, MediaKind.SCREENSHOT), plan.skippedCustom)
    }

    @Test
    fun replaceSelectedWithIncludeCustomIsTheOnlyWayToReplaceUserMedia() {
        val plan = FillPlanner.plan(existing, MediaFillMode.REPLACE_SELECTED, setOf(MediaKind.BOXART), includeCustom = true)
        assertEquals(setOf(MediaKind.BOXART), plan.fetch)
        assertEquals(setOf(MediaKind.BOXART), plan.replacesCustom)
        assertEquals(1, FillPlanner.replaceable(existing, plan, MediaKind.BOXART).size)
    }

    @Test
    fun replaceAllNeverTouchesUserMediaEvenWithIncludeCustom() {
        val plan = FillPlanner.plan(existing, MediaFillMode.REPLACE_ALL, all, includeCustom = true)
        assertEquals(setOf(MediaKind.HERO, MediaKind.LOGO, MediaKind.GRID), plan.fetch)
        assertEquals(setOf(MediaKind.HERO), plan.replace)
        assertTrue(plan.replacesCustom.isEmpty())
        assertEquals(setOf(MediaKind.BOXART, MediaKind.SCREENSHOT), plan.skippedCustom)
    }

    @Test
    fun noModeEverMarksUserItemsReplaceableWithoutIncludeCustom() {
        for (mode in MediaFillMode.entries) {
            val plan = FillPlanner.plan(existing, mode, all)
            for (kind in all) {
                assertTrue(FillPlanner.replaceable(existing, plan, kind).none { it.isCustom }, "$mode $kind")
            }
        }
    }
}
