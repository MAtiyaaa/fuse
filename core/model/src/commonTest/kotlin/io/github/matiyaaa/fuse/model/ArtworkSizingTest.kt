package io.github.matiyaaa.fuse.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkSizingTest {
    @Test fun handheldAndTelevisionRequestDifferentPhysicalAssets() {
        val handheld = ArtworkSizing.decode(160, 240)
        val television = ArtworkSizing.decode(1280, 1920)
        assertEquals(192, handheld.width)
        assertEquals(1536, television.width)
        assertTrue(television.height >= 1920)
    }

    @Test fun nearbyResizePixelsReuseBucketButLargeWidgetsUpgrade() {
        assertEquals(ArtworkSizing.decode(200, 300), ArtworkSizing.decode(201, 301))
        assertTrue(ArtworkSizing.decode(1200, 1800).width > ArtworkSizing.decode(200, 300).width)
    }

    @Test fun fourKAndExtremeLayoutsObeyMemoryAndSourceLimits() {
        val wide = ArtworkSizing.decode(3840, 2160)
        assertTrue(wide.width.toLong() * wide.height <= 8_388_608)
        val source = ArtworkSizing.decode(3840, 2160, sourceWidth = 640, sourceHeight = 360)
        assertEquals(ArtworkPixels(640, 360), source)
        val tinyBudget = ArtworkSizing.decode(4000, 4000, maxPixels = 262144)
        assertTrue(tinyBudget.width.toLong() * tinyBudget.height <= 262144)
        assertEquals(ArtworkPixels(1, 1), ArtworkSizing.decode(4000, 1, maxPixels = 1))
        assertEquals(ArtworkPixels(1, 1), ArtworkSizing.decode(1, 4000, maxPixels = 1))
    }
}
