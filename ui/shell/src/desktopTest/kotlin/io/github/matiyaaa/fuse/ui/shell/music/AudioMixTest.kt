package io.github.matiyaaa.fuse.ui.shell.music

import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioMixTest {
    private val prefs = UiPrefs(sound = SoundProfile.SOFT, soundVolume = 0.5f, music = MusicPrefs(enabled = true, volume = 0.4f))

    @Test fun masterOffSilencesMusicAndInterfaceSounds() {
        val mix = AudioMix.of(prefs.copy(muted = true))
        assertEquals(SoundProfile.OFF, mix.interfaceProfile)
        assertFalse(mix.music.enabled)
        assertFalse(mix.audible)
        // Nothing playing means no song is chosen either.
        assertEquals(null, MenuMusicPlan.track(mix.music, safeMode = false, onboarding = false))
    }

    @Test fun masterVolumeScalesEachKindUnderItsOwnVolume() {
        val mix = AudioMix.of(prefs.copy(masterVolume = 0.5f))
        assertEquals(0.25f, mix.interfaceVolume)
        assertEquals(0.2f, mix.music.volume)
        assertTrue(mix.music.enabled)
    }

    @Test fun eachKindKeepsItsOwnSwitch() {
        val mix = AudioMix.of(prefs.copy(sound = SoundProfile.OFF))
        assertEquals(SoundProfile.OFF, mix.interfaceProfile)
        assertTrue(mix.music.enabled)
    }

    @Test fun masterAtZeroIsSilenceToo() {
        val mix = AudioMix.of(prefs.copy(masterVolume = 0f))
        assertFalse(mix.audible)
    }
}
