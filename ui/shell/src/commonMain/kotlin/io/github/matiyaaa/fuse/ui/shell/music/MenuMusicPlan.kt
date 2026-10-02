package io.github.matiyaaa.fuse.ui.shell.music

import io.github.matiyaaa.fuse.ui.shell.platform.MusicState
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs

/**
 * What the menu music should be, worked out from settings and what Fuse is doing. Kept apart from
 * the composable that applies it so every combination is tested on its own: the player only ever
 * receives one complete [MusicState], never a sequence of separate changes that could arrive out of
 * order or be lost.
 */
object MenuMusicPlan {
    /**
     * The song to play, by bundled id or [BundledMusic.OWN_SONG], or null for silence: off in
     * settings or safe mode; first-time setup plays its own song.
     */
    fun track(music: MusicPrefs, safeMode: Boolean, onboarding: Boolean): String? = when {
        !music.enabled || safeMode -> null
        onboarding -> BundledMusic.ONBOARDING
        music.track == BundledMusic.OWN_SONG && music.songPath == null -> BundledMusic.MENU_DEFAULT
        else -> music.track
    }

    /**
     * The state for the player: the resolved [song] file, the volume from settings (always passed,
     * so a change made while music is off is in place when it comes back on) and whether it may be
     * heard ([quiet] while a game starts or runs).
     */
    fun state(song: String?, music: MusicPrefs, quiet: Boolean): MusicState =
        MusicState(song = song, volume = music.volume.coerceIn(0f, 1f), playing = !quiet)
}
