package io.github.matiyaaa.fuse.ui.shell.music

import io.github.matiyaaa.fuse.ui.shell.platform.MusicState
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The menu music's plan for every combination the settings and Fuse can be in. The player only ever
 * gets these complete states, so a volume change can never be lost or overtake a song change.
 */
class MenuMusicPlanTest {
    private val song = "/cache/music/puddleworld.mp3"

    @Test
    fun volumeChangesWhilePlayingReachThePlayerAsTheyAre() {
        val steps = listOf(1f, 0.5f, 0f, 0.5f)
        val states = steps.map { MenuMusicPlan.state(song, MusicPrefs(volume = it), quiet = false) }
        assertEquals(steps, states.map { it.volume })
        // The song never changes along the way, so the player never restarts it; at 0 it only pauses.
        states.forEach { assertEquals(song, it.song); assertEquals(it.volume > 0f, it.playing) }
    }

    @Test
    fun zeroVolumeKeepsTheSongButPausesIt() {
        val music = MusicPrefs(volume = 0f, shuffle = false)
        assertEquals(BundledMusic.MENU_DEFAULT, MenuMusicPlan.track(music, safeMode = false, onboarding = false))
        // Paused rather than playing silently, so a screen recording never captures a muted song.
        assertEquals(MusicState(song, 0f, false), MenuMusicPlan.state(song, music, quiet = false))
    }

    @Test
    fun shufflePlaysEachSongOnceAndNeverRepeatsRecentOnes() {
        val shuffle = MusicPrefs(shuffle = true)
        assertEquals(false, MenuMusicPlan.state(song, shuffle, quiet = false).loop)
        // Shuffle is on unless turned off; one song loops.
        assertEquals(false, MenuMusicPlan.state(song, MusicPrefs(), quiet = false).loop)
        assertEquals(true, MenuMusicPlan.state(song, MusicPrefs(shuffle = false), quiet = false).loop)
        assertEquals("mirth", MenuMusicPlan.track(shuffle, safeMode = false, onboarding = false, shuffled = "mirth"))
        // Setup keeps its own song even with shuffle on.
        assertEquals(BundledMusic.ONBOARDING, MenuMusicPlan.track(shuffle, safeMode = false, onboarding = true, shuffled = "mirth"))
        val random = kotlin.random.Random(7)
        val recent = ArrayDeque<String>()
        var current: String? = null
        repeat(200) {
            val next = MenuMusicPlan.nextShuffled(current, recent.toList(), random)
            kotlin.test.assertNotEquals(current, next)
            kotlin.test.assertTrue(next !in recent)
            recent.addLast(next)
            if (recent.size > BundledMusic.tracks.size / 2) recent.removeFirst()
            current = next
        }
    }

    @Test
    fun volumeChangedWhileOffIsInPlaceWhenMusicComesBack() {
        val off = MusicPrefs(enabled = false, volume = 0.2f, shuffle = false)
        assertNull(MenuMusicPlan.track(off, safeMode = false, onboarding = false))
        val changedWhileOff = off.copy(volume = 0.7f)
        assertEquals(0.7f, MenuMusicPlan.state(null, changedWhileOff, quiet = false).volume)
        val on = changedWhileOff.copy(enabled = true)
        assertEquals(BundledMusic.MENU_DEFAULT, MenuMusicPlan.track(on, safeMode = false, onboarding = false))
        assertEquals(MusicState(song, 0.7f, true), MenuMusicPlan.state(song, on, quiet = false))
    }

    @Test
    fun rapidChangesSettleOnTheLastValue() {
        var music = MusicPrefs()
        repeat(40) { i -> music = music.copy(volume = (i % 11) / 10f) }
        assertEquals(music.volume, MenuMusicPlan.state(song, music, quiet = false).volume)
    }

    @Test
    fun volumeIsKeptInRange() {
        assertEquals(1f, MenuMusicPlan.state(song, MusicPrefs(volume = 3f), quiet = false).volume)
        assertEquals(0f, MenuMusicPlan.state(song, MusicPrefs(volume = -1f), quiet = false).volume)
    }

    @Test
    fun aGameMakesItQuietWithoutDroppingTheSong() {
        val state = MenuMusicPlan.state(song, MusicPrefs(), quiet = true)
        assertEquals(song, state.song)
        assertEquals(false, state.playing)
    }

    @Test
    fun setupPlaysItsOwnSongAndSafeModeIsSilent() {
        assertEquals(BundledMusic.ONBOARDING, MenuMusicPlan.track(MusicPrefs(track = "mirth"), safeMode = false, onboarding = true))
        assertEquals("mirth", MenuMusicPlan.track(MusicPrefs(track = "mirth"), safeMode = false, onboarding = false))
        assertNull(MenuMusicPlan.track(MusicPrefs(), safeMode = true, onboarding = false))
    }

    @Test
    fun anOwnSongThatIsGoneFallsBackToFusesSong() {
        val own = MusicPrefs(track = BundledMusic.OWN_SONG, songPath = null)
        assertEquals(BundledMusic.MENU_DEFAULT, MenuMusicPlan.track(own, safeMode = false, onboarding = false))
        assertEquals(BundledMusic.OWN_SONG, MenuMusicPlan.track(own.copy(songPath = "/x.mp3"), safeMode = false, onboarding = false))
    }
}
