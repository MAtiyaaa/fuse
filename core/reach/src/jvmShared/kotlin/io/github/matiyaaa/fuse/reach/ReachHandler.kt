package io.github.matiyaaa.fuse.reach

import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.romm.RommPlacement
import io.github.matiyaaa.fuse.sync.OpenTicket
import io.github.matiyaaa.fuse.sync.SyncApi
import io.github.matiyaaa.fuse.transfer.DeviceAway
import io.github.matiyaaa.fuse.transfer.ExpectedHash
import io.github.matiyaaa.fuse.transfer.RangedDownload
import io.github.matiyaaa.fuse.transfer.TransferFailure
import io.github.matiyaaa.fuse.transfer.TransferFiles
import io.github.matiyaaa.fuse.transfer.TransferHandler
import io.github.matiyaaa.fuse.transfer.TransferInterrupted
import io.github.matiyaaa.fuse.transfer.TransferIo
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual fun reachHandlers(host: ReachTransferHost): List<TransferHandler> = listOf(ReachHandler(host))

/**
 * Brings a game here from wherever is best ([SourceRanking]): each file from the best source that
 * answers, carrying on from where it stopped, checked against the hashes the game was chosen by,
 * and put in place only once every file is whole. A source that drops or sends the wrong bytes
 * gives way to the next; when the only sources are devices that are away, the game waits for one.
 */
class ReachHandler(private val host: ReachTransferHost, private val clock: () -> Long = System::currentTimeMillis) : TransferHandler {
    override val source = REACH_SOURCE

