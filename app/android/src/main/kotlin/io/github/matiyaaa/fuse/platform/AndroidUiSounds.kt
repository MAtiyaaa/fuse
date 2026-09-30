package io.github.matiyaaa.fuse.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.sound.ToneSynth
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds

/**
 * Interface sounds through one static [AudioTrack] per cue. Each cue's PCM is rendered by
 * [ToneSynth] once per profile and kept in its track, so playing is a restart, not a decode. All
 * audio work runs on its own thread; [play] only posts. Navigation cues follow the controller
 * settings (on/off and their own volume).
 */
class AndroidUiSounds : UiSounds {
    private val thread = HandlerThread("FuseSounds").apply { start() }
    private val handler = Handler(thread.looper)

    // Only touched on the sound thread.
    private val tracks = HashMap<SoundCue, AudioTrack>()
    private var loadedProfile: SoundProfile? = null

    @Volatile private var profile: SoundProfile = SoundProfile.SOFT
    @Volatile private var volume: Float = 0.6f
    @Volatile private var navigationEnabled: Boolean = true
    @Volatile private var navigationVolume: Float = 1f

    override fun play(cue: SoundCue) {
        if (profile == SoundProfile.OFF || volume <= 0f) return
        val isNavigation = cue in NAVIGATION
        if (isNavigation && (!navigationEnabled || navigationVolume <= 0f)) return
        handler.post { playNow(cue, isNavigation) }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun setProfile(profile: SoundProfile) {
        if (this.profile == profile) return
        this.profile = profile
        handler.post { if (profile == SoundProfile.OFF) releaseTracks() else load(profile) }
    }

    /** Controller settings: navigation sounds on/off and their volume relative to the rest. */
    fun setNavigation(enabled: Boolean, volume: Float) {
        navigationEnabled = enabled
        navigationVolume = volume.coerceIn(0f, 1f)
    }

    /** Renders every cue ahead of time so the first press already sounds instantly. */
    fun preload() {
        handler.post { if (profile != SoundProfile.OFF) load(profile) }
    }

    private fun playNow(cue: SoundCue, isNavigation: Boolean) {
        val current = profile
        if (current == SoundProfile.OFF) return
        if (loadedProfile != current) load(current)
        val track = tracks[cue] ?: return
        val gain = volume * (if (isNavigation) navigationVolume else 1f)
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.setVolume(gain)
            track.play()
        } catch (e: IllegalStateException) {
            // The track died (audio server restart): rebuild on the next cue.
            loadedProfile = null
        }
    }

    private fun load(profile: SoundProfile) {
        releaseTracks()
        for (cue in SoundCue.entries) {
            val pcm = ToneSynth.render(cue, profile)
            if (pcm.isEmpty()) continue
            buildTrack(pcm)?.let { tracks[cue] = it }
        }
        loadedProfile = profile
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack? = try {
        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (track.state == AudioTrack.STATE_NO_STATIC_DATA) track.write(pcm, 0, pcm.size)
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track
        } else {
            track.release()
            null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun releaseTracks() {
        tracks.values.forEach { it.release() }
        tracks.clear()
        loadedProfile = null
    }

    private companion object {
        val NAVIGATION = setOf(SoundCue.MOVE, SoundCue.MOVE_UP, SoundCue.MOVE_DOWN, SoundCue.BUMP)
    }
}
