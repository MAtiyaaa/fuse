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
    private val saveEnv: SaveEnvironment = WithSaveFolders(files, { game, format -> device?.learned(game, format).orEmpty() }) { cached.saveFolders }

    init {
        scope.launch(Dispatchers.IO) { runCatching { start() } }
        // What syncs (saves, states, records, settings, the folders chosen) follows Settings as it changes.
        scope.launch(Dispatchers.IO) { runCatching { settings.settings.collect { cached = it.sync } } }
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
        if (!c.enabled || c.role.isEmpty()) {
            _status.value = if (!c.enabled) SyncStatus.Off else SyncStatus.NotSetUp
            showLocal()
            resumeMerge()
            return
        }
        val id = ensureDeviceId()
        if (device == null) device = SyncDevice(File(dir, "device"), id, c.deviceName.ifBlank { defaultDeviceName })
        if (c.role == "HOST") startHostServer(c)
        val l = link()
        if (l == null) {
            _status.value = SyncStatus.NotSetUp
            showLocal()
            resumeMerge()
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
        if (device == null) device = SyncDevice(File(dir, "device"), ensureDeviceId(), cached.deviceName.ifBlank { defaultDeviceName })
        _profiles.value = people.ordered(people.profiles.map { it.info })
        _active.value = _profiles.value.firstOrNull { it.id == cached.activeProfile }
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
                hostAdmin = admin
                adminStatus = answer
                refreshHostView()
                return
            }
            // Something else holds the port (or the service went away just now): serve here if the port is free again.
            if (!HostAdmin.portFree(c.hostPort)) {
                log("The host can't start: another program on this computer is using port ${c.hostPort}, which the host needs")
                refreshHostView()
                return
            }
        }
        val store = HostStore(hostDir, clock, hostName = c.hostName.ifBlank { defaultDeviceName })
        val server = runCatching { SyncHost(store, c.hostPort, fuseVersion, clock).start() }.getOrNull()
        if (server != null) {
            hostServer = server
            responder = Discovery.answer({ store.hello(c.hostPort, fuseVersion) })
            // Off the interface's thread: the host's status reads its own files.
            scope.launch(Dispatchers.IO) { server.changes.collect { refreshHostView() } }
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
        val active = cached.activeProfile
        // The profile in use was deleted on another device: this device simply stops using it
        // (everything here stays as it is) and asks who is playing.
        if (e.code == "locked" && active.isNotEmpty() && _profiles.value.isNotEmpty() && _profiles.value.none { it.id == active }) {
            scope.launch(Dispatchers.IO) {
                if (cached.activeProfile != active) return@launch
                runCatching { switchTo(null) }
                log("The profile in use here was deleted on another device. Choose who's playing.")
            }
            return
        }
        _status.value = when (e.code) {
            "offline" -> SyncStatus.Offline(name, device?.pendingCount ?: 0, (status.value as? SyncStatus.Offline)?.since ?: clock())
            // Asked to slow down: nothing is wrong, the next round simply waits a little longer.
            "rate" -> return
            "revoked" -> SyncStatus.NeedsAttention(name, "This device was unlinked from $name. Connect it again from Settings, Addons, Fuse Sync.", e.code)
            "clock" -> SyncStatus.NeedsAttention(name, "This device's clock is far from the host's. Set the date and time, and syncing carries on.", e.code)
            "locked" -> SyncStatus.NeedsAttention(name, "This profile's PIN changed on another device. Open it again with the new PIN to keep syncing.", e.code)
            else -> SyncStatus.NeedsAttention(name, e.message ?: "The host said no.", e.code)
        }
    }

    private suspend fun refreshLists() {
        val c = client ?: return
        _profiles.value = people.ordered(c.profiles())
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
        runCatching { adoptOutside(c) }
        if (active.isNotEmpty()) captureChanges(active)
        runCatching { c.sharedGames().games }.onSuccess { games ->
            if (games.toSet() != cached.sharedGames.toSet()) saveConfig { it.copy(sharedGames = games) }
            _sharedGames.value = games.toSet()
        }
        val sent = d.flush(c)
        if (active.isNotEmpty()) {
            d.pullMeta(c, active)
            data.write(canonical(d.meta(active)))
        }
        if (sent > 0) log(if (sent == 1) "Sent 1 change to $name" else "Sent $sent changes to $name")
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = false, pending = d.pendingCount)
    }

    /** Sends what is queued (saves included) without bringing anything in. */
    private suspend fun sendWaiting() = work.withLock {
        val c = client ?: return@withLock
        val d = device ?: return@withLock
        if (d.pendingCount == 0) return@withLock
        val name = cached.hostName.ifBlank { c.link.hostName }
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = true, pending = d.pendingCount)
        val sent = d.flush(c)
        if (sent > 0) log(if (sent == 1) "Sent 1 change to $name" else "Sent $sent changes to $name")
        _status.value = SyncStatus.Online(name, c.route ?: Route.LOCAL, working = false, pending = d.pendingCount)
    }

    private suspend fun pullActive() = work.withLock {
        val c = client ?: return@withLock
        val d = device ?: return@withLock
        val active = cached.activeProfile.ifEmpty { return@withLock }
        captureChanges(active)
        d.pullMeta(c, active)
        data.write(canonical(d.meta(active)))
        log("Brought in changes from your other devices")
    }

    /** What changed here since the profile was put in place becomes changes waiting to go. */
    private suspend fun captureChanges(profile: String) {
        val d = device ?: return
        val local = data.read(d.deviceId, hlc)
        val c = cached
        // Compared by the household's one id for each game: records kept here under another id
        // (from before the host settled it) are the same game, never a new one.
        val base = canonical(d.meta(profile))
        // Only what this device syncs: with records off it reads none, which must never read as
        // every collection deleted (or settings, with settings off).
        val changes = ProfileDiff.changes(base, local, d.deviceId, hlc).let { all ->
            all.copy(
                games = if (c.records) all.games else emptyMap(),
                collections = if (c.records) all.collections else emptyMap(),
                settings = if (c.settings) all.settings else emptyMap(),
            )
        }
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
            runCatching { withTimeoutOrNull(FORGET_WAIT_MS) { device?.flush(c) } }
            if (keepProfiles) bringDownRecords(c)
            runCatching { withTimeoutOrNull(FORGET_WAIT_MS) { c.unlinkSelf() } }
        }
        stop()
        if (cached.role == "HOST" && lifetime.state().installed) runCatching { lifetime.remove() }
        client = null
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
        dir.listFiles()?.filter { it.canonicalFile != keep && it.name !in keepNames }?.forEach { it.deleteRecursively() }
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
        log("Fuse Sync forgot its host")
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
        if (noPin.isNotEmpty()) log("${noPin.joinToString(", ") { it.name }} kept without a PIN here, as it was never typed on this device. Set one in Profiles.")
        log(if (stay.size == 1) "${stay[0].name} stays on this device as its own profile" else "${stay.size} profiles stay on this device as its own")
        return stay.map { it.id }
    }

    /** [text] as a safe folder name. */
    private fun safeName(text: String) = text.replace(Regex("[^A-Za-z0-9 ._()-]+"), "_").trim('.', ' ').take(80).ifEmpty { "_" }

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
            log("The host's saves moved to ${target.path}")
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
        val c = pendingClient?.takeIf { it.link == linked } ?: SyncClient(linked).also { pendingClient = it }
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
            if (pendingLink != null) log("Didn't join ${pendingLink?.hostName}. This device keeps its own profiles")
            finishMerge()
            _status.value = if (cached.enabled) SyncStatus.NotSetUp else SyncStatus.Off
        }
    }

    private suspend fun finishMerge() {
        pendingLink = null
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
        val d = device ?: SyncDevice(File(dir, "device"), ensureDeviceId(), cached.deviceName.ifBlank { defaultDeviceName }).also { device = it }
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
                            if (!made.protected) log("${p.name}'s PIN couldn't go to ${linked.hostName}, which runs an older Fuse. Set it again in Profiles.")
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
            if (left.isNotEmpty()) log(if (left.size == 1) "${left[0].name} was left out; their saves are in Fuse Sync's kept folder" else "${left.size} profiles were left out; their saves are in Fuse Sync's kept folder")
        }
        finishMerge()
        linkFor(linked)
        _active.value = _profiles.value.firstOrNull { it.id == cached.activeProfile }
        log(if (ids.size == 1) "Your profile is on ${linked.hostName} now" else "Your ${ids.size} profiles are on ${linked.hostName} now")
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

    /**
     * [meta] by each game's one id across devices: records kept under an id the household has since
     * settled on another (a title, where the serial is now the id) join that game's record, and
     * collections name their games the same way. Merging is safe: counters take the larger, sessions
     * join by id, settings take the later.
     */
    private fun canonical(meta: ProfileMeta): ProfileMeta = meta.byIds(gameAliases)

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
            linkUp(if (outside != null) link.copy(remoteAddress = SyncClient.normalise(outside)) else link)
        }
    }

    // ---------------------------------------------------------------- the host's account and outside address

    /** The account's username as the background service's host last said (it isn't in its status). */
    @Volatile private var adminAccount: String? = null

    override suspend fun setHostAccount(username: String, password: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val store = hostServer?.store
            when {
                store != null -> store.setAccount(username, password)
                hostAdmin != null -> hostAdmin!!.setAccount(username, password)
                else -> error("Start the host first.")
            }
            adminAccount = username.trim()
            refreshHostView()
            log("The host's account is set")
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
                else -> error("Start the host first.")
            }
            adminStatus = runCatching { hostAdmin?.status() }.getOrNull() ?: adminStatus
            // This computer's own link knows it too.
            saveConfig { it.copy(remoteAddress = clean, remoteFromHost = true) }
            refreshHostView()
        }
    }

    @Volatile private var helloAt = 0L

    /**
     * The host's address from outside, as it says it, becomes this device's (unless the person
     * typed another here): set once on the host, every device can reach it from away.
     */
    private suspend fun adoptOutside(c: SyncClient) {
        if (clock() - helloAt < HELLO_EVERY_MS) return
        helloAt = clock()
        val outside = runCatching { c.status().hello.outside }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val cfg = cached
        if (cfg.remoteAddress.isNotBlank() && !cfg.remoteFromHost) return
        if (SyncClient.normalise(cfg.remoteAddress.ifBlank { "x" }) == SyncClient.normalise(outside)) return
        saveConfig { it.copy(remoteAddress = outside, remoteFromHost = true) }
        client = SyncClient(c.link.copy(remoteAddress = SyncClient.normalise(outside)))
        log("Learned the host's address from outside")
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
            // A folder chosen for the host that already holds other things (a whole drive, say)
            // gets a folder of the host's own inside it, so the host's files never mix with them.
            val chosen = cached.hostDataDir.ifBlank { null }?.let(::File)
            if (chosen != null && chosen.isDirectory && !File(chosen, "host.json").isFile && HostFiles.holdsOthers(chosen)) {
                saveConfig { it.copy(hostDataDir = File(chosen, HOST_FOLDER).path.replace('\\', '/')) }
            }
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
                    found = HostAdmin.of(hostDir(c), c.hostPort)
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
        log("Made a profile for $clean")
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
                log(if (written > 0) "${p.name} was deleted; their saves are in Fuse Sync's kept folder" else "${p.name} was deleted")
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
        return withClient { c -> c.openProfile(id, pin); rememberPin(id, pin); Unit }
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
            val d = device ?: error("Fuse Sync isn't set up.")
            val c = client
            work.withLock {
                val before = cached.activeProfile
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
                        c.openProfile(id, pin)
                        rememberPin(id, pin)
                    } catch (e: SyncException) {
                        // The host is away: a profile without a PIN is switched to here and catches up
                        // later; one with a PIN waits, since only the host can check it.
                        val known = _profiles.value.firstOrNull { it.id == id }
                        if (e.code != "offline" || known == null) throw e
                        if (known.protected) throw SyncException("${known.name}'s profile has a PIN, which only the host can check. Try again when it's back.", "offline", 0)
                    }
                }
                // The first profile this device ever uses takes in what it already had (nothing is lost).
                val first = before.isEmpty() && d.meta(id) == ProfileMeta() && !adopted()
                if (first) {
                    // A profile someone already uses keeps what it has: this device's records only
                    // fill what it doesn't (stamped older than anything real), and its play time
                    // and sessions join. Only a new, empty profile takes this device's as they are,
                    // and only the host can say it is new: offline, nothing here is taken as newer.
                    val reached = c != null && runCatching { d.pullMeta(c, id) }.isSuccess
                    // A profile made here is new by definition: it takes what this device has as it is.
                    val fresh = own != null || (reached && d.meta(id) == ProfileMeta())
                    val local = data.read(d.deviceId, hlc)
                    val adopt = ProfileDiff.changes(ProfileMeta(), local, d.deviceId, if (fresh) hlc else HlcClock(d.deviceId) { ADOPTED_AT })
                    d.changeMeta(id) { pending, _ -> pending.merge(adopt) }
                    saveConfig { it.copy(activeProfile = id) }
                    markAdopted()
                }
                // One round for the records (everyone's waiting, then this person's newest); saves,
                // which can be large, never hold a switch up.
                if (c != null) runCatching {
                    d.flush(c, saves = false)
                    d.pullMeta(c, id)
                }
                saveConfig { it.copy(activeProfile = id) }
                data.write(canonical(d.meta(id)))
                d.useProfile(id)
                _active.value = _profiles.value.firstOrNull { it.id == id }
                log("Switched to ${_active.value?.name ?: "a profile"}")
            }
            // Saves waiting to go (the last person's, anyone's) go up now, behind the switch. Only
            // the saves: the records were just brought in, so nothing here rewrites the library
            // while the person starts using it.
            if (c != null) scope.launch(Dispatchers.IO) { runCatching { sendWaiting() }.onFailure { if (it is SyncException) handle(it) } }
        }
    }

    private fun adopted(): Boolean = File(dir, ADOPTED_FILE).isFile
    private fun markAdopted() {
        dir.mkdirs()
        File(dir, ADOPTED_FILE).writeText("1")
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
        // Profiles work without a host too: each person's save is put in place here all the same.
        if (d == null || profile.isEmpty() || !(cached.enabled || keepsOwn)) return@withContext LaunchGate.Go()
        // Another device playing it, or still sending what it just saved: the person decides whether to wait.
        if (waitForOthers && c != null) othersOn(c, d, query, profile)?.let { return@withContext it }
        // From here until its save is sent after it stops (Android keeps Fuse going meanwhile).
        getReady(query.title)
        // Saves Fuse can't place yet (the game's id unknown): what each game's folder looks like now,
        // so the folders this play changes are learned as this game's.
        snapshotLearnable(query)
        var note: String? = null
        for (slot in slots(query)) {
            // On a device more than one person plays, the folder must hold this person's save
            // first (whoever played last keeps theirs); this needs no host, so it happens offline too.
            val owner = ownerOf(query, slot, profile)
            runCatching { d.handover(owner, slot, { who -> if (who == SHARED_SAVES) 0L else canonical(d.meta(who)).game(query.game).totalSeconds }, File(File(dir, KEPT_DIR), REMOVED_DIR)) }
                .onFailure { log("${query.title}: couldn't swap in this person's save (${it.message})", query.game.id, "save") }
            if (c == null) {
                // Without a host there is nothing to be offline from.
                if (!keepsOwn) note = note ?: "Fuse Sync is offline: playing with this device's save"
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
            if (cached.records) d.played(profile, query.game, session, ::canonical)
            val total = canonical(d.meta(profile)).game(query.game).totalSeconds
            learnFromPlay(d, query)
            val c = liveLock.withLock {
                val here = slots(query)
                val captured = here.mapNotNull { slot ->
                    runCatching { d.capture(ownerOf(query, slot, profile), slot, total, title = query.title) }.getOrNull()
                        ?.also { log("${query.title}: new ${it.kind.label.lowercase()} kept", query.game.id, "save") }
                }
                // Never silent: a save that couldn't be kept says why, once per game while Fuse runs
                // (only with a host: without one, nothing is sent anywhere).
                if (!keepsOwn && captured.none { it.kind != SaveKind.STATE }) whyNotKept(query, here)?.let { why ->
                    if (explained.add("${query.game.id}|$why")) {
                        log("${query.title}: save not sent. $why", query.game.id, "save")
                        _notices.tryEmit(SyncNotice.NotSynced(query.title, SaveKind.SAVE, why))
                    }
                }
                val c = client ?: run {
                    // Without a host, a save replaced by a newer one before it could go anywhere isn't needed.
                    if (keepsOwn) runCatching { d.collect() }
                    return@withLock null
                }
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

    /** A learnable save's folders as they were before a game: each game's subfolder and when it last changed. */
    private class LearnSnap(val format: String, val folder: String, val times: Map<String, Long>)

    private val learnSnaps = java.util.concurrent.ConcurrentHashMap<String, List<LearnSnap>>()

    /** Explanations already given (game and reason), so each is said once while Fuse runs. */
    private val explained: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun snapshotLearnable(query: SaveQuery) {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return
        val snaps = runCatching { adapter.locate(query, saveEnv) }.getOrDefault(emptyList()).mapNotNull { spot ->
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
     * Why nothing of [query]'s save could be kept after it was played, or null when there is nothing
     * to say (it simply didn't change, saves don't sync, or the game keeps its own saves).
     */
    private fun whyNotKept(query: SaveQuery, here: List<LocalSlot>): String? {
        if (!cached.saves || query.platform in OWN_SAVES_PLATFORMS) return null
        val adapter = SaveAdapters.forEmulator(query.emulatorId)
            ?: return SaveAdapters.whyNot(query.emulatorId).takeIf { SaveAdapters.baseId(query.emulatorId) !in OWN_SAVES_EMULATORS }
        val spots = runCatching { adapter.locate(query, saveEnv) }.getOrDefault(emptyList()).filter { it.kind != SaveKind.STATE }
        spots.firstOrNull { !it.available }?.let { return it.note ?: "Fuse can't reach this game's save folder here." }
        val saves = here.filter { it.kind != SaveKind.STATE && it.available }
        if (saves.isNotEmpty() && saves.all { it.files.isEmpty() }) {
            val where = saves.first().let { s -> s.targets.values.firstOrNull()?.parentFile ?: s.root }.path.replace('\\', '/')
            return "Fuse found no save for it in $where. If you saved, choose the emulator's save folder in Fuse, Settings, Save folders."
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
        val total = canonical(d.meta(profile)).game(query.game).totalSeconds + played
        val captured = slots(query).mapNotNull { slot -> runCatching { d.capture(ownerOf(query, slot, profile), slot, total, title = query.title) }.getOrNull() }
        if (captured.isEmpty()) return
        log("${query.title}: new ${captured.first().kind.label.lowercase()} kept while playing", query.game.id, "save")
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
        val head = list.firstOrNull { it.canBeNewest }?.id
        list.map { SaveVersion(it.id, it.deviceName, it.at.millis, it.playSeconds, it.size, it.reason, it.id == head, kept = it.kept) }
    }

    override suspend fun restore(asked: SaveQuery, kind: SaveKind, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        val query = canonical(asked)
        runCatching {
            val c = client ?: error("Fuse Sync isn't connected.")
            val d = device ?: error("Fuse Sync isn't set up.")
            val slot = slots(query).firstOrNull { it.kind == kind } ?: error("That save isn't here.")
            val owner = ownerOf(query, slot, cached.activeProfile)
            val rev = c.revisions(owner, slot.game.id, kind).firstOrNull { it.id == version } ?: error("That version is gone.")
            // What is here now is kept in the history first, then the old one becomes the newest everywhere.
            d.restore(c, owner, slot, rev, title = query.title)
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

    override suspend fun unlink(keepProfiles: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Sends what it can first; then forgets the host. Nothing of this device's own changes.
            client?.let { c ->
                runCatching { device?.flush(c) }
                if (keepProfiles) bringDownRecords(c)
                runCatching { c.unlinkSelf() }
            }
            loop?.cancel()
            nudge?.cancel()
            watch?.job?.cancel()
            watch = null
            _nowPlaying.value = null
            cancelJoin()
            client = null
            // The people who played here stay as this device's own profiles, unless they should go too.
            val stay = if (keepProfiles) runCatching { stayLocal() }.getOrDefault(emptyList()) else emptyList()
            secrets.remove(LINK_KEY)
            File(dir, PROFILES_FILE).delete()
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

        /** The host's own folder inside a chosen folder that holds other things. */
        const val HOST_FOLDER = "Fuse Sync Host"

        /** Where saves that never reached a forgotten host are kept, inside Fuse Sync's folder. */
        const val KEPT_DIR = "kept"

        /** The longest a launch waits for the host to settle game ids. */
        const val RESOLVE_WAIT_MS = 3_000L
        private const val RESOLVE_CHUNK = 1_000

        /** How often a device waiting to be let in asks whether it has been. */
        const val JOIN_POLL_MS = 1_500L
        private const val PROFILES_FILE = "profiles.json"

        /** This device's own people: its profiles without a host, their order, PINs typed here. */
        const val PEOPLE_FILE = "people.json"
        const val ADOPTED_FILE = "adopted"

        /** A host joined while this device had profiles, until who is who is settled. */
        const val PENDING_LINK_KEY = "sync.link.pending"

        /** Inside the kept folder: saves found in an emulator's folder after their profile was removed. */
        const val REMOVED_DIR = "Removed profiles"

        /** The longest a launch waits on the host before playing with what is here. */
        const val LAUNCH_WAIT_MS = 8_000L

        /** How often a running game's save is looked at. */
        const val LIVE_LOOK_MS = 15_000L
        const val HELLO_EVERY_MS = 10 * 60_000L
        const val FORGET_WAIT_MS = 5_000L
        const val HOST_RELEASE_MS = 1_500L

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

        /** Tries, a moment apart, for a background service holding the host's port to answer. */
        /** The most game folders one play may change and still be learned as one game's. */
        const val LEARN_MAX = 4

        /** Systems whose games keep their own saves (PC games, Android apps): nothing to explain. */
        val OWN_SAVES_PLATFORMS = setOf("win", "steam", "android", "dos")
        val OWN_SAVES_EMULATORS = setOf(
            "steam", "steam-url", "desktop", "shortcut", "open", "script", "winlator", "winlator-cmod", "winlator-frost", "winlator-glibc",
            "winlator-proot", "winnative", "gamehub", "gamehub-lite", "gamehub-lite-local", "gamenative", "bannerlator", "dosbox-staging", "dosbox-x",
        )

        const val ADMIN_TRIES = 4
        const val ADMIN_RETRY_MS = 750L

        /** How long Fuse waits for a freshly started service to answer. */
        const val SERVICE_WAIT_MS = 15_000L
    }
}