    override suspend fun run(item: TransferItem, io: TransferIo) {
        var job = ReachJob.decode(item.payload)
        val place = item.place ?: throw TransferFailure("Fuse doesn't know where this goes.", retryable = false)
        val root = File(io.resolve(place))
        val stage = stageOf(job, root)
        if (job.folder && root.exists() && root.list()?.isNotEmpty() == true) {
            throw TransferFailure("A folder named ${root.name} is already there, so it was left alone.", retryable = false)
        }
        val total = job.totalBytes
        val left = job.files.filter { it.path !in job.done }.sumOf { f -> (f.size - partOf(stage, f).let { if (it.isFile) it.length() else 0L }).coerceAtLeast(0) }
        val free = TransferFiles.freeSpace(root)
        if (free in 1 until left + SPARE) throw TransferFailure("There isn't room for this on ${place.volume?.label ?: "that drive"}: it needs ${gb(left)} and ${gb(free)} is free.")

        var done = job.files.filter { it.path in job.done }.sumOf { it.size }
        io.progress(done, total)
        val tickets = HashMap<String, OpenTicket?>()
        for (f in job.files) {
            if (f.path in job.done) continue
            val part = partOf(stage, f)
            val (from, speed) = fetchChecked(job, f, part, io, done, total, tickets) { next -> job = next; io.note(job.encode()) }
            job = job.copy(done = job.done + f.path, speeds = job.speeds + (from.key to speed))
            io.note(job.encode())
            done += f.size
            io.progress(done, total)
        }

        io.phase(TransferPhase.PLACING)
        val placed = if (job.folder) {
            // The game's folder appears whole, in one step.
            for (f in job.files) {
                val part = partOf(stage, f)
                if (part.isFile) TransferFiles.place(part, destOf(stage, f), replace = true)
            }
            root.delete()
            try {
                Files.move(stage.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(stage.toPath(), root.toPath())
            }
            job.files.map { destOf(root, it).path }
        } else {
            // The file that names the game goes last, so a scan never finds half of it.
            val order = job.files.drop(1) + job.files.take(1)
            order.map { f ->
                val part = partOf(stage, f)
                val dest = destOf(root, f)
                // Already moved before a restart: it is in place.
                if (!part.isFile && dest.isFile && dest.length() == f.size) dest.path else TransferFiles.place(part, dest).path
            }
        }
        io.phase(TransferPhase.FINISHING)
        host.landed(job, item, placed)
    }

    /**
     * [f] into [part] from the best source that has it and sends it right: the source used and
     * the speed it managed. Every source that can't (unreachable, dropped, wrong bytes) gives way
     * to the next; when none could, the transfer waits and tries again.
     */
    private suspend fun fetchChecked(
        job: ReachJob,
        f: ReachFile,
        part: File,
        io: TransferIo,
        before: Long,
        total: Long,
        tickets: MutableMap<String, OpenTicket?>,
        note: suspend (ReachJob) -> Unit,
    ): Pair<ReachSource, Long> {
        var current = job
        val ranked = SourceRanking.order(job.sources.map { candidate(job, it, f, tickets) })
        if (ranked.isEmpty()) {
            val away = job.sources.filter { it.kind == ReachSource.PEER }
            if (away.isNotEmpty() && job.sources.none { it.kind == ReachSource.ROMM && host.romm(it.id) != null }) {
                throw DeviceAway(away.joinToString(" or ") { it.name.ifBlank { "the other device" } })
            }
            throw TransferInterrupted("None of the places that have ${job.title} answer right now.")
        }
        var last: Exception? = null
        for (c in ranked) {
            val key = c.source.key
            // A part from another source carries on only when the file's hashes say which bytes it must be.
            val from = current.partFrom[f.path]
            if (part.isFile && from != null && from != key && f.sha1 == null && f.md5 == null) part.delete()
            if (from != key) {
                current = current.copy(partFrom = current.partFrom + (f.path to key))
                note(current)
            }
            try {
                val started = clock()
                val had = if (part.isFile) part.length() else 0L
                io.phase(TransferPhase.TRANSFERRING)
                when (c.source.kind) {
                    ReachSource.PEER -> fromPeer(c, f, part, io, before, total, tickets)
                    ReachSource.ROMM -> fromRomm(c, f, part, io, before, total)
                }
                io.phase(TransferPhase.VERIFYING)
                check(job, f, c.source, part)
                val ms = (clock() - started).coerceAtLeast(1)
                return c.source to ((part.length() - had).coerceAtLeast(0) * 1000 / ms)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Waiting) {
                // Every byte is here; only the source's own check of the file is still to come.
                throw TransferInterrupted(e.message ?: "Waiting", e)
            } catch (e: Exception) {
                last = e
            }
        }
        val failure = last
        if (failure is TransferFailure && !failure.retryable) throw failure
        throw TransferInterrupted(failure?.message ?: "The game couldn't be fetched just now.", failure)
    }

    /** How [s] can be reached now for [f]. */
    private suspend fun candidate(job: ReachJob, s: ReachSource, f: ReachFile, tickets: MutableMap<String, OpenTicket?>): Candidate {
        val speed = job.speeds[s.key] ?: 0
        return when (s.kind) {
            ReachSource.PEER -> {
                val peers = host.peers ?: return Candidate(s, Reach.NONE)
                val open = tickets.getOrPut(s.id) { runCatching { peers.openTicket(s.id, job.game, job.files.map { it.path }) }.getOrNull() } ?: return Candidate(s, Reach.NONE)
                // A device that answers directly is tried whatever the host last heard; through the
                // host only while the host hears from it (or it couldn't send anything).
                when {
                    peers.reachable(open.ticket) -> Candidate(s, Reach.PEER_LOCAL, speed)
                    host.relay && open.ticket.relay && host.online(s.id) -> Candidate(s, Reach.PEER_RELAY, speed)
                    else -> Candidate(s, Reach.NONE)
                }
            }
            ReachSource.ROMM -> {
                val client = host.romm(s.id) ?: return Candidate(s, Reach.NONE)
                if (f.romm == null) return Candidate(s, Reach.NONE)
                Candidate(s, if (client.route == NetRoute.REMOTE) Reach.ROMM_REMOTE else Reach.ROMM_LOCAL, speed)
            }
            else -> Candidate(s, Reach.NONE)
        }
    }

    private suspend fun fromPeer(c: Candidate, f: ReachFile, part: File, io: TransferIo, before: Long, total: Long, tickets: Map<String, OpenTicket?>) {
        val peers = host.peers ?: throw TransferInterrupted("Fuse Sync isn't reachable.")
        val open = tickets[c.source.id] ?: throw TransferInterrupted("${c.source.name} didn't give leave.")
        val direct = c.reach == Reach.PEER_LOCAL
        val piece = if (direct) DIRECT_PIECE else SyncApi.RELAY_PIECE
        withContext(Dispatchers.IO) {
            part.parentFile?.mkdirs()
            if (part.isFile && part.length() > f.size) part.delete()
            var have = if (part.isFile) part.length() else 0L
            io.progress(before + have, total)
            FileOutputStream(part, true).use { out ->
                while (have < f.size) {
                    val want = minOf(piece, f.size - have)
                    val got = peers.fetch(open, f.path, have, want, direct) { buf, n, _ ->
                        io.throttle(n)
                        try {
                            out.write(buf, 0, n)
                        } catch (e: java.io.IOException) {
                            throw RangedDownload.writeFailure(part, e)
                        }
                        have += n
                        if (have > f.size) throw TransferFailure("${c.source.name} sent more than the file's size.")
                        io.progress(before + have, total)
                    }
                    if (got == 0L) throw TransferInterrupted("${c.source.name} stopped sending.")
                }
                out.fd.sync()
            }
        }
    }

    private suspend fun fromRomm(c: Candidate, f: ReachFile, part: File, io: TransferIo, before: Long, total: Long) {
        val client = host.romm(c.source.id) ?: throw TransferInterrupted("Fuse RomM is signed out.")
        val file = f.romm ?: throw TransferInterrupted("RomM doesn't have this file.")
        RangedDownload.fetch(host.http, { client.fileUrl(client.base(), c.source.romId, file) }, part, f.size.takeIf { it > 0 }, io, doneBefore = before, totalOverall = total) {
            client.authorize(this)
        }
    }

    /** A source's file is whole but its own check of it is still to come: nothing is wrong, it waits. */
    private class Waiting(message: String) : Exception(message)

    /**
     * Checks [part] by size and by the file's hashes: those the game was chosen by, else those the
     * source gives once it has read the file (another device reads a file as soon as it is asked
     * for it). Wrong bytes are removed and the next source is tried.
     */
    private suspend fun check(job: ReachJob, f: ReachFile, from: ReachSource, part: File) {
        var sha1 = f.sha1
        var md5 = f.md5
        if (sha1 == null && md5 == null && from.kind == ReachSource.PEER) {
            for (i in 0 until HASH_WAITS) {
                val got = host.hashesFrom(from.id, job.game, f.path)
                if (got != null && (got.first != null || got.second != null)) {
                    sha1 = got.first
                    md5 = got.second
                    break
                }
                delay(HASH_WAIT_MS)
            }
            if (sha1 == null && md5 == null) {
                if (part.length() != f.size) part.delete()
                throw Waiting("Waiting for ${from.name.ifBlank { "the other device" }} to finish checking ${f.path}")
            }
        }
        if (sha1 == null && md5 == null && from.kind == ReachSource.ROMM) {
            sha1 = f.romm?.sha1
            md5 = f.romm?.md5
        }
        withContext(Dispatchers.IO) {
            TransferFiles.verify(part, f.size, sha1?.let { ExpectedHash("sha1", it) } ?: md5?.let { ExpectedHash("md5", it) })
        }
    }

    override suspend fun discard(item: TransferItem, io: TransferIo) {
        val job = runCatching { ReachJob.decode(item.payload) }.getOrNull() ?: return
        val place = item.place ?: return
        val root = runCatching { File(io.resolve(place)) }.getOrNull() ?: return
        val stage = stageOf(job, root)
        for (f in job.files) partOf(stage, f).delete()
        if (job.folder) stage.deleteRecursively()
    }

    private fun stageOf(job: ReachJob, root: File): File = if (job.folder) File(root.parentFile, ".${root.name}.fuse-download") else root

    /** Where [f] goes under [base]: its place inside the game, each part made safe, never outside [base]. */
    private fun destOf(base: File, f: ReachFile): File {
        val parts = f.path.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }.map(RommPlacement::safeName)
        if (parts.isEmpty()) throw TransferFailure("A file of this game has no name.", retryable = false)
        return parts.fold(base) { dir, p -> File(dir, p) }
    }

    private fun partOf(stage: File, f: ReachFile): File = TransferFiles.partFor(destOf(stage, f))

    private fun gb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)

    companion object {
        /** Left free on a drive after a download, so it isn't filled to the last byte. */
        const val SPARE = 64L * 1024 * 1024

        /** A piece asked of a device at home in one go. */
        const val DIRECT_PIECE = 64L * 1024 * 1024

        private const val HASH_WAITS = 24
        private const val HASH_WAIT_MS = 5_000L
    }
}
