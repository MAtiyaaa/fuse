package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.ScrapeCandidate
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.shell.store.ArtworkResult
import io.github.matiyaaa.fuse.ui.shell.store.FillProgress
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.IdentifyResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlin.jvm.Synchronized
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val LinkJson = Json { explicitNulls = true; encodeDefaults = true }

/**
 * The API behind Phone Link (docs/PHONE_LINK.md), as JSON. Only what a phone may do: look, fix a
 * game's name, details and art, and start art fills. Options and matches a phone picks must be ones
 * this device offered it, so a phone can't make Fuse fetch an address of its own choosing.
 */
internal class LinkApi(
    private val store: FuseStore,
    private val auth: LinkAuth,
    private val deviceName: String,
    private val version: String,
    private val readUri: (suspend (String) -> ByteArray?)?,
) {
    data class Reply(val body: JsonElement, val status: HttpStatusCode = HttpStatusCode.OK)

    sealed interface Login {
        data class Ok(val token: String) : Login
        data class Failed(val body: JsonObject, val status: HttpStatusCode, val retryAfterSeconds: Int? = null) : Login
    }

    private val images = ImageTokens()
    private val offeredMatches = BoundedMap<String, ScrapeCandidate>(400)
    private val offeredArt = BoundedMap<String, ArtworkOption>(2_000)

    // Session ------------------------------------------------------------------------------------

    suspend fun session(token: String?) = buildJsonObject {
        put("signedIn", auth.isSignedIn(token))
        put("device", deviceName)
        put("version", version)
    }

    suspend fun login(body: JsonElement?): Login {
        val o = body as? JsonObject
        val username = o?.string("username").orEmpty()
        val password = o?.string("password").orEmpty()
        if (username.isBlank() || password.isEmpty() || username.length > 200 || password.length > 500) {
            return Login.Failed(error("Enter the username and password set on the device."), HttpStatusCode.BadRequest)
        }
        return when (val r = auth.login(username, password)) {
            is LoginResult.Ok -> Login.Ok(r.token)
            LoginResult.Wrong -> Login.Failed(error("The username and password don't match the ones set on the device."), HttpStatusCode.Unauthorized)
            LoginResult.NoAccount -> Login.Failed(error("Phone Link has no account yet. Set one on the device in Settings, Phone Link."), HttpStatusCode.Unauthorized)
            is LoginResult.Locked -> Login.Failed(
                buildJsonObject {
                    put("error", "Too many tries. Wait a minute, then try again.")
                    put("retryAfterSeconds", r.retryAfterSeconds)
                },
                HttpStatusCode.TooManyRequests,
                r.retryAfterSeconds,
            )
        }
    }

    // Now ----------------------------------------------------------------------------------------

    fun now(): JsonObject = nowOf(store.media.fillProgress.value)

    private fun nowOf(fill: FillProgress?): JsonObject {
        val home = store.library.home.value
        val prefs = store.prefs.value
        val status = store.cartridge.status.value
        val recent = store.cartridge.recent.value
        return buildJsonObject {
            put("playing", home.playtime.currentGame?.let { summary(it) } ?: JsonNull)
            put("playingSince", home.playtime.currentSince)
            put("cartridge", buildJsonObject {
                put("enabled", prefs.cartridgeEnabled)
                if (!prefs.cartridgeEnabled) return@buildJsonObject
                put("installed", status.installed)
                put("connected", status.connected)
                val downloading = status.queue.firstOrNull { it.state == QueueState.DOWNLOADING }
                val currentTitle = downloading?.title ?: status.currentTitle
                put("current", if (currentTitle == null) JsonNull else buildJsonObject {
                    put("title", currentTitle)
                    put("platform", downloading?.platformSlug ?: status.currentPlatform)
                    put("progress", downloading?.progress ?: status.progress)
                })
                putJsonArray("queue") {
                    status.queue.forEach { q ->
                        add(buildJsonObject {
                            put("romId", q.romId)
                            put("title", q.title)
                            put("platform", q.platformSlug)
                            put("state", q.state.name.lowercase())
                            put("received", q.received)
                            put("total", q.total)
                        })
                    }
                }
                put("queued", status.queue.count { it.state == QueueState.QUEUED }.takeIf { status.queue.isNotEmpty() } ?: status.queuedDownloads)
                putJsonArray("recent") {
                    recent.take(12).forEach { r ->
                        add(buildJsonObject {
                            put("romId", r.download.romId)
                            put("title", r.game?.title ?: r.download.title)
                            put("platform", r.download.platformSlug)
                            put("finishedAt", r.download.finishedAt)
                            put("gameId", r.game?.id?.value)
                        })
                    }
                }
            })
            put("fill", fill?.let { fillJson(it) } ?: JsonNull)
        }
    }

    /** "now" again whenever something in it changes, at most twice a second. */
    @OptIn(FlowPreview::class)
    fun nowUpdates(): Flow<JsonObject> = combine(
        store.library.home.map { it.playtime.currentGame?.id to it.playtime.currentSince },
        store.cartridge.status,
        store.cartridge.recent,
        store.prefs.map { it.cartridgeEnabled },
        store.media.fillProgress,
    ) { _, _, _, _, fill -> nowOf(fill) }
        .distinctUntilChanged()
        .sample(500)

    fun fillUpdates(): Flow<JsonObject?> = store.media.fillProgress.map { it?.let(::fillJson) }.distinctUntilChanged().drop(1)

    /** Something about the games changed (new ones, names, art): lists should load again. */
    @OptIn(FlowPreview::class)
    fun libraryUpdates(): Flow<Unit> = store.library.games(GameQuery())
        .map { list -> list.size to list.sumOf { (it.title.hashCode() + (it.art.boxart?.hashCode() ?: 0)).toLong() } }
        .distinctUntilChanged()
        .drop(1)
        .debounce(1_000)
        .map { }

    private fun fillJson(f: FillProgress) = buildJsonObject {
        put("done", f.done)
        put("total", f.total)
        put("current", f.current)
        put("added", f.added)
        put("details", f.details)
        put("finished", f.finished)
        put("cancelled", f.cancelled)
        putJsonArray("needsYou") {
            f.needsYou.forEach { add(buildJsonObject { put("id", it.game.value); put("title", it.title) }) }
        }
    }

    // Library ------------------------------------------------------------------------------------

    fun systems(): JsonArray = buildJsonArray {
        store.library.platforms.value.filter { it.gameCount > 0 }.forEach { p ->
            add(buildJsonObject {
                put("id", p.platform.id.value)
                put("name", p.platform.name)
                put("shortName", p.platform.shortName)
                put("gameCount", p.gameCount)
                put("accent", hex(p.platform.accent))
                put("logo", url(p.art.logo))
                put("art", url(p.art.boxart ?: p.art.hero))
            })
        }
    }

    suspend fun games(query: String, system: String, sort: String, offset: Int, limit: Int): JsonObject {
        val order = when (sort) {
            "recent" -> SortOrder.RECENTLY_PLAYED
            "added" -> SortOrder.RECENTLY_ADDED
            else -> SortOrder.TITLE
        }
        val platform = system.takeIf { it.isNotBlank() }?.let(::PlatformId)
        val all = store.library.games(GameQuery(platform = platform, sort = order)).first()
        val q = query.trim().lowercase()
        val matching = if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) }
        val from = offset.coerceIn(0, matching.size)
        val page = matching.drop(from).take(limit.coerceIn(1, 600))
        return buildJsonObject {
            put("total", matching.size)
            putJsonArray("items") { page.forEach { add(summary(it)) } }
        }
    }

    suspend fun game(id: Long): Reply {
        val gameId = GameId(id)
        val d = store.library.game(gameId).first() ?: return notFound()
        val g = d.game
        val searchAs = store.media.searchTitle(gameId)
        val media = d.media
        return Reply(buildJsonObject {
            put("id", g.id.value)
            put("title", g.displayTitle)
            put("system", d.platform.id.value)
            put("systemName", d.platform.name)
            put("year", g.metadata.releaseYear)
            put("cover", url(d.art.boxart ?: d.art.grid ?: d.art.square ?: d.art.icon))
            put("icon", url(d.art.icon))
            put("hero", url(d.art.hero))
            put("logo", url(d.art.logo))
            put("favorite", g.favorite)
            put("lastPlayedAt", g.play.lastPlayedAt)
            put("description", g.metadata.description)
            put("developer", g.metadata.developer)
            put("publisher", g.metadata.publisher)
            putJsonArray("genres") { g.metadata.genres.forEach { add(JsonPrimitive(it)) } }
            put("players", g.metadata.players)
            put("rating", g.metadata.rating)
            put("series", g.metadata.franchise)
            put("fileName", FsPath.name(g.location.path))
            put("sizeBytes", store.storage.size(gameId))
            put("playMinutes", g.play.totalSeconds / 60)
            put("searchAs", searchAs?.let { s -> buildJsonObject { put("current", s.current); put("custom", s.custom); put("default", s.default) } } ?: JsonNull)
            put("media", buildJsonObject {
                for ((name, kind) in slots) {
                    val item = media.all(kind).firstOrNull()
                    put(name, item?.let { buildJsonObject { put("url", url(it.model)); put("source", it.source.name.lowercase()) } } ?: JsonNull)
                }
            })
            putJsonArray("screenshots") { media.all(MediaKind.SCREENSHOT).mapNotNull { url(it.model) }.forEach { add(JsonPrimitive(it)) } }
        })
    }

    // Fixing a game --------------------------------------------------------------------------------

    suspend fun searchAs(id: Long, body: JsonElement?): Reply {
        val gameId = GameId(id)
        val current = store.media.searchTitle(gameId) ?: return notFound()
        val name = (body as? JsonObject)?.string("name")?.trim()?.take(200) ?: return bad("Send a name.")
        if (name.isEmpty() || name == current.default) {
            store.settings.clear(ScopedSettings.SearchTitle, ScopeRef.game(gameId))
        } else {
            store.settings.set(ScopedSettings.SearchTitle, ScopeRef.game(gameId), name)
        }
        val now = store.media.searchTitle(gameId) ?: current
        return Reply(buildJsonObject {
            put("ok", true)
            put("searchAs", buildJsonObject { put("current", now.current); put("custom", now.custom); put("default", now.default) })
        })
    }

    suspend fun identify(id: Long): Reply {
        return when (val r = store.media.identify(GameId(id))) {
            is IdentifyResult.Matches -> Reply(buildJsonObject {
                put("query", r.query)
                putJsonArray("candidates") { r.candidates.forEach { add(candidate(id, it)) } }
            })
            is IdentifyResult.Unavailable -> Reply(error(r.reason))
        }
    }

    suspend fun accept(id: Long, body: JsonElement?): Reply {
        val o = body as? JsonObject ?: return bad("Pick a match.")
        val key = matchKey(id, o.string("providerId").orEmpty(), o.string("providerGameId").orEmpty())
        val candidate = offeredMatches[key] ?: return bad("That match is no longer listed. Search again.")
        return if (store.media.acceptCandidate(GameId(id), candidate)) ok() else Reply(error("The source no longer lists that game. Search again."))
    }

    suspend fun artOptions(id: Long, kindName: String): Reply {
        val kind = slots[kindName] ?: return bad("Unknown art slot.")
        return when (val r = store.media.artworkOptions(MediaOwner.OfGame(GameId(id)), kind)) {
            is ArtworkResult.Options -> Reply(buildJsonObject {
                putJsonArray("options") {
                    r.options.filter { it.url.startsWith("https://") }.forEach { o ->
                        offeredArt[artKey(id, kind, o.url)] = o
                        add(buildJsonObject {
                            put("url", o.url)
                            put("thumb", o.thumbUrl?.takeIf { it.startsWith("https://") } ?: o.url)
                            put("provider", o.provider.displayName)
                            put("width", o.width)
                            put("height", o.height)
                            put("style", o.style)
                            put("author", o.author)
                        })
                    }
                }
            })
            is ArtworkResult.NeedsMatch -> Reply(buildJsonObject {
                putJsonArray("needsMatch") { r.candidates.forEach { add(candidate(id, it)) } }
            })
            is ArtworkResult.Unavailable -> Reply(error(r.reason))
        }
    }

    suspend fun applyArt(id: Long, kindName: String, body: JsonElement?): Reply {
        val kind = slots[kindName] ?: return bad("Unknown art slot.")
        val url = (body as? JsonObject)?.string("url") ?: return bad("Pick an image.")
        val option = offeredArt[artKey(id, kind, url)] ?: return bad("That image is no longer listed. Look again.")
        store.media.apply(MediaOwner.OfGame(GameId(id)), option)
        return ok()
    }

    fun fillGame(id: Long): Reply {
        store.media.fill(MediaFillMode.FILL_MISSING, FILL_KINDS, game = GameId(id))
        return ok()
    }

    fun fill(body: JsonElement?): Reply {
        val o = body as? JsonObject
        val platform = o?.string("system")?.takeIf { it.isNotBlank() }?.let(::PlatformId)
        when (o?.string("mode")) {
            "everything" -> store.media.fillEverything(platform)
            else -> store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.SQUARE, MediaKind.ICON, MediaKind.BOXART, MediaKind.HERO, MediaKind.LOGO, MediaKind.GRID), platform = platform)
        }
        return ok()
    }

    fun cancelFill(): Reply {
        store.media.cancelFill()
        return ok()
    }

    fun systemArt(): Reply {
        store.media.downloadSystemArt()
        return ok()
    }

    // Images ---------------------------------------------------------------------------------------

    /** The picture behind a token this server handed out, with its type. */
    suspend fun image(token: String): Pair<ContentType, ByteArray>? {
        val model = images.resolve(token) ?: return null
        val bytes = when {
            model.startsWith("/") -> readFile(model, MAX_IMAGE)
            model.startsWith("file://") -> readFile(model.removePrefix("file://"), MAX_IMAGE)
            model.startsWith("content://") -> readUri?.invoke(model)?.takeIf { it.size <= MAX_IMAGE }
            else -> null
        } ?: return null
        return sniff(bytes) to bytes
    }

    /** A URL the phone can load: public https as is, art on this device through a token. */
    private fun url(model: Any?): String? {
        val m = (model as? String)?.takeIf { it.isNotBlank() } ?: return null
        return when {
            m.startsWith("https://") -> m
            m.startsWith("/") || m.startsWith("file://") || m.startsWith("content://") -> "/api/img/${images.tokenFor(m)}"
            else -> null
        }
    }

    // Shapes -------------------------------------------------------------------------------------

    private fun summary(card: GameCard) = buildJsonObject {
        put("id", card.id.value)
        put("title", card.title)
        put("system", card.platformId.value)
        put("systemName", store.library.platforms.value.firstOrNull { it.platform.id == card.platformId }?.platform?.name ?: card.platformShort)
        put("year", card.year)
        put("cover", url(card.art.boxart ?: card.art.grid ?: card.art.square ?: card.art.icon))
        put("icon", url(card.art.icon))
        put("hero", url(card.art.hero))
        put("logo", url(card.art.logo))
        put("favorite", card.favorite)
        put("lastPlayedAt", card.lastPlayedAt)
    }

    private fun candidate(gameId: Long, c: ScrapeCandidate): JsonObject {
        offeredMatches[matchKey(gameId, c.provider.name, c.providerGameId)] = c
        return buildJsonObject {
            put("provider", c.provider.displayName)
            put("providerId", c.provider.name)
            put("providerGameId", c.providerGameId)
            put("title", c.title)
            put("platformName", c.platformName)
            put("year", c.year)
            put("confidence", c.confidence)
            put("preview", c.previewUrl?.takeIf { it.startsWith("https://") })
        }
    }

    private fun matchKey(game: Long, provider: String, providerGameId: String) = "$game|$provider|$providerGameId"
    private fun artKey(game: Long, kind: MediaKind, url: String) = "$game|${kind.name}|$url"

    private fun ok() = Reply(buildJsonObject { put("ok", true) })
    private fun bad(message: String) = Reply(error(message), HttpStatusCode.BadRequest)
    private fun notFound() = Reply(error("This game is no longer in the library."), HttpStatusCode.NotFound)
    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun hex(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    private fun sniff(b: ByteArray): ContentType = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> ContentType.Image.PNG
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> ContentType.Image.JPEG
        b.size > 12 && b.decodeToString(0, 4) == "RIFF" && b.decodeToString(8, 12) == "WEBP" -> ContentType("image", "webp")
        b.size > 6 && b.decodeToString(0, 3) == "GIF" -> ContentType.Image.GIF
        else -> ContentType.Application.OctetStream
    }

    companion object {
        private const val MAX_IMAGE = 16 * 1024 * 1024
        private val FILL_KINDS = setOf(MediaKind.SQUARE, MediaKind.ICON, MediaKind.BOXART, MediaKind.GRID, MediaKind.HERO, MediaKind.LOGO, MediaKind.SCREENSHOT)

        /** The phone's names for the art slots. */
        val slots = linkedMapOf(
            "square" to MediaKind.SQUARE,
            "icon" to MediaKind.ICON,
            "cover" to MediaKind.BOXART,
            "banner" to MediaKind.GRID,
            "background" to MediaKind.HERO,
            "logo" to MediaKind.LOGO,
            "screenshot" to MediaKind.SCREENSHOT,
        )
    }
}

/** Opaque tokens for pictures stored on the device; only paths Fuse itself handed out resolve. */
internal class ImageTokens {
    private val byToken = BoundedMap<String, String>(20_000)
    private val byPath = BoundedMap<String, String>(20_000)

    fun tokenFor(path: String): String = byPath[path] ?: base64(secureRandom(12)).replace('+', '-').replace('/', '_').trimEnd('=').also {
        byPath[path] = it
        byToken[it] = path
    }

    fun resolve(token: String): String? = if (token.length in 8..40) byToken[token] else null
}

/** A small map that forgets its oldest entries past [capacity]. */
internal class BoundedMap<K, V>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>()

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > capacity) map.remove(map.keys.first())
    }
}
