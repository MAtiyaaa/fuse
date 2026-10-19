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
import io.github.matiyaaa.fuse.romm.FoundRomm
import io.github.matiyaaa.fuse.romm.RommDiscovery
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
import io.github.matiyaaa.fuse.ui.shell.store.rommOnly
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
import kotlinx.coroutines.withTimeoutOrNull
import io.github.matiyaaa.fuse.romm.RommAccess
import io.github.matiyaaa.fuse.romm.RommTask
import io.github.matiyaaa.fuse.romm.RommServers
import io.github.matiyaaa.fuse.romm.RommWrite
import io.github.matiyaaa.fuse.romm.RommWrites
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A picture RomM keeps, for Fuse's image loader: fetched with Fuse's sign-in and kept for offline. */
data class RommArt(val server: String, val path: String) {
    /** The picture itself, without RomM's cache-busting query (the same picture is the same key). */
    val key: String get() = "romm:$server:" + path.substringBefore('?')
}

/**
 * RomM in the app: one client for the server set up (Local, Remote or Auto), the library mirror
 * kept in Fuse's database, its games matched to the library's, and its transfers through Downloads.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DefaultRommOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val transfers: Transfers,
    /** Writes the settings (through the store, so the interface follows). */
    private val write: suspend ((FuseRommSettings) -> FuseRommSettings) -> Unit,
    /** Turns Cartridge off in Fuse while RomM is on (and nothing else about it). */
    private val cartridgeOff: suspend () -> Unit,
    /** Which emulator would play a game, as the game page says it. */
    private val choiceFor: suspend (Game) -> EmulatorChoice,
    /** Applies RomM's details and pictures to the games a download brought in (Cartridge's own path, shared). */
    private val details: CartridgeDetails,
    /** Has Fuse's sources find art and details for games Fuse doesn't have, first ones first. */
    private val fillRemote: (List<GameId>, Boolean) -> Unit = { _, _ -> },
) : RommOps {
    override val supported = true
    private val mirror = RommMirror(ctx.data.database, ctx::now)

    /** The server's games Fuse doesn't have, as games: their own names, details and art, kept ([RommGames]). */
    private val remote = RommGames(ctx, mirror, { server }, { redrawSoon() })

    /**
     * Art or details of games Fuse doesn't have changed. One waiting redraw covers a burst of them,
     * and a change that lands while the lists are being drawn again gets one more, so art found
     * mid-redraw shows without reopening Fuse.
     */
    private val redraws = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun redrawSoon() {
        redraws.trySend(Unit)
    }

    /** Games on the server that Fuse doesn't have, for Fuse's sources to fill, these first. */
    private fun fillUnmatched(roms: List<RommRom>) {
        val m = matches.value
        fillRemote(roms.filter { it.id !in m }.map { rommGameId(it.id) }, false)
    }
    private val _state = MutableStateFlow(RommState())
    override val state: StateFlow<RommState> = _state

    private var client: RommClient? = null

    /** The server [client] was made for ([server] then): what goes through it is that server's alone. */
    @kotlin.concurrent.Volatile private var clientServer: String? = null
    private var refreshJob: Job? = null
    private var loopJob: Job? = null
    private val connectLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** RomM game to the Fuse game it is, once matched. */
    private val matches = MutableStateFlow<Map<Long, Pair<GameId, MatchReason>>>(emptyMap())

    /** Where RomM games landed on this device (normalised path to rom id), so the library game made from one takes its art. */
    private val landedAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Library games that already took their RomM game's art this run. */
    private val adopted = HashSet<GameId>()
    /** Moves after every mirror sync, so the lists read the mirror again. */
    private val mirrorRevision = MutableStateFlow(0)

    private val settings: FuseRommSettings get() = ctx.settings.value.romm
    private val server: String get() = settings.server.ifBlank { "main" }

    // ------------------------------------------------------------------ start

    fun start() {
        ctx.addRemoteGames(remote)
        val otherDetail = ctx.remoteDetail
        ctx.remoteDetail = { id -> id.rommOnly?.let(::detail) ?: otherDetail(id) }
        val otherAdopt = ctx.adoptArt
        ctx.adoptArt = { id -> adoptArt(id) || otherAdopt(id) }
        val otherOn = ctx.remoteGamesOn
        ctx.remoteGamesOn = { id ->
            mirror.all(server).filter { r -> (ctx.platforms.resolveFolder(r.platformSlug)?.id) == id }.map { rommGameId(it.id) } + otherOn(id)
        }
        val otherIds = ctx.remoteIds
        ctx.remoteIds = { platform ->
            val m = matches.value
            (if (!settings.enabled) emptyList() else mirror.all(server).filter { r -> r.id !in m && (platform == null || ctx.platforms.resolveFolder(r.platformSlug)?.id == platform) }.map { rommGameId(it.id) }) + otherIds(platform)
        }
        ctx.scope.launch {
            for (r in redraws) {
                delay(REDRAW_MS)
                mirrorRevision.update { it + 1 }
                runCatching { publishLists() }
            }
        }
        rommArtCache = RommArtCache(ctx, this::clientFor)
        transfers.let { t -> rommHandlers(ctx.services.http, host).forEach(t::register) }
        ctx.scope.launch {
            var seen: FuseRommSettings? = null
            ctx.settings.map { it.romm }.distinctUntilChanged { a, b -> a.enabled == b.enabled && a.mode == b.mode && a.localAddress == b.localAddress && a.remoteAddress == b.remoteAddress }
                .collect { s ->
                    val before = seen
                    seen = s
                    // New addresses, wherever they were written: another server's are another key, before anything connects.
                    if (before != null && (before.localAddress != s.localAddress || before.remoteAddress != s.remoteAddress)) serverChanged(before, s)
                    if (!s.enabled) {
                        loopJob?.cancel()
                        client = null
                        clientServer = null
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

    // ------------------------------------------------------------------ for the household's games

    /** The server in use, as its transfers name it. */
    internal val serverKey: String get() = server

    internal val enabled: Boolean get() = settings.enabled

    /**
     * The RomM game that is [romId] or has one of [md5s] (a game file's), by link or by hash only,
     * never by a look-alike name: the same game, byte for byte, as another device's copy.
     */
    internal suspend fun sameOnServer(romId: Long?, md5s: Collection<String>): RommRom? {
        if (!settings.enabled) return null
        romId?.let { id -> mirror.rom(server, id)?.let { return it } }
        for (m in md5s) mirror.byMd5(m).firstOrNull()?.let { return it }
        return null
    }

    /**
     * RomM's one game on [platform] going by the same name as [title] (as the All views put names
     * side by side), for a game page's versions: another version of the game the person has here.
     * Null when RomM has none, or several. Never a match for identity: that stays [RommMatch]'s.
     */
    internal suspend fun sameNameOnServer(platform: io.github.matiyaaa.fuse.model.PlatformId, title: String): RommRom? {
        if (!settings.enabled) return null
        val key = io.github.matiyaaa.fuse.ui.shell.library.sameTitleKey(title)
        if (key.isEmpty()) return null
        val memo = "${server}|${platform.value}|$key"
        // A game page asks again every time a transfer or a device changes: the server's whole
        // list is read once a minute for it, not each time.
        sameNames[memo]?.takeIf { ctx.now() - it.first < SAME_NAME_MS }?.let { (_, id) -> return id?.let { romHere(it) } }
        val found = mirror.all(server).filter { r ->
            ctx.platforms.resolveFolder(r.platformSlug)?.id == platform &&
                (io.github.matiyaaa.fuse.ui.shell.library.sameTitleKey(r.name) == key || io.github.matiyaaa.fuse.ui.shell.library.sameTitleKey(r.fsName.substringBeforeLast('.')) == key)
        }.singleOrNull()
        if (sameNames.size > SAME_NAME_KEEP) sameNames.clear()
        sameNames[memo] = ctx.now() to found?.id
        return found
    }

    /** [sameNameOnServer]'s answers lately, by server, system and name: when, and which game (if one). */
    private val sameNames = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long?>>()

    /** RomM's game [romId] as the mirror has it (never asks the server). */
    internal suspend fun romHere(romId: Long): RommRom? = if (!settings.enabled) null else mirror.rom(server, romId)

    /** RomM's game [romId] with its files listed (asked of the server when the mirror lacks them). */
    internal suspend fun romWithFiles(romId: Long): RommRom? = mirror.withFiles(clientFor(server), server, romId)

    /** The RomM game library game [game] was matched to, if any. */
    internal fun romOf(game: GameId): Long? = matches.value.entries.firstOrNull { it.value.first == game }?.key

    /** The library game RomM's [romId] was matched to, if any. */
    internal fun gameOf(romId: Long): GameId? = matches.value[romId]?.first

    /** The system folder a game for [platform] goes in, as RomM's downloads choose it (the person's choice, else what Fuse knows). */
    internal suspend fun folderFor(platform: Platform, slug: String): Pair<String, Boolean>? {
        val s = settings
        val sources = ctx.data.sources.all().filter { it.enabled && (it.kind == LibrarySourceKind.ROMS_ROOT || it.kind == LibrarySourceKind.ROMM_LIBRARY) }
        engine.platformFolders.value[platform.id]?.firstOrNull()?.let { if (s.systemFolders[platform.id.value].isNullOrBlank()) return it to false }
        val roots = sources.map { src ->
            val base = FsPath.normalize(src.path)
            val inner = if (fileExists(FsPath.join(base, "roms"))) FsPath.join(base, "roms") else base
            RootListing(inner, runCatching { ctx.services.fs.list(inner) }.getOrDefault(emptyList()).filter { it.isDirectory }.map { it.name }, romm = src.kind == LibrarySourceKind.ROMM_LIBRARY)
        }
        return RommPlacement.systemFolder(platform, slug, roots, s.systemFolders, s.libraryRoot.ifBlank { null }, ctx.platforms::resolveFolder)
    }

    /** The client for [server] as set up now (the one in use), or null when off or signed out. */
    internal fun clientFor(server: String): RommClient? = client?.takeIf { server == this.server && clientServer == server && settings.enabled }

    /** How a game's save is found here, without a profile (set by the store from the library). */
    @kotlin.concurrent.Volatile internal var plainSaveQuery: suspend (GameId) -> io.github.matiyaaa.fuse.sync.SaveQuery? = { null }

    /** RomM's save sync on this device, when it runs (set by the store). */
    @kotlin.concurrent.Volatile internal var saveService: io.github.matiyaaa.fuse.sync.romm.RommSaveService? = null

    override suspend fun saveReport(): io.github.matiyaaa.fuse.ui.shell.store.RommSaveReport? = withContext(Dispatchers.Default) {
        val c = saveClient() ?: return@withContext null
        val saves = c.allSaves()
        val roms = mirror.all(server).associateBy { it.id }
        val linked = matches.value
        val games = saves.groupBy { it.romId }.map { (romId, list) ->
            val rom = roms[romId]
            val game = linked[romId]?.first
            val newest = list.first()
            val standing = game?.let { id -> plainSaveQuery(id) }?.let { q -> runCatching { saveService?.standing(q, romId, newest) }.getOrNull() }
                ?: io.github.matiyaaa.fuse.sync.romm.RommSaveStanding.NOT_HERE.takeIf { game == null }
                ?: io.github.matiyaaa.fuse.sync.romm.RommSaveStanding.UNKNOWN
            io.github.matiyaaa.fuse.ui.shell.store.RommSaveGame(
                romId = romId,
                title = rom?.name?.ifBlank { null } ?: newest.fileName.substringBeforeLast('.').substringBefore(" ["),
                game = game,
                platform = rom?.let { ctx.platforms.resolveFolder(it.platformSlug)?.id },
                saves = list,
                standing = standing,
            )
        }.sortedByDescending { it.newest.updatedAt }
        io.github.matiyaaa.fuse.ui.shell.store.RommSaveReport(server, games, saves.sumOf { it.sizeBytes }, waiting = saveService?.waiting ?: 0)
    }

    override suspend fun sendWaitingSaves(): Int = saveService?.let { s -> runCatching { s.sendWaiting() }.getOrDefault(0) } ?: 0

    /** The signed-in client when RomM and its save sync are on: the client of the server [serverKey] names, never one before it. */
    internal fun saveClient(): RommClient? = client?.takeIf { c ->
        clientServer == server && settings.enabled && settings.saveSync && settings.configured &&
            c.may(RommScopes.ASSETS_WRITE) && c.may(RommScopes.DEVICES_WRITE) && c.capabilities.saveSync
    }

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
            runCatching { ctx.services.http.get("$base/api/heartbeat") { RommClient.gate(access, this) }.status.isSuccess() }.getOrDefault(false)
        } ?: false
    }

    private suspend fun connect() = connectLock.withLock {
        val s = settings
        if (s.localAddress.isBlank() && s.remoteAddress.isBlank()) {
            _state.value = RommState(RommLink.NOT_SET_UP)
            return@withLock
        }
        val cred = credential()
        access = loadAccess()
        val c = RommClient(ctx.services.http, routes(s), cred, ctx.services.appVersion, access)
        client = c
        clientServer = server
        // The mirror shows at once, whatever the server says.
        _state.value = _state.value.copy(
            link = RommLink.CONNECTING, syncedAt = mirror.syncedAt(server), games = mirror.count(server),
            accessHost = access?.host.orEmpty(), changesWaiting = changesLock.withLock { loadChanges().size },
        )
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
                    canScan = c.may(RommScopes.TASKS_RUN) && c.capabilities.tokenScans,
                    canSyncSaves = c.may(RommScopes.ASSETS_WRITE) && c.may(RommScopes.DEVICES_WRITE) && c.capabilities.saveSync,
                    canSendChanges = mayTakeChanges(c),
                    problem = null,
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
            // Fuseline 4.2: the first refresh waits for the first screen to settle.
            io.github.matiyaaa.fuse.ui.designsystem.effects.StartupGate.settled()
            while (true) {
                // Asleep, RomM isn't asked anything (an unreachable one was knocked on every minute all night).
                ctx.services.awake.first { it }
                val link = _state.value.link
                if (link == RommLink.ONLINE) {
                    if (first || ctx.now() - mirror.syncedAt(server) >= settings.refreshMinutes.coerceIn(5, 1440) * 60_000L) syncNow(full = false)
                    // Saves that couldn't go while RomM was away go now, in the order they waited.
                    saveService?.takeIf { it.waiting > 0 }?.let { s -> runCatching { s.sendWaiting() } }
                    // So do the person's changes to its games.
                    if (settings.sendChanges && _state.value.changesWaiting > 0) runCatching { sendChanges() }
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

    // ------------------------------------------------------------------ Cloudflare Access

    /** The Cloudflare Access service token for the outside address's host, when one is kept. */
    @kotlin.concurrent.Volatile private var access: RommAccess? = null

    private suspend fun loadAccess(): RommAccess? = ctx.services.secrets.get(ACCESS_SECRET)?.let {
        runCatching { json.decodeFromString(RommAccess.serializer(), it) }.getOrNull()
    }

    override suspend fun setAccess(clientId: String, clientSecret: String): Result<String> = runCatching {
        val remote = settings.remoteAddress.trim()
        val host = RommAccess.hostOf(remote)
        if (!remote.startsWith("https://", ignoreCase = true) || host.isBlank()) {
            throw RommException("Add the outside address first, starting with https://. The token only ever goes there.")
        }
        if (clientId.isBlank() || clientSecret.isBlank()) throw RommException("Both the Client ID and the Client Secret are needed.")
        val a = RommAccess(host, clientId.trim(), clientSecret.trim())
        ctx.services.secrets.put(ACCESS_SECRET, json.encodeToString(RommAccess.serializer(), a))
        access = a
        client?.access = a
        _state.update { it.copy(accessHost = host) }
        connect()
        host
    }

    override suspend fun removeAccess() {
        ctx.services.secrets.remove(ACCESS_SECRET)
        access = null
        client?.access = null
        _state.update { it.copy(accessHost = "") }
    }

    // ------------------------------------------------------------------ RomM on this computer

    override suspend fun homeServer(): io.github.matiyaaa.fuse.ui.shell.store.RommHomeView? {
        val h = ctx.services.rommHome ?: return null
        val e = h.engine() ?: return null
        val spec = h.current()
        return io.github.matiyaaa.fuse.ui.shell.store.RommHomeView(e.label, spec, spec != null && h.running())
    }

    override suspend fun startHome(library: String, port: Int, thisComputerOnly: Boolean): Result<String> = runCatching {
        val h = ctx.services.rommHome ?: throw RommException("RomM can't be set up on this device.")
        val spec = io.github.matiyaaa.fuse.romm.RommHome.Spec(library, ctx.services.dataDir.trimEnd('/', '\\') + "/romm-home", port, thisComputerOnly)
        h.start(spec).getOrThrow()
        val address = io.github.matiyaaa.fuse.romm.RommHome.address(spec)
        // Ready once it answers: its database comes up first.
        val until = ctx.now() + HOME_READY_MS
        while (!quickProbe(address)) {
            if (ctx.now() > until) throw RommException("RomM started but doesn't answer at $address yet. Give it a minute, then Test Connection.")
            delay(HOME_LOOK_MS)
        }
        setAddresses(address, settings.remoteAddress, RouteMode.AUTO)
        address
    }

    override suspend fun stopHome(): Result<Unit> {
        val h = ctx.services.rommHome ?: return Result.failure(RommException("RomM can't be set up on this device."))
        return h.stop()
    }

    // ------------------------------------------------------------------ the person's changes

    /**
     * Changes waiting to go to RomM, in the order made ([RommWrites]), and the server they were made
     * for: each server's own, kept under its key, read from Fuse's database once.
     */
    private var changes: Pair<String, List<RommWrite>>? = null
    private val changesLock = Mutex()
    private val changesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One send at a time: two at once would both take the same changes and send them twice. */
    private val sendLock = Mutex()

    /** Under [changesLock]: what waits for server [key]. */
    private suspend fun loadChanges(key: String = server): List<RommWrite> = changes?.takeIf { it.first == key }?.second ?: (
        ctx.data.cache.getOrNull(NS, "changes:$key", ctx.now())?.let {
            runCatching { changesJson.decodeFromString(ListSerializer(RommWrite.serializer()), it) }.getOrNull()
        } ?: emptyList()
        ).also { changes = key to it }

    /** Under [changesLock]: [list] is what waits for server [key] now. */
    private suspend fun keepChanges(list: List<RommWrite>, key: String = server) {
        changes = key to list
        ctx.data.cache.put(NS, "changes:$key", changesJson.encodeToString(ListSerializer(RommWrite.serializer()), list), ctx.now(), ttlMs = null)
        if (key == server) _state.update { it.copy(changesWaiting = list.size) }
    }

    /**
     * The addresses went from [before]'s to [now]'s. When they are another server's ([RommServers]),
     * it gets its own key, and what Fuse held for the one before is let go: its games' matches, where
     * its downloads landed, its favourites collection, and the library's links to its games (they are
     * found again on this server by their files). Nothing of one server is sent to or taken for
     * another; what waits for the one before stays kept under its key, and goes if it is set up again.
     */
    private suspend fun serverChanged(before: FuseRommSettings, now: FuseRommSettings) {
        val current = now.server.ifBlank { RommServers.FIRST }
        val (key, known) = RommServers.after(current, listOf(before.localAddress, before.remoteAddress), listOf(now.localAddress, now.remoteAddress), now.servers)
        if (key == current && known == now.servers) return
        write { it.copy(server = key, servers = known) }
        if (key == current) return
        loopJob?.cancel()
        client = null
        clientServer = null
        favourites = null
        sameNames.clear()
        landedAt.value = emptyMap()
        matchLock.withLock {
            matches.value = emptyMap()
            for (row in ctx.data.games.matchRows()) if (row.rommRomId != null) ctx.data.games.updateLinks(row.id) { it.copy(rommRomId = null) }
        }
        changesLock.withLock { changes = null }
    }

    /** The server takes changes from apps (RomM 5.4) and the sign-in may send them. */
    private fun mayTakeChanges(c: RommClient): Boolean =
        c.may(RommScopes.ROMS_USER_WRITE) && c.may(RommScopes.COLLECTIONS_WRITE) &&
            (c.capabilities.userProps || c.capabilities.collectionRoms || c.capabilities.playSessions)

    /**
     * The person changed [game] in the library: when it is a game from RomM and they chose to send
     * their changes, [make]'s change waits to go (and goes shortly, when RomM answers).
     */
    internal fun changed(game: GameId, make: (Long) -> RommWrite) {
        if (!settings.enabled || !settings.sendChanges) return
        // The server in use when the change was made is the one it is for.
        val key = server
        ctx.scope.launch {
            val romId = romOf(game) ?: ctx.data.games.get(game)?.links?.rommRomId ?: return@launch
            // Another server was set up meanwhile: that game's id is the one before's, never kept for this one.
            if (server != key) return@launch
            changesLock.withLock { keepChanges(RommWrites.add(loadChanges(key), make(romId)), key) }
            sendSoon()
        }
    }

    private var sendJob: Job? = null

    private fun sendSoon() {
        if (sendJob?.isActive == true) return
        sendJob = ctx.scope.launch {
            delay(SEND_AFTER_MS)
            runCatching { sendChanges() }
        }
    }

    override suspend fun setSendChanges(on: Boolean): Result<Unit> = runCatching {
        val c = client
        if (on && c != null && _state.value.link == RommLink.ONLINE && !mayTakeChanges(c)) {
            throw RommException(
                if (c.capabilities.userProps || c.capabilities.collectionRoms || c.capabilities.playSessions) "Your RomM sign-in may not send changes. Pair with RomM again to allow it."
                else "This RomM server doesn't take changes from apps. RomM 5.4 and later do.",
            )
        }
        write { it.copy(sendChanges = on) }
        // Off: what waited is forgotten, for every server, so nothing goes later that the person no longer wants sent.
        if (!on) changesLock.withLock { for (key in settings.servers.values.toSet() + server) keepChanges(emptyList(), key) } else sendSoon()
    }

    override suspend fun sendChangesNow(): Int = runCatching { sendChanges() }.getOrDefault(0)

    /** RomM's favourites collection, and the server it is on, once found or made. */
    private var favourites: Pair<String, Long>? = null

    private suspend fun favouritesOf(c: RommClient, key: String): Long =
        favourites?.takeIf { it.first == key }?.second ?: c.favourites().also { favourites = key to it }

    /**
     * Sends what waits for the server in use through its own client, in order, while RomM takes it;
     * how many went. A change RomM can't take now (away, busy, the sign-in not allowed) stays, and so
     * does everything after it; one it never will (the game gone from RomM) is let go, and never
     * counted as sent. A change the server has no part for, as far as Fuse knows (its parts unread,
     * an older RomM), is never sent and stays waiting. One send runs at a time.
     */
    private suspend fun sendChanges(): Int = sendLock.withLock {
        val c = client ?: return@withLock 0
        val key = clientServer ?: return@withLock 0
        if (key != server || !settings.sendChanges || _state.value.link != RommLink.ONLINE || !mayTakeChanges(c)) return@withLock 0
        var sent = 0
        while (true) {
            val batch = changesLock.withLock { RommWrites.next(loadChanges(key)) { takes(c, it) } }
            if (batch.isEmpty()) break
            var went = false
            val failed = try {
                went = send(c, batch, key)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RommException) {
                e
            }
            if (failed != null && (failed.forbidden || failed.status == 0 || failed.status == 429 || failed.status >= 500)) {
                if (failed.forbidden) _state.update { it.copy(problem = "Your RomM sign-in may not send changes. Pair with RomM again to allow it.") }
                break
            }
            changesLock.withLock { keepChanges(RommWrites.without(loadChanges(key), batch), key) }
            if (went) sent += batch.size
        }
        sent
    }

    /** Whether [c]'s server has the part of RomM [w] goes to ([RommClient.detect]). */
    private fun takes(c: RommClient, w: RommWrite): Boolean = when (w) {
        is RommWrite.Played -> c.capabilities.playSessions
        is RommWrite.Hidden -> c.capabilities.userProps
        is RommWrite.Trophies -> c.capabilities.notes
        is RommWrite.Favourite -> c.capabilities.collectionRoms
    }

    /**
     * [batch] to RomM, server [key] (one [takes]): a run of play sessions together, anything else
     * alone. True when it went; false when there was nothing to send (RomM's trophy note already had it).
     */
    private suspend fun send(c: RommClient, batch: List<RommWrite>, key: String): Boolean = when (val w = batch.first()) {
        is RommWrite.Played -> true.also { c.playSessions(batch.filterIsInstance<RommWrite.Played>().map { it.session }) }
        is RommWrite.Hidden -> true.also { c.setHidden(w.romId, w.on) }
        is RommWrite.Trophies -> trophyNote(c, w.romId, w.game)
        is RommWrite.Favourite -> true.also {
            try {
                c.collectionRoms(favouritesOf(c, key), listOf(w.romId), into = w.on)
            } catch (e: RommException) {
                // The favourites collection went (deleted in RomM): found or made again, once.
                if (e.status != 404) throw e
                favourites = null
                c.collectionRoms(favouritesOf(c, key), listOf(w.romId), into = w.on)
            }
        }
    }

    // ------------------------------------------------------------------ trophies

    /** When each game's trophy note was last read, so a page opened again soon doesn't ask again. */
    private val trophyLooks = HashMap<Long, Long>()
    private val trophyLock = Mutex()

    /**
     * Trophies across the person's devices with RomM and no Fuse One host: [game]'s unlocks in the
     * person's own note on it ([TrophyNote]) join its history here, and, when they chose to send
     * their changes, what was earned here is added to the note. Only for a game from RomM, on a
     * server that keeps notes. [news]: something was earned here just now, so the note is read
     * even when it was a moment ago, and the change waits for RomM when it is away.
     */
    internal suspend fun shareTrophies(game: GameId, news: Boolean) {
        if (!settings.enabled) return
        val romId = romOf(game) ?: ctx.data.games.get(game)?.links?.rommRomId ?: return
        val c = clientFor(server)
        if (c == null || _state.value.link != RommLink.ONLINE) {
            if (news) changed(game) { RommWrite.Trophies(it, game.value) }
            return
        }
        if (!c.capabilities.notes) return
        val now = ctx.now()
        val look = trophyLock.withLock {
            val last = trophyLooks[romId]
            if (!news && last != null && now - last < TROPHY_LOOK_MS) false else true.also { trophyLooks[romId] = now }
        }
        if (!look) return
        try {
            trophyNote(c, romId, game.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RommException) {
            if (news) changed(game) { RommWrite.Trophies(it, game.value) }
        }
    }

    /**
     * Reads the person's trophy note on [romId] into library game [game]'s history, then adds to
     * it what the history has that the note doesn't (or has later), when changes may go to RomM.
     * The note is made the first time there is something to put in it. True when something was
     * written to RomM; false when the note already had it all (or changes may not go).
     */
    private suspend fun trophyNote(c: RommClient, romId: Long, game: Long): Boolean {
        val me = _state.value.account
        suspend fun mine() = c.notes(romId).firstOrNull { it.title == TrophyNote.TITLE && (me.isBlank() || it.username.equals(me, ignoreCase = true)) }
        val note = mine()
        val there = note?.let { TrophyNote.read(it.content) }.orEmpty()
        TrophyHistory.add(ctx.data, game, there)
        if (!settings.sendChanges || !mayTakeChanges(c)) return false
        val id = ThisDevice.id(ctx)
        val ours = TrophyHistory.read(ctx.data, game).map { if (it.device == ThisDevice.LEGACY) it.copy(device = id) else it }
        val before = TrophyNote.merge(there, emptyList())
        val after = TrophyNote.merge(there, ours)
        if (after.isEmpty() || (note != null && after == before)) return false
        if (note != null) {
            c.updateNote(romId, note.id, TrophyNote.write(after.values))
            return true
        }
        try {
            c.addNote(romId, TrophyNote.TITLE, TrophyNote.write(after.values), listOf(TrophyNote.TAG))
        } catch (e: RommException) {
            // Made meanwhile by another device (RomM keeps one of a title per person and game): added to.
            val made = mine() ?: throw e
            val all = TrophyNote.merge(TrophyNote.read(made.content), after.values)
            TrophyHistory.add(ctx.data, game, all.values)
            c.updateNote(romId, made.id, TrophyNote.write(all.values))
        }
        return true
    }

    // ------------------------------------------------------------------ scans

    private var scanJob: Job? = null

    /** Follows scan [taskId] while RomM runs it: its counts on the RomM tab, and a word when it ends. */
    private fun followScan(taskId: String) {
        val c = client ?: return
        if (!c.capabilities.taskStatus) return
        scanJob?.cancel()
        scanJob = ctx.scope.launch {
            val until = ctx.now() + SCAN_LOOK_FOR_MS
            var last: RommTask? = null
            _state.update { it.copy(scan = io.github.matiyaaa.fuse.romm.RommScanStats()) }
            try {
                while (ctx.now() < until) {
                    val t = runCatching { c.task(taskId) }.getOrNull()
                    if (t != null) {
                        last = t
                        _state.update { it.copy(scan = t.scan ?: io.github.matiyaaa.fuse.romm.RommScanStats()) }
                        if (t.done) break
                    }
                    delay(SCAN_LOOK_MS)
                }
            } finally {
                _state.update { it.copy(scan = null) }
            }
            val t = last ?: return@launch
            if (!t.done) return@launch
            val found = t.scan?.newRoms ?: 0
            noticeFlow.tryEmit(
                when {
                    t.failed -> "RomM's scan stopped before the end"
                    found == 1 -> "RomM's scan found 1 new game"
                    found > 1 -> "RomM's scan found $found new games"
                    else -> "RomM's scan is done"
                },
            )
            refresh()
        }
    }

    // ------------------------------------------------------------------ set-up and sign-in

    override suspend fun test(local: String, remote: String, mode: RouteMode): RommTest = withContext(Dispatchers.Default) {
        suspend fun at(address: String): Pair<Boolean, String?> {
            if (address.isBlank()) return false to null
            val base = RoutePicker.clean(address)
            val c = RommClient(ctx.services.http, RoutePicker(base, null, RouteMode.LOCAL, ctx.scope, ctx::now, { true }), null, access = access ?: loadAccess())
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

    override suspend fun discover(): List<FoundRomm> = withContext(Dispatchers.Default) {
        val all = ctx.settings.value
        val hints = listOf(settings.localAddress, all.jellyfin.localAddress, all.sync.localAddress)
        RommDiscovery.find(hints) { address ->
            val c = RommClient(ctx.services.http, RoutePicker(address, null, RouteMode.LOCAL, ctx.scope, ctx::now, { true }), null)
            // Only a heartbeat that says RomM's version is RomM; anything else listening there is passed over.
            withTimeoutOrNull(DISCOVER_ASK_MS) { runCatching { c.heartbeat() }.getOrNull() }?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun setAddresses(local: String, remote: String, mode: RouteMode) {
        write { it.copy(localAddress = local.trim(), remoteAddress = remote.trim(), mode = mode.name, enabled = true) }
        cartridgeOff()
    }

    override suspend fun startPairing(upload: Boolean): Result<RommDeviceCode> = runCatching {
        val c = client ?: RommClient(ctx.services.http, routes(settings), null, ctx.services.appVersion, access).also { client = it; clientServer = server }
        if (c.capabilities.version.isEmpty()) c.detect()
        if (!c.capabilities.deviceAuth) throw RommException("This RomM server doesn't pair this way. Use a pairing code or a token from RomM instead.", code = "no-device-auth")
        c.startPairing(deviceId(), ctx.services.deviceName, ctx.host.name.lowercase(), (if (upload) RommScopes.UPLOAD_AND_SCAN else RommScopes.READ) + RommScopes.SAVES + RommScopes.CHANGES)
    }

    override suspend fun awaitPairing(code: RommDeviceCode): Result<String> = runCatching {
        val c = client ?: throw RommException("RomM isn't set up.")
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
        val c = client ?: RommClient(ctx.services.http, routes(settings), null, access = access).also { client = it; clientServer = server }
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
        val c = RommClient(ctx.services.http, routes(settings), credential, ctx.services.appVersion, access)
        c.detect()
        val account = c.me()
        ctx.services.secrets.put(SECRET, json.encodeToString(RommCredential.serializer(), credential))
        write { it.copy(enabled = true, configured = true, account = account, serverVersion = c.capabilities.version) }
        cartridgeOff()
        connect()
        return account
    }

    /**
     * The household's RomM (Fuse Sync): its addresses where this device has none, and its sign-in
     * where this device was never signed in to that server. A device whose person chose Cartridge in
     * setup keeps it and only gets the addresses. True when anything changed.
     */
    internal suspend fun fromHousehold(local: String, remote: String, mode: String, credential: String?): Boolean {
        val s = settings
        val blank = s.localAddress.isBlank() && s.remoteAddress.isBlank()
        val same = !blank && s.localAddress == local && s.remoteAddress == remote
        var changed = false
        if (blank && (local.isNotBlank() || remote.isNotBlank())) {
            val keepCartridge = ctx.settings.value.onboarding.romm == "CARTRIDGE" && ctx.settings.value.cartridge.enabled
            write { it.copy(localAddress = local, remoteAddress = remote, mode = mode, enabled = it.enabled || !keepCartridge) }
            if (!keepCartridge) cartridgeOff()
            changed = true
        }
        val readable = credential?.takeIf { runCatching { json.decodeFromString(RommCredential.serializer(), it) }.isSuccess }
        if (readable != null && (blank || same) && !s.configured && credential() == null) {
            ctx.services.secrets.put(SECRET, readable)
            changed = true
            if (settings.enabled) connect()
        }
        return changed
    }

    /** What this device would share with its household: its addresses and mode, and its sign-in. */
    internal suspend fun forHousehold(): Pair<FuseRommSettings, String?>? {
        val s = settings
        if (s.localAddress.isBlank() && s.remoteAddress.isBlank()) return null
        return s to ctx.services.secrets.get(SECRET)
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

    /** [r] for RomM's matching, with every serial and hash Fuse Identity knows for it. */
    private fun localGame(r: io.github.matiyaaa.fuse.data.repo.GameMatchRow, facts: List<io.github.matiyaaa.fuse.data.repo.IdentityFact>): LocalGame {
        val ids = io.github.matiyaaa.fuse.library.identity.Ids
        val serials = facts.filter { it.kind == io.github.matiyaaa.fuse.data.repo.IdentityRepository.SERIAL_CONTENT }.mapNotNull { ids.serialOrNull(it.value) }
            .map { io.github.matiyaaa.fuse.library.identity.SerialId(it, fromContent = true) } +
            listOfNotNull(ids.serialOrNull(r.serial)?.let { io.github.matiyaaa.fuse.library.identity.SerialId(it) })
        val hashes = facts.mapNotNull { f ->
            val kind = when (f.kind) {
                io.github.matiyaaa.fuse.data.repo.IdentityRepository.MD5 -> io.github.matiyaaa.fuse.library.identity.HashKind.MD5
                io.github.matiyaaa.fuse.data.repo.IdentityRepository.SHA1 -> io.github.matiyaaa.fuse.library.identity.HashKind.SHA1
                io.github.matiyaaa.fuse.data.repo.IdentityRepository.CRC32 -> io.github.matiyaaa.fuse.library.identity.HashKind.CRC32
                else -> null
            }
            kind?.let { io.github.matiyaaa.fuse.library.identity.ContentHash.of(it, f.value) }
        }.toSet()
        return LocalGame(r.id.value, r.platform, r.title, FsPath.name(r.path), r.serial, null, r.rommRomId, r.missing, serials = serials, hashes = hashes)
    }

    /**
     * Hashes the library files left unmatched whose size is exactly that of an unmatched RomM file
     * with an MD5 on the same system, and keeps the hashes. Nothing else is ever read: a library is
     * never hashed as a whole. True when a hash was added.
     */
    private suspend fun hashSameSize(
        rows: List<io.github.matiyaaa.fuse.data.repo.GameMatchRow>,
        roms: List<RommRom>,
        result: List<io.github.matiyaaa.fuse.romm.RommMatchResult>,
        platformOf: (String) -> String?,
    ): Boolean {
        val matchedRoms = result.map { it.romId }.toSet()
        val matchedGames = result.map { it.gameId }.toSet()
        val wanted = roms.filter { it.id !in matchedRoms }.flatMap { r ->
            val platform = platformOf(r.platformSlug) ?: return@flatMap emptyList()
            r.files.filter { !it.md5.isNullOrBlank() && it.sizeBytes in 1..MAX_HASHED_BYTES }.map { platform to it.sizeBytes }
        }.toSet()
        if (wanted.isEmpty()) return false
        val known = runCatching { ctx.data.identity.all() }.getOrDefault(emptyMap())
        var added = false
        for (r in rows) {
            if (r.missing || r.id.value in matchedGames) continue
            if (known[r.id.value].orEmpty().any { it.kind == io.github.matiyaaa.fuse.data.repo.IdentityRepository.MD5 }) continue
            val stat = runCatching { ctx.services.fs.stat(r.path) }.getOrNull() ?: continue
            if ((r.platform to stat.sizeBytes) !in wanted) continue
            val md5 = md5Of(r.path, stat.sizeBytes) ?: continue
            runCatching { ctx.data.identity.record(r.id, io.github.matiyaaa.fuse.data.repo.IdentityRepository.MD5, md5, "romm") }
            added = true
        }
        return added
    }

    /** The MD5 of the file at [path], read in blocks. */
    private suspend fun md5Of(path: String, size: Long): String? = runCatching {
        val md5 = io.github.matiyaaa.fuse.integrations.Md5()
        var at = 0L
        while (at < size) {
            kotlinx.coroutines.yield()
            val block = ctx.services.fs.readBytes(path, at, minOf(HASH_BLOCK, size - at).toInt()) ?: return null
            if (block.isEmpty()) break
            md5.update(block)
            at += block.size
        }
        if (at != size) null else md5.digestHex()
    }.getOrNull()

    private val matchLock = Mutex()

    /** Joins the mirror's games to the library's, and links strong matches so they stay joined. */
    private suspend fun rematch() = matchLock.withLock {
        withContext(Dispatchers.Default) {
            val rows = ctx.data.games.matchRows()
            val roms = mirror.all(server)
            val platformOf: (String) -> String? = { slug -> ctx.platforms.resolveFolder(slug)?.id?.value }
            val vetoed = runCatching { ctx.data.identity.vetoes(VETO_KIND) }.getOrDefault(emptySet())
                .mapNotNull { (rom, game) -> rom.toLongOrNull()?.let { it to game } }.toSet()
            suspend fun locals(): List<LocalGame> {
                val facts = runCatching { ctx.data.identity.all() }.getOrDefault(emptyMap())
                return rows.map { r -> localGame(r, facts[r.id.value].orEmpty()) }
            }
            var result = RommMatch.match(roms, locals(), vetoed, platformOf)
            // Same-size files: hash them once (only those), so a renamed copy is still found by its content.
            if (hashSameSize(rows, roms, result, platformOf)) result = RommMatch.match(roms, locals(), vetoed, platformOf)
            val before = matches.value
            // Linked from now on: the game page and Cartridge's Open both know it.
            val byId = rows.associateBy { it.id.value }
            for (m in result) {
                val row = byId[m.gameId] ?: continue
                if (row.rommRomId == null && m.reason != MatchReason.NAME_AND_TAGS) ctx.data.games.updateLinks(GameId(m.gameId)) { it.copy(rommRomId = m.romId) }
            }
            matches.value = result.associate { it.romId to (GameId(it.gameId) to it.reason) }
            // Newly joined (after linking, so the page never shows it unlinked): the library game keeps the art Fuse already found for the RomM game.
            for (m in result) if (before[m.romId]?.first?.value != m.gameId) runCatching { adoptArt(GameId(m.gameId), m.romId) }
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
        ctx.shownPlatforms.update { it + _systems.value.mapNotNull { s -> s.platform } }
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

    override fun notOnServerOn(platform: io.github.matiyaaa.fuse.model.PlatformId): Flow<List<GameCard>> = combine(matches, mirrorRevision, ctx.data.games.observeByPlatform(platform)) { m, _, games -> m to games }
        .mapLatest { (m, games) ->
            if (!settings.enabled || mirror.syncedAt(server) <= 0L) return@mapLatest emptyList()
            val matched = m.values.mapTo(HashSet()) { it.first }
            val missing = games.filter { !it.isApp && !it.missing && !it.removed && it.id !in matched }.sortedBy { it.sortKey }
            val media = if (missing.isEmpty()) emptyMap() else ctx.data.media.observeFor(missing.map { MediaOwner.OfGame(it.id) }).first()
            missing.map { ctx.summaryToCard(it, media[MediaOwner.OfGame(it.id)]) }
        }.flowOn(Dispatchers.Default)

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
            customTitle = record?.titleCustom != null,
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
        fillRemote(listOf(rommGameId(romId)), true)
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

    override fun libraryGame(romId: Long): Flow<GameId?> = matches.map { it[romId]?.first }.distinctUntilChanged()

    /**
     * Gives library game [id] the art found for the RomM game it came from ([romId], or the one
     * downloaded to its path): every kind it has none of, so nothing is looked for twice.
     */
    private suspend fun adoptArt(id: GameId, romId: Long? = null): Boolean {
        if (!synchronized(adopted) { adopted.add(id) }) return false
        val rom = romId ?: matches.value.entries.firstOrNull { it.value.first == id }?.key ?: run {
            val path = ctx.data.games.get(id)?.location?.path?.let(FsPath::normalize) ?: return false
            landedAt.value.entries.firstOrNull { (at, _) -> path == at || FsPath.isWithin(path, at) }?.value
        }
        if (rom == null) {
            synchronized(adopted) { adopted.remove(id) }
            return false
        }
        var n = ctx.data.media.copyMissing(MediaOwner.OfGame(rommGameId(rom)), MediaOwner.OfGame(id))
        // RomM's own pictures, which its tile showed where Fuse's sources had found nothing: they
        // come along as files kept here, or the game it became shows its initials instead of its cover.
        n += runCatching { adoptServerArt(id, rom) }.getOrDefault(0)
        if (n > 0) ctx.gameArtFound.update { it + 1 }
        // Its details too (year, genres, developer, summary, RomM's name for it): the page showed
        // them while the game was on RomM and lost them the moment it was downloaded. Only blanks
        // are filled; nothing the person or a source already gave the game is replaced.
        val details = runCatching { remote.get(rommGameId(rom)) }.getOrNull()
        if (details != null && details.metadata != io.github.matiyaaa.fuse.model.GameMetadata()) {
            runCatching { ctx.data.games.applyMetadata(id, details.metadata, titleFromMetadata = details.titles.metadata, onlyFillEmpty = true) }
        }
        return n > 0
    }

    /** RomM's cover, logo and screenshot for [rom], kept for library game [id] where it has none of that kind. */
    private suspend fun adoptServerArt(id: GameId, rom: Long): Int {
        val r = mirror.rom(server, rom) ?: return 0
        val owner = MediaOwner.OfGame(id)
        var added = 0
        for ((kind, path) in listOf(io.github.matiyaaa.fuse.model.MediaKind.BOXART to r.cover, io.github.matiyaaa.fuse.model.MediaKind.LOGO to r.logo, io.github.matiyaaa.fuse.model.MediaKind.SCREENSHOT to r.screenshot)) {
            if (path.isNullOrBlank()) continue
            val file = rommArtCache?.file(server, path) ?: continue
            added += ctx.data.media.putScraped(
                owner,
                listOf(io.github.matiyaaa.fuse.model.MediaItem(kind, io.github.matiyaaa.fuse.model.MediaSource.ROMM, localPath = file)),
                io.github.matiyaaa.fuse.model.MediaFillMode.FILL_MISSING,
            )
        }
        return added
    }

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
        if (!settings.enabled) return@withContext "Turn on RomM in Settings, Addons, RomM."
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
            ?: return@withContext "Choose where games go first: Settings, Addons, RomM, Library."
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

    /** RomM's transfers ask the app for these. */
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
            // The game made from these files takes what was already found for the RomM game.
            // Only the game's own file or folder, never a system folder other games share.
            val landed = if (job.newFolder || r.multi) gamePath else paths.firstOrNull()
            if (landed != null) landedAt.update { it + (FsPath.normalize(landed) to r.id) }
            if (settings.scanAfterDownload) {
                engine.rescan(ScanScope.PLATFORM, ctx.platforms.resolveFolder(r.platformSlug)?.id)
                // Once indexed: linked to RomM, with RomM's details and pictures.
                withContext(Dispatchers.Default) {
                    kotlinx.coroutines.withTimeoutOrNull(120_000) {
                        engine.scan.first { it.phase == io.github.matiyaaa.fuse.model.ScanPhase.DONE || it.phase == io.github.matiyaaa.fuse.model.ScanPhase.FAILED }
                    }
                    val lead = paths.firstOrNull()
                    val target = if (job.newFolder || r.multi) gamePath else lead ?: gamePath
                    // A picture that can't be had leaves the game without it, never the download failed.
                    suspend fun picture(path: String?): String? = path?.let { p -> runCatching { rommArtCache?.file(job.server, p) }.getOrNull() }
                    val cover = picture(r.cover)
                    val logo = picture(r.logo)
                    val shot = picture(r.screenshot)
                    details.sync(
                        listOf(CartridgeGame(r.id, target, r.name, r.platformSlug, r.summary, r.year, r.genres, r.developer, cover = cover, logo = logo, screenshot = shot, updatedAt = r.updatedAt)),
                        applyDetails = true,
                    )
                    rematch()
                    publishLists()
                }
            }
        }

        override fun scanStarted(taskId: String) = followScan(taskId)

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
        val files = runCatching { UploadFiles.collect(ctx.services.fs, g.location, g.discs, g.content) }.getOrDefault(emptyList())
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
        val files = UploadFiles.collect(ctx.services.fs, g.location, g.discs, g.content)
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
        /** How long [sameNameOnServer] keeps an answer. */
        const val SAME_NAME_MS = 60_000L
        private const val SAME_NAME_KEEP = 500
        const val SECRET = "romm.credential"

        /** Where the Cloudflare Access service token is kept ([RommAccess]). */
        const val ACCESS_SECRET = "romm.access"

        /** How long RomM on this computer is given to answer once started, and how often it is asked. */
        private const val HOME_READY_MS = 5 * 60_000L
        private const val HOME_LOOK_MS = 3_000L

        /** How long after a change it goes, so a burst of them goes together. */
        private const val SEND_AFTER_MS = 3_000L

        /** How long a game's trophy note, once read, isn't read again for its page. */
        private const val TROPHY_LOOK_MS = 10 * 60_000L

        /** How often a scan RomM runs is looked at, and for how long at most. */
        private const val SCAN_LOOK_MS = 2_000L
        private const val SCAN_LOOK_FOR_MS = 30 * 60_000L

        /** Fuse Identity's kind for "Not this game" on RomM. */
        const val VETO_KIND = "romm"

        /** Files above this size are never hashed for matching (a disc image is found by its serial). */
        private const val MAX_HASHED_BYTES = 512L * 1024 * 1024
        private const val HASH_BLOCK = 4L * 1024 * 1024

        /** Library games not on RomM shown on the tab's shelf (the rest are counted). */
        private const val NOT_ON_SERVER_SHOWN = 60

        /** How long a burst of art arriving waits before the lists are drawn again. */
        private const val REDRAW_MS = 4_000L
        private const val DISCOVER_ASK_MS = 3_000L
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

        /** The art cache in use, for the image loader (set when RomM starts). */
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
