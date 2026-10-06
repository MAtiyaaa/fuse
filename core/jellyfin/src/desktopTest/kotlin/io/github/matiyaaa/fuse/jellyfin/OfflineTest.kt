package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.PlaybackEvent
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferManager
import io.github.matiyaaa.fuse.transfer.TransferPlace
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Films and episodes kept for offline: names, what is kept, the download, moving and playing. */
class OfflineTest {
    private val root = Files.createTempDirectory("fuse-offline").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val film = MediaItem("f1", "Alien: Covenant", MediaType.MOVIE, year = 2017, runtimeMs = 7_000_000, poster = JellyfinArt("f1", ArtKind.PRIMARY, "p1"))
    private val episode = MediaItem(
        "e1", "Pilot", MediaType.EPISODE, seriesId = "s1", seriesName = "Halt and Catch Fire", seasonId = "ss1", season = 1, episode = 1,
        runtimeMs = 2_800_000, thumb = JellyfinArt("e1", ArtKind.PRIMARY, "t1"),
    )

    private fun source(size: Long) = MediaSourceDto(
        id = "ms1", container = "mkv", path = "/media/films/Alien Covenant.mkv", size = size,
        mediaStreams = listOf(
            MediaStreamDto(type = "Video", index = 0, codec = "hevc"),
            MediaStreamDto(type = "Audio", index = 1, codec = "eac3", language = "eng", isDefault = true, displayTitle = "English 5.1"),
            MediaStreamDto(type = "Audio", index = 2, codec = "aac", language = "fra", displayTitle = "Français"),
            MediaStreamDto(type = "Subtitle", index = 3, codec = "subrip", language = "eng", displayTitle = "English"),
            MediaStreamDto(type = "Subtitle", index = 4, codec = "srt", language = "spa", isExternal = true, displayTitle = "Español"),
            MediaStreamDto(type = "Subtitle", index = 5, codec = "PGSSUB", language = "deu", isExternal = true),
        ),
    )

    @Test
    fun `names are tidy folders and safe on every file system`() {
        assertEquals("Films/Alien Covenant (2017)", OfflinePlan.folder(film))
        assertEquals("Alien Covenant (2017).mkv", OfflinePlan.fileName(film, "mkv", "/x/whatever.MKV"))
        assertEquals("Shows/Halt and Catch Fire/Season 01", OfflinePlan.folder(episode))
        assertEquals("Halt and Catch Fire - S01E01 - Pilot.mp4", OfflinePlan.fileName(episode, "mp4", null))
        assertEquals("a b c", OfflinePlan.safe("a/b:c..."))
        assertEquals("Untitled", OfflinePlan.safe("???"))
        // Moving goes to the same folder, worked out from what was kept.
        assertEquals(OfflinePlan.folder(film), OfflinePlan.folderOf(OfflinePlan.meta(film, source(10))))
        assertEquals(OfflinePlan.folder(episode), OfflinePlan.folderOf(OfflinePlan.meta(episode, null)))
    }

    @Test
    fun `subtitles inside the file stay there, text ones beside it come along, picture ones beside it can't`() {
        val meta = OfflinePlan.meta(film, source(10))
        assertEquals(listOf(1, 2), meta.audio.map { it.index })
        assertEquals(listOf(0, 1), meta.audio.map { it.order })
        val subs = meta.subtitles.associateBy { it.index }
        assertEquals(0, subs.getValue(3).embeddedOrder)
        assertEquals("srt", subs.getValue(4).ext)
        assertEquals(-1, subs.getValue(4).embeddedOrder)
        assertNull(subs[5])
        assertEquals(10, meta.sizeBytes)
        assertEquals("Alien Covenant (2017).spa.4.srt", OfflinePlan.subtitleName("Alien Covenant (2017).mkv", subs.getValue(4)))
    }

