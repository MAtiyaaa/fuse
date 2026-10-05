package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.data.settings.SyncSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Fuse Sync on a JVM device (a computer, or Android): its settings in Fuse's own, its secret in the
 * secret store, its working files in [dir]. As a host it also runs the host in this process while
 * Fuse is open (the background service, when installed, runs it the rest of the time; whichever
 * starts first serves and the other connects to it). As a device it keeps its link, follows the
 * host's changes as they happen, sends what changed here, and does the save work around games.
 */
class JvmSyncService(
    private val dir: File,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val data: ProfileDataPort,
    /** LINUX, WINDOWS, MACOS or ANDROID. */
    private val platform: String,
    private val defaultDeviceName: String,
    private val fuseVersion: String,
    private val lifetime: HostLifetime,
    private val scope: CoroutineScope,
    files: SaveEnvironment = FileSaveEnvironment(platform),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Held while looking for hosts (Android only hears broadcast replies under a multicast lock). */
    private val discoveryLock: () -> AutoCloseable? = { null },
    /** How often a running game's save is looked at. */
    private val liveLookMs: Long = LIVE_LOOK_MS,
) : SyncService {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    private val _profiles = MutableStateFlow<List<ProfileInfo>>(emptyList())
    private val _active = MutableStateFlow<ProfileInfo?>(null)
    private val _activity = MutableStateFlow<List<SyncActivity>>(emptyList())
    private val _host = MutableStateFlow<HostView?>(null)
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val profiles: StateFlow<List<ProfileInfo>> = _profiles.asStateFlow()
    override val activeProfile: StateFlow<ProfileInfo?> = _active.asStateFlow()
    override val activity: StateFlow<List<SyncActivity>> = _activity.asStateFlow()
    override val host: StateFlow<HostView?> = _host.asStateFlow()
    override val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()
    override val canHost: Boolean get() = platform != "ANDROID"

    override fun lifetimeState(): ServiceState = lifetime.state()

    override val defaultName: String get() = defaultDeviceName

    private var client: SyncClient? = null
    private var device: SyncDevice? = null
    private var hostServer: SyncHost? = null
    /** The host as its own process (the background service), managed through its admin calls. */
    private var hostAdmin: HostAdmin? = null
    @Volatile private var adminStatus: HostStatus? = null
    private var responder: Discovery.Responder? = null
    private var loop: Job? = null
    private var nudge: Job? = null
    private val work = Mutex()
    private val hlc by lazy { HlcClock(deviceId(), clock) }
    @Volatile private var cached: SyncSettings = SyncSettings()

    /** The device's files, with the save folders the person chose on top. */
    private val saveEnv: SaveEnvironment = WithSaveFolders(files) { cached.saveFolders }

    init {
        scope.launch(Dispatchers.IO) { runCatching { start() } }
    }

    private suspend fun config(): SyncSettings = settings.current().sync.also { cached = it }

    private suspend fun saveConfig(change: (SyncSettings) -> SyncSettings) {
        settings.update { it.copy(sync = change(it.sync)) }
        cached = settings.current().sync
    }

    private fun deviceId(): String = cached.deviceId.ifEmpty { "pending" }

    private fun log(text: String, game: String? = null, kind: String = "") {
        _activity.update { (listOf(SyncActivity(clock(), text, game, kind)) + it).take(60) }
    }

    private suspend fun ensureDeviceId(): String {
        val c = config()
        if (c.deviceId.isNotEmpty()) return c.deviceId
        val id = "dev-" + SyncCrypto.token(12)
        saveConfig { it.copy(deviceId = id) }
        return id
    }

    private suspend fun link(): HostLink? = secrets.get(LINK_KEY)?.let { runCatching { json.decodeFromString(HostLink.serializer(), it) }.getOrNull() }

    // ---------------------------------------------------------------- starting and stopping

    private suspend fun start() {
        val c = config()
        if (!c.enabled) {
            _status.value = SyncStatus.Off
            return
        }
        if (c.role.isEmpty()) {
            _status.value = SyncStatus.NotSetUp
            return
        }
        val id = ensureDeviceId()
        device = SyncDevice(File(dir, "device"), id, c.deviceName.ifBlank { defaultDeviceName })
        if (c.role == "HOST") startHostServer(c)
        val l = link()
        if (l == null) {
            _status.value = SyncStatus.NotSetUp
            return
        }
        if (_profiles.value.isEmpty()) {
            _profiles.value = knownProfiles()
            _active.value = _profiles.value.firstOrNull { it.id == c.activeProfile }
        }
        client = SyncClient(l.copy(localAddress = c.localAddress.ifBlank { l.localAddress }, remoteAddress = c.remoteAddress.ifBlank { l.remoteAddress }))
        _status.value = SyncStatus.Connecting(c.hostName.ifBlank { l.hostName })
        startLoop()
        // A host set up before Admin existed gets it too (this computer keeps the profile it uses).
        if (c.role == "HOST") scope.launch(Dispatchers.IO) { if (adoptHost() != null) runCatching { refreshLists() } }
    }

    /**
     * On the host computer: this Fuse is the host's own, and the host has its own profile, Admin,
     * which no other device sees. Returns Admin, or null when the host can't be asked.
     */
    private suspend fun adoptHost(): ProfileInfo? {
        val id = cached.deviceId.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { hostServer?.store?.adoptOwner(id) ?: hostAdmin?.adoptOwner(id) }.getOrNull()
    }

    /**
     * Runs the host here, unless another process (the background service) already serves on its
     * port: then this one just connects to it.
     */
    private suspend fun startHostServer(c: SyncSettings) {
        if (hostServer != null) return
        val hostDir = File(dir, "host")
        // The service already runs the host: never open its files from a second process.
        HostAdmin.of(hostDir, c.hostPort)?.let { admin ->
            hostAdmin = admin
            adminStatus = runCatching { admin.status() }.getOrNull()
            refreshHostView()
            return
        }
        val store = HostStore(hostDir, clock, hostName = c.hostName.ifBlank { defaultDeviceName })
        val server = runCatching { SyncHost(store, c.hostPort, fuseVersion, clock).start() }.getOrNull()
        if (server != null) {
            hostServer = server
            responder = Discovery.answer({ store.hello(c.hostPort, fuseVersion) })
            scope.launch { server.changes.collect { refreshHostView() } }
        }
        refreshHostView()
    }

    private fun refreshHostView() {
        val c = cached
        val server = hostServer
        _host.value = HostView(
            name = server?.store?.name ?: adminStatus?.hello?.name ?: c.hostName,
            running = server != null || hostAdmin != null,
            port = c.hostPort,
            addresses = lanAddresses().map { "$it:${c.hostPort}" },
            // A code this process serves is shown only while it still works (one that was used,
            // or ran out, is never shown); the background service's is the last one it gave.
            pairingCode = if (server != null) server.pairingCode() else lastCode,
            status = runCatching { server?.store?.status(c.hostPort, fuseVersion) }.getOrNull() ?: adminStatus,
            service = lifetime.state(),
        )
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch(Dispatchers.IO) {
            var backoff = 2_000L
            var joinsChecked = false
            while (true) {
                val c = client ?: break
                try {
                    // Sends what is waiting and catches up, then waits for the host to say something changed.
                    syncOnce()
                    // Requests to join: once on the way in, after being away, and while any are showing (they run out).
                    if (!joinsChecked || backoff > 2_000L || _joins.value.isNotEmpty()) {
                        refreshJoins()
                        joinsChecked = true
                    }
                    backoff = 2_000L
                    val since = device?.seq ?: 0
                    val page = c.events(since, waitSeconds = 25)
                    if (page.events.isNotEmpty()) {
                        device?.saw(page.seq)
                        val active = cached.activeProfile
                        if (page.events.any { it.type == JournalEvent.PROFILE || it.type == JournalEvent.DEVICE }) refreshLists()
                        if (page.events.any { it.type == JournalEvent.JOIN }) refreshJoins()
                        if (active.isNotEmpty() && page.events.any { it.profile == active && it.device != cached.deviceId }) pullActive()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SyncException) {
                    handle(e)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } catch (e: Exception) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private fun handle(e: SyncException) {
        val name = cached.hostName
        _status.value = when (e.code) {
            "offline" -> SyncStatus.Offline(name, device?.pendingCount ?: 0, (status.value as? SyncStatus.Offline)?.since ?: clock())
            "revoked" -> SyncStatus.NeedsAttention(name, "This device was unlinked from $name. Connect it again from Settings, Addons, Fuse Sync.", e.code)
            "clock" -> SyncStatus.NeedsAttention(name, "This device's clock is far from the host's. Set the date and time, and syncing carries on.", e.code)
            "locked" -> SyncStatus.NeedsAttention(name, "This profile's PIN changed. Open it again to keep syncing.", e.code)
            else -> SyncStatus.NeedsAttention(name, e.message ?: "The host said no.", e.code)
        }
    }

    private suspend fun refreshLists() {
        val c = client ?: return
        _profiles.value = c.profiles()
        _devices.value = runCatching { c.devices() }.getOrDefault(_devices.value)
        _active.value = _profiles.value.firstOrNull { it.id == cached.activeProfile }
        // Kept, so who is playing shows (and Who's playing? has faces) while the host is away.
        runCatching { writeAtomically(File(dir, PROFILES_FILE), json.encodeToString(ListSerializer(ProfileInfo.serializer()), _profiles.value).toByteArray()) }
    }

    /** The profiles as last heard from the host. */
    private fun knownProfiles(): List<ProfileInfo> = runCatching {
        json.decodeFromString(ListSerializer(ProfileInfo.serializer()), File(dir, PROFILES_FILE).readText())
    }.getOrDefault(emptyList())

    /** Captures local changes, sends everything waiting, and brings in the active profile's newest records. */
    private suspend fun syncOnce() = work.withLock {
        val c = client ?: return@withLock
        val d = device ?: return@withLock
        val name = cached.hostName.ifBlank { c.link.hostName }
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = true, pending = d.pendingCount)
        if (_profiles.value.isEmpty()) refreshLists()
        val active = cached.activeProfile
        runCatching { resolveGames(c) }
        if (active.isNotEmpty()) captureChanges(active)
        runCatching { c.sharedGames().games }.onSuccess { games ->
            if (games.toSet() != cached.sharedGames.toSet()) saveConfig { it.copy(sharedGames = games) }
            _sharedGames.value = games.toSet()
        }
        val sent = d.flush(c)
        if (active.isNotEmpty()) {
            d.pullMeta(c, active)
            data.write(d.meta(active))
        }
        if (sent > 0) log(if (sent == 1) "Sent 1 change to $name" else "Sent $sent changes to $name")
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = false, pending = d.pendingCount)
    }

    private suspend fun pullActive() = work.withLock {
        val c = client ?: return@withLock
        val d = device ?: return@withLock
        val active = cached.activeProfile.ifEmpty { return@withLock }
        captureChanges(active)
        d.pullMeta(c, active)
        data.write(d.meta(active))
        log("Brought in changes from your other devices")
    }

    /** What changed here since the profile was put in place becomes changes waiting to go. */
    private suspend fun captureChanges(profile: String) {
        val d = device ?: return
        val local = data.read(d.deviceId, hlc)
        val changes = ProfileDiff.changes(d.meta(profile), local, d.deviceId, hlc)
        if (!ProfileDiff.isEmpty(changes)) d.changeMeta(profile) { pending, _ -> pending.merge(changes) }
    }

    override fun changed() {
        // Many quick changes become one send, a moment later.
        nudge?.cancel()
        nudge = scope.launch(Dispatchers.IO) {
            delay(1_500)
            runCatching { syncOnce() }.onFailure { if (it is SyncException) handle(it) }
        }
    }

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        saveConfig { it.copy(enabled = enabled) }
        stop()
        client = null
        if (enabled) {
            runCatching { start() }
        } else {
            _status.value = SyncStatus.Off
            _active.value = null
            _profiles.value = emptyList()
            _host.value = null
        }
    }

    override suspend fun stopHosting(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // This device was its own host's client: that link goes with it.
            unlink()
            if (lifetime.state().installed) lifetime.remove()
            stop()
            hostAdmin = null
            adminStatus = null
            saveConfig { it.copy(role = "", hostName = "") }
            _host.value = null
            _status.value = SyncStatus.NotSetUp
            log("This device stopped being the host")
        }
    }

    override fun stop() {
        loop?.cancel()
        nudge?.cancel()
        hostServer?.stop()
        responder?.stop()
        hostServer = null
        responder = null
    }

    // ---------------------------------------------------------------- setting up

    override suspend fun discover(): List<NearbyHost> = withContext(Dispatchers.IO) {
        val lock = runCatching { discoveryLock() }.getOrNull()
        try {
            Discovery.find().map { NearbyHost(it.hello.name, it.hello.hostId, reachable(it)) }
        } finally {
            runCatching { lock?.close() }
        }
    }

    /**
     * The first of [host]'s addresses that answers as that host, tried all at once (a computer
     * running Docker or virtual machines may answer discovery from one no other device reaches).
     */
    private suspend fun reachable(host: FoundHost): String {
        val candidates = host.candidates
        if (candidates.size == 1) return candidates.first()
        val quick = SyncHttp.client {
            install(io.ktor.client.plugins.HttpTimeout) { connectTimeoutMillis = 1_500; requestTimeoutMillis = 2_500 }
            expectSuccess = false
        }
        return try {
            kotlinx.coroutines.coroutineScope {
                val answers = candidates.map { a -> async { a to (SyncClient.hello(a, quick)?.hostId == host.hello.hostId) } }
                answers.awaitAll().firstOrNull { it.second }?.first ?: candidates.first()
            }
        } finally {
            quick.close()
        }
    }

    override suspend fun connect(address: String, code: String, remoteAddress: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val id = ensureDeviceId()
            val c = config()
            val name = c.deviceName.ifBlank { defaultDeviceName }
            suspend fun pairAt(at: String): HostLink? = try {
                SyncClient.pair(at, code, id, name, platform)
            } catch (e: SyncException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // At home first; when nothing answers there, the address from outside (a tunnel or VPN).
            val outside = remoteAddress?.takeIf { it.isNotBlank() && SyncClient.normalise(it) != SyncClient.normalise(address) }
            val link = pairAt(address)
                ?: outside?.let { o -> pairAt(o)?.let { l -> l.copy(localAddress = SyncClient.normalise(address).takeIf { !it.startsWith("https://") } ?: l.localAddress) } }
                // Nothing answered: say what usually causes it, never the network's own words.
                ?: throw SyncException(unreachableWords(outside ?: address), "offline", 0)
            val linked = if (outside != null) link.copy(remoteAddress = SyncClient.normalise(outside)) else link
            linkUp(linked)
        }
    }

    /** Keeps [linked] as this device's link to its host and starts syncing with it. */
    private suspend fun linkUp(linked: HostLink): String {
        run {
            secrets.put(LINK_KEY, json.encodeToString(HostLink.serializer(), linked))
            saveConfig {
                it.copy(
                    enabled = true, role = if (it.role == "HOST") "HOST" else "CLIENT", hostName = linked.hostName, hostId = linked.hostId,
                    localAddress = linked.localAddress.orEmpty(), remoteAddress = linked.remoteAddress.orEmpty(),
                )
            }
            stop()
            start()
            refreshLists()
            log("Connected to ${linked.hostName}")
        }
        return linked.hostName
    }

    // ---------------------------------------------------------------- one id per game

    private val aliasFile get() = File(dir, "game-aliases.json")
    private val aliasSerializer = GameAliasesSerializer

    /** Every id a game here is known by, to the id the host keeps its saves and records under. */
    @Volatile private var gameAliases: Map<String, String> =
        runCatching { json.decodeFromString(aliasSerializer, aliasFile.readText()) }.getOrDefault(emptyMap()).also { data.useAliases(it) }

    /** [q] for the game's one id across devices (as it is when the host hasn't been asked yet). */
    private fun canonical(q: SaveQuery): SaveQuery =
        gameAliases[q.game.id]?.takeIf { it != q.game.id }?.let(GameKey::parse)?.let { q.copy(game = it) } ?: q

    /**
     * Asks the host for the one id of each game here it hasn't settled yet, from every id this
     * device knows it by: a serial on one device and only a title on another still meet.
     */
    private suspend fun resolveGames(c: SyncClient) {
        val lists = runCatching { data.candidates() }.getOrDefault(emptyList()).map { l -> l.map { it.id }.distinct() }.filter { it.isNotEmpty() }
        val known = gameAliases
        val todo = lists.filter { l -> l.any { it !in known } }
        if (todo.isEmpty()) return
        val next = HashMap(known)
        for (chunk in todo.chunked(RESOLVE_CHUNK)) {
            val ids = c.resolveGames(chunk)
            chunk.zip(ids).forEach { (l, canon) -> if (canon.isNotEmpty()) l.forEach { next[it] = canon } }
        }
        gameAliases = next
        runCatching { writeAtomically(aliasFile, json.encodeToString(aliasSerializer, next).toByteArray()) }
        data.useAliases(next)
    }

    // ---------------------------------------------------------------- joining without a code

    private val _joins = MutableStateFlow<List<JoinAsk>>(emptyList())
    override val joinRequests: StateFlow<List<JoinAsk>> = _joins.asStateFlow()
    @Volatile private var joining: Pair<JoinSession, String?>? = null

    override suspend fun askToJoin(address: String, remoteAddress: String?): Result<JoinWaiting> = withContext(Dispatchers.IO) {
        runCatching {
            val id = ensureDeviceId()
            val name = config().deviceName.ifBlank { defaultDeviceName }
            suspend fun askAt(at: String): JoinSession? = try {
                SyncClient.askToJoin(at, id, name, platform)
            } catch (e: SyncException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val outside = remoteAddress?.takeIf { it.isNotBlank() && SyncClient.normalise(it) != SyncClient.normalise(address) }
            val session = askAt(address) ?: outside?.let { askAt(it) } ?: throw SyncException(unreachableWords(outside ?: address), "offline", 0)
            joining = session to outside
            JoinWaiting(session.ticket.hostName, session.match)
        }
    }

    override suspend fun awaitJoin(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (session, outside) = joining ?: error("Ask the host first.")
            val until = clock() + SyncHost.JOIN_TTL_MS
            var link: HostLink? = null
            while (link == null) {
                if (joining?.first !== session) throw CancellationException("Stopped asking")
                if (clock() > until) throw SyncException("Nobody let this device in in time. Ask again.", "gone", 410)
                link = try {
                    SyncClient.joinResult(session)
                } catch (e: SyncException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (link == null) delay(JOIN_POLL_MS)
            }
            joining = null
            linkUp(if (outside != null) link.copy(remoteAddress = SyncClient.normalise(outside)) else link)
        }
    }

    override fun cancelJoin() {
        joining = null
    }

    override suspend fun answerJoin(id: String, allow: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Connect to your host first.")
            c.answerJoin(id, allow)
            _joins.value = _joins.value.filterNot { it.id == id }
            refreshJoins()
            log(if (allow) "Let a new device in" else "Turned a device away")
        }
    }

    private suspend fun refreshJoins() {
        val c = client ?: return
        _joins.value = runCatching { c.joins() }.getOrDefault(_joins.value)
    }

    override suspend fun hostHere(name: String, installService: Boolean): Result<HostView> = withContext(Dispatchers.IO) {
        runCatching {
            require(canHost) { "This device can't be a host." }
            saveConfig { it.copy(enabled = true, role = "HOST", hostName = name.trim().ifEmpty { defaultDeviceName }) }
            val c = config()
            ensureDeviceId()
            startHostServer(c)
            // This device connects to its own host, like any other.
            val code = newPairingCode() ?: error("The host couldn't start: port ${c.hostPort} is in use by something else.")
            connect("127.0.0.1:${c.hostPort}", code).getOrThrow()
            // That code was this computer's own, and is used up: the one shown for other devices is new.
            lastCode = null
            // The host plays as its own profile, Admin, so nobody has to make one here.
            adoptHost()?.let { admin ->
                refreshLists()
                switchTo(admin.id, null)
            }
            if (installService && lifetime.supported) handOver()
            newPairingCode()
            refreshHostView()
            log("$name is a Fuse Sync Host")
            _host.value!!
        }
    }

    override suspend fun installService(): Result<ServiceState> = withContext(Dispatchers.IO) { handOver().also { refreshHostView() } }

    /**
     * Hands the host over to the background service: this process stops serving (so the two never
     * share its files), the service starts, and Fuse manages it from then on. Should the service
     * not come up, this process serves again, so the host is never left down.
     */
    private suspend fun handOver(): Result<ServiceState> {
        val c = config()
        val server = hostServer
        server?.stop()
        responder?.stop()
        hostServer = null
        responder = null
        val installed = lifetime.install()
        if (installed.isSuccess) {
            val admin = withTimeoutOrNull(SERVICE_WAIT_MS) {
                var found: HostAdmin? = null
                while (found == null) {
                    found = HostAdmin.of(File(dir, "host"), c.hostPort)
                    if (found == null) delay(250)
                }
                found
            }
            if (admin != null) {
                hostAdmin = admin
                adminStatus = runCatching { admin.status() }.getOrNull()
                log("The host now runs on its own, with Fuse closed too")
                return installed
            }
        }
        // It didn't come up: serve here again.
        startHostServer(c)
        return if (installed.isSuccess) Result.failure(IllegalStateException("The service was set up but didn't start. Fuse keeps hosting while it is open.")) else installed
    }

    override suspend fun removeService(): Result<ServiceState> = withContext(Dispatchers.IO) {
        lifetime.remove().also {
            // The host lives in Fuse again, while it is open.
            hostAdmin = null
            adminStatus = null
            val c = config()
            if (c.role == "HOST") {
                withTimeoutOrNull(5_000) { while (!HostAdmin.portFree(c.hostPort)) delay(200) }
                startHostServer(c)
            }
            refreshHostView()
        }
    }

    @Volatile private var lastCode: String? = null

    override suspend fun newPairingCode(): String? {
        // The host makes it; any device already connected may ask the host for one too.
        val code = hostServer?.newPairingCode() ?: runCatching { hostAdmin?.pairingCode() }.getOrNull() ?: runCatching { client?.pairingCode() }.getOrNull()
        lastCode = code
        adminStatus = runCatching { hostAdmin?.status() }.getOrNull() ?: adminStatus
        refreshHostView()
        return code
    }

    // ---------------------------------------------------------------- profiles

    private suspend fun <T> withClient(block: suspend (SyncClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext Result.failure(SyncException("Fuse Sync isn't connected.", "not-connected", 0))
        runCatching { block(c) }.onFailure { if (it is SyncException) handle(it) }
    }

    override suspend fun createProfile(name: String, avatar: String, pin: String?): Result<ProfileInfo> = withClient { c ->
        val made = c.createProfile(NewProfile(name, avatar, pin?.ifBlank { null }))
        refreshLists()
        made
    }

    override suspend fun changeProfile(id: String, change: ProfileChange): Result<ProfileInfo> = withClient { c ->
        c.changeProfile(id, change).also { refreshLists() }
    }

    override suspend fun deleteProfile(id: String): Result<Unit> = withClient { c ->
        if (cached.activeProfile == id) switchTo(null)
        c.deleteProfile(id)
        refreshLists()
    }

    override suspend fun openProfile(id: String, pin: String?): Result<Unit> = withClient { c -> c.openProfile(id, pin); Unit }

    override suspend fun switchTo(id: String?, pin: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = device ?: error("Fuse Sync isn't set up.")
            val c = client
            work.withLock {
                val before = cached.activeProfile
                // What changed under the profile in use stays with it, sent now or later.
                if (before.isNotEmpty()) captureChanges(before)
                if (c != null) runCatching { d.flush(c) }
                if (id == null) {
                    saveConfig { it.copy(activeProfile = "") }
                    _active.value = null
                    return@withLock
                }
                if (c != null) c.openProfile(id, pin)
                // The first profile this device ever uses takes in what it already had (nothing is lost).
                val first = before.isEmpty() && d.meta(id) == ProfileMeta() && !adopted()
                if (first) {
                    // A profile someone already uses keeps what it has: this device's records only
                    // fill what it doesn't (stamped older than anything real), and its play time
                    // and sessions join. Only a new, empty profile takes this device's as they are.
                    if (c != null) runCatching { d.pullMeta(c, id) }
                    val fresh = d.meta(id) == ProfileMeta()
                    val local = data.read(d.deviceId, hlc)
                    val adopt = ProfileDiff.changes(ProfileMeta(), local, d.deviceId, if (fresh) hlc else HlcClock(d.deviceId) { ADOPTED_AT })
                    d.changeMeta(id) { pending, _ -> pending.merge(adopt) }
                    saveConfig { it.copy(activeProfile = id) }
                    markAdopted()
                }
                if (c != null) runCatching {
                    d.flush(c)
                    d.pullMeta(c, id)
                }
                saveConfig { it.copy(activeProfile = id) }
                data.write(d.meta(id))
                d.useProfile(id)
                _active.value = _profiles.value.firstOrNull { it.id == id }
                log("Switched to ${_active.value?.name ?: "a profile"}")
            }
        }
    }

    private fun adopted(): Boolean = File(dir, "adopted").isFile
    private fun markAdopted() {
        dir.mkdirs()
        File(dir, "adopted").writeText("1")
    }

    override suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { syncOnce() }.onFailure { if (it is SyncException) handle(it) }
    }

    // ---------------------------------------------------------------- saves around a game

    private fun slots(query: SaveQuery): List<LocalSlot> {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return emptyList()
        val c = cached
        return adapter.locate(query, saveEnv)
            .filter { s -> (s.kind == SaveKind.STATE && c.states) || (s.kind != SaveKind.STATE && c.saves) }
            .map { spot ->
                // Cards shared by every game on them are the profile's, keyed by the card, not the game.
                val key = if (spot.kind == SaveKind.MEMORY_CARD) GameKey(query.platform, "card." + spot.format) else query.game
                Slots.of(key, spot)
            }
    }

    private val _sharedGames = MutableStateFlow(cached.sharedGames.toSet())
    override val sharedGames: StateFlow<Set<String>> = _sharedGames.asStateFlow()

    /**
     * Whose save [slot] is: the household's, for a game played as one save (a memory card holds
     * other games too, so it always stays the person's), else the person playing's.
     */
    private fun ownerOf(query: SaveQuery, slot: LocalSlot, profile: String): String =
        if (slot.kind != SaveKind.MEMORY_CARD && query.game.id in cached.sharedGames) SHARED_SAVES else profile

    override suspend fun saveFolders(samples: List<SaveQuery>): List<EmulatorSaves> = withContext(Dispatchers.IO) {
        config()
        SaveAdapters.survey(samples, saveEnv)
    }

    override suspend fun setShared(game: GameKey, shared: Boolean, fromMine: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Fuse Sync isn't connected. Sharing a game needs the host.")
            val from = cached.activeProfile.takeIf { fromMine && shared && it.isNotEmpty() }
            val result = c.setShared(game.id, shared, from)
            saveConfig { it.copy(sharedGames = result.games) }
            _sharedGames.value = result.games.toSet()
            log(if (shared) "A game is now one save for everyone" else "A game is each person's own save again", game.id, "save")
        }
    }

    override suspend fun beforeLaunch(asked: SaveQuery, waitForOthers: Boolean): LaunchGate = withContext(Dispatchers.IO) {
        // The game by the household's one id for it (another device may know it by another name).
        client?.let { cl -> withTimeoutOrNull(RESOLVE_WAIT_MS) { runCatching { resolveGames(cl) } } }
        val query = canonical(asked)
        val c = client
        val d = device
        val profile = cached.activeProfile
        if (d == null || profile.isEmpty() || !cached.enabled) return@withContext LaunchGate.Go()
        // Another device playing it, or still sending what it just saved: the person decides whether to wait.
        if (waitForOthers && c != null) othersOn(c, d, query, profile)?.let { return@withContext it }
        // From here until its save is sent after it stops (Android keeps Fuse going meanwhile).
        getReady(query.title)
        var note: String? = null
        for (slot in slots(query)) {
            // On a device more than one person plays, the folder must hold this person's save
            // first (whoever played last keeps theirs); this needs no host, so it happens offline too.
            val owner = ownerOf(query, slot, profile)
            runCatching { d.handover(owner, slot) { who -> if (who == SHARED_SAVES) 0L else d.meta(who).game(query.game).totalSeconds } }
                .onFailure { log("${query.title}: couldn't swap in this person's save (${it.message})", query.game.id, "save") }
            if (c == null) {
                note = note ?: "Fuse Sync is offline: playing with this device's save"
                continue
            }
            // A launch never waits long on a host that isn't there.
            val result = withTimeoutOrNull(LAUNCH_WAIT_MS) { runCatching { d.prepare(c, owner, slot) }.getOrElse { PrepareResult.Offline } } ?: PrepareResult.Offline
            when (result) {
                is PrepareResult.Conflict -> return@withContext LaunchGate.Conflict(
                    SaveConflict(
                        game = slot.game, title = query.title, kind = slot.kind,
                        here = side(result.local), host = side(result.remote),
                        local = result.local, remote = result.remote, query = query,
                    ),
                )
                is PrepareResult.Updated -> {
                    note = "Your ${result.revision.kind.label.lowercase()} from ${result.revision.deviceName}, ${ago(result.revision.at.millis)}"
                    log("${query.title}: brought the save from ${result.revision.deviceName}", query.game.id, "save")
                }
                is PrepareResult.Incompatible -> {
                    log("${query.title}: the newest ${result.revision.kind.label.lowercase()} is for another emulator, so it stays on the host", query.game.id, "save")
                    _notices.tryEmit(SyncNotice.CantUse(query.title, result.revision.kind, result.revision.deviceName, incompatibleWhy(result.revision)))
                }
                PrepareResult.Offline -> note = note ?: "Fuse Sync is offline: playing with this device's save"
                else -> Unit
            }
        }
        LaunchGate.Go(note)
    }

    private fun side(r: SaveRevision) = SaveSide(r.deviceName, r.at.millis, r.playSeconds, r.size, r.manifest.files.size)

    override suspend fun settle(conflict: SaveConflict, keepHere: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Fuse Sync isn't connected.")
            val d = device ?: error("Fuse Sync isn't set up.")
            val slot = slots(conflict.query).firstOrNull { it.kind == conflict.kind } ?: error("That save isn't here any more.")
            val owner = ownerOf(conflict.query, slot, cached.activeProfile)
            if (keepHere) {
                d.keepLocal(c, owner, slot, conflict.remote, conflict.here.playSeconds)
                log("${conflict.title}: kept this device's save; the other is in its history", conflict.game.id, "conflict")
            } else {
                d.takeRemote(c, owner, slot, conflict.remote)
                runCatching { d.flush(c) }
                log("${conflict.title}: took the save from ${conflict.host.device}; this one is in its history", conflict.game.id, "conflict")
            }
        }
    }

    override suspend fun afterExit(asked: SaveQuery, startedAt: Long, endedAt: Long) {
        val query = canonical(asked)
        stopWatching(query)
        val d = device ?: return run { _nowPlaying.value = null }
        val profile = cached.activeProfile.ifEmpty { return run { _nowPlaying.value = null } }
        withContext(Dispatchers.IO) {
            client?.let { c -> runCatching { c.notePresence(PresenceNote(profile, query.game.id, query.title, Presence.SENDING, startedAt)) } }
            // Emulators finish writing a moment after they close.
            delay(SETTLE_MS)
            // The same id the library's own record of this session gets, so it is counted once whichever arrives first.
            val session = SessionEntry(SessionEntry.idOf(startedAt, endedAt), d.deviceId, startedAt, endedAt, query.emulatorId)
            if (cached.records) d.played(profile, query.game, session)
            val total = d.meta(profile).game(query.game).totalSeconds
            val c = liveLock.withLock {
                val captured = slots(query).mapNotNull { slot ->
                    runCatching { d.capture(ownerOf(query, slot, profile), slot, total, title = query.title) }.getOrNull()
                        ?.also { log("${query.title}: saved ${it.kind.label.lowercase()}", query.game.id, "save") }
                }
                val c = client ?: return@withLock null
                runCatching { d.flush(c) }
                    .onSuccess { captured.firstOrNull { it.kind != SaveKind.STATE }?.let { r -> _notices.tryEmit(SyncNotice.Sent(query.title, r.kind, live = false)) } }
                    .onFailure { if (it is SyncException) handle(it) }
                c
            } ?: return@withContext
            // Done: nothing playing, nothing left to send (what is still queued says so by itself next time).
            runCatching { c.notePresence(PresenceNote(profile, query.game.id, query.title, if (d.pendingProfiles().isEmpty()) null else Presence.SENDING, startedAt)) }
        }
        if (watch == null) _nowPlaying.value = null
    }

    // ---------------------------------------------------------------- while a game runs

    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<SyncNotice>(extraBufferCapacity = 8)
    override val notices: kotlinx.coroutines.flow.SharedFlow<SyncNotice> = _notices

    private class Watch(val query: SaveQuery, val profile: String, val startedAt: Long, val job: Job)

    /** One send at a time while playing (the watch, and the screen going off). */
    private val liveLock = Mutex()

    @Volatile private var watch: Watch? = null

    private val _nowPlaying = MutableStateFlow<String?>(null)
    override val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()
    @Volatile private var readying: Job? = null

    /** About to start [title]: in step from now; if it never starts, that ends by itself. */
    private fun getReady(title: String) {
        if (watch == null) _nowPlaying.value = title
        readying?.cancel()
        readying = scope.launch {
            delay(READY_MS)
            if (watch == null) _nowPlaying.value = null
        }
    }

    override suspend fun playing(asked: SaveQuery, startedAt: Long) {
        val query = canonical(asked)
        val profile = cached.activeProfile.ifEmpty { return }
        if (device == null || !cached.enabled) return
        watch?.job?.cancel()
        // How the save looks as the game starts (what came down before it), taken now, before the game can write.
        val first = withContext(Dispatchers.IO) { looks(query) }
        val job = scope.launch(Dispatchers.IO) { watchWhilePlaying(query, profile, startedAt, first) }
        watch = Watch(query, profile, startedAt, job)
        readying?.cancel()
        _nowPlaying.value = query.title
    }

    override suspend fun busyWith(query: SaveQuery): LaunchGate.Busy? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val d = device ?: return@withContext null
        othersOn(c, d, canonical(query), cached.activeProfile.ifEmpty { return@withContext null })
    }

    override suspend fun sendWhilePlaying() {
        val w = watch ?: return
        withContext(Dispatchers.IO) { liveLock.withLock { sendLive(w.query, w.profile, w.startedAt) } }
    }

    private fun stopWatching(query: SaveQuery) {
        val w = watch ?: return
        if (w.query.game == query.game) {
            w.job.cancel()
            watch = null
        }
    }

    /** What a slot's files look like now, cheaply (sizes and times; the content is only read once they settle). */
    private fun looks(query: SaveQuery): List<String> = slots(query).flatMap { slot ->
        slot.files.map { f -> "${f.path}:${if (f.file.isFile) "${f.file.length()}@${f.file.lastModified()}" else "-"}" }
    }

    /**
     * Every [LIVE_LOOK_MS] while the game runs: says it is still playing, and when the game has
     * written its save and left it alone since the last look, keeps it and sends it.
     */
    private suspend fun watchWhilePlaying(query: SaveQuery, profile: String, startedAt: Long, first: List<String>) {
        suspend fun say() = client?.let { c -> runCatching { c.notePresence(PresenceNote(profile, query.game.id, query.title, Presence.PLAYING, startedAt)) } }
        say()
        var seen = first
        var moving = false
        while (true) {
            delay(liveLookMs)
            val now = looks(query)
            val changed = now != seen
            seen = now
            if (changed) {
                // Written just now: wait one more look so a save the game is still writing isn't sent half done.
                moving = true
            } else if (moving) {
                moving = false
                liveLock.withLock { sendLive(query, profile, startedAt) }
            }
            say()
        }
    }

    private suspend fun sendLive(query: SaveQuery, profile: String, startedAt: Long) {
        val d = device ?: return
        val played = ((clock() - startedAt) / 1000).coerceAtLeast(0)
        val total = d.meta(profile).game(query.game).totalSeconds + played
        val captured = slots(query).mapNotNull { slot -> runCatching { d.capture(ownerOf(query, slot, profile), slot, total, title = query.title) }.getOrNull() }
        if (captured.isEmpty()) return
        log("${query.title}: saved ${captured.first().kind.label.lowercase()} while playing", query.game.id, "save")
        val c = client ?: return
        runCatching { d.flush(c) }
            .onSuccess { captured.firstOrNull { it.kind != SaveKind.STATE }?.let { r -> _notices.tryEmit(SyncNotice.Sent(query.title, r.kind, live = true)) } }
            .onFailure { if (it is SyncException) handle(it) }
    }

    /**
     * Another device on this game for the same person (or anyone, for a game played as one save):
     * playing it lately, or done and still sending. Null when nobody is, or the host can't say.
     */
    private suspend fun othersOn(c: SyncClient, d: SyncDevice, query: SaveQuery, profile: String): LaunchGate.Busy? {
        val list = withTimeoutOrNull(PRESENCE_WAIT_MS) { runCatching { c.presence() }.getOrNull() } ?: return null
        val now = clock()
        val shared = query.game.id in cached.sharedGames
        val other = list.firstOrNull { p ->
            p.deviceId != d.deviceId && p.game == query.game.id && (shared || p.profile == profile) &&
                ((p.state == Presence.PLAYING && now - p.at <= PLAYING_ASK_MS) || (p.state == Presence.SENDING && now - p.at <= SENDING_ASK_MS))
        } ?: return null
        val slot = slots(query).firstOrNull { it.kind != SaveKind.STATE }
        val last = slot?.let { s -> runCatching { c.revisions(ownerOf(query, s, profile), s.game.id, s.kind) }.getOrNull() }
            ?.firstOrNull { it.device == other.deviceId && it.reason != RevisionReason.CONFLICT_COPY }?.at?.millis
        return LaunchGate.Busy(other.deviceName, query.title, other.state == Presence.PLAYING, other.at, last)
    }

    private fun ago(at: Long): String {
        val m = ((clock() - at) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    private fun incompatibleWhy(r: SaveRevision): String =
        "It was saved by another emulator on ${r.deviceName}, so it stays on the host. Play it there, or use the same emulator here."

    override suspend fun report(): ProfileReport? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val profile = cached.activeProfile.ifEmpty { return@withContext null }
        runCatching { c.report(profile) }.onFailure { if (it is SyncException) handle(it) }.getOrNull()
    }

    override suspend fun versions(asked: SaveQuery, kind: SaveKind): List<SaveVersion> = withContext(Dispatchers.IO) {
        val query = canonical(asked)
        val c = client ?: return@withContext emptyList()
        val profile = cached.activeProfile.ifEmpty { return@withContext emptyList() }
        val slot = slots(query).firstOrNull { it.kind == kind }
        val key = slot?.game ?: query.game
        val owner = slot?.let { ownerOf(query, it, profile) } ?: profile
        val list = runCatching { c.revisions(owner, key.id, kind) }.getOrDefault(emptyList())
        val head = list.firstOrNull { it.reason != RevisionReason.CONFLICT_COPY }?.id
        list.map { SaveVersion(it.id, it.deviceName, it.at.millis, it.playSeconds, it.size, it.reason, it.id == head) }
    }

    override suspend fun restore(asked: SaveQuery, kind: SaveKind, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        val query = canonical(asked)
        runCatching {
            val c = client ?: error("Fuse Sync isn't connected.")
            val d = device ?: error("Fuse Sync isn't set up.")
            val slot = slots(query).firstOrNull { it.kind == kind } ?: error("That save isn't here.")
            val owner = ownerOf(query, slot, cached.activeProfile)
            val rev = c.revisions(owner, slot.game.id, kind).firstOrNull { it.id == version } ?: error("That version is gone.")
            // What is here now is kept in the history first, then the old one becomes the newest.
            d.place(c, slot, rev)
            d.capture(owner, slot, rev.playSeconds, Priority.LAUNCH, title = query.title)
            d.flush(c)
            log("${query.title}: restored the save from ${rev.deviceName}", query.game.id, "restore")
        }
    }

    // A version is the person's own, or (for a game played as one save) everyone's.
    override suspend fun keepVersion(version: String, keep: Boolean): Result<Unit> = withClient { c ->
        runCatching { c.pin(cached.activeProfile, version, keep) }.recoverCatching { c.pin(SHARED_SAVES, version, keep) }.getOrThrow()
        Unit
    }

    // ---------------------------------------------------------------- leaving

    override suspend fun unlink(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Sends what it can first; then forgets the host. Nothing of this device's own changes.
            client?.let { c -> runCatching { device?.flush(c) }; runCatching { c.unlinkSelf() } }
            loop?.cancel()
            client = null
            secrets.remove(LINK_KEY)
            File(dir, PROFILES_FILE).delete()
            saveConfig { it.copy(role = if (it.role == "HOST") "HOST" else "", activeProfile = "", hostName = if (it.role == "HOST") it.hostName else "", localAddress = "", remoteAddress = "") }
            _active.value = null
            _profiles.value = emptyList()
            _status.value = SyncStatus.NotSetUp
        }
    }

    override suspend fun renameDevice(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = hostServer?.store
            when {
                s != null -> s.changeDevice(id, DeviceChange(name = name))
                hostAdmin != null -> hostAdmin!!.rename(id, name)
                else -> client?.renameSelf(name)
            }
            refreshLists()
        }
    }

    override suspend fun revokeDevice(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = hostServer?.store
            when {
                s != null -> s.revokeDevice(id)
                hostAdmin != null -> hostAdmin!!.revoke(id)
                else -> error("Unlink other devices on the host.")
            }
            refreshLists()
            refreshHostView()
        }
    }

    private fun lanAddresses(): List<String> = LanAddresses.list()

    companion object {
        /** Why a host at [address] might not answer, and what to check. */
        internal fun unreachableWords(address: String): String {
            val shown = address.removePrefix("http://").removePrefix("https://")
            return "The host didn't answer at $shown. Check that both are on the same Wi-Fi, and that the host's firewall lets Fuse in (port ${shown.substringAfterLast(':', SyncApi.DEFAULT_PORT.toString())})."
        }

        const val LINK_KEY = "sync.link"

        /** The longest a launch waits for the host to settle game ids. */
        const val RESOLVE_WAIT_MS = 3_000L
        private const val RESOLVE_CHUNK = 1_000

        /** How often a device waiting to be let in asks whether it has been. */
        const val JOIN_POLL_MS = 1_500L
        private const val PROFILES_FILE = "profiles.json"

        /** The longest a launch waits on the host before playing with what is here. */
        const val LAUNCH_WAIT_MS = 8_000L

        /** How often a running game's save is looked at. */
        const val LIVE_LOOK_MS = 15_000L

        /** How long after the save check a game has to start before Fuse stops keeping in step for it. */
        const val READY_MS = 45_000L
        const val PRESENCE_WAIT_MS = 3_000L

        /** Another device heard from this lately while playing (it may have gone to sleep mid-game). */
        const val PLAYING_ASK_MS = 30 * 60_000L

        /** Another device done with it and sending, heard from this lately. */
        const val SENDING_ASK_MS = 3 * 60_000L

        /** How long after an emulator closes its saves are read (it may still be writing them). */
        const val SETTLE_MS = 1_500L

        /** When a device's records joining a profile already in use count as made: before anything real. */
        const val ADOPTED_AT = 1L

        /** How long Fuse waits for a freshly started service to answer. */
        const val SERVICE_WAIT_MS = 15_000L
    }
}
