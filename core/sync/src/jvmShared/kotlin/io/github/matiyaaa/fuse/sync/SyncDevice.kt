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
    /** What each person last agreed with the host on, for each save: keyed `profile|game|kind`. */
    val slots: Map<String, SlotState> = emptyMap(),
    /** Whose save is in the emulator's folder now, for each save (`game|kind`). */
    val holders: Map<String, String> = emptyMap(),
    /** Each person's save as it was when someone else took the device, kept here by content: `profile|game|kind`. */
    val parked: Map<String, SaveManifest> = emptyMap(),
    /** Set once slots are keyed by person (older devices kept them by game alone). */
    val perPerson: Boolean = false,
    val outbox: List<Outgoing> = emptyList(),
    /** The profile's records as this device last merged them, by profile. */
    val metas: Map<String, ProfileMeta> = emptyMap(),
    /** Records changed here and not yet on the host, by profile. */
    val pendingMeta: Map<String, ProfileMeta> = emptyMap(),
    /** The folders a play showed hold a game's saves when its id couldn't be read: `game|format`. */
    val learned: Map<String, List<String>> = emptyMap(),
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
    private var state: DeviceState = migrated(
        if (stateFile.isFile) runCatching { json.decodeFromString(DeviceState.serializer(), stateFile.readText()) }.getOrDefault(DeviceState()) else DeviceState(),
    )

    /**
     * Before saves were kept per person, a device kept one state for each save: it is the person in
     * use's, and their save is the one on disk.
     */
    private fun migrated(s: DeviceState): DeviceState {
        if (s.perPerson) return s
        val owner = s.profile.orEmpty()
        return s.copy(
            slots = s.slots.mapKeys { (k, _) -> "$owner|$k" },
            holders = if (owner.isEmpty()) emptyMap() else s.slots.keys.associateWith { owner },
            perPerson = true,
        )
    }

    private fun keyOf(profile: String, slot: LocalSlot) = "$profile|${slot.key}"

    /** Whose save is in [slot]'s folder now (null before anyone played it here with Fuse Sync). */
    fun holderOf(slot: LocalSlot): String? = state.holders[slot.key]

    private fun persist() = writeAtomically(stateFile, json.encodeToString(DeviceState.serializer(), state).toByteArray())

    val profile: String? get() = state.profile

    /** The people with saves waiting to go to the host. */
    fun pendingProfiles(): Set<String> = state.outbox.map { it.profile }.toSet()
    val pendingCount: Int get() = state.outbox.size + state.pendingMeta.count { it.value != ProfileMeta() }
    val seq: Long get() = state.seq

    /** The folders [game]'s saves of [format] were learned to be in, from a play here. */
    fun learned(game: String, format: String): List<String> = state.learned["$game|$format"].orEmpty()

    /** Remembers that [game]'s saves of [format] are in [folders] (a play changed exactly those). */
    suspend fun learn(game: String, format: String, folders: List<String>) = mutex.withLock {
        state = state.copy(learned = state.learned + ("$game|$format" to folders.sorted()))
        persist()
    }

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
    suspend fun played(profile: String, game: GameKey, session: SessionEntry, known: ((ProfileMeta) -> ProfileMeta)? = null) = mutex.withLock {
        // [known] views the records by the household's one id for each game, when ids have moved.
        val full = (known?.invoke(meta(profile)) ?: meta(profile)).game(game)
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
    suspend fun capture(profile: String, slot: LocalSlot, playSeconds: Long, priority: Priority = Priority.SAVE, title: String = ""): SaveRevision? = mutex.withLock {
        if (!slot.available) return@withLock null
        val (manifest, hashed) = fingerprintOf(slot)
        if (manifest.files.isEmpty()) return@withLock null
        val slotState = state.slots[keyOf(profile, slot)] ?: SlotState()
        // Saved again while the last one still waits to go (played on, offline or mid-session): the
        // newer one takes its place, made from the same base, so the host sees one step, not a fork.
        // Copies kept for safety are history only: the line goes on from the last ordinary save waiting.
        val waiting = state.outbox.lastOrNull { o -> o.revision?.let { it.profile == profile && it.game == slot.game.id && it.kind == slot.kind && it.canBeNewest } == true }
        if (manifest.fingerprint == (waiting?.revision?.manifest?.fingerprint ?: slotState.fingerprint)) return@withLock null
        val replaces = waiting?.takeIf { it.revision?.reason == RevisionReason.PLAYED && it.priority == priority.rank }
        for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
        val revision = SaveRevision(
            id = SyncCrypto.token(12), profile = profile, game = slot.game.id, kind = slot.kind,
            parent = if (replaces != null) replaces.revision?.parent else waiting?.revision?.id ?: slotState.base,
            device = deviceId, deviceName = deviceName, at = hlc.now(), manifest = manifest, playSeconds = playSeconds, title = title,
        )
        state = state.copy(
            outbox = state.outbox.filterNot { it.id == replaces?.id } + Outgoing(revision.id, priority.rank, profile, revision),
            holders = state.holders + (slot.key to profile),
        )
        persist()
        revision
    }

    /**
     * Makes [slot]'s folder hold [profile]'s save before they play, on a device more than one person
     * uses. Whoever played last keeps theirs: anything they changed is captured for them first, and
     * their save is parked here by content. Then this person's own comes back from where it was
     * parked, or, if they never played this game here, the folder is cleared so they start their own
     * game (nothing is deleted: every file is in the device's store). The host's newest still comes
     * down afterwards, in [prepare].
     */
    suspend fun handover(profile: String, slot: LocalSlot, holderSeconds: (String) -> Long = { 0L }, orphanTo: File? = null) {
        if (!slot.available) return
        val holder = state.holders[slot.key]
        if (holder == profile) return
        if (holder == null) {
            // The first person to play it here with Fuse Sync: what is in the folder is theirs.
            mutex.withLock {
                state = state.copy(holders = state.holders + (slot.key to profile))
                persist()
            }
            return
        }
        // A removed profile's save has no one to keep it for: it goes to [orphanTo] as plain files.
        val orphan = holder == ORPHAN
        if (!orphan) capture(holder, slot, holderSeconds(holder), Priority.SAVE)
        mutex.withLock {
            val (current, hashed) = fingerprintOf(slot)
            for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
            if (orphan && orphanTo != null && current.files.isNotEmpty()) writeOut(File(File(orphanTo, part(slot.game.id)), part(slot.kind.label)), current)
            val mine = state.parked[keyOf(profile, slot)]
            // Clear what is there (every file of it is in the store), then put this person's own
            // back. A file Fuse Sync can't keep (a name it can't carry) is never touched.
            for ((lf, h) in hashed) if (store.has(h)) lf.file.delete()
            if (mine != null && mine.files.all { store.has(it.hash) }) write(slot, mine)
            var parked = state.parked - keyOf(profile, slot)
            if (current.files.isNotEmpty() && (!orphan || orphanTo == null)) parked = parked + (keyOf(holder, slot) to current)
            state = state.copy(parked = parked, holders = state.holders + (slot.key to profile))
            persist()
        }
    }

    // ---------------------------------------------------------------- people coming and going

    /** Everyone this device keeps anything for: records, saves waiting to go, saves parked or in a folder. */
    fun people(): Set<String> = buildSet {
        addAll(state.metas.keys)
        addAll(state.pendingMeta.keys)
        state.outbox.forEach { add(it.profile) }
        state.parked.keys.forEach { add(it.substringBefore('|')) }
        state.slots.keys.forEach { add(it.substringBefore('|')) }
        addAll(state.holders.values)
    } - ORPHAN - ""

    /**
     * People's ids change ([ids], old to new: profiles made here going to a host, or one found to be
     * the same person as someone there). Everything here follows: records, saves waiting to go,
     * parked saves, whose save each folder holds, and what was agreed. Where two become one, records
     * merge, and a save of the same game parked for both keeps the one already there.
     */
    suspend fun rekey(ids: Map<String, String>) = mutex.withLock {
        if (ids.all { (a, b) -> a == b }) return@withLock
        fun id(p: String) = ids[p] ?: p
        fun key(k: String): String {
            val p = k.substringBefore('|')
            return id(p) + k.substring(p.length)
        }
        fun <V> Map<String, V>.moved(join: (V, V) -> V): Map<String, V> {
            val out = LinkedHashMap<String, V>()
            for ((k, v) in this) {
                val to = key(k)
                out[to] = out[to]?.let { join(it, v) } ?: v
            }
            return out
        }
        state = state.copy(
            profile = state.profile?.let(::id),
            slots = state.slots.moved { a, _ -> a },
            holders = state.holders.mapValues { (_, v) -> id(v) },
            parked = state.parked.moved { a, _ -> a },
            outbox = state.outbox.map { o -> o.copy(profile = id(o.profile), revision = o.revision?.let { it.copy(profile = id(it.profile)) }) },
            metas = state.metas.moved { a, b -> a.merge(b) },
            pendingMeta = state.pendingMeta.moved { a, b -> a.merge(b) },
        )
        persist()
    }

    /**
     * [profile]'s records here with every choice stamped [at] (see [ProfileMeta.stampedAt]), as
     * changes waiting to go: joined to the same person on a host, theirs win and these fill gaps.
     */
    suspend fun restamp(profile: String, at: Hlc) = mutex.withLock {
        val m = meta(profile)
        state = state.copy(metas = state.metas - profile, pendingMeta = state.pendingMeta + (profile to m.stampedAt(at)))
        persist()
    }

    /**
     * Forgets [profile] on this device: its records, saves waiting to go and parked saves. Those
     * saves are written to [to] first as plain files (when given), so nothing is lost silently.
     * A folder holding their save now is left as it is; the next person to play that game here
     * finds it and it goes to [to] then. Returns how many saves were written out.
     */
    suspend fun forget(profile: String, to: File?): Int = mutex.withLock {
        var count = 0
        if (to != null) {
            for (o in state.outbox) {
                val r = o.revision ?: continue
                if (o.profile != profile || r.manifest.files.none { store.has(it.hash) }) continue
                writeOut(File(File(to, part(r.title.ifBlank { r.game })), part("${r.kind.label} ${r.id}")), r.manifest)
                count++
            }
            for ((k, m) in state.parked) {
                if (!k.startsWith("$profile|") || m.files.none { store.has(it.hash) }) continue
                val (_, game, kind) = k.split('|').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
                writeOut(File(File(to, part(game)), part("$kind parked")), m)
                count++
            }
        }
        state = state.copy(
            profile = state.profile.takeIf { it != profile },
            metas = state.metas - profile,
            pendingMeta = state.pendingMeta - profile,
            outbox = state.outbox.filterNot { it.profile == profile },
            parked = state.parked.filterKeys { !it.startsWith("$profile|") },
            slots = state.slots.filterKeys { !it.startsWith("$profile|") },
            holders = state.holders.mapValues { (_, v) -> if (v == profile) ORPHAN else v },
        )
        persist()
        count
    }

    /**
     * Leaving the host: each person's records become this device's own changes (so a host joined
     * later gets them as theirs), and nothing is agreed with any host any more. Saves waiting to go
     * stay, as does whose save each folder holds.
     */
    suspend fun detach() = mutex.withLock {
        state = state.copy(
            seq = 0,
            slots = emptyMap(),
            pendingMeta = (state.metas.keys + state.pendingMeta.keys).associateWith { meta(it) },
            metas = emptyMap(),
        )
        persist()
    }

    /**
     * Frees stored files nothing here needs any more: without a host, a save replaced by a newer
     * one before it was ever sent. Only for a device with no host (one with a host also keeps what
     * it is bringing down).
     */
    suspend fun collect(): Long = mutex.withLock {
        val used = HashSet<String>()
        state.outbox.forEach { o -> o.revision?.manifest?.files?.forEach { used += it.hash } }
        state.parked.values.forEach { m -> m.files.forEach { used += it.hash } }
        store.collect(used)
    }

    /** Copies [manifest]'s files (in the store) into [folder] as plain files. */
    private fun writeOut(folder: File, manifest: SaveManifest) {
        for (f in manifest.files) {
            if (!SavePath.isSafe(f.path) || !store.has(f.hash)) continue
            val target = File(folder, f.path)
            target.parentFile.mkdirs()
            store.fileOf(f.hash).copyTo(target, overwrite = true)
        }
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
            client?.revisions(profile, slot.game.id, slot.kind)?.firstOrNull { it.canBeNewest }
        } catch (e: SyncException) {
            if (e.code == "offline") return PrepareResult.Offline else throw e
        } ?: return if (client == null) PrepareResult.Offline else PrepareResult.Ready
        if (!SaveSlotFormats.compatible(head.manifest.format, slot.format)) return PrepareResult.Incompatible(head)
        val (manifest, _) = fingerprintOf(slot)
        val slotState = state.slots[keyOf(profile, slot)] ?: SlotState()
        val localChanged = manifest.files.isNotEmpty() && manifest.fingerprint != slotState.fingerprint
        // This person's saves queued from here and not yet sent are their newest (never anyone else's).
        val queued = state.outbox.lastOrNull { it.profile == profile && it.revision?.game == slot.game.id && it.revision.kind == slot.kind && it.revision.canBeNewest }?.revision
        return when (SyncRules.decide(slotState.base, localChanged || queued != null, head.id, sameContent = manifest.fingerprint == head.manifest.fingerprint)) {
            SyncDecision.UpToDate -> {
                // What is here stays the fingerprint to compare with: when it came from a save kept
                // in another shape (and was converted), the host's own would never match it.
                agree(keyOf(profile, slot), head.id, if (localChanged) head.manifest.fingerprint else slotState.fingerprint ?: head.manifest.fingerprint)
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
                slots = state.slots + (keyOf(profile, slot) to SlotState(remote.id, remote.manifest.fingerprint)),
                // This device's save goes up as one new step; copies kept for safety still go.
                outbox = state.outbox.filterNot { it.profile == profile && it.revision?.game == slot.game.id && it.revision.kind == slot.kind && it.revision.canBeNewest },
            )
            persist()
        }
        capture(profile, slot, playSeconds, Priority.LAUNCH)
        flush(client)
    }

    /** The person took the host's save: this device's is kept as a revision on the host first, then replaced. */
    suspend fun takeRemote(client: SyncClient, profile: String, slot: LocalSlot, remote: SaveRevision) {
        mutex.withLock {
            // Saves of this one still waiting to go lost to the host's: they go as the other side
            // of the conflict, never as the newest, and never as the start of what is played next.
            state = state.copy(outbox = state.outbox.map { o ->
                val r = o.revision
                if (o.profile == profile && r != null && r.game == slot.game.id && r.kind == slot.kind && r.canBeNewest) o.copy(revision = r.copy(reason = RevisionReason.CONFLICT_COPY)) else o
            })
            persist()
        }
        place(client, slot, remote)
    }

    /**
     * Brings back [revision], an older version: it is put in place (what is here kept first, as
     * history) and goes up as the newest, made from the host's newest, so every device takes it
     * next and the restore sticks. Returns the revision sent, or null when it already is the newest.
     */
    suspend fun restore(client: SyncClient, profile: String, slot: LocalSlot, revision: SaveRevision, playSeconds: Long = revision.playSeconds, title: String = ""): SaveRevision? {
        val head = client.revisions(profile, slot.game.id, slot.kind).firstOrNull { it.canBeNewest }
        place(client, slot, revision, profile)
        mutex.withLock {
            // Agreed with the host's newest: what is here now differs from it, so it goes up made from it.
            state = state.copy(slots = state.slots + (keyOf(profile, slot) to SlotState(head?.id, head?.manifest?.fingerprint)))
            persist()
        }
        return capture(profile, slot, playSeconds, Priority.LAUNCH, title)
    }

    /**
     * Puts [revision]'s files in place: each downloaded and checked, written beside its target and
     * then moved over it, after the files that were here were kept (as a revision marked as before
     * a restore, which is queued for the host). If anything fails part way, what was here stays.
     */
    suspend fun place(client: SyncClient, slot: LocalSlot, revision: SaveRevision, owner: String = revision.profile) {
        for (f in revision.manifest.files) client.download(f.hash, store)
        mutex.withLock {
            // The save as this emulator keeps it (converted when it came from one that keeps it differently).
            val incoming = inSlotFormat(slot, revision.manifest)
            // What is here now is kept first, so taking another save never loses this one.
            val (current, hashed) = fingerprintOf(slot)
            val alreadyGoing = state.outbox.any { o -> o.revision?.let { it.profile == owner && it.game == slot.game.id && it.kind == slot.kind && it.manifest.fingerprint == current.fingerprint } == true }
            if (current.files.isNotEmpty() && current.fingerprint != incoming.fingerprint && !alreadyGoing) {
                for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
                val kept = SaveRevision(
                    id = SyncCrypto.token(12), profile = owner, game = slot.game.id, kind = slot.kind,
                    parent = state.slots[keyOf(owner, slot)]?.base, device = deviceId, deviceName = deviceName, at = hlc.now(),
                    manifest = current, reason = RevisionReason.BEFORE_RESTORE,
                )
                state = state.copy(outbox = state.outbox + Outgoing(kept.id, Priority.SAVE.rank, owner, kept))
            }
            write(slot, incoming)
            state = state.copy(
                // Agreed as written here, so the converted copy isn't taken for a new save after playing.
                slots = state.slots + (keyOf(owner, slot) to SlotState(revision.id, incoming.fingerprint)),
                holders = state.holders + (slot.key to owner),
            )
            persist()
        }
    }

    /**
     * [manifest] in the shape [slot]'s emulator keeps it: as it is when that is already so, else
     * converted (a DraStic `.dsv` for a raw DS save, an N64 save's four files for RetroArch's one),
     * the converted files kept in the store.
     */
    private fun inSlotFormat(slot: LocalSlot, manifest: SaveManifest): SaveManifest {
        if (manifest.format == slot.format || !SaveConversions.canConvert(manifest.format, slot.format)) return manifest
        val bytes = manifest.files.associate { it.path to store.fileOf(it.hash).readBytes() }
        val out = SaveConversions.convert(manifest.format, slot.format, bytes)
            ?: throw IntegrityException("This save couldn't be turned into the shape this emulator reads")
        return SaveManifest(slot.format, out.map { (name, b) -> SaveFile(name, store.put(b), b.size.toLong()) }.sortedBy { it.path })
    }

    /**
     * Writes [manifest]'s files (already in the store) into [slot]: each copied beside its target,
     * checked, then moved over it. If anything fails part way, what was here stays.
     */
    private fun write(slot: LocalSlot, manifest: SaveManifest) {
        val staged = ArrayList<Pair<File, File>>()
        try {
            for (f in manifest.files) {
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
            val next = mutex.withLock { nextToSend() } ?: break
            val rev = next.revision ?: break
            val result = try {
                client.push(next.profile, rev, store)
            } catch (e: SyncException) {
                // Only a revision the host can never take leaves the queue (its files stay here). Anything
                // else (offline, a PIN to enter again, a clock to set, too many calls) keeps it waiting.
                if (e.code !in REFUSED_FOR_GOOD) throw e
                null
            }
            mutex.withLock {
                state = state.copy(outbox = state.outbox.filterNot { it.id == next.id })
                val key = "${rev.profile}|${rev.game}|${rev.kind.name}"
                if (result?.accepted == true && rev.reason != RevisionReason.BEFORE_RESTORE) {
                    state = state.copy(slots = state.slots + (key to SlotState(rev.id, rev.manifest.fingerprint)))
                }
                // One saved while this was on its way was made from the same base: it now follows this one.
                if (result?.accepted == true) {
                    state = state.copy(outbox = state.outbox.map { o ->
                        val r = o.revision
                        if (r != null && r.profile == rev.profile && r.game == rev.game && r.kind == rev.kind && r.parent == rev.parent) o.copy(revision = r.copy(parent = rev.id)) else o
                    })
                }
                persist()
            }
            sent++
        }
        return sent
    }

    /**
     * The most urgent revision waiting, but never ahead of the one it was made from: a save made
     * from another still waiting goes after it, so the host sees one line, not a conflict.
     */
    private fun nextToSend(): Outgoing? {
        var next = state.outbox.minByOrNull { it.priority } ?: return null
        val seen = HashSet<String>()
        while (seen.add(next.id)) {
            val parent = next.revision?.parent ?: break
            next = state.outbox.firstOrNull { it.revision?.id == parent } ?: break
        }
        return next
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

    /**
     * Writes every save still waiting to go into [to] as plain files
     * (`<profile>/<game>/<kind> <when>/<file>`), so they outlive this device's link to its host.
     * Returns how many saves were written.
     */
    suspend fun exportUnsent(to: File): Int = mutex.withLock {
        var count = 0
        for (o in state.outbox) {
            val r = o.revision ?: continue
            if (r.manifest.files.none { store.has(it.hash) }) continue
            writeOut(File(File(File(to, part(o.profile)), part(r.title.ifBlank { r.game })), part("${r.kind.label} ${r.id}")), r.manifest)
            count++
        }
        count
    }

    suspend fun saw(seq: Long) = mutex.withLock {
        if (seq > state.seq) {
            state = state.copy(seq = seq)
            persist()
        }
    }
}

/** Whose save a folder holds after its person's profile was removed here. */
internal const val ORPHAN = "~removed"

/** [text] as a safe folder name. */
private fun part(text: String) = text.replace(Regex("[^A-Za-z0-9 ._()-]+"), "_").trim('.', ' ').take(80).ifEmpty { "_" }

/** Answers that mean the host will never take a revision as it is: it leaves the queue (its files stay on the device). */
private val REFUSED_FOR_GOOD = setOf("bad-revision", "bad-body", "too-large", "too-many", "bad-hash")

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

/** The device's own files, for save adapters. [variables] are the environment's (XDG folders and the like). */
class FileSaveEnvironment(
    override val host: String,
    override val home: String = System.getProperty("user.home") ?: "",
    private val variables: (String) -> String? = System::getenv,
) : SaveEnvironment {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun list(path: String): List<String> = File(path).list()?.sorted().orEmpty()
    override fun readText(path: String, limit: Int): String? = runCatching {
        val f = File(path)
        if (!f.isFile || f.length() > limit) null else f.readText()
    }.getOrNull()
    override fun env(name: String): String? = variables(name)

    override fun modified(path: String): Long? {
        val f = File(path)
        if (!f.exists()) return null
        if (f.isFile) return f.lastModified()
        // A folder: its newest file, a few levels down (a game's save folder is small).
        var newest = f.lastModified()
        var seen = 0
        f.walkTopDown().maxDepth(MODIFIED_DEPTH).forEach { child ->
            if (++seen > MODIFIED_FILES) return newest
            if (child.isFile) newest = maxOf(newest, child.lastModified())
        }
        return newest
    }

    override fun storageRoots(): List<String> {
        if (host != "ANDROID") return emptyList()
        // The device's own storage, then every card and drive Android mounts beside it.
        val cards = File("/storage").listFiles().orEmpty()
            .filter { it.name != "self" && it.name != "emulated" && it.isDirectory && it.canRead() }
            .map { it.path }.sorted()
        return listOf("/storage/emulated/0") + cards
    }

    private val remembered = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

    override fun remember(key: String, compute: () -> List<String>): List<String> {
        val now = System.currentTimeMillis()
        remembered[key]?.let { (at, found) ->
            // Found folders are kept while they are all still there; "nothing" is asked again after a while.
            val fresh = if (found.isEmpty()) now - at < EMPTY_FOR_MS else found.all { File(it).exists() }
            if (fresh) return found
        }
        return compute().also { remembered[key] = now to it }
    }

    override fun readBytes(path: String, offset: Long, length: Int): ByteArray? = runCatching {
        java.io.RandomAccessFile(path, "r").use { f ->
            if (offset < 0 || offset >= f.length()) return@use null
            val n = minOf(length.toLong(), f.length() - offset).toInt()
            ByteArray(n).also { f.seek(offset); f.readFully(it) }
        }
    }.getOrNull()
}

/** How deep, and over how many files, a folder's last change is looked for. */
private const val MODIFIED_DEPTH = 6
private const val MODIFIED_FILES = 5_000

/** How long "no such folder" is believed before storage is searched again. */
private const val EMPTY_FOR_MS = 10 * 60_000L
