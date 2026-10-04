package io.github.matiyaaa.fuse.jellyfin

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** This device, as Jellyfin's dashboard lists it. [id] stays the same across runs. */
data class DeviceInfo(val name: String, val id: String, val version: String)

/** A signed-in user on a server. The token is kept in the secret store; the password never is. */
data class Account(val serverId: String?, val serverName: String?, val userId: String, val userName: String?, val token: String)

/** A server's public face: its name, version and id. */
data class ServerInfo(val name: String?, val version: String?, val id: String?, val localAddress: String?)

/** Why a request failed, in a way the screens can explain. */
class JellyfinException(message: String, val kind: Kind, val status: Int? = null) : Exception(message) {
    enum class Kind { NETWORK, AUTH, NOT_FOUND, SERVER, NOT_JELLYFIN }
}

/**
 * Fuse's own Jellyfin client: plain REST over the app's Ktor client, only what Fuse needs. Every
 * call names the address it goes to, so the connection can switch routes between calls. Nothing
 * here logs, and URLs never carry the token: it travels in the Authorization header.
 */
class JellyfinClient(private val http: HttpClient, val device: DeviceInfo) {
    /** Answers kept for coming back quickly and for working offline; set by the service. */
    var cache: ResponseCache? = null

    /** Told whenever the server answered (true) or couldn't be reached and a kept answer was used (false). */
    var onReachable: ((Boolean) -> Unit)? = null

    /** How long a kept answer is used without asking again, for browsing calls. */
    var freshForMs: Long = 0

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Jellyfin's header: who is asking, from which device, and (signed in) the token. */
    fun authorization(token: String?): String {
        fun q(s: String) = s.replace("\"", "'").replace(",", " ")
        val base = "MediaBrowser Client=\"Fuse\", Device=\"${q(device.name)}\", DeviceId=\"${q(device.id)}\", Version=\"${q(device.version)}\""
        return if (token != null) "$base, Token=\"$token\"" else base
    }

    // Public ---------------------------------------------------------------------------------------

    /** The server's name and version, or an error saying why it can't be reached. */
    suspend fun publicInfo(base: String, timeoutMs: Long = 5_000): ServerInfo {
        val text = call(base, "System/Info/Public", token = null, timeoutMs = timeoutMs) { }
        val dto = runCatching { json.decodeFromString(PublicInfoDto.serializer(), text) }.getOrNull()
            ?: throw JellyfinException("That address answers, but not as a Jellyfin server.", JellyfinException.Kind.NOT_JELLYFIN)
        if (dto.id == null && dto.version == null) throw JellyfinException("That address answers, but not as a Jellyfin server.", JellyfinException.Kind.NOT_JELLYFIN)
        return ServerInfo(dto.serverName, dto.version, dto.id, dto.localAddress)
    }

    /** Signs in with a username and password; the password is sent once and never kept. */
    suspend fun signIn(base: String, username: String, password: String): Account {
        val body = json.encodeToString(AuthRequestDto.serializer(), AuthRequestDto(username, password))
        val text = call(base, "Users/AuthenticateByName", token = null, method = Method.POST, body = body) { }
        val r = json.decodeFromString(AuthResultDto.serializer(), text)
        val user = r.user ?: throw JellyfinException("The server didn't sign you in.", JellyfinException.Kind.AUTH)
        val token = r.accessToken ?: throw JellyfinException("The server didn't sign you in.", JellyfinException.Kind.AUTH)
        return Account(r.serverId, null, user.id, user.name, token)
    }

    suspend fun signOut(base: String, account: Account) {
        runCatching { call(base, "Sessions/Logout", account.token, method = Method.POST) { } }
    }

    // Browsing ---------------------------------------------------------------------------------------

    suspend fun views(base: String, a: Account): List<MediaItem> =
        items(base, a, "UserViews", mapOf("userId" to a.userId)).items

    suspend fun resume(base: String, a: Account, limit: Int = 24, mediaTypes: String = "Video"): List<MediaItem> =
        items(base, a, "UserItems/Resume", mapOf("userId" to a.userId, "limit" to "$limit", "mediaTypes" to mediaTypes, "fields" to LIST_FIELDS, "enableImageTypes" to IMAGE_TYPES)).items

    suspend fun nextUp(base: String, a: Account, limit: Int = 24, seriesId: String? = null): List<MediaItem> =
        items(base, a, "Shows/NextUp", buildMap {
            put("userId", a.userId)
            put("limit", "$limit")
            put("fields", LIST_FIELDS)
            put("enableResumable", "false")
            seriesId?.let { put("seriesId", it) }
        }).items

