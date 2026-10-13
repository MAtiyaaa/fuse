package io.github.matiyaaa.fuse.integrations.libretro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibretroNamesTest {
    private val psp = listOf(
        "Metal Gear Ac!d (Europe) (En,Fr,De,Es,It)", "Metal Gear Ac!d (Japan)", "Metal Gear Ac!d (USA)",
        "Metal Gear Ac!d 2 (Asia)", "Metal Gear Ac!d 2 (USA)", "Ratchet _ Clank - Size Matters (USA)",
    )

    @Test
    fun aGameNamedAnotherWayInTheRepositoryIsFoundAsATitle() {
        assertEquals("Metal Gear Ac!d (USA)", LibretroThumbnails.closest(psp, listOf("Metal Gear Acid")))
        assertEquals("Metal Gear Ac!d 2 (USA)", LibretroThumbnails.closest(psp, listOf("Metal Gear Acid 2")))
        assertEquals("Ratchet _ Clank - Size Matters (USA)", LibretroThumbnails.closest(psp, listOf("Ratchet & Clank - Size Matters")))
        assertNull(LibretroThumbnails.closest(psp, listOf("Metal Gear Solid")))
    }
}
