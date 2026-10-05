package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One file of a save on this device: its place in the save and where it lives here. */
data class LocalFile(val path: String, val file: File)

/**
 * Where one game's save of one kind lives on this device, as a save adapter found it: its files,
 * the format they are in, and the folder they belong in. [available] is false when that folder's
 * drive isn't there (an SD card out, a USB drive unplugged): nothing is read or written then, and
 * missing files are never taken for deleted ones.
 */
data class LocalSlot(
    val game: GameKey,
    val kind: SaveKind,
    val format: String,
    val root: File,
    val files: List<LocalFile>,
    val available: Boolean = true,
    /** Where each named file goes here, for saves whose files are named per device (`save.srm` is `Chrono Trigger.srm`). */
    val targets: Map<String, File> = emptyMap(),
) {
    val key: String get() = "${game.id}|${kind.name}"

    /** Where the file named [name] goes: its own target, else inside [root] (never outside it). */
    fun target(name: String): File? {
        targets[name]?.let { return it }
        if (!SavePath.isSafe(name)) return null
        val f = File(root, name)
        return f.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }
}

/** Why a launch would wait, and what the person can choose. */
sealed interface PrepareResult {
    /** Ready to play: this device has the newest save (or there is none anywhere). */
    data object Ready : PrepareResult

    /** The newest save came down and is in place. */
    data class Updated(val revision: SaveRevision) : PrepareResult

    /** The host couldn't be reached: the game plays with what is here, and syncs afterwards. */
    data object Offline : PrepareResult

    /** The save's drive isn't here: nothing was touched. */
    data object Unavailable : PrepareResult

    /**
     * The newest save is in a format this emulator can't read (a state from another emulator, or a
     * save from an emulator that keeps them differently): nothing was touched, and it stays on the
     * host for the device that can use it.
     */
    data class Incompatible(val revision: SaveRevision) : PrepareResult

    /**
     * This device and another both played since they last agreed. [local] is what is here (not
     * yet on the host), [remote] the host's newest. Nothing changes until the person chooses.
     */
    data class Conflict(val local: SaveRevision, val remote: SaveRevision) : PrepareResult
}

@Serializable
internal data class SlotState(
    /** The revision this device last agreed with the host on. */
    val base: String? = null,
    /** The fingerprint of the files as they were then. */
    val fingerprint: String? = null,
)

/** Something waiting to go to the host: a revision (files already in the device's store) or records. */
@Serializable
internal data class Outgoing(val id: String, val priority: Int, val profile: String, val revision: SaveRevision? = null, val tries: Int = 0)

@Serializable
internal data class DeviceState(
    val profile: String? = null,
    val seq: Long = 0,
    val slots: Map<String, SlotState> = emptyMap(),
    val outbox: List<Outgoing> = emptyList(),
    /** The profile's records as this device last merged them, by profile. */
    val metas: Map<String, ProfileMeta> = emptyMap(),
    /** Records changed here and not yet on the host, by profile. */
    val pendingMeta: Map<String, ProfileMeta> = emptyMap(),
)

/** How urgent a transfer is: the save for a game about to start goes before everything else. */
enum class Priority(val rank: Int) { LAUNCH(0), PROFILE(1), SAVE(2), RECORDS(3), ARTWORK(4), GAME_FILES(5) }

/**
 * A device's side of Fuse Sync: what it last agreed with the host on for every save, the files it
 * captured (kept by content in [dir]/objects), and what is still to send. Everything works offline:
 * playing records the session and captures the save, and both go up when the host is next reached,
 * in order of what matters most. Coming online it catches up from where it left off. Nothing on
 * this device is ever deleted because something is missing elsewhere; deleting is always a choice
 * someone made.
 */
