package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

/**
 * Artwork from the providers (SteamGridDB, TheGamesDB, IGDB, ScreenScraper, libretro's thumbnails,
 * RetroAchievements, Steam), fetched once for the whole household: with Fuse One connected, a
 * picture this device hasn't kept is asked of the host first, and one it fetched from a provider
 * itself is given to the host for the other devices. Pictures are kept in Coil's disk cache as any
 * other, and with no host (or none answering) they come from the provider as before.
 */
object HouseholdImages {
    /** Fuse One while it runs here; set by the store. */
    @kotlin.concurrent.Volatile var sync: io.github.matiyaaa.fuse.sync.SyncService? = null

    private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    /** The providers' picture hosts, by the end of their name. */
    private val PROVIDERS = listOf(
        "steamgriddb.com", "thegamesdb.net", "igdb.com", "screenscraper.fr", "libretro.com",
        "retroachievements.org", "steamstatic.com", "akamaihd.net", "githubusercontent.com",
    )

    /** Whether [url] is a provider's picture the household shares. */
    fun shared(url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return PROVIDERS.any { host == it || host.endsWith(".$it") }
    }

    /** The household's key for [url]: its SHA-256, so the host never learns the address itself. */
    fun key(url: String): String = url.encodeUtf8().sha256().hex()

    class Fetch(private val http: HttpClient) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val sync = sync ?: return null
            val url = data.toString()
            if (!shared(url)) return null
            return Fetcher { fetch(url, sync, options, imageLoader) }
        }

        private suspend fun fetch(url: String, sync: io.github.matiyaaa.fuse.sync.SyncService, options: Options, imageLoader: ImageLoader): FetchResult {
            val disk = imageLoader.diskCache
            val cacheKey = options.diskCacheKey ?: url
            disk?.openSnapshot(cacheKey)?.let { snap ->
                return SourceFetchResult(ImageSource(snap.data, disk.fileSystem, cacheKey, snap), null, DataSource.DISK)
            }
            val key = key(url)
            val fromHost = sync.householdArt(key)
            val bytes = fromHost ?: run {
                val resp = http.get(url)
                check(resp.status.isSuccess()) { "The picture couldn't be fetched (${resp.status.value})" }
                resp.readRawBytes()
            }
            if (fromHost == null) sharing.launch { sync.shareArt(key, bytes) }
            disk?.openEditor(cacheKey)?.let { editor ->
                try {
                    disk.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                } catch (e: Exception) {
                    editor.abort()
                }
            }
            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.NETWORK)
        }
    }
}
