package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.FuseRommSettings
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.integrations.net.RoutePicker
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.bios.BiosSearchPaths
import io.github.matiyaaa.fuse.library.storage.UploadFiles
import io.github.matiyaaa.fuse.model.CartridgeGame
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.ExternalLinks
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.romm.BiosPick
import io.github.matiyaaa.fuse.romm.LocalGame
import io.github.matiyaaa.fuse.romm.MatchReason
import io.github.matiyaaa.fuse.romm.PairingState
import io.github.matiyaaa.fuse.romm.ROMM_SOURCE
import io.github.matiyaaa.fuse.romm.ROMM_UPLOAD_SOURCE
import io.github.matiyaaa.fuse.romm.RommBios
import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.romm.RommContent
import io.github.matiyaaa.fuse.romm.RommCredential
import io.github.matiyaaa.fuse.romm.RommDeviceCode
import io.github.matiyaaa.fuse.romm.RommDownloadFile
import io.github.matiyaaa.fuse.romm.RommDownloadJob
import io.github.matiyaaa.fuse.romm.RommException
import io.github.matiyaaa.fuse.romm.RommFile
import io.github.matiyaaa.fuse.romm.RommMatch
import io.github.matiyaaa.fuse.romm.RommMirror
import io.github.matiyaaa.fuse.romm.RommPlacement
import io.github.matiyaaa.fuse.romm.RommRom
import io.github.matiyaaa.fuse.romm.RommScopes
import io.github.matiyaaa.fuse.romm.RommTransferHost
import io.github.matiyaaa.fuse.romm.RommUploadFile
import io.github.matiyaaa.fuse.romm.RommUploadJob
import io.github.matiyaaa.fuse.romm.RootListing
import io.github.matiyaaa.fuse.romm.encode
import io.github.matiyaaa.fuse.romm.rommHandlers
import io.github.matiyaaa.fuse.transfer.TransferArt
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferPlaces
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.Transfers
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.RommNotOnServer
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorChoice
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import io.github.matiyaaa.fuse.ui.shell.store.RommCollectionCard
import io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommGameView
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommOps
import io.github.matiyaaa.fuse.ui.shell.store.RommPartView
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import io.github.matiyaaa.fuse.ui.shell.store.RommState
import io.github.matiyaaa.fuse.ui.shell.store.RommSystem
import io.github.matiyaaa.fuse.ui.shell.store.RommTest
import io.github.matiyaaa.fuse.ui.shell.store.RommUploadLine
import io.github.matiyaaa.fuse.ui.shell.store.RommUploadPlan
import io.github.matiyaaa.fuse.ui.shell.store.rommGameId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A picture RomM keeps, for Fuse's image loader: fetched with Fuse's sign-in and kept for offline. */
data class RommArt(val server: String, val path: String) {
    /** The picture itself, without RomM's cache-busting query (the same picture is the same key). */
    val key: String get() = "romm:$server:" + path.substringBefore('?')
}

