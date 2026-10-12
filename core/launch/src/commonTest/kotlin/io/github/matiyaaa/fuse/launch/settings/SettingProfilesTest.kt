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
            assertEquals(listOf("vanilla", "performance", "quality", "battery"), profiles.map { it.id }, family)
            for (p in profiles) for ((k, v) in p.values) {
                val s = settings.single { it.id == k }
                assertTrue(s.perGame && s.choices.any { it.value == v }, "$family ${p.id} $k=$v")
            }
        }
        assertTrue(SettingProfiles.builtIn("rpcs3", Host.LINUX).isEmpty())
    }

    @Test
    fun aGameHasAProfileWhenEveryValueInItMatches() {
        val settings = NativeSettings.forEmulator("pcsx2", Host.LINUX)
        val quality = SettingProfiles.builtIn("pcsx2", Host.LINUX).single { it.id == SettingProfiles.QUALITY }
        assertTrue(SettingProfiles.matches(quality, mapOf("pcsx2.upscale" to "3.000000", "pcsx2.vsync" to "True", "pcsx2.aspect" to "16:9"), settings))
        assertFalse(SettingProfiles.matches(quality, mapOf("pcsx2.upscale" to "3"), settings))
    }
}
