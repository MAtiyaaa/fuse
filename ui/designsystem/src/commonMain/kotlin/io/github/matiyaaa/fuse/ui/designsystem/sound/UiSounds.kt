package io.github.matiyaaa.fuse.ui.designsystem.sound

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.matiyaaa.fuse.model.SoundProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
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
 *
 * Every note starts and ends exactly at silence (raised-cosine attack and release), so nothing
 * clicks however short the note, and bright overtones fade faster than the note itself, so the
 * highs stay soft. A gentle low-pass rounds off what is left above the voice range.
 */
object ToneSynth {
    const val SAMPLE_RATE = 44_100

    /** Fade-in and fade-out of each note, in seconds. */
    private const val ATTACK = 0.005
    private const val RELEASE = 0.008

    /** Where the final low-pass starts to soften, in hertz. */
    private const val LOW_PASS = 9_000.0

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
        // One-pole low-pass: takes the edge off the top overtones without dulling the note.
        val a = 1.0 - exp(-2.0 * PI * LOW_PASS / SAMPLE_RATE)
        var y = 0.0
        for (i in out.indices) {
            y += a * (out[i] - y)
            out[i] = y
        }
        return ShortArray(out.size) { i -> (out[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort() }
    }

    private data class Tone(val freq: Double, val duration: Double, val gain: Double, val delay: Double = 0.0)

    private fun addTone(out: DoubleArray, tone: Tone, profile: SoundProfile) {
        val start = (tone.delay * SAMPLE_RATE).toInt()
        val n = (tone.duration * SAMPLE_RATE).toInt()
        val attack = (ATTACK * SAMPLE_RATE).toInt().coerceIn(1, n / 2)
        val release = (RELEASE * SAMPLE_RATE).toInt().coerceIn(1, n / 2)
        // The chime's bell partial is quieter on high notes, where it would otherwise get shrill.
        val bell = 0.4 * min(1.0, 2_400.0 / (2.76 * tone.freq))
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val env = rise(i, attack) * rise(n - 1 - i, release) * exp(-t * decay(profile) / tone.duration)
            val phase = 2 * PI * tone.freq * t
            val wave = when (profile) {
                // Soft: pure sine with a whisper of the octave.
                SoundProfile.SOFT -> sin(phase) + 0.15 * sin(2 * phase)
                // Click: bright on the attack, the overtones dying away faster than the note.
                SoundProfile.CLICK -> sin(phase) + 0.35 * sin(3 * phase) * exp(-t * 30) + 0.08 * sin(5 * phase) * exp(-t * 60)
                // Chime: bell-like inharmonic partial.
                SoundProfile.CHIME -> sin(phase) + bell * sin(2.76 * phase) * exp(-t * 18)
                SoundProfile.OFF -> 0.0
            }
            out[idx] += wave * env * tone.gain
        }
    }

    /** A raised-cosine ramp from 0 to 1 over [length] samples: no corner, so no click. */
    private fun rise(k: Int, length: Int): Double = if (k >= length) 1.0 else 0.5 - 0.5 * cos(PI * k / length)

    private fun decay(profile: SoundProfile) = when (profile) {
        SoundProfile.CLICK -> 7.0
        SoundProfile.CHIME -> 3.2
        else -> 4.5
    }
}
