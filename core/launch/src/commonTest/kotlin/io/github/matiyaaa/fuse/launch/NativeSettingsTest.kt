package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.settings.NativeSettings
import io.github.matiyaaa.fuse.launch.settings.SettingSource
import io.github.matiyaaa.fuse.launch.settings.SettingsEdit
import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Emulator settings read as the emulator reads them, and Fuse's changes put back exactly. */
class NativeSettingsTest {
    private val upscale = NativeSettings.forEmulator("pcsx2", Host.LINUX).first { it.id == "pcsx2.upscale" }

    @Test
    fun aGamesOwnSettingReplacesEveryGamesAndTheDefaultComesLast() {
        val global = IniText("[EmuCore/GS]\nupscale_multiplier = 3.000000\n")
        val own = SettingsEdit.valueFor(upscale, IniText("[EmuCore/GS]\nupscale_multiplier = 2\n"), global)
        assertEquals(SettingSource.GAME, own.source)
        assertEquals("2x (about 720p)", own.label)
        assertEquals("3x (about 1080p)", upscale.labelFor(own.everyGame), "PCSX2 writes floats; the same value either way")
        assertEquals(SettingSource.EVERY_GAME, SettingsEdit.valueFor(upscale, IniText(""), global).source)
        val none = SettingsEdit.valueFor(upscale, null, null)
        assertEquals(SettingSource.DEFAULT, none.source)
        assertEquals("1", none.value)
    }

    @Test
    fun whatFuseChangedIsPutBackAndNothingElse() {
        val text = "; mine\n[EmuCore/GS]\nupscale_multiplier = 3\nRenderer = 14\n"
        val file = IniText(text)
        var records = SettingsEdit.change(file, emptySet(), "EmuCore/GS", "upscale_multiplier", "2")
        records = SettingsEdit.change(file, records, "EmuCore/GS", "upscale_multiplier", "4")
        records = SettingsEdit.change(file, records, "EmuCore/GS", "AspectRatio", "16:9")
        assertEquals(2, records.size, "one record per key, keeping what was there first")
        assertEquals("; mine\n[EmuCore/GS]\nupscale_multiplier = 4\nRenderer = 14\nAspectRatio = 16:9\n", file.toString())
        assertEquals(0, SettingsEdit.restore(file, records))
        assertEquals(text, file.toString())

        // Changed in the emulator since: that one stays, the rest goes back.
        val again = IniText(text)
        val r = SettingsEdit.change(again, SettingsEdit.change(again, emptySet(), "EmuCore/GS", "upscale_multiplier", "2"), "EmuCore/GS", "Renderer", null)
        again.set("EmuCore/GS", "upscale_multiplier", "5")
        assertEquals(1, SettingsEdit.restore(again, r))
        assertEquals("; mine\n[EmuCore/GS]\nupscale_multiplier = 5\nRenderer = 14\n", again.toString())
    }

    @Test
    fun onlySettingsAnEmulatorTakesPerGameAreOfferedForAGame() {
        for (family in NativeSettings.families) {
            val settings = NativeSettings.forEmulator(family, Host.LINUX)
            assertTrue(settings.isNotEmpty())
            for (s in settings) {
                assertTrue(s.choices.any { s.same(it.value, s.default) }, "${s.id}'s default is one of its choices")
                assertEquals(s.choices.size, s.choices.map { it.value }.toSet().size, "${s.id} offers each value once")
            }
        }
        val windows = NativeSettings.forEmulator("duckstation", Host.WINDOWS).first { it.key == "Renderer" }.choices.map { it.value }
        assertTrue("D3D12" in windows)
        assertFalse("Metal" in windows)
        assertFalse("D3D11" in NativeSettings.forEmulator("pcsx2", Host.LINUX).first { it.key == "Renderer" }.choices.map { it.label })
    }
}
