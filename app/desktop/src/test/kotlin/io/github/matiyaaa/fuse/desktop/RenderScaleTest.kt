package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.system.RenderScale
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderScaleTest {
    private fun drm(vararg connectors: Triple<String, String, String>): File {
        val root = Files.createTempDirectory("drm").toFile()
        for ((name, status, modes) in connectors) {
            File(root, name).apply { mkdirs() }.let { dir ->
                File(dir, "status").writeText("$status\n")
                File(dir, "modes").writeText(modes)
            }
        }
        // Not connectors: the card itself and the version file.
        File(root, "card0").mkdirs()
        File(root, "version").writeText("drm 1.1.0")
        return root
    }

    @Test
    fun aHandheldPictureOnA4kTvIsEnlarged() {
        val root = drm(
            Triple("card0-eDP-1", "connected", "800x1280\n"),
            Triple("card0-DP-1", "connected", "3840x2160\n1920x1080\n"),
            Triple("card0-HDMI-A-1", "disconnected", ""),
        )
        val r = RenderScale.read(1280, 800, gameMode = true, drm = root)!!
        assertEquals("DP-1", r.output.connector)
        assertEquals(3f, r.factor)
        assertTrue(r.enlarged)
        root.deleteRecursively()
    }

    @Test
    fun nativeDrawingIsNotEnlargedAndADesktopIsNeverJudged() {
        val root = drm(Triple("card1-HDMI-A-1", "connected", "1920x1080\n"))
        assertFalse(RenderScale.read(1920, 1080, gameMode = true, drm = root)!!.enlarged)
        assertNull(RenderScale.read(1280, 720, gameMode = false, drm = root))
        root.deleteRecursively()
    }

    @Test
    fun onlyRealModesAreRead() {
        assertEquals(3840 to 2160, RenderScale.parseMode("3840x2160"))
        assertEquals(1920 to 1080, RenderScale.parseMode("1920x1080i"))
        assertNull(RenderScale.parseMode(""))
        assertNull(RenderScale.parseMode("garbage"))
        assertNull(RenderScale.parseMode("99999x1"))
        // No connected screen at all: no answer, rather than a guess.
        assertNull(RenderScale.read(1280, 800, gameMode = true, drm = Files.createTempDirectory("empty").toFile()))
    }
}
