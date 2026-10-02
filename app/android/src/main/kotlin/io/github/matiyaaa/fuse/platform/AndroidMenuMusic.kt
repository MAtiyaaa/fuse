package io.github.matiyaaa.fuse.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import io.github.matiyaaa.fuse.ui.shell.platform.MusicState
import java.io.File

/**
 * Menu music on Android: a looping [MediaPlayer] per song, mixed on the main thread. It plays only
 * while Fuse wants it ([apply]), Fuse is in front ([setForeground]) and a song is set, so games,
 * other apps and the screen turning off all get silence. Changing songs while music plays crossfades:
 * the old song fades out as the new one fades in.
 *
 * Every call ends in [reconcile], which compares what is wanted with what the players are really
 * doing and repairs the difference. A player Android killed (an error, the media server restarting)
 * is replaced by a fresh one after a short backoff, so the music never stays silent until it is
 * switched off and on again.
 */
class AndroidMenuMusic : MenuMusicPlayer {
    private val main = Handler(Looper.getMainLooper())

    /** One song: its player and where it is in the crossfade (0 silent, 1 full). */
    private class Voice(val path: String, val player: MediaPlayer) {
        var prepared = false
        var dead = false
        var mix = 0f
    }

    /** The song that should be heard. */
    private var current: Voice? = null

    /** Songs fading out after a change. */
    private val leaving = mutableListOf<Voice>()
    private var wanted = MusicState(song = null, volume = 0.2f, playing = true)
    private var foreground = false

    /** Failed opens of [failedPath] in a row; reset by a new song, a success or Fuse coming back. */
    private var failedPath: String? = null
    private var failures = 0
    private var retryPending = false

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

    override fun apply(state: MusicState) = onMain {
        val clamped = state.copy(volume = state.volume.coerceIn(0f, 1f))
        if (clamped.song != wanted.song) resetFailures()
        wanted = clamped
        reconcile()
    }

    /** Fuse's main screen started (true) or stopped (false). Coming back checks every player again. */
    fun setForeground(inFront: Boolean) = onMain {
        foreground = inFront
        if (inFront) resetFailures()
        reconcile()
    }

    private fun resetFailures() {
        failedPath = null
        failures = 0
    }

    /** Brings the players in line with [wanted]: the right song open and healthy, its volume set. */
    private fun reconcile() {
        val song = wanted.song?.takeIf { File(it).isFile }
        val healthy = current?.takeIf { !it.dead && it.path == song }
        if (healthy == null) {
            current?.let(::retire)
            current = null
            if (song != null && (failedPath != song || failures < MAX_FAILURES)) {
                // With music already playing, the new song fades in over the old one; otherwise it starts at full mix.
                current = open(song, startMix = if (leaving.isNotEmpty()) 0f else 1f)
            }
        }
        if (audible()) {
            (listOfNotNull(current) + leaving).forEach { v ->
                if (v.prepared && !v.dead && !v.player.isPlaying) runCatching { v.player.start() }.onFailure { fail(v) }
            }
        }
        applyVolumes()
        startTicking()
    }

    /** Fades [old] out when it was being heard, else lets it go at once. */
    private fun retire(old: Voice) {
        if (!old.dead && old.prepared && gate > 0f) {
            leaving += old
        } else {
            release(old)
        }
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
                if (voice.dead) return@setOnPreparedListener
                voice.prepared = true
                if (failedPath == path) resetFailures()
                reconcile()
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "menu music player error $what/$extra")
                fail(voice)
                true
            }
            p.prepareAsync()
            voice
        } catch (e: Exception) {
            Log.w(TAG, "menu music couldn't open its song", e)
            runCatching { p.release() }
            noteFailure(path)
            null
        }
    }

    /**
     * A player stopped working: it is dropped, and the song is opened again after a short wait
     * (longer each time), up to [MAX_FAILURES] times in a row for the same song.
     */
    private fun fail(voice: Voice) {
        if (voice.dead) return
        voice.dead = true
        leaving.remove(voice)
        if (current === voice) current = null
        release(voice)
        noteFailure(voice.path)
        scheduleRetry()
    }

    private fun noteFailure(path: String) {
        if (failedPath == path) failures++ else {
            failedPath = path
            failures = 1
        }
    }

    private fun scheduleRetry() {
        if (retryPending || failures >= MAX_FAILURES) return
        retryPending = true
        main.postDelayed({
            retryPending = false
            reconcile()
        }, RETRY_MS * failures)
    }

    private fun audible(): Boolean = wanted.playing && foreground && wanted.song != null

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
            current?.takeIf { it.prepared && !it.dead }?.let { v -> runCatching { if (v.player.isPlaying) v.player.pause() }.onFailure { fail(v) } }
            leaving.forEach(::release)
            leaving.clear()
        }
        val settled = leaving.isEmpty() && (current == null || current?.mix == 1f || current?.prepared != true) && gate == gateTarget
        if (settled) ticking = false
    }

    private fun applyVolumes() {
        // Perceived loudness follows the square of the level, so low settings stay usable.
        val base = wanted.volume * wanted.volume * gate
        for (v in listOfNotNull(current) + leaving) {
            if (!v.prepared || v.dead) continue
            val level = base * v.mix
            runCatching { v.player.setVolume(level, level) }.onFailure { fail(v) }
        }
    }

    private fun release(voice: Voice) {
        voice.dead = true
        runCatching { voice.player.release() }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val TAG = "FuseMusic"
        const val FRAME_MS = 30L
        const val FADE_IN_MS = 1_200f
        const val FADE_OUT_MS = 500f
        const val CROSSFADE_MS = 2_500f

        /** Opens of one song that may fail in a row before Fuse stays quiet until something changes. */
        const val MAX_FAILURES = 3
        const val RETRY_MS = 800L
    }
}
