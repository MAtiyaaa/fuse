package io.github.matiyaaa.fuse.sync.syncthing

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.FileSaveEnvironment
import io.github.matiyaaa.fuse.sync.SaveAdapters
import io.github.matiyaaa.fuse.sync.SaveEnvironment
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.sync.SaveSpot
import io.github.matiyaaa.fuse.sync.WithSaveFolders
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Fuse with the person's own Syncthing (see [SyncthingService]). Connects to it on this device,
 * shares the save folders of the emulators in the library under ids every device uses, accepts
 * those folders when another of the person's devices offers them, keeps them in step around each
 * game, and settles the two versions Syncthing keeps when both sides changed a save.
 */
class JvmSyncthingService(
    private val platform: SyncthingPlatform,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    files: SaveEnvironment = FileSaveEnvironment(platform.host),
    /** Games to plan folders from (one per emulator and system in the library), for folders another device offers. */
    var samples: suspend () -> List<SaveQuery> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncthingService {
    private val _state = MutableStateFlow<SyncthingState>(SyncthingState.Off)
    private val _devices = MutableStateFlow<List<SyncthingDevice>>(emptyList())
    private val _pending = MutableStateFlow<List<SyncthingPendingDevice>>(emptyList())
    private val _folders = MutableStateFlow<List<SyncthingFolder>>(emptyList())
    override val state: StateFlow<SyncthingState> = _state.asStateFlow()
    override val devices: StateFlow<List<SyncthingDevice>> = _devices.asStateFlow()
    override val pendingDevices: StateFlow<List<SyncthingPendingDevice>> = _pending.asStateFlow()
    override val folders: StateFlow<List<SyncthingFolder>> = _folders.asStateFlow()
    override val install: SyncthingInstall get() = platform.install

    private val lock = Mutex()
    /** Each folder's last status from Syncthing, with when it was asked. */
    private val statusCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JsonObject>>()
    private var api: SyncthingApi? = null
    private var http: HttpClient? = null
    private var watcher: Job? = null
    private var myId: String? = null

    /** The save folders the person chose (Settings, Save folders), as last read. */
    @Volatile private var saveFolders: Map<String, String> = emptyMap()
    private val env: SaveEnvironment = WithSaveFolders(files) { saveFolders }

    private suspend fun readSaveFolders() {
        saveFolders = runCatching { settings.current().sync.saveFolders }.getOrDefault(saveFolders)
    }

    init {
        scope.launch {
            if (settings.current().syncthing.enabled) find()
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(syncthing = it.syncthing.copy(enabled = enabled)) }
        if (enabled) find() else stop(SyncthingState.Off)
    }

    override suspend fun find(): SyncthingState = withContext(Dispatchers.IO) {
        _state.value = SyncthingState.Looking
        val saved = settings.current().syncthing.address.takeIf { it.isNotBlank() }
        val key = secrets.get(KEY)
        // A computer's own Syncthing says where it answers and what its key is.
        val local = if (platform.host != "ANDROID") SyncthingConfig.read(platform.host, env.home) else null
        val addresses = listOfNotNull(saved, local?.address) + DEFAULT_ADDRESSES
        for (address in addresses.distinct()) {
            val probe = SyncthingApi(SyncthingApi.client(address), SyncthingApi.withScheme(address), null)
            if (!probe.healthy()) continue
            val tryKeys = listOfNotNull(key, local?.apiKey).distinct()
            for (k in tryKeys) {
                val r = connect(address, k)
                if (r.getOrNull() is SyncthingState.Connected) return@withContext r.getOrThrow()
            }
            return@withContext SyncthingState.NeedsKey(SyncthingApi.withScheme(address)).also { _state.value = it }
        }
        SyncthingState.NotFound(installed = platform.installedApp() != null).also { _state.value = it }
    }

    override suspend fun connect(address: String, apiKey: String): Result<SyncthingState> = withContext(Dispatchers.IO) {
        runCatching {
            val base = SyncthingApi.withScheme(address)
            val client = SyncthingApi.client(base)
            val candidate = SyncthingApi(client, base, apiKey.trim())
            val status = try {
                candidate.status()
            } catch (e: SyncthingException) {
                client.close()
                if (e.kind == SyncthingException.Kind.KEY) return@runCatching SyncthingState.NeedsKey(base, refused = true).also { _state.value = it }
                throw e
            }
            val id = status["myID"]?.jsonPrimitive?.contentOrNull ?: throw SyncthingException("That isn't Syncthing answering.", SyncthingException.Kind.REFUSED)
            val version = runCatching { candidate.version()["version"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: ""
            val name = runCatching { candidate.devices().firstOrNull { it.str("deviceID") == id }?.str("name") }.getOrNull().orEmpty()
            secrets.put(KEY, apiKey.trim())
            settings.update { it.copy(syncthing = it.syncthing.copy(enabled = true, address = base)) }
            lock.withLock {
                http?.close()
                http = client
                api = candidate
                myId = id
            }
            val connected = SyncthingState.Connected(base, version.removePrefix("v"), id, name)
            _state.value = connected
            watch()
            connected
        }
    }

    override fun startApp(): Boolean = platform.startApp()

    override fun useLibrary(samples: suspend () -> List<SaveQuery>) {
        this.samples = samples
    }

    // Devices ---------------------------------------------------------------------------------------

    override suspend fun addDevice(id: String, name: String): Result<Unit> = call { a ->
        val deviceId = SyncthingService.normaliseDeviceId(id) ?: throw SyncthingException("That isn't a Syncthing device ID: it is eight groups of seven letters and numbers.", SyncthingException.Kind.REFUSED)
        if (deviceId == myId) throw SyncthingException("That is this device's own ID. Add it on your other device instead.", SyncthingException.Kind.REFUSED)
        // A device already in Syncthing keeps its own settings (addresses, name, everything): only Fuse's folders are added to it.
        if (a.devices().none { it.str("deviceID") == deviceId }) {
            a.putDevice(
                JsonObject(mapOf("deviceID" to JsonPrimitive(deviceId), "name" to JsonPrimitive(name.ifBlank { deviceId.take(7) }), "addresses" to JsonArray(listOf(JsonPrimitive("dynamic"))))),
                deviceId,
            )
        }
        // Fuse's folders go to it too.
        for (f in a.folders().filter { it.str("id").orEmpty().startsWith(SyncthingService.PREFIX) }) {
            val devices = (f["devices"] as? JsonArray).orEmpty()
            if (devices.any { (it as? JsonObject)?.str("deviceID") == deviceId }) continue
            a.putFolder(JsonObject(f + ("devices" to JsonArray(devices + JsonObject(mapOf("deviceID" to JsonPrimitive(deviceId)))))), f.str("id")!!)
        }
        refreshNow(a)
    }

    override suspend fun removeDevice(id: String): Result<Unit> = call { a ->
        val folders = a.folders()
        fun sharesWith(f: JsonObject) = (f["devices"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.str("deviceID") == id }
        // Shared with it by hand too (photos, documents): it stays in Syncthing and only leaves Fuse's folders.
        if (folders.any { f -> !f.str("id").orEmpty().startsWith(SyncthingService.PREFIX) && sharesWith(f) }) {
            for (f in folders.filter { it.str("id").orEmpty().startsWith(SyncthingService.PREFIX) && sharesWith(it) }) {
                val devices = (f["devices"] as? JsonArray).orEmpty().filterNot { (it as? JsonObject)?.str("deviceID") == id }
                a.putFolder(JsonObject(f + ("devices" to JsonArray(devices))), f.str("id")!!)
            }
        } else {
            a.deleteDevice(id)
        }
        refreshNow(a)
    }

    // Folders ---------------------------------------------------------------------------------------

    override suspend fun plan(samples: List<SaveQuery>): List<SyncthingPlanFolder> = withContext(Dispatchers.IO) {
        readSaveFolders()
        data class Found(val emulator: String, val kind: SaveKind, val dir: String?, val blocked: String?)
        val found = mutableListOf<Found>()
        for (q in samples) {
            val adapter = SaveAdapters.forEmulator(q.emulatorId) ?: continue
            val emu = SaveAdapters.baseId(q.emulatorId)
            val romDir = File(q.romPath).parentFile?.path?.replace('\\', '/')
            for (spot in runCatching { adapter.locate(q, env) }.getOrDefault(emptyList())) {
                val dir = folderOf(spot)
                val blocked = when {
                    !spot.available -> spot.note ?: "Fuse can't reach this folder here."
                    dir == null -> null
                    romDir != null && (dir == romDir || romDir.startsWith("$dir/")) ->
                        "Kept beside your games. Syncthing would copy the games too, so Fuse leaves it out."
                    SaveAdapters.androidPrivate(dir) -> "In another app's private storage, which Syncthing can't open on Android."
                    else -> null
                }
                if (dir != null || blocked != null) found += Found(emu, spot.kind, dir, blocked)
            }
        }
        val current = runCatching { api?.folders() }.getOrNull().orEmpty()
        found.groupBy { it.emulator to it.kind }.map { (key, list) ->
            val (emu, kind) = key
            val dirs = list.mapNotNull { f -> f.dir.takeIf { f.blocked == null } }
            val dir = commonDir(dirs, env.home)
            val id = "${SyncthingService.PREFIX}${slug(emu)}-${slug(kindWord(kind))}"
            val others = current.filter { it.str("id") != id }.mapNotNull { f -> norm(f.str("path"))?.let { it to f.str("label").orEmpty().ifBlank { f.str("id").orEmpty() } } }
            // Syncthing folders inside one another copy the same files twice: one already shared around it covers it, and one inside it stops it.
            val around = dir?.let { d -> others.firstOrNull { (p, _) -> d == p || d.startsWith("$p/") } }
            val inside = dir?.let { d -> others.firstOrNull { (p, _) -> p.startsWith("$d/") } }
            SyncthingPlanFolder(
                id = id,
                label = "Fuse: ${emulatorName(emu)} ${kindWord(kind)}",
                path = dir ?: list.firstNotNullOfOrNull { it.dir }.orEmpty(),
                emulator = emulatorName(emu),
                kind = kind,
                shared = current.any { it.str("id") == id } || around != null,
                blocked = when {
                    dir == null -> list.firstNotNullOfOrNull { it.blocked } ?: "Fuse couldn't find this folder."
                    around == null && inside != null -> "Holds \"${inside.second}\", which Syncthing already shares. Sharing both would copy those files twice."
                    else -> null
                },
            )
        }.sortedWith(compareBy({ it.blocked != null }, { it.emulator }, { it.kind.ordinal }))
    }

    override suspend fun planLibrary(): List<SyncthingPlanFolder> = plan(runCatching { samples() }.getOrDefault(emptyList()))

    override suspend fun share(folders: List<SyncthingPlanFolder>, keepVersions: Boolean): Result<Int> = call { a ->
        val defaults = a.folderDefaults()
        val me = myId
        val others = a.devices().mapNotNull { it.str("deviceID") }.filter { it != me }
        var added = 0
        for (f in folders.filter { !it.shared && it.blocked == null && it.path.isNotBlank() }) {
            File(f.path).mkdirs()
            val devices = (listOfNotNull(me) + others).map { JsonObject(mapOf("deviceID" to JsonPrimitive(it))) }
            val folder = JsonObject(
                defaults + mapOf(
                    "id" to JsonPrimitive(f.id),
                    "label" to JsonPrimitive(f.label),
                    "path" to JsonPrimitive(f.path),
                    "type" to JsonPrimitive("sendreceive"),
                    "devices" to JsonArray(devices),
                    "fsWatcherEnabled" to JsonPrimitive(true),
                    "versioning" to versioning(keepVersions),
                ),
            )
            a.putFolder(folder, f.id)
            added++
        }
        settings.update { it.copy(syncthing = it.syncthing.copy(keepVersions = keepVersions)) }
        refreshNow(a)
        added
    }

    override suspend fun unshare(folderId: String): Result<Unit> = call { a ->
        a.deleteFolder(folderId)
        refreshNow(a)
    }

    override suspend fun refresh() {
        val a = api ?: return
        runCatching { refreshNow(a) }.onFailure { e -> fail(e) }
    }

    // Around a game ------------------------------------------------------------------------------------

    override suspend fun beforeLaunch(query: SaveQuery): SyncthingGate = withContext(Dispatchers.IO) {
        val s = settings.current().syncthing
        if (!s.enabled) return@withContext SyncthingGate.Go()
        readSaveFolders()
        val spots = spotsOf(query)
        if (spots.isEmpty()) return@withContext SyncthingGate.Go()
        val a = api
        val mine = foldersHolding(spots)
        var note: String? = null
        if (a == null || _state.value !is SyncthingState.Connected) {
            note = "Syncthing isn't running, so this save may not be the newest"
        } else if (mine.isNotEmpty() && s.waitBeforePlaying) {
            // Brought up to date: looked over, then given a moment while another device is there.
            mine.forEach { f -> runCatching { a.scan(f.id) } }
            val someoneThere = _devices.value.any { d -> !d.self && d.connected && mine.any { d.id in it.devices } }
            if (someoneThere) {
                val done = withTimeoutOrNull(WAIT_MS) {
                    while (isActive) {
                        val busy = mine.any { f ->
                            val st = runCatching { a.folderStatus(f.id) }.getOrNull()
                            st == null || st.long("needBytes") > 0 || st.str("state") !in setOf("idle", null)
                        }
                        if (!busy) break
                        delay(POLL_MS)
                    }
                    true
                }
                if (done == null) note = "Syncthing is still bringing in changes"
            }
        }
        val conflicts = conflictsIn(spots)
        if (conflicts.isNotEmpty()) SyncthingGate.Conflict(conflicts) else SyncthingGate.Go(note)
    }

    override suspend fun afterExit(query: SaveQuery) {
        val a = api ?: return
        val spots = withContext(Dispatchers.IO) { spotsOf(query) }
        for (f in foldersHolding(spots)) runCatching { a.scan(f.id) }
    }

    override suspend fun resolve(conflict: SyncthingConflict, keepThis: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val original = File(conflict.path)
            val other = File(conflict.conflictPath)
            val folder = _folders.value.firstOrNull { conflict.path.startsWith(it.path.trimEnd('/') + "/") }
            // The version not kept goes where Syncthing keeps old versions, so it can still be had back.
            val keep = File(folder?.path ?: original.parent, ".stversions").apply { mkdirs() }
            val stamp = "~fuse-${clock()}"
            if (keepThis) {
                other.renameTo(File(keep, other.name + stamp)) || error("Couldn't move the other version aside.")
            } else {
                val aside = File(keep, original.name + stamp)
                if (original.exists() && !original.renameTo(aside)) error("Couldn't keep this device's version aside.")
                if (!other.renameTo(original)) {
                    // Nothing changes when the other can't go in: this device's goes back where it was.
                    if (aside.exists()) aside.renameTo(original)
                    error("Couldn't put the other version in place. Both are as they were.")
                }
            }
            folder?.let { f -> api?.let { a -> runCatching { a.scan(f.id) } } }
            Unit
        }
    }

    override suspend fun disconnect() {
        secrets.remove(KEY)
        settings.update { it.copy(syncthing = it.syncthing.copy(enabled = false, address = "")) }
        stop(SyncthingState.Off)
    }

    // Plumbing -----------------------------------------------------------------------------------------

    private suspend fun <T> call(block: suspend (SyncthingApi) -> T): Result<T> = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext Result.failure(SyncthingException("Connect to Syncthing first.", SyncthingException.Kind.UNREACHABLE))
        runCatching { block(a) }.onFailure { fail(it) }
    }

    private fun fail(e: Throwable) {
        val current = _state.value
        if (e is SyncthingException && e.kind == SyncthingException.Kind.UNREACHABLE && current is SyncthingState.Connected) {
            _state.value = SyncthingState.Unreachable(current.address, e.message.orEmpty())
        }
    }

    /** Every so often while connected: devices, folders, and folders other devices offer. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val a = api ?: break
                val ok = runCatching { refreshNow(a); acceptOffered(a) }.isSuccess
                val st = _state.value
                if (!ok && st is SyncthingState.Connected) _state.value = SyncthingState.Unreachable(st.address, "Syncthing stopped answering")
                if (ok && st is SyncthingState.Unreachable) runCatching { connect(st.address, secrets.get(KEY) ?: "") }
                delay(if (ok) WATCH_MS else RETRY_MS)
            }
        }
    }

    private suspend fun refreshNow(a: SyncthingApi) {
        val me = myId
        val connections = runCatching { a.connections()["connections"] as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        _devices.value = a.devices().mapNotNull { d ->
            val id = d.str("deviceID") ?: return@mapNotNull null
            val c = connections[id] as? JsonObject
            SyncthingDevice(
                id = id,
                name = d.str("name").orEmpty().ifBlank { id.take(7) },
                connected = c?.bool("connected") == true,
                address = c?.str("address")?.takeIf { it.isNotBlank() },
                paused = d.bool("paused") == true,
                self = id == me,
            )
        }.sortedWith(compareBy({ !it.self }, { !it.connected }, { it.name.lowercase() }))
        _pending.value = a.pendingDevices().mapNotNull { (id, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            SyncthingPendingDevice(id, o.str("name").orEmpty().ifBlank { id.take(7) }, o.str("address"))
        }
        val now = clock()
        val configured = a.folders()
        statusCache.keys.retainAll(configured.mapNotNull { it.str("id") }.toSet())
        _folders.value = configured.map { f ->
            val id = f.str("id").orEmpty()
            // Syncthing calls a folder's status expensive: Fuse's own folders are asked every round,
            // the person's other folders only now and then (they are shown, not acted on).
            val cached = statusCache[id]
            val st = if (cached != null && !id.startsWith(SyncthingService.PREFIX) && now - cached.first < OTHER_STATUS_MS) {
                cached.second
            } else {
                runCatching { a.folderStatus(id) }.getOrNull()?.also { statusCache[id] = now to it } ?: cached?.second
            }
            SyncthingFolder(
                id = id,
                label = f.str("label").orEmpty().ifBlank { id },
                path = norm(f.str("path")).orEmpty(),
                state = st?.str("state") ?: "unknown",
                needBytes = st?.long("needBytes") ?: 0,
                globalBytes = st?.long("globalBytes") ?: 0,
                devices = (f["devices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("deviceID") }.filter { it != me },
                keepsVersions = ((f["versioning"] as? JsonObject)?.str("type")).orEmpty().isNotBlank(),
                error = st?.str("error")?.ifBlank { null },
                paused = f.bool("paused") == true,
            )
        }.sortedWith(compareBy({ !it.isFuses }, { it.label.lowercase() }))
    }

    /**
     * Fuse's folders that one of the person's devices offers: each is accepted at this device's
     * own folder for it (from the library's emulators), shared back with the device that offered it.
     */
    private suspend fun acceptOffered(a: SyncthingApi) {
        val offered = a.pendingFolders().filterKeys { it.startsWith(SyncthingService.PREFIX) }
        if (offered.isEmpty()) return
        val known = a.devices().mapNotNull { it.str("deviceID") }.toSet()
        var plan: List<SyncthingPlanFolder>? = null
        val existing = a.folders()
        for ((id, v) in offered) {
            val from = ((v as? JsonObject)?.get("offeredBy") as? JsonObject)?.keys.orEmpty().filter { it in known }
            if (from.isEmpty()) continue
            val have = existing.firstOrNull { it.str("id") == id }
            if (have != null) {
                // Already here: the offering device just joins it.
                val devices = (have["devices"] as? JsonArray).orEmpty()
                val missing = from.filter { d -> devices.none { (it as? JsonObject)?.str("deviceID") == d } }
                if (missing.isNotEmpty()) a.putFolder(JsonObject(have + ("devices" to JsonArray(devices + missing.map { JsonObject(mapOf("deviceID" to JsonPrimitive(it))) }))), id)
                continue
            }
            val here = plan ?: plan(samples()).also { plan = it }
            val local = here.firstOrNull { it.id == id && it.blocked == null && it.path.isNotBlank() } ?: continue
            File(local.path).mkdirs()
            val devices = (listOfNotNull(myId) + from).map { JsonObject(mapOf("deviceID" to JsonPrimitive(it))) }
            a.putFolder(
                JsonObject(
                    a.folderDefaults() + mapOf(
                        "id" to JsonPrimitive(id), "label" to JsonPrimitive(local.label), "path" to JsonPrimitive(local.path),
                        "type" to JsonPrimitive("sendreceive"), "devices" to JsonArray(devices), "fsWatcherEnabled" to JsonPrimitive(true),
                        "versioning" to versioning(settings.current().syncthing.keepVersions),
                    ),
                ),
                id,
            )
        }
    }

    private fun spotsOf(q: SaveQuery): List<SaveSpot> =
        SaveAdapters.forEmulator(q.emulatorId)?.let { a -> runCatching { a.locate(q, env) }.getOrNull() }.orEmpty().filter { it.available }

    /** Fuse's shared folders (or any shared folder) that hold [spots]. */
    private fun foldersHolding(spots: List<SaveSpot>): List<SyncthingFolder> {
        val paths = spots.flatMap { s -> s.files.map { it.path } + listOfNotNull(s.root) }.map { it.replace('\\', '/') }
        return _folders.value.filter { f -> f.path.isNotBlank() && paths.any { p -> p == f.path || p.startsWith(f.path.trimEnd('/') + "/") } }
    }

    /** Two versions Syncthing kept of any of the game's save files. */
    private fun conflictsIn(spots: List<SaveSpot>): List<SyncthingConflict> {
        val out = mutableListOf<SyncthingConflict>()
        val shortNames = _devices.value.associate { it.id.take(7) to it.name }
        fun look(dir: File, names: Set<String>?) {
            for (f in dir.listFiles().orEmpty()) {
                val (original, short) = SyncthingService.conflictOf(f.name) ?: continue
                if (names != null && original !in names) continue
                val target = File(dir, original)
                out += SyncthingConflict(
                    path = target.path.replace('\\', '/'),
                    conflictPath = f.path.replace('\\', '/'),
                    name = original,
                    device = short?.let { shortNames[it] },
                    thisModified = target.lastModified(),
                    otherModified = f.lastModified(),
                )
            }
        }
        for (s in spots) {
            s.files.groupBy { File(it.path).parentFile }.forEach { (dir, files) ->
                if (dir != null) look(dir, files.map { File(it.path).name }.toSet())
            }
            s.root?.let { root ->
                // A save made of folders: each of the game's folders, looked through.
                for (folder in s.folders) File(root, folder).walkTopDown().maxDepth(3).filter { it.isDirectory }.forEach { look(it, null) }
            }
        }
        return out.distinctBy { it.conflictPath }
    }

    private suspend fun stop(state: SyncthingState) {
        watcher?.cancel()
        watcher = null
        lock.withLock {
            http?.close()
            http = null
            api = null
        }
        _devices.value = emptyList()
        _pending.value = emptyList()
        _folders.value = emptyList()
        _state.value = state
    }

    private fun versioning(keep: Boolean): JsonElement = if (keep) {
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("staggered"),
                // A month of versions, thinned out as they age.
                "params" to JsonObject(mapOf("maxAge" to JsonPrimitive("2592000"), "cleanInterval" to JsonPrimitive("3600"))),
            ),
        )
    } else {
        JsonObject(mapOf("type" to JsonPrimitive(""), "params" to JsonObject(emptyMap())))
    }

    private fun folderOf(spot: SaveSpot): String? =
        (spot.root ?: commonDir(spot.files.mapNotNull { File(it.path).parentFile?.path })).let(::norm)

    companion object {
        const val KEY = "syncthing.apikey"
        private val DEFAULT_ADDRESSES = listOf("http://127.0.0.1:8384", "https://127.0.0.1:8384")
        private const val WAIT_MS = 6_000L
        private const val POLL_MS = 300L
        private const val WATCH_MS = 20_000L
        private const val RETRY_MS = 10_000L
        private const val OTHER_STATUS_MS = 2 * 60_000L

        private fun norm(path: String?): String? = path?.replace('\\', '/')?.trimEnd('/')?.ifEmpty { null }

        private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        private fun kindWord(kind: SaveKind) = when (kind) {
            SaveKind.SAVE -> "saves"
            SaveKind.STATE -> "states"
            SaveKind.MEMORY_CARD -> "memory cards"
        }

        /**
         * The deepest folder holding all of [dirs], when it is safe to share; otherwise the folder
         * holding most of them. A shared parent is never the home folder or above it, nor a folder
         * every program keeps things in (`.config`, `.local/share`, `AppData`, `Documents`...).
         */
        internal fun commonDir(dirs: List<String>, home: String? = null): String? {
            val all = dirs.mapNotNull { norm(it) }
            val clean = all.distinct()
            if (clean.size <= 1) return clean.firstOrNull()
            val parts = clean.map { it.split('/') }
            val shared = parts.first().indices.takeWhile { i -> parts.all { it.size > i && it[i] == parts.first()[i] } }.size
            val prefix = parts.first().take(shared).joinToString("/")
            val mostUsed = all.groupingBy { it }.eachCount().maxBy { it.value }.key
            return prefix.takeIf { shared >= 4 && !tooBroad(it, home) } ?: mostUsed
        }

        /** Folders far too broad to share for saves: the home folder and above, and the ones every program uses. */
        internal fun tooBroad(path: String, home: String?): Boolean {
            val p = path.trimEnd('/').lowercase()
            val h = home?.let { norm(it) }?.lowercase()
            if (h != null && (p == h || h.startsWith("$p/"))) return true
            return p.substringAfterLast('/') in BROAD
        }

        private val BROAD = setOf(
            ".config", ".local", "share", "state", ".var", "app", "appdata", "roaming", "local", "locallow", "documents", "my documents",
            "library", "application support", "containers", "android", "data", "media", "obb", "users", "home", "emulated", "storage",
            "0", "sdcard", "games", "roms", "downloads", "desktop", "program files", "program files (x86)", "programdata",
        )

        internal fun emulatorName(base: String): String = when (base) {
            "retroarch" -> "RetroArch"
            "lemuroid" -> "Lemuroid"
            "duckstation" -> "DuckStation"
            "pcsx2" -> "PCSX2"
            "aethersx2", "nethersx2" -> "NetherSX2"
            "ppsspp" -> "PPSSPP"
            "dolphin" -> "Dolphin"
            "melonds" -> "melonDS"
            "mgba" -> "mGBA"
            "rpcs3" -> "RPCS3"
            "vita3k" -> "Vita3K"
            "shadps4" -> "shadPS4"
            "flycast" -> "Flycast"
            else -> base.replaceFirstChar { it.uppercase() }
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
