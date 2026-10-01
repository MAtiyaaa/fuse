package io.github.matiyaaa.fuse.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import java.io.File

/**
 * Menu music on Android: a looping [MediaPlayer] per song, mixed on the main thread. It plays only
 * while Fuse wants it ([setPlaying]), Fuse is in front ([setForeground]) and a song is set, so games,
 * other apps and the screen turning off all get silence. Changing songs while music plays crossfades:
 * the old song fades out as the new one fades in.
 */
class AndroidMenuMusic : MenuMusicPlayer {
    private val main = Handler(Looper.getMainLooper())

    /** One song: its player and where it is in the crossfade (0 silent, 1 full). */
    private class Voice(val path: String, val player: MediaPlayer) {
        var prepared = false
        var mix = 0f
        var target = 1f
    }

    /** The song that should be heard. */
    private var current: Voice? = null

    /** Songs fading out after a change. */
    private val leaving = mutableListOf<Voice>()
    private var song: String? = null
    private var volume = 0.2f
    private var wanted = true
    private var foreground = false

    /** 0..1: the fade for games starting and Fuse leaving the screen, on top of every voice. */
    private var gate = 0f
    private var lastTick = 0L
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTick).coerceIn(0, 100)
            lastTick = now
            step(dt)
            if (ticking) main.postDelayed(this, FRAME_MS)
        }
    }

    override fun setSong(path: String?) = onMain {
        if (path == song) return@onMain
        song = path
        val old = current
        current = null
        if (old != null) {
            if (gate > 0f && old.prepared) {
                old.target = 0f
                leaving += old
            } else {
                release(old)
            }
        }
        if (path != null && File(path).isFile) {
            // With music already playing, the new song fades in over the old one; otherwise it starts at full mix.
            current = open(path, startMix = if (leaving.isNotEmpty()) 0f else 1f)
        }
        update()
    }

    override fun setVolume(volume: Float) = onMain {
        this.volume = volume.coerceIn(0f, 1f)
        applyVolumes()
    }

    override fun setPlaying(playing: Boolean) = onMain {
        wanted = playing
        update()
    }

    /** Fuse's main screen started (true) or stopped (false). */
    fun setForeground(inFront: Boolean) = onMain {
        foreground = inFront
        update()
    }

    private fun open(path: String, startMix: Float): Voice? {
        val p = MediaPlayer()
        return try {
            val voice = Voice(path, p).apply { mix = startMix }
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setDataSource(path)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnPreparedListener {
                voice.prepared = true
                update()
            }
            p.setOnErrorListener { _, _, _ ->
                // A file Android can't play: stay silent rather than retrying.
                if (current === voice) current = null
                leaving.remove(voice)
                release(voice)
                true
            }
            p.prepareAsync()
            voice
        } catch (e: Exception) {
            p.release()
            null
        }
    }

    private fun audible(): Boolean = wanted && foreground && song != null

    private fun update() {
        if (audible()) {
            (listOfNotNull(current) + leaving).forEach { v -> if (v.prepared && !v.player.isPlaying) v.player.start() }
        }
        startTicking()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastTick = SystemClock.uptimeMillis()
        main.post(tick)
    }

    /** Moves the gate and every voice's mix towards their targets. */
    private fun step(dt: Long) {
        val gateTarget = if (audible() && current?.prepared == true) 1f else 0f
        gate = if (gateTarget > gate) (gate + dt / FADE_IN_MS).coerceAtMost(1f) else (gate - dt / FADE_OUT_MS).coerceAtLeast(0f)
        current?.let { v -> if (v.prepared) v.mix = (v.mix + dt / CROSSFADE_MS).coerceAtMost(1f) }
        val done = leaving.filter { v ->
            v.mix = (v.mix - dt / CROSSFADE_MS).coerceAtLeast(0f)
            v.mix <= 0f
        }
        done.forEach { leaving.remove(it); release(it) }
        applyVolumes()
        if (gate <= 0f) {
            // Silent: pause instead of playing at zero volume, and drop what was fading out.
            current?.player?.takeIf { current?.prepared == true && it.isPlaying }?.pause()
            leaving.forEach(::release)
            leaving.clear()
        }
        val settled = leaving.isEmpty() && (current == null || current?.mix == 1f || current?.prepared != true) && gate == gateTarget
        if (settled) ticking = false
    }

    private fun applyVolumes() {
        // Perceived loudness follows the square of the level, so low settings stay usable.
        val base = volume * volume * gate
        (listOfNotNull(current) + leaving).forEach { v ->
            if (v.prepared) {
                val level = base * v.mix
                v.player.setVolume(level, level)
            }
        }
    }

    private fun release(voice: Voice) {
        runCatching { voice.player.release() }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val FRAME_MS = 30L
        const val FADE_IN_MS = 1_200f
        const val FADE_OUT_MS = 500f
        const val CROSSFADE_MS = 2_500f
    }
}
