package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.ScreenCalibration
import io.github.matiyaaa.fuse.model.calibrationKey
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A calibrated screen keeps its own fit; every other screen keeps the settings they share. */
class CalibrationTest {
    private fun screen(name: String, w: Int, h: Int, primary: Boolean = true) =
        DisplayInfo(0, name, w, h, 60f, isPrimary = primary, isPresentation = false, isOn = true)

    @Test
    fun aCalibratedScreenIsKnownAgainAndOthersAreNot() {
        val tv = screen("LG TV", 3840, 2160)
        val fit = ScreenCalibration(margin = 4, textScale = 1.15f, interfaceSize = 150)
        val prefs = UiPrefs().let { it.copy(display = it.display.copy(calibrations = mapOf(tv.calibrationKey() to fit))) }
        assertEquals(fit, prefs.calibration(listOf(tv)))
        // The handheld's own screen, primary once the TV is unplugged: not calibrated.
        assertNull(prefs.calibration(listOf(screen("Built-in", 1920, 1080))))
        // The same TV drawn smaller and enlarged (Game Mode): still known by its own size.
        val enlarged = tv.copy(widthPx = 1920, heightPx = 1080, outputWidthPx = 3840, outputHeightPx = 2160)
        assertEquals(fit, prefs.calibration(listOf(enlarged)))
    }

    @Test
    fun calibrationsAreKeptWithTheDevicesSettings() {
        val fit = ScreenCalibration(margin = 6, textScale = 1.3f, interfaceSize = 0)
        val settings = AppSettings().let { it.copy(display = it.display.copy(calibrations = mapOf("LG TV 3840x2160" to fit))) }
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(fit, back.display.calibrations["LG TV 3840x2160"])
    }
}
