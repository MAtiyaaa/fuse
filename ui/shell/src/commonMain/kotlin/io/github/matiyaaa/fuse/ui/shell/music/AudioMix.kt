package io.github.matiyaaa.fuse.ui.shell.music

import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/**
 * Every sound Fuse makes, worked out in one place from the person's settings. The master switch
 * and master volume sit over each kind of sound's own switch and volume, so "sound off" means
 * nothing plays: not the music, not the interface sounds, not the startup sound. Each kind still
 * keeps its own setting, which comes back as it was when the master is turned on again.
 */
data class AudioMix(
    /** The interface sounds' style, [SoundProfile.OFF] when they may not be heard. */
    val interfaceProfile: SoundProfile,
    /** 0..1, already scaled by the master volume. */
    val interfaceVolume: Float,
    /** The menu music as it may be heard: off under the master switch, its volume scaled by the master. */
    val music: MusicPrefs,
) {
    /** Whether anything at all can be heard. */
    val audible: Boolean get() = interfaceProfile != SoundProfile.OFF && interfaceVolume > 0f || music.enabled && music.volume > 0f

    companion object {
        fun of(prefs: UiPrefs): AudioMix {
            val master = if (prefs.muted) 0f else prefs.masterVolume.coerceIn(0f, 1f)
            val silent = master <= 0f
            return AudioMix(
                interfaceProfile = if (silent) SoundProfile.OFF else prefs.sound,
                interfaceVolume = (prefs.soundVolume.coerceIn(0f, 1f) * master),
                music = prefs.music.copy(
                    enabled = prefs.music.enabled && !silent,
                    volume = prefs.music.volume.coerceIn(0f, 1f) * master,
                ),
            )
        }
    }
}
