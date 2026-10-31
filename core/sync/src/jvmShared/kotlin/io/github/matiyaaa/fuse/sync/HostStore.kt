package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** A profile as the host keeps it: with its PIN's hash (never the PIN) and its revision. */
@Serializable
internal data class ProfileRecord(
    val id: String,
    val name: String,
    val avatar: String,
    val createdAt: Long,
    val pinHash: String? = null,
    /** Grows every time the PIN changes: tickets from before stop working. */
    val pinEpoch: Int = 0,
    val deleted: Boolean = false,
    /** The host computer's own (Admin), hidden from every other device. */
    val hostOnly: Boolean = false,
)

/** A map of sign-ins, by key ("romm", "jellyfin:<profile>"). */
internal val SignInsSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())

/** The Hub's sign-ins, kept with the host but never in a backup of it (a backup signs nobody in). */
internal const val HUB_SESSIONS_FILE = "hub-sessions.json"
private val HubSessionsSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Long.serializer())

/**
 * A key the whole household uses (a scraper's API key, RetroAchievements' username and key):
 * "key:" and the name Fuse keeps it under on each device. Any trusted device may share one.
 */
internal val HOUSEHOLD_KEY = Regex("^key:[A-Za-z0-9._-]{1,64}$")

/** A device as the host keeps it, with the secret its calls are signed with. */
@Serializable
internal data class DeviceRecord(
    val id: String,
    val name: String,
    val platform: String,
    val secret: String,
    val pairedAt: Long,
    val lastSeen: Long = 0,
    val lastSync: Long = 0,
    val connection: String = "",
    val profile: String? = null,
    val revoked: Boolean = false,
    /** The Fuse on the host computer itself: the one device that sees the host's own profile. */
    val owner: Boolean = false,
    /** Profiles this device has opened, with the PIN epoch they were opened at. */
    val opened: Map<String, Int> = emptyMap(),
    /** The Fuse it last called from, and that Fuse's protocol (0 before 0.4.2). */
    val fuseVersion: String = "",
    val protocol: Int = 0,
    /**
     * Signed in as the host with the host account: it manages the household from its own link, which
     * never runs out, so updating or restarting the host never signs it out. Only unlinking it does.
     */
    val manager: Boolean = false,
)

@Serializable
internal data class HostIdentity(val hostId: String, val name: String, val createdAt: Long)

/** The host's account: a username, and the password only as [SyncCrypto.hashSecret] keeps it. */
@Serializable
internal data class HostAccount(val username: String, val secret: String)

/** The host's address from outside home; [learned] when it came from how a device reached it, not typed. */
@Serializable
internal data class OutsideAddress(val address: String = "", val learned: Boolean = false)

/**
 * Everything a Fuse Sync Host keeps, on its own disk, under [dir]: who it is, its devices and
 * profiles, each profile's records and the history of every save, the journal of what changed,
 * and the files themselves ([ContentStore]). Each list is written whole and atomically, and the
 * journal and histories are append-only lines, so a crash or a power cut loses at most the call
 * that was being answered, never what was already kept. One lock keeps calls from different
 * devices in order; the host is the one place that decides what is newest.
 */
