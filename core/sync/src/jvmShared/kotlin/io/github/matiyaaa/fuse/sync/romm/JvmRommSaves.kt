package io.github.matiyaaa.fuse.sync.romm

import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.romm.RommException
import io.github.matiyaaa.fuse.romm.RommSave
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
 * taken for another. What was agreed lives in [dir]/romm-saves.json; a save of this device that
 * RomM's replaced is kept in [dir]/kept/RomM first.
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
) : RommSaveService {
    @Serializable
    private data class Agreed(val hash: String, val saveId: Long)

    @Serializable
    private data class State(val devices: Map<String, String> = emptyMap(), val agreed: Map<String, Agreed> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dir, "romm-saves.json")
    private val lock = Mutex()
    private var state: State = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())

    private fun persist() {
        dir.mkdirs()
        val tmp = File(dir, "romm-saves.json.tmp")
        tmp.writeText(json.encodeToString(State.serializer(), state))
        move(tmp, file)
    }

    private fun key(romId: Long, spot: SaveSpot) = "${server()}|$romId|${spot.files.single().name}"

    /** The one-file save of [query] here, or null when its save isn't one file RomM can hold. */
    internal fun spotOf(query: SaveQuery): SaveSpot? {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return null
        return runCatching { adapter.locate(query, env()) }.getOrDefault(emptyList()).firstOrNull { s ->
            s.kind == SaveKind.SAVE && s.available && s.root == null && s.mounts.isEmpty() && s.files.size == 1
        }
    }

    /** RomM's newest save of [romId] this emulator can read: the same kind of file as [local]. */
    private suspend fun newest(c: RommClient, romId: Long, local: File, format: String): RommSave? =
        c.saves(romId).firstOrNull { s -> s.emulator == format || extension(s.fileName) == extension(local.name) }

    private suspend fun deviceId(c: RommClient): String? = lock.withLock {
        state.devices[server()]?.let { return@withLock it }
        val id = runCatching { c.registerDevice(deviceName, platform, clientVersion) }.getOrNull() ?: return@withLock null
        state = state.copy(devices = state.devices + (server() to id))
        persist()
        id
    }

    private suspend fun agree(key: String, hash: String, saveId: Long) = lock.withLock {
        state = state.copy(agreed = state.agreed + (key to Agreed(hash, saveId)))
        persist()
    }

    override suspend fun beforeLaunch(query: SaveQuery, romId: Long): RommSaveGate = withContext(Dispatchers.IO) {
        val c = client() ?: return@withContext RommSaveGate.Go()
        val spot = spotOf(query) ?: return@withContext RommSaveGate.Go()
        val local = File(spot.files.single().path)
        val key = key(romId, spot)
        val latest = try {
            withTimeoutOrNull(WAIT_MS) { newest(c, romId, local, spot.format) } ?: run {
                // Asked and nothing came back in time, or RomM has no save of it yet.
                return@withContext RommSaveGate.Go()
            }
        } catch (e: RommException) {
            return@withContext RommSaveGate.Go("RomM didn't answer: playing with this device's save")
        }
        val here = if (local.isFile) md5(local) else null
        val agreed = state.agreed[key]
        when {
            here != null && here == latest.contentHash -> {
                agree(key, here, latest.id)
                RommSaveGate.Go()
            }
            // Nothing here yet, or only a blank one, or this device's unchanged since it agreed: RomM's comes down.
            here == null || Slots.blank(local) || agreed?.hash == here -> {
                bringDown(c, query, latest, local, key)
                RommSaveGate.Go("Your save from ${latest.originDeviceName ?: "RomM"} is in place")
            }
            // RomM's unchanged since this device agreed: this device's is newer, and goes up now.
            agreed != null && (agreed.saveId == latest.id || agreed.hash == latest.contentHash) -> {
                runCatching { withTimeoutOrNull(WAIT_MS) { sendUp(c, query, romId, local, spot.format, key) } }
                RommSaveGate.Go()
            }
            // Both changed, or never agreed and different: the person chooses.
            else -> RommSaveGate.Conflict(RommSaveConflict(query.title, romId, query, local.path, local.lastModified(), local.length(), latest))
        }
    }

    override suspend fun afterExit(query: SaveQuery, romId: Long) {
        withContext(Dispatchers.IO) {
            val c = client() ?: return@withContext
            val spot = spotOf(query) ?: return@withContext
            val local = File(spot.files.single().path)
            if (!local.isFile || Slots.blank(local)) return@withContext
            val key = key(romId, spot)
            val here = md5(local)
            val agreed = state.agreed[key]
            if (agreed?.hash == here) return@withContext
            val latest = runCatching { newest(c, romId, local, spot.format) }.getOrElse { return@withContext }
            when {
                latest == null || latest.contentHash == here -> if (latest == null) runCatching { sendUp(c, query, romId, local, spot.format, key) } else agree(key, here, latest.id)
                // RomM unchanged since this device agreed: this one is the newest.
                agreed != null && (agreed.saveId == latest.id || agreed.hash == latest.contentHash) -> runCatching { sendUp(c, query, romId, local, spot.format, key) }
                // RomM changed meanwhile too: nothing is sent over it; the next launch asks.
                else -> Unit
            }
        }
    }

    override suspend fun settle(conflict: RommSaveConflict, keepHere: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client() ?: error("RomM isn't signed in.")
            val spot = spotOf(conflict.query) ?: error("This game's save isn't here any more.")
            val local = File(spot.files.single().path)
            val key = key(conflict.romId, spot)
            if (keepHere) sendUp(c, conflict.query, conflict.romId, local, spot.format, key)
            else bringDown(c, conflict.query, conflict.server, local, key)
        }
    }

    /** RomM's [save] into [local]: checked against RomM's hash, this device's kept aside first, then moved over it. */
    private suspend fun bringDown(c: RommClient, query: SaveQuery, save: RommSave, local: File, key: String) {
        val device = deviceId(c)
        val bytes = c.saveContent(save, device)
        val hash = md5(bytes)
        if (save.contentHash != null && save.contentHash != hash) throw RommException("RomM's save changed on its way. Nothing was replaced.", code = "bad-hash")
        if (local.isFile) keepAside(query, local)
        local.parentFile?.mkdirs()
        val tmp = File(local.parentFile, ".${local.name}.fuse-romm")
        tmp.writeBytes(bytes)
        move(tmp, local)
        agree(key, hash, save.id)
        if (device != null) runCatching { c.confirmSave(save, device) }
    }

    /** [local] up to RomM as the newest save of [romId]. */
    private suspend fun sendUp(c: RommClient, query: SaveQuery, romId: Long, local: File, format: String, key: String) {
        val bytes = local.readBytes()
        val sent = c.uploadSave(romId, SLOT, format, deviceId(c), local.name, bytes)
        agree(key, sent.contentHash ?: md5(bytes), sent.id)
    }

    /** A copy of [local] in Fuse's kept folder, by game and time, before RomM's replaces it. */
    private fun keepAside(query: SaveQuery, local: File) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date(clock()))
        val name = (query.title.ifBlank { query.game.id }).replace(Regex("[^A-Za-z0-9 ._()-]+"), "_").take(80)
        val to = File(File(File(File(dir, "kept"), "RomM"), name), stamp)
        to.mkdirs()
        local.copyTo(File(to, local.name), overwrite = true)
    }

    companion object {
        /** The slot Fuse keeps a game's save in on RomM (RomM pairs saves by game and slot). */
        const val SLOT = "autosave"

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

        private fun move(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
