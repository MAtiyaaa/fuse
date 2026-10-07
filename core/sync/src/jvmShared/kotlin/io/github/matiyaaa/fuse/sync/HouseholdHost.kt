package io.github.matiyaaa.fuse.sync

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The host's part in the household's games, beside [HostStore]: each device's list of games (kept
 * on disk, so a device that is off still shows what it has), the requests one device made of
 * another (kept until done), each device's transfers as it last said (only in memory), and the
 * pieces of games on their way through the host (never written anywhere).
 *
 * Devices waiting on the host ([SyncHost]'s events) are woken by [wake] without anything entering
 * the journal, which stays a record of saves and records only.
 */
class HouseholdHost(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val libDir = File(dir, "libraries")
    private val commandsFile = File(dir, "commands.json")
    private val lock = Any()

    private val libraries = ConcurrentHashMap<String, DeviceLibrary>()
    private val commands = ArrayList<DeviceCommand>()
    private val snapshots = ConcurrentHashMap<String, TransferSnapshot>()

    init {
        libDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            runCatching { json.decodeFromString(DeviceLibrary.serializer(), f.readText()) }.getOrNull()?.let { libraries[it.device] = it }
        }
        runCatching { json.decodeFromString(ListSerializer(DeviceCommand.serializer()), commandsFile.readText()) }.getOrNull()?.let { commands += it }
    }

    // ---------------------------------------------------------------- waking devices

    private val wakes = ConcurrentHashMap<String, MutableSet<String>>()
    private val _ticks = MutableStateFlow(0L)

    /** Goes up whenever any device has something new to be told. */
    val ticks: StateFlow<Long> = _ticks.asStateFlow()

    /** Tells [devices] (every device but [except] when null) that [what] is waiting for them. */
    fun wake(what: String, devices: Collection<String>? = null, except: String? = null, all: () -> Collection<String>) {
        val targets = (devices ?: all()).filter { it != except }
        if (targets.isEmpty()) return
        for (d in targets) wakes.getOrPut(d) { ConcurrentHashMap.newKeySet() }.add(what)
        _ticks.value = _ticks.value + 1
    }

    fun hasWake(device: String): Boolean = wakes[device]?.isNotEmpty() == true

    /** What is waiting for [device], cleared as it is read. */
    fun takeWakes(device: String): Set<String> {
        val set = wakes[device] ?: return emptySet()
        val out = HashSet<String>()
        val it = set.iterator()
        while (it.hasNext()) {
            out += it.next()
            it.remove()
        }
        return out
    }

    // ---------------------------------------------------------------- lists

    fun putLibrary(lib: DeviceLibrary) {
        val stamped = lib.copy(updatedAt = clock())
        libraries[lib.device] = stamped
        libDir.mkdirs()
        writeAtomically(File(libDir, safeId(lib.device) + ".json"), json.encodeToString(DeviceLibrary.serializer(), stamped).toByteArray())
    }

    /** The lists [known] doesn't have the newest of, leaving out [except]'s own and [hidden] devices. */
    fun libraries(known: Map<String, String>, except: String, live: Set<String>, seen: Map<String, Long> = emptyMap()): LibrariesPage {
        val present = libraries.values.filter { it.device != except && it.device in live }
        return LibrariesPage(
            changed = present.filter { known[it.device] != it.version },
            devices = present.map { it.device },
            seen = seen,
        )
    }

    fun library(device: String): DeviceLibrary? = libraries[device]

    /** A device left the household: its list goes. */
    fun forget(device: String) {
        libraries.remove(device)
        File(libDir, safeId(device) + ".json").delete()
        snapshots.remove(device)
        synchronized(lock) {
            if (commands.removeIf { it.target == device && it.open }) saveCommands()
        }
    }

    // ---------------------------------------------------------------- requests between devices

    fun addCommand(c: DeviceCommand): DeviceCommand {
        synchronized(lock) {
            prune()
            // The same request still open is the same request (asking twice to send a game sends it once).
            commands.firstOrNull { it.open && it.target == c.target && it.type == c.type && it.game == c.game && it.key == c.key && it.action == c.action }?.let { return it }
            val made = c.copy(id = "cmd-" + SyncCrypto.token(10), at = clock(), state = DeviceCommand.PENDING, message = null, doneAt = 0)
            commands += made
            saveCommands()
            return made
        }
    }

    /** Open requests for [device], marked as delivered once read. */
    fun pendingFor(device: String): List<DeviceCommand> = synchronized(lock) {
        var changed = false
        val out = ArrayList<DeviceCommand>()
        for (i in commands.indices) {
            val c = commands[i]
            if (c.target != device || !c.open) continue
            if (c.state == DeviceCommand.PENDING) {
                commands[i] = c.copy(state = DeviceCommand.DELIVERED)
                changed = true
            }
            out += commands[i]
        }
        if (changed) saveCommands()
        out
    }

    /** Every request still open, and those finished in the last day (so the asker sees how it went). */
    fun allCommands(): List<DeviceCommand> = synchronized(lock) {
        prune()
        val recent = clock() - DONE_SHOWN_MS
        commands.filter { it.open || it.doneAt >= recent }.sortedByDescending { it.at }
    }

    /** [device] says how [id] went; only the device it was for may. The command, or null. */
    fun ack(device: String, id: String, ack: CommandAck): DeviceCommand? = synchronized(lock) {
        val i = commands.indexOfFirst { it.id == id }
        if (i < 0 || commands[i].target != device) return null
        val state = ack.state.takeIf { it in ACK_STATES } ?: return null
        // A request that is settled stays settled: a game that came quickly can be done before
        // the word that it was taken in arrives, and that late word mustn't open it again.
        if (!commands[i].open) return commands[i]
        commands[i] = commands[i].copy(state = state, message = ack.message?.take(300), doneAt = if (state == DeviceCommand.DELIVERED) 0 else clock())
        saveCommands()
        commands[i]
    }

    /** The asker (or the device it was for) takes a request back while it is still open. */
    fun cancel(device: String, id: String): DeviceCommand? = synchronized(lock) {
        val i = commands.indexOfFirst { it.id == id }
        if (i < 0) return null
        val c = commands[i]
        if (!c.open || (c.from != device && c.target != device)) return null
        commands[i] = c.copy(state = DeviceCommand.CANCELLED, doneAt = clock())
        saveCommands()
        commands[i]
    }

    private fun prune() {
        val now = clock()
        commands.removeIf { (it.open && now - it.at > OPEN_TTL_MS) || (!it.open && now - it.doneAt > DONE_KEPT_MS) }
        if (commands.size > MAX_COMMANDS) {
            val drop = commands.sortedBy { it.at }.take(commands.size - MAX_COMMANDS).toSet()
            commands.removeAll(drop)
        }
    }

    private fun saveCommands() {
        commandsFile.parentFile?.mkdirs()
        writeAtomically(commandsFile, json.encodeToString(ListSerializer(DeviceCommand.serializer()), commands).toByteArray())
    }

    // ---------------------------------------------------------------- transfers

    fun putSnapshot(s: TransferSnapshot) {
        snapshots[s.device] = s.copy(at = clock(), items = s.items.take(MAX_SNAPSHOT_ITEMS))
    }

    fun snapshots(live: Set<String>): List<TransferSnapshot> = snapshots.values.filter { it.device in live }.sortedBy { it.name.lowercase() }

    // ---------------------------------------------------------------- pieces on their way through

    /** A piece the host asked a device to send, and the one waiting for it. */
    class Relay(val ask: RelayAsk, val source: String) {
        /** The bytes, once the source starts sending; completed with null when it can't. */
        val body = CompletableDeferred<ByteReadChannel?>()

        /** Done passing them on: the source's call may end. */
        val passed = CompletableDeferred<Unit>()
    }

    private val relays = ConcurrentHashMap<String, Relay>()

    fun openRelay(ticket: PeerTicket, file: String, offset: Long, length: Long): Relay {
        val now = clock()
        relays.values.removeIf { now - it.ask.at > RELAY_TTL_MS }
        val ask = RelayAsk("rly-" + SyncCrypto.token(12), ticket.claims(), file, offset, length, now)
        return Relay(ask, ticket.source).also { relays[ask.id] = it }
    }

    fun relay(id: String): Relay? = relays[id]

    fun closeRelay(id: String) {
        relays.remove(id)?.let { r ->
            r.body.complete(null)
            r.passed.complete(Unit)
        }
    }

    /** Pieces [device] is asked to send and hasn't started on. */
    fun relayAsks(device: String): List<RelayAsk> = relays.values.filter { it.source == device && !it.body.isCompleted }.map { it.ask }

    private fun safeId(id: String) = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(80)

    companion object {
        const val WAKE_LIBRARY = "library"
        const val WAKE_COMMANDS = "commands"
        const val WAKE_RELAY = "relay"
        const val WAKE_TRANSFERS = "transfers"

        private val ACK_STATES = setOf(DeviceCommand.DELIVERED, DeviceCommand.DONE, DeviceCommand.FAILED)

        /** A request no device took up in a month is dropped. */
        const val OPEN_TTL_MS = 30L * 24 * 60 * 60_000
        /** Finished requests are kept a week, shown a day. */
        const val DONE_KEPT_MS = 7L * 24 * 60 * 60_000
        const val DONE_SHOWN_MS = 24L * 60 * 60_000
        const val MAX_COMMANDS = 2_000
        const val MAX_SNAPSHOT_ITEMS = 200

        /** How long a piece waits for its source to start sending it. */
        const val RELAY_WAIT_MS = 30_000L
        const val RELAY_TTL_MS = 2 * 60_000L
    }
}
