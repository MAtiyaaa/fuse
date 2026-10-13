package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.utils.io.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fuse's RomM side against a real RomM server: signing in, which of its systems and games Fuse
 * recognises, their art, bringing a game down into the library, and save sync. Runs only when
 * FUSE_ROMM_LIVE_URL, FUSE_ROMM_LIVE_USER and FUSE_ROMM_LIVE_PASSWORD are set (never in CI); what it
 * finds is printed for a person to read.
 */
class LiveRommTest {
    private val url = System.getenv("FUSE_ROMM_LIVE_URL")?.trimEnd('/')
    private val user = System.getenv("FUSE_ROMM_LIVE_USER")
    private val password = System.getenv("FUSE_ROMM_LIVE_PASSWORD")
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    private val real = java.net.http.HttpClient.newBuilder()
        .proxy(ProxySelector.getDefault())
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build()

    @BeforeTest
    fun setUp() {
        assumeTrue("Set FUSE_ROMM_LIVE_URL, _USER and _PASSWORD to test against a RomM server", url != null && user != null && password != null)
        root = Files.createTempDirectory("fuse-live-romm").toFile()
        cache = Files.createTempDirectory("fuse-live-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        if (url == null) return
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    /** The test's services, with every call to the server sent to it for real (everything else as the fake answers). */
    private fun services(): FakeServices {
        val host = URI(url!!).host
        val services = FakeServices(FuseData(DesktopDatabase.open(null)), cache)
        services.web = { request ->
            if (request.url.host != host) {
                null
            } else {
                val body = when (val b = request.body) {
                    is OutgoingContent.ByteArrayContent -> HttpRequest.BodyPublishers.ofByteArray(b.bytes())
                    is OutgoingContent.NoContent -> HttpRequest.BodyPublishers.noBody()
                    is OutgoingContent.WriteChannelContent -> {
                        val ch = io.ktor.utils.io.ByteChannel()
                        val bytes = kotlinx.coroutines.coroutineScope {
                            val writer = async { b.writeTo(ch); ch.flushAndClose() }
                            ch.toByteArray().also { writer.await() }
                        }
                        HttpRequest.BodyPublishers.ofByteArray(bytes)
                    }
                    else -> HttpRequest.BodyPublishers.noBody()
                }
                val builder = HttpRequest.newBuilder(URI(request.url.toString())).method(request.method.value, body)
                request.headers.forEach { name, values -> if (name !in RESTRICTED) values.forEach { builder.header(name, it) } }
                request.body.contentType?.let { builder.header("Content-Type", it.toString()) }
                val resp = real.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                respond(
                    resp.body(), HttpStatusCode.fromValue(resp.statusCode()),
                    headersOf(*resp.headers().map().filterKeys { it.lowercase() != "content-encoding" && it.lowercase() != "transfer-encoding" }.map { (k, v) -> k to v }.toTypedArray()),
                )
            }
        }
        return services
    }

    @Test
    fun aRealRommServer(): Unit = runBlocking {
        val services = services()
        val store = createFuseStore(services, scope)
        // Downloads go into the first library.
        store.sources.add(File(root, "Games").apply { mkdirs() }.absolutePath, io.github.matiyaaa.fuse.model.LibrarySourceKind.ROMS_ROOT)
        val romm = store.romm
        romm.setEnabled(true)
        romm.setAddresses("", url!!, RouteMode.REMOTE)
        val account = romm.usePassword(user!!, password!!).getOrThrow()
        println("Signed in as $account")
        romm.refresh(full = true)
        val state = withTimeout(180_000) { romm.state.first { it.link == RommLink.ONLINE && it.syncing == null && it.games > 0 } }
        println("RomM ${state.version}: ${state.games} games, upload ${state.canUpload}, scan ${state.canScan}")

        // Systems: every one with games should be one Fuse knows.
        val systems = withTimeout(60_000) { romm.systems.first { it.isNotEmpty() } }
        val withGames = systems.filter { it.games > 0 }
        val unknown = withGames.filter { it.platform == null }
        println("Systems with games: ${withGames.size}; Fuse knows ${withGames.size - unknown.size}")
        for (s in withGames.sortedBy { it.slug }) println("  ${s.slug} (${s.name}): ${s.games} games -> ${s.platform?.value ?: "NOT RECOGNISED"}")

        // Games and their art.
        val games = withTimeout(60_000) { romm.games(null).first { it.isNotEmpty() } }
        val noArt = games.filter { it.card.art.boxart == null && it.card.art.square == null }
        println("Games: ${games.size}; without art ${noArt.size}")
        // The art each game shows is a real picture the server gives.
        val auth = "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val arts = games.mapNotNull { g -> (g.card.art.boxart as? io.github.matiyaaa.fuse.ui.shell.store.impl.RommArt)?.let { g.card.title to it } }
        var good = 0
        for ((title, art) in arts.take(40)) {
            // As Fuse fetches it: the server's address and the path RomM gave, signed in.
            val resp = runCatching { services.http.get((url + (if (art.path.startsWith("/")) art.path else "/" + art.path)).replace(" ", "%20")) { headers.append("Authorization", auth) } }
            val code = resp.getOrNull()?.status?.value
            val bytes = resp.getOrNull()?.let { r -> r.bodyAsBytes() }
            val picture = bytes != null && bytes.size > 12 && (bytes[0] == 0x89.toByte() || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || String(bytes.copyOfRange(8, 12)) == "WEBP")
            if (code == 200 && picture) good++ else println("  art for $title failed: $code (${resp.exceptionOrNull()?.message})")
        }
        println("  art fetched: $good of ${minOf(40, arts.size)} sampled")
        noArt.take(10).forEach { println("  no art: ${it.card.title} (${it.card.platformId.value})") }

        // Bring the smallest game on a handheld down into the library.
        val pick = games.filter { it.card.platformId.value in setOf("gb", "gbc", "gba", "nes", "snes") && it.sizeBytes in 1..64_000_000 }.minByOrNull { it.sizeBytes }
        assertTrue(pick != null, "a small game to download")
        println("Downloading ${pick!!.card.title} (${pick.card.platformId.value}, ${pick.sizeBytes} bytes)")
        romm.download(pick.romId)?.let { println("  download said: $it") }
        // What the transfer does, as it goes.
        val watch = scope.launch {
            var last = ""
            store.transfers.rows.collect { rows ->
                val now = rows.joinToString("; ") { r -> "${r.item.title}: ${r.item.status} ${r.item.phase ?: ""} ${r.item.waiting ?: ""} ${r.item.error ?: ""} ${r.item.doneBytes}/${r.item.totalBytes}" }
                if (now != last) { println("  transfer: $now"); last = now }
            }
        }
        val inLibrary = withTimeout(300_000) { romm.libraryGame(pick.romId).first { it != null } }!!
        val detail = withTimeout(60_000) { store.library.game(inLibrary).first { it != null } }!!
        watch.cancel()
        println("  in the library: ${detail.game.titles.display} at ${detail.game.location.launchPath}, RomM id ${detail.game.links.rommRomId}")
        assertTrue(File(detail.game.location.launchPath).let { it.exists() && it.length() > 0 }, "the file is on disk")
        val withArt = withTimeout(60_000) { store.library.game(inLibrary).first { it?.media?.boxart != null } }!!
        println("  its cover: ${withArt.media.boxart?.source} ${File(withArt.media.boxart?.model.toString()).let { if (it.isFile) "${it.length()} bytes" else it.path }}")
        assertTrue(store.transfers.rows.value.none { it.item.status == io.github.matiyaaa.fuse.transfer.TransferStatus.FAILED }, "the download didn't fail")
        assertTrue(detail.game.links.rommRomId == pick.romId)
    }

    private companion object {
        val RESTRICTED = setOf("Content-Length", "Host", "Connection", "Expect", "Upgrade", "Accept-Charset")
    }
}
