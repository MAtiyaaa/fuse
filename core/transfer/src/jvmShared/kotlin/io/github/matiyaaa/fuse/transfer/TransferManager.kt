package io.github.matiyaaa.fuse.transfer

import io.github.matiyaaa.fuse.model.StorageVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Does one kind of transfer for the [TransferManager]: a RomM game, a Jellyfin film, an upload.
 * [run] moves the bytes through [io] and returns once the result is checked and in place; it throws
 * [TransferInterrupted] (or an [IOException]) when the network or server went away (Fuse waits and
 * tries again, carrying on from where it was), [DriveMissing] when a drive went, and
 * [TransferFailure] when it can't be done.
 */
interface TransferHandler {
    val source: String

    suspend fun run(item: TransferItem, io: TransferIo)

    /** The person cancelled [item]: whatever it left half done (a partial file, an upload session) goes. */
    suspend fun discard(item: TransferItem, io: TransferIo) {}

    /** Called once [item] finished well, outside the transfer's own work (rescans, notices). */
    suspend fun done(item: TransferItem) {}
}

/** What a running transfer may ask of the manager. */
interface TransferIo {
    /** A folder kept for this transfer until it finishes (its partial files, if it can't keep them beside the destination). */
    val workDir: File

    /** Where [place] is now; throws [DriveMissing] when its drive isn't connected. */
    suspend fun resolve(place: TransferPlace): String

    /** Bytes moved so far, and the total when known. Cheap: call it as often as bytes move. */
    fun progress(done: Long, total: Long? = null)

    fun phase(phase: TransferPhase)

    /** Keeps the handler's own note with the transfer (survives a restart). */
    suspend fun note(payload: String)

    /** Waits until [bytes] more may move, under the bandwidth limit and the while-playing rule. */
    suspend fun throttle(bytes: Int)
}

/**
 * Every transfer Fuse makes for the person, in one queue: up to five downloads and five uploads at
 * once (the person chooses), in the order they set, kept in [dir] across restarts. A transfer whose
 * drive is gone waits for it; one whose server went away waits and carries on; a pause keeps what
 * was done. Moving numbers ([live]) update apart from the list ([items]), so a page showing many
 * transfers redraws only the rows that move.
 */
