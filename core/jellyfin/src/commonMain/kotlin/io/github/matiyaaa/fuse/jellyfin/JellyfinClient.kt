package io.github.matiyaaa.fuse.jellyfin

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
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

    /**
     * Set by the service: runs [fetch] behind a kept answer that was shown at once, on whichever
     * address is in use by then. Null means kept answers are only used fresh or offline.
     */
    var refreshBehind: ((key: String, fetch: suspend (base: String) -> Unit) -> Unit)? = null

    /** Told when an answer fetched behind a shown one turned out different, so pages can ask again. */
    var onRefreshed: (() -> Unit)? = null

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

    suspend fun artists(base: String, a: Account, parentId: String?, start: Int = 0, limit: Int = 60, search: String? = null): MediaPage =
        items(base, a, if (search == null) "Artists/AlbumArtists" else "Artists", buildMap {
            put("userId", a.userId)
            parentId?.let { put("parentId", it) }
            search?.let { put("searchTerm", it) }
            put("startIndex", "$start")
            put("limit", "$limit")
            put("sortBy", "SortName")
            put("fields", LIST_FIELDS)
        })

    /** People (cast and crew) whose name matches [search]. */
    suspend fun persons(base: String, a: Account, search: String, limit: Int = 30): List<MediaItem> =
        items(base, a, "Persons", mapOf("userId" to a.userId, "searchTerm" to search, "limit" to "$limit", "fields" to LIST_FIELDS)).items

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

    /** An item's file on the server as it is (size, container, streams), for keeping it offline. */
    internal suspend fun mediaSource(base: String, a: Account, id: String): MediaSourceDto? {
        val text = call(base, "Items/$id/PlaybackInfo", a.token) { param("userId", a.userId) }
        return json.decodeFromString(PlaybackInfoDto.serializer(), text).mediaSources.firstOrNull()
    }

    /** Whether [a]'s policy lets them download (EnableContentDownloading); true when the server doesn't say. */
    suspend fun downloadAllowed(base: String, a: Account): Boolean {
        val text = call(base, "Users/${a.userId}", a.token) { }
        val policy = runCatching { json.parseToJsonElement(text).jsonObject["Policy"]?.jsonObject }.getOrNull() ?: return true
        return policy["EnableContentDownloading"]?.jsonPrimitive?.booleanOrNull ?: true
    }

    /** The original file of [id] (Jellyfin's download, which needs the account's download permission). */
    fun downloadUrl(base: String, id: String): String =
        URLBuilder(base.trimEnd('/')).apply { appendPathSegments("Items", id, "Download") }.buildString()

    /** An external subtitle of [id] as a text file. */
    fun subtitleUrl(base: String, id: String, mediaSourceId: String, index: Int, ext: String): String =
        URLBuilder(base.trimEnd('/')).apply { appendPathSegments("Videos", id, mediaSourceId, "Subtitles", index.toString(), "0", "Stream.$ext") }.buildString()

    /**
     * Tells the server where [id] was left, after watching it offline: a stop report carries the
     * position, which Jellyfin keeps as the resume point (or marks it watched near the end).
     */
    suspend fun reportPosition(base: String, a: Account, id: String, positionMs: Long) {
        report(base, a, "Sessions/Playing/Stopped", PlaybackReportDto(itemId = id, positionTicks = positionMs * 10_000, playMethod = "DirectPlay"))
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
        fun url(on: String): URLBuilder = URLBuilder(on.trimEnd('/')).apply {
            if (path.isNotEmpty()) appendPathSegments(path.split('/'))
            Params(this).params()
        }
        val builder = url(base)
        // Kept answers are keyed by who asked and what, never by the address.
        val key = if (method == Method.GET && token != null) {
            token.takeLast(8) + "|" + path + "?" + builder.parameters.entries().sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value.joinToString(",")}" }
        } else {
            null
        }
        val kept = cache
        if (key != null && kept != null && freshForMs > 0) {
            kept.fresh(key, freshForMs)?.let { return it }
            // An older answer that is still right shows at once; the fresh one follows behind it.
            val behind = refreshBehind
            if (behind != null) {
                kept.current(key)?.let { shown ->
                    behind(key) { on ->
                        val text = fetch(url(on).buildString(), token, method, body, timeoutMs)
                        if (kept.put(key, text) && text != shown) onRefreshed?.invoke()
                    }
                    return shown
                }
            }
        }
        val text = try {
            fetch(builder.buildString(), token, method, body, timeoutMs)
        } catch (e: JellyfinException) {
            if (e.kind == JellyfinException.Kind.NETWORK && key != null && kept != null) {
                kept.any(key)?.let {
                    onReachable?.invoke(false)
                    return it
                }
            }
            throw e
        }
        if (key != null && kept != null) kept.put(key, text)
        return text
    }

    /** One request to the server: its answer, or why not. */
    private suspend fun fetch(url: String, token: String?, method: Method, body: String?, timeoutMs: Long): String {
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
        return response.bodyAsText()
    }

    private fun networkText(t: Throwable): String {
        val m = (t.message ?: t::class.simpleName ?: "").lowercase()
        return when {
            "timeout" in m || "timed out" in m -> "The server took too long to answer."
            "certificate" in m || "ssl" in m || "tls" in m || "handshake" in m -> "The server's certificate isn't trusted. Try the address with http:// on your own network."
            "unknownhost" in m || "unresolved" in m || "nodename" in m || "no address" in m -> "That address can't be found."
            "refused" in m -> "Nothing answers at that address. Check the port."
            "cleartext" in m -> "This device wouldn't connect without encryption. Update Fuse, or use the https address."
            "unreachable" in m || "no route" in m -> "That address isn't on this network."
            else -> "The server can't be reached."
        }
    }

    internal fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    companion object {
        internal const val LIST_FIELDS = "PrimaryImageAspectRatio,Overview,ParentId,ChildCount,RecursiveItemCount,Genres,ProductionYear"
        internal const val DETAIL_FIELDS = "Overview,Genres,Studios,People,Taglines,MediaSources,MediaStreams,Chapters,PrimaryImageAspectRatio,ParentId,ChildCount,RecursiveItemCount,ProductionLocations"
        internal const val IMAGE_TYPES = "Primary,Backdrop,Logo,Thumb"

        /**
         * Every way a typed address could mean a server, most likely first. People type addresses
         * every which way, so this forgives: spaces, a phone's comma for a dot, full-width
         * characters, a missing or half-typed scheme (`http//`, `http:/`), the browser's own
         * `/web/index.html#...` tail, a trailing slash. A base path is kept. A name or address on
         * the home network is tried over http first, on Jellyfin's usual port (8096) when none is
         * given, then https (8920); anything else is tried over https first.
         */
        fun candidates(input: String): List<String> {
            var t = buildString {
                for (c in input.trim()) append(if (c in '\uFF01'..'\uFF5E') (c - 0xFEE0) else c)
            }.replace('\u3002', '.').replace('\\', '/').filterNot { it.isWhitespace() }
            if (t.isEmpty()) return emptyList()
            // The scheme, however it was typed.
            val scheme = Regex("""^(https?)[:/]+""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
                t = t.substring(m.range.last + 1)
                m.groupValues[1].lowercase()
            }
            // What a browser shows after the server: its web app, a page in it, a query.
            t = t.substringBefore('#').substringBefore('?')
            t = t.replace(Regex("""/web(/.*)?$""", RegexOption.IGNORE_CASE), "").trimEnd('/')
            val authority = t.substringBefore('/')
            val path = t.removePrefix(authority).trimEnd('/')
            // "192,168,1,5" from a phone keyboard, and "192.168.1.5." with a stray dot.
            val hostPort = if (Regex("""^[\d,.]+(:\d+)?$""").matches(authority)) authority.replace(',', '.') else authority
            val bracketed = hostPort.startsWith("[")
            val host = (if (bracketed) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')).removeSuffix(".")
            if (host.isEmpty() || host == "[]") return emptyList()
            val port = (if (bracketed) hostPort.substringAfter("]", "").removePrefix(":") else hostPort.substringAfter(':', ""))
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            val home = isHomeHost(host)
            val out = LinkedHashSet<String>()
            fun add(s: String, p: String?) = out.add("$s://$host${p?.let { ":$it" } ?: ""}$path")
            when {
                port != null -> {
                    val first = scheme ?: if (port == "8920" || port == "443") "https" else if (home || port == "8096" || port == "80") "http" else "https"
                    add(first, port)
                    if (scheme == null) add(if (first == "https") "http" else "https", port)
                }
                scheme != null -> {
                    add(scheme, null)
                    if (home) add(scheme, if (scheme == "http") "8096" else "8920")
                }
                home -> {
                    add("http", "8096")
                    add("https", "8920")
                    add("http", null)
                    add("https", null)
                }
                else -> {
                    add("https", null)
                    add("http", null)
                    add("http", "8096")
                }
            }
            return out.toList()
        }

        /** A name or address only the home network knows: private ranges, `.local`, a bare name. */
        internal fun isHomeHost(host: String): Boolean {
            val h = host.lowercase().removeSurrounding("[", "]")
            val ip = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
            if (ip != null) {
                val (a, b) = ip
                return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
            }
            if (':' in h) return h.startsWith("fe80") || h.startsWith("fd") || h.startsWith("fc") || h == "::1"
            return '.' !in h || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal") || h.endsWith(".home.arpa") || h == "localhost"
        }
    }
}
