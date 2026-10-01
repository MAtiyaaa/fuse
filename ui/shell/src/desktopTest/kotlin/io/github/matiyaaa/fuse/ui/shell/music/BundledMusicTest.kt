package io.github.matiyaaa.fuse.ui.shell.music

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.MusicSettings
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import io.github.matiyaaa.fuse.ui.shell.store.impl.GlobalScoped
import io.github.matiyaaa.fuse.ui.shell.store.impl.toUiPrefs
import io.github.matiyaaa.fuse.ui.shell.store.impl.withUiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledMusicTest {
    @Test
    fun everyTrackShipsAndUnpacksOnce(): Unit = runBlocking {
        val cache = Files.createTempDirectory("fuse-music").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
            val store = createFuseStore(services, scope)
            for (t in BundledMusic.tracks) {
                val path = assertNotNull(store.bundledTrack(t.id), t.id)
                val bytes = File(path).readBytes()
                // An MP3 with an ID3 tag, tagged with the artist.
                assertTrue(bytes.size > 500_000, "${t.id} is ${bytes.size} bytes")
                assertEquals("ID3", bytes.copyOf(3).decodeToString())
                assertTrue(BundledMusic.ARTIST in bytes.copyOf(4096).decodeToString(), "${t.id} credits the artist")
            }
            // Unpacked once: the same file comes back.
            val first = store.bundledTrack(BundledMusic.MENU_DEFAULT)
            assertEquals(first, store.bundledTrack(BundledMusic.MENU_DEFAULT))
            assertNull(store.bundledTrack("not-a-track"))
            assertNotNull(BundledMusic.byId(BundledMusic.ONBOARDING))
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }

    @Test
    fun earlierSettingsKeepTheUsersOwnSong() {
        val own = AppSettings(music = MusicSettings(songPath = "/data/music/1.mp3", songName = "Mine"))
        assertEquals(BundledMusic.OWN_SONG, own.toUiPrefs(GlobalScoped()).music.track)
        // Without a song of their own, everyone gets the default and it is saved back as chosen.
        val fresh = AppSettings().toUiPrefs(GlobalScoped())
        assertEquals(BundledMusic.MENU_DEFAULT, fresh.music.track)
        assertEquals(BundledMusic.MENU_DEFAULT, AppSettings().withUiPrefs(fresh).music.track)
        val picked = fresh.copy(music = fresh.music.copy(track = "sighonara"))
        assertEquals("sighonara", AppSettings().withUiPrefs(picked).toUiPrefs(GlobalScoped()).music.track)
    }
}
