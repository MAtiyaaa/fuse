package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.model.CartridgeRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CartridgeProtocolTest {

    @Test
    fun deepLinksHaveTheDocumentedShape() {
        assertEquals("cartridge://home?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Home))
        assertEquals("cartridge://library?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Library))
        assertEquals("cartridge://downloads?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Downloads))
        assertEquals("cartridge://consoles?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Consoles))
        assertEquals("cartridge://settings?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Settings))
        assertEquals("cartridge://sync?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Sync))
        assertEquals("cartridge://platform/snes?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Platform("snes")))
        assertEquals("cartridge://game/4242?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Game(4242)))
        assertEquals("cartridge://bios/psx?from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Bios("psx")))
        assertEquals(
            "cartridge://search?q=Zelda%3A%20A%20Link%20%26%20More&platform=snes&from=fuse&v=1",
            CartridgeProtocol.deepLink(CartridgeRoute.Search("Zelda: A Link & More", "snes")),
        )
        assertEquals("cartridge://search?q=metroid&from=fuse&v=1", CartridgeProtocol.deepLink(CartridgeRoute.Search("metroid")))
    }

    @Test
    fun deepLinksRoundTrip() {
        val routes = listOf(
            CartridgeRoute.Home, CartridgeRoute.Library, CartridgeRoute.Downloads, CartridgeRoute.Consoles,
            CartridgeRoute.Settings, CartridgeRoute.Sync, CartridgeRoute.Platform("switch-2"), CartridgeRoute.Game(1),
            CartridgeRoute.Bios("ps2"), CartridgeRoute.Search("Pokémon: Red + Blue & 100%?", "gbc"),
            CartridgeRoute.Search("", null),
        )
        for (route in routes) {
            assertEquals(route, CartridgeProtocol.parse(CartridgeProtocol.deepLink(route)), route.toString())
        }
        val query = CartridgeProtocol.parseQuery(CartridgeProtocol.deepLink(CartridgeRoute.Home).substringAfter('?'))
        assertEquals(mapOf("from" to "fuse", "v" to "1"), query)
    }

    @Test
    fun parserRejectsUnknownLinks() {
        assertNull(CartridgeProtocol.parse("https://example.com/home"))
        assertNull(CartridgeProtocol.parse("cartridge://unknown"))
        assertNull(CartridgeProtocol.parse("cartridge://game/notanumber"))
        assertNull(CartridgeProtocol.parse("cartridge://platform"))
        assertEquals(CartridgeRoute.Search("a b"), CartridgeProtocol.parse("CARTRIDGE://search?q=a+b"))
    }

    @Test
    fun statusRowMapping() {
        val row = mapOf<String, Any?>(
            "protocol" to 1L,
            "version" to "0.9.10",
            "connected" to 1L,
            "active_downloads" to 2L,
            "queued_downloads" to 5,
            "progress" to 0.42,
            "current_title" to "Super Metroid",
            "current_platform" to "snes",
            "library_changed_at" to 1_790_000_000_000L,
            "updated_at" to 1_790_000_001_000L,
        )
        val recent = listOf(
            mapOf<String, Any?>("rom_id" to 77L, "title" to "Super Metroid", "platform_slug" to "snes", "path" to "/storage/roms/snes/Super Metroid.sfc", "finished_at" to 1_790_000_000_500L),
            mapOf<String, Any?>("rom_id" to null, "title" to "broken"),
        )
        val status = CartridgeProtocol.statusFromRow(row, recent, installedVersion = "0.9.10", checkedAt = 9)
        assertTrue(status.installed)
        assertTrue(status.bridge)
        assertEquals("0.9.10", status.version)
        assertEquals(true, status.connected)
        assertEquals(2, status.activeDownloads)
        assertEquals(5, status.queuedDownloads)
        assertEquals(0.42f, status.progress)
        assertEquals("Super Metroid", status.currentTitle)
        assertEquals("snes", status.currentPlatform)
        assertEquals(1_790_000_000_000L, status.libraryChangedAt)
        assertEquals(9, status.checkedAt)
        assertEquals(1, status.recent.size)
        assertEquals(77, status.recent[0].romId)
        assertEquals("/storage/roms/snes/Super Metroid.sfc", status.recent[0].path)

        val idle = CartridgeProtocol.statusFromRow(mapOf("protocol" to 1, "connected" to null, "progress" to null, "active_downloads" to 0))
        assertNull(idle.connected)
        assertNull(idle.progress)
        assertNull(idle.currentTitle)
    }

    @Test
    fun linuxStatusFile() {
        val json = """
            {"protocol":1,"version":"0.9.10","connected":true,"activeDownloads":1,"queuedDownloads":0,"progress":0.5,
             "currentTitle":"Chrono Trigger","currentPlatform":"snes","libraryChangedAt":1790000000000,"updatedAt":1790000000100,
             "recent":[{"romId":1,"title":"Chrono Trigger","platformSlug":"snes","path":"/home/me/roms/snes/Chrono Trigger.sfc","finishedAt":1790000000000}]}
        """.trimIndent()
        val status = CartridgeProtocol.parseStatusFile(json, checkedAt = 3)!!
        assertTrue(status.bridge)
        assertEquals(true, status.connected)
        assertEquals(1, status.activeDownloads)
        assertEquals(0.5f, status.progress)
        assertEquals("Chrono Trigger", status.recent.single().title)
        assertEquals("/home/me/roms/snes/Chrono Trigger.sfc", status.recent.single().path)
        val idle = CartridgeProtocol.parseStatusFile(
            """{"protocol":1,"version":"0.9.10","connected":null,"activeDownloads":0,"queuedDownloads":0,"progress":null,
               "currentTitle":null,"currentPlatform":null,"libraryChangedAt":0,"updatedAt":0,"recent":[]}""",
        )!!
        assertNull(idle.connected)
        assertNull(CartridgeProtocol.parseStatusFile("not json"))
        assertNull(CartridgeProtocol.parseStatusFile("{}"))
    }

    @Test
    fun statusFilePathFollowsXdg() {
        assertEquals("/xdg/state/cartridge/status.json", CartridgeProtocol.statusFilePath("/xdg/state/", "/home/me"))
        assertEquals("/home/me/.local/state/cartridge/status.json", CartridgeProtocol.statusFilePath(null, "/home/me"))
        assertEquals("/home/me/.local/state/cartridge/status.json", CartridgeProtocol.statusFilePath("relative/path", "/home/me/"))
        assertNull(CartridgeProtocol.statusFilePath(null, null))
    }

    @Test
    fun contractConstants() {
        assertEquals("io.github.abdu2304.cartridge", CartridgeProtocol.PACKAGE_NAME)
        assertEquals("io.github.abdu2304.cartridge.status", CartridgeProtocol.STATUS_AUTHORITY)
        assertEquals("io.github.abdu2304.cartridge.permission.READ_STATUS", CartridgeProtocol.READ_STATUS_PERMISSION)
        assertEquals("content://io.github.abdu2304.cartridge.status/status", CartridgeProtocol.STATUS_URI)
        assertEquals("content://io.github.abdu2304.cartridge.status/recent", CartridgeProtocol.RECENT_URI)
        assertEquals(10, CartridgeProtocol.StatusColumns.ALL.size)
        assertEquals(listOf("rom_id", "title", "platform_slug", "path", "finished_at"), CartridgeProtocol.RecentColumns.ALL)
        assertEquals("MAtiyaaa/cartridge", CartridgeProtocol.RELEASE_REPO.toString())
        assertEquals("abdu2304/cartridge", CartridgeProtocol.UPSTREAM_REPO.toString())
        assertTrue(CartridgeProtocol.supportsBridge("v0.9.10"))
        assertFalse(CartridgeProtocol.supportsBridge("0.9.9"))
        assertFalse(CartridgeProtocol.supportsBridge(null))
    }
}
