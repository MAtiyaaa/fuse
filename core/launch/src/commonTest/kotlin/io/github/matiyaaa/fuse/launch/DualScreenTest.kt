package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DualScreenTest {

    @Test
    fun dsThreeDsAndWiiUUseTheSecondScreen() {
        for (id in listOf("nds", "nintendo-dsi", "3ds", "new-nintendo-3ds", "wiiu")) {
            assertTrue(DualScreenPlatforms.usesSecondScreen(PlatformId(id)), id)
        }
        for (id in listOf("gba", "wii", "switch", "psp", "psvita", "n3ds")) {
            assertFalse(DualScreenPlatforms.usesSecondScreen(PlatformId(id)), id)
        }
        assertEquals(5, DualScreenPlatforms.ids.size)
    }

    @Test
    fun everyIdIsACatalogPlatform() {
        for (id in DualScreenPlatforms.ids) assertNotNull(PlatformCatalog.byId(id), "${id.value} is not in the catalog")
    }
}