    private class Host(val base: String = "http://jf") : OfflineHost {
        val landed = CopyOnWriteArrayList<Pair<String, OfflineMeta>>()
        val moved = CopyOnWriteArrayList<String>()
        override suspend fun session(server: String) = base to Account("srv", "Home", "u1", "me", "tok")
        override fun authorization(token: String) = "MediaBrowser Token=\"$token\""
        override suspend fun landed(job: JellyfinDownloadJob, item: TransferItem, video: String, meta: OfflineMeta) { landed += video to meta }
        override suspend fun moved(job: OfflineMoveJob, folder: String, place: TransferPlace) { moved += folder }
    }

    private suspend fun TransferManager.await(id: String, status: TransferStatus): TransferItem {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            items.value.firstOrNull { it.id == id && it.status == status }?.let { return it }
            delay(10)
        }
        error("never $status: ${items.value.firstOrNull { it.id == id }}")
    }

    @Test
    fun `a film downloads whole with its own subtitle file and pictures, signed in by header only`() = runBlocking<Unit> {
        val video = ByteArray(300_000) { (it % 251).toByte() }
        val asked = CopyOnWriteArrayList<String>()
        val noToken = AtomicInteger()
        val calls = AtomicInteger()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            asked += path
            if ("tok" in req.url.toString()) noToken.incrementAndGet()
            when {
                path == "/Items/f1/Download" -> {
                    assertEquals("MediaBrowser Token=\"tok\"", req.headers[HttpHeaders.Authorization])
                    val range = req.headers[HttpHeaders.Range]
                    // The first try breaks off part way; the second carries on from there.
                    if (calls.incrementAndGet() == 1) {
                        respond(video.copyOfRange(0, 100_000), HttpStatusCode.OK)
                    } else {
                        val from = range!!.removePrefix("bytes=").substringBefore('-').toInt()
                        respond(video.copyOfRange(from, video.size), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes $from-${video.size - 1}/${video.size}"))
                    }
                }
                path == "/Videos/f1/ms1/Subtitles/4/0/Stream.srt" -> respond("1\n00:00:01,000 --> 00:00:02,000\nHola\n")
                path.startsWith("/Items/f1/Images/") -> respond(ByteArray(64) { 1 })
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val host = Host()
        val m = TransferManager(File(root, "transfers"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)).also { it.start() }
        offlineHandlers(http, host).forEach(m::register)
        val meta = OfflinePlan.meta(film, source(video.size.toLong()))
        val job = JellyfinDownloadJob("srv", "f1", OfflinePlan.fileName(film, "mkv", null), meta)
        val folder = File(root, "Offline/" + OfflinePlan.folder(film))
        val id = m.enqueue(TransferItem("", "jellyfin:srv:f1", JELLYFIN_SOURCE, TransferDirection.DOWNLOAD, TransferKind.MEDIA, film.name, place = TransferPlace(folder.path), totalBytes = video.size.toLong(), payload = job.encode()))
        m.await(id, TransferStatus.DONE)
        val file = File(folder, "Alien Covenant (2017).mkv")
        assertContentEquals(video, file.readBytes())
        assertFalse(File(folder, ".${file.name}.fuse-part").exists())
        assertEquals(2, calls.get())
        val (path, kept) = host.landed.single()
        assertEquals(file.path, path)
        val srt = kept.subtitles.single { it.index == 4 }.file
        assertEquals("Hola", File(folder, srt).readLines()[2])
        assertNotNull(kept.poster)
        assertTrue(File(folder, kept.poster!!).isFile)
        // The token travels only in the header, never in an address.
        assertEquals(0, noToken.get())
    }

    @Test
    fun `a move copies everything first, then lets the old copy go`() = runBlocking<Unit> {
        val from = File(root, "card/Films/X (2001)").apply { mkdirs() }
        File(from, "X (2001).mkv").writeBytes(ByteArray(5000) { 7 })
        File(from, "X (2001).eng.1.srt").writeText("sub")
        val to = File(root, "usb/Fuse Offline/Films/X (2001)")
        val host = Host()
        val m = TransferManager(File(root, "transfers2"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)).also { it.start() }
        offlineHandlers(HttpClient(MockEngine { respond("") }), host).forEach(m::register)
        val job = OfflineMoveJob("jellyfin:srv:x", TransferPlace(from.path), listOf("X (2001).mkv", "X (2001).eng.1.srt"))
        val id = m.enqueue(TransferItem("", "offline-move:x", OFFLINE_MOVE_SOURCE, TransferDirection.DOWNLOAD, TransferKind.MEDIA, "X", place = TransferPlace(to.path), payload = job.encode()))
        m.await(id, TransferStatus.DONE)
        assertContentEquals(ByteArray(5000) { 7 }, File(to, "X (2001).mkv").readBytes())
        assertEquals("sub", File(to, "X (2001).eng.1.srt").readText())
        assertFalse(from.exists())
        assertEquals(listOf(to.path), host.moved)
    }

    private fun entry(meta: OfflineMeta, path: String?, key: String = "jellyfin:srv:${meta.itemId}") =
        OfflineEntry(key, "srv", meta.itemId, path, path ?: "/gone/${meta.itemId}.mkv", "USB stick", meta, 0)

    @Test
    fun `a kept film plays from its file, with its tracks and its subtitle files`() = runBlocking<Unit> {
        val folder = File(root, "f").apply { mkdirs() }
        val meta = OfflinePlan.meta(film, source(10)).let { m -> m.copy(subtitles = m.subtitles.map { if (it.index == 4) it.copy(file = "a.srt") else it }) }
        File(folder, "a.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHola\n")
        val e = entry(meta, File(folder, "film.mkv").path)
        val saved = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val r = OfflineResolver({ id -> e.takeIf { id == "f1" } }, { File(it).readText() }, { _, pos, done -> saved += pos to done }, { _, _ -> null })
        val src: PlaySource = r.resolve(e.toPlayItem(), PlayRequest(capabilities = CAPS))
        assertEquals(e.path, src.url)
        assertEquals(PlayMethod.DIRECT_PLAY, src.method)
        assertEquals(listOf("1", "2"), src.audioTracks.map { it.id })
        assertEquals("1", src.audio)
        val subs = src.subtitleTracks.associateBy { it.streamIndex }
        assertEquals(SubtitleDelivery.Embedded(0), subs.getValue(3).delivery)
        val ext = subs.getValue(4).delivery as SubtitleDelivery.External
        assertTrue(r.subtitleText(ext.url)!!.contains("Hola"))
        // Watched nearly to the end: it counts as watched.
        r.report(PlaybackEvent.Stopped(e.toPlayItem(), src, 6_500_000, finished = false))
        assertEquals(6_500_000L to true, saved.last())
        r.report(PlaybackEvent.Stopped(e.toPlayItem(), src, 1_000, finished = false))
        assertEquals(1_000L to false, saved.last())
    }

    @Test
    fun `a kept film on a drive that is out says which drive, and the next episode follows the show`() = runBlocking<Unit> {
        val away = entry(OfflinePlan.meta(film, null), null)
        val r = OfflineResolver({ away }, { null }, { _, _, _ -> }, { _, _ -> null })
        val err = assertFailsWith<JellyfinException> { r.resolve(away.toPlayItem(), PlayRequest(capabilities = CAPS)) }
        assertTrue("USB stick" in err.message!!)

        val one = entry(OfflinePlan.meta(episode, null), "/x/1.mkv")
        val two = entry(OfflinePlan.meta(episode.copy(id = "e2", episode = 2, name = "FUD"), null), "/x/2.mkv")
        val all = listOf(one, two)
        val shows = OfflineResolver(
            { id -> all.firstOrNull { it.itemId == id } }, { null }, { _, _, _ -> },
            { e, step -> all.sortedBy { it.meta.episode }.let { l -> l.getOrNull(l.indexOf(e) + step) } },
        )
        assertEquals("e2", shows.next(one.toPlayItem())?.id)
        assertEquals("e1", shows.previous(two.toPlayItem())?.id)
        assertNull(shows.next(two.toPlayItem()))
    }
}

private val CAPS = Capabilities(videoCodecs = emptyList(), audioCodecs = emptySet(), containers = emptySet())
