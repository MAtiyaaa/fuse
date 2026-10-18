package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.ui.shell.store.impl.DefaultReachOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedArtTest {
    @Test
    fun aDeviceSharesWhatTheProvidersGaveNeverWhatIsOnlyItsOwn() {
        val media = MediaSet(listOf(
            MediaItem(MediaKind.BOXART, MediaSource.STEAMGRIDDB, localPath = "/cache/a.png", remoteUrl = "https://cdn.steamgriddb.com/grid/a.png", order = 0),
            MediaItem(MediaKind.BOXART, MediaSource.IGDB, remoteUrl = "https://images.igdb.com/b.jpg", order = 1),
            MediaItem(MediaKind.HERO, MediaSource.USER, remoteUrl = "https://example.com/mine.png"),
            MediaItem(MediaKind.LOGO, MediaSource.ROMM, remoteUrl = "https://romm.home/logo.png"),
            MediaItem(MediaKind.ICON, MediaSource.LOCAL_FOLDER, localPath = "/games/icon.png"),
            MediaItem(MediaKind.GRID, MediaSource.SCREENSCRAPER, localPath = "/cache/only-here.png"),
        ) + (0 until 5).map { MediaItem(MediaKind.SCREENSHOT, MediaSource.LIBRETRO, remoteUrl = "https://thumbnails.libretro.com/s$it.png", order = it) })
        val shared = DefaultReachOps.sharedArt(media)
        // One cover (the first the device chose), three screenshots; nothing the person picked, no
        // RomM art (it needs this device's sign-in), no folder's own pictures, nothing without an address.
        assertEquals(listOf("BOXART" to "https://cdn.steamgriddb.com/grid/a.png"), shared.filter { it.kind == "BOXART" }.map { it.kind to it.url })
        assertEquals(3, shared.count { it.kind == "SCREENSHOT" })
        assertEquals(setOf("BOXART", "SCREENSHOT"), shared.map { it.kind }.toSet())
        assertEquals(emptyList(), DefaultReachOps.sharedArt(null))
    }

    @Test
    fun sharedArtNamesTheProviderItCameFrom() {
        assertEquals(ScrapeProviderId.STEAMGRIDDB, DefaultReachOps.sharedProvider("STEAMGRIDDB"))
        assertEquals(ScrapeProviderId.GAMETDB, DefaultReachOps.sharedProvider("GAMETDB"))
        assertNull(DefaultReachOps.sharedProvider("USER"))
        assertNull(DefaultReachOps.sharedProvider("ROMM"))
        assertNull(DefaultReachOps.sharedProvider("nonsense"))
    }
}
