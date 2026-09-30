package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.repo.AppOverride
import io.github.matiyaaa.fuse.data.repo.AppOverrideRepository
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallRepositoriesTest {
    @Test
    fun cacheHonoursExpiry() = runBlocking {
        TestDb().use { t ->
            val cache = t.data.cache
            cache.put("ra.user", "matiyaaa", """{"points":10}""", now = 1_000, ttlMs = 500)
            cache.put("ra.game", "1", listOf(1, 2, 3), ListSerializer(Int.serializer()), now = 1_000, ttlMs = null)
            assertEquals("""{"points":10}""", cache.getOrNull("ra.user", "matiyaaa", now = 1_499))
            assertNull(cache.getOrNull("ra.user", "matiyaaa", now = 1_500))
            assertTrue(cache.entry("ra.user", "matiyaaa")!!.isExpired(1_500), "stale data stays readable offline")
            assertEquals(listOf(1, 2, 3), cache.get("ra.game", "1", ListSerializer(Int.serializer()), now = Long.MAX_VALUE))
            assertEquals(1, cache.purgeExpired(2_000))
            assertNull(cache.entry("ra.user", "matiyaaa"))
            cache.clear("ra.game")
            assertNull(cache.entry("ra.game", "1"))
        }
    }

    @Test
    fun appOverrides() = runBlocking {
        TestDb().use { t ->
            val apps = t.data.apps
            apps.setPinned("b", true)
            apps.setPinned("a", true)
            apps.setCustomTitle("a", "  Browser ")
            apps.setHidden("c", true)
            apps.markUsed("a", 5_000)
            var all = apps.observeAll().first()
            assertEquals(0, all.getValue("b").sortOrder)
            assertEquals(1, all.getValue("a").sortOrder)
            apps.reorderPinned(listOf("a", "b"))
            all = apps.observeAll().first()
            assertEquals(listOf("a", "b"), all.values.filter { it.pinned }.sortedBy { it.sortOrder }.map { it.appId })
            assertEquals(AppOverride("a", "Browser", pinned = true, sortOrder = 0, lastUsedAt = 5_000), all["a"])

            val entries = listOf(AppEntry("a", "Chrome", "com.chrome", isGame = false), AppEntry("z", "Game", "com.game", isGame = true))
            val merged = AppOverrideRepository.applyTo(entries, all)
            assertEquals("Browser", merged[0].displayTitle)
            assertTrue(merged[0].pinned)
            assertEquals(entries[1], merged[1])

            apps.setPinned("a", false)
            assertNull(apps.get("a")!!.sortOrder)
            apps.clear("a")
            assertNull(apps.get("a"))
        }
    }

    @Test
    fun librarySourcesKeepTheirGamesWhenRemoved() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            val id = d.sources.add("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT)
            assertEquals(id, d.sources.add("/roms", "Again", LibrarySourceKind.ROMS_ROOT), "no duplicate sources")
            d.indexer.apply(report(folder("/roms/gba", listOf(scanned("/roms/gba/A.gba")))), 1_000, testCleaner)
            d.folderState.remember("/roms/snes", 5)
            d.folderState.remember("/romsbackup/gba", 5)
            d.sources.markScanned(id, 2_000)
            assertEquals(2_000, d.sources.get(id)!!.lastScanAt)
            d.sources.setEnabled(id, false)
            assertFalse(d.sources.observeAll().first().single().enabled)

            assertEquals(1, d.sources.remove(id, now = 3_000))
            assertTrue(d.sources.all().isEmpty())
            assertEquals(1, d.games.observeMissing().first().size)
            assertNull(d.folderState.lastModified("/roms/gba"))
            assertNull(d.folderState.lastModified("/roms/snes"))
            assertEquals(5, d.folderState.lastModified("/romsbackup/gba"), "only the removed source's folders are forgotten")
        }
    }
}
