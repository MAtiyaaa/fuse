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
import io.ktor.client.engine.mock.respond
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledMusicTest {
    @Test
    fun theSongsInsideFuseUnpackOnce(): Unit = runBlocking {
        val cache = Files.createTempDirectory("fuse-music").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
            val store = createFuseStore(services, scope)
            val inside = BundledMusic.tracks.filter { it.inside }
            // The menu's default and setup's song: music with no network, from the first start.
            assertEquals(setOf(BundledMusic.MENU_DEFAULT, BundledMusic.ONBOARDING), inside.map { it.id }.toSet())
            for (t in inside) {
                val path = assertNotNull(store.bundledTrack(t.id), t.id)
                val bytes = File(path).readBytes()
                // WebM (Matroska's EBML header), exactly the file that was published.
                assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), bytes.copyOf(4).map { it.toInt() and 0xFF })
                assertEquals(t.bytes, bytes.size.toLong(), t.id)
                assertTrue(store.musicHere(t.id))
            }
            val first = store.bundledTrack(BundledMusic.MENU_DEFAULT)
            assertEquals(first, store.bundledTrack(BundledMusic.MENU_DEFAULT))
            assertNull(store.bundledTrack("not-a-track"))
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }

    @Test
    fun theOthersAreFetchedCheckedAndKeptAndNeverWrongBytes(): Unit = runBlocking {
        val cache = Files.createTempDirectory("fuse-music").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
            val store = createFuseStore(services, scope)
            val song = BundledMusic.remote.first()
            // Offline (nothing answers): no song, and nothing kept.
            assertNull(store.bundledTrack(song.id))
            assertFalse(store.musicHere(song.id))
            // Something that isn't the song (a captive portal's page, a cut-off download) is never kept.
            services.web = { request ->
                if (request.url.encodedPath.endsWith("/${song.id}.webm")) respond(ByteArray(song.bytes.toInt()) { 7 }, io.ktor.http.HttpStatusCode.OK) else null
            }
            assertNull(store.bundledTrack(song.id))
            assertFalse(store.musicHere(song.id))
            assertTrue(services.requestHosts.containsAll(listOf("raw.githubusercontent.com", "cdn.jsdelivr.net")), "both places were tried")
            // A song kept earlier plays with no network at all.
            services.web = null
            val kept = File(cache, BundledMusic.cachePath(song.id)).apply { parentFile.mkdirs(); writeBytes(ByteArray(song.bytes.toInt())) }
            assertTrue(store.musicHere(song.id))
            assertEquals(kept.absolutePath, store.bundledTrack(song.id))
            store.removeDownloadedMusic()
            assertFalse(kept.exists())
            assertTrue(store.musicHere(BundledMusic.MENU_DEFAULT), "the songs inside Fuse stay")
        } finally {
            scope.cancel()
            cache.deleteRecursively()
        }
    }

    @Test
    fun everySongIsDescribedOnce() {
        assertEquals(BundledMusic.tracks.size, BundledMusic.tracks.map { it.id }.toSet().size)
        for (t in BundledMusic.tracks) {
            assertTrue(t.bytes in 500_000..4_000_000, "${t.id} is ${t.bytes} bytes")
            assertTrue(Regex("[0-9a-f]{64}").matches(t.sha256), t.id)
        }
        // The songs to fetch are a few dozen megabytes; Fuse itself carries two.
        assertTrue(BundledMusic.remoteBytes in 20_000_000..60_000_000)
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
