package io.github.matiyaaa.fuse.sync.romm

import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.romm.RommException
import io.github.matiyaaa.fuse.romm.RommSave
import io.github.matiyaaa.fuse.sync.LocalSlot
import io.github.matiyaaa.fuse.sync.SaveAdapters
import io.github.matiyaaa.fuse.sync.SaveEnvironment
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.sync.SaveSpot
import io.github.matiyaaa.fuse.sync.Slots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * [RommSaveService] on a phone or a computer. [client] is the signed-in RomM client when RomM and its
 * save sync are on (null otherwise); [server] names the server, so what was agreed with one is never
 * taken for another. What was agreed, and what waits to go, lives in [dir]/romm-saves.json; a save of
 * this device that RomM's replaced is kept in [dir]/kept/RomM first. [person] names who is playing
 * when the device has more than one person, whose saves on RomM are their own (see [RommSlots]).
 */
class JvmRommSaves(
    private val dir: File,
    private val env: () -> SaveEnvironment,
    private val client: suspend () -> RommClient?,
    private val server: () -> String,
    private val deviceName: String,
    private val platform: String,
    private val clientVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where what happened is written (Fuse's log); never a sign-in or a save's contents. */
    private val log: (String) -> Unit = {},
    private val person: () -> String? = { null },
) : RommSaveService {
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<RommSaveNotice>(extraBufferCapacity = 8)
    override val notices: kotlinx.coroutines.flow.SharedFlow<RommSaveNotice> = _notices

    /** Said, never silent: a save that should have gone to RomM and didn't. */
    private fun notSent(query: SaveQuery, why: String, e: Throwable? = null) {
        log("RomM save sync: ${query.title}: not sent. $why" + (e?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
        _notices.tryEmit(RommSaveNotice.NotSent(query.title, why))
    }

    @Serializable
    private data class Agreed(val hash: String, val saveId: Long)

    /** A save that couldn't reach RomM: it goes, in the order they waited, when RomM answers again. */
    @Serializable
    private data class Waiting(val key: String, val romId: Long, val query: SaveQuery, val since: Long)

    @Serializable
    private data class State(
        val devices: Map<String, String> = emptyMap(),
        val agreed: Map<String, Agreed> = emptyMap(),
        val waiting: List<Waiting> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dir, "romm-saves.json")
    private val lock = Mutex()

    /** One send at a time: a game's save and the ones waiting never go side by side. */
    private val sending = Mutex()
    private var state: State = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())

    private fun persist() {
        dir.mkdirs()
        val tmp = File(dir, "romm-saves.json.tmp")
        tmp.writeText(json.encodeToString(State.serializer(), state))
        move(tmp, file)
    }

    /**
     * A game's save here as the one file RomM keeps of it: a save that is one file as it is (with any
     * companions after it, RetroArch's `.rtc` clock, staying here), and a save of folders or of
     * several parts as one zip of its files ([RommPack]).
     */
    internal sealed class Held(val format: String) {
        /** What tells this save apart from the game's others in what was agreed. */
        abstract val name: String

        /** As RomM hashes it, or null when there is nothing here yet. */
        abstract fun hash(): String?

        /** Nothing in it yet (save memory a game never wrote to). */
        abstract fun blank(): Boolean

        /** What goes up, and the name it goes by. */
        abstract fun upload(title: String): Pair<String, ByteArray>

        /** RomM's [bytes] in place; the caller keeps what was here first. */
        abstract fun put(bytes: ByteArray)

        /** A copy of what is here, into [to]. */
        abstract fun copyTo(to: File)

        /** The file to show the person for it (its newest, for a save of folders), and its size in all. */
        abstract val shown: File?
        abstract val bytes: Long

        /** Whether one of RomM's saves is this kind (by the emulator that sent it, or its name). */
        abstract fun matches(save: RommSave): Boolean

        /** [bytes] as RomM hashes a save of this kind. */
        abstract fun hashOf(bytes: ByteArray): String

        class One(val file: File, format: String) : Held(format) {
            override val name: String get() = file.name
            override fun hash(): String? = if (file.isFile) md5(file) else null
            override fun blank(): Boolean = !file.isFile || Slots.blank(file)
            override fun upload(title: String) = file.name to file.readBytes()
            override fun put(bytes: ByteArray) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, ".${file.name}.fuse-romm")
                tmp.writeBytes(bytes)
                move(tmp, file)
            }
            override fun copyTo(to: File) { if (file.isFile) file.copyTo(File(to, file.name), overwrite = true) }
            override val shown: File get() = file
            override val bytes: Long get() = file.length()
            override fun matches(save: RommSave) = save.emulator == format || extension(save.fileName) == extension(file.name)
            override fun hashOf(bytes: ByteArray) = md5(bytes)
        }

        class Many(val slot: LocalSlot, format: String) : Held(format) {
            override val name: String get() = "folder:$format"
            override fun hash(): String? = RommPack.contentHash(slot)
            override fun blank(): Boolean = slot.files.isEmpty()
            override fun upload(title: String) = "${part(title)}.zip" to RommPack.pack(slot)
            override fun put(bytes: ByteArray) = RommPack.unpack(bytes, slot)
            override fun copyTo(to: File) {
                for (f in slot.files) File(to, f.path).also { it.parentFile.mkdirs() }.let { f.file.copyTo(it, overwrite = true) }
            }
            override val shown: File? get() = slot.files.maxByOrNull { it.file.lastModified() }?.file
            override val bytes: Long get() = slot.files.sumOf { it.file.length() }
            override fun matches(save: RommSave) = save.emulator == format || extension(save.fileName) == "zip"
            override fun hashOf(bytes: ByteArray) = RommPack.contentHash(bytes)
        }
    }

    /** [query]'s save here as RomM can hold it, or null when it has none Fuse can find. */
    internal fun heldOf(query: SaveQuery): Held? {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return null
        val spots = runCatching { adapter.locate(query, env()) }.getOrDefault(emptyList()).filter { it.kind == SaveKind.SAVE && it.available }
        spots.firstOrNull { it.root == null && it.mounts.isEmpty() && it.files.isNotEmpty() }
            ?.let { return Held.One(File(it.files.first().path), it.format) }
        // A save of folders, or of several parts: one zip. Not one whose folders aren't known yet.
        spots.firstOrNull { (it.root != null || it.mounts.isNotEmpty()) && it.learnIn == null }
            ?.let { return Held.Many(Slots.of(query.game, it), it.format) }
        return null
    }

    /** [query]'s save here when it is one file (what RomM's save sync took before saves of folders). */
    internal fun spotOf(query: SaveQuery): SaveSpot? {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return null
        return runCatching { adapter.locate(query, env()) }.getOrDefault(emptyList()).firstOrNull { s ->
            s.kind == SaveKind.SAVE && s.available && s.root == null && s.mounts.isEmpty() && s.files.isNotEmpty()
        }
    }

    private fun key(romId: Long, held: Held) = "${server()}|$romId|${held.name}" + (person()?.let { "|$it" } ?: "")

    /** RomM's newest save of [romId] this emulator can read, from the slot of whoever is playing. */
    private suspend fun newest(c: RommClient, romId: Long, held: Held): RommSave? {
        val who = person()
        return c.saves(romId).firstOrNull { s -> held.matches(s) && RommSlots.belongs(s.slot, held.format, who) }
    }

    private suspend fun deviceId(c: RommClient): String? = lock.withLock {
        state.devices[server()]?.let { return@withLock it }
        val id = runCatching { c.registerDevice(deviceName, platform, clientVersion) }.getOrNull() ?: return@withLock null
        state = state.copy(devices = state.devices + (server() to id))
        persist()
        id
    }

    private suspend fun agree(key: String, hash: String, saveId: Long) = lock.withLock {
        state = state.copy(agreed = state.agreed + (key to Agreed(hash, saveId)), waiting = state.waiting.filterNot { it.key == key })
        persist()
    }

    private suspend fun wait(key: String, romId: Long, query: SaveQuery) = lock.withLock {
        if (state.waiting.none { it.key == key }) state = state.copy(waiting = state.waiting + Waiting(key, romId, query, clock()))
        persist()
    }

    private suspend fun drop(key: String) = lock.withLock {
        state = state.copy(waiting = state.waiting.filterNot { it.key == key })
        persist()
    }

    /** The save here and what was last agreed for it, as [RommLedger] weighs them. */
    private fun ledger(held: Held, key: String) = Pair(
        held.hash()?.let { RommLedger.Here(it, held.blank()) },
        state.agreed[key]?.let { RommLedger.Agreed(it.hash, it.saveId) },
    )

    private fun remote(s: RommSave?) = s?.let { RommLedger.Remote(it.id, it.contentHash) }

    override suspend fun beforeLaunch(query: SaveQuery, romId: Long): RommSaveGate = withContext(Dispatchers.IO) {
        val c = client() ?: return@withContext RommSaveGate.Go()
        // What waited to go goes first, while RomM answers.
        runCatching { withTimeoutOrNull(WAIT_MS) { sendWaiting() } }
        // Save states first: never in the way of the game starting.
        try {
            withTimeoutOrNull(WAIT_MS) { statesDown(c, query, romId) }
        } catch (e: Exception) {
            log("RomM save states: ${query.title}: not brought down (${e.javaClass.simpleName}: ${e.message})")
        }
        val held = heldOf(query) ?: return@withContext RommSaveGate.Go()
        val key = key(romId, held)
        val latest = try {
            withTimeoutOrNull(WAIT_MS) { newest(c, romId, held) } ?: return@withContext RommSaveGate.Go()
        } catch (e: RommException) {
            return@withContext RommSaveGate.Go("RomM didn't answer: playing with this device's save")
        }
        val (here, agreed) = ledger(held, key)
        when (RommLedger.before(here, agreed, remote(latest))) {
            RommLedger.Before.NOTHING -> RommSaveGate.Go()
            RommLedger.Before.IN_STEP -> {
                agree(key, here!!.hash, latest.id)
                RommSaveGate.Go()
            }
            RommLedger.Before.BRING_DOWN -> {
                bringDown(c, query, latest, held, key)
                RommSaveGate.Go("Your save from ${latest.originDeviceName ?: "RomM"} is in place")
            }
            RommLedger.Before.SEND_UP -> {
                runCatching { withTimeoutOrNull(WAIT_MS) { sendUp(c, query, romId, held, key) } }
                RommSaveGate.Go()
            }
            RommLedger.Before.ASK -> RommSaveGate.Conflict(
                RommSaveConflict(query.title, romId, query, held.shown?.path.orEmpty(), held.shown?.lastModified() ?: 0L, held.bytes, latest),
            )
        }
    }

    override suspend fun standing(query: SaveQuery, romId: Long, newest: RommSave?): RommSaveStanding = withContext(Dispatchers.IO) {
        val held = heldOf(query) ?: return@withContext RommSaveStanding.UNKNOWN
        val (here, agreed) = ledger(held, key(romId, held))
        RommLedger.standing(here, agreed, remote(newest))
    }

    override suspend fun afterExit(query: SaveQuery, romId: Long) {
        withContext(Dispatchers.IO) {
            // RomM's save sync is off here (another way moves this device's saves, or none): nothing to do.
            val c = client() ?: return@withContext
            try {
                val sent = statesUp(c, query, romId)
                if (sent > 0) log("RomM save states: ${query.title}: $sent sent")
            } catch (e: Exception) {
                log("RomM save states: ${query.title}: not sent; they go after the next time you play (${e.javaClass.simpleName}: ${e.message})")
            }
            val held = heldOf(query) ?: return@withContext notSent(query, "Fuse couldn't find this game's save to send.")
            sendOne(c, query, romId, held, quiet = false)
            runCatching { sendWaiting() }
        }
    }

    /**
     * [held] up to RomM if it changed since they agreed and RomM didn't meanwhile; a save that
     * can't reach RomM waits, in order, for the next time it answers. True when nothing is left
     * waiting for it.
     */
    private suspend fun sendOne(c: RommClient, query: SaveQuery, romId: Long, held: Held, quiet: Boolean): Boolean = sending.withLock {
        val key = key(romId, held)
        val (here, agreed) = ledger(held, key)
        if (here == null || here.blank || agreed?.hash == here.hash) {
            drop(key)
            return@withLock true
        }
        val latest = try {
            newest(c, romId, held)
        } catch (e: Exception) {
            wait(key, romId, query)
            if (!quiet) notSent(query, "RomM didn't answer. It goes by itself once RomM answers again.", e)
            return@withLock false
        }
        when (RommLedger.after(here, agreed, remote(latest))) {
            RommLedger.After.NOTHING -> drop(key)
            RommLedger.After.AGREE -> agree(key, here.hash, latest!!.id)
            RommLedger.After.SEND -> try {
                sendUp(c, query, romId, held, key)
                log("RomM save sync: ${query.title}: sent")
                _notices.tryEmit(RommSaveNotice.Sent(query.title))
            } catch (e: Exception) {
                wait(key, romId, query)
                if (!quiet) notSent(query, "RomM didn't take it (${e.message ?: "no answer"}). It goes by itself once RomM answers again.", e)
                return@withLock false
            }
            // RomM's changed meanwhile too: nothing is sent over it; the next start asks which to keep.
            RommLedger.After.LEAVE_FOR_NEXT_START -> {
                drop(key)
                log("RomM save sync: ${query.title}: RomM's save changed too; the next launch asks which to keep")
            }
        }
        true
    }

    /** Sends what waited to go, in the order it waited; stops at the first that still can't. Returns how many went. */
    override suspend fun sendWaiting(): Int = withContext(Dispatchers.IO) {
        val c = client() ?: return@withContext 0
        var done = 0
        for (w in lock.withLock { state.waiting }) {
            val held = heldOf(w.query)
            if (held == null || key(w.romId, held) != w.key) {
                drop(w.key)
                continue
            }
            if (!sendOne(c, w.query, w.romId, held, quiet = true)) break
            done++
        }
        done
    }

    /** How many saves wait to go to RomM. */
    override val waiting: Int get() = state.waiting.size

    override suspend fun settle(conflict: RommSaveConflict, keepHere: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client() ?: error("RomM isn't signed in.")
            val held = heldOf(conflict.query) ?: error("This game's save isn't here any more.")
            val key = key(conflict.romId, held)
            if (keepHere) sendUp(c, conflict.query, conflict.romId, held, key)
            else bringDown(c, conflict.query, conflict.server, held, key)
        }
    }

    /** This game's save states here that RomM can hold: one file for each slot. */
    internal fun stateSpots(query: SaveQuery): List<SaveSpot> {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return emptyList()
        return runCatching { adapter.locate(query, env()) }.getOrDefault(emptyList()).filter { s ->
            s.kind == SaveKind.STATE && s.available && s.root == null && s.mounts.isEmpty() && s.files.isNotEmpty()
        }
    }

    private fun stateKey(romId: Long, file: File) = "${server()}|$romId|state|${file.name}"

    /**
     * RomM's save states of [romId] into their slots here: each slot whose state here is missing or
     * unchanged since it last agreed with RomM gets RomM's newest for it (this device's copy kept
     * aside first). A state changed here as well stays, and goes up after the game.
     */
    internal suspend fun statesDown(c: RommClient, query: SaveQuery, romId: Long) {
        val spots = stateSpots(query)
        if (spots.isEmpty()) return
        val remote = c.states(romId)
        if (remote.isEmpty()) return
        for (spot in spots) for (f in spot.files) {
            val local = File(f.path)
            val latest = remote.firstOrNull { it.baseName == local.name && (it.emulator == null || it.emulator == spot.format) } ?: continue
            val key = stateKey(romId, local)
            val here = if (local.isFile) md5(local) else null
            val agreed = state.agreed[key]
            when {
                here != null && here == latest.contentHash -> agree(key, here, latest.id)
                here == null || agreed?.hash == here -> {
                    val bytes = c.stateContent(latest)
                    val hash = md5(bytes)
                    if (latest.contentHash != null && latest.contentHash != hash) throw RommException("RomM's save state changed on its way.", code = "bad-hash")
                    if (local.isFile) keepAside(query, Held.One(local, spot.format))
                    Held.One(local, spot.format).put(bytes)
                    agree(key, hash, latest.id)
                    log("RomM save states: ${query.title}: ${local.name} brought down")
                }
            }
        }
    }

    /**
     * This device's save states of [romId] that changed since they last agreed with RomM, up to it;
     * the copy this device sent before for the same slot is taken off RomM, so its states don't
     * pile up. Returns how many went.
     */
    internal suspend fun statesUp(c: RommClient, query: SaveQuery, romId: Long): Int {
        var sent = 0
        for (spot in stateSpots(query)) for (f in spot.files) {
            val local = File(f.path)
            if (!local.isFile || local.length() == 0L) continue
            val key = stateKey(romId, local)
            val bytes = local.readBytes()
            val here = md5(bytes)
            val agreed = state.agreed[key]
            if (agreed?.hash == here) continue
            val up = c.uploadState(romId, spot.format, local.name, bytes)
            agree(key, up.contentHash ?: here, up.id)
            if (agreed != null && agreed.saveId != up.id) runCatching { c.deleteStates(listOf(agreed.saveId)) }
            sent++
        }
        return sent
    }

    /** RomM's [save] into place: checked against RomM's hash, this device's kept aside first, then put over it. */
    private suspend fun bringDown(c: RommClient, query: SaveQuery, save: RommSave, held: Held, key: String) {
        val device = deviceId(c)
        val bytes = c.saveContent(save, device)
        val hash = held.hashOf(bytes)
        // A RomM older than its zip-content hash hashed the zip's own bytes: either is RomM's word for it.
        if (save.contentHash != null && save.contentHash != hash && save.contentHash != md5(bytes)) throw RommException("RomM's save changed on its way. Nothing was replaced.", code = "bad-hash")
        if (held.hash() != null) keepAside(query, held)
        held.put(bytes)
        agree(key, held.hash() ?: hash, save.id)
        if (device != null) runCatching { c.confirmSave(save, device) }
    }

    /** [held] up to RomM as the newest save of [romId], in the slot of what it is (and of whoever is playing). */
    private suspend fun sendUp(c: RommClient, query: SaveQuery, romId: Long, held: Held, key: String) {
        val here = held.hash() ?: return
        val (name, bytes) = held.upload(query.title.ifBlank { query.game.id })
        val sent = c.uploadSave(romId, RommSlots.of(held.format, person()), held.format, deviceId(c), name, bytes)
        agree(key, sent.contentHash ?: here, sent.id)
    }

    /** A copy of what is here in Fuse's kept folder, by game and time, before RomM's replaces it. */
    private fun keepAside(query: SaveQuery, held: Held) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date(clock()))
        val to = File(File(File(File(dir, "kept"), "RomM"), part(query.title.ifBlank { query.game.id })), stamp)
        to.mkdirs()
        held.copyTo(to)
    }

    companion object {
        /** The slot Fuse kept every game's save in on RomM before 0.4.3; see [RommSlots]. */
        const val SLOT = RommSlots.LEGACY

        /** How long a launch waits on RomM. */
        const val WAIT_MS = 8_000L

        fun md5(file: File): String = file.inputStream().use { input ->
            val d = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
            d.digest().joinToString("") { "%02x".format(it) }
        }

        fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A file name's extension, without RomM's own " [date]" tag. */
        internal fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

        /** [text] as a safe file or folder name. */
        private fun part(text: String) = text.replace(Regex("[^A-Za-z0-9 ._()-]+"), "_").take(80)

        private fun move(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
