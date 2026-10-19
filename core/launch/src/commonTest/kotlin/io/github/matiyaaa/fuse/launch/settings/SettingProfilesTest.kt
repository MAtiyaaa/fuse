package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingProfilesTest {
    @Test
    fun everyProfileIsMadeOfSettingsTheEmulatorHasAndValuesItTakes() {
        for (host in listOf(Host.LINUX, Host.WINDOWS, Host.MACOS)) for (family in NativeSettings.families) {
            val settings = NativeSettings.forEmulator(family, host)
            val profiles = SettingProfiles.builtIn(family, host)
            assertEquals(listOf("vanilla", "performance", "quality", "battery", "device.steam-deck", "device.rog-ally", "device.tv-4k"), profiles.map { it.id }, family)
            for (p in profiles) for ((k, v) in p.values) {
                val s = settings.single { it.id == k }
                assertTrue(s.perGame && s.choices.any { it.value == v }, "$family ${p.id} $k=$v")
            }
        }
        assertTrue(SettingProfiles.builtIn("rpcs3", Host.LINUX).isEmpty())
    }

    @Test
    fun aDevicesProfileFitsTheResolutionToItsScreenAndSaysWhy() {
        fun scale(family: String, device: String) = SettingProfiles.forDevices(family, Host.LINUX).single { it.id == SettingProfiles.DEVICE + device }
        // In the hand, the step nearest the screen's lines; the lower of two as near.
        assertEquals("2", scale("pcsx2", "steam-deck").values["pcsx2.upscale"], "about 720 lines on an 800-line screen")
        assertEquals("3", scale("duckstation", "steam-deck").values["duckstation.scale"])
        assertEquals("3", scale("ppsspp", "steam-deck").values["ppsspp.resolution"], "816 lines")
        assertEquals("2", scale("dolphin", "steam-deck").values["dolphin.resolution"])
        assertEquals("4", scale("duckstation", "rog-ally").values["duckstation.scale"], "960 and 1200 are as near 1080: the lower")
        assertEquals("3", scale("pcsx2", "rog-ally").values["pcsx2.upscale"])
        // On a TV, the first that fills it.
        assertEquals("6", scale("pcsx2", "tv-4k").values["pcsx2.upscale"])
        assertEquals("9", scale("duckstation", "tv-4k").values["duckstation.scale"])
        assertEquals("8", scale("ppsspp", "tv-4k").values["ppsspp.resolution"], "2176 lines")
        assertEquals("6", scale("dolphin", "tv-4k").values["dolphin.resolution"])
        // Frames paced to the screen where the emulator has the switch, and every choice said.
        assertEquals("true", scale("pcsx2", "steam-deck").values["pcsx2.vsync"])
        assertEquals("True", scale("dolphin", "tv-4k").values["dolphin.vsync"])
        val deck = scale("pcsx2", "steam-deck")
        assertEquals(800, deck.lines)
        assertTrue("2x (about 720p)" in deck.about && "800 lines" in deck.about && "paced" in deck.about, deck.about)
        assertTrue(SettingProfiles.forDevices("rpcs3", Host.LINUX).isEmpty())
    }

    @Test
    fun androidHasItsHandheldAndATvAndComputersTheirs() {
        assertEquals(listOf("steam-deck", "rog-ally", "tv-4k"), SettingProfiles.devicesFor(Host.LINUX).map { it.id })
        assertEquals(listOf("android-handheld", "tv-4k"), SettingProfiles.devicesFor(Host.ANDROID).map { it.id })
        val handheld = SettingProfiles.forDevices("ppsspp", Host.ANDROID).single { it.id == SettingProfiles.DEVICE + "android-handheld" }
        assertEquals("4", handheld.values["ppsspp.resolution"], "1088 lines, nearest a 1080-line screen")
        assertTrue("Android Handheld" in handheld.about && "1080 lines" in handheld.about, handheld.about)
        assertEquals("3", SettingProfiles.forDevices("pcsx2", Host.ANDROID).single { it.id == SettingProfiles.DEVICE + "android-handheld" }.values["pcsx2.upscale"])
    }

    @Test
    fun aGameHasAProfileWhenEveryValueInItMatches() {
        val settings = NativeSettings.forEmulator("pcsx2", Host.LINUX)
        val quality = SettingProfiles.builtIn("pcsx2", Host.LINUX).single { it.id == SettingProfiles.QUALITY }
        assertTrue(SettingProfiles.matches(quality, mapOf("pcsx2.upscale" to "3.000000", "pcsx2.vsync" to "True", "pcsx2.aspect" to "16:9"), settings))
        assertFalse(SettingProfiles.matches(quality, mapOf("pcsx2.upscale" to "3"), settings))
    }
}
