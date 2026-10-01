package io.github.matiyaaa.fuse.ui.designsystem.sound

import io.github.matiyaaa.fuse.model.SoundProfile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToneSynthTest {

    private val audible = SoundProfile.entries.filter { it != SoundProfile.OFF }

    @Test
    fun everyCueStartsAndEndsInSilenceAndNeverClips() {
        for (profile in audible) {
            for (cue in SoundCue.entries) {
                val pcm = ToneSynth.render(cue, profile)
                val peak = pcm.maxOf { abs(it.toInt()) }
                assertTrue(peak > 0, "$cue in $profile makes a sound")
                assertTrue(peak < Short.MAX_VALUE * 0.9, "$cue in $profile stays clear of clipping")
                assertTrue(abs(pcm.first().toInt()) <= peak / 100, "$cue in $profile starts at silence")
                assertEquals(0, pcm.last().toInt(), "$cue in $profile ends in silence")
            }
        }
    }

    @Test
    fun aSingleNoteFadesOutInsteadOfStopping() {
        // The last sounding samples of a one-note cue are already near silence: no click at the end.
        for (profile in audible) {
            val pcm = ToneSynth.render(SoundCue.MOVE, profile)
            val peak = pcm.maxOf { abs(it.toInt()) }
            val lastSounding = pcm.indexOfLast { abs(it.toInt()) > 0 }
            val tail = (lastSounding - 20..lastSounding).maxOf { abs(pcm[it].toInt()) }
            assertTrue(tail <= peak / 50, "MOVE in $profile ends with a fade ($tail of $peak)")
        }
    }

    @Test
    fun offIsSilent() {
        for (cue in SoundCue.entries) assertEquals(0, ToneSynth.render(cue, SoundProfile.OFF).size)
    }
}
