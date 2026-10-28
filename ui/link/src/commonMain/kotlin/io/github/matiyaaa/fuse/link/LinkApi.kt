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
    /** Whether the device has screenshots and recordings to offer (the phone shows their tab). */
    private val capturesAvailable: Boolean = false,
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
        put("captures", capturesAvailable)
        // Whether the Remote's controller works (it can be turned off on the device).
        put("controller", store.prefs.value.phoneLinkController)
        // How the Remote names its buttons: like the controller in use on the device, or as the
        // device's controller is labelled in Settings when it doesn't say what it is.
        put("glyphs", padGlyphs())
    }

    /** The Remote's button names: the controller in use, else the device's setting (never keyboard keys). */
    fun padGlyphs(): String {
        val setting = store.prefs.value.input.glyphs.takeIf { it != io.github.matiyaaa.fuse.model.GlyphStyle.KEYBOARD } ?: io.github.matiyaaa.fuse.model.GlyphStyle.XBOX
        return (io.github.matiyaaa.fuse.ui.shell.app.RemoteInput.padFamily.value ?: setting).name
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
            LoginResult.NoAccount -> Login.Failed(error("Phone Link has no account yet. Set one on the device in Settings, Accounts, Phone Link."), HttpStatusCode.Unauthorized)
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
            // Whether the phone can ask the game to close (on a computer that can).
            put("canQuit", home.playtime.currentGame != null && store.library.canQuitPlaying)
            // Up next: the games the Journal says are wanted, the latest first.
            putJsonArray("upNext") { home.upNext.take(UP_NEXT_SHOWN).forEach { add(summary(it)) } }
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
            put("transfers", transfersJson())
        }
    }

    /**
     * Fuse's own downloads and uploads on this device (RomM's, the household's, the Store's): how
     * many are moving and how far along, the first few, and whether everything is paused.
     */
    private fun transfersJson(): JsonObject {
        val summary = store.transfers.summary.value
        val rows = store.transfers.rows.value.filter { it.device == null && !it.item.status.finished }
        return buildJsonObject {
            put("active", summary.active)
            put("queued", summary.queued + summary.waiting)
            put("failed", summary.failed)
            put("progress", summary.progress)
            put("paused", rows.isNotEmpty() && rows.all { it.item.status == io.github.matiyaaa.fuse.transfer.TransferStatus.PAUSED })
            putJsonArray("items") {
                rows.take(TRANSFERS_SHOWN).forEach { r ->
                    val t = r.item
                    val live = store.transfers.live(t.id).value
                    val done = live.doneBytes.takeIf { it > 0 } ?: t.doneBytes
                    val total = live.totalBytes ?: t.totalBytes
                    add(buildJsonObject {
                        put("title", t.title)
                        put("upload", t.upload)
                        put("state", t.status.name.lowercase())
                        put("done", done)
                        put("total", total)
                        put("progress", total?.takeIf { it > 0 }?.let { (done.toDouble() / it).coerceIn(0.0, 1.0) })
                    })
                }
            }
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
        store.transfers.summary,
        store.transfers.rows,
    ) { values -> nowOf(values[4] as FillProgress?) }
        .distinctUntilChanged()
        .sample(500)

    // From the phone -----------------------------------------------------------------------------

    /** Shows the game's page on the device; starting it stays a press there (or on the Remote). */
    suspend fun openOnDevice(id: Long): Reply {
        val card = store.library.game(GameId(id)).first() ?: return notFound()
        val shown = io.github.matiyaaa.fuse.ui.shell.app.ExternalRequests.post(io.github.matiyaaa.fuse.ui.shell.app.ExternalRequest.OpenGame(card.game.id))
        return if (shown) Reply(buildJsonObject { put("ok", true) }) else Reply(error("$deviceName is busy. Try again in a moment."))
    }

    suspend fun setFavorite(id: Long, body: JsonElement?): Reply {
        val favorite = ((body as? JsonObject)?.get("favorite") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: return Reply(error("Say whether it is a favourite."), HttpStatusCode.BadRequest)
        store.library.game(GameId(id)).first() ?: return notFound()
        store.library.setFavorite(GameId(id), favorite)
        return Reply(buildJsonObject { put("ok", true); put("favorite", favorite) })
    }

    /**
     * [id]'s Journal changed as [body] says: any of `status` (a PlayStatus name, or null for none),
     * `rating` (0 to 10, halves of five stars) and `note`; what isn't there stays as it is.
     */
    suspend fun setJournal(id: Long, body: JsonElement?): Reply {
        val o = body as? JsonObject ?: return Reply(error("Say what to change."), HttpStatusCode.BadRequest)
        val d = store.library.game(GameId(id)).first() ?: return notFound()
        var j = d.game.journal
        if ("status" in o) {
            val s = (o["status"] as? JsonPrimitive)?.contentOrNull
            j = j.copy(status = if (s == null) null else io.github.matiyaaa.fuse.model.PlayStatus.of(s) ?: return Reply(error("There is no such status."), HttpStatusCode.BadRequest))
        }
        if ("rating" in o) {
            val r = (o["rating"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 0..io.github.matiyaaa.fuse.model.GameJournal.MOST_RATING }
                ?: return Reply(error("A rating is 0 to 10."), HttpStatusCode.BadRequest)
            j = j.copy(rating = r)
        }
        if ("note" in o) {
            val n = (o["note"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(io.github.matiyaaa.fuse.model.GameJournal.LONGEST_NOTE)
            j = j.copy(note = n?.ifEmpty { null })
        }
        store.library.setJournal(GameId(id), j)
        return Reply(buildJsonObject { put("ok", true) })
    }

    /** Asks the game being played on the device to close, as its own window would; never by force. */
    suspend fun quitPlaying(): Reply {
        if (!store.library.canQuitPlaying) return Reply(error("$deviceName can't close games from here. Close it in the game."))
        return if (store.library.quitPlaying()) Reply(buildJsonObject { put("ok", true) }) else Reply(error("No game is running on $deviceName."))
    }

    fun pauseTransfers(pause: Boolean): Reply {
        if (pause) store.transfers.pauseAll() else store.transfers.resumeAll()
        return Reply(buildJsonObject { put("ok", true) })
    }

    /** A quick look through the game folders for games added since. */
    fun rescan(): Reply {
        store.sources.rescan(io.github.matiyaaa.fuse.model.ScanScope.QUICK)
        return Reply(buildJsonObject { put("ok", true) })
    }

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
        put("automatic", f.automatic)
        putJsonArray("paused") { f.paused.forEach { add(JsonPrimitive(it)) } }
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
            // Where the person is with it: best written on a phone's keyboard.
            put("journal", buildJsonObject {
                put("status", g.journal.status?.name)
                put("statusAt", g.journal.statusAt)
                put("rating", g.journal.rating)
                put("note", g.journal.note)
            })
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
        store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, game = GameId(id))
        return ok()
    }

    fun fill(body: JsonElement?): Reply {
        val o = body as? JsonObject
        val platform = o?.string("system")?.takeIf { it.isNotBlank() }?.let(::PlatformId)
        when (o?.string("mode")) {
            "everything" -> store.media.fillEverything(platform)
            else -> store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, platform = platform)
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
            FsPath.isAbsolute(model) -> readFile(model, MAX_IMAGE)
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
            FsPath.isAbsolute(m) || m.startsWith("file://") || m.startsWith("content://") -> "/api/img/${images.tokenFor(m)}"
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
    /**
     * [id]'s achievements as the device knows them (RetroAchievements', or the trophies its own
     * platform keeps there): how much of the set the person has, and each one with its badge, the
     * earned ones newest first, then the rest in the set's order. `available` is false when the game
     * has none Fuse knows of. Asked only when a game's page opens on the phone.
     */
    suspend fun achievements(id: Long): Reply {
        val gameId = GameId(id)
        store.library.game(gameId).first() ?: return notFound()
        val s = runCatching { store.achievements.forGame(gameId) }.getOrNull()?.takeIf { it.total > 0 }
            ?: return Reply(buildJsonObject { put("available", false) })
        val ordered = s.achievements.filter { it.earned }.sortedByDescending { maxOf(it.earnedAt ?: 0, it.earnedHardcoreAt ?: 0) } +
            s.achievements.filter { !it.earned }.sortedBy { it.displayOrder }
        return Reply(buildJsonObject {
            put("available", true)
            put("source", s.source.label)
            put("noun", s.source.noun)
            put("total", s.total)
            put("earned", s.earned)
            put("earnedHardcore", s.earnedHardcore)
            if (s.source.hasPoints) {
                put("points", s.points)
                put("pointsEarned", s.pointsEarned)
            }
            put("award", s.highestAward)
            putJsonArray("items") {
                ordered.take(ACHIEVEMENTS_SHOWN).forEach { a ->
                    add(buildJsonObject {
                        put("title", a.title)
                        put("description", a.description)
                        if (s.source.hasPoints) put("points", a.points)
                        put("badge", url(if (a.earned) a.badgeUrl else a.badgeLockedUrl.ifBlank { a.badgeUrl }))
                        put("earnedAt", a.earnedHardcoreAt ?: a.earnedAt)
                        put("hardcore", a.earnedHardcoreAt != null)
                        a.rarity?.let { put("rarity", it) }
                    })
                }
            }
        })
    }

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
        /** The most of Fuse's transfers "now" lists; the device's Downloads page has the rest. */
        private const val TRANSFERS_SHOWN = 6

        private const val MAX_IMAGE = 16 * 1024 * 1024

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

/** How many of the Journal's wanted games the phone's Now shows. */
private const val UP_NEXT_SHOWN = 8

/** The most achievements a phone is sent for one game: more than any set shows at once. */
private const val ACHIEVEMENTS_SHOWN = 400
