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
enum class SoundCue {
    MOVE, MOVE_UP, MOVE_DOWN, SELECT, BACK, OPEN, CLOSE, ERROR, BUMP, TOGGLE, ACHIEVEMENT, LAUNCH,

    /** A match struck: a dry scrape with a crackle, as the startup animation's spark catches. */
    STRIKE,

    /** The flare after it: a breath of fire that rises and opens out as the spark runs. */
    FLARE,

    /** A slow, warm swell under a soft chord, as setup's opening pulls back and opens out. */
    BLOOM,
}

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

    /** How far below its note a bubble note starts (a fraction), and how fast it rises into place. */
    private const val BUBBLE_DIP = 0.2
    private const val BUBBLE_RISE = 55.0

    private fun note(semitonesFromA4: Int): Double = 440.0 * 2.0.pow(semitonesFromA4 / 12.0)

    fun render(cue: SoundCue, profile: SoundProfile): ShortArray {
        if (profile == SoundProfile.OFF) return ShortArray(0)
        // The fire cues are noise shaped like fire, the same for every profile (only Off silences them).
        when (cue) {
            SoundCue.STRIKE -> return finish(strike())
            SoundCue.FLARE -> return finish(flare())
            SoundCue.BLOOM -> return finish(bloom(profile))
            else -> Unit
        }
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
            SoundCue.STRIKE, SoundCue.FLARE, SoundCue.BLOOM -> emptyList()
        }
        val length = parts.maxOf { it.delay + it.duration } + 0.02
        val out = DoubleArray((length * SAMPLE_RATE).toInt())
        for (tone in parts) addTone(out, tone, profile)
        return finish(out)
    }

    /** The shared last step: the gentle low-pass, then 16-bit samples with headroom. */
    private fun finish(out: DoubleArray): ShortArray {
        // One-pole low-pass: takes the edge off the top overtones without dulling the note.
        val a = 1.0 - exp(-2.0 * PI * LOW_PASS / SAMPLE_RATE)
        var y = 0.0
        for (i in out.indices) {
            y += a * (out[i] - y)
            out[i] = y
        }
        return ShortArray(out.size) { i -> (out[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort() }
    }

    /** Repeatable white noise (the same sound every time), -1 to 1. */
    private class Noise(seed: Long) {
        private var s = seed
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
    }

    /** A rise from 0 to 1 over [n] samples and a fall back over the last [m]: no click at either end. */
    private fun edges(i: Int, total: Int, n: Int, m: Int): Double = rise(i, n) * rise(total - 1 - i, m)

    /**
    * A match: two quick scrapes of bright noise (the head dragged on the box), a pop as it catches,
    * and a few crackles dying away.
    */
    private fun strike(): DoubleArray {
        val total = (0.34 * SAMPLE_RATE).toInt()
        val out = DoubleArray(total + (0.02 * SAMPLE_RATE).toInt())
        val noise = Noise(0x5741)
        var hp = 0.0
        var last = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val n = noise.next()
            // High-passed: the dry scrape is all top.
            hp = 0.86 * (hp + n - last)
            last = n
            val scrape = gate(t, 0.0, 0.045) * 0.9 + gate(t, 0.055, 0.11) * 0.75
            val catchPop = if (t in 0.11..0.135) exp(-(t - 0.11) * 160) else 0.0
            val crackle = if (t > 0.13 && noise.next() > 0.9965) 0.8 * exp(-(t - 0.13) * 9) else 0.0
            out[i] = (hp * 0.32 * scrape + catchPop * 0.35 * n + crackle * n.coerceAtLeast(0.3)) * edges(i, total, 60, 400)
        }
        return out
    }

    /**
     * The flare: noise through a low-pass that opens as the flame takes (a breath rising from a low
     * roar to a bright hiss), over a faint warm hum, swelling then settling.
     */
    private fun flare(): DoubleArray {
        val total = (0.9 * SAMPLE_RATE).toInt()
        val out = DoubleArray(total + (0.02 * SAMPLE_RATE).toInt())
        val noise = Noise(0xF1A4E)
        var lp = 0.0
        var lp2 = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val k = t / 0.9
            // The filter opens from about 300 Hz to 3.5 kHz, quickly then slowly.
            val cutoff = 300.0 + 3_200.0 * (1 - exp(-k * 5))
            val a = 1.0 - exp(-2.0 * PI * cutoff / SAMPLE_RATE)
            lp += a * (noise.next() - lp)
            lp2 += a * (lp - lp2)
            val swell = sin(PI * k.coerceAtMost(1.0)).let { it * it } * (1 - 0.35 * k)
            val hum = sin(2 * PI * 110.0 * t) * 0.08 + sin(2 * PI * 165.0 * t) * 0.04
            out[i] = (lp2 * 1.5 + hum) * 0.42 * swell * edges(i, total, 200, 1_200)
        }
        return out
    }

    /** The bloom: a soft airy rush under a warm open chord that swells in and fades out slowly. */
    private fun bloom(profile: SoundProfile): DoubleArray {
        val total = (1.6 * SAMPLE_RATE).toInt()
        val out = DoubleArray(total + (0.02 * SAMPLE_RATE).toInt())
        val noise = Noise(0xB100)
        var lp = 0.0
        val chord = doubleArrayOf(note(-9), note(-2), note(3), note(7))
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val k = t / 1.6
            val env = (1 - exp(-t * 4)) * exp(-k * 2.2)
            var tone = 0.0
            for ((j, f) in chord.withIndex()) {
                // A slow shimmer: each voice drifts a hair, so the chord breathes.
                val drift = 1 + 0.0015 * sin(2 * PI * (0.7 + j * 0.3) * t)
                tone += sin(2 * PI * f * drift * t) * (if (j == 0) 0.32 else 0.2)
            }
            if (profile == SoundProfile.CHIME || profile == SoundProfile.BUBBLE) tone += 0.08 * sin(2 * PI * note(15) * t) * exp(-t * 3)
            lp += 0.03 * (noise.next() - lp)
            val air = lp * 1.6 * sin(PI * k).coerceAtLeast(0.0)
            out[i] = (tone * 0.42 + air * 0.5) * env * edges(i, total, 400, 2_000)
        }
        return out
    }

    /** 1 between [from] and [to] seconds with soft edges, 0 outside. */
    private fun gate(t: Double, from: Double, to: Double): Double =
        if (t < from || t > to) 0.0 else sin(PI * (t - from) / (to - from))

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
                // Bubble: the note starts a little low and rises into place, round and airy.
                SoundProfile.BUBBLE -> {
                    val rising = 2 * PI * tone.freq * (t - BUBBLE_DIP * (1 - exp(-t * BUBBLE_RISE)) / BUBBLE_RISE)
                    sin(rising) + 0.1 * sin(2 * rising)
                }
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
        SoundProfile.BUBBLE -> 5.5
        else -> 4.5
    }
}
