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
    /** A folder save's empty folders, by their path inside [root]: part of its structure. */
    val folders: List<String> = emptyList(),
    /** Other folders the save keeps parts in, by the name prefix its files carry there (see [SaveSpot.mounts]). */
    val mounts: Map<String, File> = emptyMap(),
    /**
     * For a save worked on through a copy (one in a folder granted through the system's picker, see
     * [TreeMirror]): puts the copy's changes back where the emulator reads them. Runs before any
     * change Fuse makes is counted as done, so a failure undoes the change instead.
     */
    val sink: (() -> Unit)? = null,
    /** The game's own folders, as [SaveSpot.learnedAs] says, to send with its saves. */
    val learnedAs: List<String> = emptyList(),
) {
    val key: String get() = "${game.id}|${kind.name}"

    /** Where the file named [name] goes: its own target, else inside its part's folder or [root] (never outside it). */
    fun target(name: String): File? {
        targets[name]?.let { return it }
        if (!SavePath.isSafe(name)) return null
        mounts.entries.firstOrNull { name.startsWith(it.key + "/") }?.let { (prefix, dir) ->
            val f = File(dir, name.removePrefix("$prefix/"))
            return f.takeIf { it.canonicalPath.startsWith(dir.canonicalPath + File.separator) }
        }
        val f = File(root, name)
        return f.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    /** The folder [file] must stay inside (and no link may lead out of): its part's, else [root]. */
    fun boundaryOf(file: File): File {
        val path = file.absoluteFile.path
        return mounts.values.firstOrNull { path.startsWith(it.absoluteFile.path + File.separator) } ?: root
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
    /** A blank save already told about (its fingerprint): the same one found again says nothing. */
    val blank: String? = null,
)

/** Something waiting to go to the host: a revision (files already in the device's store) or records. */
@Serializable
internal data class Outgoing(val id: String, val priority: Int, val profile: String, val revision: SaveRevision? = null, val tries: Int = 0)

/**
 * A newer save the host has for one person's game that this device knows of but hasn't put in
 * place yet (its download didn't finish): made on [deviceName] at [at].
 */
@Serializable
data class Awaited(val revision: String, val deviceName: String, val at: Long)

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
    /** Newer saves the host has that didn't come down yet, keyed `profile|game|kind` ([Awaited]). */
    val awaiting: Map<String, Awaited> = emptyMap(),
    /** Each person's saves from before imports replaced them, newest last, keyed `profile|game|kind` ([ImportBackup]). */
    val importBackups: Map<String, List<ImportBackup>> = emptyMap(),
)

/**
 * A save as it was before an import (or an undo) replaced it, kept on this device by content, so it
 * can be put back without a host: [at] in milliseconds, [title] the game's.
 */
@Serializable
data class ImportBackup(val id: String, val at: Long, val manifest: SaveManifest, val title: String = "")

/** The part of a save an import brings: all of it, or one part of a Switch save (the other stays the owner's). */
enum class SavePart { WHOLE, SWITCH_ACCOUNT, SWITCH_DEVICE }

/** How many backups from before imports each save keeps on a device. */
internal const val KEEP_IMPORT_BACKUPS = 5

/** What a save imported straight to Fuse One says it is ([SyncDevice.importToFuseOne]). */
internal const val TO_FUSE_ONE = "Imported to Fuse One"

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
    private val importJournal = File(dir, "save-import-transaction.json")
    private val handoverJournal = File(dir, "save-handover-transaction.json")
    val store = ContentStore(File(dir, "objects"))
    private val mutex = Mutex()
    private val hlc = HlcClock(deviceId, clock)
    private var state: DeviceState = migrated(
        if (stateFile.isFile) runCatching { json.decodeFromString(DeviceState.serializer(), stateFile.readText()) }.getOrDefault(DeviceState()) else DeviceState(),
    )

    init {
        SaveImportTransaction.recover(importJournal) { revision -> state.outbox.any { it.revision?.id == revision } }
        // An interrupted profile swap is undone (or finished) before anything else; one that needs a
        // person's eye stays journaled, and the next handover refuses to launch until it is resolved.
        runCatching { SaveImportTransaction.recover(handoverJournal, ::handoverCommitted) }
        // Device clocks cannot restart at wall time behind a revision they already accepted.
        (state.metas.values + state.pendingMeta.values).forEach { hlc.seen(it.latestRevision()) }
    }

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

    /** The games [profile] has a save agreed with the host for on this device. */
    fun gamesWithSaves(profile: String): Set<String> =
        state.slots.keys.mapNotNull { k -> k.split('|').takeIf { it.size == 3 && it[0] == profile }?.get(1) }.toSet()

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

    /**
     * The newer save the host has for [profile]'s [slot] that this device knows of but couldn't
     * bring down yet, or null. Kept across restarts, so a launch while offline knows not to start
     * quietly with an older save.
     */
    fun awaited(profile: String, slot: LocalSlot): Awaited? = state.awaiting[keyOf(profile, slot)]

    /** Remembers that [game]'s saves of [format] are in [folders] (a play changed exactly those). */
    suspend fun learn(game: String, format: String, folders: List<String>) = mutex.withLock {
        state = state.copy(learned = state.learned + ("$game|$format" to folders.sorted()))
        persist()
    }

    suspend fun useProfile(id: String?) = mutex.withLock {
        state = state.copy(profile = id)
        persist()
    }

    /** True once this device has had [profile]'s records from the host (it can switch to them offline). */
    fun knows(profile: String): Boolean = state.metas.containsKey(profile)

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

    /** Told when a blank save was found where a real one was, and kept from going anywhere (see [capture]). */
    @Volatile var onBlank: ((LocalSlot) -> Unit)? = null

    // ---------------------------------------------------------------- saves

    /** True when every file of a save holds nothing (see [Slots.blank]). */
    private fun allBlank(hashed: List<Pair<LocalFile, String>>): Boolean = hashed.isNotEmpty() && hashed.all { (lf, _) -> Slots.blank(lf.file) }

    private fun fingerprintOf(slot: LocalSlot): Pair<SaveManifest, List<Pair<LocalFile, String>>> {
        val hashed = slot.files.filter { it.file.isFile && SavePath.isSafe(it.path) }.map { lf -> lf to lf.file.inputStream().use { SyncCrypto.sha256(it) } }
        val manifest = SaveManifest(slot.format, hashed.map { (lf, h) -> SaveFile(lf.path, h, lf.file.length()) }.sortedBy { it.path }, slot.folders.filter(SavePath::isSafe).sorted())
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
        // A blank save over one this device already had (save memory the emulator made again, all
        // zeros or all 0xFF): never sent as the newest, so it can't replace a real save anywhere.
        val hadOne = slotState.base != null || slotState.fingerprint != null ||
            state.outbox.any { o -> o.revision?.let { it.profile == profile && it.game == slot.game.id && it.kind == slot.kind } == true }
        // A blank save never goes anywhere: save memory an emulator just made (all zeros or all
        // 0xFF) holds no progress, and sent as the first save it stood as the newest, so a real
        // save from another device then came back as a conflict with nothing. It is told once:
        // the look for saves made outside Fuse finds the same file every few minutes.
        if (allBlank(hashed)) {
            if (hadOne && slotState.blank != manifest.fingerprint) {
                state = state.copy(slots = state.slots + (keyOf(profile, slot) to slotState.copy(blank = manifest.fingerprint)))
                persist()
                onBlank?.invoke(slot)
            }
            return@withLock null
        }
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
            learned = slot.learnedAs.ifEmpty { learned(slot.game.id, slot.format) },
        )
        state = state.copy(
            outbox = state.outbox.filterNot { it.id == replaces?.id } + Outgoing(revision.id, priority.rank, profile, revision),
            holders = state.holders + (slot.key to profile),
        )
        persist()
        revision
    }

    /**
     * Imports one save for [owners] (each gets their own copy), touching no one else's. The
     * emulator's folder is written only for the person whose save is in it now, or when no one's
     * is; then [active] (else the last owner) gets it there. While someone else's save is in the
     * folder, the import is set aside for each owner ([importSaveParked]) and goes into place at
     * their next launch ([handover]), so the person whose save is in the folder keeps it exactly.
     */
    suspend fun importFor(owners: List<String>, active: String?, slot: LocalSlot, format: String, files: Map<String, File>, title: String = "", part: SavePart = SavePart.WHOLE): List<SaveRevision> {
        require(owners.isNotEmpty()) { "Choose whose saves these are before importing" }
        val holder = mutex.withLock { state.holders[slot.key] }
        val here = if (holder != null) owners.firstOrNull { it == holder } else owners.firstOrNull { it == active } ?: owners.last()
        val first = here?.let { importSave(it, slot, format, files, title, part) }
        return listOfNotNull(first) + owners.filter { it != here }.map { importSaveParked(it, slot, format, files, title, part) }
    }

    /**
     * An explicit import is a normal child revision, with Imported provenance, written into the
     * folder for [profile], whose save is in it (or no one's is). What it replaces is kept first:
     * as a "Before import" revision in their history and as a backup on this device
     * ([importBackups]) that can be put back without a host. A save in the folder that no one owns,
     * on a device others keep saves on, is never taken for [profile]'s: it is set aside as unowned
     * ([UNCLAIMED]), as [handover] does. [part] is the part of a Switch save [files] are; the
     * other part stays as [profile] has it.
     */
    suspend fun importSave(profile: String, slot: LocalSlot, format: String, files: Map<String, File>, title: String = "", part: SavePart = SavePart.WHOLE): SaveRevision = mutex.withLock {
        SaveImportTransaction.recover(importJournal) { id -> state.outbox.any { it.revision?.id == id } }
        require(profile.isNotBlank() && slot.available) { "Choose a profile and an available save destination" }
        val holder = state.holders[slot.key]
        require(holder == null || holder == profile) { "Someone else's save is in this game's folder; the import is set aside for this profile instead" }
        val (current, _) = fingerprintOf(slot)
        val unowned = holder == null && current.files.isNotEmpty() && (saveKeepers() - profile).isNotEmpty()
        val own = (if (unowned) null else current.takeIf { it.files.isNotEmpty() }) ?: state.parked[keyOf(profile, slot)]
        putInFolder(profile, slot, withRest(imported(slot, format, files), part, own), title, "Imported", unowned)
    }

    /**
     * The same imported save for [profile], while [slot]'s folder holds someone else's: an Imported
     * revision of [profile]'s, and the save set aside for them on this device, so it is in the
     * folder when they play here, even offline. What they had set aside is kept first, in their
     * history and as a backup on this device ([importBackups]).
     */
    suspend fun importSaveParked(profile: String, slot: LocalSlot, format: String, files: Map<String, File>, title: String = "", part: SavePart = SavePart.WHOLE): SaveRevision = mutex.withLock {
        require(profile.isNotBlank() && slot.available) { "Choose a profile and an available save destination" }
        require(state.holders[slot.key] != profile) { "This save's folder is already this profile's" }
        setAside(profile, slot, withRest(imported(slot, format, files), part, state.parked[keyOf(profile, slot)]), title, "Imported")
    }

    /**
     * An imported save of [game] for [profile] that has no folder on this device (the emulator's
     * can't be reached here): it goes to Fuse One as their newest, made from [head] (the host's
     * newest, so it follows it rather than conflicting), whose other part a Switch backup's one part
     * keeps ([SavePart]). A device that can reach the emulator's folder puts it in place.
     */
    suspend fun importToFuseOne(client: SyncClient, profile: String, game: GameKey, kind: SaveKind, format: String, files: Map<String, File>,
        head: SaveRevision?, title: String = "", part: SavePart = SavePart.WHOLE): SaveRevision {
        require(profile.isNotBlank()) { "Choose whose save this is" }
        require(files.isNotEmpty() && files.keys.all(SavePath::isSafe)) { "This save has no safe files" }
        // The other part kept from the host's newest has to be here to go with it.
        if (part != SavePart.WHOLE && head != null) SyncClient.inParallel(head.manifest.files.map { it.hash }.distinct().filterNot(store::has)) { client.download(it, store) }
        return mutex.withLock {
            val incoming = SaveManifest(format, files.map { (name, f) ->
                require(f.isFile) { "The imported save is no longer available" }
                SaveFile(name, f.inputStream().use { store.put(it) }, f.length())
            }.sortedBy { it.path })
            val revision = SaveRevision(SyncCrypto.token(12), profile, game.id, kind, head?.id, deviceId, deviceName, hlc.now(), withRest(incoming, part, head?.manifest?.takeIf { it.format == format }),
                playSeconds = meta(profile).game(game).totalSeconds, title = title, provenance = TO_FUSE_ONE)
            state = state.copy(outbox = state.outbox + Outgoing(revision.id, Priority.SAVE.rank, profile, revision))
            persist()
            revision
        }
    }

    /** [profile]'s save of [game] waiting here to go to the host, made when it was (ms); null when none is. */
    fun waitingSave(profile: String, game: GameKey, kind: SaveKind): Long? =
        state.outbox.lastOrNull { it.revision?.let { r -> r.profile == profile && r.game == game.id && r.kind == kind && r.canBeNewest } == true }?.revision?.at?.millis

    /** [incoming] (in the store) written into [slot]'s folder for [profile]; see [importSave]. Under [mutex]. */
    private fun putInFolder(profile: String, slot: LocalSlot, incoming: SaveManifest, title: String, provenance: String, unowned: Boolean = false): SaveRevision {
        val (current, hashed) = fingerprintOf(slot)
        val waiting = state.outbox.lastOrNull { it.revision?.let { r -> r.profile == profile && r.game == slot.game.id && r.kind == slot.kind && r.canBeNewest } == true }?.revision
        val revision = SaveRevision(SyncCrypto.token(12), profile, slot.game.id, slot.kind,
            waiting?.id ?: state.slots[keyOf(profile, slot)]?.base, deviceId, deviceName, hlc.now(), incoming,
            playSeconds = meta(profile).game(slot.game).totalSeconds, title = title, provenance = provenance)
        if (current.files.isNotEmpty() && current.fingerprint != incoming.fingerprint) {
            for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
            state = if (unowned) {
                // Not anyone's to replace: set aside for whoever claims it, an older unowned one kept as a backup.
                val before = state.parked[keyOf(UNCLAIMED, slot)]?.takeIf { it.fingerprint != current.fingerprint }
                (before?.let { state.backedUp(UNCLAIMED, slot, it, title) } ?: state).backedUp(UNCLAIMED, slot, current, title)
                    .let { it.copy(parked = it.parked + (keyOf(UNCLAIMED, slot) to current)) }
            } else {
                val kept = SaveRevision(SyncCrypto.token(12), profile, slot.game.id, slot.kind,
                    state.slots[keyOf(profile, slot)]?.base, deviceId, deviceName, hlc.now(), current,
                    reason = RevisionReason.BEFORE_RESTORE, title = title, provenance = "Before import")
                state.backedUp(profile, slot, current, title).let { it.copy(outbox = it.outbox + Outgoing(kept.id, Priority.SAVE.rank, profile, kept)) }
            }
            persist()
        }
        // Theirs set aside here is replaced too: kept in their history and as a backup first.
        state.parked[keyOf(profile, slot)]?.takeIf { it.files.isNotEmpty() && it.fingerprint != incoming.fingerprint && it.fingerprint != current.fingerprint }?.let { theirs ->
            val kept = SaveRevision(SyncCrypto.token(12), profile, slot.game.id, slot.kind, state.slots[keyOf(profile, slot)]?.base, deviceId, deviceName, hlc.now(), theirs,
                reason = RevisionReason.BEFORE_RESTORE, title = title, provenance = "Before import")
            state = state.backedUp(profile, slot, theirs, title).let { it.copy(outbox = it.outbox + Outgoing(kept.id, Priority.SAVE.rank, profile, kept)) }
            persist()
        }
        val beforeImport = state
        SaveImportTransaction.write(slot, incoming, store, journal = importJournal, revision = revision.id, expectedOriginal = current, commit = {
            slot.sink?.invoke()
            state = state.copy(outbox = state.outbox + Outgoing(revision.id, Priority.SAVE.rank, profile, revision),
                holders = state.holders + (slot.key to profile), parked = state.parked - keyOf(profile, slot))
            try {
                persist()
            } catch (e: Exception) {
                state = beforeImport
                try { persist() } catch (restore: Exception) { e.addSuppressed(restore) }
                throw e
            }
        })
        return revision
    }

    /** [incoming] (in the store) set aside for [profile], whose save isn't in the folder; see [importSaveParked]. Under [mutex]. */
    private fun setAside(profile: String, slot: LocalSlot, incoming: SaveManifest, title: String, provenance: String): SaveRevision {
        var next = state
        next.parked[keyOf(profile, slot)]?.takeIf { it.files.isNotEmpty() && it.fingerprint != incoming.fingerprint }?.let { theirs ->
            val kept = SaveRevision(SyncCrypto.token(12), profile, slot.game.id, slot.kind, next.slots[keyOf(profile, slot)]?.base, deviceId, deviceName, hlc.now(), theirs,
                reason = RevisionReason.BEFORE_RESTORE, title = title, provenance = "Before import")
            next = next.backedUp(profile, slot, theirs, title).let { it.copy(outbox = it.outbox + Outgoing(kept.id, Priority.SAVE.rank, profile, kept)) }
        }
        val waiting = next.outbox.lastOrNull { it.revision?.let { r -> r.profile == profile && r.game == slot.game.id && r.kind == slot.kind && r.canBeNewest } == true }?.revision
        val revision = SaveRevision(SyncCrypto.token(12), profile, slot.game.id, slot.kind,
            waiting?.id ?: next.slots[keyOf(profile, slot)]?.base, deviceId, deviceName, hlc.now(), incoming,
            playSeconds = meta(profile).game(slot.game).totalSeconds, title = title, provenance = provenance)
        state = next.copy(outbox = next.outbox + Outgoing(revision.id, Priority.SAVE.rank, profile, revision),
            parked = next.parked + (keyOf(profile, slot) to incoming))
        persist()
        return revision
    }

    /**
     * [incoming], one part of a Switch save, made whole with the other part of [own] (the owner's
     * own save): an account backup keeps their device save, a device backup their account save.
     */
    private fun withRest(incoming: SaveManifest, part: SavePart, own: SaveManifest?): SaveManifest {
        if (part == SavePart.WHOLE || own == null) return incoming
        val device = "${SwitchNand.DEVICE}/"
        val other: (String) -> Boolean = if (part == SavePart.SWITCH_DEVICE) { p -> !p.startsWith(device) } else { p -> p.startsWith(device) }
        val rest = own.files.filter { other(it.path) && store.has(it.hash) && incoming.files.none { f -> f.path == it.path } }
        if (rest.isEmpty()) return incoming
        return incoming.copy(files = (incoming.files + rest).sortedBy { it.path }, folders = (incoming.folders + own.folders.filter(other)).distinct().sorted())
    }

    /** [manifest] kept as a backup of [profile]'s save from before an import or undo, the newest few for each save. */
    private fun DeviceState.backedUp(profile: String, slot: LocalSlot, manifest: SaveManifest, title: String): DeviceState {
        val key = keyOf(profile, slot)
        val list = (importBackups[key].orEmpty() + ImportBackup(SyncCrypto.token(12), clock(), manifest, title)).takeLast(KEEP_IMPORT_BACKUPS)
        return copy(importBackups = importBackups + (key to list))
    }

    /** The saves of [profile]'s kept on this device from before imports into [slot], newest first. */
    fun importBackups(profile: String, slot: LocalSlot): List<ImportBackup> = state.importBackups[keyOf(profile, slot)].orEmpty().reversed()

    /**
     * Puts [profile]'s backup [id] (see [importBackups]) back, with no host needed: into the folder
     * when their save is in it (or no one's is), else set aside for their next launch. What it
     * replaces is kept as a backup first, so an undo can be undone.
     */
    suspend fun restoreImportBackup(profile: String, slot: LocalSlot, id: String): SaveRevision = mutex.withLock {
        SaveImportTransaction.recover(importJournal) { r -> state.outbox.any { it.revision?.id == r } }
        require(slot.available) { "The save's folder isn't available" }
        val backup = state.importBackups[keyOf(profile, slot)]?.firstOrNull { it.id == id } ?: error("That backup is gone")
        require(backup.manifest.files.all { store.has(it.hash) }) { "That backup's files are gone" }
        val holder = state.holders[slot.key]
        if (holder == profile || holder == null && (saveKeepers() - profile).isEmpty()) putInFolder(profile, slot, backup.manifest, backup.title, "Restored from before import")
        else setAside(profile, slot, backup.manifest, backup.title, "Restored from before import")
    }

    /**
     * Whether [profile] already has a save of [slot]'s game here, and where: in the emulator's
     * folder, set aside on this device, waiting to go to the host, or agreed with the host.
     */
    fun existingSave(profile: String, slot: LocalSlot): ExistingSave? {
        val s = state
        val holder = s.holders[slot.key]
        val inFolder = if (slot.available) slot.files.filter { it.file.isFile } else emptyList()
        if (inFolder.isNotEmpty() && (holder == profile || holder == null && (saveKeepers() - profile).isEmpty()))
            return ExistingSave(ExistingSave.Where.IN_FOLDER, inFolder.maxOf { it.file.lastModified() })
        if (s.parked[keyOf(profile, slot)]?.files?.isNotEmpty() == true) return ExistingSave(ExistingSave.Where.SET_ASIDE, null)
        s.outbox.lastOrNull { it.revision?.let { r -> r.profile == profile && r.game == slot.game.id && r.kind == slot.kind && r.canBeNewest } == true }?.revision?.let {
            return ExistingSave(ExistingSave.Where.WAITING, it.at.millis)
        }
        if (s.slots[keyOf(profile, slot)]?.base != null) return ExistingSave(ExistingSave.Where.SYNCED, null)
        return null
    }

    /** An imported save's files, put in the store, in [slot]'s own format. */
    private fun imported(slot: LocalSlot, format: String, files: Map<String, File>): SaveManifest {
        require(SaveSlotFormats.compatible(format, slot.format)) { "This save format is not compatible with the selected emulator" }
        require(files.isNotEmpty() && files.keys.all(SavePath::isSafe)) { "This save has no safe files" }
        val source = SaveManifest(format, files.map { (name, f) ->
            require(f.isFile) { "The imported save is no longer available" }
            SaveFile(name, f.inputStream().use { store.put(it) }, f.length())
        }.sortedBy { it.path })
        return inSlotFormat(slot, source)
    }

    /**
     * Makes [slot]'s folder hold [profile]'s save before they play, on a device more than one person
     * uses. Whoever played last keeps theirs: anything they changed is captured for them first, and
     * their save is parked here by content. Then this person's own comes back from where it was
     * parked, or, if they never played this game here, the folder is emptied so they start their own
     * game (nothing is deleted: every file is in the device's store).
     *
     * The swap is one recoverable transaction ([SaveImportTransaction]): every file is staged and
     * backed up, a journal is written before the first destination changes, and the new owner is
     * recorded in the same commit. A crash at any step is rolled back on the next start; a file the
     * emulator changed meanwhile stops recovery with both copies kept. Any failure is thrown, so the
     * caller never launches with the wrong person's save.
     *
     * A save already in the folder that no profile owns is never given to whoever launches first
     * when [othersHere] (someone else already has saves held or parked on this device): it is parked
     * as [UNCLAIMED] until a person claims it ([claimUnclaimed]). On a device only this person has
     * used, it is theirs, as before.
     */
    suspend fun handover(profile: String, slot: LocalSlot, holderSeconds: (String) -> Long = { 0L }, orphanTo: File? = null, othersHere: Boolean = false) {
        if (!slot.available) return
        mutex.withLock { SaveImportTransaction.recover(handoverJournal, ::handoverCommitted) }
        val holder = state.holders[slot.key]
        if (holder == profile) return
        if (holder == null) {
            val (current, _) = mutex.withLock { fingerprintOf(slot) }
            if (!othersHere || current.files.isEmpty()) {
                // The first person to play it here with Fuse Sync, alone on this device: what is in the folder is theirs.
                mutex.withLock {
                    state = state.copy(holders = state.holders + (slot.key to profile))
                    persist()
                }
                return
            }
        }
        // A removed profile's save has no one to keep it for: it goes to [orphanTo] as plain files.
        val from = holder ?: UNCLAIMED
        val orphan = from == ORPHAN
        if (!orphan && from != UNCLAIMED) capture(from, slot, holderSeconds(from), Priority.SAVE)
        mutex.withLock {
            val (current, hashed) = fingerprintOf(slot)
            for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
            if (orphan && orphanTo != null && current.files.isNotEmpty()) writeOut(File(File(orphanTo, part(slot.game.id)), part(slot.kind.label)), current)
            val mine = state.parked[keyOf(profile, slot)]?.takeIf { m -> m.files.all { store.has(it.hash) } }
            val incoming = mine ?: SaveManifest(slot.format, emptyList())
            // Only files Fuse Sync can carry take part: a name it can't keep is never touched.
            val carried = slot.copy(files = hashed.map { it.first })
            var parked = state.parked - keyOf(profile, slot)
            if (current.files.isNotEmpty() && (!orphan || orphanTo == null)) parked = parked + (keyOf(from, slot) to current)
            val before = state
            val leftFolders = slot.folders.filter { it !in incoming.folders }
            SaveImportTransaction.write(carried, incoming, store, journal = handoverJournal, revision = "${slot.key}>$profile",
                expectedOriginal = current, beforeMove = handoverFault, commit = {
                    slot.sink?.invoke()
                    state = state.copy(parked = parked, holders = state.holders + (slot.key to profile))
                    try {
                        persist()
                    } catch (e: Exception) {
                        state = before
                        runCatching { persist() }.onFailure(e::addSuppressed)
                        throw e
                    }
                })
            // The folders the last person's save made go too, when empty, so theirs never shapes this one's.
            val gone = leftFolders.mapNotNull(slot::target).filter { it.isDirectory && it.list()?.isEmpty() == true }
            gone.forEach { it.delete() }
            prune(slot, hashed.map { it.first.file } + gone, keep = incoming.folders)
        }
    }

    /** Everyone whose saves this device holds in a folder or keeps parked: the people a found save might belong to. */
    fun saveKeepers(): Set<String> = (state.holders.values + state.parked.keys.map { it.substringBefore('|') }).toSet() - ORPHAN - UNCLAIMED

    /** The handover journal's [revision] is `slot key>profile`: it committed once that profile holds the slot. */
    private fun handoverCommitted(revision: String): Boolean =
        state.holders[revision.substringBeforeLast('>')] == revision.substringAfterLast('>')

    /** Test hook: runs before each file moves in a handover, to interrupt it at that step. */
    internal var handoverFault: (Int) -> Unit = {}

    /** A save of [slot]'s game that no profile owned, parked aside on a shared device, when there is one. */
    fun unclaimed(slot: LocalSlot): SaveManifest? = state.parked[keyOf(UNCLAIMED, slot)]

    /** Every save kept aside because no profile owned it: its game's id, kind and files. */
    fun unclaimedSaves(): List<Triple<String, SaveKind, SaveManifest>> = state.parked.mapNotNull { (key, manifest) ->
        if (key.substringBefore('|') != UNCLAIMED) return@mapNotNull null
        val rest = key.substringAfter('|')
        val kind = SaveKind.entries.firstOrNull { it.name == rest.substringAfterLast('|') } ?: return@mapNotNull null
        Triple(rest.substringBeforeLast('|'), kind, manifest)
    }

    /** [profile] claims the unowned save of [game]'s [kind] (see [claimUnclaimed]). */
    suspend fun claimUnclaimed(profile: String, game: GameKey, kind: SaveKind): Boolean =
        claimUnclaimed(profile, LocalSlot(game, kind, "", File("."), emptyList()))

    /**
     * [profile] says the unowned save of [slot]'s game is theirs: it becomes their parked save
     * (theirs already parked here is kept in the store's history as a revision first), and goes into
     * place at their next [handover].
     */
    suspend fun claimUnclaimed(profile: String, slot: LocalSlot): Boolean = mutex.withLock {
        val found = state.parked[keyOf(UNCLAIMED, slot)] ?: return@withLock false
        state.parked[keyOf(profile, slot)]?.let { theirs ->
            val kept = SaveRevision(SyncCrypto.token(12), profile, slot.game.id, slot.kind, state.slots[keyOf(profile, slot)]?.base,
                deviceId, deviceName, hlc.now(), theirs, reason = RevisionReason.BEFORE_RESTORE, provenance = "Before claiming an unowned save")
            state = state.copy(outbox = state.outbox + Outgoing(kept.id, Priority.SAVE.rank, profile, kept))
        }
        state = state.copy(parked = state.parked - keyOf(UNCLAIMED, slot) + (keyOf(profile, slot) to found))
        persist()
        true
    }

    /**
     * What this device keeps for [profile] on its own, without a host: each game it has play time or
     * saves for, and each save as it is kept here (the newest, waiting for a host, and one set aside
     * while someone else plays). Read only; the Sync page shows it while there is no household.
     */
    fun localReport(profile: ProfileInfo): ProfileReport {
        val s = state
        val meta = meta(profile.id)
        val revisions = s.outbox.mapNotNull { it.revision }.filter { it.profile == profile.id }
        // Parked saves are kept by `profile|game|KIND`.
        val parked = s.parked.mapNotNull { (key, manifest) ->
            if (key.substringBefore('|') != profile.id) return@mapNotNull null
            val rest = key.substringAfter('|')
            val kind = SaveKind.entries.firstOrNull { it.name == rest.substringAfterLast('|') } ?: return@mapNotNull null
            Triple(rest.substringBeforeLast('|'), kind, manifest)
        }
        fun files(m: SaveManifest) = m.files.map { FileReport(it.path, it.size, "") }
        fun stored(m: SaveManifest) = m.files.maxOfOrNull { f -> if (store.has(f.hash)) store.fileOf(f.hash).lastModified() else 0L } ?: 0L
        val ids = (meta.games.keys + revisions.map { it.game } + parked.map { it.first }).distinct()
        val games = ids.mapNotNull { id ->
            val record = meta.games[id]
            val kinds = (revisions.filter { it.game == id }.map { it.kind } + parked.filter { it.first == id }.map { it.second }).distinct()
            val slots = kinds.map { kind ->
                val versions = revisions.filter { it.game == id && it.kind == kind }.map { r ->
                    VersionReport(r.id, r.device, r.deviceName, r.at.millis, r.playSeconds, r.reason, current = false, bytes = r.manifest.size, files = files(r.manifest), kept = r.pinned, provenance = r.provenance)
                } + parked.filter { it.first == id && it.second == kind }.map { (_, _, m) ->
                    VersionReport("parked:$id:${kind.name}", deviceId, deviceName, stored(m), 0, RevisionReason.PLAYED, current = false, bytes = m.size, files = files(m))
                }
                val newest = versions.sortedByDescending { it.at }
                val bytes = (revisions.filter { it.game == id && it.kind == kind }.flatMap { it.manifest.files } + parked.filter { it.first == id && it.second == kind }.flatMap { it.third.files })
                    .distinctBy { it.hash }.sumOf { it.size }
                val format = revisions.firstOrNull { it.game == id && it.kind == kind }?.manifest?.format
                    ?: parked.first { it.first == id && it.second == kind }.third.format
                SlotReport(kind, format, bytes, newest.mapIndexed { i, v -> if (i == 0) v.copy(current = true) else v })
            }
            val seconds = record?.totalSeconds ?: 0L
            if (slots.isEmpty() && seconds <= 0L) return@mapNotNull null
            val name = record?.title?.value ?: record?.name ?: revisions.firstOrNull { it.game == id && it.title.isNotBlank() }?.title ?: id.substringAfter(':')
            GameReport(
                game = id, name = name, platform = record?.key?.platform ?: id.substringBefore(':'),
                playSeconds = seconds, devicePlay = record?.playSeconds.orEmpty(), sessions = record?.sessions?.size ?: 0,
                lastPlayed = record?.lastPlayed, favorite = record?.favorite?.value == true, slots = slots,
            )
        }.sortedByDescending { g -> maxOf(g.lastPlayed ?: 0L, g.slots.flatMap { it.versions }.maxOfOrNull { it.at } ?: 0L) }
        val devicePlay = HashMap<String, Long>()
        games.forEach { g -> g.devicePlay.forEach { (d, sec) -> devicePlay[d] = (devicePlay[d] ?: 0L) + sec } }
        val savesBytes = (revisions.flatMap { it.manifest.files } + parked.flatMap { it.third.files }).distinctBy { it.hash }.sumOf { it.size }
        return ProfileReport(profile, games.sumOf { it.playSeconds }, devicePlay, emptyList(), games, savesBytes, store.root.path, local = true)
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
    } - ORPHAN - UNCLAIMED - ""

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
            awaiting = state.awaiting.moved { a, b -> if (a.at >= b.at) a else b },
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
        SaveImportTransaction.recover(importJournal) { id -> state.outbox.any { it.revision?.id == id } }
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
            awaiting = state.awaiting.filterKeys { !it.startsWith("$profile|") },
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
            awaiting = emptyMap(),
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
        state.importBackups.values.forEach { list -> list.forEach { b -> b.manifest.files.forEach { used += it.hash } } }
        store.collect(used)
    }

    /** Copies [manifest]'s files (in the store) into [folder] as plain files. */
    private fun writeOut(folder: File, manifest: SaveManifest) {
        for (d in manifest.folders) if (SavePath.isSafe(d)) File(folder, d).mkdirs()
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
    /**
     * [prepare] in the background, after another device saved: only when the folder holds this
     * person's save (or no one's yet). Someone else's save in the folder is theirs until the next
     * launch swaps it; a conflict is left for the person, exactly as it is.
     */
    suspend fun converge(client: SyncClient, profile: String, slot: LocalSlot): PrepareResult? {
        val holder = holderOf(slot)
        if (holder != null && holder != profile) return null
        return prepare(client, profile, slot)
    }

    /** What to tell the host about [slot] after [result]: which revision is in place here, and how it stands. */
    fun appliedNote(profile: String, slot: LocalSlot, result: PrepareResult, at: Long): AppliedNote? {
        val base = state.slots[keyOf(profile, slot)]?.base
        val (revision, st) = when (result) {
            PrepareResult.Ready -> base to AppliedState.CURRENT
            is PrepareResult.Updated -> result.revision.id to AppliedState.CURRENT
            is PrepareResult.Conflict -> base to AppliedState.CONFLICT
            is PrepareResult.Incompatible -> base to AppliedState.INCOMPATIBLE
            PrepareResult.Unavailable -> base to AppliedState.UNAVAILABLE
            PrepareResult.Offline -> return null
        }
        return AppliedNote(profile, slot.game.id, slot.kind, revision, st, at)
    }

    suspend fun prepare(client: SyncClient?, profile: String, slot: LocalSlot): PrepareResult {
        if (!slot.available) return PrepareResult.Unavailable
        val head = try {
            client?.revisions(profile, slot.game.id, slot.kind)?.firstOrNull { it.canBeNewest }
        } catch (e: SyncException) {
            if (e.code == "offline") return PrepareResult.Offline else throw e
        } ?: return if (client == null) PrepareResult.Offline else PrepareResult.Ready
        val (manifest, hashed) = fingerprintOf(slot)
        val slotState = state.slots[keyOf(profile, slot)] ?: SlotState()
        if (!SaveSlotFormats.compatible(head.manifest.format, slot.format)) {
            // Seen, though it can't be used here: this device's save stays as it is, and the next
            // one made here follows the host's newest, as the newest. Without this, every save
            // made here went up as a conflict copy that no device ever took.
            if (slotState.base != head.id) {
                val old = slotState.base
                mutex.withLock {
                    state = state.copy(
                        // The files as they were stay the comparison: a change made here and not yet
                        // kept is still found and sent.
                        slots = state.slots + (keyOf(profile, slot) to SlotState(head.id, slotState.fingerprint)),
                        awaiting = state.awaiting - keyOf(profile, slot),
                        // Saves of this one still waiting to go now follow the host's newest too.
                        outbox = state.outbox.map { o ->
                            val r = o.revision
                            if (r != null && o.profile == profile && r.game == slot.game.id && r.kind == slot.kind && r.canBeNewest && r.parent == old) o.copy(revision = r.copy(parent = head.id)) else o
                        },
                    )
                    persist()
                }
            }
            return PrepareResult.Incompatible(head)
        }
        // This person's saves queued from here and not yet sent are their newest (never anyone else's).
        val queued = state.outbox.lastOrNull { it.profile == profile && it.revision?.game == slot.game.id && it.revision.kind == slot.kind && it.revision.canBeNewest }?.revision
        // A blank save here (save memory the emulator made again) is no progress made here: the
        // host's real one goes in place, so the game never starts from nothing beside it.
        val blankHere = allBlank(hashed)
        if (blankHere && queued == null) {
            mutex.withLock {
                state = state.copy(awaiting = state.awaiting + (keyOf(profile, slot) to Awaited(head.id, head.deviceName, head.at.millis)))
                persist()
            }
            place(client!!, slot, head)
            return PrepareResult.Updated(head)
        }
        val localChanged = manifest.files.isNotEmpty() && !blankHere && manifest.fingerprint != slotState.fingerprint
        return when (SyncRules.decide(slotState.base, localChanged || queued != null, head.id, sameContent = manifest.fingerprint == head.manifest.fingerprint)) {
            SyncDecision.UpToDate -> {
                // What is here stays the fingerprint to compare with: when it came from a save kept
                // in another shape (and was converted), the host's own would never match it.
                agree(keyOf(profile, slot), head.id, if (localChanged) head.manifest.fingerprint else slotState.fingerprint ?: head.manifest.fingerprint)
                PrepareResult.Ready
            }
            SyncDecision.Upload -> PrepareResult.Ready
            SyncDecision.Download -> {
                // Known from here on, until it is in place: a download cut short is never forgotten.
                mutex.withLock {
                    state = state.copy(awaiting = state.awaiting + (keyOf(profile, slot) to Awaited(head.id, head.deviceName, head.at.millis)))
                    persist()
                }
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
                // The person chose this device's save over the host's: nothing is awaited any more.
                awaiting = state.awaiting - keyOf(profile, slot),
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
        SyncClient.inParallel(revision.manifest.files.map { it.hash }.distinct().filterNot(store::has)) { client.download(it, store) }
        mutex.withLock {
            // The save as this emulator keeps it (converted when it came from one that keeps it differently).
            val incoming = inSlotFormat(slot, revision.manifest)
            // What is here now is kept first, so taking another save never loses this one.
            val (current, hashed) = fingerprintOf(slot)
            val alreadyGoing = state.outbox.any { o -> o.revision?.let { it.profile == owner && it.game == slot.game.id && it.kind == slot.kind && it.manifest.fingerprint == current.fingerprint } == true }
            // A blank one holds nothing to keep (and would only clutter the history).
            if (current.files.isNotEmpty() && current.fingerprint != incoming.fingerprint && !alreadyGoing && !allBlank(hashed)) {
                for ((lf, h) in hashed) if (!store.has(h)) lf.file.inputStream().use { store.put(it, expected = h) }
                val kept = SaveRevision(
                    id = SyncCrypto.token(12), profile = owner, game = slot.game.id, kind = slot.kind,
                    parent = state.slots[keyOf(owner, slot)]?.base, device = deviceId, deviceName = deviceName, at = hlc.now(),
                    manifest = current, reason = RevisionReason.BEFORE_RESTORE,
                )
                state = state.copy(outbox = state.outbox + Outgoing(kept.id, Priority.SAVE.rank, owner, kept))
            }
            write(slot, incoming)
            // The save as it was made, not a mix: files of the one replaced that the incoming save
            // doesn't have go (each is in the store, and in history), with the folders they leave empty.
            val keep = incoming.files.mapTo(HashSet()) { it.path }
            val stale = hashed.filter { (lf, h) -> lf.path !in keep && store.has(h) }.map { it.first.file }
            stale.forEach { it.delete() }
            prune(slot, stale, keep = incoming.folders)
            state = state.copy(
                // Agreed as written here, so the converted copy isn't taken for a new save after playing.
                slots = state.slots + (keyOf(owner, slot) to SlotState(revision.id, incoming.fingerprint)),
                holders = state.holders + (slot.key to owner),
                awaiting = state.awaiting - keyOf(owner, slot),
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
        // A folder save's empty folders first: its files then fill in the rest of the structure.
        for (folder in manifest.folders) slot.target(folder)?.mkdirs()
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
        slot.sink?.invoke()
    }

    /**
     * Removes the folders [gone] files leave empty, from each one's own folder up to (never
     * including) [slot]'s root, and never one of [keep] or one with anything left in it.
     */
    private fun prune(slot: LocalSlot, gone: List<File>, keep: List<String> = emptyList()) {
        val root = slot.root.canonicalFile
        val kept = keep.mapNotNull(slot::target).mapTo(HashSet()) { it.canonicalFile }
        for (f in gone) {
            var dir = (if (f.isDirectory) f else f.parentFile)?.canonicalFile
            while (dir != null && dir != root && dir.path.startsWith(root.path + File.separator)) {
                if (dir in kept || !dir.isDirectory || dir.list()?.isNotEmpty() != false) break
                if (!dir.delete()) break
                dir = dir.parentFile
            }
        }
    }

    private suspend fun agree(key: String, revision: String, fingerprint: String) = mutex.withLock {
        state = state.copy(slots = state.slots + (key to SlotState(revision, fingerprint)), awaiting = state.awaiting - key)
        persist()
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends what is waiting, most urgent first: records, then revisions. Stops at the first
     * failure to reach the host (it all stays queued); a revision the host refuses as a conflict
     * is kept there as a conflict copy and settled before the next launch.
     */
    suspend fun flush(client: SyncClient, saves: Boolean = true): Int {
        mutex.withLock { SaveImportTransaction.recover(importJournal) { id -> state.outbox.any { it.revision?.id == id } } }
        var sent = 0
        val profiles = mutex.withLock { state.pendingMeta.keys.toList() }
        for (p in profiles) {
            val pending = mutex.withLock { state.pendingMeta[p] } ?: continue
            val merged = client.pushMeta(p, pending).meta
            hlc.seen(merged.latestRevision())
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
        // Records only: what a profile switch waits for (its saves follow straight after, in the background).
        if (!saves) return sent
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
                // A save that went to Fuse One without a folder here was never in this device's
                // folder: what is there stays unagreed, so it can never be taken for newer than it.
                if (result?.accepted == true && rev.reason != RevisionReason.BEFORE_RESTORE && rev.provenance != TO_FUSE_ONE) {
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
        hlc.seen(m.latestRevision())
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

/** Who holds a save found on a shared device that no profile owned: kept aside until someone claims it. */
internal const val UNCLAIMED = "~unclaimed"

/** [text] as a safe folder name. */
private fun part(text: String) = text.replace(Regex("[^A-Za-z0-9 ._()-]+"), "_").trim('.', ' ').take(80).ifEmpty { "_" }

/** Answers that mean the host will never take a revision as it is: it leaves the queue (its files stay on the device). */
private val REFUSED_FOR_GOOD = setOf("bad-revision", "bad-body", "too-large", "too-many", "bad-hash")

/** Turns what a save adapter found into a slot this device can read and write. */
object Slots {
    /**
     * Whether [file] holds no save at all: empty, or every byte the same 0x00 or 0xFF, as save
     * memory is before a game writes to it. Read to the first byte that says otherwise.
     */
    fun blank(file: File): Boolean {
        if (!file.isFile) return false
        file.inputStream().buffered().use { input ->
            val first = input.read()
            if (first == -1) return true
            if (first != 0x00 && first != 0xFF) return false
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) return true
                for (i in 0 until n) if ((buf[i].toInt() and 0xFF) != first) return false
            }
        }
    }

    fun of(game: GameKey, spot: SaveSpot): LocalSlot {
        val files = ArrayList<LocalFile>()
        val empty = ArrayList<String>()
        val targets = LinkedHashMap<String, File>()
        for (f in spot.files) {
            val file = File(f.path)
            targets[f.name] = file
            if (file.isFile) files += LocalFile(f.name, file)
        }
        val root = spot.root?.let(::File) ?: spot.files.firstOrNull()?.let { File(it.path).parentFile } ?: File(".")
        // A Switch game's own hidden files are its save (Balatro keeps everything in `.lovegame`).
        val switch = spot.format == "switch.savedata"
        fun hidden(d: File) = SaveNoise.folder(d.name) && !(switch && d.name.startsWith(".") && !SwitchDumps.macLeftover(d.name))
        if (spot.root != null) {
            // Folder saves: every file in this game's folders, named by its path inside the root.
            val folders = spot.folders.ifEmpty { listOf("") }
            for (folder in folders) {
                val dir = if (folder.isEmpty()) root else File(root, folder)
                if (!dir.isDirectory) continue
                // Logs, temporary files, caches and what the system leaves behind are never the save's.
                dir.walkTopDown().onEnter { d -> d == dir || !hidden(d) }.forEach { f ->
                    val name = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    when {
                        f.name.startsWith(".") && !(switch && !SwitchDumps.macLeftover(f.name)) -> {}
                        // A folder many games share (a GCI folder): only this game's files.
                        spot.only != null && !name.startsWith(spot.only) -> {}
                        f.isFile -> if ((switch && f.name.startsWith(".") || !SaveNoise.file(f.name)) && SavePath.isSafe(name)) files += LocalFile(name, f)
                        // An empty folder inside the save is part of its structure too.
                        f != dir && f.isDirectory && f.list()?.isEmpty() == true -> if (SavePath.isSafe(name)) empty += name
                    }
                }
            }
        }
        // The save's other parts (a Switch game's device save): every file, named under its part.
        val mounts = spot.mounts.mapValues { File(it.value) }
        for ((prefix, dir) in mounts) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().onEnter { d -> d == dir || !hidden(d) }.forEach { f ->
                val inside = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                val name = "$prefix/$inside"
                if (spot.only != null && !inside.startsWith(spot.only)) return@forEach
                val kept = if (switch && f.name.startsWith(".")) !SwitchDumps.macLeftover(f.name) else !f.name.startsWith(".") && !SaveNoise.file(f.name)
                if (f.isFile && kept && SavePath.isSafe(name)) files += LocalFile(name, f)
            }
        }
        return LocalSlot(game, spot.kind, spot.format, root, files.sortedBy { it.path }, spot.available, targets, empty.sorted(), mounts, learnedAs = spot.learnedAs)
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
