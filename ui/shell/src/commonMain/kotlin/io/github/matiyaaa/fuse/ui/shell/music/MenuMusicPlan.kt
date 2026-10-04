package io.github.matiyaaa.fuse.ui.shell.music

import io.github.matiyaaa.fuse.ui.shell.platform.MusicState
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import kotlin.random.Random

/**
 * What the menu music should be, worked out from settings and what Fuse is doing. Kept apart from
 * the composable that applies it so every combination is tested on its own: the player only ever
 * receives one complete [MusicState], never a sequence of separate changes that could arrive out of
 * order or be lost.
 */
object MenuMusicPlan {
    /**
     * The song to play, by bundled id or [BundledMusic.OWN_SONG], or null for silence: off in
     * settings or safe mode; first-time setup plays its own song. With shuffle on, [shuffled] (the
     * song shuffle picked last) plays instead of the chosen one.
     */
    fun track(music: MusicPrefs, safeMode: Boolean, onboarding: Boolean, shuffled: String? = null): String? = when {
        !music.enabled || safeMode -> null
        onboarding -> BundledMusic.ONBOARDING
        music.shuffle && shuffled != null -> shuffled
        music.track == BundledMusic.OWN_SONG && music.songPath == null -> BundledMusic.MENU_DEFAULT
        else -> music.track
    }

    /**
     * The next song for shuffle: any bundled song but the one that just played, and none of the
     * [recent] ones while others are left, so a song comes back only after most of the rest.
     */
    fun nextShuffled(current: String?, recent: List<String> = emptyList(), random: Random = Random.Default): String {
        val all = BundledMusic.tracks.map { it.id }
        val fresh = all.filter { it != current && it !in recent }
        val pool = fresh.ifEmpty { all.filter { it != current } }.ifEmpty { all }
        return pool[random.nextInt(pool.size)]
    }

    /**
     * The state for the player: the resolved [song] file, the volume from settings (always passed,
     * so a change made while music is off is in place when it comes back on) and whether it may be
     * heard ([quiet] while a game starts or runs). At volume 0 the song pauses instead of playing
     * silently, so nothing plays (or is recorded) that can't be heard. Shuffle plays each song once.
     */
    fun state(song: String?, music: MusicPrefs, quiet: Boolean): MusicState {
        val volume = music.volume.coerceIn(0f, 1f)
        return MusicState(song = song, volume = volume, playing = !quiet && volume > 0f, loop = !music.shuffle)
    }
}