    /** The newest items, in one library or across all. */
    suspend fun latest(base: String, a: Account, parentId: String? = null, limit: Int = 24, types: String? = null): List<MediaItem> {
        val text = call(base, "Items/Latest", a.token) {
            param("userId", a.userId)
            param("limit", "$limit")
            param("fields", LIST_FIELDS)
            param("groupItems", "true")
            parentId?.let { param("parentId", it) }
            types?.let { param("includeItemTypes", it) }
        }
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ItemDto.serializer()), text).map { it.toMedia() }
    }

    /** A page of items: a library's, a search's, a filter's. */
    suspend fun query(
        base: String,
        a: Account,
        parentId: String? = null,
        types: String? = null,
        sort: MediaSort = MediaSort.NAME,
        filter: MediaFilter = MediaFilter.ALL,
        search: String? = null,
        start: Int = 0,
        limit: Int = 60,
        recursive: Boolean = true,
        extra: Map<String, String> = emptyMap(),
    ): MediaPage = items(base, a, "Items", buildMap {
        put("userId", a.userId)
        parentId?.let { put("parentId", it) }
        types?.let { put("includeItemTypes", it) }
        put("sortBy", sort.jellyfin)
        put("sortOrder", if (sort.descending) "Descending" else "Ascending")
        filter.jellyfin?.let { put("filters", it) }
        search?.takeIf { it.isNotBlank() }?.let { put("searchTerm", it) }
        put("startIndex", "$start")
        put("limit", "$limit")
        put("recursive", "$recursive")
        put("fields", LIST_FIELDS)
        put("enableImageTypes", IMAGE_TYPES)
        putAll(extra)
    })

    suspend fun item(base: String, a: Account, id: String): MediaItem {
        val text = call(base, "Items/$id", a.token) {
            param("userId", a.userId)
            param("fields", DETAIL_FIELDS)
        }
        return json.decodeFromString(ItemDto.serializer(), text).toMedia()
    }

    suspend fun seasons(base: String, a: Account, seriesId: String): List<MediaItem> =
        items(base, a, "Shows/$seriesId/Seasons", mapOf("userId" to a.userId, "fields" to LIST_FIELDS)).items

    suspend fun episodes(base: String, a: Account, seriesId: String, seasonId: String? = null): List<MediaItem> =
        items(base, a, "Shows/$seriesId/Episodes", buildMap {
            put("userId", a.userId)
            put("fields", "$LIST_FIELDS,Overview")
            seasonId?.let { put("seasonId", it) }
        }).items

    /** An item's neighbours in its show, for previous and next. */
    suspend fun adjacentEpisodes(base: String, a: Account, seriesId: String, itemId: String): Pair<MediaItem?, MediaItem?> {
        val all = episodes(base, a, seriesId)
        val i = all.indexOfFirst { it.id == itemId }
        if (i < 0) return null to null
        return all.getOrNull(i - 1) to all.getOrNull(i + 1)
    }

    suspend fun artists(base: String, a: Account, parentId: String?, start: Int = 0, limit: Int = 60): MediaPage =
        items(base, a, "Artists/AlbumArtists", buildMap {
            put("userId", a.userId)
            parentId?.let { put("parentId", it) }
            put("startIndex", "$start")
            put("limit", "$limit")
            put("sortBy", "SortName")
            put("fields", LIST_FIELDS)
        })

    suspend fun albumTracks(base: String, a: Account, albumId: String): List<MediaItem> =
        items(base, a, "Items", mapOf("userId" to a.userId, "parentId" to albumId, "sortBy" to "ParentIndexNumber,IndexNumber,SortName", "fields" to "$LIST_FIELDS,MediaSources")).items

    // What you have watched ---------------------------------------------------------------------------

    suspend fun setFavorite(base: String, a: Account, id: String, favorite: Boolean) {
        call(base, "UserFavoriteItems/$id", a.token, method = if (favorite) Method.POST else Method.DELETE) { param("userId", a.userId) }
    }

    suspend fun setPlayed(base: String, a: Account, id: String, played: Boolean) {
        call(base, "UserPlayedItems/$id", a.token, method = if (played) Method.POST else Method.DELETE) { param("userId", a.userId) }
    }

    // Playback -------------------------------------------------------------------------------------------

    internal suspend fun playbackInfo(base: String, a: Account, id: String, request: PlaybackInfoRequestDto): PlaybackInfoDto {
        val body = json.encodeToString(PlaybackInfoRequestDto.serializer(), request)
        val text = call(base, "Items/$id/PlaybackInfo", a.token, method = Method.POST, body = body) { param("userId", a.userId) }
        return json.decodeFromString(PlaybackInfoDto.serializer(), text)
    }

    internal suspend fun report(base: String, a: Account, path: String, body: PlaybackReportDto) {
        call(base, path, a.token, method = Method.POST, body = json.encodeToString(PlaybackReportDto.serializer(), body)) { }
    }

    /** A text file from the server (a subtitle), with the token in the header. */
    suspend fun text(url: String, a: Account): String = call(url, "", a.token) { }

    /** A picture's address on [base]. Pictures need no sign-in, so no token rides along. */
    fun imageUrl(base: String, art: JellyfinArt): String {
        val b = URLBuilder(base.trimEnd('/'))
        b.appendPathSegments("Items", art.itemId, "Images", art.kind.jellyfin)
        if (art.kind == ArtKind.BACKDROP) b.appendPathSegments(art.index.toString())
        b.parameters.append("tag", art.tag)
        if (art.width > 0) b.parameters.append("fillWidth", art.width.toString())
        b.parameters.append("quality", "90")
        return b.buildString()
    }

    // Plumbing ---------------------------------------------------------------------------------------

    internal enum class Method { GET, POST, DELETE }

    private suspend fun items(base: String, a: Account, path: String, params: Map<String, String>): MediaPage {
        val text = call(base, path, a.token) { params.forEach { (k, v) -> param(k, v) } }
        val dto = json.decodeFromString(ItemsResultDto.serializer(), text)
        return MediaPage(dto.items.map { it.toMedia() }, dto.totalRecordCount.coerceAtLeast(dto.items.size), dto.startIndex)
    }

    internal class Params(val builder: URLBuilder) {
        fun param(name: String, value: String) = builder.parameters.append(name, value)
    }

    private suspend fun call(
        base: String,
        path: String,
        token: String?,
        method: Method = Method.GET,
        body: String? = null,
        timeoutMs: Long = 20_000,
        params: Params.() -> Unit,
    ): String {
        val builder = URLBuilder(base.trimEnd('/')).apply { if (path.isNotEmpty()) appendPathSegments(path.split('/')) }
        Params(builder).params()
        val url = builder.buildString()
        // Kept answers are keyed by who asked and what, never by the address.
        val key = if (method == Method.GET && token != null) {
            token.takeLast(8) + "|" + path + "?" + builder.parameters.entries().sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value.joinToString(",")}" }
        } else {
            null
        }
        val kept = cache
        if (key != null && kept != null && freshForMs > 0) kept.fresh(key, freshForMs)?.let { return it }
        val block: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, authorization(token))
            header(HttpHeaders.Accept, "application/json")
            timeout { requestTimeoutMillis = timeoutMs; connectTimeoutMillis = minOf(timeoutMs, 8_000) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val response: HttpResponse = try {
            when (method) {
                Method.GET -> http.get(url, block)
                Method.POST -> http.post(url, block)
                Method.DELETE -> http.delete(url, block)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (key != null && kept != null) {
                kept.any(key)?.let {
                    onReachable?.invoke(false)
                    return it
                }
            }
            throw JellyfinException(networkText(t), JellyfinException.Kind.NETWORK)
        }
        onReachable?.invoke(true)
        val status = response.status.value
        if (!response.status.isSuccess()) {
            throw when (status) {
                401, 403 -> JellyfinException(if (token == null) "The username or password isn't right." else "The server signed this device out. Sign in again.", JellyfinException.Kind.AUTH, status)
                404 -> JellyfinException("The server doesn't have that.", JellyfinException.Kind.NOT_FOUND, status)
                else -> JellyfinException("The server had a problem ($status).", JellyfinException.Kind.SERVER, status)
            }
        }
        val text = response.bodyAsText()
        if (key != null && kept != null) kept.put(key, text)
        return text
    }

    private fun networkText(t: Throwable): String {
        val m = (t.message ?: t::class.simpleName ?: "").lowercase()
        return when {
            "timeout" in m || "timed out" in m -> "The server took too long to answer."
            "certificate" in m || "ssl" in m || "tls" in m || "handshake" in m -> "The server's certificate isn't trusted. Try the address with http:// on your own network."
            "unknownhost" in m || "unresolved" in m || "nodename" in m || "no address" in m -> "That address can't be found."
            "refused" in m -> "Nothing answers at that address. Check the port."
            else -> "The server can't be reached."
        }
    }

    internal fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    companion object {
        internal const val LIST_FIELDS = "PrimaryImageAspectRatio,Overview,ParentId,ChildCount,RecursiveItemCount,Genres,ProductionYear"
        internal const val DETAIL_FIELDS = "Overview,Genres,Studios,People,Taglines,MediaSources,MediaStreams,Chapters,PrimaryImageAspectRatio,ParentId,ChildCount,RecursiveItemCount,ProductionLocations"
        internal const val IMAGE_TYPES = "Primary,Backdrop,Logo,Thumb"

        /**
         * What a typed address means: a scheme added where missing (https first; a local name or
         * address gets http and the usual port), the trailing slash dropped, a base path kept.
         */
        fun candidates(input: String): List<String> {
            val t = input.trim().trimEnd('/')
            if (t.isEmpty()) return emptyList()
            if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return listOf(t)
            val host = t.substringBefore('/').substringBefore(':')
            val hasPort = t.substringBefore('/').contains(':')
            val local = host.endsWith(".local") || host.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) || !host.contains('.')
            val withPort = if (hasPort || !local) t else t.replaceFirst(host, "$host:8096")
            return if (local) listOf("http://$withPort", "https://$t") else listOf("https://$t", "http://$t")
        }
    }
}
