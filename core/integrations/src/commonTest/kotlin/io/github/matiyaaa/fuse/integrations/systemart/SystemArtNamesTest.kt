package io.github.matiyaaa.fuse.integrations.systemart

import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemArtNamesTest {

    /** Catalog platforms Art Book Next has no art for at [SystemArtPack.REF]. */
    private val knownAbsent = setOf("ps5", "switch-2")

    @Test
    fun everyCatalogPlatformThatThePackCoversResolves() {
        val unresolved = ArrayList<String>()
        for (platform in PlatformCatalog.all) {
            val name = SystemArtNames.forPlatform(platform.id, platform.folderAliases)
            val id = platform.id.value
            if (id in knownAbsent) {
                assertNull(name, "$id is on the absent list but resolves to $name; remove it from the list")
            } else if (name == null) {
                unresolved += id
            } else {
                assertTrue(name in SystemArtNames.PACK, "$id resolves to $name, which is not a pack system")
            }
        }
        if (unresolved.isNotEmpty()) println("Platforms without Art Book Next art: $unresolved")
        assertEquals(emptyList(), unresolved, "map these in SystemArtNames.OVERRIDES or add them to knownAbsent")
    }

    @Test
    fun overridesPointAtPackSystemsAndCatalogIds() {
        val ids = PlatformCatalog.all.map { it.id.value }.toSet()
        for ((id, name) in SystemArtNames.OVERRIDES) {
            assertTrue(id in ids, "override for unknown catalog id $id")
            assertTrue(name in SystemArtNames.PACK, "override $id -> $name is not a pack system")
            assertTrue(id !in SystemArtNames.PACK, "$id is already a pack name, the override is not needed")
        }
    }

    @Test
    fun overrideThenIdThenAlias() {
        assertEquals("n3ds", SystemArtNames.forPlatform(PlatformId("3ds")))
        assertEquals("gc", SystemArtNames.forPlatform(PlatformId("ngc")))
        assertEquals("dreamcast", SystemArtNames.forPlatform(PlatformId("dc")))
        assertEquals("mastersystem", SystemArtNames.forPlatform(PlatformId("sms")))
        assertEquals("tg-cd", SystemArtNames.forPlatform(PlatformId("turbografx-cd")))
        assertEquals("windows", SystemArtNames.forPlatform(PlatformId("win")))
        assertEquals("snes", SystemArtNames.forPlatform(PlatformId("snes"), setOf("sfc")))
        assertEquals("psx", SystemArtNames.forPlatform(PlatformId("PSX")))
        assertEquals("megadrive", SystemArtNames.forPlatform(PlatformId("custom-md"), setOf("custom-md", "MegaDrive")))
        assertNull(SystemArtNames.forPlatform(PlatformId("ps5"), setOf("ps5", "playstation5")))
    }

    @Test
    fun urlsCoverEveryStyle() {
        val urls = SystemArtPack.urls("sg-1000")
        val base = "https://raw.githubusercontent.com/anthonycaccese/art-book-next-es-de/${SystemArtPack.REF}/_inc/systems"
        assertEquals("$base/logos/sg-1000.svg", urls.logo)
        assertEquals("$base/_metadata-global/sg-1000.xml", urls.metadata)
        assertEquals("$base/artwork/sg-1000.png", urls.artwork(SystemArtStyle.CLASSIC))
        assertEquals("$base/artwork-noir/sg-1000.png", urls.artwork(SystemArtStyle.NOIR))
        assertEquals(SystemArtStyle.entries.toSet(), urls.artwork.keys)
        assertEquals(listOf("Classic", "Outline", "Noir", "Circuit", "Screenshots"), SystemArtStyle.entries.map { it.displayName })
    }
}
