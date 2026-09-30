package io.github.matiyaaa.fuse.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import java.io.File

/**
 * Menu music on Android: one looping [MediaPlayer], faded in and out on the main thread. It plays
 * only while Fuse wants it ([setPlaying]), Fuse is in front ([setForeground]) and a song is set, so
 * games, other apps and the screen turning off all get silence.
 */
class AndroidMenuMusic : MenuMusicPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var song: String? = null
    private var volume = 0.2f
    private var wanted = true
    private var foreground = false

    /** 0..1 on top of [volume]: where the current fade is. */
    private var level = 0f
    private var fadeTarget = 0f
    private var fadeFrom = 0f
    private var fadeStart = 0L
    private var fadeMs = 0L
    private var fading = false
    private val fadeStep = object : Runnable {
        override fun run() {
            val t = if (fadeMs <= 0) 1f else ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            level = fadeFrom + (fadeTarget - fadeFrom) * t
            applyVolume()
            if (t < 1f) {
                main.postDelayed(this, FRAME_MS)
                return
            }
            fading = false
            if (fadeTarget == 0f) player?.takeIf { prepared && it.isPlaying }?.pause()
        }
    }

    override fun setSong(path: String?) = onMain {
        if (path == song) return@onMain
        song = path
        release()
        if (path != null && File(path).isFile) open(path)
        update()
    }

    override fun setVolume(volume: Float) = onMain {
        this.volume = volume.coerceIn(0f, 1f)
        applyVolume()
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

    private fun open(path: String) {
        val p = MediaPlayer()
        try {
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
                if (player !== p) return@setOnPreparedListener
                prepared = true
                update()
            }
            p.setOnErrorListener { _, _, _ ->
                // A file Android can't play: stay silent rather than retrying.
                if (player === p) release()
                true
            }
            p.prepareAsync()
            player = p
        } catch (e: Exception) {
            p.release()
        }
    }

    private fun update() {
        val p = player ?: return
        if (!prepared) return
        val play = wanted && foreground && song != null
        if (play) {
            if (!p.isPlaying) p.start()
            fadeTo(1f, FADE_IN_MS)
        } else {
            fadeTo(0f, FADE_OUT_MS)
        }
    }

    private fun fadeTo(target: Float, ms: Long) {
        if (fadeTarget == target && (fading || level == target)) return
        main.removeCallbacks(fadeStep)
        fading = true
        fadeFrom = level
        fadeTarget = target
        fadeStart = SystemClock.uptimeMillis()
        fadeMs = ms
        main.post(fadeStep)
    }

    private fun applyVolume() {
        val p = player ?: return
        if (!prepared) return
        // Perceived loudness follows the square of the level, so low settings stay usable.
        val v = (volume * volume) * level
        p.setVolume(v, v)
    }

    private fun release() {
        main.removeCallbacks(fadeStep)
        fading = false
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        level = 0f
        fadeTarget = 0f
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val FRAME_MS = 30L
        const val FADE_IN_MS = 1_200L
        const val FADE_OUT_MS = 500L
    }
}