class TransferManager(
    private val dir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val volumes: suspend () -> List<StorageVolume> = { emptyList() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dir, "transfers.json")
    private val mutex = Mutex()
    private val handlers = ConcurrentHashMap<String, TransferHandler>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lives = ConcurrentHashMap<String, MutableStateFlow<TransferLive>>()
    private val meters = ConcurrentHashMap<String, SpeedMeter>()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<TransferItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(TransferSettings())
    val settings: StateFlow<TransferSettings> = _settings.asStateFlow()

    private val _conditions = MutableStateFlow(TransferConditions())

    private val _summary = MutableStateFlow(TransferSummary())
    /** Counts and progress for the Downloads button, updated about twice a second while anything moves. */
    val summary: StateFlow<TransferSummary> = _summary.asStateFlow()

    private val buckets = TransferDirection.entries.associateWith { TokenBucket(clock) }
    private var ticker: Job? = null
    private var started = false

    /** Starts the queue: unfinished transfers from last time carry on (or wait for a resume, when the person said so). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            mutex.withLock {
                val now = clock()
                val keep = _settings.value.keepFinishedDays
                _items.value = _items.value.mapNotNull { t ->
                    when {
                        // Finished long enough ago: gone from the list.
                        t.status.finished && (t.finishedAt ?: 0) < now - keep * DAY_MS -> null
                        t.status == TransferStatus.ACTIVE ->
                            if (_settings.value.resumeOnStart) t.copy(status = TransferStatus.QUEUED, phase = null) else t.copy(status = TransferStatus.PAUSED, phase = null)
                        t.status == TransferStatus.WAITING && !_settings.value.resumeOnStart -> t.copy(status = TransferStatus.PAUSED)
                        else -> t
                    }
                }
                persist()
            }
            kick()
        }
    }

    fun register(handler: TransferHandler) {
        handlers[handler.source] = handler
        kick()
    }

    fun configure(settings: TransferSettings) {
        val s = settings.copy(maxDownloads = settings.maxDownloads.coerceIn(1, TransferSettings.MAX_PARALLEL), maxUploads = settings.maxUploads.coerceIn(1, TransferSettings.MAX_PARALLEL))
        if (s == _settings.value) return
        _settings.value = s
        applyConditions()
    }

    /** What is going on around the transfers: a game being played, the kind of connection. */
    fun conditions(c: TransferConditions) {
        if (c == _conditions.value) return
        _conditions.value = c
        applyConditions()
    }

    /** A drive was connected or removed: transfers waiting for a drive look again at once. */
    fun drivesChanged() {
        scope.launch {
            update { list -> list.map { if (it.status == TransferStatus.WAITING && it.waiting == WaitReason.DRIVE) it.copy(retryAt = null) else it } }
            kick()
        }
    }

    /** The moving numbers of [id]: bytes, speed and time left. */
    fun live(id: String): StateFlow<TransferLive> = liveOf(id)

    /**
     * Queues a transfer and returns its id. The same [TransferItem.key] already queued, running,
     * waiting or paused is that transfer: its id comes back and nothing is queued twice.
     */
    suspend fun enqueue(item: TransferItem): String {
        val id = mutex.withLock {
            val existing = _items.value.firstOrNull { it.key == item.key && !it.status.finished }
            if (existing != null) return@withLock existing.id
            val now = clock()
            val fresh = item.copy(
                id = item.id.ifEmpty { newId() },
                order = TransferScheduler.endOrder(_items.value),
                createdAt = now,
                status = TransferStatus.QUEUED,
                doneBytes = 0,
                error = null,
            )
            // An earlier finished one with the same key leaves the list: this is the same thing again.
            _items.value = _items.value.filterNot { it.key == item.key } + fresh
            persist()
            fresh.id
        }
        kick()
        return id
    }

    fun pause(id: String) = scope.launch {
        jobs.remove(id)?.cancel(Paused())
        update { list -> list.map { if (it.id == id && !it.status.finished) it.copy(status = TransferStatus.PAUSED, phase = null, waiting = null) else it } }
        kick()
    }

    fun resume(id: String) = scope.launch {
        update { list ->
            list.map {
                if (it.id == id && (it.status == TransferStatus.PAUSED || it.status == TransferStatus.WAITING || it.status == TransferStatus.FAILED)) {
                    it.copy(status = TransferStatus.QUEUED, waiting = null, waitingFor = null, retryAt = null, error = null, attempts = 0)
                } else {
                    it
                }
            }
        }
        kick()
    }

    /** Tries a failed transfer again, from where it stopped when its source allows. */
    fun retry(id: String) = resume(id)

    fun pauseAll() = scope.launch {
        val active = _items.value.filter { !it.status.finished && it.status != TransferStatus.PAUSED }.map { it.id }.toSet()
        active.forEach { jobs.remove(it)?.cancel(Paused()) }
        update { list -> list.map { if (it.id in active) it.copy(status = TransferStatus.PAUSED, phase = null, waiting = null) else it } }
    }

    fun resumeAll() = scope.launch {
        update { list -> list.map { if (it.status == TransferStatus.PAUSED) it.copy(status = TransferStatus.QUEUED) else it } }
        kick()
    }

    /** Stops [id] for good: what it left half done is removed. */
    fun cancel(id: String) = scope.launch {
        jobs.remove(id)?.let { it.cancel(Cancelled()); it.join() }
        val item = _items.value.firstOrNull { it.id == id } ?: return@launch
        if (item.status.finished && item.status != TransferStatus.FAILED) return@launch
        runCatching { handlers[item.source]?.discard(item, ioFor(item)) }
        workDirOf(id).deleteRecursively()
        update { list -> list.map { if (it.id == id) it.copy(status = TransferStatus.CANCELLED, phase = null, finishedAt = clock()) else it } }
        lives.remove(id)
        kick()
    }

    fun moveUp(id: String) = reorder(id, -1)
    fun moveDown(id: String) = reorder(id, 1)
    fun moveToTop(id: String) = reorder(id, 0, toTop = true)

    private fun reorder(id: String, by: Int, toTop: Boolean = false) = scope.launch {
        update { list ->
            val orders = TransferScheduler.move(list, id, by, toTop)
            if (orders.isEmpty()) list else list.map { t -> orders[t.id]?.let { t.copy(order = it) } ?: t }
        }
        kick()
    }

    /** Clears finished transfers (done and cancelled; failed ones stay until retried or cancelled). */
    fun clearFinished() = scope.launch {
        update { list -> list.filterNot { it.status == TransferStatus.DONE || it.status == TransferStatus.CANCELLED } }
    }

    /** Removes [id] from the list once it is finished. */
    fun remove(id: String) = scope.launch {
        update { list -> list.filterNot { it.id == id && it.status.finished } }
    }

    /** Waits until nothing runs or waits to run (tests, and closing cleanly). */
    suspend fun idle(timeoutMs: Long = 60_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val busy = jobs.isNotEmpty() || _items.value.any { it.status == TransferStatus.QUEUED || it.status == TransferStatus.ACTIVE }
            if (!busy) return
            delay(20)
        }
    }

    // ------------------------------------------------------------------ running

    private fun held(direction: TransferDirection): WaitReason? {
        val c = _conditions.value
        val s = _settings.value
        if (s.wifiOnly && !c.unmetered) return WaitReason.WIFI
        if (c.playing && s.whilePlaying(direction) == WhilePlaying.PAUSE) return WaitReason.PLAYING
        return null
    }

    private fun applyConditions() {
        scope.launch {
            val s = _settings.value
            for (d in TransferDirection.entries) {
                val playingRate = if (_conditions.value.playing && s.whilePlaying(d) == WhilePlaying.REDUCED) TransferSettings.REDUCED_RATE else 0L
                // The overall limit is shared between the directions that are moving.
                val user = if (s.bandwidthLimit > 0) s.bandwidthLimit / 2 else 0L
                buckets.getValue(d).rate = listOf(user, playingRate).filter { it > 0 }.minOrNull() ?: 0L
            }
            // Held directions: running transfers step aside (keeping what they did) and wait.
            for (d in TransferDirection.entries) {
                val reason = held(d)
                if (reason != null) {
                    val ids = _items.value.filter { it.direction == d && it.status == TransferStatus.ACTIVE }.map { it.id }
                    ids.forEach { jobs.remove(it)?.cancel(Held()) }
                    update { list -> list.map { if (it.direction == d && (it.status == TransferStatus.ACTIVE || it.status == TransferStatus.QUEUED)) it.copy(status = TransferStatus.WAITING, waiting = reason, phase = null) else it } }
                } else {
                    update { list -> list.map { if (it.direction == d && it.status == TransferStatus.WAITING && (it.waiting == WaitReason.PLAYING || it.waiting == WaitReason.WIFI)) it.copy(status = TransferStatus.QUEUED, waiting = null) else it } }
                }
            }
            kick()
        }
    }

    /** Starts whatever may start now, and wakes again when a wait is over. */
    fun kick() {
        scope.launch {
            val toStart = mutex.withLock {
                val now = clock()
                val ids = TransferScheduler.next(_items.value, _settings.value, now, ::held).filter { handlers[_items.value.first { t -> t.id == it }.source] != null && !jobs.containsKey(it) }
                if (ids.isNotEmpty()) {
                    _items.value = _items.value.map { if (it.id in ids) it.copy(status = TransferStatus.ACTIVE, phase = TransferPhase.STARTING, waiting = null, waitingFor = null, retryAt = null, startedAt = it.startedAt ?: now) else it }
                    persist()
                }
                ids.map { id -> _items.value.first { it.id == id } }
            }
            for (item in toStart) launchJob(item)
            // A transfer waiting on a time (network back-off, a drive to look for again) wakes the queue then.
            val nextWake = _items.value.filter { it.status == TransferStatus.WAITING }.mapNotNull { it.retryAt }.minOrNull()
            if (nextWake != null) {
                val wait = (nextWake - clock()).coerceAtLeast(50)
                scope.launch { delay(wait); kick() }
            }
            ensureTicker()
        }
    }

    private fun launchJob(item: TransferItem) {
        val handler = handlers[item.source] ?: return
        val io = ioFor(item)
        meters.getOrPut(item.id) { SpeedMeter() }.reset()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val outcome: (TransferItem) -> TransferItem = try {
                handler.run(item, io)
                val live = liveOf(item.id).value
                ({ t -> t.copy(status = TransferStatus.DONE, phase = null, finishedAt = clock(), doneBytes = live.totalBytes ?: live.doneBytes, totalBytes = live.totalBytes ?: t.totalBytes, error = null) })
            } catch (e: CancellationException) {
                // Paused, held or cancelled: whoever stopped it already said where it stands.
                if (!isActive) return@launch
                // A timeout inside the transfer's own work is the network, not the person.
                ({ t -> t.copy(status = TransferStatus.WAITING, waiting = WaitReason.NETWORK, phase = null, attempts = t.attempts + 1, retryAt = clock() + TransferScheduler.backoff(t.attempts + 1)) })
            } catch (e: DriveMissing) {
                ({ t -> t.copy(status = TransferStatus.WAITING, waiting = WaitReason.DRIVE, waitingFor = e.label, phase = null, retryAt = clock() + DRIVE_LOOK_MS) })
            } catch (e: TransferFailure) {
                ({ t -> t.copy(status = TransferStatus.FAILED, phase = null, error = e.message, retryable = e.retryable, finishedAt = clock()) })
            } catch (e: Exception) {
                // The network or the server: wait and carry on, a while longer each time, then give up.
                val interrupted = e is TransferInterrupted || e is IOException || e.isNetwork()
                ({ t ->
                    val attempts = t.attempts + 1
                    if (interrupted && attempts < MAX_ATTEMPTS) {
                        t.copy(status = TransferStatus.WAITING, waiting = WaitReason.NETWORK, phase = null, attempts = attempts, retryAt = clock() + TransferScheduler.backoff(attempts), error = e.message)
                    } else {
                        t.copy(status = TransferStatus.FAILED, phase = null, attempts = attempts, error = failureWords(e), retryable = true, finishedAt = clock())
                    }
                })
            }
            jobs.remove(item.id)
            val live = liveOf(item.id).value
            update { list -> list.map { if (it.id == item.id) outcome(it).let { o -> if (o.status == TransferStatus.DONE) o else o.copy(doneBytes = live.doneBytes, totalBytes = live.totalBytes ?: o.totalBytes) } else it } }
            val finished = _items.value.firstOrNull { it.id == item.id }
            if (finished?.status == TransferStatus.DONE) {
                workDirOf(item.id).deleteRecursively()
                runCatching { handler.done(finished) }
            }
            kick()
        }
        jobs[item.id] = job
        job.start()
    }

    private fun ioFor(item: TransferItem): TransferIo = object : TransferIo {
        override val workDir: File get() = workDirOf(item.id).also { it.mkdirs() }

        override suspend fun resolve(place: TransferPlace): String {
            val all = volumes()
            return TransferPlaces.resolve(place, all) ?: throw DriveMissing(place.volume?.label ?: "The drive")
        }

        override fun progress(done: Long, total: Long?) {
            val now = clock()
            val speed = meters.getOrPut(item.id) { SpeedMeter() }.sample(done, now)
            val t = total ?: liveOf(item.id).value.totalBytes ?: item.totalBytes
            liveOf(item.id).value = TransferLive(done, t, speed, t?.let { SpeedMeter.eta(it - done, speed) })
        }

        override fun phase(phase: TransferPhase) {
            scope.launch { update(persist = false) { list -> list.map { if (it.id == item.id && it.status == TransferStatus.ACTIVE) it.copy(phase = phase) else it } } }
        }

        override suspend fun note(payload: String) {
            update { list -> list.map { if (it.id == item.id) it.copy(payload = payload) else it } }
        }

        override suspend fun throttle(bytes: Int) = buckets.getValue(item.direction).take(bytes)
    }

    /** Keeps the counts and stored byte totals moving while anything runs, without touching the list each chunk. */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var lastSave = clock()
            while (true) {
                val items = _items.value
                _summary.value = TransferScheduler.summary(items) { lives[it]?.value }
                if (items.none { it.status == TransferStatus.ACTIVE }) break
                if (clock() - lastSave > SAVE_EVERY_MS) {
                    lastSave = clock()
                    update { list -> list.map { t -> lives[t.id]?.value?.takeIf { t.status == TransferStatus.ACTIVE }?.let { l -> t.copy(doneBytes = l.doneBytes, totalBytes = l.totalBytes ?: t.totalBytes) } ?: t } }
                }
                delay(TICK_MS)
            }
            _summary.value = TransferScheduler.summary(_items.value) { lives[it]?.value }
        }
    }

    private fun liveOf(id: String): MutableStateFlow<TransferLive> = lives.getOrPut(id) {
        val t = _items.value.firstOrNull { it.id == id }
        MutableStateFlow(TransferLive(t?.doneBytes ?: 0, t?.totalBytes))
    }

    private fun workDirOf(id: String) = File(File(dir, "work"), id.filter { it.isLetterOrDigit() || it == '-' })

    private suspend fun update(persist: Boolean = true, change: (List<TransferItem>) -> List<TransferItem>) = mutex.withLock {
        val next = change(_items.value)
        if (next != _items.value) {
            _items.value = next
            if (persist) persist()
        }
        _summary.value = TransferScheduler.summary(next) { lives[it]?.value }
    }

    private fun persist() {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "transfers.json.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(TransferItem.serializer()), _items.value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): List<TransferItem> = runCatching {
        if (file.isFile) json.decodeFromString(ListSerializer(TransferItem.serializer()), file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun newId(): String = java.util.UUID.randomUUID().toString()

    private class Paused : CancellationException("paused")
    private class Held : CancellationException("held")
    private class Cancelled : CancellationException("cancelled")

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val TICK_MS = 500L
        private const val SAVE_EVERY_MS = 3_000L
        private const val DRIVE_LOOK_MS = 30_000L
        /** A transfer that lost its way this many times in a row stops and says so. */
        const val MAX_ATTEMPTS = 8

        private fun Exception.isNetwork(): Boolean {
            val n = this::class.simpleName.orEmpty()
            return "Timeout" in n || "Connect" in n || "Socket" in n || "UnresolvedAddress" in n || "ClosedReceiveChannel" in n
        }

        internal fun failureWords(e: Exception): String = when (e) {
            is TransferFailure -> e.message ?: "It couldn't be done."
            else -> "The connection kept dropping. Try again when the server is reachable."
        }
    }
}

/**
 * Shares a rate between transfers: each takes the bytes it is about to move and waits when the
 * bucket is empty. A [rate] of 0 means no limit, at no cost.
 */
class TokenBucket(private val clock: () -> Long) {
    @Volatile var rate: Long = 0
    private var tokens = 0.0
    private var at = 0L
    private val lock = Mutex()

    suspend fun take(bytes: Int) {
        val r = rate
        if (r <= 0) return
        val wait = lock.withLock {
            val now = clock()
            if (at == 0L) at = now
            tokens = minOf(r.toDouble(), tokens + (now - at) * r / 1000.0)
            at = now
            tokens -= bytes
            if (tokens >= 0) 0L else (-tokens * 1000 / r).toLong()
        }
        if (wait > 0) delay(wait)
    }
}
