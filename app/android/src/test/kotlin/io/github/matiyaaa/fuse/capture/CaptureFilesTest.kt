package io.github.matiyaaa.fuse.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFilesTest {
    @Test
    fun aFullHdScreenRecordsAtItsOwnSize() {
        assertEquals(1920 to 1080, CaptureFiles.videoSize(1920, 1080))
        assertEquals(1080 to 1920, CaptureFiles.videoSize(1080, 1920))
    }

    @Test
    fun largerScreensAreScaledDownKeepingTheirShape() {
        assertEquals(1920 to 1080, CaptureFiles.videoSize(2560, 1440))
        // A 1240 x 1080 screen (a handheld's 31:27) keeps its shape within 1080 rows.
        assertEquals(1240 to 1080, CaptureFiles.videoSize(1240, 1080))
        assertEquals(1440 to 1080, CaptureFiles.videoSize(2400, 1800))
    }

    @Test
    fun smallScreensAreNeverScaledUpAndSidesAreEven() {
        assertEquals(1280 to 720, CaptureFiles.videoSize(1280, 720))
        assertEquals(854 to 480, CaptureFiles.videoSize(855, 481))
    }

    @Test
    fun alignmentAndBitrate() {
        assertEquals(1072, CaptureFiles.align16(1080))
        assertEquals(1920, CaptureFiles.align16(1920))
        assertEquals(8_087_040, CaptureFiles.bitrate(1920, 1080))
        assertEquals(2_000_000, CaptureFiles.bitrate(320, 240))
    }

    @Test
    fun namesAreSafeForEveryFileSystem() {
        assertEquals("Fuse 2026-10-01 13-45-02", CaptureFiles.safeName("Fuse 2026-10-01 13-45-02"))
        assertEquals("a-b-c", CaptureFiles.safeName("a/b:c"))
        assertEquals("Fuse", CaptureFiles.safeName("  "))
    }
}