class SyncDevice(
    val dir: File,
    val deviceId: String,
    val deviceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(dir, "device-state.json")
    val store = ContentStore(File(dir, "objects"))
    private val mutex = Mutex()
    private val hlc = HlcClock(deviceId, clock)
    private var state: DeviceState = if (stateFile.isFile) runCatching { json.decodeFromString(DeviceState.serializer(), stateFile.readText()) }.getOrDefault(DeviceState()) else DeviceState()

    private fun persist() = writeAtomically(stateFile, json.encodeToString(DeviceState.serializer(), state).toByteArray())

    val profile: String? get() = state.profile
    val pendingCount: Int get() = state.outbox.size + state.pendingMeta.count { it.value != ProfileMeta() }
    val seq: Long get() = state.seq

    suspend fun useProfile(id: String?) = mutex.withLock {
        state = state.copy(profile = id)
        persist()
    }

    /** The profile's records as this device has them: the host's last word plus what changed here since. */
    fun meta(profile: String): ProfileMeta = (state.metas[profile] ?: ProfileMeta()).merge(state.pendingMeta[profile] ?: ProfileMeta())

    /** Changes the profile's records here (a session played, a favourite set): kept, and sent when the host is reached. */
    suspend fun changeMeta(profile: String, change: (ProfileMeta, HlcClock) -> ProfileMeta) = mutex.withLock {
        val pending = state.pendingMeta[profile] ?: ProfileMeta()
        val next = change(pending, hlc)
        state = state.copy(pendingMeta = state.pendingMeta + (profile to next))
        persist()
    }

    /**
     * Records [session] for [game]: this device's own counter grows by its length, counted from
     * everything this device knows its counter to be (its counter only ever goes up, so it is
     * the whole count, never a part).
     */
    suspend fun played(profile: String, game: GameKey, session: SessionEntry) = mutex.withLock {
        val full = meta(profile).game(game)
        if (session.id in full.sessions) return@withLock
        val mine = (full.playSeconds[session.device] ?: 0) + session.seconds
        val pending = state.pendingMeta[profile] ?: ProfileMeta()
        val record = pending.game(game)
        val next = record.copy(
            playSeconds = record.playSeconds + (session.device to mine),
            sessions = record.sessions + (session.id to session),
            lastPlayed = maxOf(record.lastPlayed ?: 0, full.lastPlayed ?: 0, session.endedAt),
        )
        state = state.copy(pendingMeta = state.pendingMeta + (profile to pending.withGame(next)))
        persist()
    }

    fun now(): Hlc = hlc.now()

    // ---------------------------------------------------------------- saves

    private fun fingerprintOf(slot: LocalSlot): Pair<SaveManifest, List<Pair<LocalFile, String>>> {
        val hashed = slot.files.filter { it.file.isFile && SavePath.isSafe(it.path) }.map { lf -> lf to lf.file.inputStream().use { SyncCrypto.sha256(it) } }
        val manifest = SaveManifest(slot.format, hashed.map { (lf, h) -> SaveFile(lf.path, h, lf.file.length()) }.sortedBy { it.path })
        return manifest to hashed
    }

    /**
     * After playing: reads the slot's files and, when they changed since the last agreed save,
     * keeps them as a new revision made from it and queues it for the host. An empty slot (the
     * game made no save, or its drive is gone) is never a revision.
     */
    suspend fun capture(profile: String, slot: LocalSlot, playSeconds: Long, priority: Priority = Priority.SAVE): SaveRevision? = mutex.withLock {
        if (!slot.available) return@withLock null
        val (manifest, hashed) = fingerprintOf(slot)
        if (manifest.files.isEmpty()) return@withLock null
        val slotState = state.slots[slot.key] ?: SlotState()
        if (manifest.fingerprint == slotState.fingerprint) return@withLock null
        for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
        val revision = SaveRevision(
            id = SyncCrypto.token(12), profile = profile, game = slot.game.id, kind = slot.kind, parent = slotState.base,
            device = deviceId, deviceName = deviceName, at = hlc.now(), manifest = manifest, playSeconds = playSeconds,
        )
        state = state.copy(outbox = state.outbox + Outgoing(revision.id, priority.rank, profile, revision))
        persist()
        revision
    }

    /**
     * Before playing: makes sure this device has the newest save. With the host reachable, a newer
     * save there comes down (files checked, then put in place together, the files that were here
     * kept as a revision first); a save changed here and there too is a [PrepareResult.Conflict] for
     * the person to settle. Without the host, the game plays with what is here.
     */
    suspend fun prepare(client: SyncClient?, profile: String, slot: LocalSlot): PrepareResult {
        if (!slot.available) return PrepareResult.Unavailable
        val head = try {
            client?.revisions(profile, slot.game.id, slot.kind)?.firstOrNull { it.reason != RevisionReason.CONFLICT_COPY }
        } catch (e: SyncException) {
            if (e.code == "offline") return PrepareResult.Offline else throw e
        } ?: return if (client == null) PrepareResult.Offline else PrepareResult.Ready
        if (!SaveSlotFormats.compatible(head.manifest.format, slot.format)) return PrepareResult.Incompatible(head)
        val (manifest, _) = fingerprintOf(slot)
        val slotState = state.slots[slot.key] ?: SlotState()
        val localChanged = manifest.files.isNotEmpty() && manifest.fingerprint != slotState.fingerprint
        // Saves queued from here and not yet sent are this device's newest.
        val queued = state.outbox.lastOrNull { it.revision?.game == slot.game.id && it.revision.kind == slot.kind }?.revision
        return when (SyncRules.decide(slotState.base, localChanged || queued != null, head.id, sameContent = manifest.fingerprint == head.manifest.fingerprint)) {
            SyncDecision.UpToDate -> {
                agree(slot.key, head.id, head.manifest.fingerprint)
                PrepareResult.Ready
            }
            SyncDecision.Upload -> PrepareResult.Ready
            SyncDecision.Download -> {
                place(client!!, slot, head)
                PrepareResult.Updated(head)
            }
            SyncDecision.Conflict -> {
                val local = queued ?: SaveRevision(
                    id = "local-" + SyncCrypto.token(6), profile = profile, game = slot.game.id, kind = slot.kind, parent = slotState.base,
                    device = deviceId, deviceName = deviceName, at = hlc.now(), manifest = manifest,
                )
                PrepareResult.Conflict(local, head)
            }
        }
    }

    /**
     * The person kept this device's save: it goes up as the newest, made from the host's (which
     * stays in the history), so the host takes it without another conflict.
     */
    suspend fun keepLocal(client: SyncClient, profile: String, slot: LocalSlot, remote: SaveRevision, playSeconds: Long) {
        mutex.withLock {
            state = state.copy(
                slots = state.slots + (slot.key to SlotState(remote.id, remote.manifest.fingerprint)),
                outbox = state.outbox.filterNot { it.revision?.game == slot.game.id && it.revision.kind == slot.kind },
            )
            persist()
        }
        capture(profile, slot, playSeconds, Priority.LAUNCH)
        flush(client)
    }

    /** The person took the host's save: this device's is kept as a revision on the host first, then replaced. */
    suspend fun takeRemote(client: SyncClient, profile: String, slot: LocalSlot, remote: SaveRevision) {
        place(client, slot, remote)
    }

    /**
     * Puts [revision]'s files in place: each downloaded and checked, written beside its target and
     * then moved over it, after the files that were here were kept (as a revision marked as before
     * a restore, which is queued for the host). If anything fails part way, what was here stays.
     */
    suspend fun place(client: SyncClient, slot: LocalSlot, revision: SaveRevision) {
        for (f in revision.manifest.files) client.download(f.hash, store)
        mutex.withLock {
            // What is here now is kept first, so taking another save never loses this one.
            val (current, hashed) = fingerprintOf(slot)
            if (current.files.isNotEmpty() && current.fingerprint != revision.manifest.fingerprint) {
                for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
                val kept = SaveRevision(
                    id = SyncCrypto.token(12), profile = revision.profile, game = slot.game.id, kind = slot.kind,
                    parent = state.slots[slot.key]?.base, device = deviceId, deviceName = deviceName, at = hlc.now(),
                    manifest = current, reason = RevisionReason.BEFORE_RESTORE,
                )
                state = state.copy(outbox = state.outbox + Outgoing(kept.id, Priority.SAVE.rank, revision.profile, kept))
            }
            val staged = ArrayList<Pair<File, File>>()
            try {
                for (f in revision.manifest.files) {
                    val target = slot.target(f.path) ?: throw IntegrityException("A file in the save has nowhere safe to go: ${f.path}")
                    target.parentFile.mkdirs()
                    val tmp = File(target.parentFile, ".${target.name}.fuse-sync")
                    store.fileOf(f.hash).copyTo(tmp, overwrite = true)
                    if (tmp.inputStream().use { SyncCrypto.sha256(it) } != f.hash) throw IntegrityException("A file changed on its way into place")
                    staged += tmp to target
                }
                for ((tmp, target) in staged) {
                    try {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } finally {
                staged.forEach { (tmp, _) -> tmp.delete() }
            }
            state = state.copy(slots = state.slots + (slot.key to SlotState(revision.id, revision.manifest.fingerprint)))
            persist()
        }
    }

    private suspend fun agree(key: String, revision: String, fingerprint: String) = mutex.withLock {
        state = state.copy(slots = state.slots + (key to SlotState(revision, fingerprint)))
        persist()
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends what is waiting, most urgent first: records, then revisions. Stops at the first
     * failure to reach the host (it all stays queued); a revision the host refuses as a conflict
     * is kept there as a conflict copy and settled before the next launch.
     */
    suspend fun flush(client: SyncClient): Int {
        var sent = 0
        val profiles = mutex.withLock { state.pendingMeta.keys.toList() }
        for (p in profiles) {
            val pending = mutex.withLock { state.pendingMeta[p] } ?: continue
            val merged = client.pushMeta(p, pending).meta
            mutex.withLock {
                // Whatever changed here while that was on its way stays pending.
                val now = state.pendingMeta[p]
                state = state.copy(
                    metas = state.metas + (p to merged),
                    pendingMeta = if (now == pending) state.pendingMeta - p else state.pendingMeta,
                )
                persist()
            }
            sent++
        }
        while (true) {
            val next = mutex.withLock { state.outbox.minByOrNull { it.priority } } ?: break
            val rev = next.revision ?: break
            val result = try {
                client.push(next.profile, rev, store)
            } catch (e: SyncException) {
                if (e.code == "offline") throw e
                // The host refused it outright (a bad revision): it would never go; keep it locally, drop it from the queue.
                null
            }
            mutex.withLock {
                state = state.copy(outbox = state.outbox.filterNot { it.id == next.id })
                val key = "${rev.game}|${rev.kind.name}"
                if (result?.accepted == true && rev.reason != RevisionReason.BEFORE_RESTORE) {
                    state = state.copy(slots = state.slots + (key to SlotState(rev.id, rev.manifest.fingerprint)))
                }
                persist()
            }
            sent++
        }
        return sent
    }

    /** Fetches the profile's records from the host and merges them in (after sending what changed here). */
    suspend fun pullMeta(client: SyncClient, profile: String): ProfileMeta {
        val m = client.meta(profile).meta
        mutex.withLock {
            state = state.copy(metas = state.metas + (profile to (state.metas[profile] ?: ProfileMeta()).merge(m)))
            persist()
        }
        return meta(profile)
    }

    suspend fun saw(seq: Long) = mutex.withLock {
        if (seq > state.seq) {
            state = state.copy(seq = seq)
            persist()
        }
    }
}

/** Turns what a save adapter found into a slot this device can read and write. */
object Slots {
    fun of(game: GameKey, spot: SaveSpot): LocalSlot {
        val files = ArrayList<LocalFile>()
        val targets = LinkedHashMap<String, File>()
        for (f in spot.files) {
            val file = File(f.path)
            targets[f.name] = file
            if (file.isFile) files += LocalFile(f.name, file)
        }
        val root = spot.root?.let(::File) ?: spot.files.firstOrNull()?.let { File(it.path).parentFile } ?: File(".")
        if (spot.root != null) {
            // Folder saves: every file in this game's folders, named by its path inside the root.
            val folders = spot.folders.ifEmpty { listOf("") }
            for (folder in folders) {
                val dir = if (folder.isEmpty()) root else File(root, folder)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                    val name = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (SavePath.isSafe(name)) files += LocalFile(name, f)
                }
            }
        }
        return LocalSlot(game, spot.kind, spot.format, root, files.sortedBy { it.path }, spot.available, targets)
    }
}

/** The device's own files, for save adapters. */
class FileSaveEnvironment(override val host: String, override val home: String = System.getProperty("user.home") ?: "") : SaveEnvironment {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun list(path: String): List<String> = File(path).list()?.sorted().orEmpty()
    override fun readText(path: String, limit: Int): String? = runCatching {
        val f = File(path)
        if (!f.isFile || f.length() > limit) null else f.readText()
    }.getOrNull()
    override fun env(name: String): String? = System.getenv(name)
}
