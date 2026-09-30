package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.model.QueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bridge protocol 2: the queue game by game and the downloaded games with RomM's details. */
class CartridgeBridgeV2Test {

    private val file = """
        {
          "protocol": 2, "version": "0.9.11", "connected": true, "activeDownloads": 1, "queuedDownloads": 1,
          "progress": 0.235, "currentTitle": "Chrono Trigger", "currentPlatform": "snes",
          "libraryChangedAt": 1790000000000, "updatedAt": 1790000123456,
          "recent": [ { "romId": 1234, "title": "Super Metroid", "platformSlug": "snes", "path": "/roms/snes/Super Metroid (USA).sfc", "finishedAt": 1789999000000 } ],
          "queue": [
            { "romId": 1300, "title": "Chrono Trigger", "platformSlug": "snes", "state": "downloading", "received": 1728053, "total": 4194304, "position": 0 },
            { "romId": 1301, "title": "EarthBound", "platformSlug": "snes", "state": "queued", "received": 0, "total": null, "position": 1 },
            { "romId": 1302, "title": "Mother 3", "platformSlug": "gba", "state": "unpacking", "received": 1, "total": 2, "position": 2 },
            { "romId": 1234, "title": "Super Metroid", "platformSlug": "snes", "state": "done", "received": 3145728, "total": 3145728, "position": 3 }
          ],
          "games": [
            {
              "romId": 1234, "path": "/roms/snes/Super Metroid (USA).sfc", "title": "Super Metroid", "platformSlug": "snes",
              "summary": "Samus returns to Zebes.", "year": 1994, "genres": ["Platform", "Adventure", "Platform"],
              "developer": "Nintendo R&D1", "publisher": "Nintendo", "rating": 92, "players": 1, "series": ["Metroid"],
              "cover": "/home/you/.config/Cartridge/imgcache/3f78", "logo": "/home/you/.config/Cartridge/logos/1234-r.png",
              "screenshot": null, "updatedAt": 1789999000000
            },
            { "title": "No id" }
          ]
        }
    """.trimIndent()

    @Test
    fun statusFileProtocol2HasTheQueueAndTheGames() {
        val snapshot = assertNotNull(CartridgeProtocol.parseSnapshot(file, checkedAt = 5))
        val status = snapshot.status
        assertEquals(2, status.protocol)
        // Finished and unknown states are left out; the rest keep Cartridge's order.
        assertEquals(listOf(1300L, 1301L), status.queue.map { it.romId })
        assertEquals(QueueState.DOWNLOADING, status.queue[0].state)
        assertEquals(1728053f / 4194304f, status.queue[0].progress!!, 0.0001f)
        assertNull(status.queue[1].total)
        assertEquals(1, status.recent.size)

        val game = snapshot.games!!.single()
        assertEquals("Super Metroid", game.title)
        assertEquals(1994, game.year)
        assertEquals(listOf("Platform", "Adventure"), game.genres)
        assertEquals(listOf("Metroid"), game.series)
        assertEquals("1", game.players)
        assertEquals(92, game.rating)
        assertEquals("/home/you/.config/Cartridge/logos/1234-r.png", game.logo)
        assertNull(game.screenshot)
    }

    @Test
    fun protocol1FilesHaveNoGames() {
        val old = """{"protocol":1,"version":"0.9.10","recent":[]}"""
        val snapshot = assertNotNull(CartridgeProtocol.parseSnapshot(old))
        assertNull(snapshot.games)
        assertEquals(1, snapshot.status.protocol)
        assertTrue(snapshot.status.queue.isEmpty())
    }

    @Test
    fun androidRowsCarryListsAsJsonText() {
        val game = assertNotNull(
            CartridgeProtocol.gameFromRow(
                mapOf(
                    "rom_id" to 9L, "title" to "EarthBound", "platform_slug" to "snes", "path" to "/storage/emulated/0/ROMs/snes/EarthBound.sfc",
                    "genres" to "[\"RPG\",\"\",null]", "series" to "not json", "rating" to 101L, "year" to 1994L,
                    "cover" to "content://io.github.abdu2304.cartridge.status/image/9/cover?v=3", "updated_at" to 7L,
                ),
            ),
        )
        assertEquals(listOf("RPG"), game.genres)
        assertEquals(emptyList(), game.series)
        assertEquals(100, game.rating)
        assertEquals("content://io.github.abdu2304.cartridge.status/image/9/cover?v=3", game.cover)
        assertNull(CartridgeProtocol.gameFromRow(mapOf("rom_id" to 1L)))

        val status = CartridgeProtocol.statusFromRow(
            mapOf("protocol" to 2L, "version" to "0.9.11"),
            queue = listOf(
                mapOf("rom_id" to 1L, "title" to "A", "platform_slug" to "snes", "state" to "paused", "received" to 5L, "total" to 10L),
                mapOf("rom_id" to 2L, "title" to "B", "state" to "failed"),
            ),
        )
        assertTrue(CartridgeProtocol.hasGames(mapOf("protocol" to 2L)))
        assertEquals(listOf(QueueState.PAUSED, QueueState.FAILED), status.queue.map { it.state })
        assertEquals(0.5f, status.queue[0].progress)
    }

    @Test
    fun pathsMatchHoweverEachAppWritesThem() {
        val fuse = listOf(
            1 to "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fsnes%2FSuper%20Metroid%20(USA).sfc",
            2 to "/storage/ABCD-1234/Games/psx/Final Fantasy VII/Final Fantasy VII (Disc 1).chd",
            3 to "/sdcard/ROMs/gba/Advance Wars (USA).gba",
            4 to "/roms/nes/Tetris.nes",
            5 to "/other/nes/Tetris.nes",
            6 to "/roms/gb/Tetris (World).gb",
        )
        val match = CartridgeMatch(fuse)
        assertEquals(1, match.find("/storage/emulated/0/ROMs/snes/Super Metroid (USA).sfc"))
        assertEquals(2, match.find("content://com.android.externalstorage.documents/tree/ABCD-1234%3AGames/document/ABCD-1234%3AGames%2Fpsx%2FFinal%20Fantasy%20VII"))
        assertEquals(3, match.find("/storage/emulated/0/ROMs/gba/Advance Wars (USA).gba"))
        assertEquals(6, match.find("/mnt/other/gb/Tetris (World).gb"))
        // Two games with the same folder and name: no guessing.
        assertNull(match.find("/elsewhere/nes/Tetris.nes"))
        assertNull(match.find("/nothing/like/this.bin"))
        assertEquals("/storage/emulated/0/roms/a.sfc", CartridgeMatch.pathKey("file:///sdcard//ROMs/a.sfc/"))
    }
}
