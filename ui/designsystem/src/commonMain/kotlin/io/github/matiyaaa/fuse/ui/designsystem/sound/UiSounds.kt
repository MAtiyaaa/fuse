package io.github.matiyaaa.fuse.ui.designsystem.sound

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.matiyaaa.fuse.model.SoundProfile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Interface sounds. All are synthesised by [ToneSynth]; no recorded or console sounds are shipped. */
enum class SoundCue { MOVE, MOVE_UP, MOVE_DOWN, SELECT, BACK, OPEN, CLOSE, ERROR, BUMP, TOGGLE, ACHIEVEMENT, LAUNCH }

/** Plays cues. Platforms implement it (Android AudioTrack, Linux javax.sound). */
interface UiSounds {
    fun play(cue: SoundCue)
    fun setVolume(volume: Float)
    fun setProfile(profile: SoundProfile)

    object Silent : UiSounds {
        override fun play(cue: SoundCue) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setProfile(profile: SoundProfile) = Unit
    }
}

val LocalUiSounds = staticCompositionLocalOf<UiSounds> { UiSounds.Silent }

/**
 * Generates short 16-bit mono PCM tones for each cue. Pitches sit on a pentatonic scale so rapid
 * movement sounds musical rather than clicky, and movement direction nudges the pitch.
 */
object ToneSynth {
    const val SAMPLE_RATE = 44_100

    private fun note(semitonesFromA4: Int): Double = 440.0 * 2.0.pow(semitonesFromA4 / 12.0)

    fun render(cue: SoundCue, profile: SoundProfile): ShortArray {
        if (profile == SoundProfile.OFF) return ShortArray(0)
        val parts: List<Tone> = when (cue) {
            SoundCue.MOVE -> listOf(Tone(note(7), 0.038, 0.20))
            SoundCue.MOVE_UP -> listOf(Tone(note(10), 0.038, 0.20))
            SoundCue.MOVE_DOWN -> listOf(Tone(note(5), 0.038, 0.20))
            SoundCue.SELECT -> listOf(Tone(note(12), 0.05, 0.28), Tone(note(19), 0.07, 0.22, delay = 0.035))
            SoundCue.BACK -> listOf(Tone(note(10), 0.05, 0.22), Tone(note(3), 0.07, 0.18, delay = 0.03))
            SoundCue.OPEN -> listOf(Tone(note(7), 0.06, 0.2), Tone(note(12), 0.06, 0.18, delay = 0.04), Tone(note(19), 0.1, 0.15, delay = 0.08))
            SoundCue.CLOSE -> listOf(Tone(note(19), 0.05, 0.16), Tone(note(12), 0.05, 0.16, delay = 0.035), Tone(note(7), 0.08, 0.14, delay = 0.07))
            SoundCue.ERROR -> listOf(Tone(note(-2), 0.09, 0.26), Tone(note(-5), 0.14, 0.24, delay = 0.09))
            SoundCue.BUMP -> listOf(Tone(note(-9), 0.045, 0.12))
            SoundCue.TOGGLE -> listOf(Tone(note(14), 0.04, 0.2))
            SoundCue.ACHIEVEMENT -> listOf(
                Tone(note(12), 0.12, 0.2), Tone(note(16), 0.12, 0.18, delay = 0.09),
                Tone(note(19), 0.14, 0.18, delay = 0.18), Tone(note(24), 0.4, 0.2, delay = 0.27),
            )
            SoundCue.LAUNCH -> listOf(Tone(note(7), 0.08, 0.2), Tone(note(14), 0.08, 0.2, delay = 0.06), Tone(note(19), 0.25, 0.18, delay = 0.12))
        }
        val length = parts.maxOf { it.delay + it.duration } + 0.02
        val out = DoubleArray((length * SAMPLE_RATE).toInt())
        for (tone in parts) addTone(out, tone, profile)
        return ShortArray(out.size) { i -> (out[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort() }
    }

    private data class Tone(val freq: Double, val duration: Double, val gain: Double, val delay: Double = 0.0)

    private fun addTone(out: DoubleArray, tone: Tone, profile: SoundProfile) {
        val start = (tone.delay * SAMPLE_RATE).toInt()
        val n = (tone.duration * SAMPLE_RATE).toInt()
        val attack = (0.004 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-t * decay(profile) / tone.duration)
            val phase = 2 * PI * tone.freq * t
            val wave = when (profile) {
                // Soft: pure sine with a whisper of the octave.
                SoundProfile.SOFT -> sin(phase) + 0.15 * sin(2 * phase)
                // Click: brighter, shorter.
                SoundProfile.CLICK -> sin(phase) + 0.35 * sin(3 * phase) + 0.1 * sin(5 * phase)
                // Chime: bell-like inharmonic partial.
                SoundProfile.CHIME -> sin(phase) + 0.4 * sin(2.76 * phase) * exp(-t * 18)
                SoundProfile.OFF -> 0.0
            }
            out[idx] += wave * env * tone.gain
        }
    }

    private fun decay(profile: SoundProfile) = when (profile) {
        SoundProfile.CLICK -> 7.0
        SoundProfile.CHIME -> 3.2
        else -> 4.5
    }
}
