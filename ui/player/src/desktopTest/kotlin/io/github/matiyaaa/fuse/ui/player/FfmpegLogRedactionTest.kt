package io.github.matiyaaa.fuse.ui.player

import io.github.matiyaaa.fuse.ui.player.ffmpeg.FfmpegNatives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegLogRedactionTest {
    @Test
    fun signInsNeverReachTheLog() {
        val line = "[hls @ 0x1] Opening 'https://jellyfin.example.com/videos/abc/main.m3u8?DeviceId=d1&ApiKey=44f03f0819c1454ca2e0f2317b7787cc&Tag=12' for reading"
        val shown = FfmpegNatives.redact(line)
        assertFalse("44f03f0819c1454ca2e0f2317b7787cc" in shown)
        assertTrue("ApiKey=<hidden>&Tag=12" in shown)
        assertTrue("DeviceId=d1" in shown)
        assertEquals("x?access_token=<hidden> y", FfmpegNatives.redact("x?access_token=abc.def y"))
        assertEquals("api_key=<hidden>", FfmpegNatives.redact("api_key=secret"))
    }
}
