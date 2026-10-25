package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.data.settings.SyncSettings
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    /** Folders granted through the system's folder picker (`tree:` save folders), by their link; none on computers. */
    private val trees: (String) -> DocumentTree? = { null },
    /**
     * Whether reading this device's game files for the household (hashing) may run now: a handheld
     * only while it charges, as reading every game it has, many gigabytes, ran it hot and drained it.
     */
    private val mayHash: () -> Boolean = { true },
    /**
     * Whether the device is awake for the rounds that wait on the host ([Awake]): a phone asleep
     * asks the host nothing until it wakes, and then catches up.
     */
    private val awake: StateFlow<Boolean> = Awake.always,
) : SyncService {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    private val _profiles = MutableStateFlow<List<ProfileInfo>>(emptyList())
    private val _active = MutableStateFlow<ProfileInfo?>(null)
    /** What happened, newest first, kept across restarts (see [log]). */
    private val _activity = MutableStateFlow(
        runCatching { json.decodeFromString(ListSerializer(SyncActivity.serializer()), File(dir, HISTORY_FILE).readText()) }.getOrDefault(emptyList()),
    )
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

    @Volatile private var client: SyncClient? = null
    private var device: SyncDevice? = null

    /**
     * Held while what a host said is shown, and while that host is let go of: a list the host sent
     * just before it was forgotten is never shown (or kept) after.
     */
    private val showing = Any()

    /** The host let go of ([showing]). */
    private fun dropClient() = synchronized(showing) { retire(client); client = null }

    /**
     * [old] closed once the calls already on their way with it are done (the longest, the wait for
     * the host's news, takes up to half a minute): a client holds a thread until it is closed.
     */
    private fun retire(old: SyncClient?) {
        if (old == null) return
        scope.launch { delay(RETIRE_MS); old.close() }
    }

    /** The household's games through this device's host (see [Household]). */
    val householdHere: JvmHousehold by lazy {
        JvmHousehold(File(dir, "household"), scope, { client }, { cached.deviceId }, { cached }, busy = { _nowPlaying.value != null || !mayHash() }, clock = clock, awake = roundsMayRun)
    }

    /**
     * Fuse One's own rounds may run: the device awake, and no game it follows in front. Under a game
     * the wait for the host's news and the household's lists hold (each answer woke the Wi-Fi and the
     * processor again under the game, every half minute for as long as it was played) and catch up
     * the moment it ends. The game's save still goes as it is written ([watchWhilePlaying]).
     */
    private val roundsMayRun: StateFlow<Boolean> by lazy {
        kotlinx.coroutines.flow.combine(awake, _nowPlaying) { a, playing -> a && playing == null }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, awake.value && _nowPlaying.value == null)
    }
    override val household: Household get() = householdHere
    private var hostServer: SyncHost? = null
    /** The host as its own process (the background service), managed through its admin calls. */
    private var hostAdmin: HostAdmin? = null
    @Volatile private var adminStatus: HostStatus? = null
    private var responder: Discovery.Responder? = null
    private var loop: Job? = null
    private var nudge: Job? = null
    private val work = Mutex()

    /** Held only while the person in use changes or their records are laid in place: never across the network. */
    private val profileLock = Mutex()
    private val listsLock = Mutex()
    private val hlc by lazy { HlcClock(deviceId(), clock) }
    @Volatile private var cached: SyncSettings = SyncSettings()

    /** This device's own people: its profiles while it has no host, its order, and PINs typed here. */
    private val peopleFile get() = File(dir, PEOPLE_FILE)
    @Volatile private var people: LocalPeople =
        runCatching { json.decodeFromString(LocalPeople.serializer(), File(dir, PEOPLE_FILE).readText()) }.getOrDefault(LocalPeople())

    private fun savePeople(next: LocalPeople) {
        people = next
        dir.mkdirs()
        writeAtomically(peopleFile, json.encodeToString(LocalPeople.serializer(), next).toByteArray())
    }

    /** A host this device joined while it had profiles, waiting for the person to say who is who. */
    @Volatile private var pendingLink: HostLink? = null
    @Volatile private var pendingClient: SyncClient? = null
    private val _merge = MutableStateFlow<ProfileMerge?>(null)
    override val merge: StateFlow<ProfileMerge?> = _merge.asStateFlow()

    /** The device's files, with the save folders the person chose on top. */
    private val saveEnv: SaveEnvironment = object : SaveEnvironment by WithSaveFolders(TreeSaveEnvironment(files, trees), { game, format -> device?.learned(game, format).orEmpty() }, { cached.saveFolders }) {
        // A part of a save its emulator has no place for yet is kept here, with the rest of Fuse Sync's.
        override fun held(game: String, part: String): String =
            File(File(File(dir, "held"), game.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120)), part).path
    }

    /** Saves in granted folders, worked on through copies here (see [TreeMirror]). */
    private val treeMirror by lazy { TreeMirror(File(dir, "trees"), trees) }

    init {
        if (fuseVersion.isNotEmpty()) fuseAppVersion = fuseVersion
        // The service starts at the end of the class (see the last init block), once all of it is made.
    }

    private suspend fun config(): SyncSettings = settings.current().sync.also { cached = it }

    private suspend fun saveConfig(change: (SyncSettings) -> SyncSettings) {
        settings.update { it.copy(sync = change(it.sync)) }
        cached = settings.current().sync
    }

    private fun deviceId(): String = cached.deviceId.ifEmpty { "pending" }

    /**
     * Notes what happened in the history: when, on which device, and what it touched ([kind]). The
     * history is written to disk as it grows, so it is still there after Fuse restarts; it is this
     * device's own and never holds a save's contents, only what happened to it.
     */
    private fun log(text: String, game: String? = null, kind: String = "") {
        val entry = SyncActivity(clock(), text, game, kind, device = cached.deviceName.ifBlank { defaultDeviceName })
        _activity.update { (listOf(entry) + it).take(HISTORY_KEEP) }
        scope.launch(Dispatchers.IO) {
            historyWrite.withLock {
                runCatching {
                    dir.mkdirs()
                    // The newest whole history, whichever write comes last.
                    writeAtomically(File(dir, HISTORY_FILE), json.encodeToString(ListSerializer(SyncActivity.serializer()), _activity.value).toByteArray())
                }
            }
        }
    }

    private val historyWrite = Mutex()

    /** One device id made at a time: Fuse's own start and setting up a host can both ask at once. */
    private val idLock = Mutex()

    private suspend fun ensureDeviceId(): String = idLock.withLock {
        val c = config()
        if (c.deviceId.isNotEmpty()) return@withLock c.deviceId
        val id = "dev-" + SyncCrypto.token(12)
        saveConfig { it.copy(deviceId = id) }
        id
    }

    private suspend fun link(): HostLink? = secrets.get(LINK_KEY)?.let { runCatching { json.decodeFromString(HostLink.serializer(), it) }.getOrNull() }

    // ---------------------------------------------------------------- starting and stopping

    private suspend fun start() {
        val c = config()
        if (!c.enabled || c.role.isEmpty()) {
            _status.value = if (!c.enabled) SyncStatus.Off else SyncStatus.NotSetUp
            showLocal()
            resumeMerge()
            return
        }
        val id = ensureDeviceId()
        if (device == null) device = newDevice(id, c.deviceName.ifBlank { defaultDeviceName })
        refreshUnclaimed()
        if (c.role == "HOST") startHostServer(c)
        val l = link()
        if (l == null) {
            _status.value = SyncStatus.NotSetUp
            showLocal()
            resumeMerge()
            return
        }
        if (_profiles.value.isEmpty()) {
            val known = knownProfiles()
            _devices.value = knownDevices()
            showPeople(known, c.activeProfile)
        }
        retire(client)
        client = linked(l.copy(localAddress = c.localAddress.ifBlank { l.localAddress }, remoteAddress = c.remoteAddress.ifBlank { l.remoteAddress }))
        _status.value = SyncStatus.Connecting(c.hostName.ifBlank { l.hostName })
        startLoop()
        startKeeper()
        // A host set up before Admin existed gets it too (this computer keeps the profile it uses).
        if (c.role == "HOST") scope.launch(Dispatchers.IO) { if (adoptHost() != null) runCatching { refreshLists() } }
    }

    /**
     * Without a host: this device's own profiles on screen, and the device that keeps each
     * person's saves and records (made with the first profile).
     */
    private suspend fun showLocal() {
        if (people.profiles.isEmpty()) {
            if (client == null) {
                _profiles.value = emptyList()
                _active.value = null
            }
            return
        }
        if (device == null) device = newDevice(ensureDeviceId(), cached.deviceName.ifBlank { defaultDeviceName })
        refreshUnclaimed()
        showPeople(people.ordered(people.profiles.map { it.info }), cached.activeProfile)
    }

    /**
     * Shows [list] and who of them is playing, the one playing first: whoever sees the people
     * already sees who is chosen, never a moment with nobody.
     */
    private fun showPeople(list: List<ProfileInfo>, active: String) {
        _active.value = list.firstOrNull { it.id == active }
        _profiles.value = list
    }

    /** True while this device keeps its own profiles (no host yet, or none any more). */
    private val keepsOwn: Boolean get() = client == null && people.profiles.isNotEmpty()

    private fun localProfile(id: String): LocalProfile? = people.profiles.firstOrNull { it.id == id }

    private val pinFails = HashMap<String, Pair<Int, Long>>()

    /** Checks a PIN kept here: wrong ones slow down after three, as on a host. */
    private fun checkPin(p: LocalProfile, pin: String?) {
        val hash = p.pinHash ?: return
        val now = clock()
        val (count, until) = synchronized(pinFails) { pinFails[p.id] ?: (0 to 0L) }
        if (now < until) throw SyncException("Too many tries. Wait ${(until - now + 999) / 1000} s.", "wait", 0)
        if (pin == null || !SyncCrypto.verifySecret(pin, hash)) {
            val next = count + 1
            val pause = if (next < 3) 0L else (1_000L shl (next - 3).coerceAtMost(8))
            synchronized(pinFails) { pinFails[p.id] = next to now + pause }
            throw SyncException("That PIN isn't right.", "wrong-pin", 0)
        }
        synchronized(pinFails) { pinFails.remove(p.id) }
    }

    /** A PIN typed here for a host's profile, kept hashed so the person keeps it if this device leaves. */
    private fun rememberPin(id: String, pin: String?) {
        if (pin.isNullOrBlank()) return
        runCatching { savePeople(people.copy(pins = people.pins + (id to SyncCrypto.hashSecret(pin)))) }
    }

    /** Where saves of people removed here go, as plain files: Fuse Sync's kept folder. */
    private fun keptFolder(): File = File(File(dir, KEPT_DIR), keptFolderName())

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
    /** One start of the host at a time: setting up a host and Fuse's own start-up can both ask at once. */
    private val hostStart = Mutex()

    private suspend fun startHostServer(c: SyncSettings) = hostStart.withLock { startHostServerOnce(c) }

    private suspend fun startHostServerOnce(c: SyncSettings) {
        if (hostServer != null) return
        val hostDir = hostDir(c)
        // The service already runs the host: never open its files from a second process.
        HostAdmin.of(hostDir, c.hostPort)?.let { admin ->
            // It may still be starting (just after the computer did): give it a moment to answer.
            var answer: HostStatus? = null
            for (attempt in 0 until ADMIN_TRIES) {
                answer = runCatching { admin.status() }.getOrNull()
                if (answer != null || HostAdmin.portFree(c.hostPort)) break
                delay(ADMIN_RETRY_MS)
            }
            if (answer != null) {
                // The service runs the Fuse it was set up with (an AppImage of its own, say): after an
                // update it is started again on this one, so the host has what this version has.
                if (olderThanThis(answer.hello.fuseVersion) && lifetime.supported) {
                    restartedService(c, answer.hello.fuseVersion)?.let { (newer, status) ->
                        hostAdmin = newer
                        adminStatus = status
                        refreshHostView()
                        return
                    }
                }
                hostAdmin = admin
                adminStatus = answer
                refreshHostView()
                return
            }
            // The port is held but the host didn't answer: when that is Fuse's own service (stuck, or
            // an older Fuse that answers differently), it is started again on this Fuse.
            if (!HostAdmin.portFree(c.hostPort) && lifetime.supported && lifetime.state().installed) {
                restartedService(c, NOT_ANSWERING)?.let { (newer, status) ->
                    hostAdmin = newer
                    adminStatus = status
                    refreshHostView()
                    return
                }
            }
            // Something else holds the port (or the service went away just now): serve here if the port is free again.
            if (!HostAdmin.portFree(c.hostPort)) {
                log("The host can't start: another program on this computer is using port ${c.hostPort}, which the host needs", kind = SyncActivity.HOST)
                refreshHostView()
                return
            }
        }
        val store = HostStore(hostDir, clock, hostName = c.hostName.ifBlank { defaultDeviceName })
        val server = runCatching { SyncHost(store, c.hostPort, fuseVersion, clock).start() }.getOrNull()
        if (server != null) {
            hostServer = server
            server.backups = HouseholdBackups(backupsDir(c), clock)
            store.leadership.noteOwnAddress(lanAddresses().firstOrNull()?.let { "$it:${c.hostPort}" }, store.outsideAddress().ifBlank { null })
            responder = Discovery.answer({ store.hello(c.hostPort, fuseVersion) })
            // Off the interface's thread: the host's status reads its own files.
            scope.launch(Dispatchers.IO) { server.changes.collect { refreshHostView() } }
            // A host belongs to the system, not to Fuse being open: one set up before this, or
            // without its service, is handed to the service now (unless the person took it away).
            if (!c.hostInFuseOnly && !triedService && lifetime.supported && !lifetime.state().installed) {
                // Once a run: a service that won't start leaves the host here, not in a loop.
                triedService = true
                scope.launch(Dispatchers.IO) {
                    if (hostServer === server) runCatching { handOver() }.onSuccess { refreshHostView() }
                }
            }
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
            outside = server?.store?.outsideAddress() ?: adminStatus?.hello?.outside.orEmpty(),
            accountName = server?.store?.accountName() ?: adminAccount ?: "".takeIf { adminStatus?.hello?.account == true },
        )
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch(Dispatchers.IO) {
            var backoff = 2_000L
            var joinsChecked = false
            while (true) {
                // Asleep, or under a game, nothing is asked of the host: the first round after catches up on everything.
                roundsMayRun.awaitAwake()
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
                    // Back after being away (or just started): every save that moved on meanwhile.
                    if (!caughtUp) {
                        caughtUp = true
                        scope.launch(Dispatchers.IO) { runCatching { convergeAll() } }
                        scope.launch(Dispatchers.IO) { householdConnect() }
                    }
                    // The wait ends when the device goes to sleep or a game starts: its answer would only wake it again.
                    val page = roundsMayRun.untilAsleep { c.events(since, waitSeconds = 25) } ?: continue
                    householdHere.woken(page.wake)
                    if (HouseholdHost.WAKE_PLAYING in page.wake) scope.launch(Dispatchers.IO) { runCatching { lookForHosted(c) } }
                    // A device checking in is no event, so when each was last seen (and so which are
                    // online, and whose games show) is read again now and then: it stayed as it was
                    // when the list was last read, "seen 5:05" on devices online all along.
                    if (clock() - listsReadAt > LISTS_EVERY_MS) runCatching { refreshLists() }
                    // The household's games didn't come up on the way in (the host didn't answer that
                    // once): asked again, so this device doesn't go without the other devices' games.
                    if (!householdHere.supported.value && clock() - householdTriedAt > HOUSEHOLD_RETRY_MS) scope.launch(Dispatchers.IO) { householdConnect() }
                    householdHere.problem.let { p ->
                        if (p != listsProblem) {
                            if (p != null) log("The other devices' games couldn't be read: $p. Trying again shortly.") else if (listsProblem != null) log("The other devices' games are here again")
                            listsProblem = p
                        }
                    }
                    if (page.events.isNotEmpty()) {
                        convergeLater(page.events)
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
                    caughtUp = false
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } catch (e: Exception) {
                    caughtUp = false
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    // ---------------------------------------------------------------- convergence

    /** Set once the saves that moved on while this device was away were looked at. */
    @Volatile private var caughtUp = false

    /** Profiles whose saves this device never brings in: it still sends theirs ([saveChoices]). */
    @Volatile private var pullOff: Set<String> = emptySet()

    /** Profiles whose saves never move here, either way ([saveChoices]). */
    @Volatile private var saveOff: Set<String> = emptySet()

    override fun saveChoices(pullOff: Set<String>, off: Set<String>) {
        this.pullOff = pullOff
        this.saveOff = off
    }

    /**
     * Whether this device brings in [owner]'s saves (the household's shared ones always), for [game]
     * unless the person keeps that game's saves on this device only.
     */
    private fun takesFrom(owner: String, game: String? = null): Boolean =
        !localOnly(game) && (owner == SHARED_SAVES || (owner !in pullOff && owner !in saveOff))

    /** Whether this device keeps and sends [owner]'s saves of [game] (the household's shared ones always). */
    private fun sendsFor(owner: String, game: String? = null): Boolean =
        !localOnly(game) && !(game != null && game in cached.receiveOnlyGames) && (owner == SHARED_SAVES || owner !in saveOff)

    /**
     * [game] keeps its saves on this device (or the person syncs it on none of theirs): never sent,
     * never brought in (each person's still swap here).
     */
    private fun localOnly(game: String?): Boolean = game != null && (game in cached.localOnlyGames || game in cached.offEverywhereGames || game in elsewhere)

    /** Games whose saves another way moves here ([savesMovedElsewhere]). */
    @Volatile private var elsewhere: Set<String> = emptySet()

    override fun savesMovedElsewhere(games: Set<String>) {
        elsewhere = games
    }

    override suspend fun oldSaveFolders(query: SaveQuery): List<OldSave> = withContext(Dispatchers.IO) { OldSaveFolders.find(canonical(query), saveEnv) }

    override suspend fun bringIntoUse(query: SaveQuery, old: OldSave): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val q = canonical(query)
            val title = q.title.ifBlank { "This game" }
            check(q.game.id !in busy) { "$title is playing. Bring the save in once it has closed." }
            // Looked for again: only a folder that still holds a newer save, as it is now.
            val now = OldSaveFolders.find(q, saveEnv).firstOrNull { it.where == old.where } ?: error("That folder has no newer save of $title any more.")
            val kept = OldSaveMove.bringIn(q.game, now, File(keptFolder(), safeName(q.title.ifBlank { q.game.id })))
            log("${q.title}: the save from ${now.where} is in use" + (kept?.let { ", the one it replaced kept in ${it.path}" } ?: ""), q.game.id, SyncActivity.RESTORE)
            kept?.path
        }
    }

    @Volatile private var queries: (suspend (String) -> SaveQuery?)? = null

    /** Games between "about to start" and "stopped and sent": never written to in the background. */
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** One background pass at a time, so a burst of saves is one tidy round. */
    private val converging = Mutex()

    override fun saveQueries(provider: suspend (gameId: String) -> SaveQuery?) {
        queries = provider
        caughtUp = false
    }

    /** Saves another device just made, for the person here (or the household's shared ones): put in place soon. */
    private fun convergeLater(events: List<JournalEvent>) {
        val active = cached.activeProfile
        if (active.isEmpty() || !cached.saves || queries == null) return
        val games = events.filter { it.type == JournalEvent.REVISION && it.device != cached.deviceId && ((it.profile == active && takesFrom(active)) || it.profile == SHARED_SAVES) }
            .mapNotNull { it.game }.distinct().filterNot(::localOnly)
        if (games.isEmpty()) return
        scope.launch(Dispatchers.IO) { converging.withLock { for (g in games) runCatching { convergeGame(g) } } }
    }

    override fun gamesArrived(keys: Collection<String>) {
        if (keys.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            val active = cached.activeProfile.ifEmpty { return@launch }
            if (!cached.saves || queries == null) return@launch
            // One question to the host for which of them it keeps saves of, then only those.
            val mine = if (takesFrom(active)) runCatching { c.heads(active).heads }.getOrDefault(emptyList()) else emptyList()
            val held = (mine + runCatching { c.heads(SHARED_SAVES).heads }.getOrDefault(emptyList())).map { it.game }.toSet()
            val games = keys.distinct().filter { it in held }.filterNot(::localOnly)
            if (games.isEmpty()) return@launch
            converging.withLock { for (g in games) runCatching { convergeGame(g) } }
        }
    }

    /** Every save the host has for the person here, looked at once: catching up after being offline, or joining. */
    private suspend fun convergeAll() {
        val c = client ?: return
        val active = cached.activeProfile.ifEmpty { return }
        if (!cached.saves || queries == null) return
        val mine = if (takesFrom(active)) c.heads(active).heads else emptyList()
        val games = (mine + runCatching { c.heads(SHARED_SAVES).heads }.getOrDefault(emptyList()))
            .filter { it.device != cached.deviceId }.map { it.game }.distinct().filterNot(::localOnly)
        converging.withLock { for (g in games) runCatching { convergeGame(g) } }
    }

    /**
     * Puts the newest save of [gameId] in place here, quietly, when this device has the game and
     * isn't playing it, then tells the host where this device stands. A conflict is never settled
     * here: it waits for the person at the next launch, with both saves kept.
     */
    private suspend fun convergeGame(gameId: String) {
        val provider = queries ?: return
        val c = client ?: return
        val d = device ?: return
        val profile = cached.activeProfile.ifEmpty { return }
        if (gameId in busy || watch?.query?.game?.id == gameId) return
        val query = canonical(provider(gameId) ?: return)
        if (query.game.id in busy) return
        val notes = ArrayList<AppliedNote>()
        runCatching { learnFromHousehold(c, d, query, profile) }
        for (slot in slots(query)) {
            val owner = ownerOf(query, slot, profile)
            // This person's saves aren't taken here (Settings, Fuse Sync, Saves on this device).
            if (!takesFrom(owner, query.game.id)) continue
            val result = runCatching { d.converge(c, owner, slot) }.getOrNull() ?: continue
            if (result is PrepareResult.Updated) log("${query.title}: the newest save from ${result.revision.deviceName} is in place", query.game.id, "save")
            d.appliedNote(owner, slot, result, clock())?.let(notes::add)
        }
        runCatching { c.noteApplied(notes) }
    }

    override suspend fun footprint(): SyncFootprint? = withContext(Dispatchers.IO) {
        val d = device
        val host = hostServer?.store?.content
        val kept = File(dir, KEPT_DIR).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        if (d == null && host == null && kept == 0L) return@withContext null
        SyncFootprint(
            deviceBytes = d?.store?.totalBytes() ?: 0L,
            deviceFiles = d?.store?.count() ?: 0,
            hostBytes = host?.totalBytes(),
            hostFiles = host?.count(),
            keptBytes = kept,
        )
    }

    override suspend fun householdServices(): HouseholdShared? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        runCatching { c.services() }.getOrNull()?.let { (s, open) -> HouseholdShared(s, open) }
    }

    override suspend fun shareServices(romm: ServiceAddress?, jellyfin: ServiceAddress?, signIns: Map<String, String>, declined: Boolean): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext false
        runCatching { c.shareServices(romm, jellyfin, signIns, declined) }.getOrDefault(false)
    }

    override suspend fun convergence(game: GameKey): Convergence? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val profile = cached.activeProfile.ifEmpty { return@withContext null }
        runCatching { c.convergence(profile, canonical(SaveQuery(game, game.platform, "", "")).game.id) }.getOrNull()
    }

    private fun handle(e: SyncException) {
        val name = cached.hostName
        val active = cached.activeProfile
        // The profile in use was deleted on another device: this device simply stops using it
        // (everything here stays as it is) and asks who is playing.
        if (e.code == "locked" && active.isNotEmpty() && _profiles.value.isNotEmpty() && _profiles.value.none { it.id == active }) {
            scope.launch(Dispatchers.IO) {
                // A switch to it the host is still checking knows who was here before: it goes back there.
                if (active in checking) return@launch
                if (runCatching { leave(active) }.getOrDefault(false)) {
                    log("The profile in use here was deleted on another device. Choose who's playing.", kind = SyncActivity.PROFILE)
                }
            }
            return
        }
        _status.value = when (e.code) {
            "offline" -> SyncStatus.Offline(name, device?.pendingCount ?: 0, (status.value as? SyncStatus.Offline)?.since ?: clock())
            // Asked to slow down: nothing is wrong, the next round simply waits a little longer.
            "rate" -> return
            "revoked" -> SyncStatus.NeedsAttention(name, "This device was unlinked from $name. Connect it again from Settings, Fuse One.", e.code)
            "clock" -> SyncStatus.NeedsAttention(name, "This device's clock is far from the host's. Set the date and time, and syncing carries on.", e.code)
            "locked" -> SyncStatus.NeedsAttention(name, "This profile's PIN changed on another device. Open it again with the new PIN to keep syncing.", e.code)
            else -> SyncStatus.NeedsAttention(name, e.message ?: "The host said no.", e.code)
        }
    }

    @Volatile private var householdTriedAt = 0L
    @Volatile private var householdProblem: String? = null
    @Volatile private var listsProblem: String? = null

    /** Starts the household's side (the other devices' games); says once in the log when it can't, and when it can again. */
    private suspend fun householdConnect() {
        householdTriedAt = clock()
        val problem = runCatching { householdHere.connected() }.getOrElse { it.message ?: it::class.simpleName ?: "it failed" }
        if (problem != null && problem != householdProblem) log("The other devices' games couldn't be read: $problem. Trying again shortly.")
        if (problem == null && householdProblem != null) log("The other devices' games are here again")
        householdProblem = problem
    }

    /** When the devices and profiles were last read from the host. */
    @Volatile private var listsReadAt = 0L

    private suspend fun refreshLists() {
        listsReadAt = clock()
        listsLock.withLock {
            val c = client ?: return@withLock
            val list = people.ordered(c.profiles())
            val devices = runCatching { c.devices() }
            synchronized(showing) {
                // Forgotten while it answered (turned off, or left): what it said is no longer this device's.
                if (client !== c) return@withLock
                devices.onSuccess { d ->
                    _devices.value = d
                    writeAtomically(File(dir, DEVICES_FILE), json.encodeToString(ListSerializer(DeviceInfo.serializer()), d).toByteArray())
                }
                showPeople(list, cached.activeProfile)
                // Kept, so who is playing shows (and Who's playing? has faces) while the host is away.
                runCatching { writeAtomically(File(dir, PROFILES_FILE), json.encodeToString(ListSerializer(ProfileInfo.serializer()), _profiles.value).toByteArray()) }
            }
        }
    }

    /** Remembered membership remains available to send pickers while the host is offline. */
    private fun knownDevices(): List<DeviceInfo> = runCatching {
        json.decodeFromString(ListSerializer(DeviceInfo.serializer()), File(dir, DEVICES_FILE).readText())
    }.getOrDefault(emptyList())

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
        runCatching { adoptOutside(c) }
        if (active.isNotEmpty()) asActive(active) { captureChanges(active) }
        if (active.isNotEmpty()) runCatching { captureOutside(c, d, active) }
        runCatching { c.sharedGames().games }.onSuccess { games ->
            if (games.toSet() != cached.sharedGames.toSet()) saveConfig { it.copy(sharedGames = games) }
            _sharedGames.value = games.toSet()
        }
        val sent = d.flush(c, saves = cached.saves)
        if (active.isNotEmpty()) {
            d.pullMeta(c, active)
            // Laid in place only if they are still the one playing (a switch may have happened meanwhile).
            asActive(active) { layInPlace(d, active) }
        }
        if (sent > 0) log(if (sent == 1) "Sent 1 change to $name" else "Sent $sent changes to $name", kind = SyncActivity.CHANGES)
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = false, pending = d.pendingCount)
        _rounds.update { it + 1 }
    }

    private val _rounds = MutableStateFlow(0)
    override val rounds: StateFlow<Int> = _rounds.asStateFlow()

    /** Sends what is queued (saves included) without bringing anything in. */
    private suspend fun sendWaiting() = work.withLock {
        val c = client ?: return@withLock
        val d = device ?: return@withLock
        if (d.pendingCount == 0) return@withLock
        val name = cached.hostName.ifBlank { c.link.hostName }
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = true, pending = d.pendingCount)
        val sent = d.flush(c, saves = cached.saves)
        if (sent > 0) log(if (sent == 1) "Sent 1 change to $name" else "Sent $sent changes to $name", kind = SyncActivity.CHANGES)
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = false, pending = d.pendingCount)
    }

    private suspend fun pullActive() = work.withLock {
        val c = client ?: return@withLock
        val d = device ?: return@withLock
        val active = cached.activeProfile.ifEmpty { return@withLock }
        asActive(active) { captureChanges(active) }
        d.pullMeta(c, active)
        asActive(active) { layInPlace(d, active) }
        log("Brought in changes from your other devices", kind = SyncActivity.CHANGES)
    }

    /** When the last look for saves made outside Fuse ran ([captureOutside]). */
    @Volatile private var lookedOutsideAt = 0L

    /**
     * Saves made while Fuse wasn't watching: a game started from Steam, ES-DE or the emulator itself
     * never passes through [afterExit], so its save stayed here and never went anywhere. Every
     * [OUTSIDE_EVERY_MS], the saves of the games this person syncs (on this device, and the
     * household's) are looked at, and one changed since it was last in step goes, once its files
     * have been left alone for [OUTSIDE_SETTLED_MS] (never one a game is still writing).
     */
    internal suspend fun captureOutside(c: SyncClient, d: SyncDevice, profile: String, force: Boolean = false) {
        if (!cached.saves) return
        val provider = queries ?: return
        val now = clock()
        if (!force && now - lookedOutsideAt < OUTSIDE_EVERY_MS) return
        lookedOutsideAt = now
        val fromHost = if (takesFrom(profile)) runCatching { c.heads(profile).heads.map { it.game } }.getOrDefault(emptyList()) else emptyList()
        val games = (d.gamesWithSaves(profile) + fromHost).filterNot { it in busy || probes.containsKey(it) || localOnly(it) }.toSet()
        var kept = 0
        for (g in games) {
            val query = runCatching { provider(g) }.getOrNull()?.let(::canonical) ?: continue
            val total = canonical(d.meta(profile)).game(query.game).totalSeconds
            for (slot in slots(query)) {
                if (slot.kind == SaveKind.STATE || !slot.available) continue
                val owner = ownerOf(query, slot, profile)
                if (!sendsFor(owner, query.game.id)) continue
                // Someone else's save is in the folder (a device more than one person plays): theirs.
                if (d.holderOf(slot).let { it != null && it != owner }) continue
                val newest = slot.files.maxOfOrNull { it.file.lastModified() } ?: continue
                if (now - newest < OUTSIDE_SETTLED_MS) continue
                val r = runCatching { d.capture(owner, slot, total, title = query.title) }
                    .onFailure { log("${query.title}: a save made outside Fuse couldn't be kept (${it.message})", query.game.id, SyncActivity.FAILED) }
                    .getOrNull() ?: continue
                kept++
                log("${query.title}: found a ${r.kind.label.lowercase()} made outside Fuse, and kept it", query.game.id, "save")
            }
        }
        if (kept > 0) runCatching { d.flush(c, saves = cached.saves) }.onFailure { if (it is SyncException) handle(it) }
    }

    /**
     * What was last laid into the library for each profile, with what has been taken from the
     * library since ([captureChanges]). The library's changes are measured against this, never
     * against records brought in from the host and not yet laid in place: measured against those, a
     * favourite made on another device read as this one taking it back, stamped now, and replaced
     * the person's choice everywhere. Until something is laid in place (after a restart), what this
     * device keeps of the profile stands for it.
     */
    private val placed = java.util.concurrent.ConcurrentHashMap<String, ProfileMeta>()

    /** Lays [profile]'s records, settings and Home in the library, and remembers what was laid. */
    private suspend fun layInPlace(d: SyncDevice, profile: String) {
        val meta = canonical(d.meta(profile))
        data.write(meta)
        placed[profile] = meta
    }

    /** What changed here since the profile was put in place becomes changes waiting to go. */
    private suspend fun captureChanges(profile: String) {
        val d = device ?: return
        val local = data.read(d.deviceId, hlc)
        val c = cached
        // Compared by the household's one id for each game: records kept here under another id
        // (from before the host settled it) are the same game, never a new one.
        val kept = canonical(d.meta(profile))
        hlc.seen(kept.latestRevision())
        val base = placed[profile]?.let(::canonical) ?: kept
        // Only what this device syncs: with records off it reads none, which must never read as
        // every collection deleted (or settings, with settings off).
        val changes = ProfileDiff.changes(base, local, d.deviceId, hlc).let { all ->
            all.copy(
                games = if (c.records) all.games else emptyMap(),
                collections = if (c.records) all.collections else emptyMap(),
                settings = if (c.settings) all.settings else emptyMap(),
            )
        }
        if (!ProfileDiff.isEmpty(changes)) {
            d.changeMeta(profile) { pending, _ -> pending.merge(changes) }
            placed[profile] = base.merge(changes)
        }
    }

    override fun changed() {
        // Many quick changes become one send, a moment later.
        nudge?.cancel()
        nudge = scope.launch(Dispatchers.IO) {
            delay(1_500)
            runCatching { syncOnce() }.onFailure { if (it is SyncException) handle(it) }
        }
    }

    override suspend fun setEnabled(enabled: Boolean, keepProfiles: Boolean): Unit = withContext(Dispatchers.IO) {
        if (!enabled) {
            // Off forgets the host: what this device sends next time starts from nothing. Its own
            // library, settings and Home stay exactly as they are, as plain Fuse, and the people who
            // played here stay as its own profiles (unless they should go too).
            runCatching { cancelMerge() }
            forgetHost(keepProfiles)
            saveConfig { it.copy(enabled = false) }
            _status.value = SyncStatus.Off
            return@withContext
        }
        saveConfig { it.copy(enabled = true) }
        stop()
        client = null
        runCatching { start() }
    }

    /** Where this computer keeps its host's saves and profiles. */
    private fun hostDir(c: SyncSettings): File = c.hostDataDir.ifBlank { null }?.let(::File) ?: File(dir, "host")

    /**
     * Forgets the host and everything kept for it here: the link, which profile was in use, saves
     * waiting to go and other people's saves parked here. With [keepProfiles], the people who played
     * on this device stay as its own profiles, with their records and saves (see [stayLocal]). The
     * game files, the saves in the emulators' folders, the library and the settings and Home in use
     * stay. A host's own data stays on disk ([deleteHost] removes it).
     */
    private suspend fun forgetHost(keepProfiles: Boolean = true) {
        watch?.job?.cancel()
        watch = null
        _nowPlaying.value = null
        cancelJoin()
        val c = client
        if (c != null) {
            runCatching { withTimeoutOrNull(FORGET_WAIT_MS) { device?.flush(c, saves = cached.saves) } }
            if (keepProfiles) bringDownRecords(c)
            runCatching { withTimeoutOrNull(FORGET_WAIT_MS) { c.unlinkSelf() } }
        }
        stop()
        if (cached.role == "HOST" && lifetime.state().installed) runCatching { lifetime.remove() }
        dropClient()
        // The people who played here stay as this device's own (their unsent saves go to the next host).
        val stay = if (keepProfiles) runCatching { stayLocal() }.getOrDefault(emptyList()) else emptyList()
        // Saves that never reached the host (it was away) are this device's only copy: they stay,
        // as plain files, rather than going with the rest.
        val unsent = if (stay.isNotEmpty()) 0 else runCatching { device?.exportUnsent(keptFolder()) ?: 0 }.getOrDefault(0)
        hostAdmin = null
        adminStatus = null
        if (stay.isEmpty()) {
            device = null
            people = LocalPeople()
        }
        runCatching { secrets.remove(LINK_KEY) }
        val keep = hostDir(cached).canonicalFile
        val keepNames = if (stay.isEmpty()) setOf(KEPT_DIR) else setOf(KEPT_DIR, "device", PEOPLE_FILE, ADOPTED_FILE, aliasFile.name)
        // A file another write renames away meanwhile is simply gone (walking it as a tree would fail).
        dir.listFiles()?.filter { it.canonicalFile != keep && it.name !in keepNames }?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        placed.clear()
        if (stay.isEmpty()) {
            gameAliases = emptyMap()
            data.useAliases(emptyMap())
        }
        val active = cached.activeProfile.takeIf { it in stay }.orEmpty()
        saveConfig {
            it.copy(
                role = "", hostName = "", hostId = "", activeProfile = active, localAddress = "", remoteAddress = "", sharedGames = emptyList(),
                // This device's Home is simply its Home now.
                homeScope = "PROFILE", deviceHome = null,
            )
        }
        _active.value = null
        _profiles.value = emptyList()
        showLocal()
        _devices.value = emptyList()
        _joins.value = emptyList()
        _sharedGames.value = emptySet()
        _host.value = null
        log("Fuse One forgot its host", kind = SyncActivity.HOST)
        if (unsent > 0) log(if (unsent == 1) "A save that hadn't reached the host is kept on this device" else "$unsent saves that hadn't reached the host are kept on this device", kind = "save")
    }

    /**
     * Before leaving a host whose profiles stay here: each person's newest records come down from it
     * (a profile with a PIN only when this device has opened it), so everyone kept has their play
     * time, favourites and settings without the host. Saves stay where they are: each person's own
     * in the emulators' folders or parked here.
     */
    private suspend fun bringDownRecords(c: SyncClient) {
        val d = device ?: return
        val people = runCatching { c.profiles() }.getOrDefault(_profiles.value).filterNot { it.hostOnly }
        withTimeoutOrNull(FORGET_WAIT_MS * 2) {
            for (p in people) runCatching { d.pullMeta(c, p.id) }
        }
        if (people.isNotEmpty()) _profiles.value = people
    }

    /**
     * Leaving a host: everyone's profiles (the host's Admin aside) become this device's own, keeping their ids, names, pictures and the PIN as last
     * typed here. Everyone else's saves here go to the kept folder as plain files. The host's own
     * profile (Admin) is never kept. Returns the ids kept. Without a host, its profiles simply stay.
     */
    private suspend fun stayLocal(): List<String> {
        if (people.profiles.isNotEmpty() && pendingLink == null && link() == null) return people.profiles.map { it.id }
        val d = device ?: return emptyList()
        val known = _profiles.value.ifEmpty { knownProfiles() }
        val here = d.people()
        val active = cached.activeProfile
        // Everyone on the host (Admin aside) stays, with what this device has of theirs.
        val stay = known.filter { !it.hostOnly }
        if (stay.isEmpty()) return emptyList()
        val ids = stay.map { it.id }.toSet()
        val kept = keptFolder()
        for (p in here - ids) {
            val name = known.firstOrNull { it.id == p }?.name ?: if (p == SHARED_SAVES) "Everyone" else "Removed"
            d.forget(p, File(kept, safeName(name)))
        }
        d.detach()
        val pins = people.pins
        val noPin = stay.filter { it.protected && pins[it.id] == null }
        savePeople(LocalPeople(stay.map { LocalProfile(it.id, it.name, it.avatar, it.createdAt, pins[it.id]) }, order = stay.map { it.id }))
        markAdopted()
        if (noPin.isNotEmpty()) log("${noPin.joinToString(", ") { it.name }} kept without a PIN here, as it was never typed on this device. Set one in Profiles.", kind = SyncActivity.PROFILE)
        log(if (stay.size == 1) "${stay[0].name} stays on this device as its own profile" else "${stay.size} profiles stay on this device as its own", kind = SyncActivity.PROFILE)
        return stay.map { it.id }
    }

    /** [text] as a safe folder name. */
    /** A folder name for saves kept when the host was forgotten: when it happened, sortable. */
    private fun keptFolderName(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date(clock()))

    override suspend fun deleteHost(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = config()
            require(c.role == "HOST") { "This device isn't the host." }
            val folder = hostDir(c)
            forgetHost()
            // The background service needs a moment to let go of its files.
            delay(HOST_RELEASE_MS)
            // Only what the host kept: a folder the person chose may hold other things too.
            HostFiles.delete(folder)
            saveConfig { it.copy(hostDataDir = "") }
            _status.value = SyncStatus.NotSetUp
        }
    }

    override suspend fun moveHostData(to: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = config()
            val from = hostDir(c).canonicalFile
            val target = File(to.trim()).absoluteFile
            require(to.isNotBlank()) { "Choose a folder." }
            require(target.canonicalFile != from) { "That's where the saves are already." }
            require(!target.canonicalPath.startsWith(from.canonicalPath + File.separator)) { "Choose a folder outside the one in use." }
            require(!target.exists() || target.listFiles().isNullOrEmpty()) { "Choose an empty folder, or a new one." }
            target.mkdirs()
            require(target.canWrite()) { "Fuse can't write to that folder." }
            val hosting = c.role == "HOST" && (hostServer != null || hostAdmin != null)
            val service = hosting && lifetime.state().installed
            if (hosting) {
                // The host stops for the move, so nothing changes while it is copied.
                hostServer?.stop()
                responder?.stop()
                hostServer = null
                responder = null
                if (service) lifetime.remove()
                hostAdmin = null
                delay(HOST_RELEASE_MS)
            }
            try {
                // Only the host's own files move: anything else in a folder the person chose stays.
                val moving = if (from.isDirectory) HostFiles.own(from) else emptyList()
                for (entry in moving) {
                    entry.copyRecursively(File(target, entry.name), overwrite = false)
                    // Every file arrived whole before the old ones go.
                    entry.walkTopDown().filter { it.isFile }.forEach { f ->
                        val copy = File(target, f.relativeTo(from).path)
                        check(copy.isFile && copy.length() == f.length()) { "A file didn't copy: ${f.name}. Nothing was moved." }
                    }
                }
                saveConfig { it.copy(hostDataDir = target.path.replace('\\', '/')) }
                if (from.isDirectory) HostFiles.delete(from)
            } catch (e: Exception) {
                if (target.canonicalFile != from) target.listFiles()?.forEach { it.deleteRecursively() }
                throw e
            } finally {
                if (hosting) {
                    if (service) handOver() else startHostServer(config())
                }
            }
            refreshHostView()
            log("The host's saves moved to ${target.path}", kind = SyncActivity.HOST)
            target.path
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
            log("This device stopped being the host", kind = SyncActivity.HOST)
        }
    }

    override fun stop() {
        loop?.cancel()
        nudge?.cancel()
        keeperJob?.cancel()
        householdHere.off()
        hostServer?.stop()
        responder?.stop()
        backupServer?.stop()
        hostServer = null
        responder = null
        backupServer = null
        backupStore = null
        keeper = null
    }

    // ---------------------------------------------------------------- backup hosts

    /** A client for [l] that keeps the link saved whenever it learns of a newer host or the household's hosts. */
    private fun linked(l: HostLink): SyncClient = SyncClient(l).also { c ->
        c.onLinkChange = { next -> scope.launch(Dispatchers.IO) { runCatching { secrets.put(LINK_KEY, json.encodeToString(HostLink.serializer(), next)) } } }
    }

    // ---------------------------------------------------------------- household backups

    private fun backupsDir(c: SyncSettings): File = c.householdBackupDir.ifBlank { null }?.let(::File) ?: File(dir, HOUSEHOLD_BACKUPS_DIR)

    private val _householdBackups = MutableStateFlow<HouseholdBackupsState?>(null)
    override val householdBackups: StateFlow<HouseholdBackupsState?> = _householdBackups.asStateFlow()

    override suspend fun refreshHouseholdBackups() {
        _householdBackups.value = withContext(Dispatchers.IO) {
            hostServer?.householdBackups() ?: hostAdmin?.let { a -> runCatching { a.householdBackups() }.getOrNull() }
        }
    }

    override suspend fun backUpHouseholdNow(): Result<HouseholdSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val server = hostServer
            val snap = if (server != null) {
                checkNotNull(server.backUp(listOf(HouseholdBackups.MANUAL))) { server.householdBackups()?.problem ?: "This device isn't the host." }
            } else {
                checkNotNull(hostAdmin) { "This device isn't the host." }.backUpNow()
            }
            log("The household was backed up", kind = SyncActivity.HOST)
            refreshHouseholdBackups()
            snap
        }
    }

    override suspend fun setHouseholdBackupFolder(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = config()
            require(c.role == "HOST") { "This device isn't the host." }
            val target = File(path.trim()).absoluteFile
            require(path.isNotBlank()) { "Choose a folder." }
            target.mkdirs()
            require(target.isDirectory && target.canWrite()) { "Fuse can't write to that folder." }
            require(!target.canonicalPath.startsWith(hostDir(c).canonicalPath + File.separator) && target.canonicalFile != hostDir(c).canonicalFile) {
                "Choose a folder outside the host's own."
            }
            saveConfig { it.copy(householdBackupDir = target.path.replace('\\', '/')) }
            hostServer?.backups = HouseholdBackups(target, clock)
            // The background host reads the folder as it starts: started again to take it.
            if (hostServer == null && hostAdmin != null && lifetime.supported) restartedService(config(), fuseVersion)?.let { (a, st) -> hostAdmin = a; adminStatus = st }
            log("The household's backups go to ${target.path}", kind = SyncActivity.HOST)
            refreshHouseholdBackups()
        }
    }

    override suspend fun restoreHousehold(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val server = hostServer
            if (server != null) server.restoreHousehold(name) else checkNotNull(hostAdmin) { "This device isn't the host." }.restoreHousehold(name)
            log("The household was put back as a backup kept it", kind = SyncActivity.HOST)
            refreshHouseholdBackups()
            runCatching { refreshLists() }
            refreshHostView()
        }
    }

    override suspend fun startRecovery(kind: String, file: String, password: String): Result<RecoveryJob> = withContext(Dispatchers.IO) {
        runCatching {
            val server = hostServer
            val job = if (server != null) server.startRecovery(kind, java.io.File(file), password)
            else checkNotNull(hostAdmin) { "This device isn't the host." }.startRecovery(kind, file, password)
            log(if (kind == RecoveryJob.WRITE) "Making a recovery package of the household" else "Putting the household back from a recovery package", kind = SyncActivity.HOST)
            job
        }
    }

    override suspend fun recoveryJob(): RecoveryJob? = withContext(Dispatchers.IO) {
        val job = hostServer?.recoveryJob() ?: runCatching { hostAdmin?.recovery() }.getOrNull()
        // A restore that finished: the household here is another now, so everything is looked at again.
        if (job != null && job.kind == RecoveryJob.RESTORE && job.state == RecoveryJob.DONE && seenRestore != job.startedAt) {
            seenRestore = job.startedAt
            runCatching { refreshLists() }
            refreshHostView()
        }
        job
    }

    @Volatile private var seenRestore = 0L

    /**
     * Artwork through the host, kept to a budget so it never crowds out saves: a few at once, a few
     * hundred a minute, and nothing for a minute after the host said it has had enough.
     */
    private val artGate = kotlinx.coroutines.sync.Semaphore(4)
    @Volatile private var artMinute = 0L
    @Volatile private var artCalls = 0
    @Volatile private var artPausedUntil = 0L

    private fun artAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now < artPausedUntil) return false
        val minute = now / 60_000
        if (minute != artMinute) { artMinute = minute; artCalls = 0 }
        return ++artCalls <= ART_PER_MINUTE
    }

    private suspend fun <T> artCall(block: suspend (SyncClient) -> T): T? {
        val c = client ?: return null
        if (!cached.enabled || !artAllowed()) return null
        return artGate.withPermit {
            try {
                block(c)
            } catch (e: SyncException) {
                // Too many calls, or the host away: pictures come from the providers for a while,
                // never waiting on a host that doesn't answer.
                artPausedUntil = System.currentTimeMillis() + if (e.code == "rate" || e.status == 429) 60_000 else ART_AWAY_MS
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                artPausedUntil = System.currentTimeMillis() + ART_AWAY_MS
                null
            }
        }
    }

    override suspend fun householdArt(key: String): ByteArray? = withContext(Dispatchers.IO) { artCall { it.artwork(key) } }

    override suspend fun shareArt(key: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) { artCall { it.shareArtwork(key, bytes) } }
    }

    override suspend fun peekRecoveryPackage(file: String): Pair<String, Long>? = withContext(Dispatchers.IO) { RecoveryPackage.peek(java.io.File(file)) }

    private val _hosts = MutableStateFlow<HostsView?>(null)
    override val hosts: StateFlow<HostsView?> = _hosts.asStateFlow()

    /** This computer's copy of the household while it is a backup (a folder of its own), and the server the other hosts reach it on. */
    @Volatile private var backupStore: HostStore? = null
    @Volatile private var backupServer: SyncHost? = null
    @Volatile private var keeper: BackupNode? = null
    private var keeperJob: Job? = null
    @Volatile private var backupWaiting = false
    @Volatile private var keeperProblem: String? = null
    @Volatile private var copiedAt = 0L
    private val keeping = Mutex()

    private fun backupDir(): File = File(dir, BACKUP_DIR)

    /** The household this computer keeps: the host's own, or its copy as a backup. */
    private fun keptStore(): HostStore? = hostServer?.store ?: backupStore

    private fun startKeeper() {
        keeperJob?.cancel()
        keeperJob = scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { keepOnce() }
                delay(KEEP_EVERY_MS)
            }
        }
    }

    /**
     * One round of keeping the household: a backup's offer sent and its copy brought up to date, an
     * election when the host is gone and a majority agrees, and the host bringing back an older host.
     */
    internal suspend fun keepOnce() = keeping.withLock {
        val c = cached
        val cl = client
        // The household's whole copy, when one is due (the host only).
        hostServer?.let { h -> if (h.backUpIfDue() != null) log("The household was backed up", kind = SyncActivity.HOST) }
        refreshHouseholdBackups()
        if (c.backupHost && canHost && c.role != "HOST" && backupStore == null) openBackup(c)
        val store = keptStore()
        if (store == null || cl == null) {
            refreshHosts()
            return@withLock
        }
        val node = keeper?.takeIf { it.store === store } ?: BackupNode(store, cl, clock = clock).also { keeper = it }
        if (store === backupStore && !store.leadership.isLeader) {
            // Accepted by the host yet? Until then the offer waits there.
            val state = runCatching { cl.backupState() }.getOrNull()
            if (state != null && !state.backup) {
                val offered = if (state.offered) state else runCatching { cl.offerBackup(ownNode(store, c)) }.getOrNull()
                backupWaiting = offered?.backup != true
                keeperProblem = null
                refreshHosts()
                return@withLock
            }
            backupWaiting = false
        }
        val before = store.seq()
        val round = node.watch()
        if (round == BackupNode.Round.COPIED || round == BackupNode.Round.UP_TO_DATE) copiedAt = clock()
        if (round == BackupNode.Round.COPIED && store.seq() != before) keeperProblem = null
        if (store === backupStore && store.leadership.isLeader) becomeHost(c)
        refreshHosts()
    }

    /** This computer as one of the household's hosts: its id, name, this device and where the others reach it. */
    private fun ownNode(store: HostStore, c: SyncSettings): HostNode =
        HostNode(store.leadership.nodeId, c.deviceName.ifBlank { defaultDeviceName }, c.deviceId, local = lanAddresses().firstOrNull()?.let { "$it:${c.hostPort}" })

    /** Opens this computer's copy of the household and the server the other hosts reach it on. */
    private fun openBackup(c: SyncSettings) {
        if (backupStore != null) return
        val store = HostStore(backupDir(), clock, hostName = c.hostName.ifBlank { "Fuse One" })
        store.leadership.standBy()
        backupStore = store
        backupServer = runCatching { SyncHost(store, c.hostPort, fuseVersion, clock).start() }.getOrNull()
        keeperProblem = if (backupServer == null) "Another program on this computer uses port ${c.hostPort}, which a backup needs to hear the other hosts." else null
    }

    /** This backup was made the household's host: it serves as the host from now on, from its copy. */
    private suspend fun becomeHost(c: SyncSettings) {
        val store = backupStore ?: return
        val server = backupServer ?: SyncHost(store, c.hostPort, fuseVersion, clock).start()
        saveConfig { it.copy(role = "HOST", backupHost = false, hostDataDir = store.dir.path.replace('\\', '/'), hostName = store.name) }
        hostServer = server
        server.backups = HouseholdBackups(backupsDir(config()), clock)
        backupServer = null
        backupStore = null
        keeper = null
        responder?.stop()
        responder = Discovery.answer({ store.hello(c.hostPort, fuseVersion) })
        store.leadership.noteOwnAddress(lanAddresses().firstOrNull()?.let { "$it:${c.hostPort}" }, null)
        scope.launch(Dispatchers.IO) { server.changes.collect { refreshHostView() } }
        log("This computer keeps the household now", kind = SyncActivity.HOST)
        runCatching { client?.failover() }
        refreshHostView()
    }

    private fun refreshHosts() {
        val store = keptStore()
        val cl = client
        val l = store?.leadership?.current ?: cl?.link?.let { Leadership(it.generation, "", it.hosts) } ?: run {
            _hosts.value = null
            return
        }
        val node = keeper
        val now = clock()
        val away = node?.hostSeenAt?.takeIf { store?.leadership?.isLeader == false && now - it > HOST_AWAY_SHOWN_MS } ?: 0L
        val role = store?.leadership?.role
        _hosts.value = HostsView(
            generation = l.generation,
            hosts = l.hosts,
            leader = l.leader,
            thisRole = role,
            thisNode = store?.leadership?.nodeId,
            offers = hostServer?.backupOffers() ?: adminStatus?.backupOffers.orEmpty(),
            backupOn = cached.backupHost,
            waiting = backupWaiting,
            copiedAt = copiedAt,
            hostAwaySince = away,
            canTakeOver = store != null && store === backupStore && role == Leadership.BACKUP && away > 0 && now - away >= TAKE_OVER_AFTER_MS,
            problem = keeperProblem,
        )
    }

    override suspend fun setBackupHost(on: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(canHost) { "Backup hosts are on computers." }
            val c = config()
            require(c.role != "HOST") { "This computer is the household's host." }
            requireNotNull(client) { "Connect to your host first." }
            saveConfig { it.copy(backupHost = on) }
            if (on) {
                keepOnce()
                log("This computer offered to be a backup host", kind = SyncActivity.HOST)
            } else {
                keeping.withLock {
                    runCatching { client?.leaveBackups() }
                    backupServer?.stop()
                    backupServer = null
                    backupStore = null
                    keeper = null
                    backupWaiting = false
                    // The copy holds the household's secrets: it goes with the choice.
                    if (backupDir().isDirectory) HostFiles.delete(backupDir())
                }
                log("This computer stopped being a backup host", kind = SyncActivity.HOST)
                refreshHosts()
            }
        }
    }

    override suspend fun acceptBackup(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val server = hostServer
            if (server != null) {
                checkNotNull(server.acceptBackup(deviceId)) { "That offer isn't waiting any more." }
            } else {
                checkNotNull(hostAdmin) { "This device isn't the host." }.acceptBackup(deviceId)
                adminStatus = runCatching { hostAdmin?.status() }.getOrNull() ?: adminStatus
            }
            log("A backup host was added", kind = SyncActivity.HOST)
            refreshHosts()
            refreshHostView()
        }
    }

    override suspend fun removeBackup(nodeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val server = hostServer
            if (server != null) server.store.removeBackup(nodeId) else checkNotNull(hostAdmin) { "This device isn't the host." }.removeBackup(nodeId)
            adminStatus = runCatching { hostAdmin?.status() }.getOrNull() ?: adminStatus
            log("A backup host was taken out", kind = SyncActivity.HOST)
            refreshHosts()
            refreshHostView()
        }
    }

    override suspend fun takeOverAsHost(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            keeping.withLock {
                val store = checkNotNull(backupStore) { "This computer isn't a backup." }
                check(store.leadership.role == Leadership.BACKUP) { "This computer isn't a backup." }
                val node = keeper?.takeIf { it.store === store } ?: BackupNode(store, checkNotNull(client), clock = clock)
                node.takeOver()
                becomeHost(config())
            }
            refreshHosts()
        }
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

    /**
     * Joins the host [linked] names. With profiles of its own, this device first settles who is who
     * with the host's people: when the host has none (or only its Admin), they all go up as they
     * are; otherwise the person says ([merge]). Until then the host's link waits, kept so a restart
     * asks again.
     */
    private suspend fun linkUp(linked: HostLink): String {
        if (people.profiles.isEmpty()) return linkFor(linked)
        secrets.put(PENDING_LINK_KEY, json.encodeToString(HostLink.serializer(), linked))
        offer(linked)
        return linked.hostName
    }

    /** Asks again about a host joined before a restart, while who is who was still being settled. */
    private suspend fun resumeMerge() {
        if (pendingLink != null || _merge.value != null) return
        val linked = runCatching { secrets.get(PENDING_LINK_KEY)?.let { json.decodeFromString(HostLink.serializer(), it) } }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) { runCatching { offer(linked) } }
    }

    /** What the host has, against this device's profiles: brought at once when the host has nobody, else asked. */
    private suspend fun offer(linked: HostLink) {
        pendingLink = linked
        val c = pendingClient?.takeIf { it.link == linked } ?: SyncClient(linked).also { retire(pendingClient); pendingClient = it }
        if (people.profiles.isEmpty()) {
            finishMerge()
            linkFor(linked)
            return
        }
        val theirs = c.profiles().filterNot { it.hostOnly }
        if (theirs.isEmpty()) {
            val count = people.profiles.size
            bring(linked, c, emptyMap())
            _notices.tryEmit(SyncNotice.Brought(count, linked.hostName))
            return
        }
        val here = people.ordered(people.profiles.map { it.info })
        val suggested = here.mapNotNull { p -> theirs.firstOrNull { sameName(it.name, p.name) }?.let { p.id to it.id } }.toMap()
        _merge.value = ProfileMerge(linked.hostName, here, theirs, suggested)
    }

    override suspend fun bringProfiles(choices: Map<String, MergeChoice>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val linked = pendingLink ?: error("There's no host waiting for these profiles.")
            val c = pendingClient ?: SyncClient(linked).also { pendingClient = it }
            bring(linked, c, choices)
            Unit
        }
    }

    override suspend fun cancelMerge(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = pendingClient ?: pendingLink?.let(::SyncClient)
            // The host forgets this device too, so its list of devices stays true.
            if (c != null) runCatching { withTimeoutOrNull(FORGET_WAIT_MS) { c.unlinkSelf() } }
            if (pendingLink != null) log("Didn't join ${pendingLink?.hostName}. This device keeps its own profiles", kind = SyncActivity.PROFILE)
            finishMerge()
            _status.value = if (cached.enabled) SyncStatus.NotSetUp else SyncStatus.Off
        }
    }

    private suspend fun finishMerge() {
        pendingLink = null
        retire(pendingClient)
        pendingClient = null
        _merge.value = null
        runCatching { secrets.remove(PENDING_LINK_KEY) }
    }

    /**
     * Brings this device's profiles to the host [linked] names, each as [choices] says (unnamed ones
     * go up as new): PINs of profiles to join are checked first, so a wrong one changes nothing.
     * Then new ones are made there (PINs carried as their hash), left-out ones leave this device
     * (their saves to the kept folder), ids move to the host's everywhere here, and the device
     * links up: records and saves go up with the next round, and the person playing stays playing.
     * Returns how many profiles went to the host.
     */
    private suspend fun bring(linked: HostLink, c: SyncClient, choices: Map<String, MergeChoice>): Int {
        val theirs = c.profiles()
        for (ch in choices.values) {
            if (ch !is MergeChoice.Same) continue
            val target = theirs.firstOrNull { it.id == ch.hostProfile && !it.hostOnly }
                ?: throw SyncException("That profile isn't on ${linked.hostName} any more.", "no-profile", 0)
            if (target.protected) c.openProfile(target.id, ch.pin)
            rememberPin(target.id, ch.pin)
        }
        val d = device ?: newDevice(ensureDeviceId(), cached.deviceName.ifBlank { defaultDeviceName }).also { device = it }
        val active = cached.activeProfile
        val ids = HashMap<String, String>()
        work.withLock {
            // What changed under the person playing is theirs, before ids move.
            if (active.isNotEmpty()) runCatching { captureChanges(active) }
            val names = theirs.map { it.name.trim().lowercase() }.toMutableSet()
            val left = ArrayList<LocalProfile>()
            var pins = people.pins
            for (p in people.profiles) {
                when (val ch = choices[p.id] ?: MergeChoice.Add) {
                    is MergeChoice.Same -> {
                        // The host's records win where both say something; these fill the gaps, and play time joins.
                        d.restamp(p.id, Hlc(ADOPTED_AT, 0, d.deviceId))
                        ids[p.id] = ch.hostProfile
                    }
                    MergeChoice.Add -> {
                        var name = p.name
                        var n = 2
                        while (name.trim().lowercase() in names) name = "${p.name.take(36)} ${n++}"
                        val made = c.createProfile(NewProfile(name, p.avatar, pinHash = p.pinHash))
                        names += name.trim().lowercase()
                        ids[p.id] = made.id
                        if (p.pinHash != null) {
                            pins = pins + (made.id to p.pinHash)
                            if (!made.protected) log("${p.name}'s PIN couldn't go to ${linked.hostName}, which runs an older Fuse. Set it again in Profiles.", kind = SyncActivity.PROFILE)
                        }
                    }
                    MergeChoice.LeaveOut -> left += p
                }
            }
            val kept = keptFolder()
            for (p in left) d.forget(p.id, File(kept, safeName(p.name)))
            d.rekey(ids)
            val next = ids[active].orEmpty()
            d.useProfile(next.ifEmpty { null })
            savePeople(LocalPeople(pins = pins.filterKeys { it in ids.values }))
            markAdopted()
            saveConfig { it.copy(activeProfile = next) }
            if (left.isNotEmpty()) log(if (left.size == 1) "${left[0].name} was left out; their saves are in Fuse One's kept folder" else "${left.size} profiles were left out; their saves are in Fuse One's kept folder", kind = SyncActivity.PROFILE)
        }
        finishMerge()
        linkFor(linked)
        _active.value = _profiles.value.firstOrNull { it.id == cached.activeProfile }
        log(if (ids.size == 1) "Your profile is on ${linked.hostName} now" else "Your ${ids.size} profiles are on ${linked.hostName} now", kind = SyncActivity.PROFILE)
        return ids.size
    }

    /** Keeps [linked] as this device's link to its host and starts syncing with it. */
    private suspend fun linkFor(linked: HostLink): String {
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
            log("Connected to ${linked.hostName}", kind = SyncActivity.DEVICE)
        }
        return linked.hostName
    }

    // ---------------------------------------------------------------- one id per game

    private val aliasFile get() = File(dir, "game-aliases.json")
    private val aliasSerializer = GameAliasesSerializer

    /** Every id a game here is known by, to the id the host keeps its saves and records under. */
    @Volatile private var gameAliases: Map<String, String> =
        safeCachedGameAliases(runCatching { json.decodeFromString(aliasSerializer, aliasFile.readText()) }.getOrDefault(emptyMap())).also { data.useAliases(it) }

    /**
     * [meta] by each game's one id across devices: records kept under an id the household has since
     * settled on another (a title, where the serial is now the id) join that game's record, and
     * collections name their games the same way. Merging is safe: counters take the larger, sessions
     * join by id, settings take the later.
     */
    private fun canonical(meta: ProfileMeta): ProfileMeta = data.normalize(meta).byIds(gameAliases)

    /** [q] for the game's one id across devices (as it is when the host hasn't been asked yet). */
    private fun canonical(q: SaveQuery): SaveQuery =
        gameAliases[q.game.id]?.takeIf { it != q.game.id }?.let(GameKey::parse)?.let { q.copy(game = it) } ?: q

    /**
     * Asks the host for the one id of each game here it hasn't settled yet, from every id this
     * device knows it by. A title-only copy waits for stronger evidence before sharing saves.
     */
    private suspend fun resolveGames(c: SyncClient) {
        val lists = runCatching { data.candidates() }.getOrDefault(emptyList()).map { candidates ->
            val strong = candidates.filter { key ->
                key.identity.startsWith("s.") || key.identity.startsWith("h.") ||
                    key.identity.startsWith("r.") || key.identity.startsWith("p.")
            }
            strong.ifEmpty { candidates }.map { it.id }.distinct()
        }.filter { it.isNotEmpty() }
        val known = gameAliases
        val todo = lists.filter { l -> l.any { it !in known } }
        if (todo.isEmpty()) return
        val next = HashMap(known)
        for (chunk in todo.chunked(RESOLVE_CHUNK)) {
            val ids = c.resolveGames(chunk)
            chunk.zip(ids).forEach { (l, canon) ->
                if (canon.isNotEmpty()) l.forEach { id ->
                    // A weaker title alias may already belong to a different strongly identified game.
                    if (!GameKey.parse(id)!!.identity.startsWith("t.") || next[id] == null || next[id] == canon) next[id] = canon
                }
            }
        }
        gameAliases = next
        runCatching { writeAtomically(aliasFile, json.encodeToString(aliasSerializer, next).toByteArray()) }
        data.useAliases(next)
    }

    // ---------------------------------------------------------------- joining without a code

    private val _joins = MutableStateFlow<List<JoinAsk>>(emptyList())
    override val joinRequests: StateFlow<List<JoinAsk>> = _joins.asStateFlow()
    @Volatile private var joining: Pair<JoinSession, String?>? = null

    override suspend fun hostAt(address: String): NearbyHost? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) { SyncClient.hello(address) }?.let { NearbyHost(it.name, it.hostId, address) }
    }

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
            JoinWaiting(session.ticket.hostName, session.match, account = session.ticket.accountSalt != null)
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

    override suspend fun joinWithAccount(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (session, outside) = joining ?: error("Ask the host first.")
            val link = SyncClient.joinWithAccount(session, username, password)
            joining = null
            // The host account's own username and password: this device is signed in as the host,
            // not only let in. A host from before 0.4.4 has no such call, and the device is simply in.
            val probe = SyncClient(link)
            runCatching { probe.signInAsHost(username, password) }
                .onSuccess { log("Signed in as the host", kind = SyncActivity.HOST) }
            probe.close()
            linkUp(if (outside != null) link.copy(remoteAddress = SyncClient.normalise(outside)) else link)
        }
    }

    override suspend fun signInAsHost(username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: throw SyncException("Connect this device to the host first.", "not-connected", 0)
            runCatching { c.signInAsHost(username, password) }.getOrElse { e ->
                // A host from before 0.4.4 doesn't know this call.
                throw if (e is SyncException && e.status == 404) SyncException("The host runs a Fuse from before 0.4.4. Update it first, then sign in here.", "old-host", 404) else e
            }
            refreshLists()
            log("Signed in as the host", kind = SyncActivity.HOST)
        }
    }

    override suspend fun signOutAsHost(): Result<Unit> = withClient { c ->
        c.signOutAsHost()
        refreshLists()
        log("No longer signed in as the host here", kind = SyncActivity.HOST)
    }

    /** True when this device is signed in as the host (it manages the household from here). */
    private fun managing(): Boolean = _devices.value.any { it.id == cached.deviceId && it.manager }

    // ---------------------------------------------------------------- the host's account and outside address

    /** The account's username as the background service's host last said (it isn't in its status). */
    @Volatile private var adminAccount: String? = null

    override suspend fun setHostAccount(username: String, password: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val store = hostServer?.store
            when {
                store != null -> store.setAccount(username, password)
                hostAdmin != null -> hostAdmin!!.setAccount(username, password)
                managing() -> checkNotNull(client) { "Fuse One isn't connected." }.setAccountAsHost(username, password)
                else -> error("Start the host first.")
            }
            adminAccount = username.trim()
            refreshHostView()
            log("The host's account is set", kind = SyncActivity.HOST)
        }
    }

    override suspend fun clearHostAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val store = hostServer?.store
            when {
                store != null -> store.clearAccount()
                hostAdmin != null -> hostAdmin!!.clearAccount()
                else -> error("Start the host first.")
            }
            adminAccount = null
            refreshHostView()
        }
    }

    override suspend fun setOutsideAddress(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = address.trim().takeIf { it.isNotEmpty() }?.let(SyncClient::normalise).orEmpty()
            val store = hostServer?.store
            when {
                store != null -> store.setOutsideAddress(clean)
                hostAdmin != null -> hostAdmin!!.setOutside(clean)
                managing() -> checkNotNull(client) { "Fuse One isn't connected." }.setOutsideAsHost(clean)
                else -> error("Start the host first.")
            }
            adminStatus = runCatching { hostAdmin?.status() }.getOrNull() ?: adminStatus
            // This computer's own link knows it too.
            saveConfig { it.copy(remoteAddress = clean, remoteFromHost = true) }
            refreshHostView()
        }
    }

    @Volatile private var helloAt = 0L

    private val _hostFuse = MutableStateFlow("")
    override val hostFuse: StateFlow<String> = _hostFuse

    /**
     * The host's address from outside, as it says it, becomes this device's (unless the person
     * typed another here): set once on the host, every device can reach it from away.
     */
    private suspend fun adoptOutside(c: SyncClient) {
        // A host on an older Fuse than this one is often updating itself just then (it does once a
        // device runs a newer Fuse): asked again each minute, so what it runs is never long out of date.
        val every = if (olderThanThis(_hostFuse.value) && _hostFuse.value.isNotEmpty()) HELLO_UPDATING_MS else HELLO_EVERY_MS
        if (clock() - helloAt < every) return
        // Counted from an answer: a host starting again just then is asked on the next round.
        val hello = runCatching { c.status().hello }.getOrNull() ?: return
        helloAt = clock()
        _hostFuse.value = hello.fuseVersion
        val outside = hello.outside.takeIf { it.isNotBlank() } ?: return
        val cfg = cached
        if (cfg.remoteAddress.isNotBlank() && !cfg.remoteFromHost) return
        if (SyncClient.normalise(cfg.remoteAddress.ifBlank { "x" }) == SyncClient.normalise(outside)) return
        saveConfig { it.copy(remoteAddress = outside, remoteFromHost = true) }
        retire(client)
        client = linked(c.link.copy(remoteAddress = SyncClient.normalise(outside)))
        log("Learned the host's address from outside", kind = SyncActivity.HOST)
    }

    override suspend fun answerJoin(id: String, allow: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Connect to your host first.")
            c.answerJoin(id, allow)
            _joins.value = _joins.value.filterNot { it.id == id }
            refreshJoins()
            log(if (allow) "Let a new device in" else "Turned a device away", kind = SyncActivity.DEVICE)
        }
    }

    private suspend fun refreshJoins() {
        val c = client ?: return
        _joins.value = runCatching { c.joins() }.getOrDefault(_joins.value)
    }

    override suspend fun hostHere(name: String, installService: Boolean): Result<HostView> = withContext(Dispatchers.IO) {
        runCatching {
            require(canHost) { "This device can't be a host." }
            // A folder chosen for the host that already holds other things (a whole drive, say)
            // gets a folder of the host's own inside it, so the host's files never mix with them.
            // Decided before this device is the host, in the same change: Fuse's own start, running
            // meanwhile, would otherwise start the host in the chosen folder itself.
            val chosen = config().hostDataDir.ifBlank { null }?.let(::File)
            val own = chosen?.takeIf { it.isDirectory && !File(it, "host.json").isFile && HostFiles.holdsOthers(it) }
                ?.let { File(it, HOST_FOLDER).path.replace('\\', '/') }
            saveConfig { it.copy(enabled = true, role = "HOST", hostName = name.trim().ifEmpty { defaultDeviceName }, hostDataDir = own ?: it.hostDataDir) }
            val c = config()
            ensureDeviceId()
            startHostServer(c)
            // This device connects to its own host, like any other.
            val code = newPairingCode() ?: error("The host couldn't start: port ${c.hostPort} is in use by something else.")
            connect("127.0.0.1:${c.hostPort}", code).getOrThrow()
            // That code was this computer's own, and is used up: the one shown for other devices is new.
            lastCode = null
            // The host plays as its own profile, Admin, so nobody has to make one here (people
            // who already had profiles here went up with it, and keep playing as themselves).
            adoptHost()?.let { admin ->
                refreshLists()
                if (cached.activeProfile.isEmpty() && _merge.value == null) switchTo(admin.id, null)
            }
            if (installService && lifetime.supported) handOver()
            newPairingCode()
            refreshHostView()
            log("$name is a Fuse One Host", kind = SyncActivity.HOST)
            _host.value!!
        }
    }

    override suspend fun installService(): Result<ServiceState> = withContext(Dispatchers.IO) {
        saveConfig { it.copy(hostInFuseOnly = false) }
        handOver().also { refreshHostView() }
    }

    /**
     * Hands the host over to the background service: this process stops serving (so the two never
     * share its files), the service starts, and Fuse manages it from then on. Should the service
     * not come up, this process serves again, so the host is never left down.
     */
    /** True when [version] (a host's) is older than this Fuse, or unknown (hosts before 0.2 didn't say). */
    private fun olderThanThis(version: String): Boolean {
        if (fuseVersion.isBlank()) return false
        if (version.isBlank()) return true
        return SyncApi.older(version, fuseVersion)
    }


    /**
     * Sets the host's service up again with this Fuse and waits for it to answer as this version.
     * Null when it didn't (the old one may still be running, and is used as it is).
     */
    private suspend fun restartedService(c: SyncSettings, was: String): Pair<HostAdmin, HostStatus>? = withContext(Dispatchers.IO) {
        log(
            if (was == NOT_ANSWERING) "The host didn't answer: starting it again on $fuseVersion"
            else "The host was running Fuse ${was.ifBlank { "from before 0.2" }}: starting it again on $fuseVersion",
            kind = SyncActivity.HOST,
        )
        if (lifetime.install().isFailure) return@withContext null
        withTimeoutOrNull(SERVICE_WAIT_MS * 2) {
            var found: Pair<HostAdmin, HostStatus>? = null
            while (found == null) {
                val admin = HostAdmin.of(hostDir(c), c.hostPort)
                val status = admin?.let { runCatching { it.status() }.getOrNull() }
                if (admin != null && status != null && !olderThanThis(status.hello.fuseVersion)) found = admin to status else delay(ADMIN_RETRY_MS)
            }
            found
        }
    }

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
                    found = HostAdmin.of(hostDir(c), c.hostPort)
                    if (found == null) delay(250)
                }
                found
            }
            if (admin != null) {
                hostAdmin = admin
                adminStatus = runCatching { admin.status() }.getOrNull()
                log("The host now runs on its own, with Fuse closed too", kind = SyncActivity.HOST)
                return installed
            }
        }
        // It didn't come up: serve here again.
        startHostServer(c)
        return if (installed.isSuccess) Result.failure(IllegalStateException("The service was set up but didn't start. Fuse keeps hosting while it is open.")) else installed
    }

    override suspend fun removeService(): Result<ServiceState> = withContext(Dispatchers.IO) {
        // Taken away on purpose: Fuse doesn't set it up again by itself.
        saveConfig { it.copy(hostInFuseOnly = true) }
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

    /** Whether this run already handed the host to its service by itself (see [startHostServerOnce]). */
    @Volatile private var triedService = false

    override suspend fun updateHost(): Result<HostUpdate> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext Result.failure(SyncException("Fuse One isn't connected.", "not-connected", 0))
        // Not through withClient: a host saying no to an update says nothing about the connection.
        runCatching { c.updateHost() }.recoverCatching { e ->
            // A host from before 0.4.2.23 has no such call: it is updated once on its computer.
            if (e is SyncException && e.status == 404) throw SyncException("The host runs a Fuse from before it could be updated from here. Update Fuse once on the host's computer.", "old-host", 404) else throw e
        }.onSuccess { answer ->
            // Not updating: it says the Fuse it runs now, which is what this device shows from here on.
            // Updating: it is asked again on the next round, and each minute until it runs the new one.
            if (!answer.started && answer.version.isNotBlank()) _hostFuse.value = answer.version
            if (answer.started) helloAt = 0
        }
    }

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
        val c = client ?: return@withContext Result.failure(SyncException("Fuse One isn't connected.", "not-connected", 0))
        runCatching { block(c) }.onFailure { if (it is SyncException) handle(it) }
    }

    /** True while this device has a host (or is joining one): profiles are the host's then. */
    private suspend fun hosted(): Boolean = client != null || link() != null

    override suspend fun createProfile(name: String, avatar: String, pin: String?): Result<ProfileInfo> {
        if (!hosted()) return withContext(Dispatchers.IO) { runCatching { createLocal(name, avatar, pin) } }
        return withClient { c ->
            val made = c.createProfile(NewProfile(name, avatar, pin?.ifBlank { null }))
            rememberPin(made.id, pin)
            refreshLists()
            made
        }
    }

    /** A profile of this device's own: no host needed. The first one also starts keeping each person's saves apart. */
    private suspend fun createLocal(name: String, avatar: String, pin: String?): ProfileInfo {
        val clean = name.trim().take(40)
        require(clean.isNotEmpty()) { "A profile needs a name" }
        require(people.profiles.none { it.name.trim().equals(clean, ignoreCase = true) }) { "A profile is already called $clean" }
        val digits = pin?.takeIf { it.isNotBlank() }
        if (digits != null) require(digits.length in 4..64) { "A PIN or password is 4 to 64 characters" }
        val made = LocalProfile(SyncCrypto.token(9), clean, avatar.take(32), clock(), digits?.let { SyncCrypto.hashSecret(it) })
        val order = people.order.ifEmpty { people.profiles.map { it.id } }
        savePeople(people.copy(profiles = people.profiles + made, order = order + made.id))
        showLocal()
        log("Made a profile for $clean", kind = SyncActivity.PROFILE)
        return made.info
    }

    override suspend fun changeProfile(id: String, change: ProfileChange): Result<ProfileInfo> {
        if (!hosted()) return withContext(Dispatchers.IO) {
            runCatching {
                val p = localProfile(id) ?: throw NoSuchElementException("No such profile")
                val name = change.name?.trim()?.take(40)?.ifEmpty { null }
                if (name != null) require(people.profiles.none { it.id != id && it.name.trim().equals(name, ignoreCase = true) }) { "A profile is already called $name" }
                var next = p.copy(name = name ?: p.name, avatar = change.avatar?.take(32) ?: p.avatar)
                if (change.pin != null || change.removePin) {
                    // Changing or removing a PIN asks for the one it has, as a host does.
                    if (p.pinHash != null && (change.currentPin == null || !SyncCrypto.verifySecret(change.currentPin, p.pinHash))) throw SecurityException("The current PIN isn't right")
                    next = if (change.removePin) next.copy(pinHash = null) else {
                        require(change.pin!!.length in 4..64) { "A PIN or password is 4 to 64 characters" }
                        next.copy(pinHash = SyncCrypto.hashSecret(change.pin))
                    }
                }
                savePeople(people.copy(profiles = people.profiles.map { if (it.id == id) next else it }))
                showLocal()
                next.info
            }
        }
        return withClient { c ->
            c.changeProfile(id, change).also {
                if (change.removePin) savePeople(people.copy(pins = people.pins - id)) else rememberPin(id, change.pin)
                refreshLists()
            }
        }
    }

    override suspend fun deleteProfile(id: String): Result<Unit> {
        if (!hosted()) return withContext(Dispatchers.IO) {
            runCatching {
                val p = localProfile(id) ?: throw NoSuchElementException("No such profile")
                if (cached.activeProfile == id) switchTo(null).getOrThrow()
                // Their saves here go to the kept folder as plain files, never silently.
                val written = device?.forget(id, File(keptFolder(), safeName(p.name))) ?: 0
                savePeople(people.copy(profiles = people.profiles - p, order = people.order - id, pins = people.pins - id))
                showLocal()
                log(if (written > 0) "${p.name} was deleted; their saves are in Fuse One's kept folder" else "${p.name} was deleted", kind = SyncActivity.PROFILE)
            }
        }
        return withClient { c ->
            if (cached.activeProfile == id) switchTo(null)
            c.deleteProfile(id)
            savePeople(people.copy(order = people.order - id, pins = people.pins - id))
            refreshLists()
        }
    }

    override suspend fun openProfile(id: String, pin: String?): Result<Unit> {
        if (!hosted()) return withContext(Dispatchers.IO) { runCatching { checkPin(localProfile(id) ?: throw SyncException("No such profile.", "no-profile", 0), pin) } }
        val online = withClient { c -> c.openProfile(id, pin); rememberPin(id, pin); Unit }
        // The host away (or not reached yet): the PIN entered here before still opens the profile.
        val e = online.exceptionOrNull() as? SyncException ?: return online
        if (e.code != "offline" && e.code != "not-connected") return online
        val known = _profiles.value.firstOrNull { it.id == id } ?: return online
        return withContext(Dispatchers.IO) { runCatching { if (known.protected) checkOfflinePin(id, known.name, pin) } }
    }

    /**
     * A host profile's PIN checked here while the host can't be reached: against the salted hash of
     * the PIN last entered for it on this device, with the same growing wait after wrong tries as
     * any PIN. A PIN never entered here can only be checked by the host, and that is said plainly.
     */
    private fun checkOfflinePin(id: String, name: String, pin: String?) {
        val remembered = people.pins[id]
            ?: throw SyncException("$name's PIN hasn't been entered on this device yet, so only the host can check it. Try again when it's back.", "offline", 0)
        checkPin(LocalProfile(id, name, "", 0, remembered), pin)
    }

    override suspend fun reorderProfiles(ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client
            if (c == null) {
                savePeople(people.copy(order = ids))
                showLocal()
                return@runCatching
            }
            _profiles.value = LocalPeople(order = ids).ordered(_profiles.value)
            // Every device follows the host's order; a host too old to keep one leaves it to this device.
            val shared = runCatching { c.orderProfiles(ids) }.isSuccess
            savePeople(people.copy(order = if (shared) emptyList() else ids))
            if (shared) refreshLists()
        }
    }

    override suspend fun switchTo(id: String?, pin: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = device ?: error("Fuse One isn't set up.")
            val c = client
            val began = clock()
            val before = cached.activeProfile
            val known = id?.let { want -> _profiles.value.firstOrNull { it.id == want } }
            // A person this device already has (their records came from the host before, their PIN
            // was entered here before): they arrive at once, from what is here, and the host is
            // asked afterwards. Nothing waits on the network or on a sync round in progress.
            if (id != null && c != null && id != before && known != null && d.knows(id) && adopted() &&
                (!known.protected || people.pins[id] != null)
            ) {
                if (known.protected) checkOfflinePin(id, known.name, pin)
                // Being checked from before it arrives, so nothing else sends this device away from it meanwhile.
                checking += id
                try {
                    arrive(d, before, id)
                } catch (e: Throwable) {
                    checking -= id
                    throw e
                }
                log("Switched to ${known.name} in ${clock() - began} ms", kind = SyncActivity.PROFILE)
                scope.launch(Dispatchers.IO) { catchUp(c, d, id, pin, before, check = true) }
                return@runCatching
            }
            profileLock.withLock {
                // What changed under the profile in use stays with it, sent now or later (its
                // records go up with the next person's below; its saves straight after the switch).
                if (before.isNotEmpty()) captureChanges(before)
                if (id == null) {
                    saveConfig { it.copy(activeProfile = "") }
                    _active.value = null
                    return@withLock
                }
                val own = localProfile(id)?.takeIf { c == null }
                if (own != null) {
                    if (id != before) checkPin(own, pin)
                } else if (c != null) {
                    try {
                        withTimeout(HOST_SWITCH_MS) { c.openProfile(id, pin) }
                        rememberPin(id, pin)
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        if (known == null) throw SyncException("${cached.hostName.ifBlank { "The host" }} didn't answer. Try again in a moment.", "offline", 0)
                        if (known.protected && id != before) checkOfflinePin(id, known.name, pin)
                    } catch (e: SyncException) {
                        // The host is away: a profile without a PIN is switched to here and catches up
                        // later; one with a PIN is checked against the PIN entered here before
                        // (kept only as a salted hash), with the same lockout as any other PIN.
                        if (e.code != "offline" || known == null) throw e
                        if (known.protected && id != before) checkOfflinePin(id, known.name, pin)
                    }
                }
                // The first profile this device ever uses takes in what it already had (nothing is lost).
                val first = before.isEmpty() && d.meta(id) == ProfileMeta() && !adopted()
                if (first) {
                    // A profile someone already uses keeps what it has: this device's records only
                    // fill what it doesn't (stamped older than anything real), and its play time
                    // and sessions join. Only a new, empty profile takes this device's as they are,
                    // and only the host can say it is new: offline, nothing here is taken as newer.
                    val reached = c != null && runCatching { withTimeout(HOST_SWITCH_MS) { d.pullMeta(c, id) } }.isSuccess
                    // A profile made here is new by definition: it takes what this device has as it is.
                    val fresh = own != null || (reached && d.meta(id) == ProfileMeta())
                    val local = data.read(d.deviceId, hlc)
                    val seed = if (fresh) local else local.copy(settings = local.settings.mapValues { Lww(it.value.value, Hlc.ZERO) })
                    val adopt = ProfileDiff.changes(ProfileMeta(), seed, d.deviceId, if (fresh) hlc else HlcClock(d.deviceId) { ADOPTED_AT })
                    d.changeMeta(id) { pending, _ -> pending.merge(adopt) }
                    saveConfig { it.copy(activeProfile = id) }
                    markAdopted()
                } else if (c != null && !d.knows(id)) {
                    // Someone new to this device: their records are needed before they arrive, so
                    // they are brought in now (once; from then on they are kept here).
                    runCatching { withTimeout(HOST_SWITCH_MS) { d.pullMeta(c, id) } }
                }
                saveConfig { it.copy(activeProfile = id) }
                layInPlace(d, id)
                d.useProfile(id)
                _active.value = _profiles.value.firstOrNull { it.id == id }
                log("Switched to ${_active.value?.name ?: "a profile"} in ${clock() - began} ms", kind = SyncActivity.PROFILE)
            }
            // The newest records and the saves waiting to go follow, behind the switch.
            if (c != null && id != null) scope.launch(Dispatchers.IO) { catchUp(c, d, id, null, before, check = false) }
        }
    }

    /**
     * Puts [id] in place from what this device keeps of them: what changed under [before] stays
     * with [before], then [id]'s records, settings and Home become the library's. Local only.
     */
    private suspend fun arrive(d: SyncDevice, before: String, id: String, onlyIfStill: Boolean = false): Boolean = profileLock.withLock {
        // [onlyIfStill]: only while [before] is still the one playing; a switch made meanwhile stands.
        if (onlyIfStill && cached.activeProfile != before) return@withLock false
        if (before.isNotEmpty()) captureChanges(before)
        saveConfig { it.copy(activeProfile = id) }
        layInPlace(d, id)
        d.useProfile(id)
        _active.value = _profiles.value.firstOrNull { it.id == id }
        true
    }

    /**
     * This device stops using [id] when it is still the profile in use, decided under the same lock
     * as every switch, so a switch made meanwhile (back to who was here) is never undone. Its changes
     * stay with it. True when it did.
     */
    private suspend fun leave(id: String): Boolean = profileLock.withLock {
        if (cached.activeProfile != id) return@withLock false
        captureChanges(id)
        saveConfig { it.copy(activeProfile = "") }
        _active.value = null
        true
    }

    /** Profiles just switched to from what is kept here, which the host is still checking ([catchUp]). */
    private val checking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * After a switch: the host checks the person (a PIN changed elsewhere, a profile deleted
     * elsewhere sends this device back to who was here, or to "Who's playing?"), then what waits
     * goes up and their newest records come in, laid in place only while they are still the one
     * playing. Offline, it simply waits for the next round.
     */
    private suspend fun catchUp(c: SyncClient, d: SyncDevice, id: String, pin: String?, before: String, check: Boolean) {
        val began = clock()
        // [check]: the switch was made from what is kept here, so the host hasn't seen it yet.
        if (check) {
            try {
                c.openProfile(id, pin)
                rememberPin(id, pin)
            } catch (e: SyncException) {
                if (e.code == "offline" || e.code == "rate") return
                val back = before.takeIf { b -> b.isNotEmpty() && _profiles.value.any { it.id == b } }
                // Only while [id] is still the one playing: a switch made meanwhile stands.
                val moved = if (back != null) arrive(d, id, back, onlyIfStill = true) else leave(id)
                if (moved) {
                    log(
                        (e.message ?: "The host didn't accept that profile.") + if (back != null) " Switched back." else " Choose who's playing.",
                        kind = SyncActivity.PROFILE,
                    )
                }
                return
            } finally {
                checking -= id
            }
        }
        work.withLock {
            // What the person changed since arriving is theirs first, then the host's newest joins it.
            asActive(id) { captureChanges(id) }
            runCatching { d.flush(c, saves = false) }
            val pulled = runCatching { d.pullMeta(c, id) }.isSuccess
            if (pulled) asActive(id) {
                captureChanges(id)
                layInPlace(d, id)
            }
        }
        log("Brought in ${_profiles.value.firstOrNull { it.id == id }?.name ?: "the profile"}'s newest in ${clock() - began} ms", kind = SyncActivity.PROFILE)
        // Saves waiting to go (the last person's, anyone's) go up now. Only the saves: the records
        // were just brought in, so nothing here rewrites the library while the person plays.
        runCatching { sendWaiting() }.onFailure { if (it is SyncException) handle(it) }
    }

    /**
     * Runs [block] while [profile] is still the one in use, under the switch lock: work for someone
     * who was just switched away from never touches the next person's library.
     */
    private suspend fun <T> asActive(profile: String, block: suspend () -> T): T? =
        profileLock.withLock { if (cached.activeProfile == profile) block() else null }

    private fun adopted(): Boolean = File(dir, ADOPTED_FILE).isFile
    private fun markAdopted() {
        dir.mkdirs()
        File(dir, ADOPTED_FILE).writeText("1")
    }

    override suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { syncOnce() }.onFailure { if (it is SyncException) handle(it) }
    }

    // ---------------------------------------------------------------- saves around a game

    private fun slots(query: SaveQuery, live: Boolean = false): List<LocalSlot> {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return emptyList()
        val c = cached
        return adapter.locate(query, saveEnv)
            .filter { s -> (s.kind == SaveKind.STATE && c.states) || (s.kind != SaveKind.STATE && c.saves) }
            .map { spot ->
                // Cards shared by every game on them are the profile's, keyed by the card, not the game.
                val key = if (spot.kind == SaveKind.MEMORY_CARD) GameKey(query.platform, "card." + spot.format) else query.game
                // A save in a granted folder: a fresh copy to work on, and its changes pushed back.
                if (live || !spot.available) return@map Slots.of(key, spot)
                treeMirror.pull(spot)?.let { (local, parts) -> Slots.of(key, local).copy(sink = { treeMirror.push(parts) }) } ?: Slots.of(key, spot)
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

    private val saveImporter by lazy { JvmSaveImporter(File(dir, "save-imports")) }

    override suspend fun previewSaveImport(source: String, games: List<SaveQuery>): Result<SaveImportPlan> = withContext(Dispatchers.IO) {
        work.withLock {
            runCatching {
                config()
                saveImporter.scan(File(source), games, saveEnv, fuseOne = savesToFuseOne())
            }
        }
    }

    /** Fuse One keeps this device's saves: one with no folder here can go to it (see [JvmSaveImporter.scan]). */
    private fun savesToFuseOne(): Boolean = cached.enabled && cached.role.isNotEmpty() && cached.saves

    override suspend fun discardSaveImport(plan: SaveImportPlan) = withContext(Dispatchers.IO) {
        work.withLock { saveImporter.discard(plan) }
    }

    override suspend fun assignSaveImport(plan: SaveImportPlan, entry: String, game: SaveQuery): Result<SaveImportPlan> = withContext(Dispatchers.IO) {
        work.withLock {
            runCatching {
                config()
                saveImporter.assign(plan, entry, game, saveEnv, fuseOne = savesToFuseOne())
            }
        }
    }

    override suspend fun importSaves(plan: SaveImportPlan, choices: List<SaveImportChoice>, profile: String): Result<SaveImportResult> = withContext(Dispatchers.IO) {
        work.withLock {
            runCatching {
                config()
                require(_nowPlaying.value == null) { "Close the running game before importing saves" }
                require(profile.isNotBlank() && profiles.value.any { it.id == profile }) { "Choose whose saves these are before importing" }
                require(choices.isNotEmpty() && choices.map { it.entry }.distinct().size == choices.size) { "Choose each save destination once" }
                // Saves going to Fuse One have no folder here to look up again; the rest do.
                val bindings = choices.map { saveImporter.binding(plan, it) }
                require(bindings.map { it.query.game.id + "|" + it.spot.kind + "|" + it.part }.distinct().size == bindings.size) { "Choose only one source save for each game at a time" }
                val here = choices.indices.filterNot { bindings[it].fuseOne }
                val resolved = here.zip(resolveImport(plan, here.map { choices[it] })).toMap()
                val d = device ?: newDevice(ensureDeviceId(), cached.deviceName.ifBlank { defaultDeviceName }).also { device = it }
                // A Switch game's account save and its device save are two parts of one slot, each imported in turn.
                require(resolved.values.map { (binding, _, slot) -> slot.key + "|" + binding.part }.distinct().size == resolved.size) { "Choose one import for each shared memory card" }
                val failures = ArrayList<SaveImportFailure>()
                val everyone = choices.flatMap { it.profiles.ifEmpty { listOf(profile) } }.toSet()
                require(everyone.all { p -> profiles.value.any { it.id == p } }) { "Choose whose saves these are before importing" }
                var imported = 0
                val replaced = ArrayList<ReplacedSave>()
                val revisions = choices.indices.flatMap { index ->
                    val entry = plan.entries.first { e -> e.id == choices[index].entry }
                    val local = resolved[index]
                    if (local == null) {
                        // No folder for it here: it goes to Fuse One, for a device that can reach one.
                        val binding = bindings[index]
                        val query = canonical(binding.query)
                        return@flatMap try {
                            val c = client ?: error("Fuse One isn't connected, so ${query.title}'s save couldn't go to it. Import it again once it is.")
                            val owners = choices[index].profiles.ifEmpty { listOf(profile) }.map { if (query.game.id in cached.sharedGames) SHARED_SAVES else it }.distinct()
                            owners.map { o ->
                                val head = runCatching { c.revisions(o, query.game.id, binding.spot.kind).firstOrNull { it.canBeNewest } }
                                    .getOrElse { throw IllegalStateException("Fuse One can't be reached just now, so ${query.title}'s save couldn't go to it. Import it again once it can.") }
                                d.importToFuseOne(c, o, query.game, binding.spot.kind, entry.sourceFormat, binding.files, head, query.title, binding.part).id
                            }.also { imported++ }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            failures += SaveImportFailure(entry.id, entry.name, e.message ?: "The save could not be imported")
                            emptyList()
                        }
                    }
                    val (binding, query, slot) = local
                    // Each chosen person gets their own copy, and no one else's save is touched: the
                    // folder is written only for whoever's save is in it (see SyncDevice.importFor).
                    val owners = choices[index].profiles.ifEmpty { listOf(profile) }.map { ownerOf(query, slot, it) }.distinct()
                    val before = owners.associateWith { o -> d.importBackups(o, slot).map { it.id }.toSet() }
                    try {
                        val ids = d.importFor(owners, cached.activeProfile.ifEmpty { null }, slot, entry.sourceFormat, binding.files, query.title, binding.part).map { it.id }
                        imported++
                        for (o in owners) d.importBackups(o, slot).firstOrNull { it.id !in before.getValue(o) }?.let { replaced += ReplacedSave(entry.id, o, query, slot.kind, it.id) }
                        ids
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        failures += SaveImportFailure(entry.id, entry.name, e.message ?: "The save could not be imported")
                        emptyList()
                    }
                }
                // Never pull before capture: the explicit import must enter history before reconnect.
                // Normal flush owns parent/conflict handling and leaves offline saves queued.
                val sent = if (cached.enabled && everyone.any { it !in cached.noSyncProfiles }) client?.let { c -> runCatching { d.flush(c) }.isSuccess } == true else false
                if (failures.isEmpty()) saveImporter.discard(plan)
                SaveImportResult(imported, revisions, queued = revisions.isNotEmpty() && cached.enabled && !sent, failures = failures, replaced = replaced)
            }
        }
    }

    /**
     * Each choice's binding, its game by the household's id and the save slot it goes to, looked up
     * again now (emulator settings may have changed since the preview).
     */
    private fun resolveImport(plan: SaveImportPlan, choices: List<SaveImportChoice>): List<Triple<JvmSaveImporter.Binding, SaveQuery, LocalSlot>> {
        val bindings = choices.map { saveImporter.binding(plan, it) }
        require(bindings.map { it.query.game.id + "|" + it.spot.kind + "|" + it.part }.distinct().size == bindings.size) { "Choose only one source save for each game at a time" }
        return bindings.map { binding ->
            val query = canonical(binding.query)
            val fresh = SaveAdapters.forEmulator(query.emulatorId)?.locate(query, saveEnv)
                ?.firstOrNull { it.kind == binding.spot.kind && it.format == binding.spot.format && it.root == binding.spot.root && it.files == binding.spot.files }
                ?: error("A save destination changed. Inspect a new import preview")
            require(fresh.available) { "The save destination is no longer available" }
            val key = if (fresh.kind == SaveKind.MEMORY_CARD) GameKey(query.platform, "card." + fresh.format) else query.game
            Triple(binding, query, Slots.of(key, fresh))
        }
    }

    override suspend fun existingSaves(plan: SaveImportPlan, choices: List<SaveImportChoice>, profile: String): Map<String, Map<String, ExistingSave>> = withContext(Dispatchers.IO) {
        work.withLock {
            runCatching {
                config()
                val d = device ?: return@runCatching emptyMap<String, Map<String, ExistingSave>>()
                val (away, here) = choices.partition { saveImporter.binding(plan, it).fuseOne }
                val inFolders = resolveImport(plan, here).mapIndexedNotNull { index, (_, query, slot) ->
                    val choice = here[index]
                    val found = choice.profiles.ifEmpty { listOf(profile) }.mapNotNull { p -> d.existingSave(ownerOf(query, slot, p), slot)?.let { p to it } }.toMap()
                    found.takeIf { it.isNotEmpty() }?.let { choice.entry to it }
                }.toMap()
                // Going to Fuse One: what each person has there now, or waiting here to go to it.
                val onFuseOne = away.mapNotNull { choice ->
                    val binding = saveImporter.binding(plan, choice)
                    val query = canonical(binding.query)
                    val found = choice.profiles.ifEmpty { listOf(profile) }.mapNotNull { p ->
                        val owner = if (query.game.id in cached.sharedGames) SHARED_SAVES else p
                        val save = d.waitingSave(owner, query.game, binding.spot.kind)?.let { ExistingSave(ExistingSave.Where.WAITING, it) }
                            ?: client?.let { c -> runCatching { c.revisions(owner, query.game.id, binding.spot.kind).firstOrNull { it.canBeNewest } }.getOrNull() }
                                ?.let { ExistingSave(ExistingSave.Where.SYNCED, it.at.millis) }
                        save?.let { p to it }
                    }.toMap()
                    found.takeIf { it.isNotEmpty() }?.let { choice.entry to it }
                }.toMap()
                inFolders + onFuseOne
            }.getOrDefault(emptyMap())
        }
    }

    override suspend fun switchCards(): List<SwitchCard> = withContext(Dispatchers.IO) {
        val user = System.getProperty("user.name").orEmpty()
        // Where cards and readers mount: macOS, Linux desktops (udisks), Windows drive letters, Android's storage.
        val roots = (listOf("/Volumes", "/media/$user", "/run/media/$user", "/media", "/mnt", "/storage").flatMap { d ->
            File(d).listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }.orEmpty()
        } + File.listRoots().filter { it.path.length <= 3 && it.path != "/" }).distinctBy { it.absolutePath }
        roots.mapNotNull { root ->
            runCatching {
                val names = root.listFiles()?.filter { it.isDirectory }.orEmpty()
                // A card whose emuMMC is set up and on: its small settings files are kept, so a card
                // that loses them later (a phone's storage check, pulled out unejected) can have them back.
                runCatching { emuMmcGuard.snapshot(root) }
                val lost = runCatching { emuMmcGuard.lost(root) }.getOrDefault(emptyList())
                val jksv = names.firstOrNull { it.name.equals("JKSV", true) }
                val checkpoint = names.firstOrNull { it.name.equals("switch", true) }?.let { File(it, "Checkpoint/saves") }?.takeIf { it.isDirectory }
                val folder = jksv ?: checkpoint
                val games = folder?.listFiles()?.count { g -> g.isDirectory && g.listFiles()?.isNotEmpty() == true } ?: 0
                val name = root.name.ifEmpty { root.path }
                when {
                    folder != null && games > 0 -> SwitchCard(folder.absolutePath, name, "${if (jksv != null) "JKSV" else "Checkpoint"}, $games ${if (games == 1) "game" else "games"}", lost, root.absolutePath)
                    lost.isNotEmpty() -> SwitchCard("", name, "emuMMC settings missing", lost, root.absolutePath)
                    else -> null
                }
            }.getOrNull()
        }
    }

    override suspend fun restoreEmuMmc(card: SwitchCard): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(card.root).takeIf { card.root.isNotBlank() && it.isDirectory } ?: error("The card isn't plugged in any more.")
            emuMmcGuard.restore(root).also { n -> log("Put back $n emuMMC settings ${if (n == 1) "file" else "files"} on ${card.name}", kind = "restore") }
        }
    }

    /** The Switch cards' emuMMC settings files, kept as last seen healthy ([EmuMmcGuard]). */
    private val emuMmcGuard by lazy { EmuMmcGuard(File(dir, "emummc-guard").apply { mkdirs() }) }

    override suspend fun importBackups(asked: SaveQuery, kind: SaveKind, profile: String): List<ImportBackupInfo> = withContext(Dispatchers.IO) {
        val query = canonical(asked)
        val d = device ?: return@withContext emptyList()
        val slot = slots(query).firstOrNull { it.kind == kind } ?: return@withContext emptyList()
        d.importBackups(ownerOf(query, slot, profile), slot).map { ImportBackupInfo(it.id, it.at, it.manifest.size, it.title) }
    }

    override suspend fun restoreImportBackup(asked: SaveQuery, kind: SaveKind, profile: String, backup: String): Result<Unit> = withContext(Dispatchers.IO) {
        work.withLock {
            runCatching {
                require(_nowPlaying.value == null) { "Close the running game before putting a save back" }
                val query = canonical(asked)
                val d = device ?: error("This device has no saves kept from before an import.")
                val slot = slots(query).firstOrNull { it.kind == kind } ?: error("That save isn't here.")
                val owner = ownerOf(query, slot, profile)
                d.restoreImportBackup(owner, slot, backup)
                if (cached.enabled && owner !in cached.noSyncProfiles) client?.let { c -> runCatching { d.flush(c) } }
                log("${query.title}: put back the save from before an import", query.game.id, "restore")
            }
        }
    }

    override suspend fun setShared(game: GameKey, shared: Boolean, fromMine: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Fuse One isn't connected. Sharing a game needs the host.")
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
        // A test run (Find Where It Saves): this person's save swapped in as ever, nothing brought down,
        // and the folders noted only then, so the save the game makes is the one seen.
        probes[query.game.id]?.let { probe ->
            if (clock() - probe.armedAt > PROBE_START_MS || d == null) {
                probes.remove(query.game.id)
            } else {
                swapIn(d, query, profile.ifEmpty { null })?.let { blocked -> return@withContext blocked }
                probes[query.game.id] = probe.copy(snapshot = SaveProbe.snapshot(probe.roots, saveEnv))
                return@withContext LaunchGate.Go("A test run: save sync is paused for ${query.title} until it closes")
            }
        }
        // Profiles work without a host too: each person's save is put in place here all the same.
        if (d == null || profile.isEmpty() || !(cached.enabled || keepsOwn)) return@withContext LaunchGate.Go()
        // Another device playing it, or still sending what it just saved: the person decides whether to wait.
        if (waitForOthers && c != null) othersOn(c, d, query, profile)?.let { return@withContext it }
        // From here until its save is sent after it stops (Android keeps Fuse going meanwhile).
        getReady(query.title)
        busy += query.game.id
        scope.launch { delay(READY_MS); if (watch?.query?.game != query.game) busy -= query.game.id }
        // Saves Fuse can't place yet (the game's id unknown): where another device keeps them, if it
        // knows; else what each game's folder looks like now, so this play teaches Fuse.
        if (c != null) withTimeoutOrNull(LAUNCH_WAIT_MS) { runCatching { learnFromHousehold(c, d, query, profile) } }
        snapshotLearnable(query)
        var note: String? = null
        for (slot in slots(query)) {
            // On a device more than one person plays, the folder must hold this person's save
            // first (whoever played last keeps theirs); this needs no host, so it happens offline too.
            val owner = ownerOf(query, slot, profile)
            val swapped = runCatching {
                d.handover(owner, slot, { who -> if (who == SHARED_SAVES) 0L else canonical(d.meta(who)).game(query.game).totalSeconds },
                    File(File(dir, KEPT_DIR), REMOVED_DIR), othersHere = d.saveKeepers().any { it != owner && it != SHARED_SAVES })
            }
            if (swapped.isSuccess && d.unclaimed(slot) != null && unclaimedTitles.put(query.game.id, query.title) == null) {
                refreshUnclaimed()
                _notices.tryEmit(SyncNotice.KeptAside(query.title))
            }
            swapped.exceptionOrNull()?.let { e ->
                // Never start with someone else's save: the person sees why, and nothing was lost (every file is kept).
                log("${query.title}: couldn't swap in this person's save (${e.message})", query.game.id, SyncActivity.FAILED)
                busy -= query.game.id
                return@withContext LaunchGate.Blocked(query.title,
                    "Fuse couldn't put your save for ${query.title} in place, so it didn't start, to keep from playing with someone else's save. " +
                        "Every file is kept. ${e.message ?: ""}".trim())
            }
            // This person's saves aren't taken here (or this device moves its saves another way, with
            // RomM or Syncthing, or not at all): the game starts with the save this device has.
            if (!cached.saves || !takesFrom(owner, query.game.id)) continue
            val awaited = d.awaited(owner, slot)
            if (c == null) {
                // Without a host there is nothing to be offline from.
                if (!keepsOwn) {
                    // Offline, but a newer save is known to be on the host: not started quietly without it.
                    if (awaited != null && waitForOthers) return@withContext waiting(query, offline = true, awaited)
                    note = note ?: "Fuse One is offline: playing with this device's save"
                }
                continue
            }
            // A launch never waits long on a host that isn't there; the save keeps coming meanwhile.
            val attempt = prepareFor(c, d, owner, slot)
            val result = when {
                attempt != null && attempt.isSuccess -> attempt.getOrThrow()
                // Slow, or the host answered with a problem, with a newer save known of or the host
                // thought reachable: never started quietly with an older save.
                waitForOthers && (status.value !is SyncStatus.Offline || d.awaited(owner, slot) != null) ->
                    return@withContext waiting(query, offline = false, d.awaited(owner, slot))
                else -> {
                    // Playing with what is here: a save still coming must not land while the game runs.
                    stopPreparing(owner, slot)
                    PrepareResult.Offline
                }
            }
            if (result == PrepareResult.Offline && waitForOthers) {
                d.awaited(owner, slot)?.let { return@withContext waiting(query, offline = true, it) }
            }
            d.appliedNote(owner, slot, result, clock())?.let { n -> scope.launch(Dispatchers.IO) { runCatching { c.noteApplied(listOf(n)) } } }
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
                PrepareResult.Offline -> note = note ?: "Fuse One is offline: playing with this device's save"
                else -> Unit
            }
        }
        LaunchGate.Go(note)
    }

    /**
     * One preparation of each save at a time ([SyncDevice.prepare]), kept going when a launch stops
     * waiting for it, so the save keeps coming and waiting again picks it up where it is.
     */
    private val preparing = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<PrepareResult>>>()

    /** [SyncDevice.prepare] for [slot], waited for at most [LAUNCH_WAIT_MS]: null when it is still going. */
    private suspend fun prepareFor(c: SyncClient, d: SyncDevice, owner: String, slot: LocalSlot): Result<PrepareResult>? {
        val key = "$owner|${slot.key}"
        val job = preparing.compute(key) { _, running ->
            running?.takeIf { it.isActive } ?: scope.async(Dispatchers.IO) { runCatching { d.prepare(c, owner, slot) } }
        }!!
        val result = withTimeoutOrNull(LAUNCH_WAIT_MS) { job.await() }
        if (result != null) preparing.remove(key, job)
        return result
    }

    /**
     * Stops [slot]'s preparation still going in the background, and waits until it has: a save it
     * was already writing is in place before the game starts, never while it runs.
     */
    private suspend fun stopPreparing(owner: String, slot: LocalSlot) {
        preparing.remove("$owner|${slot.key}")?.let { job -> runCatching { job.cancelAndJoin() } }
    }

    /** The launch of [query] waits for a newer save ([LaunchGate.Waiting]); it isn't busy meanwhile. */
    private fun waiting(query: SaveQuery, offline: Boolean, awaited: Awaited?): LaunchGate.Waiting {
        busy -= query.game.id
        log(
            if (offline) "${query.title}: not started, a newer save is on Fuse One and this device is offline" else "${query.title}: not started yet, its newest save is still coming",
            query.game.id, "save",
        )
        return LaunchGate.Waiting(query.title, offline, awaited?.deviceName, awaited?.at)
    }

    private fun side(r: SaveRevision) = SaveSide(r.deviceName, r.at.millis, r.playSeconds, r.size, r.manifest.files.size)

    override suspend fun settle(conflict: SaveConflict, keepHere: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Fuse One isn't connected.")
            val d = device ?: error("Fuse One isn't set up.")
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
        // Open to join only while it was played: the presence said next no longer has it.
        if (hostingHere?.first == query.game.id) hostingHere = null
        // A test run ends with its game, whatever else happens here; one that never started is gone too.
        val probe = probes.remove(query.game.id)?.takeIf { it.snapshot != null }
        val d = device ?: return run { busy -= query.game.id; _nowPlaying.value = null }
        val profile = cached.activeProfile.ifEmpty {
            // Fuse One is set up, but nobody was chosen as playing: the save has no one to go to.
            // Said, rather than left looking as though it synced.
            if (client != null && cached.saves && explained.add("${query.game.id}|no-one")) {
                log("${query.title}: save not sent, nobody is chosen as playing on this device", query.game.id, SyncActivity.FAILED)
                _notices.tryEmit(SyncNotice.NoOneChosen(query.title))
            }
            return run { busy -= query.game.id; _nowPlaying.value = null }
        }
        withContext(Dispatchers.IO) {
            client?.let { c -> runCatching { c.notePresence(PresenceNote(profile, query.game.id, query.title, Presence.SENDING, startedAt)) } }
            // Emulators finish writing a moment after they close.
            delay(SETTLE_MS)
            // The same id the library's own record of this session gets, so it is counted once whichever arrives first.
            val session = SessionEntry(SessionEntry.idOf(startedAt, endedAt), d.deviceId, startedAt, endedAt, query.emulatorId)
            if (cached.records) d.played(profile, query.game, session, ::canonical)
            val total = canonical(d.meta(profile)).game(query.game).totalSeconds
            // A test run (Find Where It Saves): where its save landed is learned, and nothing of it is sent as the person's.
            if (probe != null) {
                runCatching { finishProbe(d, profile, probe, total) }.onFailure { e ->
                    log("${query.title}: the test run couldn't finish (${e.message})", query.game.id, SyncActivity.FAILED)
                    _probeResults.tryEmit(SaveProbeResult.NothingSeen(query.title, probe.roots))
                }
                return@withContext
            }
            learnFromPlay(d, query)
            val c = liveLock.withLock {
                val here = slots(query)
                // This device moves its saves another way (RomM, Syncthing) or not at all: none are kept to send.
                val captured = here.filter { (cached.saves || keepsOwn) && sendsFor(ownerOf(query, it, profile), query.game.id) }.mapNotNull { slot ->
                    runCatching { d.capture(ownerOf(query, slot, profile), slot, total, title = query.title) }.getOrNull()
                        ?.also { log("${query.title}: new ${it.kind.label.lowercase()} kept", query.game.id, "save") }
                }
                // Never silent: a save that couldn't be kept says why, once per game while Fuse runs
                // (only with a host: without one, nothing is sent anywhere).
                if (!keepsOwn && cached.saves && here.any { sendsFor(ownerOf(query, it, profile), query.game.id) } && captured.none { it.kind != SaveKind.STATE }) whyNotKept(query, here, endedAt - startedAt)?.let { why ->
                    if (explained.add("${query.game.id}|$why")) {
                        log("${query.title}: save not sent. $why", query.game.id, SyncActivity.FAILED)
                        _notices.tryEmit(SyncNotice.NotSynced(query.title, SaveKind.SAVE, why))
                    }
                }
                val c = client ?: run {
                    // Without a host, a save replaced by a newer one before it could go anywhere isn't needed.
                    if (keepsOwn) runCatching { d.collect() }
                    return@withLock null
                }
                runCatching { d.flush(c, saves = cached.saves) }
                    .onSuccess { captured.firstOrNull { it.kind != SaveKind.STATE }?.let { r -> _notices.tryEmit(SyncNotice.Sent(query.title, r.kind, live = false)) } }
                    .onFailure { if (it is SyncException) handle(it) }
                c
            } ?: return@withContext
            // Done: nothing playing, nothing left to send (what is still queued says so by itself next time).
            runCatching { c.notePresence(PresenceNote(profile, query.game.id, query.title, if (d.pendingProfiles().isEmpty()) null else Presence.SENDING, startedAt)) }
        }
        busy -= query.game.id
        if (watch == null) _nowPlaying.value = null
    }

    // ---------------------------------------------------------------- Find Where It Saves

    /**
     * A test run: armed at [armedAt] for its game's next start within [PROBE_START_MS], with the
     * folders it watches ([roots]) as they were when the game started ([snapshot]).
     */
    private data class Probe(val query: SaveQuery, val roots: List<String>, val armedAt: Long, val snapshot: SaveProbe.Snapshot? = null)

    /**
     * Before a test run starts: on a device more than one person plays, the folder holds this
     * person's save first, as before any game. A [LaunchGate.Blocked] when it couldn't be swapped in.
     */
    private suspend fun swapIn(d: SyncDevice, query: SaveQuery, profile: String?): LaunchGate.Blocked? {
        profile ?: return null
        for (slot in slots(query)) {
            val owner = ownerOf(query, slot, profile)
            val swapped = runCatching {
                d.handover(owner, slot, { who -> if (who == SHARED_SAVES) 0L else canonical(d.meta(who)).game(query.game).totalSeconds },
                    File(File(dir, KEPT_DIR), REMOVED_DIR), othersHere = d.saveKeepers().any { it != owner && it != SHARED_SAVES })
            }
            swapped.exceptionOrNull()?.let { e ->
                return LaunchGate.Blocked(query.title, "Fuse couldn't put your save for ${query.title} in place, so the test run didn't start. Every file is kept. ${e.message ?: ""}".trim())
            }
        }
        return null
    }

    private val probes = java.util.concurrent.ConcurrentHashMap<String, Probe>()
    private val _probeResults = kotlinx.coroutines.flow.MutableSharedFlow<SaveProbeResult>(extraBufferCapacity = 4)
    override val probeResults: kotlinx.coroutines.flow.SharedFlow<SaveProbeResult> = _probeResults

    override val saveEnvironment: SaveEnvironment get() = saveEnv

    override suspend fun startSaveProbe(query: SaveQuery): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            device ?: error("Turn on Fuse One first: the test run teaches Fuse Sync where this game saves.")
            val q = canonical(query)
            val roots = SaveProbe.roots(q, saveEnv)
            require(roots.isNotEmpty()) { "Fuse found no folder to watch for this game's emulator. Choose its save folder in Settings, Save folders." }
            probes[q.game.id] = Probe(q, roots, clock())
            log("${q.title}: a test run is ready; save sync is paused for it until it closes", q.game.id, "save")
            roots
        }
    }

    override fun cancelSaveProbe(game: GameKey) {
        probes.remove(game.id)
    }

    /**
     * After a test run of [probe]'s game: where its save appeared, learned when Fuse didn't look
     * there; the test save copied aside first; then the person's own save settled: their newest
     * from the host put in place over the test save, or, with no save of theirs anywhere yet, the
     * test save kept as their first.
     */
    private suspend fun finishProbe(d: SyncDevice, profile: String, probe: Probe, total: Long) {
        val q = probe.query
        val changed = SaveProbe.changed(probe.snapshot ?: return, saveEnv)
        val finding = SaveProbe.conclude(q, saveEnv, changed)
        val keptAt = if (changed.isEmpty()) null else keepTestSave(q, changed)
        val result = when (finding) {
            SaveProbe.Finding.Nothing -> SaveProbeResult.NothingSeen(q.title, probe.roots)
            is SaveProbe.Finding.Unusable -> SaveProbeResult.CantUse(q.title, finding.seen, keptAt)
            is SaveProbe.Finding.Known -> SaveProbeResult.Found(q.title, finding.paths.first().substringBeforeLast('/'), learned = false, settledAfterProbe(d, profile, q, total), keptAt)
            is SaveProbe.Finding.Learned -> {
                d.learn(q.game.id, finding.format, finding.folders)
                SaveProbeResult.Found(q.title, finding.folders.joinToString(", ") { "${finding.inFolder}/$it" }, learned = true, settledAfterProbe(d, profile, q, total), keptAt)
            }
            is SaveProbe.Finding.Folder -> {
                saveConfig { it.copy(saveFolders = it.saveFolders + (SaveAdapters.baseId(q.emulatorId) to finding.folder)) }
                SaveProbeResult.Found(q.title, finding.folder, learned = true, settledAfterProbe(d, profile, q, total), keptAt)
            }
        }
        log(
            when (result) {
                is SaveProbeResult.Found -> "${q.title}: the test run found its save in ${result.where}"
                is SaveProbeResult.CantUse -> "${q.title}: the test run saw ${result.seen.size} changed files, in no way Fuse can keep in step yet"
                is SaveProbeResult.NothingSeen -> "${q.title}: the test run saw no new save"
            },
            q.game.id, if (result is SaveProbeResult.Found) "save" else SyncActivity.FAILED,
        )
        _probeResults.tryEmit(result)
    }

    /** The test run's files ([changed]) copied aside on this device, in Fuse's kept folder; where they went. */
    private fun keepTestSave(q: SaveQuery, changed: List<String>): String? {
        val to = File(File(File(File(dir, KEPT_DIR), TEST_SAVES_DIR), safeName(q.title.ifBlank { q.game.id })), keptFolderName())
        var copied = 0
        for (path in changed) {
            val bytes = File(path).takeIf { it.isFile }?.readBytes() ?: saveEnv.readBytes(path, 0, TEST_SAVE_MAX) ?: continue
            val target = File(to, path.trimStart('/').replace(':', '_'))
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
            copied++
        }
        return if (copied == 0) null else to.path
    }

    private fun safeName(text: String) = text.replace(Regex("[^A-Za-z0-9 ._()-]+"), "_").trim('.', ' ').take(80).ifEmpty { "_" }

    /**
     * The person's own save after a test run, now that Fuse knows where it goes: their newest from
     * the host put in place (the test save stays in the history, never as theirs), or, when there is
     * no save of theirs anywhere, the test save kept as their first. Offline, nothing changes now: the
     * next launch brings theirs and asks which to keep. Says what happened, or null.
     */
    private suspend fun settledAfterProbe(d: SyncDevice, profile: String, q: SaveQuery, total: Long): String? {
        val c = client
        var said: String? = null
        for (slot in slots(q).filter { it.available && it.kind != SaveKind.STATE }) {
            val owner = ownerOf(q, slot, profile)
            if (!takesFrom(owner, q.game.id)) continue
            if (c == null) return "Fuse One is offline: your own save comes at the next launch, and Fuse asks which to keep"
            val head = runCatching { c.revisions(owner, slot.game.id, slot.kind).firstOrNull { it.canBeNewest } }
                .getOrElse { return "Fuse One didn't answer: your own save comes at the next launch, and Fuse asks which to keep" }
            if (head != null && SaveSlotFormats.compatible(head.manifest.format, slot.format)) {
                d.place(c, slot, head, owner)
                said = "Your save from ${head.deviceName} is in place"
            } else if (head == null && sendsFor(owner, q.game.id)) {
                d.capture(owner, slot, total, title = q.title)
                runCatching { d.flush(c, saves = cached.saves) }
                said = said ?: "No save of yours was anywhere yet, so the test save is your first"
            }
        }
        return said
    }

    /** This device's sync state, telling the person when a blank save was kept from replacing a real one. */
    private fun newDevice(id: String, name: String): SyncDevice = SyncDevice(File(dir, "device"), id, name).also { d ->
        d.onBlank = { slot ->
            log("A blank ${slot.kind.label.lowercase()} (as the emulator makes it before the game saves) is where your real one was. It wasn't sent, and your real one goes back in place the next time you play it from Fuse", slot.game.id, SyncActivity.FAILED)
        }
    }

    /**
     * For each of [query]'s saves Fuse can't place here because the game's id can't be read (a
     * compressed 3DS image): the folders the newest save says it is kept in on the device that made
     * it, when the formats match and the names are plain folder names. Learned here as if played.
     */
    private suspend fun learnFromHousehold(c: SyncClient, d: SyncDevice, query: SaveQuery, profile: String) {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return
        val owner = if (query.game.id in cached.sharedGames) SHARED_SAVES else profile
        for (spot in runCatching { adapter.locate(query, saveEnv) }.getOrDefault(emptyList())) {
            if (spot.available || spot.learnIn == null || d.learned(query.game.id, spot.format).isNotEmpty()) continue
            val head = c.revisions(owner, query.game.id, spot.kind).firstOrNull { it.canBeNewest } ?: continue
            if (!SaveSlotFormats.compatible(head.manifest.format, spot.format)) continue
            val folders = head.learned.filter { LEARNABLE_NAME.matches(it) }
            if (folders.isEmpty() || folders.size != head.learned.size) continue
            d.learn(query.game.id, spot.format, folders)
            log("${query.title}: learned where its save goes from ${head.deviceName}", query.game.id, "save")
        }
    }

    /** A learnable save's folders as they were before a game: each game's subfolder and when it last changed. */
    private class LearnSnap(val format: String, val folder: String, val times: Map<String, Long>)

    private val learnSnaps = java.util.concurrent.ConcurrentHashMap<String, List<LearnSnap>>()

    /** Explanations already given (game and reason), so each is said once while Fuse runs. */
    private val explained: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun snapshotLearnable(query: SaveQuery) {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return
        val snaps = runCatching { adapter.locate(query, saveEnv) }.getOrDefault(emptyList()).mapNotNull { spot ->
            // Only a save that can't be placed is learned from a play.
            if (spot.available) return@mapNotNull null
            val folder = spot.learnIn ?: return@mapNotNull null
            LearnSnap(spot.format, folder, saveEnv.list(folder).associateWith { saveEnv.modified("$folder/$it") ?: 0L })
        }
        if (snaps.isEmpty()) learnSnaps.remove(query.game.id) else learnSnaps[query.game.id] = snaps
    }

    /**
     * After a game whose saves couldn't be placed: the game folders it changed are its own. Only a
     * clear answer is kept (a few folders, as one game makes); anything else waits for the next play.
     */
    private suspend fun learnFromPlay(d: SyncDevice, query: SaveQuery) {
        val snaps = learnSnaps.remove(query.game.id) ?: return
        for (snap in snaps) {
            val changed = saveEnv.list(snap.folder).filter { name ->
                if (SaveNoise.folder(name) || SaveNoise.file(name)) return@filter false
                val now = saveEnv.modified("${snap.folder}/$name") ?: return@filter false
                val before = snap.times[name]
                before == null || now > before
            }
            if (changed.size !in 1..LEARN_MAX) continue
            d.learn(query.game.id, snap.format, changed)
            log("${query.title}: learned where its save is", query.game.id, "save")
        }
    }

    /**
     * Why nothing of [query]'s save could be kept after it was played for [playedMs], or null when
     * there is nothing to say (it simply didn't change, saves don't sync, the game keeps its own
     * saves, or it was only opened and closed: a game started for a minute has usually not saved
     * yet, and saying "no save found" then reads as a lost save).
     */
    private fun whyNotKept(query: SaveQuery, here: List<LocalSlot>, playedMs: Long): String? {
        if (!cached.saves || query.platform in OWN_SAVES_PLATFORMS) return null
        if (playedMs in 0 until SHORT_PLAY_MS) return null
        val adapter = SaveAdapters.forEmulator(query.emulatorId)
            ?: return SaveAdapters.whyNot(query.emulatorId).takeIf { SaveAdapters.baseId(query.emulatorId) !in OWN_SAVES_EMULATORS }
        val spots = runCatching { adapter.locate(query, saveEnv) }.getOrDefault(emptyList()).filter { it.kind != SaveKind.STATE }
        spots.firstOrNull { !it.available }?.let { return it.note ?: "Fuse can't reach this game's save folder here." }
        val saves = here.filter { it.kind != SaveKind.STATE && it.available }
        if (saves.isNotEmpty() && saves.all { it.files.isEmpty() }) {
            val where = saves.first().let { s -> s.targets.values.firstOrNull()?.parentFile ?: s.root }.path.replace('\\', '/')
            // The emulator's own words on where it saves, when its adapter knows them (melonDS's ROM folders, Eden's users).
            val advice = spots.firstNotNullOfOrNull { it.note }
                ?: "If you saved, use Find Where It Saves in the game's Save History, or choose the emulator's save folder in Settings, Save folders."
            return "Fuse found no save for it in $where. $advice"
        }
        return null
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

    /** The game about to start (or playing) here that is open to join, by its id, with how. */
    @Volatile private var hostingHere: Pair<String, Hosting>? = null

    private val _together = MutableStateFlow<List<Presence>>(emptyList())
    override val together: StateFlow<List<Presence>> = _together.asStateFlow()

    override suspend fun hostNext(query: SaveQuery, hosting: Hosting?) {
        val id = canonical(query).game.id
        // Where the others reach this device: its home network addresses, unless the caller knows better.
        val open = hosting?.let { if (it.addresses.isEmpty()) it.copy(addresses = lanAddresses()) else it }
        hostingHere = open?.let { id to it }
        // Already playing it (hosting from a game that runs): said straight away.
        val w = watch ?: return
        if (w.query.game.id == id) client?.let { c -> runCatching { c.notePresence(PresenceNote(w.profile, id, w.query.title, Presence.PLAYING, w.startedAt, open)) } }
    }

    override suspend fun hostedFor(query: SaveQuery): List<Presence> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext emptyList()
        val id = canonical(query).game.id
        runCatching { lookForHosted(c) }.getOrDefault(_together.value).filter { it.game == id }
    }

    /**
     * The games the household's other devices host now, from the host: kept in [together], and a
     * notice for each one newly open (so an invitation reaches whoever has Fuse open).
     */
    private suspend fun lookForHosted(c: SyncClient): List<Presence> {
        val me = device?.deviceId
        val now = clock()
        val list = withTimeoutOrNull(PRESENCE_WAIT_MS) { c.presence() } ?: return _together.value
        val open = list.filter { it.hosting != null && it.deviceId != me && it.state == Presence.PLAYING && now - it.at <= PLAYING_ASK_MS }
        val before = _together.value.map { it.deviceId to it.game }.toSet()
        _together.value = open
        val names = profiles.value.associate { it.id to it.name }
        for (p in open) if ((p.deviceId to p.game) !in before) _notices.tryEmit(SyncNotice.Hosting(names[p.profile] ?: p.deviceName, p.deviceName, p.title))
        return open
    }

    private val _unclaimed = MutableStateFlow<List<UnclaimedSave>>(emptyList())
    override val unclaimed: StateFlow<List<UnclaimedSave>> = _unclaimed.asStateFlow()

    /** Titles of games whose saves were kept aside, as they were launched (the device keeps only ids). */
    private val unclaimedTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** What this device keeps aside for someone to claim, read again after anything that can change it. */
    private fun refreshUnclaimed() {
        val d = device ?: return run { _unclaimed.value = emptyList() }
        _unclaimed.value = d.unclaimedSaves().map { (id, kind, m) ->
            val key = GameKey(id.substringBefore(':'), id.substringAfter(':'))
            val title = unclaimedTitles[id] ?: key.identity.replace('-', ' ').replace('_', ' ').split(' ')
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            UnclaimedSave(key, title, kind, m.files.size, m.size)
        }
    }

    override suspend fun claim(save: UnclaimedSave, profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = device ?: error("Fuse Sync isn't set up.")
            require(profile.isNotBlank()) { "Choose whose save it is." }
            if (!d.claimUnclaimed(profile, save.game, save.kind)) error("That save isn't waiting any more.")
            log("${save.title}: the save kept aside is now ${people.profiles.firstOrNull { it.info.id == profile }?.info?.name ?: "this person"}'s", save.game.id, "save")
        }.also { refreshUnclaimed() }
    }
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
        // A test run's save is watched for where it lands, never sent while it runs.
        if (probes.containsKey(query.game.id)) return
        val profile = cached.activeProfile.ifEmpty { return }
        if (device == null || !cached.enabled) return
        watch?.job?.cancel()
        // How the save looks as the game starts (what came down before it), taken now, before the game can write.
        val first = withContext(Dispatchers.IO) { looks(query) }
        busy += query.game.id
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
    private fun looks(query: SaveQuery): List<String> {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return emptyList()
        // A save in a granted folder is looked at through the folder's own times, never copied every few seconds.
        val spots = runCatching { adapter.locate(query, saveEnv) }.getOrDefault(emptyList())
        if (spots.any { s -> listOfNotNull(s.root) .plus(s.mounts.values).plus(s.files.map { it.path }).any(TreePaths::isTree) }) {
            return spots.flatMap { s -> listOfNotNull(s.root).plus(s.mounts.values).plus(s.files.map { it.path }) }.map { "$it@${saveEnv.modified(it)}" }
        }
        return slots(query, live = true).flatMap { slot ->
            slot.files.map { f -> "${f.path}:${if (f.file.isFile) "${f.file.length()}@${f.file.lastModified()}" else "-"}" }
        }
    }

    /**
     * Every [LIVE_LOOK_MS] while the game runs: says it is still playing, and when the game has
     * written its save and left it alone since the last look, keeps it and sends it.
     */
    private suspend fun watchWhilePlaying(query: SaveQuery, profile: String, startedAt: Long, first: List<String>) {
        var saidAt = 0L
        suspend fun say() {
            saidAt = clock()
            val hosting = hostingHere?.takeIf { it.first == query.game.id }?.second
            client?.let { c -> runCatching { c.notePresence(PresenceNote(profile, query.game.id, query.title, Presence.PLAYING, startedAt, hosting)) } }
        }
        say()
        var seen = first
        var moving = false
        while (true) {
            delay(liveLookMs)
            // The lid closed mid-game: the save went as the screen went off, and the looks carry on once it wakes.
            awake.awaitAwake()
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
            // Others ask whether a game was played in the last half hour: once a minute says so,
            // without a request on every look at the save.
            if (clock() - saidAt >= PRESENCE_EVERY_MS) say()
        }
    }

    private suspend fun sendLive(query: SaveQuery, profile: String, startedAt: Long) {
        val d = device ?: return
        // Saves move another way here (RomM, Syncthing) or not at all.
        if (!cached.saves) return
        val played = ((clock() - startedAt) / 1000).coerceAtLeast(0)
        val total = canonical(d.meta(profile)).game(query.game).totalSeconds + played
        val captured = slots(query).filter { sendsFor(ownerOf(query, it, profile), query.game.id) }.mapNotNull { slot -> runCatching { d.capture(ownerOf(query, slot, profile), slot, total, title = query.title) }.getOrNull() }
        if (captured.isEmpty()) return
        log("${query.title}: new ${captured.first().kind.label.lowercase()} kept while playing", query.game.id, "save")
        val c = client ?: return
        runCatching { d.flush(c, saves = cached.saves) }
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
            ?.firstOrNull { it.device == other.deviceId && it.canBeNewest }?.at?.millis
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
        val profile = cached.activeProfile.ifEmpty { return@withContext null }
        // Without a host, what this device keeps for the person on its own.
        val c = client ?: return@withContext device?.takeIf { keepsOwn }?.let { d ->
            _profiles.value.firstOrNull { it.id == profile }?.let { runCatching { d.localReport(it) }.getOrNull() }
        }
        runCatching { c.report(profile) }.onFailure { if (it is SyncException) handle(it) }.getOrNull()
    }

    override suspend fun versions(asked: SaveQuery, kind: SaveKind): List<SaveVersion> = withContext(Dispatchers.IO) {
        val query = canonical(asked)
        val profile = cached.activeProfile.ifEmpty { return@withContext emptyList() }
        // Without a host: the saves this device kept from before imports, which it can put back itself.
        val c = client ?: return@withContext importBackups(query, kind, profile).map {
            SaveVersion(LOCAL_BACKUP + it.id, cached.deviceName.ifBlank { defaultDeviceName }, it.at, 0, it.bytes, RevisionReason.BEFORE_RESTORE, false, provenance = "Before import (this device)")
        }
        val slot = slots(query).firstOrNull { it.kind == kind }
        val key = slot?.game ?: query.game
        val owner = slot?.let { ownerOf(query, it, profile) } ?: profile
        val list = runCatching { c.revisions(owner, key.id, kind) }.getOrDefault(emptyList())
        val head = list.firstOrNull { it.canBeNewest }?.id
        list.map { SaveVersion(it.id, it.deviceName, it.at.millis, it.playSeconds, it.size, it.reason, it.id == head, kept = it.kept, provenance = it.provenance) }
    }

    override suspend fun restore(asked: SaveQuery, kind: SaveKind, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        val query = canonical(asked)
        if (version.startsWith(LOCAL_BACKUP)) return@withContext restoreImportBackup(query, kind, cached.activeProfile, version.removePrefix(LOCAL_BACKUP))
        runCatching {
            val c = client ?: error("Fuse One isn't connected.")
            val d = device ?: error("Fuse One isn't set up.")
            val slot = slots(query).firstOrNull { it.kind == kind } ?: error("That save isn't here.")
            val owner = ownerOf(query, slot, cached.activeProfile)
            val rev = c.revisions(owner, slot.game.id, kind).firstOrNull { it.id == version } ?: error("That version is gone.")
            // What is here now is kept in the history first, then the old one becomes the newest everywhere.
            d.restore(c, owner, slot, rev, title = query.title)
            d.flush(c, saves = cached.saves)
            log("${query.title}: restored the save from ${rev.deviceName}", query.game.id, "restore")
        }
    }

    // A version is the person's own, or (for a game played as one save) everyone's.
    override suspend fun keepVersion(version: String, keep: Boolean): Result<Unit> = withClient { c ->
        runCatching { c.pin(cached.activeProfile, version, keep) }.recoverCatching { c.pin(SHARED_SAVES, version, keep) }.getOrThrow()
        Unit
    }

    // ---------------------------------------------------------------- leaving

    override suspend fun unlink(keepProfiles: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Sends what it can first; then forgets the host. Nothing of this device's own changes.
            client?.let { c ->
                runCatching { device?.flush(c, saves = cached.saves) }
                if (keepProfiles) bringDownRecords(c)
                runCatching { c.unlinkSelf() }
            }
            loop?.cancel()
            nudge?.cancel()
            householdHere.off()
            watch?.job?.cancel()
            watch = null
            _nowPlaying.value = null
            cancelJoin()
            dropClient()
            // The people who played here stay as this device's own profiles, unless they should go too.
            val stay = if (keepProfiles) runCatching { stayLocal() }.getOrDefault(emptyList()) else emptyList()
            secrets.remove(LINK_KEY)
            File(dir, PROFILES_FILE).delete()
            File(dir, DEVICES_FILE).delete()
            val active = cached.activeProfile.takeIf { it in stay }.orEmpty()
            saveConfig { it.copy(role = if (it.role == "HOST") "HOST" else "", activeProfile = active, hostName = if (it.role == "HOST") it.hostName else "", localAddress = "", remoteAddress = "", sharedGames = emptyList()) }
            _sharedGames.value = emptySet()
            _active.value = null
            _profiles.value = emptyList()
            _devices.value = emptyList()
            _joins.value = emptyList()
            _status.value = SyncStatus.NotSetUp
            showLocal()
        }
    }

    override suspend fun renameDevice(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = hostServer?.store
            when {
                s != null -> s.changeDevice(id, DeviceChange(name = name))
                hostAdmin != null -> hostAdmin!!.rename(id, name)
                id != cached.deviceId && managing() -> client?.renameDevice(id, name)
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
                managing() -> checkNotNull(client) { "Fuse One isn't connected." }.unlinkDevice(id)
                else -> error("Unlink other devices on the host, or sign in as the host here.")
            }
            refreshLists()
            refreshHostView()
        }
    }

    private fun lanAddresses(): List<String> = LanAddresses.list()

    // Last of all, once every field above is made: started from the first init block, a busy machine
    // ran start() on another thread before its locks existed, and it stopped there without a word.
    init {
        scope.launch(Dispatchers.IO) { runCatching { start() } }
        // What syncs (saves, states, records, settings, the folders chosen) follows Settings as it changes.
        scope.launch(Dispatchers.IO) { runCatching { settings.settings.collect { cached = it.sync } } }
    }

    companion object {
        /** The id a save kept on this device from before an import has in [versions], beside the host's. */
        const val LOCAL_BACKUP = "local:"

        /** At most this many artwork calls a minute go to the host; the rest go to the providers. */
        const val ART_PER_MINUTE = 300

        /** How long pictures skip a host that didn't answer. */
        const val ART_AWAY_MS = 2 * 60_000L

        /** Why a host at [address] might not answer, and what to check. */
        internal fun unreachableWords(address: String): String {
            val shown = address.removePrefix("http://").removePrefix("https://")
            return "The host didn't answer at $shown. Check that both are on the same Wi-Fi, and that the host's firewall lets Fuse in (port ${shown.substringAfterLast(':', SyncApi.DEFAULT_PORT.toString())})."
        }

        const val LINK_KEY = "sync.link"

        /** Where the host keeps the household's whole copies unless the person chose a folder. */
        const val HOUSEHOLD_BACKUPS_DIR = "household-backups"

        /** Where a backup keeps its copy of the household. */
        const val BACKUP_DIR = "backup-host"

        /** How often a backup copies the household and looks for the host (and the host for an older one). */
        const val KEEP_EVERY_MS = 60_000L

        /** How long a replaced client is kept for the calls already on their way with it. */
        const val RETIRE_MS = 60_000L

        /** How long the host must be silent before a backup says so. */
        const val HOST_AWAY_SHOWN_MS = 2 * 60_000L

        /** How long the host must be silent before a person may make a backup the host. */
        const val TAKE_OVER_AFTER_MS = 5 * 60_000L

        /** The host's own folder inside a chosen folder that holds other things. */
        const val HOST_FOLDER = "Fuse Sync Host"

        /** Where saves that never reached a forgotten host are kept, inside Fuse Sync's folder. */
        const val KEPT_DIR = "kept"

        /** How long an armed test run waits for its game to start before it is called off. */
        const val PROBE_START_MS = 10 * 60_000L

        /** Where test runs' saves are copied aside, inside [KEPT_DIR]. */
        const val TEST_SAVES_DIR = "Test saves"

        /** The largest file a test run copies aside when it can only be read through the save environment. */
        const val TEST_SAVE_MAX = 256 * 1024 * 1024

        /** The longest a launch waits for the host to settle game ids. */
        const val RESOLVE_WAIT_MS = 3_000L
        private const val RESOLVE_CHUNK = 1_000

        /** How often a device waiting to be let in asks whether it has been. */
        const val JOIN_POLL_MS = 1_500L
        private const val PROFILES_FILE = "profiles.json"
        private const val DEVICES_FILE = "devices.json"

        /** This device's own people: its profiles without a host, their order, PINs typed here. */
        const val PEOPLE_FILE = "people.json"

        /** This device's sync history, newest first, and how much of it is kept. */
        const val HISTORY_FILE = "history.json"
        const val HISTORY_KEEP = 200
        const val ADOPTED_FILE = "adopted"

        /** A host joined while this device had profiles, until who is who is settled. */
        const val PENDING_LINK_KEY = "sync.link.pending"

        /** Inside the kept folder: saves found in an emulator's folder after their profile was removed. */
        const val REMOVED_DIR = "Removed profiles"

        /** The longest a launch waits on the host before playing with what is here. */
        const val LAUNCH_WAIT_MS = 8_000L

        /** How often a running game's save is looked at. */
        const val LIVE_LOOK_MS = 15_000L

        /** How often a device playing says so to the host (see [PLAYING_ASK_MS]). */
        const val PRESENCE_EVERY_MS = 60_000L
        const val HELLO_EVERY_MS = 10 * 60_000L

        /** How often a host on an older Fuse than this device is asked which it runs now. */
        const val HELLO_UPDATING_MS = 60_000L
        const val FORGET_WAIT_MS = 5_000L
        const val HOST_RELEASE_MS = 1_500L

        /** How long after the save check a game has to start before Fuse stops keeping in step for it. */
        const val READY_MS = 45_000L

        /** How often saves made outside Fuse are looked for ([captureOutside]). */
        const val OUTSIDE_EVERY_MS = 5 * 60_000L

        /** How long a save's files must be left alone before one made outside Fuse is kept. */
        const val OUTSIDE_SETTLED_MS = 60_000L
        const val PRESENCE_WAIT_MS = 3_000L

        /** How often the device list is read again for when each device was last seen (under [ONLINE_MS]). */
        const val LISTS_EVERY_MS = 45_000L

        /** How soon the household's side is tried again after it didn't come up. */
        const val HOUSEHOLD_RETRY_MS = 30_000L

        /** Another device heard from this lately while playing (it may have gone to sleep mid-game). */
        const val PLAYING_ASK_MS = 30 * 60_000L

        /** Another device done with it and sending, heard from this lately. */
        const val SENDING_ASK_MS = 3 * 60_000L

        /** How long after an emulator closes its saves are read (it may still be writing them). */
        const val SETTLE_MS = 1_500L

        /** A play shorter than this that left no save says nothing about it (see [whyNotKept]). */
        const val SHORT_PLAY_MS = 5 * 60_000L

        /** When a device's records joining a profile already in use count as made: before anything real. */
        const val ADOPTED_AT = 1L

        /** The longest a switch waits on the host, when it has to ask (someone new to this device). */
        const val HOST_SWITCH_MS = 8_000L

        /** Tries, a moment apart, for a background service holding the host's port to answer. */
        /** The most game folders one play may change and still be learned as one game's. */
        const val LEARN_MAX = 4

        /** A folder name learned from another device: one plain name, never a path. */
        val LEARNABLE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

        /** Systems whose games keep their own saves (PC games, Android apps): nothing to explain. */
        val OWN_SAVES_PLATFORMS = setOf("win", "steam", "android", "dos")
        val OWN_SAVES_EMULATORS = setOf(
            "steam", "steam-url", "desktop", "shortcut", "open", "script", "winlator", "winlator-cmod", "winlator-frost", "winlator-glibc",
            "winlator-proot", "winnative", "gamehub", "gamehub-lite", "gamehub-lite-local", "gamenative", "bannerlator", "dosbox-staging", "dosbox-x",
        )

        const val ADMIN_TRIES = 4
        /** Said of a host on this computer that holds its port but doesn't answer. */
        private const val NOT_ANSWERING = "?"
        const val ADMIN_RETRY_MS = 750L

        /** How long Fuse waits for a freshly started service to answer. */
        const val SERVICE_WAIT_MS = 15_000L
    }
}