class HostStore(
    val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    hostName: String = "Fuse Sync Host",
    /** The free space on [dir]'s drive, as the Hub shows it. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    val content = ContentStore(File(dir, "objects"))

    private val identityFile = File(dir, "host.json")
    private val devicesFile = File(dir, "devices.json")
    private val profilesFile = File(dir, "profiles.json")
    private val sharedFile = File(dir, "shared-games.json")
    private val aliasFile = File(dir, "game-aliases.json")
    private val accountFile = File(dir, "account.json")
    /**
     * The Hub's sign-ins from away, each kept as its token's SHA-256 (never the token) with when it
     * ends: kept in memory alone, an update or a restart of the host signed everyone out.
     */
    private val hubSessionsFile = File(dir, HUB_SESSIONS_FILE)
    private val hubSessions = HashMap<String, Long>()
    private val outsideFile = File(dir, "outside.json")
    /** The household's RomM and Jellyfin: addresses, and sign-ins sealed with [servicesKey]. */
    private val servicesFile = File(dir, "services.json")
    /** The host's own key for the sign-ins it keeps: never sent, never leaves this folder. */
    private val servicesKeyFile = File(dir, "services.key")

    @Volatile private var account: HostAccount? = null
    @Volatile private var outside = OutsideAddress()

    /** Every id a game has been known by, to the one id the household's saves and records use. */
    private val aliases = HashMap<String, String>()
    private val journalFile = File(dir, "journal.jsonl")

    /**
     * The token for this host's own management calls (codes for new devices, renaming and
     * unlinking devices), from this computer only: a file only this user can read, so Fuse can run
     * the host it started as a background service.
     */
    val adminToken: String by lazy {
        val f = File(dir, "admin.token")
        if (!f.isFile) {
            writeAtomically(f, SyncCrypto.token(32).toByteArray())
            runCatching {
                f.setReadable(false, false); f.setReadable(true, true)
                f.setWritable(false, false); f.setWritable(true, true)
            }
        }
        f.readText().trim()
    }

    @Volatile internal var identity: HostIdentity
        private set
    private val devices = LinkedHashMap<String, DeviceRecord>()
    private val profiles = LinkedHashMap<String, ProfileRecord>()
    private val metas = HashMap<String, ProfileMeta>()
    private val revisions = HashMap<String, MutableList<SaveRevision>>()
    private val shared = LinkedHashSet<String>()
    private val journal = ArrayList<JournalEvent>()
    private val appliedFile = File(dir, "applied.json")

    /** What each device reported in place for each save: device to `profile|game|kind` to note. */
    private val applied = HashMap<String, HashMap<String, AppliedNote>>()
    private var seq = 0L
    val startedAt: Long = clock()

    init {
        dir.mkdirs()
        identity = if (identityFile.isFile) {
            json.decodeFromString(HostIdentity.serializer(), identityFile.readText())
        } else {
            HostIdentity(SyncCrypto.token(12), hostName, clock()).also { writeAtomically(identityFile, json.encodeToString(HostIdentity.serializer(), it).toByteArray()) }
        }
        load()
    }

    /** Reads everything kept in [dir] (at start, and after a backup takes a new copy of the household). */
    private fun load() {
        devices.clear(); profiles.clear(); metas.clear(); revisions.clear(); shared.clear(); journal.clear(); applied.clear(); aliases.clear()
        account = null
        outside = OutsideAddress()
        seq = 0
        if (devicesFile.isFile) json.decodeFromString(ListSerializer(DeviceRecord.serializer()), devicesFile.readText()).forEach { devices[it.id] = it }
        if (profilesFile.isFile) json.decodeFromString(ListSerializer(ProfileRecord.serializer()), profilesFile.readText()).forEach { profiles[it.id] = it }
        for (p in profiles.values) {
            val metaFile = File(profileDir(p.id), "meta.json")
            if (metaFile.isFile) metas[p.id] = json.decodeFromString(ProfileMeta.serializer(), metaFile.readText())
            val revFile = File(profileDir(p.id), "revisions.jsonl")
            if (revFile.isFile) {
                // A line cut short by a crash is skipped, never fatal.
                revisions[p.id] = revFile.readLines().mapNotNull { line -> runCatching { json.decodeFromString(SaveRevision.serializer(), line) }.getOrNull() }.toMutableList()
            }
        }
        // The saves everyone plays together, and which games they are.
        File(profileDir(SHARED_SAVES), "revisions.jsonl").takeIf { it.isFile }?.let { f ->
            revisions[SHARED_SAVES] = f.readLines().mapNotNull { line -> runCatching { json.decodeFromString(SaveRevision.serializer(), line) }.getOrNull() }.toMutableList()
        }
        if (accountFile.isFile) account = runCatching { json.decodeFromString(HostAccount.serializer(), accountFile.readText()) }.getOrNull()
        if (hubSessionsFile.isFile) runCatching { json.decodeFromString(HubSessionsSerializer, hubSessionsFile.readText()) }.getOrNull()?.let(hubSessions::putAll)
        if (outsideFile.isFile) outside = runCatching { json.decodeFromString(OutsideAddress.serializer(), outsideFile.readText()) }.getOrDefault(OutsideAddress())
        if (aliasFile.isFile) runCatching { json.decodeFromString(GameAliasesSerializer, aliasFile.readText()) }.getOrNull()?.let(aliases::putAll)
        if (sharedFile.isFile) runCatching { json.decodeFromString(SharedGames.serializer(), sharedFile.readText()).games }.getOrNull()?.let(shared::addAll)
        if (appliedFile.isFile) runCatching {
            json.decodeFromString(AppliedFileSerializer, appliedFile.readText()).forEach { (d, m) -> applied[d] = HashMap(m) }
        }
        if (journalFile.isFile) {
            journalFile.readLines().mapNotNullTo(journal) { line -> runCatching { json.decodeFromString(JournalEvent.serializer(), line) }.getOrNull() }
            seq = journal.lastOrNull()?.seq ?: 0
            trimJournal()
        }
        if (repairIdentityAliases()) writeAtomically(aliasFile, json.encodeToString(GameAliasesSerializer, aliases).toByteArray())
    }

    private fun profileDir(id: String) = File(File(dir, "profiles"), id)

    // ---------------------------------------------------------------- backups

    /** This computer's place among the household's hosts (see [HostLeadership]). */
    val leadership: HostLeadership by lazy { HostLeadership(dir, clock) { identity.name } }

    /** Whether a path in [dir] (with `/`) is part of the household a backup copies. */
    private fun replicated(path: String): Boolean {
        if (path in NOT_COPIED || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        val name = path.substringAfterLast('/')
        if (name.startsWith(".") && name.endsWith(".tmp")) return false
        return path.substringBefore('/') in COPIED
    }

    /** Everything a backup needs but the saves' files, by path: a consistent copy, taken under the lock. */
    fun replicaFiles(): Map<String, ByteArray> = synchronized(lock) {
        val out = java.util.TreeMap<String, ByteArray>()
        for (f in dir.walkTopDown().onEnter { it == dir || replicated(it.relativeTo(dir).path.replace('\\', '/')) }) {
            if (!f.isFile) continue
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            if (replicated(rel)) out[rel] = f.readBytes()
        }
        out
    }

    /** The files (by hash) the household's saves use: what a backup fetches beside [replicaFiles]. */
    fun referencedObjects(): Set<String> = synchronized(lock) { revisions.values.flatten().flatMap { it.manifest.files }.map { it.hash }.toSet() }

    /**
     * Takes [files] (a copy of the household from its host) in place of this backup's own, and reads
     * them back. Never on the host itself. Files of the household no longer in the copy go.
     */
    fun applyReplica(files: Map<String, ByteArray>) = synchronized(lock) {
        check(!leadership.isLeader) { "The host never takes a copy over its own" }
        require(files.keys.all(::replicated)) { "Not a household file" }
        val keep = files.keys.toSet()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            if (replicated(rel) && rel !in keep && rel != HostLeadership.HOSTS_FILE) f.delete()
        }
        for ((path, bytes) in files) {
            if (path == HostLeadership.HOSTS_FILE) continue
            val f = File(dir, path)
            f.parentFile?.mkdirs()
            writeAtomically(f, bytes)
        }
        files[HostLeadership.HOSTS_FILE]?.let { leadership.adoptCopied(it) }
        if (identityFile.isFile) identity = json.decodeFromString(HostIdentity.serializer(), identityFile.readText())
        load()
    }

    /**
     * Puts the household back as [files] (a household backup) kept it, on the host itself: the
     * household's hosts and their secret stay as they are now (a backup from an older generation
     * would otherwise make this host give way), and the journal goes on from where it is, so every
     * device looks again and catches up.
     */
    fun restoreFrom(files: Map<String, ByteArray>) = synchronized(lock) {
        check(leadership.isLeader) { "Only the household's host is restored" }
        require(files.keys.all(::replicated)) { "Not a household file" }
        val before = seq
        val keepAsIs = setOf(HostLeadership.HOSTS_FILE, HostLeadership.SECRET_FILE)
        val incoming = files.filterKeys { it !in keepAsIs }
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            if (replicated(rel) && rel !in incoming && rel !in keepAsIs) f.delete()
        }
        for ((path, bytes) in incoming) {
            val f = File(dir, path)
            f.parentFile?.mkdirs()
            writeAtomically(f, bytes)
        }
        if (identityFile.isFile) identity = json.decodeFromString(HostIdentity.serializer(), identityFile.readText())
        load()
        if (seq < before) seq = before
        event(JournalEvent.PROFILE)
        event(JournalEvent.DEVICE)
    }

    /** What [reconcile] took from an older host. */
    data class Reconciled(val devices: Int, val profiles: Int, val revisions: Int, val conflicts: Int)

    /**
     * Takes from [older] (a copy of a host that went on taking changes after this computer became
     * the host) what this one hasn't: devices paired and profiles made there, records merged, and
     * every save it took, pushed as from the device that made it, so one that doesn't follow this
     * host's newest is kept as a conflict copy, never lost and never put over the newest. The
     * saves' files must be here first.
     */
    fun reconcile(older: HostStore): Reconciled = synchronized(lock) {
        var newDevices = 0
        var newProfiles = 0
        var revs = 0
        var conflicts = 0
        val theirs = synchronized(older.lock) {
            Triple(older.devices.values.toList(), older.profiles.values.toList(), older.metas.toMap()) to (older.revisions.mapValues { it.value.toList() } to (older.shared.toSet() to older.aliases.toMap()))
        }
        val (lists, rest) = theirs
        val (theirDevices, theirProfiles, theirMetas) = lists
        val (theirRevisions, more) = rest
        val (theirShared, theirAliases) = more
        for (d in theirDevices) if (d.id !in devices) { devices[d.id] = d; newDevices++ }
        if (newDevices > 0) saveDevices()
        for (p in theirProfiles) if (p.id !in profiles) { profiles[p.id] = p; newProfiles++ }
        if (newProfiles > 0) saveProfiles()
        for ((p, meta) in theirMetas) if (p in profiles) {
            val before = metas[p] ?: ProfileMeta()
            val merged = before.merge(meta)
            if (merged != before) {
                metas[p] = merged
                writeAtomically(File(profileDir(p), "meta.json"), json.encodeToString(ProfileMeta.serializer(), merged).toByteArray())
                event(JournalEvent.META, profile = p, device = "reconcile")
            }
        }
        for ((p, list) in theirRevisions) {
            if (p != SHARED_SAVES && p !in profiles) continue
            val mine = revisions[p].orEmpty().map { it.id }.toSet()
            for (r in list.filter { it.id !in mine }.sortedBy { it.at }) {
                if (r.manifest.files.any { !content.has(it.hash) }) continue
                val result = push(p, r.device, r)
                revs++
                if (result.conflict) conflicts++
            }
        }
        if (!shared.containsAll(theirShared)) {
            shared += theirShared
            writeAtomically(sharedFile, json.encodeToString(SharedGames.serializer(), SharedGames(shared.toList())).toByteArray())
        }
        val newAliases = theirAliases.filterKeys { it !in aliases }
        if (newAliases.isNotEmpty()) {
            aliases += newAliases
            writeAtomically(aliasFile, json.encodeToString(GameAliasesSerializer, aliases).toByteArray())
        }
        Reconciled(newDevices, newProfiles, revs, conflicts)
    }

    private fun saveDevices() = writeAtomically(devicesFile, json.encodeToString(ListSerializer(DeviceRecord.serializer()), devices.values.toList()).toByteArray())
    private fun saveProfiles() = writeAtomically(profilesFile, json.encodeToString(ListSerializer(ProfileRecord.serializer()), profiles.values.toList()).toByteArray())

    private fun append(file: File, line: String) {
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray())
            out.fd.sync()
        }
    }

    /** Notes that a device asked to join, so the devices that may let it in look. */
    internal fun noteJoin(): Unit = synchronized(lock) { event(JournalEvent.JOIN) }

    private fun event(type: String, profile: String? = null, game: String? = null, kind: SaveKind? = null, revision: String? = null, device: String? = null): JournalEvent {
        val e = JournalEvent(++seq, type, profile, game, kind, revision, device, clock())
        journal += e
        append(journalFile, json.encodeToString(JournalEvent.serializer(), e))
        trimJournal()
        return e
    }

    /**
     * Keeps the journal to its newest [JOURNAL_KEEP] entries once it has twice that. The journal only
     * tells devices to look (they always catch up in full), so one that was away longer misses nothing.
     */
    private fun trimJournal() {
        if (journal.size <= JOURNAL_KEEP * 2) return
        val keep = journal.takeLast(JOURNAL_KEEP)
        journal.clear()
        journal.addAll(keep)
        writeAtomically(journalFile, keep.joinToString("") { json.encodeToString(JournalEvent.serializer(), it) + "\n" }.toByteArray())
    }

    // ---------------------------------------------------------------- host

    val name: String get() = identity.name

    fun hello(port: Int, fuseVersion: String): HostHello {
        val l = leadership.current
        return HostHello(
            identity.hostId, identity.name, SyncApi.VERSION, port, fuseVersion, minVersion = SyncApi.MIN_VERSION, outside = outside.address, account = account != null,
            features = listOf(SyncApi.FEATURE_HOUSEHOLD, SyncApi.FEATURE_BACKUPS),
            node = leadership.nodeId, generation = l.generation, role = leadership.role, hosts = l.hosts,
        )
    }

    /** Adds [backup] to the household's hosts: it copies everything the host keeps from now on. */
    fun addBackup(backup: HostNode): Leadership = synchronized(lock) {
        require(backup.deviceId != null && devices[backup.deviceId]?.revoked == false) { "A backup is one of the household's devices" }
        // The hosts' own secret exists before the first copy goes, so every backup has it.
        leadership.secret
        leadership.addBackup(backup).also { event(JournalEvent.DEVICE, device = backup.deviceId) }
    }

    fun removeBackup(nodeId: String): Leadership = synchronized(lock) {
        leadership.removeBackup(nodeId).also { event(JournalEvent.DEVICE) }
    }

    /** True when [device] is one of the household's backups, so it may copy the household. */
    fun isBackupDevice(device: String): Boolean = leadership.current.hosts.any { it.deviceId == device }

    // ---------------------------------------------------------------- the account and the outside address

    /** The account's username, or null when the host has none. */
    fun accountName(): String? = account?.username

    /** Sets the host's account; [password] null keeps the one it has. */
    fun setAccount(username: String, password: String?) = synchronized(lock) {
        val name = username.trim()
        require(name.length in 1..40) { "Choose a username of up to 40 characters." }
        val secret = when {
            password != null -> {
                require(password.length >= MIN_PASSWORD) { "Choose a password of at least $MIN_PASSWORD characters." }
                SyncCrypto.hashSecret(password, iterations = ACCOUNT_ITERATIONS)
            }
            else -> account?.secret ?: throw IllegalArgumentException("Choose a password.")
        }
        val next = HostAccount(name, secret)
        writeAtomically(accountFile, json.encodeToString(HostAccount.serializer(), next).toByteArray())
        runCatching { accountFile.setReadable(false, false); accountFile.setReadable(true, true) }
        account = next
        // A new password ends every Hub sign-in made with the old one.
        if (password != null) closeHubSessions()
    }

    fun clearAccount() = synchronized(lock) {
        accountFile.delete()
        account = null
        closeHubSessions()
    }

    /** Signs the Hub in for [token] until [until], letting go of sign-ins that ended before [now]. */
    fun openHubSession(token: String, until: Long, now: Long) = synchronized(lock) {
        hubSessions.entries.removeIf { it.value < now }
        hubSessions[hubKey(token)] = until
        saveHubSessions()
    }

    /** True while [token] is a Hub sign-in that hasn't ended at [now]. */
    fun hubSessionOpen(token: String, now: Long): Boolean = synchronized(lock) {
        val key = hubKey(token)
        val until = hubSessions[key] ?: return false
        if (until >= now) return true
        hubSessions.remove(key)
        saveHubSessions()
        false
    }

    /**
     * Carries [token]'s Hub sign-in on to [until] when it is used: a browser that opens the Hub now
     * and then stays signed in for good. Written at most once a day per sign-in.
     */
    fun renewHubSession(token: String, until: Long): Boolean = synchronized(lock) {
        val key = hubKey(token)
        val was = hubSessions[key] ?: return false
        if (until - was < RENEW_EVERY_MS) return false
        hubSessions[key] = until
        saveHubSessions()
        true
    }

    /** Ends [token]'s Hub sign-in (Sign Out). */
    fun closeHubSession(token: String) = synchronized(lock) {
        if (hubSessions.remove(hubKey(token)) != null) saveHubSessions()
    }

    /** Ends every Hub sign-in. */
    fun closeHubSessions() = synchronized(lock) {
        hubSessions.clear()
        hubSessionsFile.delete()
    }

    private fun hubKey(token: String): String = SyncCrypto.sha256(token.toByteArray())

    private fun saveHubSessions() {
        if (hubSessions.isEmpty()) {
            hubSessionsFile.delete()
            return
        }
        writeAtomically(hubSessionsFile, json.encodeToString(HubSessionsSerializer, hubSessions).toByteArray())
        runCatching { hubSessionsFile.setReadable(false, false); hubSessionsFile.setReadable(true, true) }
    }

    /** True for the account's username (any case) and password. */
    fun checkAccount(username: String, password: String): Boolean {
        val a = account ?: return false
        return a.username.equals(username.trim(), ignoreCase = true) && SyncCrypto.verifySecret(password, a.secret)
    }

    /** How the account's password is stretched, for a device joining with it; null without an account. */
    fun accountStretch(): Pair<String, Int>? = account?.let { SyncCrypto.secretParts(it.secret) }?.let { (salt, n, _) -> salt to n }

    /** True when [proof] shows the device knows the account's password, for the join sharing [joinSecret]. */
    fun accountProofOk(username: String, joinSecret: String, proof: String): Boolean {
        val a = account ?: return false
        if (!a.username.equals(username.trim(), ignoreCase = true)) return false
        val key = SyncCrypto.secretParts(a.secret)?.third ?: return false
        return SyncCrypto.constantEquals(SyncCrypto.hmac(key, joinSecret), proof)
    }

    fun outsideAddress(): String = outside.address

    /**
     * Sets the address from outside home. One [learned] from how a device reached the host never
     * replaces one the person typed.
     */
    fun setOutsideAddress(address: String, learned: Boolean = false): Boolean = synchronized(lock) {
        val clean = address.trim().trimEnd('/')
        if (learned && outside.address.isNotEmpty() && !outside.learned) return false
        if (clean == outside.address && learned == outside.learned) return false
        val next = OutsideAddress(clean, learned && clean.isNotEmpty())
        writeAtomically(outsideFile, json.encodeToString(OutsideAddress.serializer(), next).toByteArray())
        outside = next
        true
    }

    fun seq(): Long = synchronized(lock) { seq }

    fun status(port: Int, fuseVersion: String): HostStatus {
        // The files' totals are read outside the lock: devices' calls never wait on the disk for them.
        val bytes = content.totalBytes()
        val objects = content.count()
        val free = freeSpace(dir)
        return synchronized(lock) {
            HostStatus(
                hello = hello(port, fuseVersion),
                profiles = profiles.values.filter { !it.deleted }.map(::infoOf),
                devices = devices.values.map(::deviceInfoOf),
                storageBytes = bytes,
                objectCount = objects,
                revisionCount = revisions.values.sumOf { it.size },
                journalSeq = seq,
                startedAt = startedAt,
                freeBytes = free,
            )
        }
    }

    // ---------------------------------------------------------------- devices

    internal fun addDevice(id: String, name: String, platform: String): DeviceRecord = synchronized(lock) {
        val record = DeviceRecord(id, name.take(64), platform.take(32), SyncCrypto.token(32), clock())
        devices[id] = record
        saveDevices()
        event(JournalEvent.DEVICE, device = id)
        record
    }

    internal fun device(id: String): DeviceRecord? = synchronized(lock) { devices[id] }

    internal fun touchDevice(id: String, connection: String?, synced: Boolean = false) = synchronized(lock) {
        val d = devices[id] ?: return@synchronized
        val now = clock()
        // Written at most once a minute: last seen doesn't need every call on disk.
        val changed = d.copy(lastSeen = now, lastSync = if (synced) now else d.lastSync, connection = connection ?: d.connection)
        devices[id] = changed
        if (synced || now - d.lastSeen > 60_000 || connection != d.connection) saveDevices()
    }

    fun devices(): List<DeviceInfo> = synchronized(lock) { devices.values.map(::deviceInfoOf) }

    fun changeDevice(id: String, change: DeviceChange): DeviceInfo? = synchronized(lock) {
        val d = devices[id] ?: return@synchronized null
        val next = d.copy(name = change.name?.take(64)?.ifBlank { null } ?: d.name, profile = change.profile ?: d.profile)
        devices[id] = next
        saveDevices()
        event(JournalEvent.DEVICE, device = id)
        deviceInfoOf(next)
    }

    /** Unlinks a device: its secret stops working at once. What it synced stays. */
    fun revokeDevice(id: String): Boolean = synchronized(lock) {
        val d = devices[id] ?: return@synchronized false
        devices[id] = d.copy(revoked = true, secret = "", opened = emptyMap(), manager = false)
        saveDevices()
        event(JournalEvent.DEVICE, device = id)
        true
    }

    /** Signs device [id] in (or out) as the host; false for a device that isn't linked. */
    fun setManager(id: String, on: Boolean): Boolean = synchronized(lock) {
        val d = devices[id]?.takeIf { !it.revoked } ?: return@synchronized false
        if (d.manager != on) {
            devices[id] = d.copy(manager = on)
            saveDevices()
            event(JournalEvent.DEVICE, device = id)
        }
        true
    }

    /**
     * A device as the household sees it. The host computer's own Fuse ([DeviceRecord.owner]) is seen
     * now whenever the host answers: the host runs on it, so it is plainly there. Its Fuse may stay
     * closed for days while the host's service keeps running, and the household then called the very
     * computer it was talking to "away, seen 7 days ago" beside its RomM shown online.
     */
    private fun deviceInfoOf(d: DeviceRecord): DeviceInfo {
        val here = d.owner && !d.revoked
        return DeviceInfo(
            d.id, d.name, d.platform, d.profile,
            lastSeen = if (here) maxOf(d.lastSeen, clock()) else d.lastSeen,
            lastSync = d.lastSync,
            connection = if (here) d.connection.ifEmpty { "LOCAL" } else d.connection,
            revoked = d.revoked, fuseVersion = d.fuseVersion, protocol = d.protocol, manager = d.manager,
        )
    }

    /** Notes the Fuse [id] calls from, kept only when it changed (an update, or the first call from 0.4.2). */
    internal fun noteVersion(id: String, fuseVersion: String, protocol: Int) = synchronized(lock) {
        val d = devices[id] ?: return@synchronized
        val v = fuseVersion.take(32)
        if (d.fuseVersion == v && d.protocol == protocol) return@synchronized
        devices[id] = d.copy(fuseVersion = v, protocol = protocol)
        saveDevices()
    }

    // ---------------------------------------------------------------- profiles

    /**
     * The profiles, as [device] may see them: the host's own (Admin) only on the host computer's
     * Fuse. With no device (the Hub, the host's own status) every one.
     */
    fun profiles(device: String? = null): List<ProfileInfo> = synchronized(lock) {
        val owner = device == null || devices[device]?.owner == true
        profiles.values.filter { !it.deleted && (owner || !it.hostOnly) }.map(::infoOf)
    }

    /**
     * Marks [deviceId] as the Fuse on the host computer itself and makes sure the host has its own
     * profile, Admin: the host never needs a person's profile, and no other device sees Admin.
     * Returns Admin. Safe to call again.
     */
    fun adoptOwner(deviceId: String): ProfileInfo = synchronized(lock) {
        val d = devices[deviceId]
        if (d != null && !d.owner) {
            devices[deviceId] = d.copy(owner = true)
            saveDevices()
        }
        val existing = profiles.values.firstOrNull { it.hostOnly && !it.deleted }
        if (existing != null) return@synchronized infoOf(existing)
        val taken = profiles.values.any { !it.deleted && it.name.equals(ADMIN_NAME, ignoreCase = true) }
        val record = ProfileRecord(SyncCrypto.token(9), if (taken) "$ADMIN_NAME (Host)" else ADMIN_NAME, ADMIN_AVATAR, clock(), hostOnly = true)
        profiles[record.id] = record
        saveProfiles()
        event(JournalEvent.PROFILE, profile = record.id)
        infoOf(record)
    }

    private fun infoOf(p: ProfileRecord): ProfileInfo {
        val revs = revisions[p.id].orEmpty()
        val bytes = revs.flatMap { it.manifest.files }.distinctBy { it.hash }.sumOf { it.size }
        val used = devices.values.filter { it.profile == p.id && !it.revoked }.map { it.name }
        return ProfileInfo(p.id, p.name, p.avatar, p.pinHash != null, p.createdAt, bytes, used, hostOnly = p.hostOnly)
    }

    fun createProfile(request: NewProfile): ProfileInfo = synchronized(lock) {
        val name = request.name.trim().take(40)
        require(name.isNotEmpty()) { "A profile needs a name" }
        require(profiles.values.none { !it.deleted && it.name.equals(name, ignoreCase = true) }) { "A profile is already called $name" }
        val id = SyncCrypto.token(9)
        val pinHash = request.pin?.takeIf { it.isNotBlank() }?.let { pinOk(it); SyncCrypto.hashSecret(it) }
            ?: request.pinHash?.let { carried -> carried.takeIf(::hashOk) ?: throw IllegalArgumentException("That PIN couldn't be carried over") }
        val record = ProfileRecord(id, name, request.avatar.take(32), clock(), pinHash = pinHash)
        profiles[id] = record
        saveProfiles()
        event(JournalEvent.PROFILE, profile = id)
        infoOf(record)
    }

    private fun pinOk(pin: String) = require(pin.length in 4..64) { "A PIN or password is 4 to 64 characters" }

    /** A PIN already hashed elsewhere (a profile made without a host): only Fuse's own kind, never one too costly to check. */
    private fun hashOk(hash: String): Boolean {
        val (_, iterations, key) = SyncCrypto.secretParts(hash) ?: return false
        return iterations in MIN_PIN_ITERATIONS..SyncCrypto.PIN_ITERATIONS * 2 && key.size in 16..64
    }

    /** The device that made a profile with a carried PIN may use it (it never saw the PIN typed here). */
    internal fun openFor(device: String, profile: String): Unit = synchronized(lock) {
        val p = profiles[profile]?.takeIf { !it.deleted } ?: return@synchronized
        val d = devices[device] ?: return@synchronized
        devices[device] = d.copy(opened = d.opened + (profile to p.pinEpoch), profile = profile)
        saveDevices()
    }

    /**
     * Puts the profiles in the order of [ids] (any not named keep their place after them), for
     * every device: Who's playing? and every list follow it.
     */
    fun setOrder(ids: List<String>): Unit = synchronized(lock) {
        val first = ids.distinct().mapNotNull { id -> profiles[id]?.let { id to it } }
        if (first.isEmpty()) return@synchronized
        val rest = profiles.entries.filter { it.key !in ids }.map { it.key to it.value }
        profiles.clear()
        (first + rest).forEach { (k, v) -> profiles[k] = v }
        saveProfiles()
        event(JournalEvent.PROFILE)
    }

    fun changeProfile(id: String, change: ProfileChange): ProfileInfo = synchronized(lock) {
        val p = profiles[id]?.takeIf { !it.deleted } ?: throw NoSuchElementException("No such profile")
        var next = p.copy(name = change.name?.trim()?.take(40)?.ifEmpty { null } ?: p.name, avatar = change.avatar?.take(32) ?: p.avatar)
        if (change.pin != null || change.removePin) {
            // Changing or removing protection asks for the current PIN, so a device someone else
            // picked up can't take a profile over.
            if (p.pinHash != null && (change.currentPin == null || !SyncCrypto.verifySecret(change.currentPin, p.pinHash))) throw SecurityException("The current PIN isn't right")
            next = if (change.removePin) next.copy(pinHash = null, pinEpoch = p.pinEpoch + 1) else {
                pinOk(change.pin!!)
                next.copy(pinHash = SyncCrypto.hashSecret(change.pin), pinEpoch = p.pinEpoch + 1)
            }
        }
        profiles[id] = next
        saveProfiles()
        event(JournalEvent.PROFILE, profile = id)
        infoOf(next)
    }

    /**
     * Deletes a profile: it disappears from every device's list. Its records and files stay on the
     * host's disk until it is collected, so a mistake can still be undone from a backup.
     */
    fun deleteProfile(id: String): Boolean = synchronized(lock) {
        val p = profiles[id] ?: return@synchronized false
        profiles[id] = p.copy(deleted = true)
        saveProfiles()
        event(JournalEvent.PROFILE, profile = id)
        true
    }

    private val failures = HashMap<String, Pair<Int, Long>>()

    /**
     * Opens [profile] for [device] with [pin]: true, and from now on the device may use it, until
     * the PIN changes or the device is unlinked. Wrong PINs slow down (a pause that doubles after
     * three wrong tries), per device and profile.
     */
    internal fun unlock(device: String, profile: String, pin: String?): UnlockResult = synchronized(lock) {
        val p = profiles[profile]?.takeIf { !it.deleted } ?: return@synchronized UnlockResult.NoProfile
        val d = devices[device] ?: return@synchronized UnlockResult.NoProfile
        // The host's own profile doesn't exist for any other device.
        if (p.hostOnly && !d.owner) return@synchronized UnlockResult.NoProfile
        val key = "$device/$profile"
        val (count, until) = failures[key] ?: (0 to 0L)
        val now = clock()
        if (now < until) return@synchronized UnlockResult.Wait(until - now)
        if (p.pinHash != null && (pin == null || !SyncCrypto.verifySecret(pin, p.pinHash))) {
            val next = count + 1
            val pause = if (next < 3) 0L else (1_000L shl (next - 3).coerceAtMost(8))
            failures[key] = next to now + pause
            return@synchronized UnlockResult.WrongPin
        }
        failures.remove(key)
        devices[device] = d.copy(opened = d.opened + (profile to p.pinEpoch), profile = profile)
        saveDevices()
        UnlockResult.Opened
    }

    /** Whether [device] may use [profile] now. */
    internal fun mayUse(device: String, profile: String): Boolean = synchronized(lock) {
        // Saves everyone plays together belong to the household: any device it trusts may use them.
        if (profile == SHARED_SAVES) return@synchronized devices[device]?.revoked == false
        val p = profiles[profile]?.takeIf { !it.deleted } ?: return@synchronized false
        val d = devices[device]?.takeIf { !it.revoked } ?: return@synchronized false
        // The host's own profile is the host computer's alone.
        if (p.hostOnly && !d.owner) return@synchronized false
        if (p.pinHash == null) return@synchronized true
        d.opened[profile] == p.pinEpoch
    }

    // ---------------------------------------------------------------- records

    fun meta(profile: String): MetaState = synchronized(lock) { MetaState(metas[profile] ?: ProfileMeta(), seq) }

    /**
     * Merges a device's records into the profile's; returns the merged whole. A Fuse from before
     * 0.4.3.2 sends Fuse for a theme it hasn't got: that is left out ([OlderFuseThemes]).
     */
    fun mergeMeta(profile: String, device: String, incoming: ProfileMeta): MetaState = synchronized(lock) {
        val before = metas[profile] ?: ProfileMeta()
        val merged = before.merge(OlderFuseThemes.keep(before, incoming, devices[device]?.fuseVersion.orEmpty()))
        if (merged != before) {
            metas[profile] = merged
            writeAtomically(File(profileDir(profile), "meta.json"), json.encodeToString(ProfileMeta.serializer(), merged).toByteArray())
            event(JournalEvent.META, profile = profile, device = device)
        }
        MetaState(merged, seq)
    }

    // ---------------------------------------------------------------- one id per game

    /**
     * Resolve proven aliases while keeping title-only fallback identities separate. Older clients
     * may still send a title beside a serial: it is deliberately ignored, not persisted as evidence.
     */
    fun resolveGames(lists: List<List<String>>): List<String> = synchronized(lock) {
        var changed = repairIdentityAliases()
        val out = lists.map { raw ->
            val platform = raw.firstNotNullOfOrNull { GameKey.parse(it)?.platform }
            val valid = raw.filter { GameKey.parse(it)?.platform == platform && platform != null }.distinct().take(MAX_ALIASES)
            val strong = valid.filter(::strongIdentity)
            var ids = strong.ifEmpty { valid.filter { GameKey.parse(it)?.identity?.startsWith("t.") == true } }
            if (ids.isEmpty()) return@map ""
            // A contradictory claim cannot teach the host that two serials are one title.
            val serials = ids.filter(::serialIdentity).toSet()
            if (serials.size > 1) ids = listOf(ids.first(::serialIdentity))
            fun compatible(canonical: String): Boolean {
                val ownSerials = ids.filter(::serialIdentity).toSet()
                val bound = (aliases.filterValues { it == canonical }.keys + canonical)
                    .filter(::serialIdentity).toSet()
                return ownSerials.isEmpty() || bound.isEmpty() || ownSerials.any { it in bound }
            }
            val canon = ids.firstNotNullOfOrNull { aliases[it]?.takeIf(::compatible) }
                ?: ids.firstOrNull { hasData(it) && compatible(it) } ?: ids.first()
            if (aliases[canon] != canon) { aliases[canon] = canon; changed = true }
            for (id in ids) {
                if (id == canon || aliases[id] == canon) continue
                val previous = aliases[id]
                if (previous == null) {
                    aliases[id] = canon
                    adopt(id, canon)
                    changed = true
                } else if (compatible(previous)) {
                    // Newly learned strong evidence can join two previously independent histories.
                    val group = (aliases.filterValues { it == previous }.keys + previous)
                    val canonicalGroup = (aliases.filterValues { it == canon }.keys + canon)
                    if ((group + canonicalGroup).filter(::serialIdentity).distinct().size > 1) continue
                    for (member in group) aliases[member] = canon
                    adopt(previous, canon)
                    changed = true
                }
            }
            canon
        }
        if (changed) writeAtomically(aliasFile, json.encodeToString(GameAliasesSerializer, aliases).toByteArray())
        out
    }

    private fun serialIdentity(id: String) = GameKey.parse(id)?.identity?.startsWith("s.") == true

    private fun strongIdentity(id: String): Boolean = GameKey.parse(id)?.identity?.let {
        it.startsWith("s.") || it.startsWith("h.") || it.startsWith("r.") || it.startsWith("p.")
    } == true

    /**
     * Remove the weak bridges retained by previous releases. A group with contradictory serials
     * has unknown historical ownership: retain its original records/revisions, and split strong
     * keys for future writes without copying those saves into either game. Unambiguous groups
     * keep their known strong identity and move existing history with it.
     */
    private fun repairIdentityAliases(): Boolean {
        var changed = false
        for ((canonical, members) in aliases.toMap().entries.groupBy({ it.value }, { it.key })) {
            val keys = (members + canonical).distinct()
            val strong = keys.filter(::strongIdentity)
            if (strong.isEmpty()) continue
            val conflicting = strong.count(::serialIdentity) > 1
            val target = if (strongIdentity(canonical)) canonical else strong.firstOrNull(::serialIdentity) ?: strong.sorted().first()
            for (key in keys) {
                val next = if (strongIdentity(key)) { if (conflicting) key else target } else key
                if (aliases[key] != next) { aliases[key] = next; changed = true }
            }
            if (!conflicting && canonical != target) adopt(canonical, target)
        }
        return changed
    }

    private fun hasData(game: String): Boolean =
        revisions.values.any { list -> list.any { it.game == game } } || metas.values.any { game in it.games }

    /** Join proven identities without dropping either game's revision history or collection state. */
    private fun adopt(from: String, to: String) {
        if (GameKey.parse(to) == null) return
        for ((profile, list) in revisions) {
            var moved = false
            for (i in list.indices) {
                val r = list[i]
                if (r.game == from) {
                    list[i] = r.copy(game = to)
                    moved = true
                }
            }
            if (moved) rewriteRevisions(profile)
        }
        for ((profile, meta) in metas.toMap()) {
            val next = meta.byIds(mapOf(from to to))
            if (next == meta) continue
            metas[profile] = next
            writeAtomically(File(profileDir(profile), "meta.json"), json.encodeToString(ProfileMeta.serializer(), next).toByteArray())
        }
    }

    // ---------------------------------------------------------------- revisions

    fun revisions(profile: String, game: String? = null, kind: SaveKind? = null): List<SaveRevision> = synchronized(lock) {
        revisions[profile].orEmpty().filter { (game == null || it.game == game) && (kind == null || it.kind == kind) }.sortedByDescending { it.at }
    }

    fun head(profile: String, game: String, kind: SaveKind): SaveRevision? = synchronized(lock) {
        // The newest revision played (or restored); copies kept for safety are history, never the head.
        revisions[profile].orEmpty().filter { it.game == game && it.kind == kind && it.canBeNewest }.maxByOrNull { it.at }
    }

    /** Keeps what [device] reports in place; older notes never replace newer ones. */
    /** What the host keeps of the household's services: sign-ins sealed with the host's own key. */
    @Serializable
    private data class KeptServices(
        val romm: ServiceAddress? = null,
        val jellyfin: ServiceAddress? = null,
        /** "romm", "jellyfin:<profile>" and "key:<name>" to each sign-in or key, sealed with [servicesKey]. */
        val signIns: Map<String, String> = emptyMap(),
        val salt: String = "",
        val shared: Boolean = false,
        val declined: List<String> = emptyList(),
    )

    private val servicesKey: String by lazy {
        if (servicesKeyFile.isFile) servicesKeyFile.readText().trim()
        else SyncCrypto.token(32).also { writeAtomically(servicesKeyFile, it.toByteArray()); runCatching { servicesKeyFile.setReadable(false, false); servicesKeyFile.setReadable(true, true) } }
    }

    private fun keptServices(): KeptServices =
        if (servicesFile.isFile) runCatching { json.decodeFromString(KeptServices.serializer(), servicesFile.readText()) }.getOrDefault(KeptServices()) else KeptServices()

    /**
     * The household's services for [device]: addresses, and the sign-ins it may have (RomM's, and
     * Jellyfin's for the profiles it may open) sealed with its own secret. Null for a device that
     * isn't trusted.
     */
    fun servicesFor(device: String): HouseholdServices? = synchronized(lock) {
        val d = devices[device]?.takeIf { !it.revoked } ?: return@synchronized null
        val kept = keptServices()
        val open = kept.signIns.mapNotNull { (key, sealedHere) ->
            val profile = key.substringAfter("jellyfin:", "").ifEmpty { null }
            if (profile != null && !mayUse(device, profile)) return@mapNotNull null
            SyncCrypto.open(sealedHere, servicesKey, kept.salt)?.let { key to it.decodeToString() }
        }.toMap()
        val salt = SyncCrypto.token(16)
        val sealed = if (open.isEmpty()) null else SyncCrypto.seal(json.encodeToString(SignInsSerializer, open).toByteArray(), d.secret, salt)
        HouseholdServices(kept.romm, kept.jellyfin, sealed, salt, kept.shared, kept.declined)
    }

    /**
     * Takes what [device] shares: addresses replace the household's, sign-ins it sealed with its own
     * secret are opened and kept sealed with the host's key (Jellyfin's only for profiles it may
     * open). A device saying no is noted, so the next one asks. False when it can't be opened.
     */
    fun shareServices(device: String, share: ServicesShare): Boolean = synchronized(lock) {
        val d = devices[device]?.takeIf { !it.revoked } ?: return@synchronized false
        val kept = keptServices()
        if (share.declined) {
            writeAtomically(servicesFile, json.encodeToString(KeptServices.serializer(), kept.copy(declined = (kept.declined + device).distinct())).toByteArray())
            return@synchronized true
        }
        val given: Map<String, String> = share.sealed?.let { sealedText ->
            val plain = SyncCrypto.open(sealedText, d.secret, share.salt) ?: return@synchronized false
            runCatching { json.decodeFromString(SignInsSerializer, plain.decodeToString()) }.getOrNull() ?: return@synchronized false
        }.orEmpty().filterKeys { key ->
            key == "romm" || HOUSEHOLD_KEY.matches(key) || key.substringAfter("jellyfin:", "").let { it.isNotEmpty() && mayUse(device, it) }
        }
        // Every sign-in is sealed again under one fresh salt with the host's key.
        val current = kept.signIns.mapNotNull { (k, v) -> SyncCrypto.open(v, servicesKey, kept.salt)?.let { k to it.decodeToString() } }.toMap()
        val all = current + given
        val salt = SyncCrypto.token(16)
        val next = KeptServices(
            romm = share.romm ?: kept.romm,
            jellyfin = share.jellyfin ?: kept.jellyfin,
            signIns = all.mapValues { (_, v) -> SyncCrypto.seal(v.toByteArray(), servicesKey, salt) },
            salt = salt,
            shared = kept.shared || given.isNotEmpty() || share.romm != null || share.jellyfin != null,
            declined = kept.declined - device,
        )
        writeAtomically(servicesFile, json.encodeToString(KeptServices.serializer(), next).toByteArray())
        true
    }

    fun noteApplied(device: String, notes: List<AppliedNote>): Unit = synchronized(lock) {
        val mine = applied.getOrPut(device) { HashMap() }
        var changed = false
        for (n in notes.take(MAX_APPLIED)) {
            val key = "${n.profile}|${n.game}|${n.kind.name}"
            val before = mine[key]
            if (before != null && before.at > n.at) continue
            if (before == n) continue
            mine[key] = n.copy(at = if (n.at > 0) n.at else clock())
            changed = true
        }
        if (changed) writeAtomically(appliedFile, json.encodeToString(AppliedFileSerializer, applied).toByteArray())
    }

    /**
     * Every device's place with each save of [game] for [profile] (and the household's shared
     * save): current, behind, waiting on the person, or never reported (likely without the game).
     * The device that made the newest counts as current, report or not.
     */
    fun convergence(profile: String, game: String): Convergence = synchronized(lock) {
        val owners = listOf(profile, SHARED_SAVES)
        val heads = owners.flatMap { o -> revisions[o].orEmpty().filter { it.game == game && it.canBeNewest }.groupBy { it.kind }.map { (_, g) -> g.maxBy { it.at } } }
        val live = devices.values.filter { !it.revoked }
        Convergence(heads.map { head ->
            val key = "${head.profile}|$game|${head.kind.name}"
            SlotDevices(game, head.kind, head.id, head.deviceName, live.map { d ->
                val n = applied[d.id]?.get(key)
                val state = when {
                    d.id == head.device -> AppliedState.CURRENT
                    n == null -> null
                    n.revision == head.id -> AppliedState.CURRENT
                    n.state == AppliedState.CURRENT -> AppliedState.BEHIND
                    else -> n.state
                }
                DeviceSlot(d.id, d.name, d.platform, state, n?.revision ?: head.id.takeIf { d.id == head.device }, n?.at ?: 0, d.lastSeen)
            })
        })
    }

    fun heads(profile: String): Heads = synchronized(lock) {
        val all = revisions[profile].orEmpty().filter { it.canBeNewest }
        Heads(all.groupBy { it.game to it.kind }.values.map { group -> group.maxBy { it.at } }, seq)
    }

    /**
     * Everything kept for [profile], for the Hub: its play time in all and by device, and every game
     * it played or saved, with each save's versions, their files, sizes and where each file is kept.
     */
    fun report(profile: String): ProfileReport? = synchronized(lock) {
        val record = profiles[profile]?.takeIf { !it.deleted } ?: return null
        val meta = metas[profile] ?: ProfileMeta()
        val revs = revisions[profile].orEmpty()
        // Each game's versions in one pass, not one pass over every version per game.
        val byGame = revs.groupBy { it.game }
        val keys = (meta.games.keys + byGame.keys).distinct()
        val games = keys.map { id -> gameReportOf(id, meta.games[id], byGame[id].orEmpty()) }
            .sortedWith(compareByDescending<GameReport> { maxOf(it.lastPlayed ?: 0, it.slots.flatMap { s -> s.versions }.maxOfOrNull { v -> v.at } ?: 0) })
        val devicePlay = HashMap<String, Long>()
        meta.games.values.forEach { g -> g.playSeconds.forEach { (d, sec) -> devicePlay[d] = (devicePlay[d] ?: 0) + sec } }
        ProfileReport(
            profile = infoOf(record),
            playSeconds = meta.games.values.sumOf { it.totalSeconds },
            devicePlay = devicePlay,
            devices = devices.values.map(::deviceInfoOf),
            games = games,
            savesBytes = revs.flatMap { it.manifest.files }.distinctBy { it.hash }.sumOf { it.size },
            storePath = dir.absolutePath,
        )
    }

    /** One game of [profile] as [report] has it, without building every other game's. */
    fun gameReport(profile: String, game: String): GameReport? = synchronized(lock) {
        profiles[profile]?.takeIf { !it.deleted } ?: return null
        val g = metas[profile]?.games?.get(game)
        val mine = revisions[profile].orEmpty().filter { it.game == game }
        if (g == null && mine.isEmpty()) return null
        gameReportOf(game, g, mine)
    }

    private fun gameReportOf(id: String, g: GameRecord?, mine: List<SaveRevision>): GameReport {
        fun stored(hash: String) = content.fileOf(hash).relativeTo(dir).path.replace('\\', '/')
        val slots = mine.groupBy { it.kind }.map { (kind, list) ->
            val newest = list.sortedByDescending { it.at }
            val head = newest.firstOrNull { it.canBeNewest }?.id
            SlotReport(
                kind = kind,
                format = newest.first().manifest.format,
                bytes = newest.flatMap { it.manifest.files }.distinctBy { it.hash }.sumOf { it.size },
                versions = newest.map { r ->
                    VersionReport(
                        r.id, r.device, r.deviceName, r.at.millis, r.playSeconds, r.reason, r.id == head, r.size,
                        r.manifest.files.map { f -> FileReport(f.path, f.size, stored(f.hash)) },
                        kept = r.kept, provenance = r.provenance,
                    )
                },
            )
        }.sortedBy { it.kind.ordinal }
        val key = GameKey.parse(id)
        return GameReport(
            game = id,
            name = g?.title?.value ?: g?.name ?: mine.firstOrNull { it.title.isNotBlank() }?.title ?: readable(key?.identity ?: id),
            platform = key?.platform.orEmpty(),
            playSeconds = g?.totalSeconds ?: 0,
            devicePlay = g?.playSeconds.orEmpty(),
            sessions = g?.sessions?.size ?: 0,
            lastPlayed = g?.lastPlayed,
            favorite = g?.favorite?.value == true,
            slots = slots,
        )
    }

    /** "t.pokemon-ruby" as "Pokemon Ruby", "s.slus00067" as "SLUS00067": a game's key, readable. */
    private fun readable(identity: String): String = when {
        identity.startsWith("t.") -> identity.removePrefix("t.").split('-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        identity.startsWith("s.") -> identity.removePrefix("s.").uppercase()
        else -> identity
    }

    fun missing(hashes: List<String>): List<String> = hashes.filter { SavePath.isHash(it) && !content.has(it) }.distinct()

    /**
     * Takes a new revision. It becomes the newest when it was made from the newest the host has
     * (or the slot is new); otherwise it is a conflict, kept as a conflict copy and never lost,
     * and the device is told what the host has.
     */
    fun push(profile: String, device: String, revision: SaveRevision): RevisionResult = synchronized(lock) {
        require(revision.profile == profile) { "Revision for another profile" }
        require(revision.manifest.files.all { SavePath.isSafe(it.path) && SavePath.isHash(it.hash) && it.size >= 0 }) { "Bad file in the save" }
        require(revision.manifest.folders.size <= MAX_FOLDERS && revision.manifest.folders.all(SavePath::isSafe)) { "Bad folder in the save" }
        require(revision.id.length in 8..64 && revision.id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Bad revision id" }
        require(GameKey.parse(revision.game) != null) { "Bad game key" }
        val missing = missing(revision.manifest.files.map { it.hash })
        require(missing.isEmpty()) { "Files not on the host yet: ${missing.size}" }
        val list = revisions.getOrPut(profile) { ArrayList() }
        list.firstOrNull { it.id == revision.id }?.let { existing ->
            // The same push again (a retry after a lost answer): same answer.
            val head = head(profile, revision.game, revision.kind)
            val conflict = existing.reason == RevisionReason.CONFLICT_COPY && revision.reason != RevisionReason.CONFLICT_COPY
            return@synchronized RevisionResult(accepted = !conflict, head = head, conflict = conflict)
        }
        val head = head(profile, revision.game, revision.kind)
        // The device's clock may be wrong: the host's order is the host's.
        val at = Hlc(maxOf(clock(), (head?.at?.millis ?: 0) + 1), 0, device)
        // A copy kept for safety (before a restore, the other side of a conflict) joins the history
        // as it is and never becomes the newest, whatever it was made from.
        val historyOnly = !revision.canBeNewest
        val fits = historyOnly || head == null || head.id == revision.parent || head.manifest.fingerprint == revision.manifest.fingerprint
        val kept = if (fits) {
            revision.copy(at = at, device = device)
        } else {
            revision.copy(at = at, device = device, reason = RevisionReason.CONFLICT_COPY)
        }
        list += kept
        append(File(profileDir(profile), "revisions.jsonl"), json.encodeToString(SaveRevision.serializer(), kept))
        event(JournalEvent.REVISION, profile, revision.game, revision.kind, kept.id, device)
        // The device that made the newest has it in place: it plays this game, and is current.
        if (fits && !historyOnly) noteApplied(device, listOf(AppliedNote(profile, revision.game, revision.kind, kept.id, AppliedState.CURRENT, clock())))
        when {
            historyOnly -> RevisionResult(accepted = true, head = head)
            fits -> RevisionResult(accepted = true, head = kept)
            else -> RevisionResult(accepted = false, head = head, conflict = true)
        }
    }

    /** The games played as one save for everyone. */
    fun sharedGames(): SharedGames = synchronized(lock) { SharedGames(shared.toList()) }

    /**
     * Makes [game] one save for everyone, or each person's own again. Made shared from [from], that
     * person's newest saves of it become the shared ones (each kind's head, copied, so theirs stays
     * in their own history too); without [from] everyone starts it together from nothing.
     * Each person's own saves are never touched either way.
     */
    fun setShared(game: String, isShared: Boolean, from: String?, device: String): SharedGames = synchronized(lock) {
        require(GameKey.parse(game) != null) { "Bad game key" }
        if (isShared) {
            if (from != null) {
                val heads = revisions[from].orEmpty().filter { it.game == game && it.canBeNewest }
                    .groupBy { it.kind }.mapNotNull { (_, list) -> list.maxByOrNull { it.at } }
                val list = revisions.getOrPut(SHARED_SAVES) { ArrayList() }
                for (h in heads) {
                    val parent = head(SHARED_SAVES, game, h.kind)
                    val copy = h.copy(
                        id = SyncCrypto.token(12), profile = SHARED_SAVES, parent = parent?.id,
                        at = Hlc(maxOf(clock(), (parent?.at?.millis ?: 0) + 1), 0, device), reason = RevisionReason.PLAYED, pinned = false,
                    )
                    list += copy
                    append(File(profileDir(SHARED_SAVES), "revisions.jsonl"), json.encodeToString(SaveRevision.serializer(), copy))
                    event(JournalEvent.REVISION, SHARED_SAVES, game, h.kind, copy.id, device)
                }
            }
            shared += game
        } else {
            shared -= game
        }
        writeAtomically(sharedFile, json.encodeToString(SharedGames.serializer(), SharedGames(shared.toList())).toByteArray())
        SharedGames(shared.toList())
    }

    /**
     * Marks a revision as one to keep for good (or not). Only the mark changes: a copy kept for
     * safety stays one, so keeping it never makes it the newest.
     */
    fun pin(profile: String, id: String, pinned: Boolean): Boolean = synchronized(lock) {
        val list = revisions[profile] ?: return@synchronized false
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized false
        val r = list[i]
        // An older host kept a pin as the reason itself; letting go of one of those makes it an ordinary save.
        list[i] = if (!pinned && r.reason == RevisionReason.MILESTONE) r.copy(reason = RevisionReason.PLAYED, pinned = false) else r.copy(pinned = pinned)
        rewriteRevisions(profile)
        true
    }

    private fun rewriteRevisions(profile: String) {
        val text = revisions[profile].orEmpty().joinToString("") { json.encodeToString(SaveRevision.serializer(), it) + "\n" }
        writeAtomically(File(profileDir(profile), "revisions.jsonl"), text.toByteArray())
    }

    /**
     * Thins each slot's history by [retention] and removes files no revision uses any more.
     * Returns the bytes freed.
     */
    fun compact(retention: Retention): Long = synchronized(lock) {
        val now = clock()
        for ((profile, list) in revisions) {
            val keep = list.groupBy { it.game to it.kind }.values.flatMap { retention.keep(it, now) }.toSet()
            if (list.removeAll { it.id !in keep }) rewriteRevisions(profile)
        }
        val referenced = revisions.values.flatten().flatMap { it.manifest.files }.map { it.hash }.toSet()
        content.collect(referenced)
    }

    fun events(since: Long, limit: Int = 500): JournalPage = synchronized(lock) {
        JournalPage(journal.asSequence().filter { it.seq > since }.take(limit).toList(), seq)
    }
}

internal sealed interface UnlockResult {
    data object Opened : UnlockResult
    data object WrongPin : UnlockResult
    data object NoProfile : UnlockResult
    data class Wait(val millis: Long) : UnlockResult
}

/** The host computer's own profile. */
internal const val ADMIN_NAME = "Admin"

/** The fewest rounds a carried PIN hash may have been stretched with. */
internal const val MIN_PIN_ITERATIONS = 10_000
internal const val ADMIN_AVATAR = "crown"

/**
 * What a host keeps in its folder, and nothing else: the folder may be one the person chose (a big
 * drive) that holds other things too, and those are never moved or deleted with the host.
 */
object HostFiles {
    private val OWN = setOf(
        "host.json", "devices.json", "profiles.json", "shared-games.json", "game-aliases.json", "account.json", "outside.json",
        "journal.jsonl", "applied.json", "admin.token", "objects", "profiles", "services.json", "services.key",
        "household", HostLeadership.HOSTS_FILE, HostLeadership.NODE_FILE, HostLeadership.SECRET_FILE, HUB_SESSIONS_FILE,
    )

    /** The host's own files and folders in [dir] (with any write a crash left half done). */
    fun own(dir: File): List<File> = dir.listFiles().orEmpty().filter { f ->
        f.name in OWN || (f.name.startsWith(".") && f.name.endsWith(".tmp") && f.name.removePrefix(".").removeSuffix(".tmp") in OWN)
    }

    /** True when [dir] holds things that aren't a host's (so a new host belongs in a folder of its own inside it). */
    fun holdsOthers(dir: File): Boolean = dir.listFiles().orEmpty().any { f -> f !in own(dir) && f.name != ".DS_Store" && f.name != "desktop.ini" }

    /** Deletes the host's own files from [dir], then [dir] itself if nothing else is left in it. */
    fun delete(dir: File) {
        own(dir).forEach { it.deleteRecursively() }
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
    }
}

/** Journal entries kept (twice as many before it is trimmed). */
private const val JOURNAL_KEEP = 10_000

/** What a backup copies of a host's folder: everything but the saves' files, the admin token and the computer's own place. */
private val COPIED = setOf(
    "host.json", "devices.json", "profiles.json", "shared-games.json", "game-aliases.json", "account.json", "outside.json",
    "journal.jsonl", "applied.json", "services.json", "services.key", "profiles", "household",
    HostLeadership.HOSTS_FILE, HostLeadership.SECRET_FILE,
)
private val NOT_COPIED = setOf("admin.token", HostLeadership.NODE_FILE)

/** The most ids one game may be claimed under at once. */
private const val MAX_ALIASES = 8

/** Empty folders one save may carry. */
private const val MAX_FOLDERS = 10_000

/** The shortest password the host's account takes. */
internal const val MIN_PASSWORD = 8

/** How hard the account's password is stretched (it guards joining and the Hub from away). */
internal const val ACCOUNT_ITERATIONS = 210_000

/** A Hub sign-in is carried on when used, at most this often. */
private const val RENEW_EVERY_MS = 24 * 60 * 60_000L

/** applied.json: device id to `profile|game|kind` to what it reported. */
private val AppliedFileSerializer: kotlinx.serialization.KSerializer<Map<String, Map<String, AppliedNote>>> = kotlinx.serialization.builtins.MapSerializer(
    String.serializer(),
    kotlinx.serialization.builtins.MapSerializer(String.serializer(), AppliedNote.serializer()),
)

/** The most notes one report may carry. */
private const val MAX_APPLIED = 5_000