/**
 * Fuse RomM in the app: one client for the server set up (Local, Remote or Auto), the library mirror
 * kept in Fuse's database, its games matched to the library's, and its transfers through Downloads.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DefaultRommOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val transfers: Transfers,
    /** Writes the settings (through the store, so the interface follows). */
    private val write: suspend ((FuseRommSettings) -> FuseRommSettings) -> Unit,
    /** Turns Cartridge off in Fuse while Fuse RomM is on (and nothing else about it). */
    private val cartridgeOff: suspend () -> Unit,
    /** Which emulator would play a game, as the game page says it. */
    private val choiceFor: suspend (Game) -> EmulatorChoice,
    /** Applies RomM's details and pictures to the games a download brought in (Cartridge's own path, shared). */
    private val details: CartridgeDetails,
    /** Has Fuse's sources find art and details for games Fuse doesn't have, first ones first. */
    private val fillRemote: (List<GameId>) -> Unit = {},
) : RommOps {
    override val supported = true
    private val mirror = RommMirror(ctx.data.database, ctx::now)

    /** The server's games Fuse doesn't have, as games: their own names, details and art, kept ([RommGames]). */
    private val remote = RommGames(ctx, mirror, { server }, { redrawSoon() })
    private var redrawJob: Job? = null

    /** Art or details of a game Fuse doesn't have changed: the lists draw again, once for a burst of them. */
    private fun redrawSoon() {
        if (redrawJob?.isActive == true) return
        redrawJob = ctx.scope.launch {
            delay(REDRAW_MS)
            mirrorRevision.update { it + 1 }
            publishLists()
        }
    }

    /** Games on the server that Fuse doesn't have, for Fuse's sources to fill, these first. */
    private fun fillUnmatched(roms: List<RommRom>) {
        val m = matches.value
        fillRemote(roms.filter { it.id !in m }.map { rommGameId(it.id) })
    }
    private val _state = MutableStateFlow(RommState())
    override val state: StateFlow<RommState> = _state

    private var client: RommClient? = null
    private var refreshJob: Job? = null
    private var loopJob: Job? = null
    private val connectLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** RomM game to the Fuse game it is, once matched. */
    private val matches = MutableStateFlow<Map<Long, Pair<GameId, MatchReason>>>(emptyMap())
    /** Moves after every mirror sync, so the lists read the mirror again. */
    private val mirrorRevision = MutableStateFlow(0)

    private val settings: FuseRommSettings get() = ctx.settings.value.romm
    private val server: String get() = settings.server.ifBlank { "main" }

    // ------------------------------------------------------------------ start

    fun start() {
        ctx.remoteGames = remote
        rommArtCache = RommArtCache(ctx, this::clientFor)
        transfers.let { t -> rommHandlers(ctx.services.http, host).forEach(t::register) }
        ctx.scope.launch {
            ctx.settings.map { it.romm }.distinctUntilChanged { a, b -> a.enabled == b.enabled && a.mode == b.mode && a.localAddress == b.localAddress && a.remoteAddress == b.remoteAddress }
                .collect { s ->
                    if (!s.enabled) {
                        loopJob?.cancel()
                        client = null
                        _state.value = RommState(RommLink.OFF)
                    } else {
                        connect()
                    }
                }
        }
        // A scan that finished may have brought in games RomM has: matched again.
        ctx.scope.launch {
            engine.scan.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == io.github.matiyaaa.fuse.model.ScanPhase.DONE && settings.enabled) rematch()
            }
        }
    }

    /** The client for [server] as set up now (the one in use), or null when off or signed out. */
    internal fun clientFor(server: String): RommClient? = client?.takeIf { server == this.server && settings.enabled }

    private suspend fun credential(): RommCredential? = ctx.services.secrets.get(SECRET)?.let {
        runCatching { json.decodeFromString(RommCredential.serializer(), it) }.getOrNull()
    }

    private fun routes(s: FuseRommSettings): RoutePicker = RoutePicker(
        s.localAddress.ifBlank { null }, s.remoteAddress.ifBlank { null }, modeOf(s.mode), ctx.scope, ctx::now,
        probe = { base ->
            runCatching {
                val r = quickProbe(base)
                r
            }.getOrDefault(false)
        },
    )

    private suspend fun quickProbe(base: String): Boolean = withContext(Dispatchers.Default) {
        kotlinx.coroutines.withTimeoutOrNull(PROBE_MS) {
            runCatching { ctx.services.http.get("$base/api/heartbeat").status.isSuccess() }.getOrDefault(false)
        } ?: false
    }

    private suspend fun connect() = connectLock.withLock {
        val s = settings
        if (s.localAddress.isBlank() && s.remoteAddress.isBlank()) {
            _state.value = RommState(RommLink.NOT_SET_UP)
            return@withLock
        }
        val cred = credential()
        val c = RommClient(ctx.services.http, routes(s), cred, ctx.services.appVersion)
        client = c
        // The mirror shows at once, whatever the server says.
        _state.value = _state.value.copy(link = RommLink.CONNECTING, syncedAt = mirror.syncedAt(server), games = mirror.count(server))
        publishLists()
        if (cred == null) {
            _state.update { it.copy(link = RommLink.SIGNED_OUT, problem = null) }
            return@withLock
        }
        try {
            c.detect()
            val account = c.me()
            _state.update {
                it.copy(
                    link = RommLink.ONLINE, route = c.route, version = c.capabilities.version, account = account,
                    canUpload = c.may(RommScopes.ROMS_WRITE) && c.capabilities.chunkedUploads,
                    canScan = c.may(RommScopes.TASKS_RUN) && c.capabilities.tokenScans, problem = null,
                )
            }
            if (s.account != account || s.serverVersion != c.capabilities.version || !s.configured) {
                write { it.copy(account = account, serverVersion = c.capabilities.version, configured = true) }
            }
            startLoop()
        } catch (e: RommException) {
            _state.update { it.copy(link = linkAfter(e), problem = e.message) }
            // Away: look again in a while.
            startLoop()
        }
    }

    /**
     * The link after [e]: signed out when RomM refused the sign-in, away only when no address could
     * be reached at all. A server that answered slowly, or with something Fuse couldn't read, is
     * still there: the problem is shown and the next look tries again.
     */
    private fun linkAfter(e: RommException): RommLink = when {
        e.forbidden -> RommLink.SIGNED_OUT
        e.code == "offline" || e.code == "no-address" -> RommLink.OFFLINE
        else -> RommLink.ONLINE
    }

    /** Keeps the mirror up to date while Fuse runs, and notices when the server comes back. */
    private fun startLoop() {
        loopJob?.cancel()
        loopJob = ctx.scope.launch {
            var first = true
            while (true) {
                val link = _state.value.link
                if (link == RommLink.ONLINE) {
                    if (first || ctx.now() - mirror.syncedAt(server) >= settings.refreshMinutes.coerceIn(5, 1440) * 60_000L) syncNow(full = false)
                } else if (link == RommLink.OFFLINE) {
                    connectLock.withLock { }
                    runCatching { client?.heartbeat() }.onSuccess {
                        _state.update { it.copy(link = RommLink.CONNECTING) }
                        ctx.scope.launch { connect() }
                        return@launch
                    }
                }
                first = false
                delay(LOOP_MS)
            }
        }
    }

    override fun refresh(full: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = ctx.scope.launch {
            if (_state.value.link != RommLink.ONLINE) connect()
            if (_state.value.link == RommLink.ONLINE) syncNow(full)
        }
    }

    private suspend fun syncNow(full: Boolean) {
        val c = client ?: return
        val before = mirror.newGames(server, 1000).size
        try {
            val result = mirror.sync(c, server, full = full) { p -> _state.update { it.copy(syncing = p) } }
            _state.update { it.copy(syncing = null, syncedAt = ctx.now(), games = result.total, route = c.route, problem = null) }
            rematch()
            mirrorRevision.update { it + 1 }
            publishLists()
            fillUnmatched(mirror.all(server))
            val now = mirror.newGames(server, 1000).size
            if (now > before && settings.newGames == "NOTIFY") noticeFlow.tryEmit(if (now - before == 1) "A new game is on your RomM server" else "${now - before} new games are on your RomM server")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RommException) {
            _state.update { it.copy(syncing = null, link = linkAfter(e), problem = e.message) }
        } catch (e: Exception) {
            _state.update { it.copy(syncing = null, problem = "The library couldn't be brought up to date.") }
        }
    }

    val noticeFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val notices: Flow<String> get() = noticeFlow

    // ------------------------------------------------------------------ set-up and sign-in

    override suspend fun test(local: String, remote: String, mode: RouteMode): RommTest = withContext(Dispatchers.Default) {
        suspend fun at(address: String): Pair<Boolean, String?> {
            if (address.isBlank()) return false to null
            val base = RoutePicker.clean(address)
            val c = RommClient(ctx.services.http, RoutePicker(base, null, RouteMode.LOCAL, ctx.scope, ctx::now, { true }), null)
            return try {
                true to c.heartbeat()
            } catch (e: Exception) {
                false to null
            }
        }
        val l = if (local.isNotBlank() && mode != RouteMode.REMOTE) at(local) else null
        val r = if (remote.isNotBlank() && mode != RouteMode.LOCAL) at(remote) else null
        val version = l?.second ?: r?.second
        RommTest(
            localOk = l?.first, remoteOk = r?.first, version = version,
            problem = when {
                local.isBlank() && remote.isBlank() -> "Add the address of your RomM server."
                version == null -> "Fuse couldn't reach RomM there. Check the address, and that RomM is running."
                else -> null
            },
        )
    }

    override suspend fun setAddresses(local: String, remote: String, mode: RouteMode) {
        write { it.copy(localAddress = local.trim(), remoteAddress = remote.trim(), mode = mode.name, enabled = true) }
        cartridgeOff()
    }

    override suspend fun startPairing(upload: Boolean): Result<RommDeviceCode> = runCatching {
        val c = client ?: RommClient(ctx.services.http, routes(settings), null, ctx.services.appVersion).also { client = it }
        if (c.capabilities.version.isEmpty()) c.detect()
        if (!c.capabilities.deviceAuth) throw RommException("This RomM server doesn't pair this way. Use a pairing code or a token from RomM instead.", code = "no-device-auth")
        c.startPairing(deviceId(), ctx.services.deviceName, ctx.host.name.lowercase(), if (upload) RommScopes.UPLOAD_AND_SCAN else RommScopes.READ)
    }

    override suspend fun awaitPairing(code: RommDeviceCode): Result<String> = runCatching {
        val c = client ?: throw RommException("Fuse RomM isn't set up.")
        val until = ctx.now() + code.expiresInSeconds * 1000L
        var interval = code.intervalSeconds.coerceAtLeast(2) * 1000L
        while (ctx.now() < until) {
            delay(interval)
            when (val s = c.pollPairing(code)) {
                PairingState.Waiting -> Unit
                PairingState.SlowDown -> interval += 2_000
                PairingState.Denied -> throw RommException("The pairing was declined in RomM.")
                PairingState.Expired -> throw RommException("The pairing ran out. Start it again.")
                is PairingState.Approved -> return@runCatching signedIn(s.credential)
            }
        }
        throw RommException("The pairing ran out. Start it again.")
    }

    override suspend fun usePairCode(code: String): Result<String> = runCatching {
        val c = client ?: RommClient(ctx.services.http, routes(settings), null).also { client = it }
        signedIn(c.exchangeCode(code))
    }

    override suspend fun usePassword(username: String, password: String): Result<String> = runCatching {
        signedIn(RommCredential.Password(username.trim(), password))
    }

    override suspend fun useToken(token: String): Result<String> = runCatching {
        signedIn(RommCredential.Token(token.trim()))
    }

    /** Keeps [credential] in the secret store once it is shown to work, and connects with it. */
    private suspend fun signedIn(credential: RommCredential): String {
        val c = RommClient(ctx.services.http, routes(settings), credential, ctx.services.appVersion)
        c.detect()
        val account = c.me()
        ctx.services.secrets.put(SECRET, json.encodeToString(RommCredential.serializer(), credential))
        write { it.copy(enabled = true, configured = true, account = account, serverVersion = c.capabilities.version) }
        cartridgeOff()
        connect()
        return account
    }

    override suspend fun setEnabled(enabled: Boolean) {
        write { it.copy(enabled = enabled) }
        if (enabled) cartridgeOff()
    }

    override suspend fun signOut() {
        ctx.services.secrets.remove(SECRET)
        client?.credential = null
        write { it.copy(account = "") }
        _state.update { it.copy(link = RommLink.SIGNED_OUT, account = "", canUpload = false, canScan = false) }
    }

    private suspend fun deviceId(): String {
        ctx.data.cache.entry(NS, "deviceId")?.valueJson?.trim('"')?.let { return it }
        val id = "fuse-" + kotlin.random.Random.nextLong().toULong().toString(16)
        ctx.data.cache.put(NS, "deviceId", "\"$id\"", ctx.now(), ttlMs = null)
        return id
    }

    // ------------------------------------------------------------------ matching

    private val matchLock = Mutex()

    /** Joins the mirror's games to the library's, and links strong matches so they stay joined. */
    private suspend fun rematch() = matchLock.withLock {
        withContext(Dispatchers.Default) {
            val rows = ctx.data.games.matchRows()
            val locals = rows.map { r -> LocalGame(r.id.value, r.platform, r.title, FsPath.name(r.path), r.serial, null, r.rommRomId, r.missing) }
            val roms = mirror.all(server)
            val result = RommMatch.match(roms, locals) { slug -> ctx.platforms.resolveFolder(slug)?.id?.value }
            matches.value = result.associate { it.romId to (GameId(it.gameId) to it.reason) }
            // Linked from now on: the game page and Cartridge's Open both know it.
            val byId = rows.associateBy { it.id.value }
            for (m in result) {
                val row = byId[m.gameId] ?: continue
                if (row.rommRomId == null && m.reason != MatchReason.NAME_AND_TAGS) ctx.data.games.updateLinks(GameId(m.gameId)) { it.copy(rommRomId = m.romId) }
            }
            // A scan or a download changed what the library has: what RomM hasn't got follows.
            _notOnServer.value = notOnServerNow()
        }
    }

    // ------------------------------------------------------------------ lists

    private val _systems = MutableStateFlow<List<RommSystem>>(emptyList())

    /** The server's systems with Fuse's system art and colours, drawn again as that art arrives. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val systems: StateFlow<List<RommSystem>> = _systems
        .flatMapLatest { list ->
            val ids = list.mapNotNull { it.platform }.distinct()
            if (ids.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(list)
            } else {
                combine(ctx.data.media.observeFor(ids.map { MediaOwner.OfPlatform(it) }), ctx.settings.map { it.library.systemColors }) { media, colors ->
                    list.map { s ->
                        val id = s.platform ?: return@map s
                        s.copy(art = media[MediaOwner.OfPlatform(id)]?.let(Art::from) ?: Art.None, accent = colors[id.value])
                    }
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(ctx.scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    private val _recent = MutableStateFlow<List<RommGame>>(emptyList())
    override val recent: StateFlow<List<RommGame>> = _recent
    private val _new = MutableStateFlow<List<RommGame>>(emptyList())
    override val newGames: StateFlow<List<RommGame>> = _new
    private val _collections = MutableStateFlow<List<RommCollectionCard>>(emptyList())
    override val collections: StateFlow<List<RommCollectionCard>> = _collections

    private suspend fun publishLists() = withContext(Dispatchers.Default) {
        val platforms = mirror.platforms(server)
        val m = matches.value
        val installedBySlug = mirror.all(server).filter { it.id in m }.groupingBy { it.platformSlug }.eachCount()
        _systems.value = platforms.map { p ->
            RommSystem(ctx.platforms.resolveFolder(p.slug)?.id ?: ctx.platforms.resolveFolder(p.fsSlug)?.id, p.slug, p.name, p.romCount, installedBySlug[p.slug] ?: 0, p.sizeBytes)
        }.sortedWith(compareBy({ it.platform == null }, { it.name.lowercase() }))
        // These get Fuse's system art like the library's systems.
        ctx.shownPlatforms.value = _systems.value.mapNotNull { it.platform }.toSet()
        val recentRoms = mirror.recent(server, 40)
        _recent.value = toGames(recentRoms)
        val fresh = mirror.newGames(server, 200)
        _new.value = toGames(fresh, isNew = true)
        // What shows first gets its art first.
        fillUnmatched(fresh + recentRoms)
        _state.update { it.copy(newGames = fresh.size, games = mirror.count(server)) }
        _notOnServer.value = notOnServerNow()
        val cols = mirror.collections(server)
        _collections.value = cols.map { c ->
            RommCollectionCard(c.id, c.name, c.smart, c.romIds.size, toGames(mirror.roms(server, c.romIds.take(4))).map { it.card })
        }.filter { it.games > 0 }
    }

    private val _notOnServer = MutableStateFlow(RommNotOnServer())
    override val notOnServer: StateFlow<RommNotOnServer> = _notOnServer

    /**
     * Library games no RomM game was matched to, apps and games whose files are gone aside. Only
     * once a whole read of the server's library has finished: before that, most would look missing.
     */
    private suspend fun notOnServerNow(): RommNotOnServer {
        if (mirror.syncedAt(server) <= 0L) return RommNotOnServer()
        val matched = matches.value.values.mapTo(HashSet()) { it.first }
        val missing = ctx.data.games.observeAll().first().filter { !it.isApp && !it.missing && !it.removed && it.id !in matched }
        val shown = missing.take(NOT_ON_SERVER_SHOWN)
        val media = if (shown.isEmpty()) emptyMap() else ctx.data.media.observeFor(shown.map { MediaOwner.OfGame(it.id) }).first()
        return RommNotOnServer(shown.map { ctx.summaryToCard(it, media[MediaOwner.OfGame(it.id)]) }, missing.size)
    }

    override fun games(slug: String?): Flow<List<RommGame>> = combine(mirrorRevision, matches, transfers.items) { _, _, _ -> }.mapLatest {
        toGames(if (slug == null) mirror.all(server) else mirror.onPlatform(server, slug))
    }.flowOn(Dispatchers.Default)

    override fun collection(id: String): Flow<List<RommGame>> = combine(mirrorRevision, matches) { _, _ -> }.mapLatest {
        val c = mirror.collections(server).firstOrNull { it.id == id } ?: return@mapLatest emptyList()
        toGames(mirror.roms(server, c.romIds))
    }.flowOn(Dispatchers.Default)

    override fun search(text: String): Flow<List<RommGame>> = flow {
        if (text.isBlank()) emit(emptyList()) else emit(toGames(mirror.search(server, text, 120)))
    }.flowOn(Dispatchers.Default)

    override fun markNewSeen() {
        ctx.scope.launch {
            mirror.seenNew(server)
            publishLists()
        }
    }

    /** Mirror rows as the lists show them: the library's own card where Fuse has the game. */
    private suspend fun toGames(roms: List<RommRom>, isNew: Boolean = false): List<RommGame> {
        if (roms.isEmpty()) return emptyList()
        val m = matches.value
        val running = transfers.items.value.filter { it.source == ROMM_SOURCE && !it.status.finished }.associateBy { it.key }
        val ids = roms.mapNotNull { m[it.id]?.first }
        val owners = ids.map { MediaOwner.OfGame(it) } + roms.filter { it.id !in m }.map { MediaOwner.OfGame(rommGameId(it.id)) }
        val media = if (owners.isEmpty()) emptyMap() else ctx.data.media.observeFor(owners).first()
        val records = remote.all()
        val summaries = ids.mapNotNull { id -> ctx.data.games.summary(id)?.let { id to it } }.toMap()
        return roms.map { r ->
            val game = m[r.id]?.first
            val summary = game?.let { summaries[it] }
            val t = running[keyOf(r.id)]
            val card = summary?.let { ctx.summaryToCard(it, media[MediaOwner.OfGame(it.id)]).copy(rommRomId = r.id) }
                ?: cardOf(r, records[r.id], media[MediaOwner.OfGame(rommGameId(r.id))])
            RommGame(
                romId = r.id, card = card,
                presence = when {
                    t?.status == TransferStatus.ACTIVE -> RommPresence.DOWNLOADING
                    t != null -> RommPresence.QUEUED
                    game != null -> RommPresence.INSTALLED
                    else -> RommPresence.ON_SERVER
                },
                game = game, sizeBytes = r.sizeBytes, isNew = isNew, transfer = t?.id,
            )
        }
    }

    /**
     * A RomM game Fuse doesn't have, drawn like any Fuse game: the art Fuse's sources found for it
     * (or the user chose), with RomM's own pictures only where nothing else was found, and its name
     * and year as Fuse knows them. The tile picks box art or poster by the Posters setting, as ever.
     */
    private fun cardOf(r: RommRom, record: RommGameRecord?, media: MediaSet?): GameCard {
        val platform = ctx.platforms.resolveFolder(r.platformSlug)
        return GameCard(
            id = rommGameId(r.id),
            platformId = platform?.id ?: io.github.matiyaaa.fuse.model.PlatformId(r.platformSlug),
            title = remote.title(r, record),
            platformShort = platform?.shortName ?: r.platformSlug.uppercase(),
            accent = platform?.accent ?: StoreContext.DEFAULT_ACCENT,
            art = withServerArt(media?.let(Art::from) ?: Art.None, r),
            year = record?.metadata?.releaseYear ?: r.year,
            discs = RommContent.parts(r).count { it.disc != null },
            rommRomId = r.id,
        )
    }

    /** [found] with RomM's pictures where Fuse has none of that kind. */
    private fun withServerArt(found: Art, r: RommRom): Art {
        val theirs = artOf(r)
        return found.copy(boxart = found.boxart ?: theirs.boxart, logo = found.logo ?: theirs.logo, screenshot = found.screenshot ?: theirs.screenshot)
    }

    private fun artOf(r: RommRom): Art = Art(
        boxart = r.cover?.let { RommArt(server, it) },
        logo = r.logo?.let { RommArt(server, it) },
        screenshot = r.screenshot?.let { RommArt(server, it) },
    )

    // ------------------------------------------------------------------ one game

    override fun detail(romId: Long): Flow<GameDetail?> {
        val owner = MediaOwner.OfGame(rommGameId(romId))
        // Opened: its art and details are looked for straight away, if they aren't already there.
        fillRemote(listOf(rommGameId(romId)))
        return combine(mirrorRevision, ctx.installed, ctx.data.media.observe(owner)) { _, _, m -> m }.mapLatest { media ->
            val r = mirror.withFiles(clientFor(server), server, romId) ?: return@mapLatest null
            val platform = ctx.platforms.resolveFolder(r.platformSlug) ?: return@mapLatest null
            val game = remote.game(r, remote.all()[romId]) ?: return@mapLatest null
            GameDetail(
                game = game, platform = platform, media = media, art = withServerArt(Art.from(media), r),
                emulator = runCatching { choiceFor(game) }.getOrDefault(EmulatorChoice(null, emptyList(), "Automatic", null, false)),
                contentNotes = emptyList(), achievements = null, collections = emptyList(), secondsThisWeek = 0,
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun forGame(game: GameId): Flow<RommGameView?> = combine(matches, transfers.items, mirrorRevision) { m, _, _ -> m.entries.firstOrNull { it.value.first == game } }
        .mapLatest { e ->
            if (!settings.enabled) return@mapLatest null
            val romId = e?.key ?: ctx.data.games.get(game)?.links?.rommRomId ?: return@mapLatest null
            view(romId, game, e?.value?.second)
        }.flowOn(Dispatchers.Default)

    override fun forRom(romId: Long): Flow<RommGameView?> = combine(matches, transfers.items, mirrorRevision) { m, _, _ -> m[romId] }
        .mapLatest { v -> view(romId, v?.first, v?.second) }.flowOn(Dispatchers.Default)

    private suspend fun view(romId: Long, game: GameId?, reason: MatchReason?): RommGameView? {
        val r = mirror.withFiles(clientFor(server), server, romId) ?: return null
        val here = game?.let { localNames(it) }.orEmpty()
        val running = transfers.items.value.firstOrNull { it.source == ROMM_SOURCE && it.key.startsWith(keyOf(romId)) && !it.status.finished }
        return RommGameView(
            romId = r.id, name = r.name,
            parts = RommContent.parts(r).map { p ->
                RommPartView(p.kind, p.label, p.sizeBytes, p.files.map { it.id }, here = p.files.isNotEmpty() && p.files.all { it.name.lowercase() in here }, downloading = running != null)
            },
            matchedBy = reason?.let(::reasonWords),
            transfer = running?.id,
        )
    }

    /** The names of every file a game has here: its file, its discs, its content, and what is in its folder. */
    private suspend fun localNames(id: GameId): Set<String> {
        val g = ctx.data.games.get(id) ?: return emptySet()
        val names = HashSet<String>()
        names += FsPath.name(g.location.path).lowercase()
        g.discs.forEach { names += FsPath.name(it.path).lowercase() }
        g.content.forEach { names += FsPath.name(it.path).lowercase() }
        if (g.location.kind == LocationKind.FOLDER) walkNames(g.location.path, 0, names)
        else FsPath.parent(g.location.path)?.let { parent -> runCatching { ctx.services.fs.list(parent) }.getOrDefault(emptyList()).forEach { names += it.name.lowercase() } }
        return names
    }

    private suspend fun walkNames(dir: String, depth: Int, out: MutableSet<String>) {
        if (depth > 3) return
        for (e in runCatching { ctx.services.fs.list(dir) }.getOrDefault(emptyList())) {
            out += e.name.lowercase()
            if (e.isDirectory) walkNames(e.path, depth + 1, out)
        }
    }

    // ------------------------------------------------------------------ downloads

    override suspend fun download(romId: Long, what: RommDownloadWhat): String? = withContext(Dispatchers.Default) {
        if (!settings.enabled) return@withContext "Turn on Fuse RomM in Settings, Addons, Fuse RomM."
        val r = mirror.withFiles(clientFor(server), server, romId) ?: return@withContext "That game isn't in your RomM library any more."
        if (r.files.isEmpty()) return@withContext "Fuse needs RomM to list this game's files first. Try again once the server answers."
        val platform = ctx.platforms.resolveFolder(r.platformSlug) ?: return@withContext "Fuse doesn't know RomM's system \"${r.platformSlug}\", so it can't tell where this goes."
        val game = matches.value[romId]?.first
        val here = game?.let { localNames(it) }.orEmpty()
        val parts = RommContent.parts(r)
        val files: List<RommFile> = when (what) {
            RommDownloadWhat.Game -> r.files.filter { it.kind == ContentKind.GAME }.ifEmpty { r.playable }
            RommDownloadWhat.Everything -> r.playable
            RommDownloadWhat.MissingDiscs -> parts.filter { it.disc != null }.flatMap { it.files }.filter { it.name.lowercase() !in here }
            RommDownloadWhat.Updates -> r.files.filter { it.kind == ContentKind.UPDATE }
            RommDownloadWhat.Dlc -> r.files.filter { it.kind == ContentKind.DLC }
            is RommDownloadWhat.Files -> r.files.filter { it.id in what.ids }
        }.filter { game == null || it.name.lowercase() !in here || what is RommDownloadWhat.Files }
        if (files.isEmpty()) return@withContext if (game != null) "Everything of this is already here." else "RomM has no files for this game."
        // Where it goes: the game's own folder when Fuse has it, else the system's folder Fuse knows.
        val existing = game?.let { ctx.data.games.get(it) }
        val existingDir = existing?.location?.takeIf { it.kind == LocationKind.FOLDER }?.path
        val folder = existing?.location?.path?.let { if (existingDir != null) FsPath.parent(it) else FsPath.parent(it) }
            ?: systemFolder(platform, r)?.first
            ?: return@withContext "Choose where games go first: Settings, Addons, Fuse RomM, Library."
        val placement = RommPlacement.layout(r, files, folder, existingDir)
        val newFolder = existingDir == null && placement.gamePath != files.singleOrNull()?.let { placement.files[it.id] } && !fileExists(placement.gamePath)
        val placeRoot = placement.gamePath.takeIf { newFolder || existingDir != null } ?: folder
        val volumes = engine.drives.volumes.value
        val label = when (what) {
            RommDownloadWhat.Game, RommDownloadWhat.Everything -> ""
            RommDownloadWhat.MissingDiscs -> "Missing discs"
            RommDownloadWhat.Updates -> "Update"
            RommDownloadWhat.Dlc -> "DLC"
            is RommDownloadWhat.Files -> what.label
        }
        val job = RommDownloadJob(
            server = server, romId = r.id,
            files = files.map { f -> RommDownloadFile(f, FsPath.normalize(placement.files.getValue(f.id)).removePrefix(FsPath.normalize(placeRoot)).trimStart('/')) },
            newFolder = newFolder, platform = platform.id.value,
        )
        val drive = TransferPlaces.of(placeRoot, volumes, ctx.now())
        transfers.enqueue(
            TransferItem(
                id = "", key = keyOf(r.id) + (if (label.isNotEmpty()) ":" + files.joinToString(",") { it.id.toString() } else ""),
                source = ROMM_SOURCE, direction = TransferDirection.DOWNLOAD,
                kind = if (label.isEmpty()) TransferKind.GAME else TransferKind.CONTENT,
                title = r.name, detail = label, platform = platform.id.value,
                art = TransferArt(logo = r.logo?.let { rommArtCache?.modelKey(server, it) }, cover = r.cover?.let { rommArtCache?.modelKey(server, it) }),
                place = drive, target = "${drive.volume?.label ?: "This device"}  ·  ${shortPath(folder)}",
                totalBytes = files.sumOf { it.sizeBytes }, payload = job.encode(),
            ),
        )
        null
    }

    private suspend fun fileExists(path: String) = runCatching { ctx.services.fs.stat(path) != null }.getOrDefault(false)

    /** The system's folder for RomM's [r] on this device, and whether Fuse makes it. */
    private suspend fun systemFolder(platform: Platform, r: RommRom): Pair<String, Boolean>? {
        val s = settings
        val sources = ctx.data.sources.all().filter { it.enabled && (it.kind == LibrarySourceKind.ROMS_ROOT || it.kind == LibrarySourceKind.ROMM_LIBRARY) }
        // Folders Fuse already found holding this system's games come first.
        engine.platformFolders.value[platform.id]?.firstOrNull()?.let { if (s.systemFolders[platform.id.value].isNullOrBlank()) return it to false }
        val roots = sources.map { src ->
            val base = FsPath.normalize(src.path)
            val inner = if (fileExists(FsPath.join(base, "roms"))) FsPath.join(base, "roms") else base
            RootListing(inner, runCatching { ctx.services.fs.list(inner) }.getOrDefault(emptyList()).filter { it.isDirectory }.map { it.name }, romm = src.kind == LibrarySourceKind.ROMM_LIBRARY)
        }
        val rommFs = mirror.platforms(server).firstOrNull { it.slug == r.platformSlug }?.fsSlug ?: r.platformSlug
        return RommPlacement.systemFolder(platform, rommFs, roots, s.systemFolders, s.libraryRoot.ifBlank { null }, ctx.platforms::resolveFolder)
    }

    /** Fuse RomM's transfers ask the app for these. */
    private val host = object : RommTransferHost {
        override fun client(server: String): RommClient? = clientFor(server)
        override val mirror: RommMirror get() = this@DefaultRommOps.mirror

        override suspend fun landed(job: RommDownloadJob, item: TransferItem, paths: List<String>) {
            val fw = job.firmware
            if (fw != null) {
                engine.refreshBios()
                job.install?.let { how -> noticeFlow.tryEmit("${fw.fileName} is in ${shortPath(FsPath.parent(paths.firstOrNull() ?: "") ?: "")}. $how") }
                return
            }
            val r = mirror.rom(job.server, job.romId) ?: return
            val gamePath = item.place?.let { p -> TransferPlaces.resolve(p, engine.drives.volumes.value) } ?: return
            // The folder a game landed in is one Fuse reads: a system folder outside every library is added.
            val inLibrary = ctx.data.sources.all().any { it.enabled && FsPath.isWithin(FsPath.normalize(gamePath), FsPath.normalize(it.path)) }
            if (!inLibrary) FsPath.parent(gamePath)?.let { engine.add(it, LibrarySourceKind.PLATFORM_FOLDER) }
            if (settings.scanAfterDownload) {
                engine.rescan(ScanScope.PLATFORM, ctx.platforms.resolveFolder(r.platformSlug)?.id)
                // Once indexed: linked to RomM, with RomM's details and pictures.
                withContext(Dispatchers.Default) {
                    kotlinx.coroutines.withTimeoutOrNull(120_000) {
                        engine.scan.first { it.phase == io.github.matiyaaa.fuse.model.ScanPhase.DONE || it.phase == io.github.matiyaaa.fuse.model.ScanPhase.FAILED }
                    }
                    val lead = paths.firstOrNull()
                    val target = if (job.newFolder || r.multi) gamePath else lead ?: gamePath
                    val cover = r.cover?.let { rommArtCache?.file(job.server, it) }
                    val logo = r.logo?.let { rommArtCache?.file(job.server, it) }
                    val shot = r.screenshot?.let { rommArtCache?.file(job.server, it) }
                    details.sync(
                        listOf(CartridgeGame(r.id, target, r.name, r.platformSlug, r.summary, r.year, r.genres, r.developer, cover = cover, logo = logo, screenshot = shot, updatedAt = r.updatedAt)),
                        applyDetails = true,
                    )
                    rematch()
                    publishLists()
                }
            }
        }

        override suspend fun uploaded(job: RommUploadJob, item: TransferItem, romId: Long?) {
            if (job.skipped.isNotEmpty()) noticeFlow.tryEmit(if (job.skipped.size == job.files.size) "RomM already had ${item.title}" else "Sent ${item.title} to RomM (it already had some of it)")
            else noticeFlow.tryEmit("Sent ${item.title} to RomM")
            delay(5_000)
            refresh()
        }
    }

    // ------------------------------------------------------------------ uploads

    override suspend fun uploadPlan(game: GameId): RommUploadPlan? = withContext(Dispatchers.Default) {
        val g = ctx.data.games.get(game) ?: return@withContext null
        val c = client
        val files = runCatching { UploadFiles.collect(ctx.services.fs, g.location, g.discs) }.getOrDefault(emptyList())
        val platform = ctx.platform(g.platformId)
        val rommPlatform = mirror.platforms(server).firstOrNull { p -> ctx.platforms.resolveFolder(p.slug)?.id == g.platformId }
            ?: runCatching { c?.platforms() }.getOrNull()?.firstOrNull { p -> ctx.platforms.resolveFolder(p.slug)?.id == g.platformId }
        RommUploadPlan(
            title = g.displayTitle,
            platformName = platform?.name ?: g.platformId.value,
            platformId = rommPlatform?.id,
            files = files.map { f -> RommUploadLine(f.name, f.folder, f.sizeBytes, f.folder.split('/').firstOrNull()?.let { ContentKind.ofFolder(it) } ?: ContentKind.GAME) },
            totalBytes = files.sumOf { it.sizeBytes },
            needsPermission = c == null || !c.may(RommScopes.ROMS_WRITE),
            serverName = settings.account.takeIf { it.isNotBlank() }?.let { "RomM ($it)" } ?: "RomM",
            problem = when {
                files.isEmpty() -> "Fuse found no files for this game."
                rommPlatform == null -> "Your RomM server has no ${platform?.name ?: "such"} system yet. Add it in RomM, then try again."
                c?.capabilities?.chunkedUploads == false -> "This RomM server is too old to take uploads from Fuse."
                else -> null
            },
        )
    }

    override suspend fun upload(game: GameId, plan: RommUploadPlan): String? = withContext(Dispatchers.Default) {
        if (plan.needsPermission) return@withContext "Fuse's RomM sign-in can only read. Pair Fuse again and allow uploads."
        val pid = plan.platformId ?: return@withContext plan.problem ?: "RomM has no such system."
        val g = ctx.data.games.get(game) ?: return@withContext "That game isn't in the library any more."
        val files = UploadFiles.collect(ctx.services.fs, g.location, g.discs)
        val job = RommUploadJob(server, pid, files.map { RommUploadFile(it.path, it.name, it.folder, it.sizeBytes) }, romId = g.links.rommRomId, scanAfter = settings.scanAfterUpload)
        val volumes = engine.drives.volumes.value
        transfers.enqueue(
            TransferItem(
                id = "", key = "romm:upload:${game.value}", source = ROMM_UPLOAD_SOURCE, direction = TransferDirection.UPLOAD, kind = TransferKind.GAME,
                title = g.displayTitle, detail = if (files.size > 1) "${files.size} files" else "", platform = g.platformId.value,
                place = TransferPlaces.of(g.location.path, volumes, ctx.now()), target = plan.serverName, totalBytes = plan.totalBytes, payload = job.encode(),
            ),
        )
        null
    }

    // ------------------------------------------------------------------ BIOS

    override suspend fun biosPlan(all: Boolean): List<BiosPick> = withContext(Dispatchers.Default) {
        val fw = mirror.firmware(server)
        if (fw.isEmpty()) return@withContext emptyList()
        val rommPlatforms = mirror.platforms(server).associate { it.id to it.slug } + runCatching { ctx.data.database.rommQueries.platforms(server).executeAsList().associate { it.id to it.slug } }.getOrDefault(emptyMap())
        val statuses = engine.bios.value
        val systems = ctx.data.games.platformCounts().first().keys.mapNotNull(ctx::platform)
        val roots = runCatching { ctx.services.locations.biosRoots() }.getOrDefault(emptyList())
        val emulatorFolders = ctx.services.emulators.biosFolders(ctx.installed.value)
        val sources = ctx.data.sources.all()
        val chosen = settings.biosFolder.ifBlank { null }
        val existing = HashMap<String, Boolean>()
        for (p in systems) {
            for (path in BiosSearchPaths.forPlatform(p, roots, sources, emulatorFolders)) existing[path] = fileExists(path)
        }
        RommBios.needed(
            systems, { statuses[it.id] }, fw, rommPlatforms::get, ctx.platforms::resolveFolder,
            destinationFor = { p, file ->
                // The first BIOS folder for the system that is there (an emulator's own first), else the chosen one.
                val folder = chosen?.takeIf { it.isNotBlank() }
                    ?: (emulatorFolders + BiosSearchPaths.forPlatform(p, roots, sources, emulatorFolders)).firstOrNull { existing[it] == true }
                    ?: roots.firstOrNull()
                folder?.let { FsPath.join(it, file.name) }
            },
            exists = { false },
            all = all,
            installerFolder = { p ->
                // Beside the library (or the BIOS folder), in a folder of its own: never inside the emulator.
                val base = settings.libraryRoot.ifBlank { null } ?: chosen ?: roots.firstOrNull()
                base?.let { FsPath.join(FsPath.join(it, "Firmware"), p.shortName.replace(Regex("[^A-Za-z0-9 ._-]"), "")) }
            },
        ).filter { !fileExists(it.destination) }
    }

    override suspend fun downloadBios(picks: List<BiosPick>): Int {
        var n = 0
        val volumes = engine.drives.volumes.value
        for (p in picks) {
            val place = TransferPlaces.of(p.destination, volumes, ctx.now())
            transfers.enqueue(
                TransferItem(
                    id = "", key = "romm:bios:${p.firmware.id}:${p.destination}", source = ROMM_SOURCE, direction = TransferDirection.DOWNLOAD,
                    kind = TransferKind.BIOS, title = p.firmware.fileName, detail = p.why, platform = p.platform,
                    place = place, target = "${place.volume?.label ?: "This device"}  ·  ${shortPath(FsPath.parent(p.destination) ?: p.destination)}",
                    totalBytes = p.firmware.sizeBytes, payload = RommDownloadJob(server, firmware = p.firmware, install = p.install).encode(),
                ),
            )
            n++
        }
        return n
    }

    companion object {
        const val SECRET = "romm.credential"

        /** Library games not on RomM shown on the tab's shelf (the rest are counted). */
        private const val NOT_ON_SERVER_SHOWN = 60

        /** How long a burst of art arriving waits before the lists are drawn again. */
        private const val REDRAW_MS = 4_000L
        private const val NS = "romm"
        private const val PROBE_MS = 1_500L
        private const val LOOP_MS = 60_000L

        fun keyOf(romId: Long) = "romm:rom:$romId"

        fun modeOf(s: String): RouteMode = runCatching { RouteMode.valueOf(s) }.getOrDefault(RouteMode.AUTO)

        private fun reasonWords(r: MatchReason): String = when (r) {
            MatchReason.LINKED -> "Linked to RomM"
            MatchReason.HASH -> "Same file, byte for byte"
            MatchReason.TITLE_ID -> "Same title id"
            MatchReason.FILE_NAME -> "Same file name"
            MatchReason.NAME_AND_TAGS -> "Same name, region and revision"
        }

        /** The last two parts of a folder, as the person reads where something goes. */
        fun shortPath(path: String): String = FsPath.normalize(path).split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

        /** The art cache in use, for the image loader (set when Fuse RomM starts). */
        @kotlin.concurrent.Volatile var rommArtCache: RommArtCache? = null
    }
}

/**
 * RomM's pictures, fetched once with Fuse's sign-in and kept in Fuse's cache: shown offline, never
 * fetched again as the route changes, one fetch at a time per picture.
 */
internal class RommArtCache(private val ctx: StoreContext, private val clientFor: (String) -> RommClient?) {
    private val locks = HashMap<String, Mutex>()

    /** What a transfer row keeps to draw the picture again. */
    fun modelKey(server: String, path: String): String = "romm-art:$server|$path"

    fun parseKey(text: String): RommArt? = text.takeIf { it.startsWith("romm-art:") }?.removePrefix("romm-art:")?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { RommArt(it[0], it[1]) }

    /** The picture's file here, fetched when it isn't yet; null when it can't be had. */
    suspend fun file(server: String, path: String): String? {
        val art = RommArt(server, path)
        val name = "romm-art/" + art.key.hashCode().toUInt().toString(16) + "-" + art.key.length
        val lock = synchronized(locks) { locks.getOrPut(name) { Mutex() } }
        return lock.withLock {
            ctx.services.cacheFile(name) {
                val c = clientFor(server) ?: throw IllegalStateException("signed out")
                val resp = ctx.services.http.get(c.assetUrl(c.base(), path)) { c.authorize(this) }
                if (!resp.status.isSuccess()) throw IllegalStateException("${resp.status.value}")
                resp.bodyAsBytes().also { if (pictureExtension(it) == null) throw IllegalStateException("not a picture") }
            }
        }
    }
}
